package skawau.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.{`Content-Length`, `Content-Type`, Location}
import fs2.io.file.Files
import skawau.storage.BlobStore
import skawau.auth.{ApiKeys, Crypto, OidcClient}
import skawau.core.*
import skawau.db.*
import skawau.shared.*

import java.time.Instant
import java.util.UUID

/** ログイン（OIDC・開発用）・ログアウト・画面の設定。 */
final class AuthRoutes(oidc: Option[OidcClient], users: UserStore, activity: ActivityStore, office: OfficeService, val sessions: SessionAuth,
    cfg: ApiConfig) extends RouteSupport:

  /** ログイン後に戻る先（同じサイトのパスだけ）。 */
  private def safeReturn(s: Option[String]): String =
    s.filter(p => p.startsWith("/") && !p.startsWith("//") && !p.startsWith("/\\")).getOrElse("/")

  private def redirect(to: String, cookie: Option[ResponseCookie] = None): IO[Response[IO]] =
    val r = Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(to)))
    IO.pure(cookie.fold(r)(r.addCookie))

  private def loggedIn(u: User, via: String): IO[ResponseCookie] =
    sessions.issue(u).flatTap(_ => activity.audit(Some(u.id), via, "auth.login", None, Json.obj()))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "auth" / "login" =>
      val back = safeReturn(param(req, "returnTo"))
      oidc match
        case Some(o) =>
          val state = Crypto.randomToken(); val verifier = Crypto.randomToken(48); val nonce = Crypto.randomToken()
          users.saveLoginState(state, verifier, nonce, back) *> o.authorizationUri(state, verifier, nonce).flatMap(u => redirect(u.renderString))
        case None if cfg.devLogin => redirect(s"/login?returnTo=${java.net.URLEncoder.encode(back, "UTF-8")}")
        case None                 => err(Status.ServiceUnavailable, "OIDC が設定されていません")

    case req @ GET -> Root / "auth" / "callback" =>
      (oidc, param(req, "code"), param(req, "state")) match
        case (Some(o), Some(code), Some(state)) =>
          users.takeLoginState(state).flatMap {
            case None => err(Status.BadRequest, "ログインの有効期限が切れました。もう一度ログインしてください")
            case Some((verifier, nonce, back)) =>
              o.authenticate(code, verifier, nonce, Instant.now()).attempt.flatMap {
                case Left(e) =>
                  activity.audit(None, "web", "auth.denied", None, Json.obj("error" -> e.getMessage.asJson)) *>
                    err(Status.Unauthorized, s"ログインできませんでした: ${e.getMessage}")
                case Right(id) =>
                  for
                    u <- users.upsert(id.sub, id.name, id.email, id.picture)
                    r <- if u.disabled then err(Status.Forbidden, "このユーザーは無効にされています")
                         else users.syncGroups(u.id, id.groups) *> loggedIn(u, "web").flatMap(c => redirect(back, Some(c)))
                  yield r
              }
          }
        case (None, _, _) => err(Status.ServiceUnavailable, "OIDC が設定されていません")
        case _            => err(Status.BadRequest, "code と state が必要です")

    case req @ POST -> Root / "auth" / "logout" =>
      sessions.revoke(req).flatMap(c => Ok(Json.obj("redirect" -> "/".asJson)).map(_.addCookie(c)))

    // 開発用: 名前だけでログインする（DEV_LOGIN=true のときだけ）
    case req @ POST -> Root / "auth" / "dev-login" if cfg.devLogin && oidc.isEmpty =>
      body[Json](req) { j =>
        val c     = j.hcursor
        val name  = c.get[String]("name").getOrElse("").trim
        val admin = c.get[Boolean]("admin").getOrElse(false)
        val groups = c.get[List[String]]("groups").getOrElse(Nil)
        if name.isEmpty then err(Status.BadRequest, "名前を入力してください")
        else
          for
            u0 <- users.upsert(s"dev:$name", name, c.get[String]("email").toOption.filter(_.contains("@")), None)
            _  <- users.update(u0.id, Option.when(admin)(true), None, None)
            _  <- users.syncGroups(u0.id, groups)
            u  <- users.find(u0.id).map(_.getOrElse(u0))
            ck <- loggedIn(u, "web")
            r  <- Ok(Json.obj("redirect" -> safeReturn(c.get[String]("returnTo").toOption).asJson)).map(_.addCookie(ck))
          yield r
      }

    case GET -> Root / "api" / "client-config" =>
      Ok(ClientConfigDto(cfg.devLogin && oidc.isEmpty, cfg.version, office.info, s"${cfg.publicUrl}/dav/", s"${cfg.publicUrl}/mcp", cfg.maxUploadBytes, cfg.versionsKept,
        cfg.trashDays))
  }

/** 自分の情報・APIキー・通知、管理画面（ユーザー・監査ログ・統計）。 */
final class UserRoutes(users: UserStore, nodes: NodeStore, activity: ActivityStore, files: FileService, blobs: Option[BlobStore],
    val sessions: SessionAuth) extends RouteSupport:

  private val avatarTypes    = Set("image/png", "image/jpeg", "image/gif", "image/webp")
  private val avatarMaxBytes = 2L * 1024 * 1024

  /** アイコンを差し替える（URL は画像を変えるたびに変え、ブラウザのキャッシュを効かせる）。 */
  private def replaceAvatar(uid: UUID, key: Option[String]): IO[Unit] =
    users.setAvatar(uid, key, key.map(_ => s"/api/users/$uid/avatar?v=${Crypto.randomToken(6)}"))

  /** 本文（画像）を一時ファイルに受け取って S3 に置く。 */
  private def storeAvatar(s: AuthSession, store: BlobStore, ct: String, body: fs2.Stream[IO, Byte]): IO[Response[IO]] =
    Files[IO].tempFile.use { tmp =>
      body.take(avatarMaxBytes + 1).through(Files[IO].writeAll(tmp)).compile.drain *> Files[IO].size(tmp).flatMap { size =>
        if size > avatarMaxBytes then err(Status.PayloadTooLarge, s"画像が大きすぎます（上限 ${Bytes.format(avatarMaxBytes)}）")
        else if size == 0 then err(Status.BadRequest, "画像が空です")
        else
          val key = s"avatars/${s.userId}/${UUID.randomUUID()}"
          store.put(key, tmp, ct) *> replaceAvatar(s.userId, Some(key)) *>
            users.find(s.userId).flatMap(u => Ok(Json.obj("avatarUrl" -> u.flatMap(_.avatarUrl).asJson)))
      }
    }

  private def me(s: AuthSession): IO[MeDto] =
    files.quota(s.user).map(q => MeDto(s.user.id, s.user.name, s.user.email, s.user.avatarUrl, s.isAdmin, s.viaString, s.scopeStrings, q))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "me" => authed(req)(s => me(s).flatMap(Ok(_)))

    case req @ PATCH -> Root / "api" / "me" =>
      authed(req)(s => body[UpdateMeReq](req)(r => users.updateMe(s.userId, r.name) *> NoContent()))

    // ---------------------------------------------------------------- アイコン（本文が画像そのもの）

    case req @ PUT -> Root / "api" / "me" / "avatar" =>
      sessionOnly(req) { s =>
        val ct = req.contentType.map(c => s"${c.mediaType.mainType}/${c.mediaType.subType}").getOrElse("")
        blobs match
          case None                             => err(Status.ServiceUnavailable, "画像の保存先（S3）が設定されていません")
          case Some(_) if !avatarTypes(ct)      => err(Status.BadRequest, "PNG・JPEG・GIF・WebP の画像を選んでください")
          case Some(store)                      => storeAvatar(s, store, ct, req.body)
      }

    case req @ DELETE -> Root / "api" / "me" / "avatar" =>
      sessionOnly(req)(s => replaceAvatar(s.userId, None) *> NoContent())

    case req @ GET -> Root / "api" / "users" / UuidVar(id) / "avatar" =>
      authed(req)(_ => users.avatarKey(id).map(k => (k, blobs)).flatMap {
        case (Some(key), Some(store)) => store.get(key).flatMap {
            case None => err(Status.NotFound, "画像が見つかりません")
            case Some((meta, bytes)) =>
              val mt = MediaType.parse(meta.contentType).toOption.filter(m => avatarTypes(s"${m.mainType}/${m.subType}")).getOrElse(MediaType.image.png)
              IO.pure(Response[IO](Status.Ok).withBodyStream(bytes)
                .putHeaders(`Content-Type`(mt), `Content-Length`.unsafeFromLong(meta.size), "X-Content-Type-Options" -> "nosniff",
                  "Cache-Control" -> "private, max-age=86400"))
          }
        case _ => err(Status.NotFound, "画像が登録されていません")
      })

    // ---------------------------------------------------------------- APIキー（アプリパスワード。画面からのみ）

    case req @ GET -> Root / "api" / "me" / "api-keys" => sessionOnly(req)(s => users.keys(s.userId).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "me" / "api-keys" =>
      sessionOnly(req)(s => body[CreateApiKeyReq](req) { r =>
        val scopes = r.scopes.map(_.trim.toLowerCase).distinct
        if r.name.trim.isEmpty then err(Status.BadRequest, "名前を入力してください")
        else if scopes.isEmpty || !scopes.forall(ApiScope.all.contains) then err(Status.BadRequest, "スコープは read / write / admin から選んでください")
        else if scopes.contains(ApiScope.Admin) && !s.isAdmin then err(Status.Forbidden, "admin スコープは管理者だけが使えます")
        else
          val (key, prefix) = ApiKeys.generate()
          val exp = r.expiresInDays.filter(_ > 0).map(d => Instant.now().plusSeconds(d.toLong * 86400))
          users.createKey(s.userId, r.name.trim, prefix, Crypto.sha256(key), scopes, exp).flatMap { info =>
            activity.audit(Some(s.userId), "web", "api_key.create", Some(info.id.toString), Json.obj("name" -> info.name.asJson, "scopes" -> scopes.asJson)) *>
              Created(CreatedApiKeyDto(key, info))
          }
      })

    case req @ DELETE -> Root / "api" / "me" / "api-keys" / UuidVar(id) =>
      sessionOnly(req)(s => users.revokeKey(s.userId, id).ifM(
        activity.audit(Some(s.userId), "web", "api_key.revoke", Some(id.toString), Json.obj()) *> NoContent(),
        err(Status.NotFound, "APIキーが見つかりません")))

    // ---------------------------------------------------------------- 通知

    case req @ GET -> Root / "api" / "notifications" =>
      authed(req)(s => activity.notifications(s.userId, boolParam(req, "unread"), intParam(req, "limit", 100, 500)).flatMap(Ok(_)))

    case req @ GET -> Root / "api" / "notifications" / "unread" => authed(req)(s => activity.unread(s.userId).flatMap(n => Ok(UnreadDto(n))))

    case req @ POST -> Root / "api" / "notifications" / "read" =>
      authed(req)(s => body[IdsReq](req)(r => activity.markRead(s.userId, r.ids) *> NoContent()))

    // ---------------------------------------------------------------- 管理

    case req @ GET -> Root / "api" / "admin" / "users" => admin(req)(_ => users.list(param(req, "q")).flatMap(Ok(_)))

    case req @ PATCH -> Root / "api" / "admin" / "users" / UuidVar(id) =>
      admin(req)(s => body[UpdateUserReq](req) { r =>
        if id == s.userId && (r.isAdmin.contains(false) || r.disabled.contains(true)) then err(Status.BadRequest, "自分の管理者権限は外せません")
        else
          val quota = r.quotaMb.map(mb => Option.when(mb >= 0)(mb * 1024 * 1024))
          users.update(id, r.isAdmin, r.disabled, quota).ifM(
            activity.audit(Some(s.userId), s.viaString, "user.update", Some(id.toString), r.asJson) *> NoContent(),
            err(Status.NotFound, "ユーザーが見つかりません"))
      })

    case req @ GET -> Root / "api" / "admin" / "audit" => admin(req)(_ => activity.auditList(intParam(req, "limit", 200, 1000)).flatMap(Ok(_)))

    case req @ GET -> Root / "api" / "admin" / "stats" => admin(req)(_ => nodes.stats.flatMap(Ok(_)))
  }
