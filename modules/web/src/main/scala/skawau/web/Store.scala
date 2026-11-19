package skawau.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom
import skawau.shared.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success}

final case class Toast(
  id: Int,
  text: String,
  link: Option[String],
  error: Boolean)

/** プレビューで開いているファイル（同じフォルダの中で前後に送れる）. token は公開リンクで開いたとき. */
final case class PreviewReq(
  items: List[NodeDto],
  index: Int,
  token: Option[String] = None) {
  def current: NodeDto = items(index)
}

/** 移動・コピーのダイアログ. */
final case class MoveReqUi(
  items: List[NodeDto],
  copy: Boolean,
  from: Option[UUID])

/** 画面全体で共有する状態と、変更の自動同期（SSE で知らせを受けて表示を取り直す. つながらないときは15秒ごと）. */
object Store {

  val me: RV[Option[MeDto]] = RV(None)
  val config: RV[Option[ClientConfigDto]] = RV(None)
  val unread: RV[UnreadDto] = RV(UnreadDto(0))
  val toasts: RV[List[Toast]] = RV(Nil)

  /** ファイルに変更があるたびに増える. 各画面はこれを監視して表示を取り直す. */
  val tick: RV[Int] = RV(0)

  /** 画面の上に重ねるダイアログ（App.st が描く）. */
  val shareFor: RV[Option[NodeDto]] = RV(None)
  val moveReq: RV[Option[MoveReqUi]] = RV(None)
  val preview: RV[Option[PreviewReq]] = RV(None)

  /** 左のメニュー（狭い画面で開閉する）. */
  val sideOpen: RV[Boolean] = RV(false)

  private var toastSeq = 0

  def myId: Option[UUID] = me.v.map(_.id)
  def isAdmin = me.mapC(_.exists(_.isAdmin))
  def office: Option[OfficeInfoDto] = config.v.flatMap(_.office)

  // ---------------------------------------------------------------- トースト

  def toast(
    text: String,
    link: Option[String] = None,
    error: Boolean = false
  ): Unit = {
    toastSeq += 1
    val t = Toast(toastSeq, text, link, error)
    toasts := (toasts.v :+ t).takeRight(5)
    dom.window.setTimeout(() => dismiss(t.id), if (error) 9000 else 5000)
  }

  def dismiss(
    id: Int
  ): Unit = toasts := toasts.v.filterNot(_.id == id)

  def error(
    e: Throwable
  ): Unit = e match {
    case ApiError(401, _) => ()
    case other => toast(other.getMessage, error = true)
  }

  /** Future の失敗をトーストで知らせる. */
  def report[A](
    f: Future[A]
  )(
    ok: A => Unit
  ): Unit = f.onComplete {
    case Success(a) => ok(a)
    case Failure(e) => error(e)
  }

  /** 自分の情報（容量の使用量を含む）を読み直す. */
  def reloadMe(): Unit = Api.me().foreach(m => me := Some(m))

  // ---------------------------------------------------------------- 自動同期（SSE）

  private var stream: Option[dom.EventSource] = None
  private var pending: Option[Int] = None
  private var lastSync = 0.0

  /** 知らせが続けて届いてもまとめて1回だけ取り直す. */
  private def soon(): Unit =
    if (pending.isEmpty) pending = Some(dom.window.setTimeout(() => { pending = None; refresh() }, 300))

  private def live: Boolean = stream.exists(_.readyState == dom.EventSource.OPEN)

  /** 表示中の一覧・通知の件数・容量を取り直す. */
  def refresh(): Unit = if (me.v.isDefined) {
    lastSync = js.Date.now()
    tick := tick.v + 1
    Api.unread().foreach(u => if (u != unread.v) unread := u)
    reloadMe()
  }

  /** 自分の操作の直後. */
  def changed(): Unit = soon()

  def connect(): Unit = if (me.v.isDefined && stream.isEmpty) {
    val es = new dom.EventSource("/api/stream")
    es.addEventListener("change", (_: dom.MessageEvent) => soon())
    es.addEventListener("notify", (_: dom.MessageEvent) => soon())
    stream = Some(es)
  }

  def startSync(): Unit = {
    // SSE でつながっている間は、念のため60秒ごとにだけ確認する
    dom.window.setInterval(
      () =>
        if (dom.document.visibilityState.toString == "visible" && (!live || js.Date.now() - lastSync > 60000))
          refresh(),
      15000
    )
    dom.document.addEventListener(
      "visibilitychange",
      (_: dom.Event) => if (dom.document.visibilityState.toString == "visible") refresh()
    )
  }

}
