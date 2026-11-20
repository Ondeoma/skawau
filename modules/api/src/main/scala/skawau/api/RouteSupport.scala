package skawau.api

import cats.effect.*
import io.circe.Decoder
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skawau.core.*
import sbirds.http.{Errors, RouteHelpers}
import sbirds.shared.AppError

import java.util.UUID

/**
 * *Routes に共通の補助（エラー応答・認証・リクエスト本文・パラメータ）。クエリ・パスの UUID・302 は sbirds.http.RouteHelpers。
 */
trait RouteSupport extends RouteHelpers {

  protected def bodyInvalid: sbirds.shared.ErrorCode = ErrorCode.RequestBodyInvalid
  protected def sessions: SessionAuth

  /** エラーの応答（コード付きでログに出る）. detail を省くとコードの既定の文言. */
  protected def err(code: ErrorCode, detail: String | Null = null): IO[Response[IO]] =
    Errors.respond(AppError(code, Option(detail)))

  protected def fail(e: AppError): IO[Response[IO]] = Errors.respond(e)

  /** 結果が Right なら f、Left なら失敗の応答。 */
  protected def respond[A](r: Res[A])(f: A => IO[Response[IO]]): IO[Response[IO]] = r.flatMap(_.fold(fail, f))

  protected def okJson[A: io.circe.Encoder](r: Res[A]): IO[Response[IO]] = respond(r)(a => Ok(a))

  /** POST だが読み取りだけの API（本文で条件を送るもの）。 */
  private val readOnlyPosts = Set("/api/files/lookup", "/api/users/lookup")
  private def readOnlyPost(req: Request[IO]): Boolean = req.method == Method.POST &&
    readOnlyPosts.contains(req.pathInfo.renderString)

  private def safeMethod(req: Request[IO]) = Set(Method.GET, Method.HEAD, Method.OPTIONS).contains(req.method)

  protected def sameOrigin(req: Request[IO]): Boolean = sessions.sameOrigin(req)

  /** ログインしていなければ 401。更新系の要求を read スコープのキーで送ったら 403。Cookie のセッションでほかのサイトから送られたら 403。 */
  protected def authed(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    sessions.fromRequest(req).flatMap {
      case None => err(ErrorCode.NotLoggedIn)
      case Some(s) if s.isSession && !safeMethod(req) && !sameOrigin(req) =>
        err(ErrorCode.CrossSiteRequest)
      case Some(s) if !s.canWrite && !safeMethod(req) && !readOnlyPost(req) =>
        err(ErrorCode.ApiKeyNeedsWrite)
      // 操作したユーザーを、トレース（スパンの属性）とこの中のログに載せる
      case Some(s) => sbirds.observe.Telemetry.withUser(s.user.id.toString, s.user.name)(f(s))
    }

  /** システム管理者のみ（APIキーは admin スコープが必要）。 */
  protected def admin(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.canAdmin then f(s) else err(ErrorCode.AdminOnly))

  /** Cookie のセッションのみ（APIキーの発行など）。 */
  protected def sessionOnly(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req)(s => if s.isSession then f(s) else err(ErrorCode.SessionOnly))

  // 本文の上限を掛けてから読む（超えたら 413）ので、RouteHelpers のものは使わない
  override protected def body[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]): IO[Response[IO]] =
    BodyLimit(req).as[A].attempt.flatMap {
      case Left(_: BodyLimit.TooLarge) => err(ErrorCode.RequestTooLarge)
      case Left(e) => err(ErrorCode.RequestBodyInvalid, s"リクエストの内容が正しくありません: ${e.getMessage.take(200)}")
      case Right(b) => f(b)
    }

  protected def boolParam(req: Request[IO], k: String): Boolean =
    param(req, k).exists(v => v == "1" || v.equalsIgnoreCase("true"))

  /** カンマ区切りの UUID（?ids=a,b,c）。 */
  protected def idsParam(req: Request[IO], k: String): List[UUID] =
    param(req, k).toList.flatMap(_.split(',').toList).flatMap(uuidOpt)
}
