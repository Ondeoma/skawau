package skawau.web

import org.scalajs.dom

import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** 画像の加工. */
object Images {

  /** 中央で正方形に切り取り、size px に縮めた PNG（アイコンの登録用. GIF などで読めないときは元のファイル）. */
  def squareThumb(file: dom.File, size: Int = 256): Future[dom.File] = {
    val p = Promise[dom.File]()
    val url = dom.URL.createObjectURL(file)
    val img = dom.document.createElement("img").asInstanceOf[dom.HTMLImageElement]
    img.addEventListener(
      "load",
      (_: dom.Event) => {
        val (w, h) = (img.naturalWidth.toDouble, img.naturalHeight.toDouble)
        val s = math.min(w, h)
        val side = math.min(size.toDouble, s)
        val canvas = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]
        canvas.width = side.toInt
        canvas.height = side.toInt
        canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D].drawImage(
          img,
          (w - s) / 2,
          (h - s) / 2,
          s,
          s,
          0,
          0,
          canvas.width,
          canvas.height
        )
        dom.URL.revokeObjectURL(url)
        canvas.asInstanceOf[js.Dynamic].toBlob(
          (b: dom.Blob) =>
            p.trySuccess(
              if (b == null) file
              else js.Dynamic.newInstance(js.Dynamic.global.File)(
                js.Array(b),
                "avatar.png",
                js.Dynamic.literal(`type` = "image/png")
              ).asInstanceOf[dom.File]
            ),
          "image/png"
        )
      }
    )
    img.addEventListener("error", (_: dom.Event) => { dom.URL.revokeObjectURL(url); p.trySuccess(file) })
    img.src = url
    p.future
  }
}
