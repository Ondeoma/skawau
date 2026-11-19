package skawau.api

import sbirds.shared.AppError
import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.Crypto
import skawau.auth.Passwords
import skawau.core.*
import skawau.db.*
import skawau.shared.*

import java.util.UUID

/** ファイル・フォルダ・版・ごみ箱・お気に入り・検索・共有の REST API。 */
final class FileRoutes(
  files: FileService,
  nodes: NodeStore,
  shares: ShareStore,
  activity: ActivityStore,
  users: UserStore,
  notifier: Notifier,
  val sessions: SessionAuth,
  cfg: ApiConfig) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ---------------------------------------------------------------- フォルダ

    case req @ GET -> Root / "api" / "files" / "root" => getFilesRoot(req)
    case req @ GET -> Root / "api" / "files" / "resolve" => getFilesResolve(req)
    case req @ GET -> Root / "api" / "files" / UuidVar(id) => getFile(req, id)
    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "children" => getFileChildren(req, id)
    case req @ POST -> Root / "api" / "files" / UuidVar(id) / "folders" => postFileFolders(req, id)
    case req @ POST -> Root / "api" / "files" / UuidVar(id) / "new" => postFileNew(req, id)
    case req @ PUT -> Root / "api" / "files" / UuidVar(id) / "upload" => putFileUpload(req, id)
    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "content" => getFileContent(req, id)
    case req @ PUT -> Root / "api" / "files" / UuidVar(id) / "content" => putFileContent(req, id)
    case req @ GET -> Root / "api" / "files" / "zip" => getFilesZip(req)
    case req @ PATCH -> Root / "api" / "files" / UuidVar(id) => patchFile(req, id)
    case req @ POST -> Root / "api" / "files" / "transfer" => postFilesTransfer(req)
    case req @ POST -> Root / "api" / "files" / "delete" => postFilesDelete(req)
    case req @ PUT -> Root / "api" / "files" / UuidVar(id) / "favorite" => putFileFavorite(req, id)
    case req @ DELETE -> Root / "api" / "files" / UuidVar(id) / "favorite" => deleteFileFavorite(req, id)
    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "versions" => getFileVersions(req, id)
    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "versions" / UuidVar(vid) / "content" =>
      getFileVersionContent(req, id, vid)
    case req @ POST -> Root / "api" / "files" / UuidVar(id) / "versions" / UuidVar(vid) / "restore" =>
      postFileVersionRestore(req, id, vid)
    case req @ DELETE -> Root / "api" / "files" / UuidVar(id) / "versions" / UuidVar(vid) =>
      deleteFileVersion(req, id, vid)
    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "activity" => getFileActivity(req, id)
    case req @ GET -> Root / "api" / "activity" => getActivity(req)
    case req @ GET -> Root / "api" / "recent" => getRecent(req)
    case req @ GET -> Root / "api" / "favorites" => getFavorites(req)
    case req @ GET -> Root / "api" / "search" => getSearch(req)
    case req @ GET -> Root / "api" / "shared-with-me" => getSharedWithMe(req)
    case req @ GET -> Root / "api" / "shared-by-me" => getSharedByMe(req)
    case req @ GET -> Root / "api" / "trash" => getTrash(req)
    case req @ POST -> Root / "api" / "trash" / UuidVar(id) / "restore" => postTrashRestore(req, id)
    case req @ DELETE -> Root / "api" / "trash" / UuidVar(id) => deleteTrash(req, id)
    case req @ DELETE -> Root / "api" / "trash" => emptyTrash(req)
    case req @ GET -> Root / "api" / "sharees" => getSharees(req)
    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "shares" => getFileShares(req, id)
    case req @ POST -> Root / "api" / "files" / UuidVar(id) / "shares" => postFileShares(req, id)
    case req @ PATCH -> Root / "api" / "shares" / UuidVar(id) => patchShare(req, id)
    case req @ DELETE -> Root / "api" / "shares" / UuidVar(id) => deleteShare(req, id)
  }

  private def getFilesRoot(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s => nodes.root(s.userId).flatMap(folderDto(s, _)).flatMap(Ok(_)))

  private def getFilesResolve(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      nodes.root(s.userId).flatMap(root =>
        nodes.resolve(root.id, Names.segments(param(req, "path").getOrElse("/")))
      ).flatMap {
        case None => err(ErrorCode.NodeNotFound)
        case Some(n) => dtoOf(s, n).flatMap(d => nodes.withPaths(v(s), List(n -> d))).flatMap(h => Ok(h.head))
      }
    )

  private def getFile(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      respond(files.node(v(s), id, Access.View))(
        (
          n,
          _
        ) => dtoOf(s, n).flatMap(d => nodes.withPaths(v(s), List(n -> d))).flatMap(h => Ok(h.head))
      )
    )

  private def getFileChildren(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      respond(files.folder(v(s), id, Access.View))(
        (
          n,
          _
        ) => folderDto(s, n).flatMap(Ok(_))
      )
    )

  private def postFileFolders(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s => body[NameReq](req)(r => nodeResult(s)(files.createFolder(Actor.of(s), v(s), id, r.name))))

  private def postFileNew(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s => body[NameReq](req)(r => nodeResult(s)(files.createEmpty(Actor.of(s), v(s), id, r.name))))

  private def putFileUpload(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req) { s =>
      param(req, "name") match {
        case None => err(ErrorCode.NameParamRequired)
        case Some(name) =>
          val rel = param(req, "path").map(Names.segments).getOrElse(Nil)
          // 本文を受け取る前に権限を確かめる（書けないフォルダへ大きな本文を送りつけて、ディスクを使わせないように）
          respond(files.folder(v(s), id, Access.Edit))(
            (
              _,
              _
            ) =>
              nodeResult(s)(files.receive(req.body)(tmp =>
                files.upload(Actor.of(s), v(s), id, rel, name, tmp, OnConflict.parse(param(req, "conflict")))
              ))
          )
      }
    }

  private def getFileContent(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      respond(files.node(v(s), id, Access.View))(
        (
          n,
          _
        ) =>
          if n.isDir then Serve.zip(files, List(n), n.name, s.viaString)
          else Serve.node(req, files, n, boolParam(req, "download"), s.viaString)
      )
    )

  private def putFileContent(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      respond(files.node(v(s), id, Access.Edit))(
        (
          n,
          _
        ) =>
          nodeResult(s)(files.receive(req.body)(tmp =>
            files.writeTo(Actor.of(s), n, tmp, EventKind.Updated, param(req, "version").flatMap(_.toIntOption))
          ))
      )
    )

  private def getFilesZip(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req) { s =>
      idsParam(req, "ids").traverse[IO, Either[AppError, (Node, Access)]](id =>
        files.node(v(s), id, Access.View)
      ).flatMap { rs =>
        val ok = rs.collect { case Right((n, _)) => n }
        if ok.isEmpty then err(ErrorCode.FileNotFound)
        else Serve.zip(files, ok, "skawau", s.viaString)
      }
    }

  private def patchFile(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s => body[NameReq](req)(r => nodeResult(s)(files.rename(Actor.of(s), v(s), id, r.name))))

  private def postFilesTransfer(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[MoveReq](req)(r =>
        batch(s, r.ids)(id =>
          files.transfer(Actor.of(s), v(s), id, r.targetId, r.copy, OnConflict.parse(r.conflict)).map(_.map(Some(_)))
        )
      )
    )

  private def postFilesDelete(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[IdsReq](req)(r => batch(s, r.ids)(id => files.trash(Actor.of(s), v(s), id).map(_.as(None))))
    )

  private def putFileFavorite(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      respond(files.node(v(s), id, Access.View))(_ => nodes.setFavorite(s.userId, id, on = true) *> NoContent())
    )

  private def deleteFileFavorite(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s => nodes.setFavorite(s.userId, id, on = false) *> NoContent())

  private def getFileVersions(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      respond(files.node(v(s), id, Access.View)) {
        (
          n,
          _
        ) =>
          (nodes.versions(n.id), n.modifiedBy.flatTraverse(users.brief)).tupled.flatMap {
            (
              vs,
              mod
            ) =>
              {
                val current = VersionDto(n.id, n.version, n.size, n.updatedAt, mod, current = true)
                Ok(current :: vs.map(
                  (
                    r,
                    u
                  ) => VersionDto(r.id, r.version, r.size, r.createdAt, u, current = false)
                ))
              }
          }
      }
    )

  private def getFileVersionContent(
    req: Request[IO],
    id: UUID,
    vid: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      respond(files.node(v(s), id, Access.View))(
        (
          n,
          _
        ) =>
          nodes.version(n.id, vid).flatMap {
            case None => err(ErrorCode.VersionNotFound)
            case Some(r) =>
              val old = n.copy(
                blobKey = Some(r.blobKey),
                size = r.size,
                version = r.version,
                etag = s"v${r.id}",
                updatedAt = r.createdAt,
                name =
                  s"${FileKind.base(n.name)} (版${r.version})${Option(FileKind.ext(n.name)).filter(_.nonEmpty).fold("")("." +
                      _)}"
              )
              Serve.node(req, files, old, download = true, s.viaString)
          }
      )
    )

  private def postFileVersionRestore(
    req: Request[IO],
    id: UUID,
    vid: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      respond(files.node(v(s), id, Access.Edit))(
        (
          n,
          _
        ) => nodeResult(s)(files.restoreVersion(Actor.of(s), n, vid))
      )
    )

  private def deleteFileVersion(
    req: Request[IO],
    id: UUID,
    vid: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      respond(files.node(v(s), id, Access.Owner))(
        (
          n,
          _
        ) =>
          nodes.deleteVersion(n.id, vid).ifM(NoContent(), err(ErrorCode.VersionNotFound))
      )
    )

  private def getFileActivity(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      respond(files.node(v(s), id, Access.View))(
        (
          n,
          _
        ) => activity.forNode(s.userId, n, 100).flatMap(Ok(_))
      )
    )

  private def getActivity(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      activity.forViewer(
        v(s),
        param(req, "before").flatMap(_.toLongOption),
        intParam(req, "limit", 100, 500)
      ).flatMap(Ok(_))
    )

  private def getRecent(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s => hits(s, nodes.recent(v(s), intParam(req, "limit", 50, 200))))

  private def getFavorites(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s => hits(s, nodes.favorites(v(s))))

  private def getSearch(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      param(req, "q").fold(Ok(List.empty[NodeHitDto]))(q =>
        hits(s, nodes.search(v(s), q, intParam(req, "limit", 100, 500)))
      )
    )

  private def getSharedWithMe(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s => hits(s, nodes.sharedWithMe(v(s))))

  private def getSharedByMe(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req) { s =>
      for {
        ss <- shares.byOwner(s.userId, cfg.publicUrl)
        items <- nodes.dtosByIds(v(s), ss.map(_.nodeId))
        hs <- nodes.withPaths(v(s), items)
        byId = hs.map(h => h.node.id -> h).toMap
        res <- Ok(ss.flatMap(sh => byId.get(sh.nodeId).map(h => SharedByMeDto(sh, h.node, h.path))))
      } yield res
    }

  private def getTrash(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s => nodes.trashList(s.userId).flatMap(Ok(_)))

  private def postTrashRestore(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      nodes.trashed(s.userId, id).flatMap {
        case None => err(ErrorCode.TrashItemNotFound)
        case Some(n) => nodes.restore(n, Actor.of(s)).flatMap(dtoOf(s, _)).flatMap(Ok(_))
      }
    )

  private def deleteTrash(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      nodes.trashed(s.userId, id).flatMap {
        case None => err(ErrorCode.TrashItemNotFound)
        case Some(n) => nodes.purge(n, Actor.of(s)) *> NoContent()
      }
    )

  private def emptyTrash(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s =>
      nodes.trashList(s.userId).flatMap(_.traverse_(t =>
        nodes.trashed(s.userId, t.id).flatMap(_.traverse_(nodes.purge(_, Actor.of(s))))
      )) *>
        NoContent()
    )

  private def getSharees(
    req: Request[IO]
  ): IO[Response[IO]] =
    authed(req)(s => users.sharees(param(req, "q").getOrElse(""), s.userId).flatMap(Ok(_)))

  private def getFileShares(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      respond(files.node(v(s), id, Access.View))(
        (
          n,
          a
        ) =>
          if a == Access.Owner then shares.forNode(n.id, cfg.publicUrl).flatMap(Ok(_)) else Ok(List.empty[ShareDto])
      )
    )

  private def postFileShares(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[CreateShareReq](req)(r =>
        respond(files.node(v(s), id, Access.Owner))(
          (
            n,
            _
          ) => createShare(s, n, r)
        )
      )
    )

  private def patchShare(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      body[UpdateShareReq](req)(r =>
        withShare(s, id) {
          (
            sh,
            n
          ) =>
            {
              val perm = r.permission.map(_.trim)
              val pw = r.password.map(p => Option(p).filter(_.nonEmpty).map(Passwords.hash))
              (
                validPermission(sh.kind, n, perm.getOrElse(sh.permission)),
                r.expiresAt.traverse(e => Dates.parse(Some(e), "期限"))
              ).tupled match {
                case Left(f) => fail(f)
                case Right(_, exp) =>
                  shares.update(
                    sh.id,
                    perm,
                    pw.filter(_ => sh.kind == ShareKind.Link),
                    exp,
                    r.label.map(l => Option(l.trim).filter(_.nonEmpty))
                  ) *>
                    shares.dto(sh.id, cfg.publicUrl).flatMap(d => Ok(d.get))
              }
            }
        }
      )
    )

  private def deleteShare(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    authed(req)(s =>
      withShare(s, id) {
        (
          sh,
          n
        ) =>
          shares.delete(sh.id) *>
            nodes.event(n, Actor.of(s), EventKind.Unshared, Json.obj("kind" -> sh.kind.asJson)) *> NoContent()
      }
    )

  private def v(
    s: AuthSession
  ): Viewer = Viewer.of(s)

  private def dtoOf(
    s: AuthSession,
    n: Node
  ): IO[NodeDto] = nodes.dto(v(s), n.id).map(_.get)

  private def folderDto(
    s: AuthSession,
    n: Node
  ): IO[FolderDto] =
    for {
      d <- dtoOf(s, n)
      items <- nodes.children(v(s), n.id)
      cs <- nodes.crumbs(v(s), List(n))
      (path, inShare) = cs.getOrElse(n.id, (Nil, false))
    } yield FolderDto(d, path, items, inShare)

  private def nodeResult(
    s: AuthSession
  )(
    r: Res[Node]
  ): IO[Response[IO]] = respond(r)(n => dtoOf(s, n).flatMap(Ok(_)))

  /** まとめて処理する（失敗しても続け、失敗した名前と理由を返す）。 */
  private def batch(
    s: AuthSession,
    ids: List[UUID]
  )(
    f: UUID => Res[Option[Node]]
  ): IO[Response[IO]] =
    ids.distinct.traverse(id =>
      f(id).flatMap {
        case Right(n) => n.traverse(dtoOf(s, _)).map(d => Right(d))
        // 見られないものの名前は出さない
        case Left(e) => nodes.access(v(s), id).map(n => Left(s"${n.fold("?")(_._1.name)}: ${e.message}"))
      }
    ).flatMap { rs =>
      Ok(BatchResultDto(rs.collect { case Right(Some(d)) => d }, rs.collect { case Left(m) => m }))
    }

  private def hits(
    s: AuthSession,
    items: IO[List[(Node, NodeDto)]]
  ): IO[Response[IO]] =
    items.flatMap(nodes.withPaths(v(s), _)).flatMap(Ok(_))

  /** 共有の所有者（ノードの所有者）だけが変更できる。 */
  private def withShare(
    s: AuthSession,
    id: UUID
  )(
    f: (ShareRow, Node) => IO[Response[IO]]
  ): IO[Response[IO]] =
    shares.find(id).flatMap {
      case None => err(ErrorCode.ShareNotFound)
      case Some(sh) => nodes.find(sh.nodeId).flatMap {
          case Some(n) if n.ownerId == s.userId || sh.ownerId == s.userId => f(sh, n)
          case _ => err(ErrorCode.ShareNotFound)
        }
    }

  private def validPermission(
    kind: String,
    n: Node,
    p: String
  ): Either[AppError, Unit] = p match {
    case "view" | "edit" => Right(())
    case "upload" if kind == ShareKind.Link && n.isDir => Right(())
    case "upload" => Left(AppError(ErrorCode.UploadOnlyNeedsFolderLink))
    case _ => Left(AppError(ErrorCode.SharePermInvalid))
  }

  private def createShare(
    s: AuthSession,
    n: Node,
    r: CreateShareReq
  ): IO[Response[IO]] =
    if n.isRoot then err(ErrorCode.RootNotShareable)
    else {
      val target: Either[AppError, (Option[UUID], Option[UUID])] = r.kind match {
        case ShareKind.User if r.userId.contains(s.userId) => Left(AppError(ErrorCode.ShareWithSelf))
        case ShareKind.User => r.userId.toRight(AppError(ErrorCode.ShareUserIdRequired)).map(u => (Some(u), None))
        case ShareKind.Group => r.groupId.toRight(AppError(ErrorCode.ShareGroupIdRequired)).map(g => (None, Some(g)))
        case ShareKind.Link => Right((None, None))
        case _ => Left(AppError(ErrorCode.ShareKindInvalid))
      }
      (target, validPermission(r.kind, n, r.permission), Dates.parse(r.expiresAt, "期限")).tupled match {
        case Left(f) => fail(f)
        case Right(((uid, gid), _, exp)) =>
          val token = Option.when(r.kind == ShareKind.Link)(Crypto.randomToken(18))
          val pw = r.password.map(_.trim).filter(_.nonEmpty).filter(_ => r.kind == ShareKind.Link).map(Passwords.hash)
          shares.create(
            n.id,
            s.userId,
            r.kind,
            uid,
            gid,
            r.permission,
            token,
            pw,
            exp,
            r.label.map(_.trim).filter(_.nonEmpty)
          ).flatMap {
            case Left(f) => fail(f)
            case Right(id) =>
              for {
                sh <- shares.find(id).map(_.get)
                _ <- nodes.event(
                  n,
                  Actor.of(s),
                  EventKind.Shared,
                  Json.obj("kind" -> r.kind.asJson, "permission" -> r.permission.asJson)
                )
                to <- shares.recipients(sh)
                link = if n.isDir then s"/files/${n.id}" else s"/view/${n.id}"
                _ <- notifier.notify(
                  to,
                  NotificationKind.Shared,
                  s"${s.user.name} さんが「${n.name}」を共有しました",
                  Perm.label(r.permission),
                  Some(link),
                  Some(s.userId)
                )
                _ <- activity.audit(
                  Some(s.userId),
                  s.viaString,
                  "share.create",
                  Some(n.id.toString),
                  Json.obj("kind" -> r.kind.asJson)
                )
                _ <- sbirds.observe.Metrics.count(
                  "skawau.shares.created",
                  "kind" -> r.kind,
                  "permission" -> r.permission,
                  "via" -> s.viaString
                )
                d <- shares.dto(id, cfg.publicUrl)
                res <- Created(d.get)
              } yield res
          }
      }
    }
}
