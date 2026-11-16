package skawau.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skawau.auth.{Crypto, Jwt, Passwords}
import skawau.core.*
import skawau.db.*
import skawau.shared.*

import java.time.{Instant, LocalDate}
import java.util.UUID

/**
 * 公開リンク（`/s/{token}` の画面が使う API）。パスワードつきのリンクは、正しいパスワードを送ると
 * そのリンクのパスだけに効く Cookie（署名つき、24時間）を渡す。
 * 権限: view（閲覧・ダウンロード）/ edit（加えてアップロード・フォルダの作成・Office の編集）/ upload（アップロードだけ。中身は見せない）。
 */
final class PublicRoutes(files: FileService, nodes: NodeStore, shares: ShareStore, activity: ActivityStore, office: OfficeService,
    val sessions: SessionAuth, cfg: ApiConfig) extends RouteSupport:

  private def cookieName(token: String) = "skawau_link_" + Crypto.hex(Crypto.sha256(token)).take(12)
  private def cookiePath(token: String) = s"/api/public/$token"

  /** Cookie に入れるパスワードの印（パスワードを変えたら、前のパスワードで得た Cookie は使えなくなる）。 */
  private def passwordMark(hash: String): String = Crypto.hex(Crypto.sha256(hash)).take(16)

  private def unlocked(req: Request[IO], sh: ShareRow): Boolean =
    sh.passwordHash.forall(h => req.cookies.find(_.name == cookieName(sh.token.get)).exists(c =>
      Jwt.verify(c.content, cfg.appSecret).toOption.exists(j =>
        j.hcursor.get[UUID]("sid").contains(sh.id) && j.hcursor.get[String]("ph").contains(passwordMark(h)))))

  // パスワードの総当たりを防ぐ（ノードごとのメモリで数える）。リンクごとに15分で10回まで間違えられ、
  // ハッシュ（PBKDF2）の計算は同時に4つまで（大量に送りつけて CPU を使い切らせないように）
  private val MaxFailures = 10
  private val FailureWindow = 15L * 60
  private val failures = new java.util.concurrent.ConcurrentHashMap[UUID, (Int, Instant)]()
  private val hashing  = new java.util.concurrent.Semaphore(4)

  private def lockedOut(id: UUID, now: Instant): Boolean =
    Option(failures.get(id)).exists((n, at) => n >= MaxFailures && at.plusSeconds(FailureWindow).isAfter(now))

  private def recordFailure(id: UUID, now: Instant): IO[Unit] = IO {
    if failures.size > 10000 then failures.entrySet.removeIf(e => e.getValue._2.plusSeconds(FailureWindow).isBefore(now))
    failures.compute(id, (_, old) => Option(old) match
      case Some((n, at)) if at.plusSeconds(FailureWindow).isAfter(now) => (n + 1, at)
      case _                                                          => (1, now))
    ()
  }

  /** パスワードを確かめる（混み合っていて計算できなければ None）。 */
  private def verifyPassword(password: String, hash: String): IO[Option[Boolean]] = IO.blocking {
    if hashing.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS) then
      try Some(Passwords.verify(password, hash)) finally hashing.release()
    else None
  }

  /** 有効なリンクと起点のノード（期限切れ・ごみ箱は 404）。 */
  private def link(token: String): IO[Option[(ShareRow, Node)]] =
    shares.byToken(token).flatMap {
      case Some(sh) if !sh.expired(LocalDate.now()) => nodes.find(sh.nodeId).map(_.map(sh -> _))
      case _                                        => IO.pure(None)
    }

  /** パスワードを確かめたうえで、リンクの範囲にあるノード（id が None なら起点）。 */
  private def withLink(req: Request[IO], token: String, id: Option[UUID])(f: (ShareRow, Node, Node) => IO[Response[IO]]): IO[Response[IO]] =
    link(token).flatMap {
      case None => err(Status.NotFound, "リンクが見つからないか、期限が切れています")
      case Some((sh, _)) if !unlocked(req, sh) => err(Status.Unauthorized, "パスワードを入力してください")
      case Some((sh, root)) =>
        id.filterNot(_ == root.id) match
          case None => f(sh, root, root)
          case Some(i) => nodes.find(i).flatMap {
            case Some(n) if n.ancestors.contains(root.id) => f(sh, root, n)
            case _                                         => err(Status.NotFound, "ファイルが見つかりません")
          }
    }

  private def grant(sh: ShareRow) = LinkGrant(sh.id, sh.nodeId, sh.ownerId, sh.permission)

  /** リンクで見せる形にする（所有者の情報は名前だけ、権限はリンクの権限）。 */
  private def asPublic(sh: ShareRow)(d: NodeDto): NodeDto =
    d.copy(perm = sh.permission, favorite = false, shared = false, hasLink = false, modifiedBy = None)

  private def ownerViewer(sh: ShareRow) = Viewer(sh.ownerId, Nil)

  /** リンクで見せるノードの応答。 */
  private def publicDto(sh: ShareRow, n: Node): IO[Response[IO]] = nodes.dto(ownerViewer(sh), n.id).flatMap(d => Ok(asPublic(sh)(d.get)))

  private val actor = Actor(None, "link")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "public" / token =>
      link(token).flatMap {
        case None => err(Status.NotFound, "リンクが見つからないか、期限が切れています")
        case Some((sh, n)) =>
          nodes.dto(ownerViewer(sh), n.id).flatMap(d =>
            Ok(PublicInfoDto(token, n.name, n.isDir, n.size, n.contentType, sh.permission, d.fold("")(_.owner.name), sh.expiresAt.map(_.toString),
              !unlocked(req, sh), n.id, sh.label)))
      }

    case req @ POST -> Root / "api" / "public" / token / "auth" =>
      body[PasswordReq](req)(r => link(token).flatMap {
        case None => err(Status.NotFound, "リンクが見つかりません")
        case Some((sh, _)) =>
          val now = Instant.now()
          if lockedOut(sh.id, now) then err(Status.TooManyRequests, "パスワードを続けて間違えたため、しばらく入力できません")
          else sh.passwordHash.fold(IO.pure(Option(false)))(h => verifyPassword(r.password, h)).flatMap {
            case None => err(Status.ServiceUnavailable, "混み合っています。しばらくしてからやり直してください")
            case Some(true) =>
              val claims = Json.obj("sid" -> sh.id.asJson, "ph" -> sh.passwordHash.map(passwordMark).asJson,
                "exp" -> now.plusSeconds(86400).getEpochSecond.asJson)
              val c = ResponseCookie(cookieName(token), Jwt.sign(claims, cfg.appSecret),
                maxAge = Some(86400L), path = Some(cookiePath(token)), httpOnly = true, secure = cfg.cookieSecure, sameSite = Some(SameSite.Lax))
              NoContent().map(_.addCookie(c))
            case Some(false) =>
              recordFailure(sh.id, now) *> IO.sleep(scala.concurrent.duration.DurationInt(1).second) *> err(Status.Forbidden, "パスワードが違います")
          }
      })

    /** フォルダの中身（upload のリンクは中身を見せない）。 */
    case req @ GET -> Root / "api" / "public" / token / "folder" =>
      withLink(req, token, param(req, "id").flatMap(uuidOpt)) { (sh, root, n) =>
        if !n.isDir then err(Status.BadRequest, "フォルダではありません")
        else
          for
            d     <- nodes.dto(ownerViewer(sh), n.id).map(_.get)
            items <- if grant(sh).canRead then nodes.children(ownerViewer(sh), n.id) else IO.pure(Nil)
            names <- nodes.crumbs(ownerViewer(sh), List(n)).map(_.get(n.id).map(_._1).getOrElse(Nil))
            path   = names.dropWhile(_.id != root.id)
            res   <- Ok(FolderDto(asPublic(sh)(d), path, items.map(asPublic(sh)), inShare = true))
          yield res
      }

    case req @ GET -> Root / "api" / "public" / token / "content" =>
      withLink(req, token, param(req, "id").flatMap(uuidOpt)) { (sh, _, n) =>
        if !grant(sh).canRead then err(Status.Forbidden, "このリンクではダウンロードできません")
        else if n.isDir then Serve.zip(files, List(n), n.name)
        else
          val dl = boolParam(req, "download")
          nodes.event(n, actor, EventKind.LinkDownloaded).whenA(dl) *> Serve.node(req, files, n, dl)
      }

    case req @ GET -> Root / "api" / "public" / token / "zip" =>
      withLink(req, token, None) { (sh, root, _) =>
        if !grant(sh).canRead then err(Status.Forbidden, "このリンクではダウンロードできません")
        else
          idsParam(req, "ids").traverse(nodes.find).map(_.flatten.filter(n => n.id == root.id || n.ancestors.contains(root.id))).flatMap { ns =>
            val items = if ns.isEmpty then List(root) else ns
            Serve.zip(files, items, root.name)
          }
      }

    /** アップロード（edit・upload のリンク。同じ名前は「名前 (2)」にする）。 */
    case req @ PUT -> Root / "api" / "public" / token / "upload" =>
      withLink(req, token, param(req, "dir").flatMap(uuidOpt)) { (sh, _, dir) =>
        (param(req, "name"), grant(sh).canUpload && dir.isDir) match
          case (_, false) => err(Status.Forbidden, "このリンクではアップロードできません")
          case (None, _)  => err(Status.BadRequest, "name を指定してください")
          case (Some(name), _) =>
            val rel = if grant(sh).canEdit then param(req, "path").map(Names.segments).getOrElse(Nil) else Nil
            respond(files.receive(req.body)(tmp => files.upload(actor, ownerViewer(sh), dir.id, rel, name, tmp, OnConflict.Rename))) { n =>
              activity.notify(List(sh.ownerId), NotificationKind.Uploaded, s"公開リンクから「${n.name}」がアップロードされました",
                sh.label.getOrElse(""), Some(s"/files/${n.parentId.getOrElse(dir.id)}"), None) *>
                publicDto(sh, n)
            }
      }

    case req @ POST -> Root / "api" / "public" / token / "folders" =>
      withLink(req, token, param(req, "dir").flatMap(uuidOpt)) { (sh, _, dir) =>
        if !grant(sh).canEdit then err(Status.Forbidden, "このリンクではフォルダを作れません")
        else body[NameReq](req)(r => respond(files.createFolder(actor, ownerViewer(sh), dir.id, r.name))(n =>
          publicDto(sh, n)))
      }

    case req @ GET -> Root / "api" / "public" / token / "office" =>
      withLink(req, token, param(req, "id").flatMap(uuidOpt)) { (sh, _, n) =>
        if !grant(sh).canRead then err(Status.Forbidden, "このリンクでは開けません")
        else okJson(office.config(n, (s"link-${Crypto.randomToken(6)}", "ゲスト"), grant(sh).canEdit, None, Some(s"/s/$token"), Some(sh.id)))
      }
  }
