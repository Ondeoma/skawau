package skawau.db

import cats.effect.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.util.UUID

/** OAuth の認可コードと、認可で発行した API キー（表の形は sbirds.oauth.OAuthKeyStore のもの）。 */
final class OAuthStore(xa: Transactor[IO]) extends sbirds.oauth.OAuthKeyStore(xa) {

  /** メールアドレス（小文字）が一致する、使えるユーザー（id・名前・メールアドレス）。 */
  def usersByEmail(emails: List[String]): IO[List[(UUID, String, String)]] =
    if emails.isEmpty then IO.pure(Nil)
    else
      tx(
        sql"""select id, name, email from app_user where not disabled and lower(email) = any(${emails.toArray})""".query[
          (UUID, String, String)
        ].to[List]
      )
}
