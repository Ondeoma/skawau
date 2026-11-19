package skawau.web

import org.scalajs.dom

import io.circe.Json

import scala.concurrent.{Future, Promise}
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** ONLYOFFICE のエディタ. api.js は Document Server から一度だけ読み込む. */
object Office {

  /** 開いたエディタ. destroy() で閉じる. */
  final class Handle private[Office] (
    editor: js.Dynamic) {
    def destroy(): Unit = try { editor.destroyEditor(); () }
    catch { case _: Throwable => () } // 閉じ済み
  }

  private val loaded = scala.collection.mutable.Map.empty[String, Future[Unit]]

  private def loadScript(
    url: String
  ): Future[Unit] = loaded.getOrElseUpdate(
    url, {
      val p = Promise[Unit]()
      val s = dom.document.createElement("script").asInstanceOf[dom.HTMLScriptElement]
      s.src = url
      s.addEventListener("load", (_: dom.Event) => p.trySuccess(()))
      s.addEventListener(
        "error",
        (_: dom.Event) => { loaded.remove(url); p.tryFailure(new Exception(s"Document Server に接続できません（$url）")) }
      )
      dom.document.head.appendChild(s)
      p.future
    }
  )

  def open(
    serverUrl: String,
    elementId: String,
    config: Json
  ): Future[Handle] =
    loadScript(serverUrl.stripSuffix("/") + "/web-apps/apps/api/documents/api.js").map { _ =>
      val docs = dom.window.asInstanceOf[js.Dynamic].DocsAPI
      if (js.isUndefined(docs)) throw new Exception("Document Server の api.js を読み込めませんでした")
      val defaults = Json.obj(
        "width" -> Json.fromString("100%"),
        "height" -> Json.fromString("100%"),
        "type" -> Json.fromString(if (dom.window.innerWidth < 720) "mobile" else "desktop")
      )
      val container = Option(dom.document.getElementById(elementId)).getOrElse(throw new Exception("エディタの置き場所がありません"))
      // DocEditor は渡した要素を iframe に置き換えるため、画面の部品が持つ要素の中に置き場所を作って渡す
      container.innerHTML = ""
      val ph = dom.document.createElement("div")
      ph.id = elementId + "-placeholder"
      container.appendChild(ph)
      new Handle(js.Dynamic.newInstance(docs.DocEditor)(ph.id, js.JSON.parse(defaults.deepMerge(config).noSpaces)))
    }

}
