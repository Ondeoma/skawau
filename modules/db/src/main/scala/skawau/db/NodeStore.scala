package skawau.db

import sbirds.shared.AppError
import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.circe.jsonb.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import skawau.core.*
import skawau.shared.*

import java.time.Instant
import java.util.UUID

/** S3 に置いた中身。 */
final case class BlobInfo(
  key: String,
  size: Long,
  contentType: String,
  sha256: String)

/** 版の行（以前の版）。 */
final case class VersionRow(
  id: UUID,
  nodeId: UUID,
  version: Int,
  size: Long,
  contentType: Option[String],
  blobKey: String,
  createdAt: Instant,
  createdBy: Option[UUID])

/** ファイル・フォルダ・版・ごみ箱・お気に入り。変更履歴（file_event）も同じトランザクションで書く。 */
final class NodeStore(
  xa: Transactor[IO]) extends TxStore(xa) {

  private val colNames = List(
    "id",
    "owner_id",
    "parent_id",
    "ancestors",
    "name",
    "is_dir",
    "size",
    "content_type",
    "blob_key",
    "sha256",
    "etag",
    "version",
    "created_at",
    "updated_at",
    "modified_by",
    "deleted_at"
  )

  /** select 用（別名 n）。 */
  private val cols = Fragment.const(colNames.map("n." + _).mkString(", "))

  /** returning 用。 */
  private val returning = Fragment.const("returning " + colNames.mkString(", "))

  /** ごみ箱に入っていない（自分も祖先も）。 */
  private val alive =
    fr"n.deleted_at is null and not exists (select 1 from node a where a.id = any(n.ancestors) and a.deleted_at is not null)"

  import Visibility.toViewer

  /** 見ている人の権限（owner / edit / view / null）。 */
  private def permFr(
    v: Viewer
  ): Fragment =
    fr"case when n.owner_id = ${v.userId} then 'owner' else (select case when bool_or(s.permission = 'edit') then 'edit' when count(*) > 0 then 'view' end" ++
      fr"from share s where s.node_id = any(n.ancestors || n.id) and" ++ toViewer(v) ++ fr") end"

  private def selectDto(
    v: Viewer
  ): Fragment =
    fr"select" ++ cols ++ fr"," ++ permFr(v) ++
      fr""", ou.id, ou.name, ou.avatar_url, mu.id, mu.name, mu.avatar_url,
          exists(select 1 from favorite f where f.user_id = ${v.userId} and f.node_id = n.id),
          exists(select 1 from share s where s.node_id = n.id and s.kind <> 'link'),
          exists(select 1 from share s where s.node_id = n.id and s.kind = 'link')
          from node n join app_user ou on ou.id = n.owner_id left join app_user mu on mu.id = n.modified_by"""

  private type DtoRow = (Node, Option[String], UserBrief, Option[UserBrief], Boolean, Boolean, Boolean)

  private def toDto(
    r: DtoRow
  ): (Node, NodeDto) = {
    val (n, perm, owner, mod, fav, shared, link) = r
    n -> NodeDto(
      n.id,
      n.parentId,
      n.name,
      n.isDir,
      n.size,
      n.contentType,
      n.createdAt,
      n.updatedAt,
      mod,
      owner,
      n.version,
      n.etag,
      perm.getOrElse(Perm.View),
      fav,
      shared,
      link
    )
  }

  private def dtos(
    v: Viewer,
    where: Fragment,
    tail: Fragment = Fragment.empty
  ): ConnectionIO[List[(Node, NodeDto)]] = (selectDto(v) ++ fr"where" ++ where ++ tail).query[DtoRow].map(
    toDto
  ).to[List]

  private def newEtag(): String = UUID.randomUUID().toString.replace("-", "").take(16)

  // ---------------------------------------------------------------- 取得

  /** ユーザーのルートのフォルダ（なければ作る）。 */
  def root(
    userId: UUID
  ): IO[Node] = tx(rootC(userId))

  def rootC(
    userId: UUID
  ): ConnectionIO[Node] =
    sql"""insert into node (id, owner_id, parent_id, name, is_dir, etag) values (${UUID.randomUUID()}, $userId, null, '', true, ${newEtag()})
          on conflict (owner_id) where parent_id is null do nothing""".update.run *>
      (fr"select" ++ cols ++ fr"from node n where n.owner_id = $userId and n.parent_id is null").query[Node].unique

  /** 生きている（ごみ箱にない）ノード。権限は見ない。 */
  def find(
    id: UUID
  ): IO[Option[Node]] = tx(findC(id))

  def findC(
    id: UUID
  ): ConnectionIO[
    Option[Node]
  ] = (fr"select" ++ cols ++ fr"from node n where n.id = $id and" ++ alive).query[Node].option

  /** ごみ箱にあるものも含めて取る。 */
  def findAny(
    id: UUID
  ): IO[Option[Node]] = tx((fr"select" ++ cols ++ fr"from node n where n.id = $id").query[Node].option)

  /** 見ている人の権限つきで取る（見えなければ None）。 */
  def access(
    v: Viewer,
    id: UUID
  ): IO[Option[(Node, Access)]] = tx(accessC(v, id))

  def accessC(
    v: Viewer,
    id: UUID
  ): ConnectionIO[Option[(Node, Access)]] =
    (fr"select" ++ cols ++ fr"," ++ permFr(v) ++ fr"from node n where n.id = $id and" ++ alive)
      .query[(Node, Option[String])].option.map(_.flatMap(
        (
          n,
          p
        ) => p.flatMap(Access.parse).map(n -> _)
      ))

  def dto(
    v: Viewer,
    id: UUID
  ): IO[Option[NodeDto]] = tx(dtos(v, fr"n.id = $id")).map(_.headOption.map(_._2))

  /** まとめて取る（生きているものだけ）。 */
  def dtosByIds(
    v: Viewer,
    ids: List[UUID]
  ): IO[List[(Node, NodeDto)]] =
    if ids.isEmpty then IO.pure(Nil) else tx(dtos(v, fr"n.id = any(${ids.distinct.toArray}) and" ++ alive))

  /** ID からまとめて名前を引く（見られるものだけ。外部のサービスのリンクの表示用）。 */
  def lookup(
    v: Viewer,
    ids: List[UUID]
  ): IO[List[(UUID, String, Boolean, Option[String])]] =
    if ids.isEmpty then IO.pure(Nil)
    else
      tx(
        (fr"select n.id, n.name, n.is_dir, case when n.is_dir then null else n.content_type end from node n where n.id = any(${ids.toArray}) and n.parent_id is not null and" ++
          alive ++
          fr"and" ++ permFr(v) ++ fr"is not null").query[(UUID, String, Boolean, Option[String])].to[List]
      )

  /** フォルダの中身（名前順。フォルダが先）。 */
  def children(
    v: Viewer,
    folderId: UUID
  ): IO[List[NodeDto]] =
    tx(dtos(
      v,
      fr"n.parent_id = $folderId and n.deleted_at is null",
      fr"order by n.is_dir desc, lower(n.name)"
    )).map(_.map(_._2))

  /** 子の名前（同じ名前の確認用）。 */
  def childNamed(
    parentId: UUID,
    name: String
  ): IO[Option[Node]] = tx(childNamedC(parentId, name))

  private def childNamedC(
    parentId: UUID,
    name: String
  ): ConnectionIO[Option[Node]] =
    (fr"select" ++ cols ++
      fr"from node n where n.parent_id = $parentId and lower(n.name) = lower($name) and n.deleted_at is null").query[
      Node
    ].option

  private def childNamesC(
    parentId: UUID
  ): ConnectionIO[Set[String]] =
    sql"select name from node where parent_id = $parentId and deleted_at is null".query[String].to[List].map(_.toSet)

  /** WebDAV のパスをたどる（大文字小文字は区別しない）。 */
  def resolve(
    rootId: UUID,
    segments: List[String]
  ): IO[Option[Node]] = tx(
    findC(rootId).flatMap(start =>
      segments.foldLeftM(start)(
        (
          cur,
          seg
        ) => cur.filter(_.isDir).flatTraverse(c => childNamedC(c.id, seg))
      )
    )
  )

  def childrenRaw(
    folderId: UUID
  ): IO[List[Node]] =
    tx((fr"select" ++ cols ++
      fr"from node n where n.parent_id = $folderId and n.deleted_at is null order by lower(n.name)").query[
      Node
    ].to[List])

  /** 子孫（ごみ箱にあるものの下は除く）と、起点からの相対パス（ZIP・コピー用）。フォルダも含む。親が先に来る。 */
  def subtree(
    node: Node
  ): IO[List[(Node, String)]] = tx(subtreeC(node))

  private def subtreeC(
    node: Node
  ): ConnectionIO[List[(Node, String)]] =
    (fr"select" ++ cols ++ fr"from node n where ${node.id} = any(n.ancestors) and" ++ alive ++
      fr"order by cardinality(n.ancestors), lower(n.name)")
      .query[Node].to[List].map { ns =>
        val names = scala.collection.mutable.Map(node.id -> "")
        ns.flatMap { n =>
          n.parentId.flatMap(names.get).map { base =>
            val p = if base.isEmpty then n.name else s"$base/${n.name}"
            names(n.id) = p
            n -> p
          }
        }
      }

  // ---------------------------------------------------------------- パス

  /** 所有者から見たパス（"/a/b/name"。ルートは "/"）。 */
  def pathC(
    n: Node
  ): ConnectionIO[String] =
    sql"""select coalesce(string_agg(name, '/' order by array_position(${n.ancestors.toArray}, id)), '') from node
          where id = any(${n.ancestors.toArray}) and parent_id is not null""".query[String].unique
      .map(p => if n.isRoot then "/" else ("/" + p + "/").replace("//", "/") + n.name)

  def path(
    n: Node
  ): IO[String] = tx(pathC(n))

  /** 見ている人がたどれるパンくず（共有されたものなら共有の起点から）と、共有されたものか。 */
  def crumbs(
    v: Viewer,
    nodes: List[Node]
  ): IO[Map[UUID, (List[Crumb], Boolean)]] =
    if nodes.isEmpty then IO.pure(Map.empty)
    else
      tx {
        val ids = nodes.flatMap(_.lineage).distinct.toArray
        for {
          names <- sql"select id, name from node where id = any($ids)".query[(UUID, String)].to[List].map(_.toMap)
          shared <- (fr"select s.node_id from share s where s.node_id = any($ids) and" ++ toViewer(v)).query[
            UUID
          ].to[List].map(_.toSet)
        } yield nodes.map { n =>
          val line = n.lineage
          if n.ownerId == v.userId then n.id -> (line.map(id => Crumb(id, names.getOrElse(id, ""))), false)
          else {
            val start = line.indexWhere(shared.contains)
            n.id -> (line.drop(math.max(start, 0)).map(id => Crumb(id, names.getOrElse(id, ""))), true)
          }
        }.toMap
      }

  /** 一覧の項目に場所（親のパス）を付ける。 */
  def withPaths(
    v: Viewer,
    items: List[(Node, NodeDto)]
  ): IO[List[NodeHitDto]] =
    crumbs(v, items.map(_._1)).map { m =>
      items.map {
        (
          n,
          d
        ) =>
          {
            val (cs, inShare) = m.getOrElse(n.id, (Nil, false))
            val parents = cs.dropRight(1).map(c => if c.name.isEmpty then "" else c.name)
            val shown = (if inShare then "共有されたもの" :: parents else parents.filter(_.nonEmpty)).mkString("/")
            NodeHitDto(d, "/" + shown)
          }
      }
    }

  // ---------------------------------------------------------------- 一覧

  /** 見られるもの（自分の・共有されたもの）の条件。 */
  private def visible(
    v: Viewer,
    roots: Array[UUID]
  ): Fragment =
    fr"(n.owner_id = ${v.userId} or n.id = any($roots) or n.ancestors && $roots) and" ++ alive

  /** 最近更新したファイル。 */
  def recent(
    v: Viewer,
    limit: Int
  ): IO[List[(Node, NodeDto)]] = tx(
    Visibility.sharedRoots(v).flatMap(roots =>
      dtos(v, fr"not n.is_dir and" ++ visible(v, roots), fr"order by n.updated_at desc limit $limit")
    )
  )

  def favorites(
    v: Viewer
  ): IO[List[(Node, NodeDto)]] = tx(
    Visibility.sharedRoots(v).flatMap(roots =>
      dtos(
        v,
        fr"n.id in (select node_id from favorite where user_id = ${v.userId}) and" ++ visible(v, roots),
        fr"order by n.is_dir desc, lower(n.name)"
      )
    )
  )

  /** 名前の検索（日本語の部分一致・かなの揺れを吸収。PGroonga）。 */
  def search(
    v: Viewer,
    q: String,
    limit: Int
  ): IO[List[(Node, NodeDto)]] = tx(
    Visibility.sharedRoots(v).flatMap { roots =>
      val words = q.trim.split("\\s+").toList.filter(_.nonEmpty).take(5)
      val cond =
        words.map(w => fr"(n.name &@~ ${Search.phrase(w)} or n.name ilike ${Search.contains(w)})").reduceOption(_ ++
          fr"and" ++ _).getOrElse(fr"false")
      dtos(
        v,
        fr"n.parent_id is not null and" ++ cond ++ fr"and" ++ visible(v, roots),
        fr"order by n.is_dir desc, n.updated_at desc limit $limit"
      )
    }
  )

  /** 共有されたもの（起点ごと。自分のものは除く）。 */
  def sharedWithMe(
    v: Viewer
  ): IO[List[(Node, NodeDto)]] = tx(
    Visibility.sharedRoots(v).flatMap(roots =>
      dtos(
        v,
        fr"n.id = any($roots) and n.owner_id <> ${v.userId} and" ++ alive,
        fr"order by n.is_dir desc, lower(n.name)"
      )
    )
  )

  // ---------------------------------------------------------------- 容量

  /** 使用量（ファイルの大きさの合計。ごみ箱と以前の版を含む）。 */
  def usage(
    ownerId: UUID
  ): IO[Long] = tx(usageC(ownerId))

  private def usageC(
    ownerId: UUID
  ): ConnectionIO[Long] =
    sql"""select (select coalesce(sum(size), 0) from node where owner_id = $ownerId and not is_dir)
               + (select coalesce(sum(v.size), 0) from file_version v join node n on n.id = v.node_id where n.owner_id = $ownerId)""".query[
      Long
    ].unique

  def stats: IO[StatsDto] = tx(
    sql"""select (select count(*)::int from app_user),
                 (select count(*) from node where not is_dir), (select count(*) from node where is_dir and parent_id is not null),
                 (select coalesce(sum(size), 0)::bigint from node where not is_dir),
                 (select coalesce(sum(size), 0)::bigint from file_version),
                 (select coalesce(sum(size), 0)::bigint from node where deleted_at is not null and not is_dir),
                 (select count(*) from share where kind <> 'link'), (select count(*) from share where kind = 'link')"""
      .query[StatsDto].unique
  )

  // ---------------------------------------------------------------- 作成・更新

  private def insertC(
    owner: UUID,
    parent: Node,
    name: String,
    isDir: Boolean,
    blob: Option[BlobInfo],
    actor: Actor
  ): ConnectionIO[Node] =
    (fr"insert into node (id, owner_id, parent_id, ancestors, name, is_dir, size, content_type, blob_key, sha256, etag, modified_by) values (" ++
      fr"${UUID.randomUUID()}, $owner, ${parent.id}, ${parent.lineage.toArray}, $name, $isDir, ${blob.fold(0L)(_.size)}, ${blob.map(_.contentType)}," ++
      fr"${blob.map(_.key)}, ${blob.map(_.sha256)}, ${newEtag()}, ${actor.userId})" ++ returning)
      .query[Node].unique

  private def touchParent(
    parentId: UUID
  ): ConnectionIO[Unit] =
    sql"update node set etag = ${newEtag()}, updated_at = now() where id = $parentId".update.run.void

  private def unique(
    e: Throwable
  ): Boolean = e match {
    case s: java.sql.SQLException => s.getSQLState == "23505"
    case _ => false
  }

  /** 一意制約の違反（同じ名前）を Conflict にする。 */
  private def guarded[A](
    c: IO[A]
  ): IO[Either[AppError, A]] =
    c.attempt.flatMap {
      case Right(a) => IO.pure(Right(a))
      case Left(e) if unique(e) => IO.pure(Left(AppError(ErrorCode.NodeNameTaken)))
      case Left(e) => IO.raiseError(e)
    }

  def createFolder(
    parent: Node,
    name: String,
    actor: Actor
  ): IO[Either[AppError, Node]] = guarded(tx(
    for {
      n <- insertC(parent.ownerId, parent, name, isDir = true, None, actor)
      _ <- touchParent(parent.id)
      p <- pathC(n)
      _ <- Events.insert(n, actor, EventKind.Created, p)
    } yield n
  ))

  /** パスの途中のフォルダを作りながらたどる（フォルダごとのアップロード用）。 */
  def ensureFolders(
    parent: Node,
    segments: List[String],
    actor: Actor
  ): IO[Either[AppError, Node]] =
    segments.foldLeftM[IO, Either[AppError, Node]](Right(parent)) {
      case (Left(e), _) => IO.pure(Left(e))
      case (Right(cur), seg) =>
        childNamed(cur.id, seg).flatMap {
          case Some(n) if n.isDir => IO.pure(Right(n))
          case Some(_) => IO.pure(Left(AppError(ErrorCode.FolderBlockedByFile, s"「$seg」という名前のファイルがあるため、フォルダを作れません")))
          case None => createFolder(cur, seg, actor).flatMap {
              case Left(e) if e.code == ErrorCode.NodeNameTaken =>
                childNamed(
                  cur.id,
                  seg
                ).map(_.filter(_.isDir).toRight(AppError(ErrorCode.FolderCreateFailed, s"「$seg」を作れません")))
              case other => IO.pure(other)
            }
        }
    }

  def createFile(
    parent: Node,
    name: String,
    blob: BlobInfo,
    actor: Actor,
    kind: String = EventKind.Uploaded
  ): IO[Either[AppError, Node]] = guarded(tx(
    for {
      n <- insertC(parent.ownerId, parent, name, isDir = false, Some(blob), actor)
      _ <- touchParent(parent.id)
      p <- pathC(n)
      _ <- Events.insert(n, actor, kind, p, Json.obj("size" -> Json.fromLong(blob.size)))
    } yield n
  ))

  /**
   * 中身を置き換える（今の版を以前の版に回し、版を1つ進める）。keep を超えた古い版は消す（S3 の中身は blob_trash へ）。
   * expectedVersion を渡すと、その版でなければ Conflict（同時の上書きを防ぐ）。
   */
  def replaceContent(
    node: Node,
    blob: BlobInfo,
    actor: Actor,
    keep: Int,
    kind: String = EventKind.Updated,
    expectedVersion: Option[Int] = None
  ): IO[Either[AppError, Node]] = tx(
    (fr"select" ++ cols ++ fr"from node n where n.id = ${node.id} and not n.is_dir for update").query[
      Node
    ].option.flatMap {
      case None => fail(AppError(ErrorCode.FileNotFound))
      case Some(cur) if expectedVersion.exists(_ != cur.version) =>
        fail(AppError(ErrorCode.EditConflict))
      case Some(cur) =>
        for {
          // 空（0バイト）の版は残さない（WebDAV のクライアントは空のファイルを作ってから中身を書くため）
          _ <- queueBlobsC(cur.blobKey.filter(_ => cur.size == 0).toList)
          _ <- cur.blobKey.filter(_ => cur.size > 0).traverse_(k =>
            sql"""insert into file_version (id, node_id, version, size, content_type, blob_key, sha256, created_at, created_by)
                       values (${UUID.randomUUID()}, ${cur.id}, ${cur.version}, ${cur.size}, ${cur.contentType}, $k, ${cur.sha256}, ${cur.updatedAt},
                               ${cur.modifiedBy})""".update.run
          )
          n <-
          (fr"""update node set size = ${blob.size}, content_type = ${blob.contentType}, blob_key = ${blob.key}, sha256 = ${blob.sha256},
                     version = version + 1, etag = ${newEtag()}, updated_at = now(), modified_by = ${actor.userId} where id = ${cur.id}""" ++
            returning).query[Node].unique
          _ <- pruneVersionsC(cur.id, keep)
          p <- pathC(n)
          _ <- Events.insert(n, actor, kind, p, Json.obj("version" -> Json.fromInt(n.version)))
        } yield Right(n)
    }
  )

  private def pruneVersionsC(
    nodeId: UUID,
    keep: Int
  ): ConnectionIO[Unit] =
    sql"""with old as (delete from file_version where node_id = $nodeId and id not in
            (select id from file_version where node_id = $nodeId order by version desc limit ${math.max(
        keep,
        0
      )}) returning blob_key)
          insert into blob_trash (key) select blob_key from old on conflict do nothing""".update.run.void

  /** 名前の変更。 */
  def rename(
    node: Node,
    name: String,
    actor: Actor
  ): IO[Either[AppError, Node]] = guarded(tx(
    for {
      old <- pathC(node)
      n <-
      (fr"update node set name = $name, content_type = ${contentTypeOf(node, name)}, etag = ${newEtag()} where id = ${node.id}" ++
        returning)
        .query[Node].unique
      _ <- node.parentId.traverse_(touchParent)
      p <- pathC(n)
      _ <- Events.insert(
        n,
        actor,
        EventKind.Renamed,
        p,
        Json.obj("from" -> Json.fromString(node.name), "fromPath" -> Json.fromString(old))
      )
    } yield n
  ))

  /** 名前から決めた Content-Type（名前を変えたら直す）。 */
  private def contentTypeOf(
    node: Node,
    name: String
  ): Option[String] = if node.isDir then None else Some(FileKind.contentType(name))

  /** 親を付け替える（自分と子孫の ancestors を1つの UPDATE ずつで直す）。 */
  private def reparentC(
    node: Node,
    dest: Node,
    name: String
  ): ConnectionIO[Unit] =
    sql"""update node set parent_id = ${dest.id}, ancestors = ${dest.lineage.toArray}, name = $name, content_type = ${contentTypeOf(
        node,
        name
      )},
          etag = ${newEtag()}
          where id = ${node.id}""".update.run *>
      sql"""update node set ancestors = ${dest.lineage.toArray} || ancestors[array_position(ancestors, ${node.id}):]
            where ${node.id} = any(ancestors)""".update.run.void

  /** S3 の中身を後で消すよう積む。 */
  private def queueBlobsC(
    keys: List[String]
  ): ConnectionIO[Unit] =
    keys.traverse_(k => sql"insert into blob_trash (key) values ($k) on conflict do nothing".update.run)

  /** 移動（子孫の ancestors も付け替える。所有者の違うフォルダへ移すと、子孫ごと所有者を移す）。 */
  def move(
    node: Node,
    target0: Node,
    name: String,
    actor: Actor
  ): IO[Either[AppError, Node]] =
    find(target0.id).flatMap {
      case None => IO.pure(Left(AppError(ErrorCode.MoveTargetNotFound)))
      case Some(target) => moveTo(node, target, name, actor)
    }

  private def moveTo(
    node: Node,
    target: Node,
    name: String,
    actor: Actor
  ): IO[Either[AppError, Node]] =
    if target.lineage.contains(node.id) then IO.pure(Left(AppError(ErrorCode.MoveIntoSelf)))
    else
      guarded(tx(
        for {
          old <- pathC(node)
          _ <- detachTrashedC(node).whenA(target.ownerId != node.ownerId)
          _ <- reparentC(node, target, name)
          _ <-
          (sql"update node set owner_id = ${target.ownerId} where id = ${node.id} or ${node.id} = any(ancestors)".update.run *>
            // 共有・リンクも新しい所有者のものにする（前の所有者が操作し続けられないように）
            sql"""update share set owner_id = ${target.ownerId}
                       where node_id in (select id from node where id = ${node.id} or ${node.id} = any(ancestors))""".update.run)
            .whenA(target.ownerId != node.ownerId)
          _ <- node.parentId.traverse_(touchParent) *> touchParent(target.id)
          n <- findC(node.id).map(_.get)
          p <- pathC(n)
          _ <- Events.insert(n, actor, EventKind.Moved, p, Json.obj("fromPath" -> Json.fromString(old)))
        } yield n
      ))

  /**
   * 所有者が変わる移動の前に、中のごみ箱にあるものを元の所有者のルートの下へ外す
   * （ごみ箱の中身・その版が、移った先の所有者に渡らないように。ごみ箱の一覧の「元の場所」はそのまま）。
   */
  private def detachTrashedC(
    node: Node
  ): ConnectionIO[Unit] =
    for {
      root <- rootC(node.ownerId)
      ts <-
      (fr"select" ++ cols ++ fr"from node n where ${node.id} = any(n.ancestors) and n.deleted_at is not null").query[
        Node
      ].to[List]
      tops = ts.filterNot(t => ts.exists(o => t.ancestors.contains(o.id)))
      _ <- tops.traverse_(t => reparentC(t, root, t.name))
    } yield ()

  /**
   * コピー（フォルダは中身ごと）。S3 の中身の複製は呼び出し側で行い、copyBlob で新しいキーを受け取る。
   * 失敗したときは作った S3 の中身を blob_trash に積む。
   */
  def copy(
    node: Node,
    target0: Node,
    name: String,
    actor: Actor,
    copyBlob: String => IO[String]
  ): IO[Either[AppError, Node]] =
    find(target0.id).flatMap {
      case None => IO.pure(Left(AppError(ErrorCode.CopyTargetNotFound)))
      case Some(target) if target.lineage.contains(node.id) => IO.pure(Left(AppError(ErrorCode.CopyIntoSelf)))
      case Some(target) => copyTo(node, target, name, actor, copyBlob)
    }

  private def copyTo(
    node: Node,
    target: Node,
    name: String,
    actor: Actor,
    copyBlob: String => IO[String]
  ): IO[Either[AppError, Node]] =
    subtree(node).flatMap { desc =>
      val all = (node, "") :: desc
      all.traverse(
        (
          n,
          _
        ) => n.blobKey.traverse(copyBlob).map(n.id -> _)
      ).flatMap { keys =>
        val newKeys = keys.toMap
        val ids = all.map(
          (
            n,
            _
          ) => n.id -> UUID.randomUUID()
        ).toMap
        val created = newKeys.values.flatten.toList
        val ins = all.traverse {
          (
            n,
            _
          ) =>
            {
              val parentLine: List[UUID] =
                if n.id == node.id then target.lineage
                else n.ancestors.drop(n.ancestors.indexOf(node.id)).map(ids) // node 以下の祖先を新しい ID に
              val anc = if n.id == node.id then parentLine else target.lineage ++ parentLine
              val pid = if n.id == node.id then target.id else ids(n.parentId.get)
              val nm = if n.id == node.id then name else n.name
              sql"""insert into node (id, owner_id, parent_id, ancestors, name, is_dir, size, content_type, blob_key, sha256, etag, modified_by)
            values (${ids(
                  n.id
                )}, ${target.ownerId}, $pid, ${anc.toArray}, $nm, ${n.isDir}, ${n.size}, ${n.contentType},
                    ${newKeys.get(n.id).flatten}, ${n.sha256}, ${newEtag()}, ${actor.userId})""".update.run
            }
        }
        guarded(tx(
          for {
            _ <- ins
            _ <- touchParent(target.id)
            c <- findC(ids(node.id)).map(_.get)
            p <- pathC(c)
            _ <- Events.insert(c, actor, EventKind.Copied, p)
          } yield c
        )).flatTap {
          case Left(_) => tx(queueBlobsC(created))
          case Right(_) => IO.unit
        }
      }
    }

  /** 表示用の一意な名前（同じ名前があれば「名前 (2)」）。 */
  def uniqueName(
    parentId: UUID,
    name: String
  ): IO[String] = tx(childNamesC(parentId)).map(Names.unique(name, _))

  // ---------------------------------------------------------------- ごみ箱

  def trash(
    node: Node,
    actor: Actor
  ): IO[Either[AppError, Unit]] =
    if node.isRoot then IO.pure(Left(AppError(ErrorCode.RootNotDeletable)))
    else
      tx(
        for {
          p <- pathC(node)
          _ <-
            sql"""update node set deleted_at = now(), deleted_by = ${actor.userId}, trash_path = $p where id = ${node.id}""".update.run
          _ <- node.parentId.traverse_(touchParent)
          _ <- Events.insert(node, actor, EventKind.Trashed, p)
        } yield Right(())
      ).flatTap(_ => sbirds.observe.Metrics.count("skawau.trash.moved", "via" -> actor.via))

  def trashList(
    ownerId: UUID
  ): IO[List[TrashItemDto]] = tx(
    sql"""select n.id, n.name, n.is_dir,
                 case when n.is_dir then coalesce((select sum(c.size) from node c where n.id = any(c.ancestors) and not c.is_dir), 0) else n.size end::bigint,
                 n.deleted_at, coalesce(n.trash_path, '/' || n.name), u.id, u.name, u.avatar_url
          from node n left join app_user u on u.id = n.deleted_by
          where n.owner_id = $ownerId and n.deleted_at is not null order by n.deleted_at desc"""
      .query[(UUID, String, Boolean, Long, Instant, String, Option[UserBrief])]
      .map(
        (
          id,
          n,
          d,
          s,
          at,
          p,
          by
        ) =>
          TrashItemDto(
            id,
            n,
            d,
            s,
            at,
            p.substring(0, math.max(p.lastIndexOf('/'), 0)) match { case "" => "/"; case x => x },
            by
          )
      )
      .to[List]
  )

  /** ごみ箱にある起点のノード（所有者のもの）。 */
  def trashed(
    ownerId: UUID,
    id: UUID
  ): IO[Option[Node]] =
    tx((fr"select" ++ cols ++
      fr"from node n where n.id = $id and n.owner_id = $ownerId and n.deleted_at is not null").query[Node].option)

  /** 元の場所へ戻す（元のフォルダがなければルートへ。同じ名前があれば「名前 (2)」）。 */
  def restore(
    node: Node,
    actor: Actor
  ): IO[Node] = tx(
    for {
      parent <- node.parentId.flatTraverse(findC)
      dest <- parent.fold(rootC(node.ownerId))(_.pure[ConnectionIO])
      names <- childNamesC(dest.id)
      name = Names.unique(node.name, names)
      // 名前・親を先に直す（ごみ箱の印を外すと同じ名前の一意制約の対象になるため）
      _ <- reparentC(node, dest, name)
      _ <- sql"update node set deleted_at = null, deleted_by = null, trash_path = null where id = ${node.id}".update.run
      _ <- touchParent(dest.id)
      n <- findC(node.id).map(_.get)
      p <- pathC(n)
      _ <- Events.insert(n, actor, EventKind.Restored, p)
    } yield n
  ).flatTap(_ => sbirds.observe.Metrics.count("skawau.trash.restored", "via" -> actor.via))

  /** 完全に削除する（子孫・版・共有ごと。S3 の中身は blob_trash に積んで後で消す）。期限切れの自動の削除は via が system。 */
  def purge(
    node: Node,
    actor: Actor
  ): IO[Unit] = tx(
    for {
      _ <- sql"""insert into blob_trash (key)
                 select blob_key from node where (id = ${node.id} or ${node.id} = any(ancestors)) and blob_key is not null
                 union select v.blob_key from file_version v join node n on n.id = v.node_id where n.id = ${node.id} or ${node.id} = any(n.ancestors)
                 on conflict do nothing""".update.run
      p <- sql"select coalesce(trash_path, name) from node where id = ${node.id}".query[String].unique
      _ <- sql"delete from node where id = ${node.id}".update.run
      _ <- Events.insert(node, actor, EventKind.Deleted, p)
    } yield ()
  ).flatTap(_ => sbirds.observe.Metrics.count("skawau.trash.purged", "via" -> actor.via))

  /** 期限を過ぎたごみ箱の起点（自動で完全に削除する）。 */
  def expiredTrash(
    days: Int,
    limit: Int
  ): IO[List[Node]] =
    tx((fr"select" ++ cols ++
      fr"from node n where n.deleted_at < now() - make_interval(days => $days) order by n.deleted_at limit $limit").query[
      Node
    ].to[List])

  // ---------------------------------------------------------------- 版

  def versions(
    nodeId: UUID
  ): IO[List[(VersionRow, Option[UserBrief])]] = tx(
    sql"""select v.id, v.node_id, v.version, v.size, v.content_type, v.blob_key, v.created_at, v.created_by, u.id, u.name, u.avatar_url
          from file_version v left join app_user u on u.id = v.created_by where v.node_id = $nodeId order by v.version desc"""
      .query[(VersionRow, Option[UserBrief])].to[List]
  )

  def version(
    nodeId: UUID,
    id: UUID
  ): IO[Option[VersionRow]] = tx(
    sql"select id, node_id, version, size, content_type, blob_key, created_at, created_by from file_version where node_id = $nodeId and id = $id"
      .query[VersionRow].option
  )

  def deleteVersion(
    nodeId: UUID,
    id: UUID
  ): IO[Boolean] = tx(
    sql"""with d as (delete from file_version where node_id = $nodeId and id = $id returning blob_key)
          insert into blob_trash (key) select blob_key from d on conflict do nothing""".update.run.map(_ > 0)
  )

  /** 変更履歴だけを書く（共有など、ノード自体は変えない操作）。 */
  def event(
    n: Node,
    actor: Actor,
    kind: String,
    detail: Json = Json.obj()
  ): IO[Unit] = tx(pathC(n).flatMap(p => Events.insert(n, actor, kind, p, detail)))

  // ---------------------------------------------------------------- お気に入り

  def setFavorite(
    userId: UUID,
    nodeId: UUID,
    on: Boolean
  ): IO[Unit] = tx(
    if on then
      sql"insert into favorite (user_id, node_id) values ($userId, $nodeId) on conflict do nothing".update.run.void
    else sql"delete from favorite where user_id = $userId and node_id = $nodeId".update.run.void
  )

  // ---------------------------------------------------------------- S3 の後片付け

  /** blob_trash のキーを消す（SKIP LOCKED で複数ノードが取り合う）。 */
  def drainBlobTrash(
    delete: String => IO[Unit]
  ): IO[Int] =
    processEach(
      sql"select key from blob_trash where attempts < 20 order by queued_at limit 100 for update skip locked".query[
        String
      ].to[List]
    )(k => {
      delete(k).attempt
    })(
      (
        k,
        r
      ) =>
        r match {
          case Right(_) => sql"delete from blob_trash where key = $k".update.run
          case Left(_) => sql"update blob_trash set attempts = attempts + 1 where key = $k".update.run
        }
    )
}

/** 変更履歴の書き込み（同じトランザクションで呼ぶ）。 */
object Events {
  def insert(
    n: Node,
    actor: Actor,
    kind: String,
    path: String,
    detail: Json = Json.obj()
  ): ConnectionIO[Unit] =
    sql"""insert into file_event (node_id, owner_id, lineage, actor_id, via, kind, name, path, detail)
          values (${n.id}, ${n.ownerId}, ${n.lineage.toArray}, ${actor.userId}, ${actor.via}, $kind, ${n.name}, $path, $detail)""".update.run.void
}
