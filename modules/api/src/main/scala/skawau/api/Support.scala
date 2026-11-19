package skawau.api

import cats.effect.*
import skawau.core.*
import sbirds.shared.AppError

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * ONLYOFFICE（Document Server）との連携の設定。
 * url はブラウザから見た Document Server、internalUrl はこのサーバーから見た Document Server（保存した文書の取得に使う）、
 * appUrl は Document Server から見たこのサーバー（文書の取得・保存の知らせ）、secret は Document Server の JWT_SECRET。
 */
final case class OfficeConfig(url: String, internalUrl: String, appUrl: String, secret: String)

final case class ApiConfig(adminGroups: Set[String],
                           cookieSecure: Boolean,
                           publicUrl: String,
                           appSecret: String,
                           sessionTtl: FiniteDuration = 14.days,
                           cookieName: String = "skawau_session",
                           devLogin: Boolean = false,
                           maxUploadBytes: Long = 10L * 1024 * 1024 * 1024,
                           defaultQuota: Option[Long] = None,
                           versionsKept: Int = 30,
                           trashDays: Int = 30,
                           version: String = "0.1.0",
                           office: Option[OfficeConfig] = None,
                           oauthClients: List[OAuthClient] = Nil)

/**
 * 認可を求める外部のサービス（OAUTH_CLIENTS で登録する）。redirectUri は完全一致で照合する。
 * scopes はそのサービスが求めてよいスコープ（read のほか、ファイルを置くサービス（skaka の録画）には write）。
 */
final case class OAuthClient(id: String,
                             secret: String,
                             redirectUri: String,
                             name: String,
                             scopes: List[String] = List("read"))

type Res[A] = IO[Either[AppError, A]]

/** MCP のツールが REST API を内部で呼ぶときに付けるヘッダー。 */
val McpHeader = "X-Skawau-Mcp"

/** 日付の入力（yyyy-MM-dd）。 */
object Dates {
  def parse(s: Option[String], what: String): Either[AppError, Option[java.time.LocalDate]] =
    s.map(_.trim).filter(_.nonEmpty) match {
      case None => Right(None)
      case Some(v) => scala.util.Try(
          java.time.LocalDate.parse(v)
        ).toOption.toRight(AppError(ErrorCode.DateFormatInvalid, s"$what は yyyy-MM-dd で指定してください")).map(Some(_))
    }
}

/** URL のパスのデコード（WebDAV のパスなど）。 */
object UrlPath {
  def decode(s: String): String = java.net.URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8)
  def encodeSegment(s: String): String = java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
}
