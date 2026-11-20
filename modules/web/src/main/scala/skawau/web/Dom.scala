package skawau.web

import org.scalajs.dom

/** DOM のイベント・クリップボードの補助（共通のものは sbirds-lib の Dom）. */
object Dom {

  export sbirds.web.Dom.*

  def stop(e: dom.Event): Unit = e.stopPropagation()

  /** 入力欄の文字をすべて選ぶ（コピーしやすく）. */
  def selectAll(e: dom.Event): Unit = e.target.asInstanceOf[dom.HTMLInputElement].select()

  /** ドラッグされているのが（ブラウザの外からの）ファイルか. */
  def hasFiles(e: dom.Event): Boolean = {
    val dt = e.asInstanceOf[dom.DragEvent].dataTransfer
    dt != null && dt.types.toList.contains("Files")
  }

}
