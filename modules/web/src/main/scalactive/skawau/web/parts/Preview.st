<script lang="scala">
import skawau.web.*
import org.scalajs.dom
import skawau.shared.*

import scala.scalajs.js

// プレビュー（Store.preview）: 同じフォルダの画像・動画・PDF・テキストを ← → で送れる.
case class State(keys: RV[Option[js.Function1[dom.KeyboardEvent, Unit]]] = RV(None))

def initState() = State()

def close(): Unit = Store.preview := None

def step(d: Int): Unit = Store.preview.v.foreach { p =>
  val i = p.index + d
  if (i >= 0 && i < p.items.size) Store.preview := Some(p.copy(index = i))
}

def mounted(s: State): Unit = {
  val f: js.Function1[dom.KeyboardEvent, Unit] = (e: dom.KeyboardEvent) => {
    val typing = Option(dom.document.activeElement).exists(a => a.tagName == "TEXTAREA" || a.tagName == "INPUT")
    if (!typing && e.key == "ArrowLeft") step(-1)
    else if (!typing && e.key == "ArrowRight") step(1)
  }
  dom.window.addEventListener("keydown", f)
  s.keys := Some(f)
}

def unmounted(s: State): Unit = s.keys.v.foreach(f => dom.window.removeEventListener("keydown", f))

/** 表示中のファイル（v-for の key で、ファイルが変わったら中身の部品を作り直す）. */
def currentList = Store.preview.mapC(_.toList.map(_.current))
def token = Store.preview.v.flatMap(_.token)
def name = Store.preview.mapC(_.map(_.current.name).getOrElse(""))
def counter = Store.preview.mapC(_.filter(_.items.size > 1).map(p => s"${p.index + 1} / ${p.items.size}").getOrElse(""))
def hasPrev = Store.preview.mapC(_.exists(_.index > 0))
def hasNext = Store.preview.mapC(_.exists(p => p.index < p.items.size - 1))

def download(): Unit = Store.preview.v.foreach { p =>
  val n = p.current
  Api.download(p.token.fold(Api.contentUrl(n.id, download = true))(t => Api.publicContentUrl(t, n.id, download = true)))
}
</script>

<template>
  <Modal :onClose="_ => close()" :xwide="true">
    <div class="row head">
      <b class="grow nm">{{ name }}</b>
      <span class="muted small">{{ counter }}</span>
      <button class="small" :disabled="hasPrev.mapC(!_)" @click="step(-1)">←</button>
      <button class="small" :disabled="hasNext.mapC(!_)" @click="step(1)">→</button>
      <button class="small" @click="download()">⬇️ ダウンロード</button>
      <button class="ghost small" @click="close()">✕</button>
    </div>
    <FileContent v-for="n in currentList" :key="n.id" :node="n" :token="token" />
  </Modal>
</template>

<style scoped>
.head { margin-bottom: .7rem; }
.nm { word-break: break-all; }
</style>
