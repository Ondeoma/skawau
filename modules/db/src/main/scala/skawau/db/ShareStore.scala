package skawau.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import skawau.shared.*

import java.time.{Instant, LocalDate}
import java.util.UUID

/** 共有の行。 */
final case class ShareRow(id: UUID, nodeId: UUID, ownerId: UUID, kind: String, userId: Option[UUID], groupId: Option[UUID], permission: String,
    token: Option[String], passwordHash: Option[String], expiresAt: Option[LocalDate], label: Option[String], createdAt: Instant):
  def expired(today: LocalDate): Boolean = expiresAt.exists(_.isBefore(today))

/** 共有（ユーザー・グループ・公開リンク）。 */
final class ShareStore(xa: Transactor[IO]) extends TxStore(xa):

  private val cols = fr"s.id, s.node_id, s.owner_id, s.kind, s.user_id, s.group_id, s.permission, s.token, s.password_hash, s.expires_at, s.label, s.created_at"

  private val dtoSelect =
    fr"select" ++ cols ++ fr""", u.id, u.name, u.avatar_url, g.id, g.name, (select count(*)::int from group_member m where m.group_id = g.id),
          o.id, o.name, o.avatar_url
          from share s join app_user o on o.id = s.owner_id left join app_user u on u.id = s.user_id left join user_group g on g.id = s.group_id"""

  private def toDto(base: String)(r: (ShareRow, Option[UserBrief], Option[GroupBrief], UserBrief)): ShareDto =
    val (s, u, g, o) = r
    ShareDto(s.id, s.nodeId, s.kind, u, g, s.permission, s.token, s.token.map(t => s"$base/s/$t"), s.passwordHash.isDefined,
      s.expiresAt.map(_.toString), s.label, s.createdAt, o)

  def find(id: UUID): IO[Option[ShareRow]] = tx((fr"select" ++ cols ++ fr"from share s where s.id = $id").query[ShareRow].option)

  def byToken(token: String): IO[Option[ShareRow]] =
    tx((fr"select" ++ cols ++ fr"from share s where s.kind = 'link' and s.token = $token").query[ShareRow].option)

  def dto(id: UUID, base: String): IO[Option[ShareDto]] =
    tx((dtoSelect ++ fr"where s.id = $id").query[(ShareRow, Option[UserBrief], Option[GroupBrief], UserBrief)].option).map(_.map(toDto(base)))

  /** ノードに付いている共有（祖先のものは含まない）。 */
  def forNode(nodeId: UUID, base: String): IO[List[ShareDto]] =
    tx((dtoSelect ++ fr"where s.node_id = $nodeId order by s.kind desc, s.created_at")
      .query[(ShareRow, Option[UserBrief], Option[GroupBrief], UserBrief)].to[List]).map(_.map(toDto(base)))

  /** 自分が所有するファイルに付いている共有。 */
  def byOwner(ownerId: UUID, base: String): IO[List[ShareDto]] =
    tx((dtoSelect ++ fr"join node n on n.id = s.node_id where n.owner_id = $ownerId and n.deleted_at is null order by s.created_at desc")
      .query[(ShareRow, Option[UserBrief], Option[GroupBrief], UserBrief)].to[List]).map(_.map(toDto(base)))

  def create(nodeId: UUID, ownerId: UUID, kind: String, userId: Option[UUID], groupId: Option[UUID], permission: String, token: Option[String],
      passwordHash: Option[String], expires: Option[LocalDate], label: Option[String]): IO[Either[StoreError, UUID]] =
    val id = UUID.randomUUID()
    tx(sql"""insert into share (id, node_id, owner_id, kind, user_id, group_id, permission, token, password_hash, expires_at, label)
             values ($id, $nodeId, $ownerId, $kind, $userId, $groupId, $permission, $token, $passwordHash, $expires, $label)""".update.run)
      .as(Right(id): Either[StoreError, UUID])
      .recover { case e: java.sql.SQLException if e.getSQLState == "23505" => Left(StoreError.Conflict("すでにその相手と共有しています")) }

  /** password・expires は Some(None) で外す。 */
  def update(id: UUID, permission: Option[String], passwordHash: Option[Option[String]], expires: Option[Option[LocalDate]],
      label: Option[Option[String]]): IO[Unit] =
    val sets = List(permission.map(v => fr"permission = $v"), passwordHash.map(v => fr"password_hash = $v"), expires.map(v => fr"expires_at = $v"),
      label.map(v => fr"label = $v")).flatten
    if sets.isEmpty then IO.unit else tx((fr"update share" ++ Sql.set(sets) ++ fr"where id = $id").update.run.void)

  def delete(id: UUID): IO[Unit] = tx(sql"delete from share where id = $id".update.run.void)

  /** 共有の相手（グループはその所属者。所有者は除く）。通知に使う。 */
  def recipients(s: ShareRow): IO[List[UUID]] = tx(
    (s.userId, s.groupId) match
      case (Some(u), _) => List(u).filterNot(_ == s.ownerId).pure[ConnectionIO]
      case (_, Some(g)) => sql"select user_id from group_member where group_id = $g and user_id <> ${s.ownerId}".query[UUID].to[List]
      case _            => List.empty[UUID].pure[ConnectionIO]
  )
