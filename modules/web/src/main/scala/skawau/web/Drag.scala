package skawau.web

import org.scalajs.dom

import java.util.UUID
import scala.scalajs.js

/** 一覧の中でのドラッグ（ファイル・フォルダを別のフォルダへ動かす）. */
object Drag {
  val Mime = "application/x-skawau-ids"

  def start(ids: List[UUID], e: dom.Event): Unit = {
    val dt = e.asInstanceOf[dom.DragEvent].dataTransfer
    dt.setData(Mime, ids.mkString(","))
    dt.setData("text/plain", ids.size.toString + " 件")
    dt.effectAllowed = dom.DataTransferEffectAllowedKind.move
  }

  /** ドロップされた ID（一覧の中からのドラッグのとき）. */
  def ids(e: dom.Event): List[UUID] = {
    val dt = e.asInstanceOf[dom.DragEvent].dataTransfer
    Option(dt).map(_.getData(Mime)).filter(_ != null).toList.flatMap(_.split(',').toList).flatMap(s =>
      scala.util.Try(UUID.fromString(s)).toOption
    )
  }

  def isInternal(e: dom.Event): Boolean = {
    val dt = e.asInstanceOf[dom.DragEvent].dataTransfer
    dt != null && dt.types.toList.contains(Mime)
  }
}
