package skawau.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.{ZipEntry, ZipOutputStream}

/**
 * 新しく作る Office 文書の中身（最小構成の OOXML）。外部のひな形ファイルを持たずにここで組み立てる。
 * 空の文書（docx）・シート1枚のブック（xlsx）・タイトルのスライド1枚（pptx、16:9）。テキスト系は空。
 */
object OfficeTemplates {

  /** 作れる種類（拡張子 → 表示名）。 */
  val kinds: List[(String, String)] = List(
    "docx" -> "文書（Word）",
    "xlsx" -> "スプレッドシート（Excel）",
    "pptx" -> "プレゼンテーション（PowerPoint）",
    "txt" -> "テキスト",
    "md" -> "Markdown"
  )

  def supports(ext: String): Boolean = kinds.exists(_._1 == ext)

  /** 拡張子に応じた空の中身（Office 以外は空のバイト列）。 */
  def blank(ext: String): Array[Byte] = ext match {
    case "docx" => zip(docx)
    case "xlsx" => zip(xlsx)
    case "pptx" => zip(pptx)
    case _ => Array.emptyByteArray
  }

  private def zip(parts: List[(String, String)]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val z = new ZipOutputStream(out)
    parts.foreach {
      (name, body) =>
        {
          z.putNextEntry(new ZipEntry(name))
          z.write(body.getBytes(StandardCharsets.UTF_8))
          z.closeEntry()
        }
    }
    z.close()
    out.toByteArray
  }

  private val Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n"
  private val RelNs = "http://schemas.openxmlformats.org/package/2006/relationships"
  private val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
  private def rels(items: (String, String, String)*): String =
    Xml + s"""<Relationships xmlns="$RelNs">""" +
      items.map((id, tpe, target) => s"""<Relationship Id="$id" Type="$R/$tpe" Target="$target"/>""").mkString +
      "</Relationships>"
  private def types(overrides: (String, String)*): String =
    Xml + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
      """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
      """<Default Extension="xml" ContentType="application/xml"/>""" +
      overrides.map((p, t) => s"""<Override PartName="$p" ContentType="$t"/>""").mkString + "</Types>"
  private val Ooxml = "application/vnd.openxmlformats-officedocument"

  // ---------------------------------------------------------------- docx（A4 縦）

  private val Wml = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

  private def docx: List[(String, String)] = List(
    "[Content_Types].xml" -> types(
      "/word/document.xml" -> s"$Ooxml.wordprocessingml.document.main+xml",
      "/word/styles.xml" -> s"$Ooxml.wordprocessingml.styles+xml"
    ),
    "_rels/.rels" -> rels(("rId1", "officeDocument", "word/document.xml")),
    "word/_rels/document.xml.rels" -> rels(("rId1", "styles", "styles.xml")),
    // 既定の書式: 10.5pt、日本語は游明朝（Word の日本語版の既定に近づける）
    "word/styles.xml" ->
      (Xml + s"""<w:styles xmlns:w="$Wml"><w:docDefaults><w:rPrDefault><w:rPr>""" +
        """<w:rFonts w:ascii="Century" w:eastAsia="游明朝" w:hAnsi="Century" w:cs="Times New Roman"/><w:kern w:val="2"/>""" +
        """<w:sz w:val="21"/><w:szCs w:val="22"/><w:lang w:val="en-US" w:eastAsia="ja-JP" w:bidi="ar-SA"/></w:rPr></w:rPrDefault>""" +
        """<w:pPrDefault><w:pPr><w:jc w:val="both"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
        """<w:style w:type="paragraph" w:default="1" w:styleId="a"><w:name w:val="Normal"/><w:qFormat/><w:pPr><w:widowControl w:val="0"/></w:pPr></w:style>""" +
        """</w:styles>"""),
    "word/document.xml" ->
      (Xml +
        """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p/>""" +
        """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1985" w:right="1701" w:bottom="1701" w:left="1701" w:header="851" """ +
        """w:footer="992" w:gutter="0"/></w:sectPr></w:body></w:document>""")
  )

  // ---------------------------------------------------------------- xlsx

  private val Sml = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

  private def xlsx: List[(String, String)] = List(
    "[Content_Types].xml" -> types(
      "/xl/workbook.xml" -> s"$Ooxml.spreadsheetml.sheet.main+xml",
      "/xl/worksheets/sheet1.xml" -> s"$Ooxml.spreadsheetml.worksheet+xml",
      "/xl/styles.xml" -> s"$Ooxml.spreadsheetml.styles+xml"
    ),
    "_rels/.rels" -> rels(("rId1", "officeDocument", "xl/workbook.xml")),
    "xl/workbook.xml" ->
      (Xml +
        s"""<workbook xmlns="$Sml" xmlns:r="$R"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>"""),
    "xl/_rels/workbook.xml.rels" ->
      rels(("rId1", "worksheet", "worksheets/sheet1.xml"), ("rId2", "styles", "styles.xml")),
    "xl/worksheets/sheet1.xml" -> (Xml + s"""<worksheet xmlns="$Sml"><sheetData/></worksheet>"""),
    "xl/styles.xml" ->
      (Xml + s"""<styleSheet xmlns="$Sml"><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>""" +
        """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
        """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>""" +
        """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
  )

  // ---------------------------------------------------------------- pptx（16:9、タイトルのスライド1枚）

  private val Pml = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
    s"""xmlns:r="$R" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""
  private val GroupProps =
    """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"""
  private val NoOverride = """<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>"""

  /** プレースホルダー（位置を書くのはレイアウトだけ。スライドはレイアウトの位置を引き継ぐ）。 */
  private def placeholder(id: Int,
                          name: String,
                          tpe: String,
                          idx: Option[Int],
                          pos: Option[(Long, Long, Long, Long)],
                          size: Int): String = {
    val xfrm = pos.fold("")((x, y, cx, cy) => s"""<a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""")
    val anchor = if tpe == "ctrTitle" then """ anchor="b"""" else ""
    val ph = s"""<p:ph type="$tpe"${idx.fold("")(i => s""" idx="$i"""")}/>"""
    val run = if pos.isDefined then s"""<a:lvl1pPr algn="ctr"><a:defRPr sz="$size"/></a:lvl1pPr>""" else ""
    s"""<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr>$ph</p:nvPr></p:nvSpPr>""" +
      s"""<p:spPr>$xfrm</p:spPr><p:txBody><a:bodyPr$anchor/><a:lstStyle>$run</a:lstStyle><a:p><a:endParaRPr lang="ja-JP"/></a:p></p:txBody></p:sp>"""
  }

  private def pptx: List[(String, String)] = List(
    "[Content_Types].xml" -> types(
      "/ppt/presentation.xml" -> s"$Ooxml.presentationml.presentation.main+xml",
      "/ppt/slideMasters/slideMaster1.xml" -> s"$Ooxml.presentationml.slideMaster+xml",
      "/ppt/slideLayouts/slideLayout1.xml" -> s"$Ooxml.presentationml.slideLayout+xml",
      "/ppt/slides/slide1.xml" -> s"$Ooxml.presentationml.slide+xml",
      "/ppt/theme/theme1.xml" -> s"$Ooxml.theme+xml"
    ),
    "_rels/.rels" -> rels(("rId1", "officeDocument", "ppt/presentation.xml")),
    "ppt/presentation.xml" ->
      (Xml + s"""<p:presentation $Pml>""" +
        """<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>""" +
        """<p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst>""" +
        """<p:sldSz cx="12192000" cy="6858000"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>"""),
    "ppt/_rels/presentation.xml.rels" -> rels(
      ("rId1", "slideMaster", "slideMasters/slideMaster1.xml"),
      ("rId2", "slide", "slides/slide1.xml"),
      ("rId3", "theme", "theme/theme1.xml")
    ),
    "ppt/slideMasters/slideMaster1.xml" ->
      (Xml + s"""<p:sldMaster $Pml><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg>""" +
        s"""<p:spTree>$GroupProps</p:spTree></p:cSld>""" +
        """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" """ +
        """accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>""" +
        """<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>"""),
    "ppt/slideMasters/_rels/slideMaster1.xml.rels" ->
      rels(("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"), ("rId2", "theme", "../theme/theme1.xml")),
    "ppt/slideLayouts/slideLayout1.xml" ->
      (Xml + s"""<p:sldLayout $Pml type="title" preserve="1"><p:cSld name="タイトル スライド"><p:spTree>$GroupProps""" +
        placeholder(2, "Title 1", "ctrTitle", None, Some((1524000L, 1122363L, 9144000L, 2387600L)), 6000) +
        placeholder(3, "Subtitle 2", "subTitle", Some(1), Some((1524000L, 3602038L, 9144000L, 1655762L)), 2400) +
        s"""</p:spTree></p:cSld>$NoOverride</p:sldLayout>"""),
    "ppt/slideLayouts/_rels/slideLayout1.xml.rels" -> rels(("rId1", "slideMaster", "../slideMasters/slideMaster1.xml")),
    "ppt/slides/slide1.xml" ->
      (Xml + s"""<p:sld $Pml><p:cSld><p:spTree>$GroupProps""" +
        placeholder(2, "Title 1", "ctrTitle", None, None, 0) +
        placeholder(3, "Subtitle 2", "subTitle", Some(1), None, 0) +
        s"""</p:spTree></p:cSld>$NoOverride</p:sld>"""),
    "ppt/slides/_rels/slide1.xml.rels" -> rels(("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml")),
    "ppt/theme/theme1.xml" -> theme
  )

  private def theme: String = {
    def color(n: String, v: String) = s"""<a:$n><a:srgbClr val="$v"/></a:$n>"""
    val fill = """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>"""
    val line = s"""<a:ln w="6350">$fill</a:ln>"""
    val eff = """<a:effectStyle><a:effectLst/></a:effectStyle>"""
    val fonts = (latin: String) => s"""<a:latin typeface="$latin"/><a:ea typeface=""/><a:cs typeface=""/>"""
    Xml +
      """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office"><a:themeElements>""" +
      """<a:clrScheme name="Office"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>""" +
      color("dk2", "44546A") + color("lt2", "E7E6E6") + color("accent1", "4472C4") + color("accent2", "ED7D31") +
      color("accent3", "A5A5A5") +
      color("accent4", "FFC000") + color("accent5", "5B9BD5") + color("accent6", "70AD47") + color("hlink", "0563C1") +
      color("folHlink", "954F72") +
      "</a:clrScheme>" +
      s"""<a:fontScheme name="Office"><a:majorFont>${fonts("Calibri Light")}</a:majorFont><a:minorFont>${fonts(
          "Calibri"
        )}</a:minorFont></a:fontScheme>""" +
      s"""<a:fmtScheme name="Office"><a:fillStyleLst>${fill * 3}</a:fillStyleLst><a:lnStyleLst>${line *
          3}</a:lnStyleLst>""" +
      s"""<a:effectStyleLst>${eff * 3}</a:effectStyleLst><a:bgFillStyleLst>${fill *
          3}</a:bgFillStyleLst></a:fmtScheme>""" +
      "</a:themeElements></a:theme>"
  }
}
