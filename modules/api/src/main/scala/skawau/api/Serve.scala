package skawau.api

import cats.effect.*
import org.http4s.*
import org.typelevel.ci.CIString
import skawau.core.*
import skawau.shared.FileKind

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** ファイルの中身の応答（REST・公開リンク・WebDAV・ONLYOFFICE で共通）。Range・条件付き GET に対応する。 */
object Serve:

  val httpDate: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

  /** "bytes=a-b" / "bytes=a-" / "bytes=-n"（1つの範囲だけ）。 */
  def parseRange(h: Option[String], size: Long): Option[(Long, Option[Long])] =
    h.map(_.trim).filter(_.startsWith("bytes=")).map(_.drop(6)).filterNot(_.contains(',')).flatMap { r =>
      r.split("-", -1) match
        case Array("", n) => n.toLongOption.filter(_ > 0).map(x => (math.max(0L, size - x), None))
        case Array(a, "") => a.toLongOption.filter(_ < size).map(x => (x, None))
        case Array(a, b)  => (a.toLongOption, b.toLongOption) match
          case (Some(x), Some(y)) if x <= y && x < size => Some((x, Some(math.min(y, size - 1))))
          case _                                        => None
        case _ => None
    }

  def etag(n: Node): String = s"\"${n.etag}-${n.version}\""

  /**
   * 中身を返す。download なら保存させる（attachment）。HTML・SVG などスクリプトを含み得る形式は、表示の指定でも保存させる。
   * PDF 以外は CSP の sandbox を付け、万一表示されてもスクリプトを動かさない。
   */
  def node(req: Request[IO], files: FileService, n: Node, download: Boolean, head: Boolean = false): IO[Response[IO]] =
    val tag = etag(n)
    if req.headers.get(CIString("If-None-Match")).exists(_.head.value == tag) then IO.pure(Response[IO](Status.NotModified).putHeaders("ETag" -> tag))
    else n.blobKey match
      case None => IO.pure(Response[IO](Status.NotFound))
      case Some(key) =>
        val range = parseRange(req.headers.get(CIString("Range")).map(_.head.value), n.size).filter(_ => n.size > 0)
        val inline = !download && FileKind.inlineSafe(n.name)
        val ct     = if inline then n.contentType.getOrElse(FileKind.contentType(n.name)) else FileKind.contentType(n.name)
        val common = List[Header.ToRaw](
          "ETag"                   -> tag,
          "Last-Modified"          -> httpDate.format(n.updatedAt),
          "Accept-Ranges"          -> "bytes",
          "Content-Disposition"    -> Names.contentDisposition(n.name, inline),
          "X-Content-Type-Options" -> "nosniff",
          "Cache-Control"          -> "private, no-cache"
        ) ++ (if FileKind.kind(n.name) == FileKind.Pdf then Nil else List[Header.ToRaw]("Content-Security-Policy" -> "sandbox; default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'"))
        if head then
          IO.pure(Response[IO](Status.Ok).putHeaders(common*).putHeaders("Content-Type" -> ct, "Content-Length" -> n.size.toString))
        else
          files.read(key, range).map {
            case None => Response[IO](if range.isDefined then Status.RangeNotSatisfiable else Status.NotFound)
            case Some((meta, body)) =>
              val base = Response[IO](if meta.range.isDefined then Status.PartialContent else Status.Ok).withBodyStream(body)
                .putHeaders(common*).putHeaders("Content-Type" -> ct, "Content-Length" -> meta.size.toString)
              meta.range.fold(base)((a, b) => base.putHeaders("Content-Range" -> s"bytes $a-$b/${meta.total}"))
          }

  /** ZIP の応答（1つなら「その名前.zip」、複数なら「name.zip」）。 */
  def zip(files: FileService, items: List[Node], name: String): IO[Response[IO]] =
    val fileName = (if items.size == 1 then items.head.name else name) + ".zip"
    files.zipOf(items).map(body => Response[IO](Status.Ok).withBodyStream(body).putHeaders(
      "Content-Type"        -> "application/zip",
      "Content-Disposition" -> Names.contentDisposition(fileName, inline = false),
      "Cache-Control"       -> "no-store"
    ))
