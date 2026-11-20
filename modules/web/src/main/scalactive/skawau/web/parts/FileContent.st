<script lang="scala">
import skawau.web.*
import skawau.shared.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// ファイルの中身の表示: 画像・動画・音声・PDF・テキスト（テキストは編集の権限があれば画面で書き換えられる）.
// token は公開リンクで開いたとき.
case class Props(node: NodeDto, token: Option[String] = None)

case class State(text: RV[Option[String]] = RV(None),
                 error: RV[String] = RV(""),
                 editing: RV[Boolean] = RV(false),
                 draft: RV[String] = RV(""),
                 version: RV[Int] = RV(0),
                 saving: RV[Boolean] = RV(false))

def initState(props: Props) = State(version = RV(props.node.version))

def url(props: Props): String = props.token match {
  case Some(t) => Api.publicContentUrl(t, props.node.id) + s"&v=${props.node.version}"
  case None    => Api.contentUrl(props.node.id) + s"?v=${props.node.version}"
}

def kind(props: Props): String = FileKind.kind(props.node.name, props.node.isDir)

def mounted(s: State, props: Props): Unit =
  if (kind(props) == FileKind.Text) {
    if (props.node.size > 5L * 1024 * 1024) s.error := "大きすぎるため表示できません。ダウンロードしてください"
    else Api.text(url(props)).onComplete {
      case Success(t) => s.text := Some(t)
      case Failure(e) => s.error := e.getMessage
    }
  }

def canEdit(props: Props): Boolean =
  props.token.isEmpty && Perm.canEdit(props.node.perm) && FileKind.textEditable(props.node.name, props.node.size)

def edit(s: State): Unit = { s.draft := s.text.v.getOrElse(""); s.editing := true }

def save(s: State, props: Props): Unit = {
  s.saving := true
  Api.putText[NodeDto](s"/api/files/${props.node.id}/content?version=${s.version.v}", s.draft.v).onComplete {
    case Success(n) =>
      s.saving := false
      s.text := Some(s.draft.v)
      s.version := n.version
      s.editing := false
      Toasts.show("保存しました")
      Store.changed()
    case Failure(e) =>
      s.saving := false
      Toasts.error(e)
  }
}

def is(props: Props, k: String): Boolean = kind(props) == k
def textBody(s: State) = s.text.mapC(_.getOrElse("読み込み中…"))
def hasError(s: State) = s.error.mapC(_.nonEmpty)
def viewing(s: State) = s.editing.mapC(!_)
</script>

<template>
  <div class="content">
    <img v-if="is(props, FileKind.Image)" :src="url(props)" :alt="props.node.name" class="img">
    <video v-if="is(props, FileKind.Video)" :src="url(props)" controls preload="metadata" class="media"></video>
    <audio v-if="is(props, FileKind.Audio)" :src="url(props)" controls preload="metadata"></audio>
    <iframe v-if="is(props, FileKind.Pdf)" :src="url(props)" class="pdf" :title="props.node.name"></iframe>
    <div v-if="is(props, FileKind.Text)" class="text">
      <p v-if="hasError(s)" class="error">{{ s.error }}</p>
      <template v-else>
        <div v-if="canEdit(props)" class="row bar">
          <span class="grow"></span>
          <button v-if="viewing(s)" class="small" @click="edit(s)">✏️ 編集</button>
          <template v-if="s.editing">
            <button class="small" @click="s.editing := false">やめる</button>
            <button class="small primary" :disabled="s.saving" @click="save(s, props)">保存（新しい版）</button>
          </template>
        </div>
        <pre v-if="viewing(s)" class="text-view">{{ textBody(s) }}</pre>
        <textarea v-if="s.editing" class="text-edit" v-model="s.draft" spellcheck="false"></textarea>
      </template>
    </div>
  </div>
</template>

<style scoped>
.content { display: flex; justify-content: center; align-items: center; min-height: 200px; }
.img { max-width: 100%; max-height: 78vh; object-fit: contain; border-radius: 6px; }
.media { max-width: 100%; max-height: 78vh; background: #000; border-radius: 6px; }
.pdf { width: 100%; height: 78vh; border: 1px solid var(--line); border-radius: 6px; background: #fff; }
.text { width: 100%; }
.bar { margin-bottom: .4rem; }
</style>
