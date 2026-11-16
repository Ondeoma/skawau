package skawau.app

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import doobie.implicits.*
import fs2.io.file.Path
import org.http4s.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.staticcontent.*
import skawau.api.*
import skawau.auth.{OidcClient, OidcConfig}
import skawau.core.Actor
import skawau.db.*
import skawau.shared.ErrorDto
import skawau.storage.{S3BlobStore, S3Config}

import scala.concurrent.duration.*

final case class AppConfig(
    port: Int,
    db: DbConfig,
    oidc: Option[OidcConfig],
    api: ApiConfig,
    staticDir: Option[String],
    s3: Option[S3Config],
    nodeId: String
)

/** 設定は環境変数から読む。 */
object Config:
  private def opt(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)
  private def int(name: String): Option[Int]    = opt(name).flatMap(_.toIntOption)
  private def long(name: String): Option[Long]  = opt(name).flatMap(_.toLongOption)
  private def enabled(name: String): Boolean    = opt(name).exists(_.equalsIgnoreCase("true"))
  private def required(name: String): Either[String, String] = opt(name).toRight(s"環境変数 $name を設定してください")

  /** ONLYOFFICE_URL を設定したときだけ有効。JWT の秘密は Document Server の JWT_SECRET と同じ値。 */
  private def office(pub: String): Either[String, Option[OfficeConfig]] =
    opt("ONLYOFFICE_URL") match
      case None => Right(None)
      case Some(url) =>
        opt("ONLYOFFICE_JWT_SECRET") match
          case None => Left("ONLYOFFICE_URL を設定したときは ONLYOFFICE_JWT_SECRET（Document Server の JWT_SECRET と同じ値）も設定してください")
          case Some(sec) if sec.length < 32 => Left("ONLYOFFICE_JWT_SECRET は32文字以上にしてください（推測されると保存の知らせを偽造されます）")
          case Some(sec) =>
            Right(Some(OfficeConfig(url.stripSuffix("/"), opt("ONLYOFFICE_INTERNAL_URL").getOrElse(url).stripSuffix("/"),
              opt("ONLYOFFICE_APP_URL").getOrElse(pub).stripSuffix("/"), sec)))

  /** OAUTH_CLIENTS: [{"id":"skea","secret":"…","redirectUri":"https://…/callback","name":"skea","scopes":["read"]}]（scopes は省略すると read だけ） */
  private def oauthClients: Either[String, List[OAuthClient]] =
    opt("OAUTH_CLIENTS").fold(Right(Nil)) { s =>
      io.circe.parser.parse(s).flatMap(_.as[List[io.circe.Json]]).left.map(_ => "OAUTH_CLIENTS は JSON の配列で指定してください").flatMap(_.traverse { j =>
        val c = j.hcursor
        (c.get[String]("id"), c.get[String]("secret"), c.get[String]("redirectUri")).tupled.toOption
          .filter((id, sec, uri) => id.nonEmpty && sec.length >= 16 && (uri.startsWith("https://") || uri.startsWith("http://localhost")))
          .map((id, sec, uri) => OAuthClient(id, sec, uri, c.get[String]("name").getOrElse(id), c.get[List[String]]("scopes").getOrElse(List("read"))))
          .filter(c => c.scopes.nonEmpty && c.scopes.forall(Set("read", "write")))
          .toRight("OAUTH_CLIENTS の各要素には id・secret（16文字以上）・redirectUri（https://）を指定してください（scopes は read・write）")
      })
    }

  def load: IO[AppConfig] = IO.fromEither(
    (for
      dbUrl  <- required("DATABASE_URL")
      dbUser <- required("DATABASE_USER")
      dbPass <- required("DATABASE_PASSWORD")
      pub    <- required("PUBLIC_URL").map(_.stripSuffix("/"))
      devLogin = enabled("DEV_LOGIN")
      oidc <- (opt("OIDC_ISSUER"), opt("OIDC_CLIENT_ID"), opt("OIDC_CLIENT_SECRET")) match
                case (Some(i), Some(c), Some(s)) =>
                  Right(Some(OidcConfig(i, c, s, s"$pub/auth/callback", opt("OIDC_GROUPS_CLAIM").getOrElse("groups"))))
                case (None, None, None) if devLogin => Right(None)
                case _ => Left("OIDC_ISSUER・OIDC_CLIENT_ID・OIDC_CLIENT_SECRET を設定してください（開発中は DEV_LOGIN=true で省略できます）")
      secret <- opt("APP_SECRET") match
                  case Some(s) if s.length >= 16 => Right(s)
                  case Some(_) => Left("APP_SECRET は16文字以上にしてください")
                  // 固定の値は、開発用のログインで OIDC も https もないときだけ（本番の設定の誤りで使われないように）
                  case None if devLogin && oidc.isEmpty && !pub.startsWith("https://") => Right("skawau-development-secret")
                  case None => Left("APP_SECRET（16文字以上の秘密の値）を設定してください")
      off <- office(pub)
      clients <- oauthClients
    yield AppConfig(
      port = int("HTTP_PORT").getOrElse(8080),
      db = DbConfig(dbUrl, dbUser, dbPass, int("DATABASE_POOL_SIZE").getOrElse(10)),
      oidc = oidc,
      api = ApiConfig(
        adminGroups = opt("ADMIN_GROUPS").map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty),
        cookieSecure = pub.startsWith("https://"),
        publicUrl = pub,
        appSecret = secret,
        devLogin = devLogin,
        maxUploadBytes = long("MAX_UPLOAD_MB").getOrElse(10240L) * 1024 * 1024,
        defaultQuota = long("DEFAULT_QUOTA_MB").filter(_ > 0).map(_ * 1024 * 1024),
        versionsKept = int("VERSIONS_KEPT").getOrElse(30),
        trashDays = int("TRASH_DAYS").getOrElse(30),
        office = off,
        oauthClients = clients
      ),
      staticDir = opt("STATIC_DIR"),
      s3 = opt("S3_ENDPOINT").map(ep => S3Config(ep, opt("S3_REGION").getOrElse("us-east-1"), opt("S3_BUCKET").getOrElse("skawau"),
        opt("S3_ACCESS_KEY").getOrElse(""), opt("S3_SECRET_KEY").getOrElse(""), !opt("S3_PATH_STYLE").contains("false"))),
      nodeId = opt("NODE_ID").getOrElse(java.net.InetAddress.getLocalHost.getHostName + "-" + java.util.UUID.randomUUID().toString.take(8))
    )).left.map(new IllegalArgumentException(_))
  )

object Main extends IOApp.Simple:

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.slf4j.Slf4jFactory.create[IO]

  def run: IO[Unit] =
    for
      cfg <- Config.load
      n   <- migrateWithRetry(cfg)
      _   <- IO.println(s"[skawau] database migrated ($n migration(s) applied); node ${cfg.nodeId}")
      _ <- (for
        xa    <- Database.transactor(cfg.db)
        http  <- EmberClientBuilder.default[IO].withTimeout(55.seconds).withIdleConnectionTime(60.seconds).build
        users    = new UserStore(xa)
        nodes    = new NodeStore(xa)
        shares   = new ShareStore(xa)
        activity = new ActivityStore(xa)
        lock     = new JobLock(xa, cfg.nodeId)
        oidc  <- Resource.eval(cfg.oidc.traverse(OidcClient.create(_, http)))
        blobs <- blobStore(cfg)
        sess     = new SessionAuth(users, cfg.api)
        files    = new FileService(nodes, users, blobs, cfg.api)
        office   = new OfficeService(files, http, cfg.api)
        topic   <- Resource.eval(fs2.concurrent.Topic[IO, FeedItem])
        feed     = new EventFeed(activity, topic)
        _ <- Resource.eval(startupNotes(cfg))
        loops = List[IO[Unit]](feed.loop, blobLoop(nodes, blobs), trashLoop(nodes, lock, cfg), purgeLoop(users, activity, lock))
        _ <- loops.traverse_(_.background)
        rest =
          healthRoutes(xa, cfg.nodeId) <+>
            new AuthRoutes(oidc, users, activity, office, sess, cfg.api).routes <+>
            new UserRoutes(users, nodes, activity, files, blobs, sess).routes <+>
            new OfficeRoutes(office, files, nodes, shares, sess).routes <+>
            new FileRoutes(files, nodes, shares, activity, users, sess, cfg.api).routes <+>
            new PublicRoutes(files, nodes, shares, activity, office, sess, cfg.api).routes <+>
            new StreamRoutes(feed, sess).routes <+>
            new DavRoutes(files, nodes, sess).routes <+>
            new OAuthRoutes(new OAuthStore(xa), nodes, activity, sess, cfg.api).routes
        // MCP のツールは REST API を内部で呼ぶ
        all = rest <+> new McpRoutes(rest, sess, cfg.api.publicUrl).routes
        app = securityHeaders(cfg.staticDir.fold(all)(d => all <+> spa(d)))
        _ <- EmberServerBuilder.default[IO]
               .withHost(ipv4"0.0.0.0")
               .withPort(Port.fromInt(cfg.port).getOrElse(port"8080"))
               .withShutdownTimeout(5.seconds)
               .withIdleTimeout(5.minutes)
               .withHttpApp(ErrorHandling.Custom.recoverWith(app.orNotFound)(handler))
               .build
        _ <- Resource.eval(IO.println(s"[skawau] listening on :${cfg.port} (${cfg.api.publicUrl})"))
      yield ()).useForever
    yield ()

  /** DB（Patroni のプライマリ）が使えるまで待ってからマイグレーションする。複数ノード同時起動でも Flyway のロックで1回だけ適用される。 */
  private def migrateWithRetry(cfg: AppConfig, attempt: Int = 1): IO[Int] =
    Database.migrate(cfg.db).handleErrorWith { e =>
      if attempt >= 60 then IO.raiseError(e)
      else IO.println(s"[skawau] database not ready (${e.getMessage.take(120)}); retry $attempt/60") *> IO.sleep(2.seconds) *> migrateWithRetry(cfg, attempt + 1)
    }

  /** ロードバランサー用のヘルスチェック。DB（プライマリ）に書けない状態なら 503。 */
  private def healthRoutes(xa: doobie.Transactor[IO], node: String): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "healthz" =>
      sql"select not pg_is_in_recovery()".query[Boolean].unique.transact(xa).timeout(3.seconds).attempt.flatMap {
        case Right(true)  => text(Status.Ok, s"ok $node")
        case Right(false) => text(Status.ServiceUnavailable, "database is read-only (not primary)")
        case Left(e)      => text(Status.ServiceUnavailable, s"database unavailable: ${e.getMessage}")
      }
  }

  private def text(status: Status, body: String): IO[Response[IO]] =
    IO.pure(Response[IO](status).withEntity(body)(using EntityEncoder.stringEncoder))

  private def blobStore(cfg: AppConfig): Resource[IO, Option[skawau.storage.BlobStore]] = cfg.s3 match
    case Some(c) => S3BlobStore.resource(c).evalTap(_.ensureBucket).map(Some(_))
    case None    => Resource.eval(IO.println("[skawau] WARNING: S3_ENDPOINT が未設定のため、ファイルを保存できません").as(None))

  private def startupNotes(cfg: AppConfig): IO[Unit] =
    IO.whenA(cfg.oidc.isEmpty)(IO.println("[skawau] WARNING: OIDC が未設定です。開発用のログイン（DEV_LOGIN）を使います。本番では使わないでください")) *>
      IO.whenA(cfg.api.office.isEmpty)(IO.println("[skawau] ONLYOFFICE は未設定です（ONLYOFFICE_URL）。Office 文書はダウンロードだけできます"))

  private val handler: PartialFunction[Throwable, IO[Response[IO]]] = { case e =>
    IO.println(s"[skawau] unhandled error: $e") *> InternalServerError(ErrorDto("サーバーでエラーが発生しました"))
  }

  /** 完全に削除したファイル・古い版の S3 の中身を消す（全ノードで動かし、SKIP LOCKED で取り合う）。 */
  private def blobLoop(nodes: NodeStore, blobs: Option[skawau.storage.BlobStore]): IO[Unit] =
    blobs.fold(IO.unit)(b => Periodic.run(30.seconds, 30.seconds)(e => s"[skawau] blob cleanup failed: $e")(nodes.drainBlobTrash(b.delete)))

  /** 期限（TRASH_DAYS）を過ぎたごみ箱の中身を完全に削除する。 */
  private def trashLoop(nodes: NodeStore, lock: JobLock, cfg: AppConfig): IO[Unit] =
    Periodic.run(3.minutes, 1.hour)(e => s"[skawau] trash expiry failed: $e")(lock.exclusive("trash-expiry", 50.minutes)(
      nodes.expiredTrash(cfg.api.trashDays, 500).flatMap(_.traverse_(n => nodes.purge(n, Actor(None, "system"))))))

  private def purgeLoop(users: UserStore, activity: ActivityStore, lock: JobLock): IO[Unit] =
    Periodic.run(10.minutes, 1.hour)(e => s"[skawau] purge failed: $e")(lock.exclusive("purge", 50.minutes)(
      IO.realTimeInstant.flatMap(users.purgeExpired) *> activity.purgeOld(180).void))

  /**
   * すべての応答に付けるセキュリティのヘッダー（個別に付けたものはそのまま）。
   * 画面を他のサイトの iframe に埋め込ませない（クリックジャッキング）。PDF のプレビューは同じオリジンの iframe なので SAMEORIGIN。
   */
  private def securityHeaders(routes: HttpRoutes[IO]): HttpRoutes[IO] = routes.map { r =>
    def absent(n: String) = r.headers.get(org.typelevel.ci.CIString(n)).isEmpty
    val hs = List(
      "X-Content-Type-Options"  -> "nosniff",
      "X-Frame-Options"         -> "SAMEORIGIN",
      "Referrer-Policy"         -> "same-origin",
      "Content-Security-Policy" -> "frame-ancestors 'self'; base-uri 'self'; object-src 'none'"
    ).filter((n, _) => absent(n))
    r.putHeaders(hs.map(h => h: Header.ToRaw)*)
  }

  /** ビルド済みの画面の配信。存在しないパスは index.html（SPA）。 */
  private def spa(dir: String): HttpRoutes[IO] =
    val files = fileService[IO](org.http4s.server.staticcontent.FileService.Config(dir))
    val index = HttpRoutes.of[IO] {
      case req @ GET -> _ if !Set("api", "auth", "dav", "onlyoffice", "oauth", "mcp", "healthz").exists(p => req.pathInfo.renderString.startsWith("/" + p)) =>
        StaticFile.fromPath(Path(dir) / "index.html", Some(req)).getOrElseF(NotFound())
    }
    HttpRoutes[IO] { req =>
      val cc = if req.pathInfo.renderString.startsWith("/assets/") then "public, max-age=31536000, immutable" else "no-cache"
      (files <+> index).run(req).map(_.putHeaders("Cache-Control" -> cc))
    }
