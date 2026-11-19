package skawau.api

import cats.effect.*
import org.http4s.*
import org.http4s.headers.Authorization
import sbirds.auth.Crypto
import skawau.auth.ApiKeys
import skawau.core.*
import skawau.db.*

import java.time.Instant
import java.util.UUID

/** 認証: Cookie のセッション、`Authorization: Bearer <APIキー>`、WebDAV は Basic（パスワードに APIキー）。 */
final class SessionAuth(users: UserStore, cfg: ApiConfig) {

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
        req.cookies.find(_.name == cfg.cookieName).map(_.content) match {
          case None => IO.pure(None)
          case Some(token) => users.findSession(Crypto.sha256(token), Instant.now()).map(_.map((u, gs) =>
              AuthSession(u, gs, isAdmin(u, gs))
            ))
        }
    }

  /** WebDAV: Basic（ユーザー名は何でもよく、パスワードに APIキー）または Bearer。Cookie は使わない。 */
  def fromDav(req: Request[IO]): IO[Option[AuthSession]] =
    req.headers.get[Authorization] match {
      case Some(Authorization(BasicCredentials(_, pass))) if ApiKeys.looksLike(pass) => byKey(pass, dav = true)
      case _ => bearer(req).fold(IO.pure(None))(byKey(_, dav = true))
    }

  private val publicOrigin =
    "^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+".r.findFirstIn(cfg.publicUrl).getOrElse(cfg.publicUrl).toLowerCase

  /**
   * この画面から送られた要求か（Cookie のセッションでの更新系の要求に使う CSRF の対策）。
   * SameSite=Lax の Cookie は同じサイトの別のサブドメインからの送信を防げないため、Sec-Fetch-Site（なければ Origin）で確かめる。
   */
  def sameOrigin(req: Request[IO]): Boolean = {
    def header(n: String) = req.headers.get(org.typelevel.ci.CIString(n)).map(_.head.value)
    header("Sec-Fetch-Site") match {
      case Some(v) => v == "same-origin" || v == "none"
      case None => header("Origin").forall(_.toLowerCase == publicOrigin)
    }
  }

  def sessionFor(userId: UUID): IO[Option[AuthSession]] =
    users.withGroups(userId).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs))))

  private def bearer(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t }.filter(
      ApiKeys.looksLike
    )

  def issue(user: User, idToken: Option[String] = None): IO[ResponseCookie] = {
    val token = Crypto.randomToken()
    users.createSession(
      Crypto.sha256(token),
      user.id,
      Instant.now().plusSeconds(cfg.sessionTtl.toSeconds),
      idToken
    ).as(cookie(token, cfg.sessionTtl.toSeconds))
  }

  /** sbirds のアプリの表示名（ログインしたときの ID トークンにある. API キーや開発用のログインでは空）. */
  def appNames(req: Request[IO]): IO[Map[String, String]] =
    req.cookies.find(_.name == cfg.cookieName) match {
      case Some(c) =>
        users.sessionIdToken(Crypto.sha256(c.content)).map(_.fold(Map.empty)(sbirds.auth.AppNames.fromIdToken))
      case None => IO.pure(Map.empty)
    }

  /** セッションを消す. IdP からもログアウトするための id_token（OIDC でログインしていたとき）と、Cookie を消す指示を返す. */
  def revoke(req: Request[IO]): IO[(Option[String], ResponseCookie)] =
    req.cookies.find(_.name == cfg.cookieName) match {
      case Some(c) => users.deleteSession(Crypto.sha256(c.content)).map(_ -> cookie("", 0))
      case None => IO.pure(None -> cookie("", 0))
    }

  private def cookie(value: String, maxAge: Long): ResponseCookie =
    ResponseCookie(
      cfg.cookieName,
      value,
      maxAge = Some(maxAge),
      path = Some("/"),
      httpOnly = true,
      secure = cfg.cookieSecure,
      sameSite = Some(SameSite.Lax)
    )
}
