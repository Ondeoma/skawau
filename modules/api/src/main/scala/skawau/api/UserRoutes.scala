package skawau.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import fs2.io.file.Files
import skawau.storage.BlobStore
import sbirds.auth.Crypto
import skawau.auth.ApiKeys
import skawau.core.*
import skawau.db.*
import skawau.shared.*

import java.time.Instant
import java.util.UUID

/** 自分の情報・APIキー・通知、管理画面（ユーザー・監査ログ・統計）。 */
final class UserRoutes(users: UserStore,
                       nodes: NodeStore,
                       activity: ActivityStore,
                       files: FileService,
                       blobs: Option[BlobStore],
                       val sessions: SessionAuth) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "me" => getMe(req)
    case req @ PATCH -> Root / "api" / "me" => patchMe(req)
    case req @ PUT -> Root / "api" / "me" / "avatar" => putMeAvatar(req)
    case req @ DELETE -> Root / "api" / "me" / "avatar" => deleteMeAvatar(req)
    case req @ GET -> Root / "api" / "users" / UuidVar(id) / "avatar" => getUserAvatar(req, id)
    case req @ GET -> Root / "api" / "me" / "api-keys" => getMeApiKeys(req)
    case req @ POST -> Root / "api" / "me" / "api-keys" => postMeApiKeys(req)
    case req @ DELETE -> Root / "api" / "me" / "api-keys" / UuidVar(id) => deleteMeApiKey(req, id)
    case req @ GET -> Root / "api" / "notifications" => getNotifications(req)
    case req @ GET -> Root / "api" / "notifications" / "unread" => getNotificationsUnread(req)
    case req @ POST -> Root / "api" / "notifications" / "read" => postNotificationsRead(req)
    case req @ GET -> Root / "api" / "admin" / "users" => getAdminUsers(req)
    case req @ PATCH -> Root / "api" / "admin" / "users" / UuidVar(id) => patchAdminUser(req, id)
    case req @ GET -> Root / "api" / "admin" / "audit" => getAdminAudit(req)
    case req @ GET -> Root / "api" / "admin" / "stats" => getAdminStats(req)
  }

  private def getMe(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => me(s).flatMap(m => sessions.appNames(req).flatMap(n => Ok(m.copy(appNames = n)))))

  private def patchMe(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => body[UpdateMeReq](req)(r => users.updateMe(s.userId, r.name) *> NoContent()))

  private def putMeAvatar(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req) { s =>
      val ct = req.contentType.map(c => s"${c.mediaType.mainType}/${c.mediaType.subType}").getOrElse("")
      blobs match {
        case None => err(ErrorCode.AvatarStorageNotConfigured)
        case Some(_) if !avatarTypes(ct) => err(ErrorCode.AvatarFormatUnsupported)
        case Some(store) => storeAvatar(s, store, ct, req.body)
      }
    }

  private def deleteMeAvatar(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s => replaceAvatar(s.userId, None) *> NoContent())

  private def getUserAvatar(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(_ =>
      users.avatarKey(id).map(k => (k, blobs)).flatMap {
        case (Some(key), Some(store)) => store.get(key).flatMap {
            case None => err(ErrorCode.AvatarNotFound)
            case Some((meta, bytes)) =>
              val mt = MediaType.parse(meta.contentType).toOption.filter(m =>
                avatarTypes(s"${m.mainType}/${m.subType}")
              ).getOrElse(MediaType.image.png)
              IO.pure(Response[IO](Status.Ok).withBodyStream(bytes)
                .putHeaders(
                  `Content-Type`(mt),
                  `Content-Length`.unsafeFromLong(meta.size),
                  "X-Content-Type-Options" -> "nosniff",
                  "Cache-Control" -> "private, max-age=86400"
                ))
          }
        case _ => err(ErrorCode.AvatarNotSet)
      }
    )

  private def getMeApiKeys(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s => users.keys(s.userId).flatMap(Ok(_)))

  private def postMeApiKeys(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s =>
      body[CreateApiKeyReq](req) { r =>
        val scopes = r.scopes.map(_.trim.toLowerCase).distinct
        if r.name.trim.isEmpty then err(ErrorCode.NameRequired)
        else if scopes.isEmpty || !scopes.forall(ApiScope.all.contains) then
          err(ErrorCode.ApiKeyScopeInvalid)
        else if scopes.contains(ApiScope.Admin) && !s.isAdmin then err(ErrorCode.ApiKeyAdminScopeAdminOnly)
        else {
          val (key, prefix) = ApiKeys.generate()
          val exp = r.expiresInDays.filter(_ > 0).map(d => Instant.now().plusSeconds(d.toLong * 86400))
          users.createKey(s.userId, r.name.trim, prefix, Crypto.sha256(key), scopes, exp).flatMap { info =>
            activity.audit(
              Some(s.userId),
              "web",
              "api_key.create",
              Some(info.id.toString),
              Json.obj("name" -> info.name.asJson, "scopes" -> scopes.asJson)
            ) *>
              Created(CreatedApiKeyDto(key, info))
          }
        }
      }
    )

  private def deleteMeApiKey(req: Request[IO], id: UUID): IO[Response[IO]] =
    sessionOnly(req)(s =>
      users.revokeKey(s.userId, id).ifM(
        activity.audit(Some(s.userId), "web", "api_key.revoke", Some(id.toString), Json.obj()) *> NoContent(),
        err(ErrorCode.ApiKeyNotFound)
      )
    )

  private def getNotifications(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      activity.notifications(s.userId, boolParam(req, "unread"), intParam(req, "limit", 100, 500)).flatMap(Ok(_))
    )

  private def getNotificationsUnread(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => activity.unread(s.userId).flatMap(n => Ok(UnreadDto(n))))

  private def postNotificationsRead(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s => body[IdsReq](req)(r => activity.markRead(s.userId, r.ids) *> NoContent()))

  private def getAdminUsers(req: Request[IO]): IO[Response[IO]] =
    admin(req)(_ => users.list(param(req, "q")).flatMap(Ok(_)))

  private def patchAdminUser(req: Request[IO], id: UUID): IO[Response[IO]] =
    admin(req)(s =>
      body[UpdateUserReq](req) { r =>
        if id == s.userId && (r.isAdmin.contains(false) || r.disabled.contains(true)) then
          err(ErrorCode.CannotRemoveOwnAdmin)
        else {
          val quota = r.quotaMb.map(mb => Option.when(mb >= 0)(mb * 1024 * 1024))
          users.update(id, r.isAdmin, r.disabled, quota).ifM(
            activity.audit(Some(s.userId), s.viaString, "user.update", Some(id.toString), r.asJson) *> NoContent(),
            err(ErrorCode.UserNotFound)
          )
        }
      }
    )

  private def getAdminAudit(req: Request[IO]): IO[Response[IO]] =
    admin(req)(_ => activity.auditList(intParam(req, "limit", 200, 1000)).flatMap(Ok(_)))

  private def getAdminStats(req: Request[IO]): IO[Response[IO]] =
    admin(req)(_ => nodes.stats.flatMap(Ok(_)))

  private val avatarTypes = Set("image/png", "image/jpeg", "image/gif", "image/webp")
  private val avatarMaxBytes = 2L * 1024 * 1024

  /** アイコンを差し替える（URL は画像を変えるたびに変え、ブラウザのキャッシュを効かせる）。 */
  private def replaceAvatar(uid: UUID, key: Option[String]): IO[Unit] =
    users.setAvatar(uid, key, key.map(_ => s"/api/users/$uid/avatar?v=${Crypto.randomToken(6)}"))

  /** 本文（画像）を一時ファイルに受け取って S3 に置く。 */
  private def storeAvatar(s: AuthSession, store: BlobStore, ct: String, body: fs2.Stream[IO, Byte]): IO[Response[IO]] =
    Files[IO].tempFile.use { tmp =>
      body.take(avatarMaxBytes + 1).through(Files[IO].writeAll(tmp)).compile.drain *> Files[IO].size(tmp).flatMap {
        size =>
          if size > avatarMaxBytes then err(ErrorCode.AvatarTooLarge, s"画像が大きすぎます（上限 ${Bytes.format(avatarMaxBytes)}）")
          else if size == 0 then err(ErrorCode.AvatarEmpty)
          else {
            val key = s"avatars/${s.userId}/${UUID.randomUUID()}"
            store.put(key, tmp, ct) *> replaceAvatar(s.userId, Some(key)) *>
              users.find(s.userId).flatMap(u => Ok(Json.obj("avatarUrl" -> u.flatMap(_.avatarUrl).asJson)))
          }
      }
    }

  private def me(s: AuthSession): IO[MeDto] =
    files.quota(s.user).map(q =>
      MeDto(s.user.id, s.user.name, s.user.email, s.user.avatarUrl, s.isAdmin, s.viaString, s.scopeStrings, q)
    )

}
