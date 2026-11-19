package skawau.web

import org.scalajs.dom

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
