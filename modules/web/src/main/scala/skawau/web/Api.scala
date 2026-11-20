package skawau.web

import io.circe.*
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalajs.dom
import skawau.shared.*

import java.util.UUID
import scala.concurrent.{Future, Promise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** バックエンドの REST API（共通部分は sbirds-lib の ApiBase）. 公開リンクの画面では 401 でログインに飛ばさない（パスワードの入力を求める）. */
object Api extends sbirds.web.ApiBase {

  override protected def stayOnUnauthorized: Boolean = {
    val p = dom.window.location.pathname
    p.startsWith("/login") || p.startsWith("/s/")
  }

  private def toLogin(): Unit = if (!stayOnUnauthorized) {
    val back = dom.window.location.pathname + dom.window.location.search
    dom.window.location.href = loginUrl(back)
  }

  private def errorOf(status: Int, statusText: String, text: String): ApiError =
    ApiError(
      status,
      parse(text).toOption.flatMap(_.hcursor.get[String]("error").toOption).getOrElse(s"$status $statusText")
    )

  /** 本文をそのまま送る（テキストの保存）. */
  def putText[A: Decoder](url: String, text: String): Future[A] = {
    val i = init(dom.HttpMethod.PUT)
    i.body = text
    dom.fetch(url, i).toFuture.flatMap { res =>
      res.text().toFuture.map(t =>
        if (res.ok) as[A](parse(t).toOption) else throw errorOf(res.status, res.statusText, t)
      )
    }
  }

  def text(url: String): Future[String] = {
    dom.fetch(url, init(dom.HttpMethod.GET)).toFuture.flatMap(res =>
      res.text().toFuture.map(t => if (res.ok) t else throw errorOf(res.status, res.statusText, t))
    )
  }

  /**
   * ファイルを送る（XMLHttpRequest で進み具合を onProgress(送った, 全体) に知らせる）.
   * 戻り値の abort で取りやめられる.
   */
  def upload[A: Decoder](url: String, file: dom.Blob, onProgress: (Double, Double) => Unit): (Future[A], () => Unit) = {
    val p = Promise[A]()
    val xhr = new dom.XMLHttpRequest()
    xhr.open("PUT", url)
    xhr.withCredentials = true
    xhr.upload.onprogress = (e: dom.ProgressEvent) => onProgress(e.loaded, e.total)
    xhr.onload = (_: dom.Event) => {
      val t = xhr.responseText
      if (xhr.status >= 200 && xhr.status < 300) p.tryComplete(scala.util.Try(as[A](parse(t).toOption)))
      else {
        if (xhr.status == 401) toLogin()
        p.tryFailure(errorOf(xhr.status, xhr.statusText, t))
      }
    }
    xhr.onerror = (_: dom.Event) => p.tryFailure(ApiError(0, "通信に失敗しました"))
    xhr.onabort = (_: dom.Event) => p.tryFailure(ApiError(0, "取りやめました"))
    xhr.send(file)
    (p.future, () => xhr.abort())
  }

  // ---------------------------------------------------------------- 認証・自分

  def clientConfig(): Future[ClientConfigDto] = get[ClientConfigDto]("/api/client-config")
  def me(): Future[MeDto] = get[MeDto]("/api/me")
  def devLogin(name: String, email: String, admin: Boolean, groups: List[String], returnTo: String): Future[Json] =
    post[Json](
      "/auth/dev-login",
      Json.obj(
        "name" -> name.asJson,
        "email" -> email.asJson,
        "admin" -> admin.asJson,
        "groups" -> groups.asJson,
        "returnTo" -> returnTo.asJson
      )
    )

  /** ログアウトして、次に開く URL（IdP のログアウト画面か「/」）を返す. */
  def logout(): Future[String] = post[Json]("/auth/logout").map(_.hcursor.get[String]("redirect").getOrElse("/"))
  def updateMe(name: String): Future[Unit] = patchUnit("/api/me", UpdateMeReq(Some(name)).asJson)

  /** アイコンの画像を登録する（本文が画像そのもの）. */
  def setAvatar(file: dom.Blob): Future[io.circe.Json] = upload[io.circe.Json]("/api/me/avatar", file, (_, _) => ())._1
  def removeAvatar(): Future[Unit] = delete("/api/me/avatar")
  def apiKeys(): Future[List[ApiKeyDto]] = get[List[ApiKeyDto]]("/api/me/api-keys")
  def createApiKey(r: CreateApiKeyReq): Future[CreatedApiKeyDto] = post[CreatedApiKeyDto]("/api/me/api-keys", r.asJson)
  def revokeApiKey(id: UUID): Future[Unit] = delete(s"/api/me/api-keys/$id")

  // ---------------------------------------------------------------- 通知・変更履歴

  def notifications(unreadOnly: Boolean): Future[List[NotificationDto]] =
    get[List[NotificationDto]]("/api/notifications" + query("unread" -> Option.when(unreadOnly)("true")))
  def unread(): Future[UnreadDto] = get[UnreadDto]("/api/notifications/unread")

  /** ids が空ならすべて既読にする. */
  def markRead(ids: List[UUID]): Future[Unit] = postUnit("/api/notifications/read", IdsReq(ids).asJson)
  def activity(before: Option[Long], limit: Int): Future[List[EventDto]] =
    get[List[EventDto]]("/api/activity" + query("limit" -> Some(limit.toString), "before" -> before.map(_.toString)))

  // ---------------------------------------------------------------- 管理

  def adminUsers(q: String): Future[List[AdminUserDto]] =
    get[List[AdminUserDto]]("/api/admin/users" + query("q" -> Some(q)))
  def updateUser(id: UUID, r: UpdateUserReq): Future[Unit] = patchUnit(s"/api/admin/users/$id", r.asJson)
  def stats(): Future[StatsDto] = get[StatsDto]("/api/admin/stats")
  def audit(): Future[List[AuditDto]] = get[List[AuditDto]]("/api/admin/audit?limit=200")

  // ---------------------------------------------------------------- ファイル

  def root(): Future[FolderDto] = get[FolderDto]("/api/files/root")
  def folder(id: UUID): Future[FolderDto] = get[FolderDto](s"/api/files/$id/children")
  def node(id: UUID): Future[NodeHitDto] = get[NodeHitDto](s"/api/files/$id")
  def mkdir(parent: UUID, name: String): Future[NodeDto] =
    post[NodeDto](s"/api/files/$parent/folders", NameReq(name).asJson)
  def newFile(parent: UUID, name: String): Future[NodeDto] =
    post[NodeDto](s"/api/files/$parent/new", NameReq(name).asJson)
  def rename(id: UUID, name: String): Future[NodeDto] = patch[NodeDto](s"/api/files/$id", NameReq(name).asJson)
  def transfer(ids: List[UUID], target: UUID, copy: Boolean, conflict: Option[String]): Future[BatchResultDto] =
    post[BatchResultDto]("/api/files/transfer", MoveReq(ids, target, copy, conflict).asJson)
  def trash(ids: List[UUID]): Future[BatchResultDto] = post[BatchResultDto]("/api/files/delete", IdsReq(ids).asJson)
  def favorite(id: UUID, on: Boolean): Future[Unit] =
    send(if (on) dom.HttpMethod.PUT else dom.HttpMethod.DELETE, s"/api/files/$id/favorite")
  def versions(id: UUID): Future[List[VersionDto]] = get[List[VersionDto]](s"/api/files/$id/versions")
  def restoreVersion(id: UUID, vid: UUID): Future[NodeDto] = post[NodeDto](s"/api/files/$id/versions/$vid/restore")
  def nodeActivity(id: UUID): Future[List[EventDto]] = get[List[EventDto]](s"/api/files/$id/activity")
  def officeConfig(id: String, token: Option[String]): Future[OfficeConfigDto] =
    get[OfficeConfigDto](token.fold(s"/api/files/$id/office")(t => s"/api/public/$t/office?id=$id"))

  // ---------------------------------------------------------------- 一覧（場所つき）・ごみ箱

  def recent(): Future[List[NodeHitDto]] = get[List[NodeHitDto]]("/api/recent")
  def favorites(): Future[List[NodeHitDto]] = get[List[NodeHitDto]]("/api/favorites")
  def sharedWithMe(): Future[List[NodeHitDto]] = get[List[NodeHitDto]]("/api/shared-with-me")
  def search(q: String): Future[List[NodeHitDto]] = get[List[NodeHitDto]]("/api/search" + query("q" -> Some(q)))
  def sharedByMe(): Future[List[SharedByMeDto]] = get[List[SharedByMeDto]]("/api/shared-by-me")
  def trashList(): Future[List[TrashItemDto]] = get[List[TrashItemDto]]("/api/trash")
  def restoreTrash(id: UUID): Future[Unit] = postUnit(s"/api/trash/$id/restore")
  def purgeTrash(id: UUID): Future[Unit] = delete(s"/api/trash/$id")
  def emptyTrash(): Future[Unit] = delete("/api/trash")

  // ---------------------------------------------------------------- 共有

  def shares(id: UUID): Future[List[ShareDto]] = get[List[ShareDto]](s"/api/files/$id/shares")
  def deleteShare(id: UUID): Future[Unit] = delete(s"/api/shares/$id")
  def createShare(id: UUID, r: CreateShareReq): Future[ShareDto] =
    post[ShareDto](s"/api/files/$id/shares", r.asJson.dropNullValues)
  def updateShare(id: UUID, r: UpdateShareReq): Future[ShareDto] =
    patch[ShareDto](s"/api/shares/$id", r.asJson.dropNullValues)
  def sharees(q: String): Future[ShareeDto] = get[ShareeDto]("/api/sharees" + query("q" -> Some(q)))

  def contentUrl(id: UUID, download: Boolean = false): String = s"/api/files/$id/content" +
    (if (download) "?download=1" else "")
  def zipUrl(ids: List[UUID]): String = "/api/files/zip?ids=" + ids.mkString(",")
  def uploadUrl(folder: UUID, name: String, path: String, conflict: Option[String]): String =
    s"/api/files/$folder/upload" + query("name" -> Some(name), "path" -> Some(path), "conflict" -> conflict)

  // ---------------------------------------------------------------- 公開リンク

  def publicInfo(token: String): Future[PublicInfoDto] = get[PublicInfoDto](s"/api/public/$token")
  def publicAuth(token: String, password: String): Future[Unit] =
    postUnit(s"/api/public/$token/auth", PasswordReq(password).asJson)
  def publicMkdir(token: String, dir: UUID, name: String): Future[NodeDto] =
    post[NodeDto](s"/api/public/$token/folders?dir=$dir", NameReq(name).asJson)
  def publicFolder(token: String, id: Option[UUID]): Future[FolderDto] =
    get[FolderDto](s"/api/public/$token/folder" + query("id" -> id.map(_.toString)))
  def publicContentUrl(token: String, id: UUID, download: Boolean = false): String =
    s"/api/public/$token/content" + query("id" -> Some(id.toString), "download" -> Option.when(download)("1"))
  def publicUploadUrl(token: String, dir: UUID, name: String, path: String): String =
    s"/api/public/$token/upload" + query("dir" -> Some(dir.toString), "name" -> Some(name), "path" -> Some(path))

}
