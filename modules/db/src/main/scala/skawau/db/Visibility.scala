package skawau.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.util.UUID

/** 見ている人（権限の判定に使う）。 */
final case class Viewer(
  userId: UUID,
  groupIds: List[UUID])

object Viewer {
  def of(
    s: skawau.core.AuthSession
  ): Viewer = Viewer(s.userId, s.groupIds)
}

/** 共有による見え方の判定（ファイル・変更履歴のストアで共通）。共有の別名は s。 */
object Visibility {

  /** 見ている人に向けた、有効な（期限切れでない）ユーザー・グループへの共有。 */
  def toViewer(
    v: Viewer
  ): Fragment =
    fr"s.kind <> 'link' and (s.user_id = ${v.userId} or s.group_id = any(${v.groupIds.toArray})) and (s.expires_at is null or s.expires_at >= current_date)"

  /** 見ている人に共有されたものの起点。 */
  def sharedRoots(
    v: Viewer
  ): ConnectionIO[
    Array[UUID]
  ] = (fr"select distinct s.node_id from share s where" ++ toViewer(v)).query[UUID].to[List].map(_.toArray)
}
