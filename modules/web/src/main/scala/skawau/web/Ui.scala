package skawau.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import skawau.shared.*

/** 画面内のダイアログ（ブラウザ標準の confirm・prompt・alert の代わり）. 表示は parts/DialogHost.st. */
final case class DialogReq(message: String,
                           okLabel: String,
                           danger: Boolean,
                           input: Option[RV[String]],
                           choices: List[(String, String)],
                           cancel: Boolean,
                           onOk: String => Unit,
                           onCancel: () => Unit)

/** この端末だけの表示の設定（localStorage. 使えないときは既定値）. */
object Prefs extends sbirds.web.LocalPrefs("skawau")

/** 一覧の表示（一覧・タイル）. この端末に記憶する. */
object Layout {

  val grid: RV[Boolean] = RV(Prefs.get("grid", "false") == "true")

  /** FileTable に渡す形（computed なので、画面の部品の中で呼ぶこと）. */
  def gridC = grid.mapC(identity)

  def setGrid(on: Boolean): Unit = { grid := on; Prefs.set("grid", on.toString) }

}

/** 画面の色（自動 = OS の設定、ライト、ダーク）. この端末に記憶する. */
object Theme extends sbirds.web.ThemeSwitch(Prefs)

/** オートコンプリート（parts/Combo.st）の選択肢. icon は前に付ける文字、user はアイコン. */
final case class Opt(value: String,
                     label: String,
                     icon: String = "",
                     user: Option[UserBrief] = None,
                     color: String = "",
                     hint: String = "")

object Opt {
  def of(xs: List[(String, String)]): List[Opt] = xs.map((v, l) => Opt(v, l))
}

/** タブ・チップの見た目（選んでいるものに active を付ける. 画面の部品の中で呼ぶこと）. */
object Ui {
  def active[A](cur: RV[A], v: A, base: String = ""): CRV[String] =
    cur.mapC(x => if (x == v) (base + " active").trim else base)
}
