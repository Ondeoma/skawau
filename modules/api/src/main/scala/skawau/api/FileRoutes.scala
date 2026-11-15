package skawau.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skawau.auth.{Crypto, Passwords}
import skawau.core.*
import skawau.db.*
import skawau.shared.*

import java.util.UUID

/** ファイル・フォルダ・版・ごみ箱・お気に入り・検索・共有の REST API。 */
final class FileRoutes(files: FileService, nodes: NodeStore, shares: ShareStore, activity: ActivityStore, users: UserStore,
    val sessions: SessionAuth, cfg: ApiConfig) extends RouteSupport:

  private def v(s: AuthSession): Viewer = Viewer.of(s)

  private def dtoOf(s: AuthSession, n: Node): IO[NodeDto] = nodes.dto(v(s), n.id).map(_.get)

  private def folderDto(s: AuthSession, n: Node): IO[FolderDto] =
    for
      d     <- dtoOf(s, n)
      items <- nodes.children(v(s), n.id)
      cs    <- nodes.crumbs(v(s), List(n))
      (path, inShare) = cs.getOrElse(n.id, (Nil, false))
    yield FolderDto(d, path, items, inShare)

  private def nodeResult(s: AuthSession)(r: Res[Node]): IO[Response[IO]] = respond(r)(n => dtoOf(s, n).flatMap(Ok(_)))

  /** まとめて処理する（失敗しても続け、失敗した名前と理由を返す）。 */
  private def batch(s: AuthSession, ids: List[UUID])(f: UUID => Res[Option[Node]]): IO[Response[IO]] =
    ids.distinct.traverse(id => f(id).flatMap {
      case Right(n) => n.traverse(dtoOf(s, _)).map(d => Right(d))
      case Left(e)  => nodes.find(id).map(n => Left(s"${n.fold("?")(_.name)}: ${e.message}"))
    }).flatMap { rs =>
      Ok(BatchResultDto(rs.collect { case Right(Some(d)) => d }, rs.collect { case Left(m) => m }))
    }

  private def hits(s: AuthSession, items: IO[List[(Node, NodeDto)]]): IO[Response[IO]] =
    items.flatMap(nodes.withPaths(v(s), _)).flatMap(Ok(_))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ---------------------------------------------------------------- フォルダ

    case req @ GET -> Root / "api" / "files" / "root" =>
      authed(req)(s => nodes.root(s.userId).flatMap(folderDto(s, _)).flatMap(Ok(_)))

    /** パス（"/資料/2026"。自分のファイルのルートから）でファイル・フォルダを引く。 */
    case req @ GET -> Root / "api" / "files" / "resolve" =>
      authed(req)(s => nodes.root(s.userId).flatMap(root => nodes.resolve(root.id, Names.segments(param(req, "path").getOrElse("/")))).flatMap {
        case None    => err(Status.NotFound, "ファイルまたはフォルダが見つかりません")
        case Some(n) => dtoOf(s, n).flatMap(d => nodes.withPaths(v(s), List(n -> d))).flatMap(h => Ok(h.head))
      })

    case req @ GET -> Root / "api" / "files" / UuidVar(id) =>
      authed(req)(s => respond(files.node(v(s), id, Access.View))((n, _) => dtoOf(s, n).flatMap(d => nodes.withPaths(v(s), List(n -> d))).flatMap(h => Ok(h.head))))

    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "children" =>
      authed(req)(s => respond(files.folder(v(s), id, Access.View))((n, _) => folderDto(s, n).flatMap(Ok(_))))

    case req @ POST -> Root / "api" / "files" / UuidVar(id) / "folders" =>
      authed(req)(s => body[NameReq](req)(r => nodeResult(s)(files.createFolder(Actor.of(s), v(s), id, r.name))))

    /** 新しいファイル（docx・xlsx・pptx は最小の文書、それ以外は空）。 */
    case req @ POST -> Root / "api" / "files" / UuidVar(id) / "new" =>
      authed(req)(s => body[NameReq](req)(r => nodeResult(s)(files.createEmpty(Actor.of(s), v(s), id, r.name))))

    /** アップロード（本文がそのまま中身）。path は途中のフォルダ（a/b）、conflict は rename / overwrite。 */
    case req @ PUT -> Root / "api" / "files" / UuidVar(id) / "upload" =>
      authed(req) { s =>
        param(req, "name") match
          case None => err(Status.BadRequest, "name を指定してください")
          case Some(name) =>
            val rel = param(req, "path").map(Names.segments).getOrElse(Nil)
            nodeResult(s)(files.receive(req.body)(tmp => files.upload(Actor.of(s), v(s), id, rel, name, tmp, OnConflict.parse(param(req, "conflict")))))
      }

    // ---------------------------------------------------------------- 中身

    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "content" =>
      authed(req)(s => respond(files.node(v(s), id, Access.View))((n, _) =>
        if n.isDir then Serve.zip(files, List(n), n.name)
        else Serve.node(req, files, n, boolParam(req, "download"))))

    /** 画面のテキストエディタからの保存（version を渡すと、ほかの人の更新との競合を検出する）。 */
    case req @ PUT -> Root / "api" / "files" / UuidVar(id) / "content" =>
      authed(req)(s => respond(files.node(v(s), id, Access.Edit))((n, _) =>
        nodeResult(s)(files.receive(req.body)(tmp => files.writeTo(Actor.of(s), n, tmp, EventKind.Updated, param(req, "version").flatMap(_.toIntOption))))))

    case req @ GET -> Root / "api" / "files" / "zip" =>
      authed(req) { s =>
        idsParam(req, "ids").traverse[IO, Either[Fail, (Node, Access)]](id => files.node(v(s), id, Access.View)).flatMap { rs =>
          val ok = rs.collect { case Right((n, _)) => n }
          if ok.isEmpty then err(Status.NotFound, "ファイルが見つかりません")
          else Serve.zip(files, ok, "skawau")
        }
      }

    // ---------------------------------------------------------------- 名前の変更・移動・コピー・削除

    case req @ PATCH -> Root / "api" / "files" / UuidVar(id) =>
      authed(req)(s => body[NameReq](req)(r => nodeResult(s)(files.rename(Actor.of(s), v(s), id, r.name))))

    case req @ POST -> Root / "api" / "files" / "transfer" =>
      authed(req)(s => body[MoveReq](req)(r =>
        batch(s, r.ids)(id => files.transfer(Actor.of(s), v(s), id, r.targetId, r.copy, OnConflict.parse(r.conflict)).map(_.map(Some(_))))))

    case req @ POST -> Root / "api" / "files" / "delete" =>
      authed(req)(s => body[IdsReq](req)(r => batch(s, r.ids)(id => files.trash(Actor.of(s), v(s), id).map(_.as(None)))))

    case req @ PUT -> Root / "api" / "files" / UuidVar(id) / "favorite" =>
      authed(req)(s => respond(files.node(v(s), id, Access.View))(_ => nodes.setFavorite(s.userId, id, on = true) *> NoContent()))

    case req @ DELETE -> Root / "api" / "files" / UuidVar(id) / "favorite" =>
      authed(req)(s => nodes.setFavorite(s.userId, id, on = false) *> NoContent())

    // ---------------------------------------------------------------- 版

    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "versions" =>
      authed(req)(s => respond(files.node(v(s), id, Access.View)) { (n, _) =>
        (nodes.versions(n.id), n.modifiedBy.flatTraverse(users.brief)).tupled.flatMap { (vs, mod) =>
          val current = VersionDto(n.id, n.version, n.size, n.updatedAt, mod, current = true)
          Ok(current :: vs.map((r, u) => VersionDto(r.id, r.version, r.size, r.createdAt, u, current = false)))
        }
      })

    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "versions" / UuidVar(vid) / "content" =>
      authed(req)(s => respond(files.node(v(s), id, Access.View))((n, _) =>
        nodes.version(n.id, vid).flatMap {
          case None    => err(Status.NotFound, "版が見つかりません")
          case Some(r) =>
            val old = n.copy(blobKey = Some(r.blobKey), size = r.size, version = r.version, etag = s"v${r.id}", updatedAt = r.createdAt,
              name = s"${FileKind.base(n.name)} (版${r.version})${Option(FileKind.ext(n.name)).filter(_.nonEmpty).fold("")("." + _)}")
            Serve.node(req, files, old, download = true)
        }))

    case req @ POST -> Root / "api" / "files" / UuidVar(id) / "versions" / UuidVar(vid) / "restore" =>
      authed(req)(s => respond(files.node(v(s), id, Access.Edit))((n, _) => nodeResult(s)(files.restoreVersion(Actor.of(s), n, vid))))

    case req @ DELETE -> Root / "api" / "files" / UuidVar(id) / "versions" / UuidVar(vid) =>
      authed(req)(s => respond(files.node(v(s), id, Access.Owner))((n, _) =>
        nodes.deleteVersion(n.id, vid).ifM(NoContent(), err(Status.NotFound, "版が見つかりません"))))

    // ---------------------------------------------------------------- 変更履歴

    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "activity" =>
      authed(req)(s => respond(files.node(v(s), id, Access.View))((n, _) => activity.forNode(s.userId, n, 100).flatMap(Ok(_))))

    case req @ GET -> Root / "api" / "activity" =>
      authed(req)(s => activity.forViewer(v(s), param(req, "before").flatMap(_.toLongOption), intParam(req, "limit", 100, 500)).flatMap(Ok(_)))

    // ---------------------------------------------------------------- 一覧

    case req @ GET -> Root / "api" / "recent" => authed(req)(s => hits(s, nodes.recent(v(s), intParam(req, "limit", 50, 200))))

    case req @ GET -> Root / "api" / "favorites" => authed(req)(s => hits(s, nodes.favorites(v(s))))

    case req @ GET -> Root / "api" / "search" =>
      authed(req)(s => param(req, "q").fold(Ok(List.empty[NodeHitDto]))(q => hits(s, nodes.search(v(s), q, intParam(req, "limit", 100, 500)))))

    case req @ GET -> Root / "api" / "shared-with-me" => authed(req)(s => hits(s, nodes.sharedWithMe(v(s))))

    case req @ GET -> Root / "api" / "shared-by-me" =>
      authed(req) { s =>
        for
          ss    <- shares.byOwner(s.userId, cfg.publicUrl)
          items <- nodes.dtosByIds(v(s), ss.map(_.nodeId))
          hs    <- nodes.withPaths(v(s), items)
          byId   = hs.map(h => h.node.id -> h).toMap
          res   <- Ok(ss.flatMap(sh => byId.get(sh.nodeId).map(h => SharedByMeDto(sh, h.node, h.path))))
        yield res
      }

    // ---------------------------------------------------------------- ごみ箱（所有者のもの）

    case req @ GET -> Root / "api" / "trash" => authed(req)(s => nodes.trashList(s.userId).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "trash" / UuidVar(id) / "restore" =>
      authed(req)(s => nodes.trashed(s.userId, id).flatMap {
        case None    => err(Status.NotFound, "ごみ箱に見つかりません")
        case Some(n) => nodes.restore(n, Actor.of(s)).flatMap(dtoOf(s, _)).flatMap(Ok(_))
      })

    case req @ DELETE -> Root / "api" / "trash" / UuidVar(id) =>
      authed(req)(s => nodes.trashed(s.userId, id).flatMap {
        case None    => err(Status.NotFound, "ごみ箱に見つかりません")
        case Some(n) => nodes.purge(n, Actor.of(s)) *> NoContent()
      })

    case req @ DELETE -> Root / "api" / "trash" =>
      authed(req)(s => nodes.trashList(s.userId).flatMap(_.traverse_(t => nodes.trashed(s.userId, t.id).flatMap(_.traverse_(nodes.purge(_, Actor.of(s)))))) *>
        NoContent())

    // ---------------------------------------------------------------- 共有

    case req @ GET -> Root / "api" / "sharees" =>
      authed(req)(s => users.sharees(param(req, "q").getOrElse(""), s.userId).flatMap(Ok(_)))

    /** ノードに付いている共有（所有者だけが見られる）。 */
    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "shares" =>
      authed(req)(s => respond(files.node(v(s), id, Access.View))((n, a) =>
        if a == Access.Owner then shares.forNode(n.id, cfg.publicUrl).flatMap(Ok(_)) else Ok(List.empty[ShareDto])))

    case req @ POST -> Root / "api" / "files" / UuidVar(id) / "shares" =>
      authed(req)(s => body[CreateShareReq](req)(r => respond(files.node(v(s), id, Access.Owner))((n, _) => createShare(s, n, r))))

    case req @ PATCH -> Root / "api" / "shares" / UuidVar(id) =>
      authed(req)(s => body[UpdateShareReq](req)(r => withShare(s, id) { (sh, n) =>
        val perm = r.permission.map(_.trim)
        val pw   = r.password.map(p => Option(p).filter(_.nonEmpty).map(Passwords.hash))
        (validPermission(sh.kind, n, perm.getOrElse(sh.permission)), r.expiresAt.traverse(e => Dates.parse(Some(e), "期限"))).tupled match
          case Left(f) => fail(f)
          case Right(_, exp) =>
            shares.update(sh.id, perm, pw.filter(_ => sh.kind == ShareKind.Link), exp, r.label.map(l => Option(l.trim).filter(_.nonEmpty))) *>
              shares.dto(sh.id, cfg.publicUrl).flatMap(d => Ok(d.get))
      }))

    case req @ DELETE -> Root / "api" / "shares" / UuidVar(id) =>
      authed(req)(s => withShare(s, id) { (sh, n) =>
        shares.delete(sh.id) *> nodes.event(n, Actor.of(s), EventKind.Unshared, Json.obj("kind" -> sh.kind.asJson)) *> NoContent()
      })
  }

  /** 共有の所有者（ノードの所有者）だけが変更できる。 */
  private def withShare(s: AuthSession, id: UUID)(f: (ShareRow, Node) => IO[Response[IO]]): IO[Response[IO]] =
    shares.find(id).flatMap {
      case None => err(Status.NotFound, "共有が見つかりません")
      case Some(sh) => nodes.find(sh.nodeId).flatMap {
        case Some(n) if n.ownerId == s.userId || sh.ownerId == s.userId => f(sh, n)
        case _ => err(Status.NotFound, "共有が見つかりません")
      }
    }

  private def validPermission(kind: String, n: Node, p: String): Either[Fail, Unit] = p match
    case "view" | "edit"                         => Right(())
    case "upload" if kind == ShareKind.Link && n.isDir => Right(())
    case "upload" => Left(Fail.Invalid("アップロードのみはフォルダの公開リンクだけで使えます"))
    case _        => Left(Fail.Invalid("権限は view / edit / upload のどれかです"))

  private def createShare(s: AuthSession, n: Node, r: CreateShareReq): IO[Response[IO]] =
    if n.isRoot then err(Status.BadRequest, "ルートのフォルダは共有できません")
    else
      val target: Either[Fail, (Option[UUID], Option[UUID])] = r.kind match
        case ShareKind.User if r.userId.contains(s.userId) => Left(Fail.Invalid("自分とは共有できません"))
        case ShareKind.User  => r.userId.toRight(Fail.Invalid("userId を指定してください")).map(u => (Some(u), None))
        case ShareKind.Group => r.groupId.toRight(Fail.Invalid("groupId を指定してください")).map(g => (None, Some(g)))
        case ShareKind.Link  => Right((None, None))
        case _               => Left(Fail.Invalid("kind は user / group / link のどれかです"))
      (target, validPermission(r.kind, n, r.permission), Dates.parse(r.expiresAt, "期限")).tupled match
        case Left(f) => fail(f)
        case Right(((uid, gid), _, exp)) =>
          val token = Option.when(r.kind == ShareKind.Link)(Crypto.randomToken(18))
          val pw    = r.password.map(_.trim).filter(_.nonEmpty).filter(_ => r.kind == ShareKind.Link).map(Passwords.hash)
          shares.create(n.id, s.userId, r.kind, uid, gid, r.permission, token, pw, exp, r.label.map(_.trim).filter(_.nonEmpty)).orFail.flatMap {
            case Left(f) => fail(f)
            case Right(id) =>
              for
                sh  <- shares.find(id).map(_.get)
                _   <- nodes.event(n, Actor.of(s), EventKind.Shared, Json.obj("kind" -> r.kind.asJson, "permission" -> r.permission.asJson))
                to  <- shares.recipients(sh)
                link = if n.isDir then s"/files/${n.id}" else s"/view/${n.id}"
                _   <- activity.notify(to, NotificationKind.Shared, s"${s.user.name} さんが「${n.name}」を共有しました",
                         Perm.label(r.permission), Some(link), Some(s.userId))
                _   <- activity.audit(Some(s.userId), s.viaString, "share.create", Some(n.id.toString), Json.obj("kind" -> r.kind.asJson))
                d   <- shares.dto(id, cfg.publicUrl)
                res <- Created(d.get)
              yield res
          }
