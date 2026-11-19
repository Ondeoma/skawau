package skawau.api

import cats.effect.*
import org.http4s.*
import org.typelevel.ci.CIString
import skawau.core.*
import sbirds.observe.Metrics
import skawau.shared.FileKind

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** ファイルの中身の応答（REST・公開リンク・WebDAV・ONLYOFFICE で共通）。Range・条件付き GET に対応する。 */
object Serve {

  val httpDate: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

  /** "bytes=a-b" / "bytes=a-" / "bytes=-n"（1つの範囲だけ）。 */
  def parseRange(h: Option[String], size: Long): Option[(Long, Option[Long])] =
    h.map(_.trim).filter(_.startsWith("bytes=")).map(_.drop(6)).filterNot(_.contains(',')).flatMap { r =>
      r.split("-", -1) match {
        case Array("", n) => n.toLongOption.filter(_ > 0).map(x => (math.max(0L, size - x), None))
        case Array(a, "") => a.toLongOption.filter(_ < size).map(x => (x, None))
        case Array(a, b) => (a.toLongOption, b.toLongOption) match {
            case (Some(x), Some(y)) if x <= y && x < size => Some((x, Some(math.min(y, size - 1))))
            case _ => None
          }
        case _ => None
      }
    }

  def etag(n: Node): String = s"\"${n.etag}-${n.version}\""

  /**
   * ダウンロード（表示を含む）の数と、実際に送ったバイト数を数える。via は経路（web・api・dav・mcp・link・office）。
   * Range の続き（動画の再生などで何度も来る）は数に入れず、バイト数だけ足す。
   */
  private def metered(body: fs2.Stream[IO, Byte], via: String, kind: String, first: Boolean): fs2.Stream[IO, Byte] = {
    val attrs = List("via" -> via, "kind" -> kind)
    fs2.Stream.exec(IO.whenA(first)(Metrics.count("skawau.files.downloaded", attrs*))) ++
      body.chunks.evalTap(c => Metrics.add("skawau.files.downloaded_bytes", c.size.toLong, attrs*)).flatMap(
        fs2.Stream.chunk
      )
  }

  /**
   * 中身を返す。download なら保存させる（attachment）。HTML・SVG などスクリプトを含み得る形式は、表示の指定でも保存させる。
   * PDF 以外は CSP の sandbox を付け、万一表示されてもスクリプトを動かさない。
   * Content-Type は保存した値（アップロードしたときの名前から決めたもの）を信用せず、いまの名前から決める
   * （HTML を上げてから名前を .pdf に変えると、HTML が CSP なしで表示されてしまうため）。
   */
  def node(req: Request[IO],
           files: FileService,
           n: Node,
           download: Boolean,
           via: String,
           head: Boolean = false): IO[Response[IO]] = {
    val tag = etag(n)
    if req.headers.get(CIString("If-None-Match")).exists(_.head.value == tag) then
      IO.pure(Response[IO](Status.NotModified).putHeaders("ETag" -> tag))
    else
      n.blobKey match {
        case None => IO.pure(Response[IO](Status.NotFound))
        case Some(key) =>
          val range = parseRange(req.headers.get(CIString("Range")).map(_.head.value), n.size).filter(_ => n.size > 0)
          val inline = !download && FileKind.inlineSafe(n.name)
          val ct = FileKind.contentType(n.name)
          val common = List[Header.ToRaw](
            "ETag" -> tag,
            "Last-Modified" -> httpDate.format(n.updatedAt),
            "Accept-Ranges" -> "bytes",
            "Content-Disposition" -> Names.contentDisposition(n.name, inline),
            "X-Content-Type-Options" -> "nosniff",
            "Cache-Control" -> "private, no-cache"
          ) ++
            (if FileKind.kind(n.name) == FileKind.Pdf then Nil
             else
               List[Header.ToRaw]("Content-Security-Policy" ->
                 "sandbox; default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'"))
          if head then
            IO.pure(Response[IO](Status.Ok).putHeaders(common*).putHeaders(
              "Content-Type" -> ct,
              "Content-Length" -> n.size.toString
            ))
          else
            files.read(key, range).map {
              case None => Response[IO](if range.isDefined then Status.RangeNotSatisfiable else Status.NotFound)
              case Some((meta, body)) =>
                val kind = if download then "download" else "inline"
                val base = Response[IO](if meta.range.isDefined then Status.PartialContent else Status.Ok)
                  .withBodyStream(metered(body, via, kind, meta.range.forall(_._1 == 0)))
                  .putHeaders(common*).putHeaders("Content-Type" -> ct, "Content-Length" -> meta.size.toString)
                meta.range.fold(base)((a, b) => base.putHeaders("Content-Range" -> s"bytes $a-$b/${meta.total}"))
            }
      }
  }

  /** ZIP の応答（1つなら「その名前.zip」、複数なら「name.zip」）。 */
  def zip(files: FileService, items: List[Node], name: String, via: String): IO[Response[IO]] = {
    val fileName = (if items.size == 1 then items.head.name else name) + ".zip"
    files.zipOf(items).map(body =>
      Response[IO](Status.Ok).withBodyStream(metered(body, via, "zip", first = true)).putHeaders(
        "Content-Type" -> "application/zip",
        "Content-Disposition" -> Names.contentDisposition(fileName, inline = false),
        "Cache-Control" -> "no-store"
      )
    )
  }
}
