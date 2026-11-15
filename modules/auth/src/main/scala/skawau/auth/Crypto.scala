package skawau.auth

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.{Mac, SecretKeyFactory}
import javax.crypto.spec.{PBEKeySpec, SecretKeySpec}

/** 乱数のトークン・ハッシュ・署名。 */
object Crypto:
  private val rnd = new SecureRandom()
  private val b64 = Base64.getUrlEncoder.withoutPadding()

  def randomToken(bytes: Int = 32): String =
    val a = new Array[Byte](bytes)
    rnd.nextBytes(a)
    b64.encodeToString(a)

  def sha256(s: String): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))

  def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString

  def pkceChallenge(verifier: String): String = b64.encodeToString(sha256(verifier))

  def hmacSha256(key: String, data: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    mac.doFinal(data)

  def hmacHex(key: String, data: Array[Byte]): String = hex(hmacSha256(key, data))

  /** 時間に依存しない比較（署名の検証用）。 */
  def constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8))

/** APIキー（WebDAV のアプリパスワードを兼ねる）: skawau_ で始まるランダム値。DB にはハッシュだけを保存し、画面には先頭の prefix だけ出す。 */
object ApiKeys:
  val Prefix = "skawau_"
  def generate(): (String, String) =
    val key = Prefix + Crypto.randomToken(30)
    (key, key.take(Prefix.length + 6))
  def looksLike(s: String): Boolean = s.startsWith(Prefix)

/** パスワードのハッシュ（公開リンクのパスワード）。PBKDF2-HMAC-SHA256、"pbkdf2:<回数>:<塩>:<値>"。 */
object Passwords:
  private val Iterations = 210000

  private def derive(password: String, salt: Array[Byte], iterations: Int): Array[Byte] =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray, salt, iterations, 256)).getEncoded

  def hash(password: String): String =
    val salt = new Array[Byte](16)
    new SecureRandom().nextBytes(salt)
    val enc = Base64.getEncoder
    List("pbkdf2", Iterations.toString, enc.encodeToString(salt), enc.encodeToString(derive(password, salt, Iterations))).mkString(":")

  def verify(password: String, stored: String): Boolean =
    stored.split(':') match
      case Array("pbkdf2", it, salt, value) =>
        scala.util.Try {
          val dec = Base64.getDecoder
          MessageDigest.isEqual(derive(password, dec.decode(salt), it.toInt), dec.decode(value))
        }.getOrElse(false)
      case _ => false
