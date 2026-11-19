package skawau.core

import java.time.Instant
import java.util.UUID

/** UUID として読めれば Some（読めなければ None）。 */
def uuidOpt(
  s: String
): Option[UUID] = scala.util.Try(UUID.fromString(s.trim)).toOption

/** APIキー（アプリパスワード）のスコープ。 */
enum Scope {
  case Read, Write, Admin
}

object Scope {
  def parse(
    s: String
  ): Option[Scope] = s.trim.toLowerCase match {
    case "read" => Some(Read)
    case "write" => Some(Write)
    case "admin" => Some(Admin)
    case _ => None
  }
  extension (
    s: Scope
  ) {
    def asString: String = s match {
      case Read => "read"
      case Write => "write"
      case Admin => "admin"
    }
  }
}

/** quota は容量の上限（バイト。None なら既定値）。 */
final case class User(
  id: UUID,
  sub: String,
  name: String,
  email: Option[String],
  avatarUrl: Option[String],
  isAdmin: Boolean,
  disabled: Boolean,
  quota: Option[Long])
final case class Group(
  id: UUID,
  name: String)

/** 認証手段。 */
enum AuthVia {
  case Session

  /** APIキー（WebDAV のアプリパスワードを兼ねる）。dav は WebDAV、mcp は MCP からの呼び出し。 */
  case ApiKey(
    id: UUID,
    scopes: Set[Scope],
    dav: Boolean = false,
    mcp: Boolean = false)
}

/** 認証済みの利用者。isAdmin はシステム管理者（ADMIN_GROUPS の所属、または画面で管理者にしたユーザー）。 */
final case class AuthSession(
  user: User,
  groups: List[Group],
  isAdmin: Boolean,
  via: AuthVia = AuthVia.Session) {

  def userId: UUID = user.id
  def groupIds: List[UUID] = groups.map(_.id)

  /** 更新系の操作ができるか（read スコープだけのキーは不可）。 */
  def canWrite: Boolean = via match {
    case AuthVia.ApiKey(_, scopes, _, _) => scopes.contains(Scope.Write) || scopes.contains(Scope.Admin)
    case _ => true
  }

  /** システム管理の操作ができるか（APIキーは admin スコープが必要）。 */
  def canAdmin: Boolean = isAdmin &&
    (via match {
      case AuthVia.ApiKey(_, scopes, _, _) => scopes.contains(Scope.Admin)
      case _ => true
    })

  def viaString: String = via match {
    case AuthVia.Session => "web"
    case AuthVia.ApiKey(_, _, true, _) => "dav"
    case AuthVia.ApiKey(_, _, _, true) => "mcp"
    case _: AuthVia.ApiKey => "api"
  }

  def isSession: Boolean = via == AuthVia.Session

  def scopeStrings: List[String] = via match {
    case AuthVia.ApiKey(_, s, _, _) => s.toList.map(_.asString).sorted
    case _ => List("read", "write") ++ Option.when(isAdmin)("admin")
  }
}

/** 操作した人（変更履歴に残す）。公開リンクからの操作は user が共有した人、via が "link"。 */
final case class Actor(
  userId: Option[UUID],
  via: String)

object Actor {
  def of(
    s: AuthSession
  ): Actor = Actor(Some(s.userId), s.viaString)
}

/** ファイルまたはフォルダ（DB の node）。ancestors はルートから親までの ID。 */
final case class Node(
  id: UUID,
  ownerId: UUID,
  parentId: Option[UUID],
  ancestors: List[UUID],
  name: String,
  isDir: Boolean,
  size: Long,
  contentType: Option[String],
  blobKey: Option[String],
  sha256: Option[String],
  etag: String,
  version: Int,
  createdAt: Instant,
  updatedAt: Instant,
  modifiedBy: Option[UUID],
  deletedAt: Option[Instant]) {
  def isRoot: Boolean = parentId.isEmpty

  /** 自分と祖先（共有の判定に使う）。 */
  def lineage: List[UUID] = ancestors :+ id
}

/** ファイル・フォルダに対する実効の権限（強い順に Owner > Edit > View）。 */
enum Access {
  case View, Edit, Owner
}

object Access {
  given Ordering[Access] = Ordering.by(_.ordinal)
  def parse(
    s: String
  ): Option[Access] = s match {
    case "view" => Some(View)
    case "edit" => Some(Edit)
    case "owner" => Some(Owner)
    case _ => None
  }
  extension (
    a: Access
  ) {
    def asString: String = a match {
      case View => "view"
      case Edit => "edit"
      case Owner => "owner"
    }
    def canEdit: Boolean = a.ordinal >= Edit.ordinal
    def >=(
      o: Access
    ): Boolean = a.ordinal >= o.ordinal
  }
}

/** 公開リンクで開いたときの文脈（共有の起点と権限）。 */
final case class LinkGrant(
  shareId: UUID,
  nodeId: UUID,
  ownerId: UUID,
  permission: String) {
  def canEdit: Boolean = permission == "edit"
  def canUpload: Boolean = permission == "edit" || permission == "upload"
  def canRead: Boolean = permission != "upload"
}
