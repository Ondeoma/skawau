package skawau.shared

import io.circe.*

import java.util.UUID

// バックエンド(JVM)と画面(Scala.js)の両方で使う API の型。
// 日時は Timestamp（JVM では Instant）、日付は "yyyy-MM-dd" の文字列（Scala.js で java.time を使わないため）。

/** ファイル・フォルダに対する権限. 閲覧 < 編集 < 所有者. upload は公開リンクのフォルダへの「アップロードだけ」. */
object Perm {
  val View = "view"
  val Upload = "upload"
  val Edit = "edit"
  val Owner = "owner"
  def rank(p: String): Int = p match {
    case View => 1
    case Upload => 1
    case Edit => 2
    case Owner => 3
    case _ => 0
  }
  def canEdit(p: String): Boolean = rank(p) >= 2
  def label(p: String): String = p match {
    case View => "閲覧のみ"
    case Upload => "アップロードのみ"
    case Edit => "編集可"
    case Owner => "所有者"
    case _ => p
  }
}

/** 共有の種類. */
object ShareKind {
  val User = "user"
  val Group = "group"
  val Link = "link"
  val all: List[String] = List(User, Group, Link)
}

/** 変更履歴・通知の種類. */
object EventKind {
  val Created = "created"
  val Uploaded = "uploaded"
  val Updated = "updated"
  val Renamed = "renamed"
  val Moved = "moved"
  val Copied = "copied"
  val Trashed = "trashed"
  val Restored = "restored"
  val Deleted = "deleted"
  val VersionRestored = "version_restored"
  val Shared = "shared"
  val Unshared = "unshared"
  val LinkDownloaded = "link_downloaded"
  def label(k: String): String = k match {
    case Created => "作成"
    case Uploaded => "アップロード"
    case Updated => "更新"
    case Renamed => "名前の変更"
    case Moved => "移動"
    case Copied => "コピー"
    case Trashed => "ごみ箱へ移動"
    case Restored => "ごみ箱から復元"
    case Deleted => "完全に削除"
    case VersionRestored => "版を復元"
    case Shared => "共有"
    case Unshared => "共有を解除"
    case LinkDownloaded => "リンクからダウンロード"
    case _ => k
  }
}

object NotificationKind {
  val Shared = "shared"
  val Uploaded = "uploaded"
}

final case class UserBrief(id: UUID, name: String, avatarUrl: Option[String]) derives Encoder.AsObject, Decoder
final case class GroupBrief(id: UUID, name: String, members: Int) derives Encoder.AsObject, Decoder

final case class QuotaDto(used: Long, limit: Option[Long]) derives Encoder.AsObject, Decoder

final case class MeDto(
  id: UUID,
  name: String,
  email: Option[String],
  avatarUrl: Option[String],
  isAdmin: Boolean,
  via: String,
  scopes: List[String],
  quota: QuotaDto,
  /** sbirds のアプリの表示名（slug → 名前. sruru の ID トークンから. 自身は "_self"） */
  appNames: Map[String, String] = Map.empty) derives Encoder.AsObject, Decoder

final case class UpdateMeReq(name: Option[String]) derives Encoder.AsObject, Decoder

/** ONLYOFFICE（Document Server）の連携. edit は編集できる拡張子、view は閲覧だけできる拡張子. */
final case class OfficeInfoDto(url: String, edit: List[String], view: List[String]) derives Encoder.AsObject, Decoder

final case class ClientConfigDto(devLogin: Boolean,
                                 version: String,
                                 office: Option[OfficeInfoDto],
                                 webdavUrl: String,
                                 mcpUrl: String,
                                 maxUploadBytes: Long,
                                 versionsKept: Int,
                                 trashDays: Int) derives Encoder.AsObject, Decoder

// ---------------------------------------------------------------- ファイル・フォルダ

/**
 * ファイルまたはフォルダ. perm は見ている人の権限、shared はほかの人と共有しているか（所有者に見せる）、
 * hasLink は公開リンクがあるか.
 */
final case class NodeDto(id: UUID,
                         parentId: Option[UUID],
                         name: String,
                         isDir: Boolean,
                         size: Long,
                         contentType: Option[String],
                         createdAt: Timestamp,
                         updatedAt: Timestamp,
                         modifiedBy: Option[UserBrief],
                         owner: UserBrief,
                         version: Int,
                         etag: String,
                         perm: String,
                         favorite: Boolean,
                         shared: Boolean,
                         hasLink: Boolean)
  derives Encoder.AsObject, Decoder

/** パンくずリストの1つ. */
final case class Crumb(id: UUID, name: String) derives Encoder.AsObject, Decoder

/** フォルダの中身. path は見ている人がたどれる範囲（共有されたフォルダなら共有の起点から）. inShare は共有されたものを見ているか. */
final case class FolderDto(folder: NodeDto, path: List[Crumb], items: List[NodeDto], inShare: Boolean)
  derives Encoder.AsObject, Decoder

/** 検索・最近・お気に入りの1件（場所つき）. */
final case class NodeHitDto(node: NodeDto, path: String) derives Encoder.AsObject, Decoder

final case class NameReq(name: String) derives Encoder.AsObject, Decoder

/** 移動・コピー. conflict は同じ名前があるとき: rename（「名前 (2)」にする）/ overwrite（上書き）/ 未指定はエラー. */
final case class MoveReq(ids: List[UUID], targetId: UUID, copy: Boolean, conflict: Option[String])
  derives Encoder.AsObject, Decoder

final case class IdsReq(ids: List[UUID]) derives Encoder.AsObject, Decoder

/** まとめての操作（移動・コピー・削除）の結果. 失敗したものは「名前: 理由」. */
final case class BatchResultDto(done: List[NodeDto], errors: List[String]) derives Encoder.AsObject, Decoder

final case class VersionDto(id: UUID,
                            version: Int,
                            size: Long,
                            createdAt: Timestamp,
                            author: Option[UserBrief],
                            current: Boolean)
  derives Encoder.AsObject, Decoder

final case class TrashItemDto(id: UUID,
                              name: String,
                              isDir: Boolean,
                              size: Long,
                              deletedAt: Timestamp,
                              originalPath: String,
                              deletedBy: Option[UserBrief]) derives Encoder.AsObject, Decoder

// ---------------------------------------------------------------- 共有

/** 共有. kind が link のときは token と url（公開リンク）. expiresAt は "yyyy-MM-dd". */
final case class ShareDto(id: UUID,
                          nodeId: UUID,
                          kind: String,
                          user: Option[UserBrief],
                          group: Option[GroupBrief],
                          permission: String,
                          token: Option[String],
                          url: Option[String],
                          hasPassword: Boolean,
                          expiresAt: Option[String],
                          label: Option[String],
                          createdAt: Timestamp,
                          owner: UserBrief) derives Encoder.AsObject, Decoder

final case class CreateShareReq(kind: String,
                                userId: Option[UUID],
                                groupId: Option[UUID],
                                permission: String,
                                password: Option[String],
                                expiresAt: Option[String],
                                label: Option[String]) derives Encoder.AsObject, Decoder

/** 共有の変更. password・expiresAt に空文字を渡すと外す. */
final case class UpdateShareReq(permission: Option[String],
                                password: Option[String],
                                expiresAt: Option[String],
                                label: Option[String])
  derives Encoder.AsObject, Decoder

/** 自分が共有したもの（共有ごとに1件）. */
final case class SharedByMeDto(share: ShareDto, node: NodeDto, path: String) derives Encoder.AsObject, Decoder

/** 共有の相手の候補（ユーザーまたはグループ）. */
final case class ShareeDto(users: List[UserBrief], groups: List[GroupBrief]) derives Encoder.AsObject, Decoder

// ---------------------------------------------------------------- 公開リンク

/** 公開リンクの情報. needsPassword のときはパスワードを送るまで中身は見られない. */
final case class PublicInfoDto(token: String,
                               name: String,
                               isDir: Boolean,
                               size: Long,
                               contentType: Option[String],
                               permission: String,
                               ownerName: String,
                               expiresAt: Option[String],
                               needsPassword: Boolean,
                               rootId: UUID,
                               label: Option[String])
  derives Encoder.AsObject, Decoder

final case class PasswordReq(password: String) derives Encoder.AsObject, Decoder

// ---------------------------------------------------------------- 変更履歴・通知

final case class EventDto(id: Long,
                          nodeId: UUID,
                          kind: String,
                          name: String,
                          path: String,
                          actor: Option[UserBrief],
                          via: String,
                          at: Timestamp,
                          detail: Json) derives Encoder.AsObject, Decoder

final case class NotificationDto(id: UUID,
                                 kind: String,
                                 title: String,
                                 body: String,
                                 link: Option[String],
                                 actor: Option[UserBrief],
                                 at: Timestamp,
                                 read: Boolean) derives Encoder.AsObject, Decoder

final case class UnreadDto(unread: Int) derives Encoder.AsObject, Decoder

// ---------------------------------------------------------------- ONLYOFFICE

/** 画面が Document Server のエディタを開くための設定（config は DocsAPI.DocEditor にそのまま渡す. 署名つき）. */
final case class OfficeConfigDto(documentServerUrl: String,
                                 config: Json,
                                 name: String,
                                 canEdit: Boolean,
                                 folderId: Option[UUID])
  derives Encoder.AsObject, Decoder

// ---------------------------------------------------------------- APIキー（アプリパスワード）

final case class ApiKeyDto(id: UUID,
                           name: String,
                           prefix: String,
                           scopes: List[String],
                           createdAt: Timestamp,
                           expiresAt: Option[Timestamp],
                           lastUsedAt: Option[Timestamp]) derives Encoder.AsObject, Decoder

final case class CreateApiKeyReq(name: String, scopes: List[String], expiresInDays: Option[Int])
  derives Encoder.AsObject, Decoder

final case class CreatedApiKeyDto(key: String, info: ApiKeyDto) derives Encoder.AsObject, Decoder

object ApiScope {
  val Read = "read"
  val Write = "write"
  val Admin = "admin"
  val all: List[String] = List(Read, Write, Admin)
}

// ---------------------------------------------------------------- 管理

final case class AdminUserDto(id: UUID,
                              name: String,
                              email: Option[String],
                              avatarUrl: Option[String],
                              isAdmin: Boolean,
                              disabled: Boolean,
                              lastLoginAt: Option[Timestamp],
                              groups: List[String],
                              used: Long,
                              quota: Option[Long])
  derives Encoder.AsObject, Decoder

/** quotaMb: 0 未満で無制限（既定に戻す）. */
final case class UpdateUserReq(isAdmin: Option[Boolean], disabled: Option[Boolean], quotaMb: Option[Long])
  derives Encoder.AsObject, Decoder

final case class AuditDto(id: Long,
                          at: Timestamp,
                          actor: Option[UserBrief],
                          via: String,
                          action: String,
                          target: Option[String],
                          detail: Json)
  derives Encoder.AsObject, Decoder

final case class StatsDto(users: Int,
                          files: Long,
                          folders: Long,
                          bytes: Long,
                          versionBytes: Long,
                          trashBytes: Long,
                          shares: Long,
                          links: Long)
  derives Encoder.AsObject, Decoder
