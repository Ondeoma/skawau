package skawau.api

import sbirds.shared.AppError
import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skawau.core.*
import skawau.db.*
import sbirds.observe.{Log, Metrics}
import skawau.shared.*

import java.util.UUID

final class OfficeRoutes(office: OfficeService,
                         files: FileService,
                         nodes: NodeStore,
                         shares: ShareStore,
                         val sessions: SessionAuth)
  extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    /** ログインしている人のエディタの設定。 */
    case req @ GET -> Root / "api" / "files" / UuidVar(id) / "office" => getFileOffice(req, id)
    case req @ GET -> Root / "onlyoffice" / "download" / UuidVar(id) => getOnlyofficeDownload(req, id)
    case req @ POST -> Root / "onlyoffice" / "callback" / UuidVar(id) => postOnlyofficeCallback(req, id)
  }

  private def getFileOffice(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req)(s =>
      respond(files.node(Viewer.of(s), id, Access.View)) {
        (n, a) =>
          okJson(office.config(
            n,
            (s.userId.toString, s.user.name),
            a.canEdit && s.canWrite,
            Some(s.userId),
            n.parentId.map(p => s"/files/$p")
          ))
      }
    )

  private def getOnlyofficeDownload(req: Request[IO], id: UUID): IO[Response[IO]] =
    office.checkUrl(param(req, "t"), id) match {
      case Left(e) => err(ErrorCode.OfficeTokenInvalid, e)
      // 保存の知らせ用の URL の署名（期限が長く、閲覧だけの人にも渡る）では文書を取らせない
      case Right(t) if t.hcursor.downField("edit").succeeded => err(ErrorCode.OfficeTokenInvalid, "用途が違います")
      case Right(_) => nodes.find(id).flatMap {
          case Some(n) if !n.isDir => Serve.node(req, files, n, download = true, "office")
          case _ => err(ErrorCode.FileNotFound)
        }
    }

  private def postOnlyofficeCallback(req: Request[IO], id: UUID): IO[Response[IO]] =
    office.checkUrl(param(req, "t"), id) match {
      case Left(e) => dsReply(1, e).map(_.withStatus(Status.Forbidden))
      case Right(t) =>
        BodyLimit(req).as[Json].attempt.flatMap {
          case Left(_) => dsReply(1).map(_.withStatus(Status.BadRequest))
          case Right(raw) =>
            office.callbackBody(req, raw) match {
              case Left(e) => dsReply(1, e).map(_.withStatus(Status.Forbidden))
              case Right(b) =>
                val c = b.hcursor
                val status = c.get[Int]("status").getOrElse(0)
                val actor = Actor(t.hcursor.get[Option[UUID]]("actor").toOption.flatten, "office")
                // 2: 全員が閉じた（保存する）、6: 強制保存。それ以外（1: 編集中、4: 変更なしで閉じた など）は何もしない
                (status, c.get[String]("url").toOption) match {
                  case (2 | 6, Some(url)) =>
                    nodes.find(id).flatMap {
                      case None => dsReply(1, "file not found")
                      case Some(n) =>
                        val trigger = if status == 6 then "forcesave" else "closed"
                        mayStillEdit(
                          t,
                          n
                        ).ifM(
                          office.save(n, url, actor),
                          IO.pure(Left(AppError(ErrorCode.EditPermissionRequired)))
                        ).flatMap {
                          case Right(_) =>
                            Metrics.count("skawau.office.saved", "result" -> "ok", "trigger" -> trigger) *> dsReply(0)
                          case Left(f) =>
                            Metrics.count("skawau.office.saved", "result" -> "error", "trigger" -> trigger) *>
                              Log.report(ErrorCode.OfficeSaveFailed, f.message) *> dsReply(1, f.message)
                        }
                    }
                  case (2 | 6, None) => dsReply(1, "url がありません")
                  case _ => dsReply(0)
                }
            }
        }
    }

  /** 保存してよいか（編集を許した設定で、その人・そのリンクにいまも編集の権限があるか。共有を外したあとは保存させない）。 */
  private def mayStillEdit(t: Json, n: Node): IO[Boolean] = {
    val c = t.hcursor
    if !c.get[Boolean]("edit").contains(true) then IO.pure(false)
    else
      (c.get[Option[UUID]]("actor").toOption.flatten, c.get[Option[UUID]]("sid").toOption.flatten) match {
        case (Some(uid), _) => sessions.sessionFor(uid).flatMap {
            case Some(s) if !s.user.disabled => nodes.access(Viewer.of(s), n.id).map(_.exists(_._2.canEdit))
            case _ => IO.pure(false)
          }
        case (None, Some(sid)) => shares.find(sid).map(_.exists(sh =>
            sh.kind == "link" && !sh.expired(java.time.LocalDate.now()) && sh.permission == "edit" &&
              (sh.nodeId == n.id || n.ancestors.contains(sh.nodeId))
          ))
        case _ => IO.pure(false)
      }
  }

  /** Document Server への応答（error が 0 なら成功）。 */
  private def dsReply(error: Int, message: String = ""): IO[Response[IO]] =
    Ok(Json.obj("error" -> error.asJson).deepMerge(if message.isEmpty then Json.obj()
    else Json.obj("message" -> message.asJson)))

}
