package skawau.api

import cats.effect.*
import org.http4s.*
import org.http4s.headers.Authorization
import sbirds.auth.Crypto
import sbirds.http.{Csrf, HttpParts, SessionCookie}
import skawau.auth.ApiKeys
import skawau.core.*
import skawau.db.*

import java.time.Instant
import java.util.UUID

/** 認証: Cookie のセッション、`Authorization: Bearer <APIキー>`、WebDAV は Basic（パスワードに APIキー）。 */
final class SessionAuth(users: UserStore, cfg: ApiConfig) {

  private val cookie = new SessionCookie(cfg.cookieName, cfg.cookieSecure, cfg.sessionTtl)

  private def isAdmin(u: User, gs: List[Group]) = u.isAdmin || gs.exists(g => cfg.adminGroups.contains(g.name))

  private def byKey(token: String, dav: Boolean, mcp: Boolean = false): IO[Option[AuthSession]] =
    users.findKey(Crypto.sha256(token), Instant.now()).map(_.collect {
      // 外部のサービスとの連携で発行したキー（OAuth）は WebDAV には使えない
      case (u, gs, kid, scopes, oauth) if !(dav && oauth) =>
        AuthSession(u, gs, isAdmin(u, gs), AuthVia.ApiKey(kid, scopes.flatMap(Scope.parse).toSet, dav, mcp))
    })

  def fromRequest(req: Request[IO]): IO[Option[AuthSession]] =
    bearer(req) match {
      case Some(token) =>
        // MCP のツールは REST API を内部で呼ぶ（その印のヘッダーで、変更履歴の経路を mcp にする）
        byKey(
          token,
          dav = false,
          mcp = req.pathInfo.renderString.startsWith("/mcp") ||
            req.headers.get(org.typelevel.ci.CIString(McpHeader)).isDefined
        )
      case None =>
        cookie.hash(req) match {
          case None => IO.pure(None)
          case Some(hash) =>
            users.findSession(hash, Instant.now()).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs))))
        }
    }

  /** WebDAV: Basic（ユーザー名は何でもよく、パスワードに APIキー）または Bearer。Cookie は使わない。 */
  def fromDav(req: Request[IO]): IO[Option[AuthSession]] =
    req.headers.get[Authorization] match {
      case Some(Authorization(BasicCredentials(_, pass))) if ApiKeys.looksLike(pass) => byKey(pass, dav = true)
      case _ => bearer(req).fold(IO.pure(None))(byKey(_, dav = true))
    }

  /**
   * この画面から送られた要求か（Cookie のセッションでの更新系の要求に使う CSRF の対策）。
   * SameSite=Lax の Cookie は同じサイトの別のサブドメインからの送信を防げないため、Sec-Fetch-Site（なければ Origin・Referer）で確かめる。
   */
  private val csrf = Csrf.Policy(cfg.publicUrl).copy(trustFetchSite = true, safeMethods = Csrf.SafeWithOptions)

  def sameOrigin(req: Request[IO]): Boolean = Csrf.fromOwnScreen(req, csrf)

  def sessionFor(userId: UUID): IO[Option[AuthSession]] =
    users.withGroups(userId).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs))))

  private def bearer(req: Request[IO]): Option[String] = HttpParts.bearer(req).filter(ApiKeys.looksLike)

  def issue(user: User, idToken: Option[String] = None): IO[ResponseCookie] =
    cookie.issue((hash, expires) => users.createSession(hash, user.id, expires, idToken))

  /** sbirds のアプリの表示名（ログインしたときの ID トークンにある. API キーや開発用のログインでは空）. */
  def appNames(req: Request[IO]): IO[Map[String, String]] = cookie.appNames(req)(users.sessionIdToken)

  /** セッションを消す. IdP からもログアウトするための id_token（OIDC でログインしていたとき）と、Cookie を消す指示を返す. */
  def revoke(req: Request[IO]): IO[(Option[String], ResponseCookie)] = cookie.revoke(req)(users.deleteSession)
}
