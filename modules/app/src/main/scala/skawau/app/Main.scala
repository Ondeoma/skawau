package skawau.app

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import doobie.implicits.*
import fs2.io.file.Path
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.staticcontent.*
import skawau.api.*
import sbirds.auth.{OidcClient, OidcConfig}
import skawau.core.Actor
import skawau.db.*
import sbirds.observe.{HttpClientTelemetry, HttpTelemetry, Metrics, RumRoutes, Telemetry}
import sbirds.http.Errors
import sbirds.observe.Log
import skawau.core.ErrorCode
import sbirds.storage.S3Config
import skawau.storage.S3BlobStore

import scala.concurrent.duration.*

final case class AppConfig(
  port: Int,
  db: DbConfig,
  oidc: Option[OidcConfig],
  api: ApiConfig,
  staticDir: Option[String],
  s3: Option[S3Config],
  nodeId: String)

object Main extends IOApp.Simple {

  // ログにトレースの ID・操作したユーザーを載せるため、IOLocal の文脈を Java のライブラリから見えるようにする
  // （IORuntime を作る前に設定する必要がある. オブジェクトの初期化は main より先に動く）
  Telemetry.prepare()

  private val version =
    Option(getClass.getPackage.getImplementationVersion).orElse(sys.env.get("APP_VERSION")).getOrElse("dev")

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.slf4j.Slf4jFactory.create[IO]

  def run: IO[Unit] =
    for {
      cfg <- Config.load
      n <- migrateWithRetry(cfg)
      _ <- sbirds.observe.Log.println(s"[skawau] database migrated ($n migration(s) applied); node ${cfg.nodeId}")
      _ <- (for {
        _ <- Telemetry.resource("skawau", version, runtime)
        xa <- Database.transactor(cfg.db)
        _ <- gauges(xa)
        // 外への呼び出し（OIDC・Document Server・プッシュのサービス）もトレースに出す（traceparent を付けて送る）
        http <- EmberClientBuilder.default[IO].withTimeout(
          55.seconds
        ).withIdleConnectionTime(60.seconds).build.map(HttpClientTelemetry(_))
        users = new UserStore(xa)
        nodes = new NodeStore(xa)
        shares = new ShareStore(xa)
        activity = new ActivityStore(xa)
        lock = new JobLock(xa, cfg.nodeId)
        oidc <- Resource.eval(cfg.oidc.traverse(OidcClient.create(_, http)))
        blobs <- blobStore(cfg)
        sess = new SessionAuth(users, cfg.api)
        files = new FileService(nodes, users, blobs, cfg.api)
        office = new OfficeService(files, http, cfg.api)
        pushStore = new sbirds.push.PushStore(xa)
        push <- Resource.eval(sbirds.push.PushService.create(pushStore, http, cfg.api.publicUrl, "skawau"))
        notifier = new Notifier(activity, Some(push))
        topic <- Resource.eval(fs2.concurrent.Topic[IO, FeedItem])
        feed = new EventFeed(activity, topic)
        _ <- Resource.eval(startupNotes(cfg))
        loops = List[IO[Unit]](
          feed.loop,
          blobLoop(nodes, blobs),
          trashLoop(nodes, lock, cfg),
          purgeLoop(users, activity, lock)
        )
        _ <- loops.traverse_(_.background)
        rest =
          healthRoutes(xa, cfg.nodeId) <+>
            new AuthRoutes(oidc, users, activity, office, sess, cfg.api).routes <+>
            new UserRoutes(users, nodes, activity, files, blobs, sess).routes <+>
            new OfficeRoutes(office, files, nodes, shares, sess).routes <+>
            new FileRoutes(files, nodes, shares, activity, users, notifier, sess, cfg.api).routes <+>
            new PublicRoutes(files, nodes, shares, office, notifier, sess, cfg.api).routes <+>
            new StreamRoutes(feed, sess).routes <+>
            new DavRoutes(files, nodes, sess).routes <+>
            new OAuthRoutes(new OAuthStore(xa), nodes, activity, sess, cfg.api).routes <+>
            RumRoutes.routes <+>
            new PushRoutes(pushStore, push, sess).routes
        // MCP のツールは REST API を内部で呼ぶ
        all = rest <+> new McpRoutes(rest, sess, cfg.api.publicUrl).routes
        app = securityHeaders(cfg.staticDir.fold(all)(d => all <+> spa(d)))
        // バックエンドの要求をトレース・メトリクス・アクセスログにする（画面のファイル・/healthz は除く）
        httpApp <- HttpTelemetry(
          Errors.handle(app.orNotFound, ErrorCode.Unexpected),
          List("/api", "/auth", "/mcp", "/oauth", "/dav", "/onlyoffice")
        )
        _ <- EmberServerBuilder.default[IO]
          .withHost(ipv4"0.0.0.0")
          .withPort(Port.fromInt(cfg.port).getOrElse(port"8080"))
          .withShutdownTimeout(5.seconds)
          .withIdleTimeout(5.minutes)
          .withHttpApp(httpApp)
          .build
        _ <- Resource.eval(sbirds.observe.Log.println(s"[skawau] listening on :${cfg.port} (${cfg.api.publicUrl})"))
      } yield ()).useForever
    } yield ()

  /** DB（Patroni のプライマリ）が使えるまで待ってからマイグレーションする。複数ノード同時起動でも Flyway のロックで1回だけ適用される。 */
  private def migrateWithRetry(
    cfg: AppConfig,
    attempt: Int = 1
  ): IO[Int] =
    Database.migrate(cfg.db).handleErrorWith { e =>
      if attempt >= 60 then IO.raiseError(e)
      else
        Log.report(ErrorCode.DatabaseNotReady, s"${e.getMessage.take(120)}; retry $attempt/60") *>
          IO.sleep(2.seconds) *> migrateWithRetry(cfg, attempt + 1)
    }

  /** ロードバランサー用のヘルスチェック。DB（プライマリ）に書けない状態なら 503。 */
  private def healthRoutes(
    xa: doobie.Transactor[IO],
    node: String
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" =>
      sql"select not pg_is_in_recovery()".query[Boolean].unique.transact(xa).timeout(3.seconds).attempt.flatMap {
        case Right(true) => text(Status.Ok, s"ok $node")
        case Right(false) => text(Status.ServiceUnavailable, "database is read-only (not primary)")
        case Left(e) => Log.report(ErrorCode.HealthCheckFailed, "", e) *>
            text(Status.ServiceUnavailable, s"database unavailable: ${e.getMessage}")
      }
  }

  private def text(
    status: Status,
    body: String
  ): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(body)(using EntityEncoder.stringEncoder))

  private def blobStore(
    cfg: AppConfig
  ): Resource[IO, Option[skawau.storage.BlobStore]] = cfg.s3 match {
    case Some(c) => S3BlobStore.resource(c).evalTap(_.ensureBucket).map(Some(_))
    case None =>
      Resource.eval(sbirds.observe.Log.println("[skawau] WARNING: S3_ENDPOINT が未設定のため、ファイルを保存できません").as(None))
  }

  private def startupNotes(
    cfg: AppConfig
  ): IO[Unit] =
    IO.whenA(
      cfg.oidc.isEmpty
    )(sbirds.observe.Log.println("[skawau] WARNING: OIDC が未設定です。開発用のログイン（DEV_LOGIN）を使います。本番では使わないでください")) *>
      IO.whenA(
        cfg.api.office.isEmpty
      )(sbirds.observe.Log.println("[skawau] ONLYOFFICE は未設定です（ONLYOFFICE_URL）。Office 文書はダウンロードだけできます"))

  /** DB から定期的に読むメトリクス（skorimako のダッシュボード・アラート用）。 */
  private def gauges(
    xa: doobie.Transactor[IO]
  ): Resource[IO, Unit] = {
    def q(
      sqlText: doobie.Fragment
    ): IO[Long] = sqlText.query[Long].unique.transact(xa)
    List(
      Metrics.gauge("skawau.users.total", "有効なユーザーの数")(q(sql"select count(*) from app_user where not disabled")),
      Metrics.gauge(
        "skawau.users.active_24h",
        "24時間以内にログインしたユーザーの数"
      )(q(sql"select count(*) from app_user where last_login_at > now() - interval '24 hours'")),
      Metrics.gauge("skawau.files.total", "ファイルの数（ごみ箱の中を含む）")(q(sql"select count(*) from node where not is_dir")),
      Metrics.gauge("skawau.storage.used_bytes", "使用量の合計（バイト. 以前の版を含む）")(q(
        sql"select (select coalesce(sum(size), 0) from node where not is_dir) + (select coalesce(sum(size), 0) from file_version)"
      )),
      Metrics.gauge(
        "skawau.trash.items",
        "ごみ箱に入っているもの（起点）の数"
      )(q(sql"select count(*) from node where deleted_at is not null")),
      Metrics.gauge(
        "skawau.shares.links",
        "公開リンクの数（期限切れを除く）"
      )(q(sql"select count(*) from share where kind = 'link' and (expires_at is null or expires_at >= current_date)")),
      Metrics.gauge("skawau.blob_trash.pending", "S3 から消す待ちの中身の数")(q(sql"select count(*) from blob_trash"))
    ).sequence_
  }

  /** 完全に削除したファイル・古い版の S3 の中身を消す（全ノードで動かし、SKIP LOCKED で取り合う）。 */
  private def blobLoop(
    nodes: NodeStore,
    blobs: Option[skawau.storage.BlobStore]
  ): IO[Unit] =
    blobs.fold(IO.unit)(b =>
      Periodic.run(30.seconds, 30.seconds, ErrorCode.BlobCleanupFailed)(nodes.drainBlobTrash(b.delete))
    )

  /** 期限（TRASH_DAYS）を過ぎたごみ箱の中身を完全に削除する。 */
  private def trashLoop(
    nodes: NodeStore,
    lock: JobLock,
    cfg: AppConfig
  ): IO[Unit] =
    Periodic.run(3.minutes, 1.hour, ErrorCode.TrashExpiryFailed)(lock.exclusive("trash-expiry", 50.minutes)(
      Telemetry.span("trash.expiry")(nodes.expiredTrash(cfg.api.trashDays, 500).flatMap(_.traverse_(n =>
        nodes.purge(n, Actor(None, "system"))
      )))
    ))

  private def purgeLoop(
    users: UserStore,
    activity: ActivityStore,
    lock: JobLock
  ): IO[Unit] =
    Periodic.run(10.minutes, 1.hour, ErrorCode.PurgeFailed)(lock.exclusive("purge", 50.minutes)(
      Telemetry.span("purge")(IO.realTimeInstant.flatMap(users.purgeExpired) *> activity.purgeOld(180).void)
    ))

  /**
   * すべての応答に付けるセキュリティのヘッダー（個別に付けたものはそのまま）。
   * 画面を他のサイトの iframe に埋め込ませない（クリックジャッキング）。PDF のプレビューは同じオリジンの iframe なので SAMEORIGIN。
   */
  private def securityHeaders(
    routes: HttpRoutes[IO]
  ): HttpRoutes[IO] = routes.map { r =>
    def absent(
      n: String
    ) = r.headers.get(org.typelevel.ci.CIString(n)).isEmpty
    val hs = List(
      "X-Content-Type-Options" -> "nosniff",
      "X-Frame-Options" -> "SAMEORIGIN",
      "Referrer-Policy" -> "same-origin",
      "Content-Security-Policy" -> "frame-ancestors 'self'; base-uri 'self'; object-src 'none'"
    ).filter(
      (
        n,
        _
      ) => absent(n)
    )
    r.putHeaders(hs.map(h => h: Header.ToRaw)*)
  }

  /** ビルド済みの画面の配信。存在しないパスは index.html（SPA）。 */
  private def spa(
    dir: String
  ): HttpRoutes[IO] = {
    val files = fileService[IO](org.http4s.server.staticcontent.FileService.Config(dir))
    val index = HttpRoutes.of[IO] {
      case req @ GET -> _
          if !Set("api", "auth", "dav", "onlyoffice", "oauth", "mcp", "healthz").exists(p =>
            req.pathInfo.renderString.startsWith("/" + p)
          ) =>
        StaticFile.fromPath(Path(dir) / "index.html", Some(req)).getOrElseF(NotFound())
    }
    HttpRoutes[IO] { req =>
      val cc =
        if req.pathInfo.renderString.startsWith("/assets/") then "public, max-age=31536000, immutable" else "no-cache"
      (files <+> index).run(req).map(_.putHeaders("Cache-Control" -> cc))
    }
  }
}
