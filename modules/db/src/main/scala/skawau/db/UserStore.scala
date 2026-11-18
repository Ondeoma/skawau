package skawau.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import skawau.core.*
import skawau.shared.*

import java.time.Instant
import java.util.UUID

/** ユーザー・グループ・セッション・ログイン途中の状態・APIキー。 */
final class UserStore(xa: Transactor[IO]) extends TxStore(xa):

  private val userCols = fr"u.id, u.sub, u.name, u.email, u.avatar_url, u.is_admin, u.disabled, u.quota_bytes"

  // ---------------------------------------------------------------- ユーザー

  /** ログイン時に作成・更新する。 */
  def upsert(sub: String, name: String, email: Option[String], avatar: Option[String]): IO[User] = tx(
    sql"""insert into app_user (id, sub, name, email, avatar_url, last_login_at) values (${UUID.randomUUID()}, $sub, $name, $email, $avatar, now())
          on conflict (sub) do update set name = excluded.name, email = excluded.email,
            avatar_url = case when app_user.avatar_key is not null then app_user.avatar_url else excluded.avatar_url end,
            last_login_at = now()
          returning id, sub, name, email, avatar_url, is_admin, disabled, quota_bytes""".query[User].unique
  )

  def find(id: UUID): IO[Option[User]] = tx(findC(id))

  def findC(id: UUID): ConnectionIO[Option[User]] =
    (fr"select" ++ userCols ++ fr"from app_user u where u.id = $id").query[User].option

  def brief(id: UUID): IO[Option[UserBrief]] = tx(sql"select id, name, avatar_url from app_user where id = $id".query[UserBrief].option)

  /** ログイン時に IdP の groups クレームで所属を置き換える。 */
  def syncGroups(userId: UUID, names: List[String]): IO[Unit] = tx {
    val ns = names.map(_.trim).filter(_.nonEmpty).distinct
    for
      ids <- ns.traverse(n =>
        sql"""insert into user_group (id, name) values (${UUID.randomUUID()}, $n)
              on conflict (name) do update set name = excluded.name returning id""".query[UUID].unique)
      _ <- sql"delete from group_member where user_id = $userId".update.run
      _ <- ids.traverse_(g => sql"insert into group_member (group_id, user_id) values ($g, $userId) on conflict do nothing".update.run)
    yield ()
  }

  def groupsOf(userId: UUID): ConnectionIO[List[Group]] =
    sql"select g.id, g.name from user_group g join group_member m on m.group_id = g.id where m.user_id = $userId order by g.name"
      .query[Group].to[List]

  def withGroups(userId: UUID): IO[Option[(User, List[Group])]] = tx(
    findC(userId).flatMap(_.filterNot(_.disabled).traverse(u => groupsOf(u.id).map(u -> _)))
  )

  /** 共有の相手の候補（名前・メールで絞る。自分は除く）。 */
  def sharees(q: String, exclude: UUID, limit: Int = 10): IO[ShareeDto] = tx {
    val t    = q.trim
    val like = Search.contains(t)
    for
      us <- sql"""select id, name, avatar_url from app_user
                  where not disabled and id <> $exclude and ($t = '' or name &@~ ${Search.phrase(t)} or name ilike $like or email ilike $like)
                  order by last_login_at desc nulls last, name limit $limit""".query[UserBrief].to[List]
      gs <- sql"""select g.id, g.name, (select count(*)::int from group_member m where m.group_id = g.id) from user_group g
                  where $t = '' or g.name &@~ ${Search.phrase(t)} or g.name ilike $like order by g.name limit $limit""".query[GroupBrief].to[List]
    yield ShareeDto(us, gs)
  }

  /** 管理画面の一覧（使用量つき）。 */
  def list(q: Option[String]): IO[List[AdminUserDto]] = tx {
    val where = q.map(_.trim).filter(_.nonEmpty).map(s => fr"where u.name ilike ${Search.contains(s)} or u.email ilike ${Search.contains(s)}").getOrElse(Fragment.empty)
    (fr"""select u.id, u.name, u.email, u.avatar_url, u.is_admin, u.disabled, u.last_login_at,
                 coalesce((select array_agg(g.name order by g.name) from user_group g join group_member m on m.group_id = g.id where m.user_id = u.id), '{}'),
                 coalesce((select sum(n.size) from node n where n.owner_id = u.id and not n.is_dir), 0)::bigint, u.quota_bytes
          from app_user u""" ++ where ++ fr"order by u.name limit 1000")
      .query[(UUID, String, Option[String], Option[String], Boolean, Boolean, Option[Instant], List[String], Long, Option[Long])]
      .map(AdminUserDto.apply).to[List]
  }

  /** quota: Some(None) で既定に戻す。 */
  def update(id: UUID, isAdmin: Option[Boolean], disabled: Option[Boolean], quota: Option[Option[Long]]): IO[Boolean] = tx {
    val sets = List(isAdmin.map(v => fr"is_admin = $v"), disabled.map(v => fr"disabled = $v"), quota.map(v => fr"quota_bytes = $v")).flatten
    if sets.isEmpty then findC(id).map(_.isDefined)
    else
      for
        n <- (fr"update app_user" ++ Sql.set(sets) ++ fr"where id = $id").update.run
        _ <- if disabled.contains(true) then sql"delete from session where user_id = $id".update.run else 0.pure[ConnectionIO]
      yield n > 0
  }

  /** 画面から登録したアイコン（S3 のキー）。 */
  def avatarKey(id: UUID): IO[Option[String]] = tx(sql"select avatar_key from app_user where id = $id".query[Option[String]].option.map(_.flatten))

  /** アイコンを登録する（key が None なら外す）。前の画像は blob_trash に積んで後で消す。 */
  def setAvatar(id: UUID, key: Option[String], url: Option[String]): IO[Unit] = tx(
    for
      old <- sql"select avatar_key from app_user where id = $id".query[Option[String]].option.map(_.flatten)
      _   <- sql"update app_user set avatar_key = $key, avatar_url = $url where id = $id".update.run
      _   <- old.traverse_(k => sql"insert into blob_trash (key) values ($k) on conflict do nothing".update.run)
    yield ()
  )

  def updateMe(id: UUID, name: Option[String]): IO[Unit] =
    name.map(_.trim).filter(_.nonEmpty).traverse_(v => tx(sql"update app_user set name = $v where id = $id".update.run))

  // ---------------------------------------------------------------- セッション

  def createSession(hash: Array[Byte], userId: UUID, expires: Instant, idToken: Option[String] = None): IO[Unit] = tx(
    sql"insert into session (token_hash, user_id, expires_at, id_token) values ($hash, $userId, $expires, $idToken)".update.run.void
  )

  def findSession(hash: Array[Byte], now: Instant): IO[Option[(User, List[Group])]] = tx(
    sql"select user_id from session where token_hash = $hash and expires_at > $now".query[UUID].option.flatMap {
      case None => none[(User, List[Group])].pure[ConnectionIO]
      case Some(uid) => findC(uid).flatMap(_.filterNot(_.disabled).traverse(u => groupsOf(u.id).map(u -> _)))
    }
  )

  /** セッションを消し、IdP からログアウトするための id_token を返す. */
  def deleteSession(hash: Array[Byte]): IO[Option[String]] =
    tx(sql"delete from session where token_hash = $hash returning id_token".query[Option[String]].option.map(_.flatten))

  def purgeExpired(now: Instant): IO[Unit] = tx(
    sql"delete from session where expires_at < $now".update.run *>
      sql"delete from login_state where created_at < now() - interval '1 hour'".update.run.void
  )

  // ---------------------------------------------------------------- ログイン途中の状態（OIDC）

  def saveLoginState(state: String, verifier: String, nonce: String, returnTo: String): IO[Unit] = tx(
    sql"insert into login_state (state, verifier, nonce, return_to) values ($state, $verifier, $nonce, $returnTo)".update.run.void
  )

  /** 取り出して消す（1回だけ使える）。 */
  def takeLoginState(state: String): IO[Option[(String, String, String)]] = tx(
    sql"""delete from login_state where state = $state and created_at > now() - interval '15 minutes'
          returning verifier, nonce, return_to""".query[(String, String, String)].option
  )

  // ---------------------------------------------------------------- APIキー

  private type KeyRow = (UUID, String, String, List[String], Instant, Option[Instant], Option[Instant])

  def createKey(userId: UUID, name: String, prefix: String, hash: Array[Byte], scopes: List[String], expires: Option[Instant]): IO[ApiKeyDto] = tx(
    sql"""insert into api_key (id, user_id, name, prefix, key_hash, scopes, expires_at)
          values (${UUID.randomUUID()}, $userId, $name, $prefix, $hash, ${scopes.toArray}, $expires)
          returning id, name, prefix, scopes, created_at, expires_at, last_used_at""".query[KeyRow].unique.map(ApiKeyDto.apply)
  )

  def keys(userId: UUID): IO[List[ApiKeyDto]] = tx(
    sql"""select id, name, prefix, scopes, created_at, expires_at, last_used_at from api_key
          where user_id = $userId and revoked_at is null order by created_at desc""".query[KeyRow].map(ApiKeyDto.apply).to[List]
  )

  def revokeKey(userId: UUID, id: UUID): IO[Boolean] = tx(
    sql"update api_key set revoked_at = now() where id = $id and user_id = $userId and revoked_at is null".update.run.map(_ > 0)
  )

  /** 有効なキー（最後は外部のサービスとの連携で発行したものか）。最終使用日時は1分単位で記録する。 */
  def findKey(hash: Array[Byte], now: Instant): IO[Option[(User, List[Group], UUID, List[String], Boolean)]] = tx(
    sql"""select k.id, k.user_id, k.scopes, k.oauth_client is not null from api_key k join app_user u on u.id = k.user_id
          where k.key_hash = $hash and k.revoked_at is null and (k.expires_at is null or k.expires_at > $now) and not u.disabled"""
      .query[(UUID, UUID, List[String], Boolean)].option.flatMap {
        case None => none.pure[ConnectionIO]
        case Some((kid, uid, scopes, oauth)) =>
          for
            _  <- sql"update api_key set last_used_at = $now where id = $kid and (last_used_at is null or last_used_at < $now - interval '1 minute')".update.run
            u  <- findC(uid)
            gs <- groupsOf(uid)
          yield u.map(x => (x, gs, kid, scopes, oauth))
      }
  )

/** 検索語の組み立て。 */
object Search:
  /** PGroonga の検索語（構文を注入されないよう、フレーズとしてエスケープする）。 */
  def phrase(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** LIKE の部分一致（% と _ は文字として扱う。PostgreSQL の既定のエスケープ文字は \）。 */
  def contains(s: String): String = "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
