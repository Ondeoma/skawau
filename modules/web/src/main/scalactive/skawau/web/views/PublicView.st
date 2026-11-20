<script lang="scala">
import skawau.web.*
import org.scalajs.dom
import skawau.shared.*
import skawau.web.parts.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 公開リンク（/s/{token}. ログイン不要）: パスワードの入力、フォルダの閲覧・ダウンロード、アップロード（権限があれば）、
// 画像・PDF などのプレビュー、Office 文書を ONLYOFFICE で開く.
case class Props(token: String)

case class State(info: RV[Option[PublicInfoDto]] = RV(None),
                 folder: RV[Option[FolderDto]] = RV(None),
                 error: RV[String] = RV(""),
                 password: RV[String] = RV(""),
                 pwError: RV[String] = RV(""),
                 selected: RV[Set[UUID]] = RV(Set.empty),
                 over: RV[Boolean] = RV(false),
                 fileInput: ElementRef[dom.HTMLInputElement] = ElementRef())

def initState() = State()

def loadInfo(s: State, props: Props): Unit = Api.publicInfo(props.token).onComplete {
  case Success(i) =>
    s.info := Some(i)
    pageTitle(i.name)
    if (!i.needsPassword && i.isDir) open(s, props, None)
  case Failure(e) => s.error := e.getMessage
}

def open(s: State, props: Props, id: Option[UUID]): Unit = {
  s.selected := Set.empty
  Toasts.report(Api.publicFolder(props.token, id))(f => s.folder := Some(f))
}

def mounted(s: State, props: Props): Unit = {
  loadInfo(s, props)
  watch(Store.tick, false)((_, _) => s.folder.v.foreach(f => open(s, props, Some(f.folder.id))))
}

def unlock(s: State, props: Props): Unit = {
  s.pwError := ""
  Api.publicAuth(props.token, s.password.v).onComplete {
    case Success(_) => loadInfo(s, props)
    case Failure(e) => s.pwError := e.getMessage
  }
}

def locked(s: State) = s.info.mapC(_.exists(_.needsPassword))
def single(s: State) = s.info.mapC(_.exists(i => !i.needsPassword && !i.isDir))
def isFolder(s: State) = s.info.mapC(_.exists(i => !i.needsPassword && i.isDir))
def canUpload(s: State) = s.info.mapC(_.exists(i => i.permission == Perm.Edit || i.permission == Perm.Upload))
def canRead(s: State) = s.info.mapC(_.exists(_.permission != Perm.Upload))
def canEdit(s: State) = s.info.mapC(_.exists(_.permission == Perm.Edit))
def title(s: State) = s.info.mapC(_.map(_.name).getOrElse(""))
def sub(s: State) = s.info.mapC(_.map(i => s"${i.ownerName} さんが共有${i.expiresAt.fold("")(d => s"・$d まで")}${i.label.fold("")(l => s"・$l")}").getOrElse(""))

/** 1つのファイルのリンクのとき、その中身（FileContent に渡す形）. */
def singleNode(s: State) = s.info.mapC(_.filter(i => !i.needsPassword && !i.isDir).toList.map { i =>
  val now = Timestamp(new scala.scalajs.js.Date().toISOString())
  NodeDto(i.rootId, None, i.name, false, i.size, i.contentType, now, now, None, UserBrief(i.rootId, i.ownerName, None), 1, "", i.permission,
    false, false, false)
})

def rows(s: State) = s.folder.mapC(_.toList.flatMap(_.items).map(Row(_)))
def crumbs(s: State) = s.folder.mapC(_.toList.flatMap(_.path))
def items(s: State): List[NodeDto] = s.folder.v.toList.flatMap(_.items)

def openNode(s: State, props: Props, n: NodeDto): Unit =
  if (n.isDir) open(s, props, Some(n.id))
  else if (FileOps.officeOpenable(n)) Router.go(s"/s/${props.token}/office/${n.id}")
  else if (FileKind.previewable(FileOps.kind(n))) {
    val list = items(s).filter(x => !x.isDir && FileKind.previewable(FileOps.kind(x)))
    Store.preview := Some(PreviewReq(list, math.max(0, list.indexWhere(_.id == n.id)), Some(props.token)))
  } else download(props, n)

def download(props: Props, n: NodeDto): Unit = Dom.downloadUrl(Api.publicContentUrl(props.token, n.id, download = true), "")

def actions(s: State, props: Props)(n: NodeDto): List[MenuItem] = List(
  MenuItem("開く", () => openNode(s, props, n)),
  MenuItem(if (n.isDir) "ZIP でダウンロード" else "ダウンロード", () => download(props, n))
)

def downloadAll(s: State, props: Props): Unit = {
  val ids = if (s.selected.v.nonEmpty) s.selected.v.toList else Nil
  Dom.downloadUrl(s"/api/public/${props.token}/zip" + (if (ids.isEmpty) "" else "?ids=" + ids.mkString(",")), "")
}

def officeOk(s: State) = computed { s.info.v.exists(i => Store.config.v.flatMap(_.office).isDefined && FileKind.officeType(i.name).isDefined) }

def dir(s: State): Option[UUID] = s.folder.v.map(_.folder.id).orElse(s.info.v.map(_.rootId))

def upload(s: State, props: Props, files: List[(dom.File, String)]): Unit = dir(s).foreach { d =>
  Uploads.start(files, (name, path) => Api.publicUploadUrl(props.token, d, name, path)).foreach { _ =>
    Toasts.show("アップロードしました")
    if (canRead(s).v) open(s, props, Some(d))
  }
}

def pick(s: State, props: Props, e: dom.Event): Unit = upload(s, props, Uploads.picked(e))

def zoneOver(s: State, e: dom.Event): Unit = if (Dom.hasFiles(e) && canUpload(s).v) { e.preventDefault(); if (!s.over.v) s.over := true }
def zoneDrop(s: State, props: Props, e: dom.Event): Unit = {
  s.over := false
  if (canUpload(s).v) Uploads.dropped(e).foreach(f => Toasts.report(f)(upload(s, props, _)))
}

def mkdir(s: State, props: Props): Unit = dir(s).foreach { d =>
  Dialog.prompt("新しいフォルダの名前", "新しいフォルダ", "作成") { name =>
    if (name.trim.nonEmpty) Toasts.report(Api.publicMkdir(props.token, d, name.trim))(_ => open(s, props, Some(d)))
  }
}

def zoneClass(s: State) = s.over.mapC(o => if (o) "dropzone over" else "dropzone")
def noGrid = computed(false)
</script>

<template>
  <div class="pub">
    <header class="ptop">
      <img :src='AppNames.logo("/logo.svg").v' alt="" height="26" class="brand-logo"><b class="brand">{{ AppNames.self }}</b>
      <span class="grow"></span>
      <a href="/files" class="small">ログイン</a>
    </header>
    <div class="page">
      <div v-if="s.error.mapC(_.nonEmpty)" class="card center"><h2>リンクを開けません</h2><p class="error">{{ s.error }}</p></div>
      <div v-if="locked(s)" class="card center lock">
        <h2>🔒 {{ title(s) }}</h2>
        <p class="muted small">このリンクはパスワードで保護されています。</p>
        <form @submit.prevent="unlock(s, props)">
          <input type="password" v-model="s.password" placeholder="パスワード" v-focus>
          <button type="submit" class="primary">開く</button>
        </form>
        <p v-if="s.pwError.mapC(_.nonEmpty)" class="error">{{ s.pwError }}</p>
      </div>

      <div v-if="single(s)">
        <div class="row head">
          <div class="grow"><h1>{{ title(s) }}</h1><div class="muted small">{{ sub(s) }}</div></div>
          <button v-if="officeOk(s)" class="primary" @click='Router.go("/s/" + props.token + "/office/" + s.info.v.get.rootId)'>ONLYOFFICE で開く</button>
          <button v-if="canRead(s)" @click='Dom.downloadUrl(Api.publicContentUrl(props.token, s.info.v.get.rootId, true), "")'>⬇️ ダウンロード</button>
        </div>
        <template v-for="n in singleNode(s)">
          <FileContent v-if="FileKind.previewable(FileOps.kind(n))" :node="n" :token="Some(props.token)" />
        </template>
      </div>

      <div v-if="isFolder(s)" :class="zoneClass(s)" @dragover="zoneOver(s, event)" @dragleave="s.over := false" @drop="zoneDrop(s, props, event)">
        <div class="row head">
          <div class="grow"><h1>📁 {{ title(s) }}</h1><div class="muted small">{{ sub(s) }}</div></div>
          <button v-if="canEdit(s)" @click="mkdir(s, props)">📁 新しいフォルダ</button>
          <button v-if="canUpload(s)" class="primary" @click="s.fileInput.foreach(_.click())">⬆️ アップロード</button>
          <button v-if="canRead(s)" @click="downloadAll(s, props)">⬇️ {{ s.selected.mapC(x => if (x.isEmpty) "すべてダウンロード" else s"${x.size}件をダウンロード") }}</button>
          <input ref="s.fileInput" type="file" multiple class="hidden" @change="pick(s, props, event)">
        </div>
        <nav v-if="canRead(s)" class="crumbs small">
          <template v-for="c in crumbs(s)"><a href="#" @click.prevent="open(s, props, Some(c.id))">{{ c.name }}</a><span class="sl">›</span></template>
        </nav>
        <FileTable v-if="canRead(s)" :rows="rows(s)" :selected="s.selected" :grid="noGrid" :onOpen="n => openNode(s, props, n)" :actions="actions(s, props)"
          empty="このフォルダは空です" />
        <div v-if="canRead(s).mapC(!_)" class="card center drop">
          <p>📥 ファイルをここにドラッグするか、「アップロード」を押してください。</p>
          <p class="muted small">アップロードしたファイルは {{ s.info.mapC(_.map(_.ownerName).getOrElse("")) }} さんに届きます（ほかのファイルは見えません）。</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ptop { display: flex; align-items: center; gap: .5rem; padding: .6rem 1rem; border-bottom: 1px solid var(--line); }
.brand { color: var(--accent); }
.head { margin-bottom: .8rem; align-items: flex-start; }
.head h1 { margin: 0; word-break: break-all; }
.center { max-width: 520px; margin: 6vh auto; text-align: center; }
.lock form { display: flex; gap: .5rem; justify-content: center; }
.drop { margin-top: 2rem; }
.crumbs { margin-bottom: .6rem; }
</style>
