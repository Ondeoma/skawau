package skawau.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import org.http4s.*

class McpRoutesSpec extends munit.FunSuite {

  private val svg =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\">\n  <title>案2：ギアたてがみライオン</title>\n</svg>\n"
  private val uri = Uri.unsafeFromString("/api/files/00000000-0000-0000-0000-000000000001/upload?name=a.svg")
  private def text(r: Request[IO]): String = r.bodyText.compile.string.unsafeRunSync()

  test("文字列の本文は JSON の文字列にせず、そのまま送る（SVG などが壊れないように）") {
    val r = McpRoutes.request(Method.PUT, uri, Some(Json.fromString(svg)))
    assertEquals(text(r), svg)
    assertEquals(r.contentType.map(_.mediaType), Some(MediaType.text.plain))
  }

  test("オブジェクトの本文は JSON で送る") {
    val body = Json.obj("name" -> Json.fromString("報告.md"))
    val r = McpRoutes.request(Method.POST, uri, Some(body))
    assertEquals(parse(text(r)), Right(body))
    assertEquals(r.contentType.map(_.mediaType), Some(MediaType.application.json))
  }

  test("本文がなければ空") {
    assertEquals(text(McpRoutes.request(Method.GET, uri, None)), "")
  }
}
