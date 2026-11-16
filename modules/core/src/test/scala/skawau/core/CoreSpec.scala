package skawau.core

import skawau.shared.FileKind

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class CoreSpec extends munit.FunSuite:

  test("名前の検査") {
    assertEquals(Names.invalid("資料.docx"), None)
    assert(Names.invalid("").isDefined)
    assert(Names.invalid("..").isDefined)
    assert(Names.invalid("a/b").isDefined)
    assert(Names.invalid("a\\b").isDefined)
    assert(Names.invalid("a\u0000b").isDefined)
    assert(Names.invalid("x" * 256).isDefined)
    assert(Names.invalid("report\u202Etxt.exe").isDefined) // 向きを変える制御文字（拡張子の偽装）
    assertEquals(Names.zipSegment(".."), "_")
    assertEquals(Names.zipSegment("a/b"), "a_b")
  }

  test("ログイン後に戻る先は同じサイトのパスだけ") {
    assertEquals(skawau.shared.ReturnTo.orRoot(Some("/files/1?x=2")), "/files/1?x=2")
    assertEquals(skawau.shared.ReturnTo.orRoot(Some("//evil.example")), "/")
    assertEquals(skawau.shared.ReturnTo.orRoot(Some("/\\evil.example")), "/")
    assertEquals(skawau.shared.ReturnTo.orRoot(Some("https://evil.example")), "/")
    assertEquals(skawau.shared.ReturnTo.orRoot(Some("/a\nb")), "/")
  }

  test("同じ名前があるときの別名（大文字小文字は区別しない）") {
    assertEquals(Names.unique("a.txt", Set("b.txt")), "a.txt")
    assertEquals(Names.unique("a.txt", Set("A.TXT")), "a (2).txt")
    assertEquals(Names.unique("a.txt", Set("a.txt", "a (2).txt")), "a (3).txt")
    assertEquals(Names.unique("フォルダ", Set("フォルダ")), "フォルダ (2)")
    assertEquals(Names.unique(".env", Set(".env")), ".env (2)")
  }

  test("パスのセグメントと NFC への正規化（macOS の濁点の分解）") {
    assertEquals(Names.segments("/a//b/ c /"), List("a", "b", "c"))
    val decomposed = "プロジェクト" // ﾌﾟﾛｼﾞｪｸﾄ（結合文字）
    assertEquals(Names.normalize(decomposed), "プロジェクト")
  }

  test("Content-Disposition（日本語のファイル名）") {
    val h = Names.contentDisposition("見積書 2026.pdf", inline = false)
    assert(h.startsWith("attachment; filename=\""))
    assert(h.contains("filename*=UTF-8''%E8%A6%8B%E7%A9%8D%E6%9B%B8%202026.pdf"))
  }

  test("ファイルの種類と ONLYOFFICE の形式") {
    assertEquals(FileKind.kind("a.DOCX"), FileKind.Word)
    assertEquals(FileKind.kind("a.xlsx"), FileKind.Cell)
    assertEquals(FileKind.kind("a.ppt"), FileKind.Slide)
    assertEquals(FileKind.kind("photo.JPG"), FileKind.Image)
    assertEquals(FileKind.kind("x", isDir = true), FileKind.Folder)
    assert(FileKind.officeEditable("a.pptx"))
    assert(!FileKind.officeEditable("a.doc"))
    assertEquals(FileKind.officeType("a.doc"), Some(FileKind.Word))
    assert(!FileKind.inlineSafe("evil.html"))
    assert(!FileKind.inlineSafe("evil.svg"))
    assert(FileKind.inlineSafe("ok.pdf"))
  }

  private def entries(bytes: Array[Byte]): List[String] =
    val z = new ZipInputStream(new ByteArrayInputStream(bytes))
    Iterator.continually(z.getNextEntry).takeWhile(_ != null).map(_.getName).toList

  test("Office のひな形は必要な部品を持つ ZIP") {
    val docx = entries(OfficeTemplates.blank("docx"))
    assertEquals(docx.head, "[Content_Types].xml")
    assert(docx.contains("word/document.xml") && docx.contains("word/styles.xml"))
    assert(entries(OfficeTemplates.blank("xlsx")).contains("xl/worksheets/sheet1.xml"))
    val pptx = entries(OfficeTemplates.blank("pptx"))
    List("ppt/presentation.xml", "ppt/slides/slide1.xml", "ppt/slideMasters/slideMaster1.xml", "ppt/slideLayouts/slideLayout1.xml",
      "ppt/theme/theme1.xml").foreach(p => assert(pptx.contains(p), p))
    assertEquals(OfficeTemplates.blank("txt").length, 0)
  }

  test("XML として読める（ひな形の書き間違いの検出）") {
    val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(true)
    List("docx", "xlsx", "pptx").foreach { ext =>
      val z = new ZipInputStream(new ByteArrayInputStream(OfficeTemplates.blank(ext)))
      Iterator.continually(z.getNextEntry).takeWhile(_ != null).foreach { e =>
        val body = z.readAllBytes()
        f.newDocumentBuilder().parse(new ByteArrayInputStream(body)) // 壊れていれば例外
      }
    }
  }

  private def zip(parts: (String, String)*): Array[Byte] =
    val out = new java.io.ByteArrayOutputStream()
    val z   = new java.util.zip.ZipOutputStream(out)
    parts.foreach { (n, b) => z.putNextEntry(new java.util.zip.ZipEntry(n)); z.write(b.getBytes("UTF-8")); z.closeEntry() }
    z.close()
    out.toByteArray

  test("Office 文書から文字を取り出す") {
    val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    val docx = zip("word/document.xml" -> s"""<w:document xmlns:w="$w"><w:body><w:p><w:r><w:t>見積</w:t></w:r><w:r><w:t>書</w:t></w:r></w:p><w:p><w:r><w:t>二行目</w:t></w:r></w:p></w:body></w:document>""")
    assertEquals(OfficeText.extract("docx", docx), Right("見積書\n二行目"))
    val sml = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    val xlsx = zip(
      "xl/workbook.xml" -> s"""<workbook xmlns="$sml"><sheets><sheet name="売上" sheetId="1"/></sheets></workbook>""",
      "xl/sharedStrings.xml" -> s"""<sst xmlns="$sml"><si><t>品名</t></si><si><t>りんご</t></si></sst>""",
      "xl/worksheets/sheet1.xml" -> s"""<worksheet xmlns="$sml"><sheetData><row><c t="s"><v>0</v></c><c><v>3</v></c></row><row><c t="s"><v>1</v></c><c><v>120</v></c></row></sheetData></worksheet>""")
    assertEquals(OfficeText.extract("xlsx", xlsx), Right("## 売上\n品名\t3\nりんご\t120"))
    val pptx = OfficeTemplates.blank("pptx")
    assertEquals(OfficeText.extract("pptx", pptx), Right("## スライド 1"))
  }

  test("DOCTYPE を含む XML は読まない（XXE 対策）") {
    val evil = zip("word/document.xml" -> """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/passwd">]><d>&x;</d>""")
    assert(OfficeText.extract("docx", evil).isLeft)
  }
