package skawau.api

import cats.effect.*
import cats.syntax.all.*
import org.http4s.*
import org.http4s.headers.`WWW-Authenticate`
import org.typelevel.ci.CIString
import skawau.core.*
import skawau.db.*
import skawau.shared.FileKind

import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * WebDAV（`/dav/` が自分のファイルのルート）。認証は Basic（ユーザー名は任意、パスワードに個人設定で発行したアプリパスワード）。
 * クラス 1 と、Finder・Windows のために形だけの LOCK / UNLOCK（クラス 2）に対応する。PROPPATCH は受け付けるだけ。
 * 削除はごみ箱へ、上書きは新しい版になる。
 */
final class DavRoutes(files: FileService, nodes: NodeStore, val sessions: SessionAuth) extends RouteSupport:

  private val Prefix = "dav"

  private def xml(status: Status, body: String): Response[IO] =
    Response[IO](status).withEntity(body).putHeaders("Content-Type" -> "application/xml; charset=utf-8")

  private def esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  private def href(segs: List[String], dir: Boolean): String =
    ("/" + (Prefix :: segs.map(UrlPath.encodeSegment)).mkString("/")) + (if dir && segs.nonEmpty then "/" else if segs.isEmpty then "/" else "")

  private def unauthorized: IO[Response[IO]] =
    IO.pure(Response[IO](Status.Unauthorized).putHeaders(`WWW-Authenticate`(Challenge("Basic", "skawau", Map("charset" -> "UTF-8"))))
      .withEntity("アプリパスワードでログインしてください"))

  /** パスのセグメント（/dav より後ろ、デコード済み）。 */
  private def segmentsOf(path: Uri.Path): Option[List[String]] =
    path.segments.toList.map(_.decoded()) match
      case Prefix :: rest => Some(rest.map(Names.normalize).filter(_.nonEmpty))
      case _              => None

  /** Destination ヘッダー（絶対 URL またはパス）のセグメント。 */
  private def destination(req: Request[IO]): Option[List[String]] =
    req.headers.get(CIString("Destination")).map(_.head.value).flatMap(d => Uri.fromString(d).toOption).flatMap(u => segmentsOf(u.path))

  private def prop(n: Node, segs: List[String], quota: Option[(Long, Option[Long])]): String =
    val common =
      s"<d:displayname>${esc(if n.isRoot then "skawau" else n.name)}</d:displayname>" +
        s"<d:getlastmodified>${Serve.httpDate.format(n.updatedAt)}</d:getlastmodified>" +
        s"<d:creationdate>${DateTimeFormatter.ISO_INSTANT.format(n.createdAt)}</d:creationdate>" +
        s"<d:getetag>${esc(Serve.etag(n))}</d:getetag>"
    val kind =
      if n.isDir then "<d:resourcetype><d:collection/></d:resourcetype>"
      else s"<d:resourcetype/><d:getcontentlength>${n.size}</d:getcontentlength><d:getcontenttype>${esc(n.contentType.getOrElse(FileKind.contentType(n.name)))}</d:getcontenttype>"
    val q = quota.fold("")((used, limit) =>
      s"<d:quota-used-bytes>$used</d:quota-used-bytes>" + limit.fold("")(l => s"<d:quota-available-bytes>${math.max(0L, l - used)}</d:quota-available-bytes>"))
    s"<d:response><d:href>${esc(href(segs, n.isDir))}</d:href><d:propstat><d:prop>$common$kind$q</d:prop>" +
      "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

  private def multistatus(body: String): String =
    """<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">""" + body + "</d:multistatus>"

  val routes: HttpRoutes[IO] = HttpRoutes[IO] { req =>
    cats.data.OptionT.fromOption[IO](segmentsOf(req.pathInfo)).semiflatMap { segs =>
      if req.method == Method.OPTIONS then
        IO.pure(Response[IO](Status.Ok).putHeaders("DAV" -> "1, 2", "MS-Author-Via" -> "DAV",
          "Allow" -> "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, MOVE, COPY, LOCK, UNLOCK"))
      else
        sessions.fromDav(req).flatMap {
          case None => unauthorized
          case Some(s) if !s.canWrite && !Set("GET", "HEAD", "PROPFIND").contains(req.method.name) =>
            IO.pure(Response[IO](Status.Forbidden).withEntity("このアプリパスワードは参照専用です"))
          case Some(s) => nodes.root(s.userId).flatMap(root => handle(req, s, root, segs))
        }
    }
  }

  private def handle(req: Request[IO], s: AuthSession, root: Node, segs: List[String]): IO[Response[IO]] =
    val v     = Viewer.of(s)
    val actor = Actor.of(s)
    val target = nodes.resolve(root.id, segs)
    def parent = nodes.resolve(root.id, segs.dropRight(1)).map(_.filter(_.isDir))
    def status(st: Status, msg: String = "") = IO.pure(Response[IO](st).withEntity(msg))
    // WebDAV では同じ名前の衝突は 412（Precondition Failed）で返す
    def failed(f: Fail): IO[Response[IO]] = status(f match { case _: Fail.Conflict => Status.PreconditionFailed; case _ => f.status }, f.message)

    req.method.name match
      case "PROPFIND" =>
        target.flatMap {
          case None => status(Status.NotFound)
          case Some(n) =>
            val depth0 = req.headers.get(CIString("Depth")).exists(_.head.value.trim == "0")
            val quota  = if n.isRoot then files.quota(s.user).map(q => Some((q.used, q.limit))) else IO.pure(None)
            (quota, if n.isDir && !depth0 then nodes.childrenRaw(n.id) else IO.pure(Nil)).tupled.map { (q, kids) =>
              xml(Status.MultiStatus, multistatus(prop(n, segs, q) + kids.map(c => prop(c, segs :+ c.name, None)).mkString))
            }
        }

      case "GET" | "HEAD" =>
        target.flatMap {
          case Some(n) if !n.isDir => Serve.node(req, files, n, download = true, head = req.method == Method.HEAD)
          case Some(_)             => status(Status.MethodNotAllowed, "フォルダは PROPFIND で一覧してください")
          case None                => status(Status.NotFound)
        }

      case "PUT" =>
        if segs.isEmpty then status(Status.MethodNotAllowed)
        else parent.flatMap {
          case None => status(Status.Conflict, "親のフォルダがありません")
          case Some(dir) =>
            target.flatMap { existing =>
              files.receive(req.body)(tmp => files.upload(actor, v, dir.id, Nil, segs.last, tmp, OnConflict.Overwrite)).flatMap {
                case Right(n) => IO.pure(Response[IO](if existing.isDefined then Status.NoContent else Status.Created).putHeaders("ETag" -> Serve.etag(n)))
                case Left(f)  => failed(f)
              }
            }
        }

      case "MKCOL" =>
        target.flatMap {
          case Some(_) => status(Status.MethodNotAllowed, "すでにあります")
          case None => parent.flatMap {
            case None      => status(Status.Conflict, "親のフォルダがありません")
            case Some(dir) => files.createFolder(actor, v, dir.id, segs.last).flatMap(_.fold(failed, _ => status(Status.Created)))
          }
        }

      case "DELETE" =>
        target.flatMap {
          case None    => status(Status.NotFound)
          case Some(n) => files.trash(actor, v, n.id).flatMap(_.fold(failed, _ => status(Status.NoContent)))
        }

      case m @ ("MOVE" | "COPY") =>
        val overwrite = !req.headers.get(CIString("Overwrite")).exists(_.head.value.trim.equalsIgnoreCase("F"))
        (target, IO.pure(destination(req).filter(_.nonEmpty))).tupled.flatMap {
          case (None, _)       => status(Status.NotFound)
          case (_, None)       => status(Status.BadRequest, "Destination が正しくありません")
          case (Some(n), Some(dest)) =>
            (nodes.resolve(root.id, dest.dropRight(1)), nodes.resolve(root.id, dest)).tupled.flatMap {
              case (Some(dir), existing) if dir.isDir =>
                if existing.exists(_.id != n.id) && !overwrite then status(Status.PreconditionFailed, "移動先にすでにあります")
                else
                  files.transfer(actor, v, n.id, dir.id, m == "COPY", OnConflict.Overwrite, Some(dest.last))
                    .flatMap(_.fold(failed, _ => status(if existing.isDefined then Status.NoContent else Status.Created)))
              case _ => status(Status.Conflict, "移動先のフォルダがありません")
            }
        }

      case "LOCK" =>
        val token = s"opaquelocktoken:${UUID.randomUUID()}"
        val body = multistatusLock(token, href(segs, dir = false))
        target.flatMap { existing =>
          // ロックで空のファイルを作るクライアントがあるため、なければ作る
          val create = if existing.isEmpty && segs.nonEmpty then
              parent.flatMap(_.traverse_(dir => files.receiveBytes(Array.emptyByteArray)(tmp => files.upload(actor, v, dir.id, Nil, segs.last, tmp, OnConflict.Fail)).void))
            else IO.unit
          create *> IO.pure(xml(if existing.isEmpty then Status.Created else Status.Ok, body).putHeaders("Lock-Token" -> s"<$token>"))
        }

      case "UNLOCK" => status(Status.NoContent)

      case "PROPPATCH" =>
        target.flatMap {
          case None => status(Status.NotFound)
          case Some(n) =>
            IO.pure(xml(Status.MultiStatus, multistatus(s"<d:response><d:href>${esc(href(segs, n.isDir))}</d:href><d:propstat><d:prop/>" +
              "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")))
        }

      case _ => status(Status.MethodNotAllowed)

  private def multistatusLock(token: String, path: String): String =
    """<?xml version="1.0" encoding="utf-8"?><d:prop xmlns:d="DAV:"><d:lockdiscovery><d:activelock>""" +
      "<d:locktype><d:write/></d:locktype><d:lockscope><d:exclusive/></d:lockscope><d:depth>infinity</d:depth>" +
      s"<d:timeout>Second-3600</d:timeout><d:locktoken><d:href>$token</d:href></d:locktoken>" +
      s"<d:lockroot><d:href>${esc(path)}</d:href></d:lockroot></d:activelock></d:lockdiscovery></d:prop>"
