package skawau.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import skawau.auth.Jwt
import skawau.core.*
import skawau.db.*
import skawau.observe.{Log, Metrics, Telemetry}
import skawau.shared.*

import java.time.Instant
import java.util.UUID

/**
 * ONLYOFFICE（Document Server）との連携。
 *
 *  1. 画面が `GET /api/files/{id}/office`（公開リンクは `/api/public/{token}/office/{id}`）でエディタの設定を受け取り、
 *     Document Server の api.js の `DocsAPI.DocEditor` に渡す。設定は ONLYOFFICE_JWT_SECRET で署名する（`token`）。
 *  2. Document Server は設定の `document.url`（`/onlyoffice/download/{id}?t=…`）から文書を取る。`t` はこのサーバーが署名した期限付きの値。
 *  3. 編集が終わる（全員が閉じる）か保存ボタン（強制保存）で、Document Server が `editorConfig.callbackUrl`
 *     （`/onlyoffice/callback/{id}?t=…`）に知らせる。status 2・6 のときは `url` から新しい文書を取り、新しい版として保存する。
 *
 * 文書キー（document.key）は「ノードID_版」。保存のたびに版が進むので、次に開くと新しい文書として扱われる。
 */
final class OfficeService(files: FileService, http: Client[IO], cfg: ApiConfig):

  def enabled: Boolean = cfg.office.isDefined

  def info: Option[OfficeInfoDto] =
    cfg.office.map(o => OfficeInfoDto(o.url, FileKind.officeEdit.keys.toList.sorted, FileKind.officeView.keys.toList.sorted))

  private def appUrl(o: OfficeConfig) = o.appUrl.stripSuffix("/")

  /** URL に付ける署名（このサーバーの APP_SECRET で署名する。Document Server の秘密とは別）。 */
  private def urlToken(fields: (String, Json)*): String = Jwt.sign(Json.obj(fields*), cfg.appSecret)

  /**
   * エディタの設定。user は (ID, 名前)。canEdit は編集を許すか（形式が編集できなければ閲覧になる）。
   * actor は保存したときに記録する人（公開リンクは None で、link にリンクの共有 ID）。
   * 保存の知らせの URL には、編集を許したか・誰の権限か・期限を入れ、保存するときに権限がまだあるかを確かめ直す。
   */
  def config(n: Node, user: (String, String), canEdit0: Boolean, actor: Option[UUID], back: Option[String],
      link: Option[UUID] = None): Res[OfficeConfigDto] =
    (cfg.office, FileKind.officeType(n.name)) match
      case (None, _) => IO.pure(Left(Fail.Invalid("ONLYOFFICE が設定されていません（ONLYOFFICE_URL）")))
      case (_, None) => IO.pure(Left(Fail.Invalid("ONLYOFFICE で開けない形式です")))
      case (Some(o), Some(docType)) =>
        val canEdit = canEdit0 && FileKind.officeEditable(n.name)
        val exp     = Instant.now().plusSeconds(6 * 3600).getEpochSecond
        val dl      = s"${appUrl(o)}/onlyoffice/download/${n.id}?t=${urlToken("fid" -> n.id.asJson, "exp" -> exp.asJson)}"
        val cbExp   = Instant.now().plusSeconds(7 * 86400).getEpochSecond
        val cb      = s"${appUrl(o)}/onlyoffice/callback/${n.id}?t=${urlToken("fid" -> n.id.asJson, "actor" -> actor.asJson, "sid" -> link.asJson,
                        "edit" -> canEdit.asJson, "exp" -> cbExp.asJson)}"
        val body = Json.obj(
          "document" -> Json.obj(
            "fileType" -> FileKind.ext(n.name).asJson,
            "key"      -> s"${n.id}_${n.version}".asJson,
            "title"    -> n.name.asJson,
            "url"      -> dl.asJson,
            "permissions" -> Json.obj(
              "edit"     -> canEdit.asJson,
              "download" -> true.asJson,
              "print"    -> true.asJson,
              "comment"  -> canEdit.asJson,
              "review"   -> canEdit.asJson,
              "copy"     -> true.asJson
            )
          ),
          "documentType" -> docType.asJson,
          "editorConfig" -> Json.obj(
            "callbackUrl" -> cb.asJson,
            "mode"        -> (if canEdit then "edit" else "view").asJson,
            "lang"        -> "ja".asJson,
            "region"      -> "ja-JP".asJson,
            "user"        -> Json.obj("id" -> user._1.asJson, "name" -> user._2.asJson),
            "customization" -> Json.obj(
              "forcesave" -> true.asJson,
              "compactHeader" -> true.asJson,
              "goback" -> back.fold(Json.Null)(b => Json.obj("url" -> s"${cfg.publicUrl}$b".asJson, "text" -> "フォルダに戻る".asJson))
            ).dropNullValues
          )
        )
        val signed = body.deepMerge(Json.obj("token" -> Jwt.sign(body, o.secret).asJson))
        // エディタを開いた数（編集・閲覧、ログインした人・公開リンク）
        Metrics.count("skawau.office.opened", "mode" -> (if canEdit then "edit" else "view"), "via" -> (if link.isDefined then "link" else "user"))
          .as(Right(OfficeConfigDto(o.url, signed, n.name, canEdit, n.parentId)))

  // ---------------------------------------------------------------- Document Server からの呼び出し

  /** URL の署名を確かめ、対象のノード ID と一致するか。 */
  def checkUrl(t: Option[String], id: UUID): Either[String, Json] =
    t.toRight("t がありません").flatMap(Jwt.verify(_, cfg.appSecret)).flatMap(j =>
      Either.cond(j.hcursor.get[UUID]("fid").contains(id), j, "対象が一致しません"))

  /**
   * 知らせの本文（Document Server の JWT を確かめる）。本文の `token`、または Authorization ヘッダー
   * （ペイロードの `payload` に本文）のどちらでも受け付ける。
   */
  def callbackBody(req: Request[IO], raw: Json): Either[String, Json] =
    cfg.office.toRight("ONLYOFFICE が設定されていません").flatMap { o =>
      raw.hcursor.get[String]("token").toOption match
        case Some(t) => Jwt.verify(t, o.secret)
        case None =>
          req.headers.get[Authorization].collect { case Authorization(Credentials.Token(_, t)) => t }
            .toRight("署名がありません").flatMap(Jwt.verify(_, o.secret)).map(j => j.hcursor.downField("payload").focus.getOrElse(j))
    }

  /** Document Server の公開の URL を、このサーバーから取りに行ける URL に置き換える。 */
  private def internal(url: String): String = cfg.office.fold(url) { o =>
    val pub = o.url.stripSuffix("/")
    val int = o.internalUrl.stripSuffix("/")
    if pub != int && url.startsWith(pub) then int + url.drop(pub.length) else url
  }

  /** 取りに行ってよい URL か（Document Server のものだけ。ほかのホスト・内部のアドレスへの要求に使わせない）。 */
  private def fromDocumentServer(uri: Uri): Boolean = cfg.office.exists { o =>
    def origin(u: Uri) =
      val scheme = u.scheme.map(_.value.toString.toLowerCase)
      (scheme, u.host.map(_.value.toLowerCase), u.port.orElse(scheme.map(s => if s == "https" then 443 else 80)))
    Uri.fromString(o.internalUrl).toOption.exists(i => origin(i) == origin(uri))
  }

  /** 編集した文書を取り込む（新しい版）。 */
  def save(n: Node, url: String, actor: Actor): Res[Node] =
    Telemetry.span("office.save", "node.id" -> n.id.toString)(Uri.fromString(internal(url)).left.map(e => Fail.Invalid(e.message)).filterOrElse(fromDocumentServer, Fail.Invalid("Document Server 以外の URL です")) match
      case Left(f) => IO.pure(Left(f))
      case Right(uri) =>
        http.run(Request[IO](Method.GET, uri)).use { resp =>
          if !resp.status.isSuccess then IO.pure(Left(Fail.Invalid(s"Document Server から文書を取れませんでした（${resp.status.code}）")))
          else files.receive(resp.body)(tmp => files.writeTo(actor, n, tmp, EventKind.Updated))
        })

final class OfficeRoutes(office: OfficeService, files: FileService, nodes: NodeStore, shares: ShareStore, val sessions: SessionAuth)
    extends RouteSupport:

  /** 保存してよいか（編集を許した設定で、その人・そのリンクにいまも編集の権限があるか。共有を外したあとは保存させない）。 */
  private def mayStillEdit(t: Json, n: Node): IO[Boolean] =
    val c = t.hcursor
    if !c.get[Boolean]("edit").contains(true) then IO.pure(false)
    else (c.get[Option[UUID]]("actor").toOption.flatten, c.get[Option[UUID]]("sid").toOption.flatten) match
      case (Some(uid), _) => sessions.sessionFor(uid).flatMap {
        case Some(s) if !s.user.disabled => nodes.access(Viewer.of(s), n.id).map(_.exists(_._2.canEdit))
        case _                           => IO.pure(false)
      }
      case (None, Some(sid)) => shares.find(sid).map(_.exists(sh =>
        sh.kind == "link" && !sh.expired(java.time.LocalDate.now()) && sh.permission == "edit" && (sh.nodeId == n.id || n.ancestors.contains(sh.nodeId))))
      case _ => IO.pure(false)

  /** Document Server への応答（error が 0 なら成功）。 */
  private def dsReply(error: Int, message: String = ""): IO[Response[IO]] =
    Ok(Json.obj("error" -> error.asJson).deepMerge(if message.isEmpty then Json.obj() else Json.obj("message" -> message.asJson)))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    /** ログインしている人のエディタの設定。 */
    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "office" =>
      authed(req)(s => respond(files.node(Viewer.of(s), id, Access.View)) { (n, a) =>
        okJson(office.config(n, (s.userId.toString, s.user.name), a.canEdit && s.canWrite, Some(s.userId), n.parentId.map(p => s"/files/$p")))
      })

    /** Document Server が文書を取りに来る。 */
    case req @ GET -> Root / "onlyoffice" / "download" / UuidVar(id) =>
      office.checkUrl(param(req, "t"), id) match
        case Left(e) => err(Status.Forbidden, e)
        case Right(_) => nodes.find(id).flatMap {
          case Some(n) if !n.isDir => Serve.node(req, files, n, download = true, "office")
          case _                   => err(Status.NotFound, "ファイルが見つかりません")
        }

    /** Document Server からの知らせ（保存など）。応答は {"error":0}。 */
    case req @ POST -> Root / "onlyoffice" / "callback" / UuidVar(id) =>
      office.checkUrl(param(req, "t"), id) match
        case Left(e) => dsReply(1, e).map(_.withStatus(Status.Forbidden))
        case Right(t) =>
          req.as[Json].attempt.flatMap {
            case Left(_) => dsReply(1).map(_.withStatus(Status.BadRequest))
            case Right(raw) =>
              office.callbackBody(req, raw) match
                case Left(e) => dsReply(1, e).map(_.withStatus(Status.Forbidden))
                case Right(b) =>
                  val c      = b.hcursor
                  val status = c.get[Int]("status").getOrElse(0)
                  val actor  = Actor(t.hcursor.get[Option[UUID]]("actor").toOption.flatten, "office")
                  // 2: 全員が閉じた（保存する）、6: 強制保存。それ以外（1: 編集中、4: 変更なしで閉じた など）は何もしない
                  (status, c.get[String]("url").toOption) match
                    case (2 | 6, Some(url)) =>
                      nodes.find(id).flatMap {
                        case None => dsReply(1, "file not found")
                        case Some(n) =>
                          val trigger = if status == 6 then "forcesave" else "closed"
                          mayStillEdit(t, n).ifM(office.save(n, url, actor), IO.pure(Left(Fail.Forbidden("編集の権限がありません")))).flatMap {
                            case Right(_) => Metrics.count("skawau.office.saved", "result" -> "ok", "trigger" -> trigger) *> dsReply(0)
                            case Left(f)  => Metrics.count("skawau.office.saved", "result" -> "error", "trigger" -> trigger) *>
                                               Log.println(s"[skawau] onlyoffice save failed: ${f.message}") *> dsReply(1, f.message)
                          }
                      }
                    case (2 | 6, None) => dsReply(1, "url がありません")
                    case _             => dsReply(0)
          }
  }
