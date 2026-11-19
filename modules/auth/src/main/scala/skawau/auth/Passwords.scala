package skawau.auth

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** パスワードのハッシュ（公開リンクのパスワード）。PBKDF2-HMAC-SHA256、"pbkdf2:<回数>:<塩>:<値>"。 */
object Passwords {
  private val Iterations = 210000

  private def derive(
    password: String,
    salt: Array[Byte],
    iterations: Int
  ): Array[Byte] =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(
      password.toCharArray,
      salt,
      iterations,
      256
    )).getEncoded

  def hash(
    password: String
  ): String = {
    val salt = new Array[Byte](16)
    new SecureRandom().nextBytes(salt)
    val enc = Base64.getEncoder
    List(
      "pbkdf2",
      Iterations.toString,
      enc.encodeToString(salt),
      enc.encodeToString(derive(password, salt, Iterations))
    ).mkString(":")
  }

  def verify(
    password: String,
    stored: String
  ): Boolean =
    stored.split(':') match {
      case Array("pbkdf2", it, salt, value) =>
        scala.util.Try {
          val dec = Base64.getDecoder
          MessageDigest.isEqual(derive(password, dec.decode(salt), it.toInt), dec.decode(value))
        }.getOrElse(false)
      case _ => false
    }
}
