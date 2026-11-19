package skawau.core

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Element, Node as DomNode}

/**
 * Office 文書（OOXML）から文字を取り出す（MCP で AI に中身を読ませるため）。書式・画像は捨てる。
 * docx は段落ごと、xlsx はシートごとに行をタブ区切り、pptx はスライドごと。
 */
object OfficeText {

  /** ZIP の中で読む部品の大きさの上限（壊れた・悪意のある文書で膨らまないように）。 */
  private val MaxPart = 20L * 1024 * 1024

  /** 展開した合計の上限と、読む部品の数の上限（小さな部品を大量に詰めた ZIP 爆弾を防ぐ）。 */
  private val MaxTotal = 100L * 1024 * 1024
  private val MaxParts = 2000

  /** 文字を取り出すのに使う部品だけを読む。 */
  private val Wanted =
    "^(word/document|xl/sharedStrings|xl/workbook|xl/worksheets/sheet\\d+|ppt/slides/slide\\d+)\\.xml$".r
  private def wanted(
    name: String
  ): Boolean = Wanted.matches(name)

  def supports(
    ext: String
  ): Boolean = Set("docx", "xlsx", "pptx")(ext)

  def extract(
    ext: String,
    bytes: Array[Byte]
  ): Either[String, String] =
    scala.util.Try {
      val parts = unzip(bytes)
      ext match {
        case "docx" => docx(parts)
        case "xlsx" => xlsx(parts)
        case "pptx" => pptx(parts)
        case _ => throw new IllegalArgumentException("対応していない形式です")
      }
    }.toEither.left.map(e => s"文字を取り出せませんでした: ${e.getMessage}")

  private def unzip(
    bytes: Array[Byte]
  ): Map[String, Array[Byte]] = {
    val z = new ZipInputStream(new ByteArrayInputStream(bytes))
    var all = 0L
    var count = 0
    Iterator.continually(z.getNextEntry).takeWhile(_ != null).filter(e => wanted(e.getName)).map { e =>
      count += 1
      if count > MaxParts then throw new IllegalArgumentException("文書の部品が多すぎます")
      val out = new java.io.ByteArrayOutputStream()
      val buf = new Array[Byte](64 * 1024)
      var total = 0L
      Iterator.continually(z.read(buf)).takeWhile(_ >= 0).foreach { n =>
        total += n
        all += n
        if total > MaxPart then throw new IllegalArgumentException("文書の部品が大きすぎます")
        if all > MaxTotal then throw new IllegalArgumentException("文書が大きすぎます")
        out.write(buf, 0, n)
      }
      e.getName -> out.toByteArray
    }.toMap
  }

  private def parse(
    b: Array[Byte]
  ): Element = {
    val f = DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(true)
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) // XXE を防ぐ
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    f.newDocumentBuilder().parse(new ByteArrayInputStream(b)).getDocumentElement
  }

  /** 子孫の要素（名前空間を除いた名前で）。 */
  private def all(
    e: DomNode,
    local: String
  ): List[Element] = {
    val nl = e.asInstanceOf[Element].getElementsByTagNameNS("*", local)
    (0 until nl.getLength).map(nl.item(_).asInstanceOf[Element]).toList
  }

  private def texts(
    e: Element,
    local: String
  ): String = all(e, local).map(_.getTextContent).mkString

  private def docx(
    parts: Map[String, Array[Byte]]
  ): String =
    parts.get("word/document.xml").map(parse).map(doc => all(doc, "p").map(texts(_, "t")).mkString("\n")).getOrElse("")

  private def numbered(
    parts: Map[String, Array[Byte]],
    prefix: String
  ): List[(Int, Array[Byte])] =
    parts.toList.flatMap(
      (
        k,
        v
      ) =>
        s"^${java.util.regex.Pattern.quote(prefix)}(\\d+)\\.xml$$".r.findFirstMatchIn(k).map(m => m.group(1).toInt -> v)
    ).sortBy(_._1)

  private def pptx(
    parts: Map[String, Array[Byte]]
  ): String =
    numbered(parts, "ppt/slides/slide").map {
      (
        i,
        b
      ) =>
        {
          val lines = all(parse(b), "p").map(texts(_, "t")).filter(_.nonEmpty)
          (s"## スライド $i" :: lines).mkString("\n")
        }
    }.mkString("\n\n")

  private def xlsx(
    parts: Map[String, Array[Byte]]
  ): String = {
    val shared = parts.get("xl/sharedStrings.xml").map(parse).map(d =>
      all(d, "si").map(texts(_, "t")).toVector
    ).getOrElse(Vector.empty)
    val names =
      parts.get("xl/workbook.xml").map(parse).map(d => all(d, "sheet").map(_.getAttribute("name"))).getOrElse(Nil)
    numbered(parts, "xl/worksheets/sheet").map {
      (
        i,
        b
      ) =>
        {
          val rows = all(parse(b), "row").map { r =>
            all(r, "c").map { c =>
              val v = all(c, "v").headOption.map(_.getTextContent).getOrElse("")
              c.getAttribute("t") match {
                case "s" => v.toIntOption.flatMap(shared.lift).getOrElse("")
                case "inlineStr" => texts(c, "t")
                case _ => v
              }
            }.mkString("\t")
          }.filter(_.trim.nonEmpty)
          (s"## ${names.lift(i - 1).getOrElse(s"Sheet$i")}" :: rows).mkString("\n")
        }
    }.mkString("\n\n")
  }
}
