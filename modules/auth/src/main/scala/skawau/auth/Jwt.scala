package skawau.auth

import io.circe.Json
import io.circe.parser.parse

import sbirds.auth.Crypto

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/**
 * JWT（HS256 のみ）。ONLYOFFICE の Document Server との署名（設定・コールバック）と、
 * Document Server に渡すダウンロード・コールバックの URL の署名に使う。
 */
object Jwt {
  private val enc = Base64.getUrlEncoder.withoutPadding()
  private val dec = Base64.getUrlDecoder
  private val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".getBytes(StandardCharsets.UTF_8))

  def sign(
    payload: Json,
    secret: String
  ): String = {
    val body = enc.encodeToString(payload.noSpaces.getBytes(StandardCharsets.UTF_8))
    val sig = enc.encodeToString(Crypto.hmacSha256(secret, s"$header.$body".getBytes(StandardCharsets.UTF_8)))
    s"$header.$body.$sig"
  }

  /** 署名と期限（exp があれば）を確かめてペイロードを返す。 */
  def verify(
    token: String,
    secret: String,
    now: Instant = Instant.now()
  ): Either[String, Json] =
    token.split('.') match {
      case Array(h, b, s) =>
        for {
          hj <- scala.util.Try(new String(dec.decode(h), StandardCharsets.UTF_8)).toEither.left.map(_ =>
            "ヘッダーが読めません"
          ).flatMap(parse(_).left.map(_.getMessage))
          _ <- Either.cond(hj.hcursor.get[String]("alg").contains("HS256"), (), "HS256 以外の署名は受け付けません")
          exp = enc.encodeToString(Crypto.hmacSha256(secret, s"$h.$b".getBytes(StandardCharsets.UTF_8)))
          _ <- Either.cond(Crypto.constantTimeEquals(exp, s), (), "署名が一致しません")
          pj <- scala.util.Try(new String(dec.decode(b), StandardCharsets.UTF_8)).toEither.left.map(_ =>
            "本文が読めません"
          ).flatMap(parse(_).left.map(_.getMessage))
          _ <- pj.hcursor.get[Long]("exp").toOption.fold(Right(()))(e =>
            Either.cond(Instant.ofEpochSecond(e).isAfter(now), (), "期限が切れています")
          )
        } yield pj
      case _ => Left("JWT の形式ではありません")
    }
}
