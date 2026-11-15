<script lang="scala">
import skawau.web.*
import skawau.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// ONLYOFFICE のエディタ（全画面）. token は公開リンクから開いたとき.
// 保存は Document Server が行う（全員が閉じたあと、または保存ボタンで、新しい版として skawau に送られてくる）.
case class Props(fileId: String, token: Option[String])

case class State(cfg: RV[Option[OfficeConfigDto]] = RV(None),
                 error: RV[String] = RV(""),
                 handle: RV[Option[Office.Handle]] = RV(None))

def initState() = State()

def mounted(s: State, props: Props): Unit = {
  Api.officeConfig(props.fileId, props.token).onComplete {
    case Success(c) =>
      s.cfg := Some(c)
      pageTitle(c.name)
      Office.open(c.documentServerUrl, "office-editor", c.config).onComplete {
        case Success(h) => s.handle := Some(h)
        case Failure(e) => s.error := e.getMessage
      }
    case Failure(e) => s.error := e.getMessage
  }
}

def unmounted(s: State): Unit = s.handle.v.foreach(_.destroy())

def back(s: State, props: Props): Unit = props.token match {
  case Some(t) => Router.go(s"/s/$t")
  case None    => Router.go(s.cfg.v.flatMap(_.folderId).fold("/files")(id => s"/files/$id"))
}

def name(s: State) = s.cfg.mapC(_.map(_.name).getOrElse("読み込み中…"))
def mode(s: State) = s.cfg.mapC(_.map(c => if (c.canEdit) "編集できます（自動で保存されます）" else "閲覧のみ").getOrElse(""))
def hasError(s: State) = s.error.mapC(_.nonEmpty)
</script>

<template>
  <div class="office">
    <div class="obar">
      <button class="ghost small" @click="back(s, props)">← 戻る</button>
      <b class="grow nm">{{ name(s) }}</b>
      <span class="muted small hide-sp">{{ mode(s) }}</span>
    </div>
    <div v-if="hasError(s)" class="page narrow"><div class="card">
      <h2>文書を開けませんでした</h2>
      <p class="error">{{ s.error }}</p>
      <p class="muted small">ONLYOFFICE（Document Server）が動いているか、管理者に確認してください。</p>
      <button @click="back(s, props)">戻る</button>
    </div></div>
    <div id="office-editor" class="editor"></div>
  </div>
</template>

<style scoped>
.office { display: flex; flex-direction: column; height: 100vh; height: 100dvh; }
.obar { display: flex; align-items: center; gap: .6rem; padding: .3rem .7rem; border-bottom: 1px solid var(--line); background: var(--bg); }
.nm { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.editor { flex: 1; min-height: 0; }
</style>
