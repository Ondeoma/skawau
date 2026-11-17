package skawau.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Decoder
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Authorization
import skawau.auth.{ApiKeys, Crypto}
import skawau.core.*
import skawau.db.*
import skawau.shared.*

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * ONLYOFFICE（Document Server）との連携の設定。
 * url はブラウザから見た Document Server、internalUrl はこのサーバーから見た Document Server（保存した文書の取得に使う）、
 * appUrl は Document Server から見たこのサーバー（文書の取得・保存の知らせ）、secret は Document Server の JWT_SECRET。
 */
final case class OfficeConfig(url: String, internalUrl: String, appUrl: String, secret: String)

final case class ApiConfig(
    adminGroups: Set[String],
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
    oauthClients: List[OAuthClient] = Nil
)

/**
 * 認可を求める外部のサービス（OAUTH_CLIENTS で登録する）。redirectUri は完全一致で照合する。
 * scopes はそのサービスが求めてよいスコープ（read のほか、ファイルを置くサービス（skaka の録画）には write）。
 */
final case class OAuthClient(id: String, secret: String, redirectUri: String, name: String, scopes: List[String] = List("read"))

/** 処理の失敗（HTTP の応答に変換する）。 */
enum Fail:
  case NotFound(msg: String)
  case Forbidden(msg: String)
  case Invalid(msg: String)
  case Conflict(msg: String)
  case Unauthorized(msg: String)
  case TooLarge(msg: String)
  case Insufficient(msg: String)

  def message: String = this match
    case NotFound(m)     => m
    case Forbidden(m)    => m
    case Invalid(m)      => m
    case Conflict(m)     => m
    case Unauthorized(m) => m
    case TooLarge(m)     => m
    case Insufficient(m) => m

  /** 応答の HTTP ステータス。 */
  def status: Status = this match
    case NotFound(_)     => Status.NotFound
    case Forbidden(_)    => Status.Forbidden
    case Invalid(_)      => Status.BadRequest
    case Conflict(_)     => Status.Conflict
    case Unauthorized(_) => Status.Unauthorized
    case TooLarge(_)     => Status.PayloadTooLarge
    case Insufficient(_) => Status.InsufficientStorage

object Fail:
  def from(e: StoreError): Fail = e match
    case StoreError.NotFound(w) => Fail.NotFound(s"$w が見つかりません")
    case StoreError.Invalid(m)  => Fail.Invalid(m)
    case StoreError.Conflict(m) => Fail.Conflict(m)

type Res[A] = IO[Either[Fail, A]]

extension [A](io: IO[Either[StoreError, A]]) def orFail: Res[A] = io.map(_.left.map(Fail.from))

/** MCP のツールが REST API を内部で呼ぶときに付けるヘッダー。 */
val McpHeader = "X-Skawau-Mcp"

/** 認証: Cookie のセッション、`Authorization: Bearer <APIキー>`、WebDAV は Basic（パスワードに APIキー）。 */
final class SessionAuth(users: UserStore, cfg: ApiConfig):

  private def isAdmin(u: User, gs: List[Group]) = u.isAdmin || gs.exists(g => cfg.adminGroups.contains(g.name))

  private def byKey(token: String, dav: Boolean, mcp: Boolean = false): IO[Option[AuthSession]] =
    users.findKey(Crypto.sha256(token), Instant.now()).map(_.collect {
      // 外部のサービスとの連携で発行したキー（OAuth）は WebDAV には使えない
      case (u, gs, kid, scopes, oauth) if !(dav && oauth) =>
        AuthSession(u, gs, isAdmin(u, gs), AuthVia.ApiKey(kid, scopes.flatMap(Scope.parse).toSet, dav, mcp))
    })

  def fromRequest(req: Request[IO]): IO[Option[AuthSession]] =
    bearer(req) match
      case Some(token) =>
        // MCP のツールは REST API を内部で呼ぶ（その印のヘッダーで、変更履歴の経路を mcp にする）
        byKey(token, dav = false, mcp = req.pathInfo.renderString.startsWith("/mcp") || req.headers.get(org.typelevel.ci.CIString(McpHeader)).isDefined)
      case None =>
        req.cookies.find(_.name == cfg.cookieName).map(_.content) match
          case None        => IO.pure(None)
          case Some(token) => users.findSession(Crypto.sha256(token), Instant.now()).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs))))

  /** WebDAV: Basic（ユーザー名は何でもよく、パスワードに APIキー）または Bearer。Cookie は使わない。 */
  def fromDav(req: Request[IO]): IO[Option[AuthSession]] =
    req.headers.get[Authorization] match
      case Some(Authorization(BasicCredentials(_, pass))) if ApiKeys.looksLike(pass) => byKey(pass, dav = true)
      case _ => bearer(req).fold(IO.pure(None))(byKey(_, dav = true))

  private val publicOrigin = "^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+".r.findFirstIn(cfg.publicUrl).getOrElse(cfg.publicUrl).toLowerCase

  /**
   * この画面から送られた要求か（Cookie のセッションでの更新系の要求に使う CSRF の対策）。
   * SameSite=Lax の Cookie は同じサイトの別のサブドメインからの送信を防げないため、Sec-Fetch-Site（なければ Origin）で確かめる。
   */
  def sameOrigin(req: Request[IO]): Boolean =
    def header(n: String) = req.headers.get(org.typelevel.ci.CIString(n)).map(_.head.value)
    header("Sec-Fetch-Site") match
      case Some(v) => v == "same-origin" || v == "none"
      case None    => header("Origin").forall(_.toLowerCase == publicOrigin)

  def sessionFor(userId: UUID): IO[Option[AuthSession]] =
    users.withGroups(userId).map(_.map((u, gs) => AuthSession(u, gs, isAdmin(u, gs))))

  private def bearer(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t }.filter(ApiKeys.looksLike)

  def issue(user: User): IO[ResponseCookie] =
    val token = Crypto.randomToken()
    users.createSession(Crypto.sha256(token), user.id, Instant.now().plusSeconds(cfg.sessionTtl.toSeconds)).as(cookie(token, cfg.sessionTtl.toSeconds))

  def revoke(req: Request[IO]): IO[ResponseCookie] =
    req.cookies.find(_.name == cfg.cookieName) match
      case Some(c) => users.deleteSession(Crypto.sha256(c.content)).as(cookie("", 0))
      case None    => IO.pure(cookie("", 0))

  private def cookie(value: String, maxAge: Long): ResponseCookie =
    ResponseCookie(cfg.cookieName, value, maxAge = Some(maxAge), path = Some("/"), httpOnly = true, secure = cfg.cookieSecure,
      sameSite = Some(SameSite.Lax))

/** *Routes に共通の補助（エラー応答・認証・リクエスト本文・パラメータ）。 */
trait RouteSupport extends Http4sDsl[IO]:
  protected def sessions: SessionAuth

  protected def err(status: Status, msg: String): IO[Response[IO]] =
    Response[IO](status).withEntity(ErrorDto(msg)).pure[IO]

  protected def fail(f: Fail): IO[Response[IO]] = err(f.status, f.message)

  /** 結果が Right なら f、Left なら失敗の応答。 */
  protected def respond[A](r: Res[A])(f: A => IO[Response[IO]]): IO[Response[IO]] = r.flatMap(_.fold(fail, f))

  protected def okJson[A: io.circe.Encoder](r: Res[A]): IO[Response[IO]] = respond(r)(a => Ok(a))


  /** POST だが読み取りだけの API（本文で条件を送るもの）。 */
  private val readOnlyPosts = Set("/api/files/lookup", "/api/users/lookup")
  private def readOnlyPost(req: Request[IO]): Boolean = req.method == Method.POST && readOnlyPosts.contains(req.pathInfo.renderString)

  private def safeMethod(req: Request[IO]) = Set(Method.GET, Method.HEAD, Method.OPTIONS).contains(req.method)

  protected def sameOrigin(req: Request[IO]): Boolean = sessions.sameOrigin(req)

  /** ログインしていなければ 401。更新系の要求を read スコープのキーで送ったら 403。Cookie のセッションでほかのサイトから送られたら 403。 */
  protected def authed(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap {
      case None => err(Status.Unauthorized, "ログインしてください")
      case Some(s) if s.isSession && !safeMethod(req) && !sameOrigin(req) => err(Status.Forbidden, "ほかのサイトからの要求は受け付けません")
      case Some(s) if !s.canWrite && !safeMethod(req) && !readOnlyPost(req) =>
        err(Status.Forbidden, "このAPIキーは参照専用です（write スコープが必要です）")
      // 操作したユーザーを、トレース（スパンの属性）とこの中のログに載せる
      case Some(s) => skawau.observe.Telemetry.withUser(s.user.id.toString, s.user.name)(f(s))
    }

  /** システム管理者のみ（APIキーは admin スコープが必要）。 */
  protected def admin(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.canAdmin then f(s) else err(Status.Forbidden, "管理者だけが使えます"))

  /** Cookie のセッションのみ（APIキーの発行など）。 */
  protected def sessionOnly(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.isSession then f(s) else err(Status.Forbidden, "この操作は画面からのみ行えます"))

  protected def body[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]): IO[Response[IO]] =
    req.as[A].attempt.flatMap {
      case Left(e)  => err(Status.BadRequest, s"リクエストの内容が正しくありません: ${e.getMessage.take(200)}")
      case Right(b) => f(b)
    }

  protected object UuidVar:
    def unapply(s: String): Option[UUID] = uuidOpt(s)

  protected def param(req: Request[IO], k: String): Option[String] = req.params.get(k).map(_.trim).filter(_.nonEmpty)

  protected def intParam(req: Request[IO], k: String, default: Int, max: Int): Int =
    param(req, k).flatMap(_.toIntOption).map(v => math.max(1, math.min(v, max))).getOrElse(default)

  protected def boolParam(req: Request[IO], k: String): Boolean = param(req, k).exists(v => v == "1" || v.equalsIgnoreCase("true"))

  /** カンマ区切りの UUID（?ids=a,b,c）。 */
  protected def idsParam(req: Request[IO], k: String): List[UUID] =
    param(req, k).toList.flatMap(_.split(',').toList).flatMap(uuidOpt)

/** 日付の入力（yyyy-MM-dd）。 */
object Dates:
  def parse(s: Option[String], what: String): Either[Fail, Option[java.time.LocalDate]] =
    s.map(_.trim).filter(_.nonEmpty) match
      case None    => Right(None)
      case Some(v) => scala.util.Try(java.time.LocalDate.parse(v)).toOption.toRight(Fail.Invalid(s"$what は yyyy-MM-dd で指定してください")).map(Some(_))

/** URL のパスのデコード（WebDAV のパスなど）。 */
object UrlPath:
  def decode(s: String): String = java.net.URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8)
  def encodeSegment(s: String): String = java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
