package skawau.api

class ServeSpec extends munit.FunSuite {

  test("Range ヘッダーの解釈") {
    assertEquals(Serve.parseRange(Some("bytes=0-99"), 1000), Some((0L, Some(99L))))
    assertEquals(Serve.parseRange(Some("bytes=500-"), 1000), Some((500L, None)))
    assertEquals(Serve.parseRange(Some("bytes=-100"), 1000), Some((900L, None)))
    assertEquals(Serve.parseRange(Some("bytes=900-5000"), 1000), Some((900L, Some(999L))))
    assertEquals(Serve.parseRange(Some("bytes=1000-"), 1000), None)
    assertEquals(Serve.parseRange(Some("bytes=5-1"), 1000), None)
    assertEquals(Serve.parseRange(Some("bytes=0-1,5-6"), 1000), None)
    assertEquals(Serve.parseRange(None, 1000), None)
  }

  test("同じ名前があるときの扱いの指定") {
    assertEquals(OnConflict.parse(Some("Overwrite")), OnConflict.Overwrite)
    assertEquals(OnConflict.parse(Some("rename")), OnConflict.Rename)
    assertEquals(OnConflict.parse(None), OnConflict.Fail)
  }
}
