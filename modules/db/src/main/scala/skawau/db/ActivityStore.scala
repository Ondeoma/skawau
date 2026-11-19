package skawau.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import skawau.core.Node
import skawau.shared.*

import java.time.Instant
import java.util.UUID

/** 変更履歴・通知・監査ログ。 */
final class ActivityStore(xa: Transactor[IO]) extends TxStore(xa) {

  import doobie.postgres.circe.jsonb.implicits.*

  private val eventSelect =
    fr"""select e.owner_id, e.id, e.node_id, e.kind, e.name, e.path, u.id, u.name, u.avatar_url, e.via, e.at, e.detail
         from file_event e left join app_user u on u.id = e.actor_id"""

  private type EventRow = (Long, UUID, String, String, String, Option[UserBrief], String, Instant, Json)

  /** 所有者以外にはパス（共有の起点より上のフォルダ名）を見せない。 */
  private def toDto(viewer: UUID)(owner: UUID, r: EventRow): EventDto = {
    val dto = EventDto(r._1, r._2, r._3, r._4, r._5, r._6, r._7, r._8, r._9)
    if owner == viewer then dto else dto.copy(path = "", detail = Json.obj())
  }

  /** 見ている人に関係する変更（自分のファイルと、共有されたものの中）。 */
  def forViewer(v: Viewer, before: Option[Long], limit: Int): IO[List[EventDto]] = tx {
    for {
      roots <- Visibility.sharedRoots(v)
      rows <-
      (eventSelect ++ fr"where (e.owner_id = ${v.userId} or e.lineage && $roots)" ++
        before.fold(Fragment.empty)(b => fr"and e.id < $b") ++
        fr"order by e.id desc limit $limit").query[(UUID, EventRow)].to[List]
    } yield rows.map(toDto(v.userId).tupled)
  }

  /** ノード（フォルダなら中身も）の変更。 */
  def forNode(viewer: UUID, node: Node, limit: Int): IO[List[EventDto]] = tx(
    (eventSelect ++
      fr"where e.node_id = ${node.id} or ${node.id} = any(e.lineage) order by e.id desc limit $limit").query[(
      UUID,
      EventRow
    )].to[List]
  ).map(_.map(toDto(viewer).tupled))

  def latestEvent: IO[Long] = tx(sql"select coalesce(max(id), 0) from file_event".query[Long].unique)

  /** 新しい変更と、それを知らせる相手（所有者・共有の相手）。画面の自動更新に使う。 */
  def audienceAfter(last: Long): IO[List[(Long, UUID)]] = tx(
    sql"""select e.id, x.u from file_event e, lateral (
            select e.owner_id as u
            union select s.user_id from share s where s.node_id = any(e.lineage) and s.user_id is not null
              and (s.expires_at is null or s.expires_at >= current_date)
            union select m.user_id from share s join group_member m on m.group_id = s.group_id where s.node_id = any(e.lineage)
              and (s.expires_at is null or s.expires_at >= current_date)
          ) x where e.id > $last order by e.id limit 1000""".query[(Long, UUID)].to[List]
  )

  def purgeOld(days: Int): IO[Int] = tx(
    sql"delete from file_event where at < now() - make_interval(days => $days)".update.run *>
      sql"delete from notification where at < now() - make_interval(days => $days)".update.run *>
      sql"delete from audit_log where at < now() - make_interval(days => ${days * 2})".update.run
  )

  // ---------------------------------------------------------------- 通知

  def notify(users: List[UUID],
             kind: String,
             title: String,
             body: String,
             link: Option[String],
             actor: Option[UUID]): IO[Unit] =
    tx(users.distinct.traverse_(u =>
      sql"insert into notification (id, user_id, kind, title, body, link, actor_id) values (${UUID.randomUUID()}, $u, $kind, $title, $body, $link, $actor)"
        .update.run
    ))

  def notifications(userId: UUID, unreadOnly: Boolean, limit: Int): IO[List[NotificationDto]] = tx(
    (fr"""select n.id, n.kind, n.title, n.body, n.link, u.id, u.name, u.avatar_url, n.at, n.read_at is not null
          from notification n left join app_user u on u.id = n.actor_id where n.user_id = $userId""" ++
      (if unreadOnly then fr"and n.read_at is null" else Fragment.empty) ++ fr"order by n.at desc limit $limit")
      .query[NotificationDto].to[List]
  )

  def unread(userId: UUID): IO[Int] =
    tx(sql"select count(*)::int from notification where user_id = $userId and read_at is null".query[Int].unique)

  /** ids が空ならすべて既読にする。 */
  def markRead(userId: UUID, ids: List[UUID]): IO[Unit] = tx(
    (if ids.isEmpty then sql"update notification set read_at = now() where user_id = $userId and read_at is null"
     else
       sql"update notification set read_at = now() where user_id = $userId and id = any(${ids.toArray}) and read_at is null"
    ).update.run.void
  )

  def notifiedSince(since: Instant): IO[List[(UUID, Instant)]] =
    tx(sql"select user_id, max(at) from notification where at > $since group by user_id".query[(
      UUID,
      Instant
    )].to[List])

  // ---------------------------------------------------------------- 監査ログ

  def audit(actor: Option[UUID], via: String, action: String, target: Option[String], detail: Json): IO[Unit] = tx(
    sql"insert into audit_log (actor_id, via, action, target, detail) values ($actor, $via, $action, $target, $detail)".update.run.void
  )

  def auditList(limit: Int): IO[List[AuditDto]] = tx(
    sql"""select a.id, a.at, u.id, u.name, u.avatar_url, a.via, a.action, a.target, a.detail
          from audit_log a left join app_user u on u.id = a.actor_id order by a.id desc limit $limit""".query[
      AuditDto
    ].to[List]
  )
}
