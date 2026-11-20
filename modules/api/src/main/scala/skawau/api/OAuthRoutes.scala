package skawau.api

import skawau.core.ErrorCode
import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.Crypto
import sbirds.oauth.{OAuthCode, OAuthProvider}
import skawau.auth.ApiKeys
import skawau.core.AuthSession
import skawau.db.*
import skawau.shared.*

import java.util.UUID

/**
 * 外部のサービス（skea など）への認可（OAuth 2.0 の認可コード + PKCE。流れは sbirds.oauth.OAuthProvider）と、ファイル名の一括参照。
 * 取り決めは skea・skiwi の docs/skawau-integration.md。
 * 発行するトークンは、そのユーザーの API キー（「<クライアント名> 連携」。API キーの画面から失効できる）。スコープは求められたもの
 * （既定は read。write はそのクライアントに許したときだけ。skaka は録画を置くために write を求める）。
 */
final class OAuthRoutes(oauth: OAuthStore,
                        nodes: NodeStore,
                        activity: ActivityStore,
                        val sessions: SessionAuth,
                        cfg: ApiConfig)
  extends RouteSupport {

  private val host = new OAuthProvider.Host[AuthSession] {
    val settings = OAuthProvider.Settings(
      "skawau",
      cfg.cookieSecure,
      unreadableForm = "要求の内容が正しくありません。連携元の画面からやり直してください"
    )
    val codes = oauth

    def client(id: String): IO[Option[OAuthClient]] = IO.pure(cfg.oauthClients.find(_.id == id))

    /** 同意は画面のログイン（Cookie のセッション）だけで受け付ける。APIキーでの同意を許すと、連携元がスコープを無断で引き上げられる。 */
    def browserSession(req: Request[IO]): IO[Option[AuthSession]] = sessions.fromRequest(req).map(_.filter(_.isSession))

    def userId(s: AuthSession): UUID = s.user.id

    override def scopes(c: OAuthClient, requested: Option[String]): Option[List[String]] =
      OAuthProvider.requestedScopes(c.scopes, requested, List(ApiScope.Read))

    // ログインの要らない入口なので、フォームを読む前に本文の上限を掛ける
    override def limit(req: Request[IO]): Request[IO] = BodyLimit(req)

    def consent(req: Request[IO], c: OAuthClient, s: AuthSession, scopes: List[String]): IO[OAuthProvider.Consent] = {
      val name = OAuthProvider.escape(c.name)
      val write = scopes.contains(ApiScope.Write)
      val what = if write then "skawau のファイルを読み書き" else "skawau のファイル名を読み取り"
      val scope = if write then "<li>ファイル・フォルダの作成・アップロード・変更・共有ができます（WebDAV には使えません）</li>"
      else "<li>読み取りだけです（作成・変更・削除はしません）</li>"
      IO.pure(OAuthProvider.Consent(
        s"$name との連携",
        s"""<h1>$name との連携</h1>
           |<p><b>$name</b> が、あなた（${OAuthProvider.escape(s.user.name)}）の権限で${what}します。</p>
           |<ul class="muted">$scope<li>あなたが見られるファイル・フォルダ（自分のもの・共有されたもの）だけが対象です</li>
           |<li>個人設定の「アプリパスワード（APIキー）」から、いつでも連携をやめられます</li></ul>""".stripMargin
      ))
    }

    override def authorized(req: Request[IO], s: AuthSession, c: OAuthClient, scopes: List[String]): IO[Unit] =
      activity.audit(Some(s.user.id), "web", "oauth.authorize", Some(c.id), Json.obj())

    def issue(req: Request[IO], c: OAuthClient, oc: OAuthCode): IO[OAuthProvider.Token] = {
      val (key, prefix) = ApiKeys.generate()
      oauth.replaceKey(
        oc.userId,
        c.id,
        s"${c.name} 連携",
        prefix,
        Crypto.sha256(key),
        oc.scope.split(' ').toList.filter(c.scopes.contains)
      ) *>
        activity.audit(Some(oc.userId), "oauth", "api_key.create", Some(c.id), Json.obj("client" -> c.id.asJson))
          .as(OAuthProvider.Token(key, oc.scope))
    }

    def revoke(req: Request[IO], c: OAuthClient, token: String): IO[Unit] =
      oauth.revokeKey(Crypto.sha256(token), c.id)
  }

  val routes: HttpRoutes[IO] = new OAuthProvider(host).routes <+> HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "files" / "lookup" => postFilesLookup(req)
    case req @ POST -> Root / "api" / "users" / "lookup" => postUsersLookup(req)
  }

  private def postFilesLookup(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      body[Json](req) { j =>
        val ids = j.hcursor.get[List[String]]("ids").getOrElse(Nil).flatMap(skawau.core.uuidOpt).distinct
        if ids.size > 100 then err(ErrorCode.LookupIdsTooMany)
        else
          nodes.lookup(Viewer.of(s), ids).flatMap(rows =>
            // folder は skea、isDir・mimeType は skiwi の取り決め（同じ意味）
            Ok(Json.obj("files" -> rows.map((id, name, dir, ct) =>
              Json.obj(
                "id" -> id.asJson,
                "name" -> name.asJson,
                "folder" -> dir.asJson,
                "isDir" -> dir.asJson,
                "mimeType" -> ct.asJson
              )
            ).asJson))
          )
      }
    )

  private def postUsersLookup(req: Request[IO]): IO[Response[IO]] =
    authed(req)(_ =>
      body[Json](req) { j =>
        val emails = j.hcursor.get[List[String]]("emails").getOrElse(Nil).map(_.trim.toLowerCase).filter(_.contains(
          "@"
        )).distinct
        if emails.size > 100 then err(ErrorCode.LookupEmailsTooMany)
        else
          oauth.usersByEmail(emails).flatMap(rows =>
            Ok(Json.obj("users" -> rows.map((id, name, email) =>
              Json.obj("id" -> id.asJson, "name" -> name.asJson, "email" -> email.asJson)
            ).asJson))
          )
      }
    )
}
