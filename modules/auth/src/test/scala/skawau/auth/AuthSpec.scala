package skawau.auth

import io.circe.Json
import io.circe.syntax.*

import java.time.Instant

class AuthSpec extends munit.FunSuite:

  test("JWT（HS256）の署名と検証") {
    val t = Jwt.sign(Json.obj("fid" -> "x".asJson), "secret-0123456789")
    assertEquals(Jwt.verify(t, "secret-0123456789").map(_.hcursor.get[String]("fid").toOption), Right(Some("x")))
    assert(Jwt.verify(t, "other-secret-0123").isLeft)
    val parts = t.split('.')
    assert(Jwt.verify(parts(0) + "." + parts(1).reverse + "." + parts(2), "secret-0123456789").isLeft)
  }

  test("JWT の期限") {
    val past = Jwt.sign(Json.obj("exp" -> Instant.now().minusSeconds(10).getEpochSecond.asJson), "k")
    assert(Jwt.verify(past, "k").isLeft)
    val future = Jwt.sign(Json.obj("exp" -> Instant.now().plusSeconds(60).getEpochSecond.asJson), "k")
    assert(Jwt.verify(future, "k").isRight)
  }

  test("HS256 以外（alg: none）は受け付けない") {
    val enc = java.util.Base64.getUrlEncoder.withoutPadding()
    val none = enc.encodeToString("""{"alg":"none"}""".getBytes) + "." + enc.encodeToString("{}".getBytes) + "."
    assert(Jwt.verify(none, "k").isLeft)
  }

  test("リンクのパスワードのハッシュ") {
    val h = Passwords.hash("ひみつ123")
    assert(Passwords.verify("ひみつ123", h))
    assert(!Passwords.verify("ひみつ124", h))
    assert(!Passwords.verify("x", "broken"))
    assertNotEquals(Passwords.hash("a"), Passwords.hash("a")) // 塩が毎回違う
  }

  test("APIキーの形式") {
    val (key, prefix) = ApiKeys.generate()
    assert(ApiKeys.looksLike(key))
    assert(key.startsWith(prefix))
  }
