<script lang="scala">
import skawau.web.*
import org.scalajs.dom
import skawau.shared.*
import skawau.web.parts.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// フォルダの中身（folderId が None なら自分のルート）. アップロード（ボタン・ドラッグ＆ドロップ、フォルダごと）、新規作成、
// まとめての操作、行のドラッグでの移動、詳細のパネル.
case class Props(folderId: Option[UUID])

case class State(data: RV[Option[FolderDto]] = RV(None),
                 error: RV[String] = RV(""),
                 selected: RV[Set[UUID]] = RV(Set.empty),
                 filter: RV[String] = RV(""),
                 over: RV[Boolean] = RV(false),
                 newMenu: RV[Boolean] = RV(false),
                 panel: RV[Option[NodeDto]] = RV(None),
                 crumbOver: RV[Option[UUID]] = RV(None),
                 fileInput: ElementRef[dom.HTMLInputElement] = ElementRef(),
                 dirInput: ElementRef[dom.HTMLInputElement] = ElementRef())

def initState() = State()

def load(s: State, props: Props): Unit =
  props.folderId.fold(Api.root())(Api.folder).onComplete {
    case Success(d) =>
      s.data := Some(d)
      s.error := ""
      s.selected := s.selected.v.filter(id => d.items.exists(_.id == id))
      s.panel.v.foreach(p => s.panel := d.items.find(_.id == p.id).orElse(Option.when(d.folder.id == p.id)(d.folder)))
      pageTitle(if (d.folder.parentId.isEmpty) "すべてのファイル" else d.folder.name)
    case Failure(e) => s.error := e.getMessage
  }

def mounted(s: State, props: Props): Unit = {
  load(s, props)
  watch(Store.tick, false)((_, _) => load(s, props))
}

def folder(s: State) = s.data.mapC(_.map(_.folder))
def folderId(s: State): Option[UUID] = s.data.v.map(_.folder.id)
def items(s: State): List[NodeDto] = s.data.v.toList.flatMap(_.items)
def canEdit(s: State) = s.data.mapC(_.exists(d => Perm.canEdit(d.folder.perm)))

def rows(s: State) = computed {
  val q = s.filter.v.trim.toLowerCase
  s.data.v.toList.flatMap(_.items).filter(n => q.isEmpty || n.name.toLowerCase.contains(q)).map(Row(_))
}

def selectedNodes(s: State): List[NodeDto] = items(s).filter(n => s.selected.v.contains(n.id))
def selCount(s: State) = s.selected.mapC(_.size)
def hasSel(s: State) = s.selected.mapC(_.nonEmpty)

/** パンくず（共有されたものを見ているときは「共有されたもの」から）. */
def crumbs(s: State) = s.data.mapC(_.toList.flatMap(d => d.path.dropRight(1)))
def current(s: State) = s.data.mapC(_.flatMap(_.path.lastOption).map(c => if (c.name.isEmpty) "すべてのファイル" else c.name).getOrElse(""))
def inShare(s: State) = s.data.mapC(_.exists(_.inShare))
def atRoot(s: State) = s.data.mapC(_.exists(_.folder.parentId.isEmpty))
def crumbName(c: Crumb): String = if (c.name.isEmpty) "すべてのファイル" else c.name
def crumbClass(s: State, c: Crumb) = s.crumbOver.mapC(o => if (o.contains(c.id)) "drop-over" else "")

def open(s: State, n: NodeDto): Unit = FileOps.open(n, items(s))

def actions(s: State)(n: NodeDto): List[MenuItem] = FileOps.actions(n, () => open(s, n), Some(x => s.panel := Some(x)))

// ---------------------------------------------------------------- アップロード

/** 同じ名前があれば、上書き（新しい版）・両方残す・送らない を選んでから送る. */
def upload(s: State, target: UUID, files: List[(dom.File, String)]): Unit = if (files.nonEmpty) {
  val existing = if (folderId(s).contains(target)) items(s).map(_.name.toLowerCase).toSet else Set.empty[String]
  val clash = files.filter((f, p) => p.isEmpty && existing.contains(f.name.toLowerCase))
  def go(conflict: Option[String], list: List[(dom.File, String)]): Unit =
    Uploads.start(list, (name, path) => Api.uploadUrl(target, name, path, conflict.orElse(Some("rename"))))
  if (clash.isEmpty) go(None, files)
  else
    Dialog.choose(s"「${clash.head._1.name}」${if (clash.size > 1) s"ほか${clash.size - 1}件" else ""}は同じ名前のファイルがあります。",
      List("overwrite" -> "上書きする（以前の内容は版として残る）", "rename" -> "両方残す（名前に番号を付ける）", "skip" -> "同じ名前のものは送らない"), "アップロード") {
      case "skip" => go(Some("rename"), files.filterNot(clash.contains))
      case c      => go(Some(c), files)
    }
}

def pick(s: State, e: dom.Event): Unit = { val files = Uploads.picked(e); folderId(s).foreach(upload(s, _, files)) }

def zoneOver(s: State, e: dom.Event): Unit =
  if (Dom.hasFiles(e) && canEdit(s).v) { e.preventDefault(); if (!s.over.v) s.over := true }

def zoneLeave(s: State, e: dom.Event): Unit = {
  val r = e.asInstanceOf[dom.DragEvent].relatedTarget
  val zone = e.currentTarget.asInstanceOf[dom.Element]
  if (r == null || !zone.contains(r.asInstanceOf[dom.Node])) s.over := false
}

def zoneDrop(s: State, e: dom.Event): Unit = {
  s.over := false
  if (canEdit(s).v) Uploads.dropped(e).foreach(f => folderId(s).foreach(id => Toasts.report(f)(upload(s, id, _))))
}

/** フォルダ（行・パンくず）に落としたとき: 一覧の中のものなら移動、ファイルならそのフォルダへアップロード. */
def dropOn(s: State, target: UUID, e: dom.Event): Unit = {
  val ids = Drag.ids(e).filterNot(_ == target)
  if (ids.nonEmpty)
    Toasts.report(Api.transfer(ids, target, copy = false, None)) { r =>
      if (r.errors.nonEmpty) Dialog.alert("移動できなかったもの:\n" + r.errors.mkString("\n"))
      else Toasts.show(s"${r.done.size}件を移動しました")
      s.selected := Set.empty
      Store.changed()
    }
  else Uploads.dropped(e).foreach(f => Toasts.report(f)(upload(s, target, _)))
}

def dropFn(s: State): Option[(NodeDto, dom.Event) => Unit] = Some((n: NodeDto, e: dom.Event) => dropOn(s, n.id, e))

def crumbDragOver(s: State, c: Crumb, e: dom.Event): Unit =
  if (Drag.isInternal(e)) { e.preventDefault(); if (!s.crumbOver.v.contains(c.id)) s.crumbOver := Some(c.id) }

def crumbDrop(s: State, c: Crumb, e: dom.Event): Unit = { e.preventDefault(); s.crumbOver := None; dropOn(s, c.id, e) }

// ---------------------------------------------------------------- まとめての操作

def moveSel(s: State, copy: Boolean): Unit = Store.moveReq := Some(MoveReqUi(selectedNodes(s), copy, folderId(s)))
def trashSel(s: State): Unit = FileOps.trash(selectedNodes(s))(s.selected := Set.empty)
def downloadSel(s: State): Unit = FileOps.downloadMany(selectedNodes(s))

def newItem(s: State, f: UUID => Unit): Unit = { s.newMenu := false; folderId(s).foreach(f) }

def zoneClass(s: State) = s.over.mapC(o => if (o) "dropzone over" else "dropzone")
def splitClass(s: State) = s.panel.mapC(p => if (p.isDefined) "split with-panel" else "split")
def hasError(s: State) = s.error.mapC(_.nonEmpty)
def owner(s: State) = s.data.mapC(_.filter(d => !Store.myId.contains(d.folder.owner.id)).map(d => s"所有者: ${d.folder.owner.name}・${Perm.label(d.folder.perm)}").getOrElse(""))
def folderNode(s: State): Option[NodeDto] = s.data.v.map(_.folder)
def canShareFolder(s: State) = s.data.mapC(_.exists(d => d.folder.perm == Perm.Owner && d.folder.parentId.isDefined))
</script>

<template>
  <div class="page wide">
    <div class="row head">
      <nav class="crumbs grow">
        <template v-if="inShare(s)"><RouterLink to="/shared">共有されたもの</RouterLink><span class="sl">›</span></template>
        <template v-for="c in crumbs(s)">
          <a :href='"/files/" + c.id' :class="crumbClass(s, c)" @click.prevent='Router.go("/files/" + c.id)'
             @dragover="crumbDragOver(s, c, event)" @dragleave="s.crumbOver := None" @drop="crumbDrop(s, c, event)">{{ crumbName(c) }}</a>
          <span class="sl">›</span>
        </template>
        <span class="cur">{{ current(s) }}</span>
      </nav>
      <span class="muted small hide-sp">{{ owner(s) }}</span>
    </div>
    <p v-if="hasError(s)" class="error">{{ s.error }}</p>
    <div class="toolbar">
      <div v-if="canEdit(s)" class="newwrap">
        <button class="primary" @click="s.newMenu := !s.newMenu.v">＋ 新規</button>
        <div v-if="s.newMenu" class="menu-scrim" @click="s.newMenu := false"></div>
        <div v-if="s.newMenu" class="menu-pop newmenu">
          <button @click="s.newMenu := false; s.fileInput.foreach(_.click())">⬆️ ファイルをアップロード</button>
          <button @click="s.newMenu := false; s.dirInput.foreach(_.click())">📂 フォルダをアップロード</button>
          <hr>
          <button @click="newItem(s, FileOps.newFolder)">📁 フォルダ</button>
          <button @click='newItem(s, id => FileOps.newFile(id, "docx", "文書"))'>📘 文書（docx）</button>
          <button @click='newItem(s, id => FileOps.newFile(id, "xlsx", "スプレッドシート"))'>📗 スプレッドシート（xlsx）</button>
          <button @click='newItem(s, id => FileOps.newFile(id, "pptx", "プレゼンテーション"))'>📙 プレゼンテーション（pptx）</button>
          <button @click='newItem(s, id => FileOps.newFile(id, "txt", "テキスト"))'>📄 テキスト</button>
          <button @click='newItem(s, id => FileOps.newFile(id, "md", "メモ"))'>📝 Markdown</button>
        </div>
      </div>
      <input ref="s.fileInput" type="file" multiple class="hidden" @change="pick(s, event)">
      <input ref="s.dirInput" type="file" multiple webkitdirectory class="hidden" @change="pick(s, event)">
      <button v-if="canShareFolder(s)" @click="Store.shareFor := folderNode(s)">👥 このフォルダを共有</button>
      <div v-if="hasSel(s)" class="selbar">
        <b class="small">{{ selCount(s) }} 件を選択</b>
        <button class="small" @click="downloadSel(s)">⬇️ ダウンロード</button>
        <button v-if="canEdit(s)" class="small" @click="moveSel(s, false)">移動</button>
        <button class="small" @click="moveSel(s, true)">コピー</button>
        <button v-if="canEdit(s)" class="small danger" @click="trashSel(s)">ごみ箱へ</button>
        <button class="small ghost" @click="s.selected := Set.empty">選択を解除</button>
      </div>
      <span class="grow"></span>
      <input type="search" class="filter" v-model="s.filter" placeholder="このフォルダで絞り込み">
      <ViewToggle />
      <button class="small ghost" title="詳細" @click="s.panel := (if (s.panel.v.isDefined) None else folderNode(s))">ⓘ</button>
    </div>
    <div :class="splitClass(s)">
      <div :class="zoneClass(s)" @dragover="zoneOver(s, event)" @dragleave="zoneLeave(s, event)" @drop="zoneDrop(s, event)">
        <FileTable :rows="rows(s)" :selected="s.selected" :grid="Layout.gridC" :onOpen="n => open(s, n)" :actions="actions(s)"
          :draggable="true" :onDrop="dropFn(s)" :thumb="FileOps.thumb"
          empty="このフォルダは空です。ファイルをここにドラッグするか「＋ 新規」からアップロードしてください" />
      </div>
      <DetailsPanel v-if="s.panel.mapC(_.isDefined)" :node="s.panel" :onClose="_ => s.panel := None" />
    </div>
  </div>
</template>

<style scoped>
.head { min-height: 2.4rem; }
.newwrap { position: relative; }
.newmenu { top: 2.6rem; left: 0; }
.filter { width: 220px; }
.menu-scrim { position: fixed; inset: 0; z-index: 1100; }
@media (max-width: 720px) { .filter { width: 100%; order: 10; } }
</style>
