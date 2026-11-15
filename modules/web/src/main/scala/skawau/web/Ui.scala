package skawau.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom
import skawau.shared.*

import scala.concurrent.Future
import scala.scalajs.js

/** DOM のイベント・クリップボードの補助. */
object Dom {

  /** 入力欄（input・select・textarea）の値. */
  def value(e: dom.Event): String = e.target.asInstanceOf[js.Dynamic].value.asInstanceOf[String]

  def checked(e: dom.Event): Boolean = e.target.asInstanceOf[dom.HTMLInputElement].checked

  def stop(e: dom.Event): Unit = e.stopPropagation()

  /** 入力欄の文字をすべて選ぶ（コピーしやすく）. */
  def selectAll(e: dom.Event): Unit = e.target.asInstanceOf[dom.HTMLInputElement].select()

  def copy(text: String, message: String = "コピーしました"): Unit = {
    dom.window.navigator.clipboard.writeText(text)
    Store.toast(message)
  }

  /** ドラッグされているのが（ブラウザの外からの）ファイルか. */
  def hasFiles(e: dom.Event): Boolean = {
    val dt = e.asInstanceOf[dom.DragEvent].dataTransfer
    dt != null && dt.types.toList.contains("Files")
  }

}

/** 表示の補助. */
object Fmt {

  private def opts(o: (String, String)*): js.Dynamic = js.Dynamic.literal(o.map((k, v) => k -> (v: js.Any))*)

  def dateTime(t: Timestamp): String =
    t.toDate.asInstanceOf[js.Dynamic].toLocaleString("ja-JP", opts("year" -> "numeric", "month" -> "2-digit", "day" -> "2-digit",
      "hour" -> "2-digit", "minute" -> "2-digit")).asInstanceOf[String]

  /** 「3分前」「昨日」など. */
  def ago(t: Timestamp): String = {
    val diff = (js.Date.now() - t.toEpochMilli) / 1000
    if (diff < 60) "たった今"
    else if (diff < 3600) s"${(diff / 60).toInt}分前"
    else if (diff < 86400) s"${(diff / 3600).toInt}時間前"
    else if (diff < 86400 * 7) s"${(diff / 86400).toInt}日前"
    else dateTime(t).take(10)
  }

  def bytes(n: Long): String = Bytes.format(n)

  def today(): String = isoDate(new js.Date())

  def isoDate(d: js.Date): String = f"${d.getFullYear().toInt}%04d-${d.getMonth().toInt + 1}%02d-${d.getDate().toInt}%02d"

  def addDays(d: String, n: Int): String = {
    val dt = new js.Date(d + "T00:00:00")
    dt.setDate(dt.getDate() + n)
    isoDate(dt)
  }

  def initials(name: String): String = name.trim.take(1)

  /** 容量の表示（「1.2 GB / 10 GB」）. */
  def quota(q: QuotaDto): String = q.limit.fold(s"${bytes(q.used)} 使用中")(l => s"${bytes(q.used)} / ${bytes(l)}")

  def quotaPercent(q: QuotaDto): Int = q.limit.filter(_ > 0).fold(0)(l => math.min(100, (q.used.toDouble / l * 100).toInt))

}

/** 画面内のダイアログ（ブラウザ標準の confirm・prompt・alert の代わり）. 表示は parts/DialogHost.st. */
final case class DialogReq(message: String, okLabel: String, danger: Boolean, input: Option[RV[String]], choices: List[(String, String)],
                           cancel: Boolean, onOk: String => Unit, onCancel: () => Unit)

object Dialog {

  val current: RV[Option[DialogReq]] = RV(None)

  private def open(r: DialogReq): Unit = current := Some(r)

  /** 確認（OK を押したら f）. */
  def confirm(message: String, ok: String = "OK", danger: Boolean = true)(f: => Unit): Unit =
    open(DialogReq(message, ok, danger, None, Nil, cancel = true, _ => f, () => ()))

  /** 文字の入力（OK を押したら入力した値で f）. */
  def prompt(message: String, default: String = "", ok: String = "OK")(f: String => Unit): Unit =
    open(DialogReq(message, ok, danger = false, Some(RV(default)), Nil, cancel = true, f, () => ()))

  /** 選択肢から選ぶ（choices は 値 → 表示名）. */
  def choose(message: String, choices: List[(String, String)], ok: String = "OK", danger: Boolean = false)(f: String => Unit): Unit =
    open(DialogReq(message, ok, danger, Some(RV(choices.headOption.map(_._1).getOrElse(""))), choices, cancel = true, f, () => ()))

  def alert(message: String): Unit = open(DialogReq(message, "OK", danger = false, None, Nil, cancel = false, _ => (), () => ()))

  def close(ok: Boolean): Unit = current.v.foreach { r =>
    current := None
    if (ok) r.onOk(r.input.map(_.v).getOrElse("")) else r.onCancel()
  }

}

/** この端末だけの表示の設定（localStorage. 使えないときは既定値）. */
object Prefs {

  def get(key: String, default: String): String =
    scala.util.Try(Option(dom.window.localStorage.getItem("skawau." + key))).toOption.flatten.getOrElse(default)

  def set(key: String, value: String): Unit = scala.util.Try(dom.window.localStorage.setItem("skawau." + key, value))

}

/** 一覧の表示（一覧・タイル）. この端末に記憶する. */
object Layout {

  val grid: RV[Boolean] = RV(Prefs.get("grid", "false") == "true")

  /** FileTable に渡す形（computed なので、画面の部品の中で呼ぶこと）. */
  def gridC = grid.mapC(identity)

  def setGrid(on: Boolean): Unit = { grid := on; Prefs.set("grid", on.toString) }

}

/** 画面の色（自動 = OS の設定、ライト、ダーク）. この端末に記憶する. */
object Theme {

  val current: RV[String] = RV(Prefs.get("theme", "system"))

  def apply(): Unit = {
    val el = dom.document.documentElement
    if (current.v == "system") el.removeAttribute("data-theme") else el.setAttribute("data-theme", current.v)
  }

  def set(v: String): Unit = { current := v; Prefs.set("theme", v); apply() }

  val options: List[(String, String)] = List("system" -> "🖥 自動（OS に合わせる）", "light" -> "☀️ ライト", "dark" -> "🌙 ダーク")

}

/** オートコンプリート（parts/Combo.st）の選択肢. icon は前に付ける文字、user はアイコン. */
final case class Opt(value: String, label: String, icon: String = "", user: Option[UserBrief] = None, color: String = "", hint: String = "")

object Opt {
  def of(xs: List[(String, String)]): List[Opt] = xs.map((v, l) => Opt(v, l))
}

/** ONLYOFFICE のエディタ（frontend/src/office.js）. */
object Office {

  @js.native
  trait Handle extends js.Object {
    def destroy(): Unit = js.native
  }

  def open(serverUrl: String, elementId: String, config: io.circe.Json): Future[Handle] =
    dom.window.asInstanceOf[js.Dynamic].skawauOffice.load(serverUrl, elementId, js.JSON.parse(config.noSpaces))
      .asInstanceOf[js.Promise[Handle]].toFuture

}

/** タブ・チップの見た目（選んでいるものに active を付ける. 画面の部品の中で呼ぶこと）. */
object Ui {
  def active[A](cur: RV[A], v: A, base: String = ""): CRV[String] = cur.mapC(x => if (x == v) (base + " active").trim else base)
}
