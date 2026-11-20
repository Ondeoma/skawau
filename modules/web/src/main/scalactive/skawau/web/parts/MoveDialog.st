<script lang="scala">
import skawau.web.*
import skawau.shared.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 移動・コピー先のフォルダを選ぶダイアログ（Store.moveReq）. 自分のファイルと、編集できる共有されたフォルダをたどれる.
case class State(folder: RV[Option[FolderDto]] = RV(None),
                 shared: RV[Option[List[NodeHitDto]]] = RV(None),
                 move: ActionState)

def initState() = State(move = useAction())

def req: Option[MoveReqUi] = Store.moveReq.v

def go(s: State, id: Option[UUID]): Unit = {
  s.shared := None
  Toasts.report(id.fold(Api.root())(Api.folder))(f => s.folder := Some(f))
}

def showShared(s: State): Unit = {
  s.folder := None
  Toasts.report(Api.sharedWithMe())(hs => s.shared := Some(hs.filter(h => h.node.isDir && Perm.canEdit(h.node.perm))))
}

def mounted(s: State): Unit = go(s, req.flatMap(_.from))

def close(): Unit = Store.moveReq := None

def moving: Set[UUID] = req.toList.flatMap(_.items.map(_.id)).toSet

def folders(s: State) = s.folder.mapC(_.toList.flatMap(_.items.filter(n => n.isDir && !moving.contains(n.id))))
def crumbs(s: State) = s.folder.mapC(_.toList.flatMap(_.path))
def crumbName(c: Crumb): String = if (c.name.isEmpty) "すべてのファイル" else c.name
def target(s: State) = s.folder.mapC(_.map(_.folder))
def canDrop(s: State) = computed { s.folder.v.exists(f => Perm.canEdit(f.folder.perm) && !moving.contains(f.folder.id)) && !s.move.busy.v }
def inShared(s: State) = s.shared.mapC(_.isDefined)
def inShare(s: State) = s.folder.mapC(_.exists(_.inShare))
def title = Store.moveReq.mapC(_.map(r => s"${if (r.items.size == 1) s"「${r.items.head.name}」" else s"${r.items.size}件"}を${if (r.copy) "コピー" else "移動"}").getOrElse(""))
def okLabel = Store.moveReq.mapC(r => if (r.exists(_.copy)) "ここにコピー" else "ここに移動")

def run(s: State, conflict: Option[String]): Unit = (req, s.folder.v) match {
  case (Some(r), Some(f)) =>
    s.move.run(Api.transfer(r.items.map(_.id), f.folder.id, r.copy, conflict)).onComplete { res =>
      res match {
        case scala.util.Success(b) if b.errors.nonEmpty && conflict.isEmpty && b.errors.exists(_.contains("移動先に")) =>
          Dialog.choose("移動先に同じ名前のものがあります。", List("rename" -> "両方残す（名前に番号を付ける）", "overwrite" -> "上書きする（移動先にあったものはごみ箱へ）"), "続ける") { c =>
            run(s, Some(c))
          }
          if (b.done.nonEmpty) Store.changed()
        case scala.util.Success(b) =>
          if (b.errors.nonEmpty) Dialog.alert("できなかったもの:\n" + b.errors.mkString("\n"))
          else Toasts.show(s"${b.done.size}件を${if (r.copy) "コピー" else "移動"}しました", Some(s"/files/${f.folder.id}"))
          close()
          Store.changed()
        case scala.util.Failure(e) => Toasts.error(e)
      }
    }
  case _ => ()
}
</script>

<template>
  <Modal :onClose="_ => close()">
    <h2>{{ title }}</h2>
    <div class="row small">
      <button class="small" @click="go(s, None)">📁 すべてのファイル</button>
      <button class="small" @click="showShared(s)">👥 共有されたもの</button>
    </div>
    <nav v-if="target(s).mapC(_.isDefined)" class="crumbs small path">
      <span v-if="inShare(s)" class="sl">共有されたもの ›</span>
      <template v-for="c in crumbs(s)"><a href="#" @click.prevent="go(s, Some(c.id))">{{ crumbName(c) }}</a><span class="sl">›</span></template>
    </nav>
    <div class="box">
      <template v-if="inShared(s)">
        <div v-for="h in s.shared.mapC(_.getOrElse(Nil))" :key="h.node.id" class="dir" @click="go(s, Some(h.node.id))">📁 {{ h.node.name }}
          <span class="muted small">{{ h.node.owner.name }}</span></div>
        <p v-if="s.shared.mapC(_.exists(_.isEmpty))" class="muted small pad">編集できる共有されたフォルダはありません</p>
      </template>
      <template v-else>
        <div v-for="f in folders(s)" :key="f.id" class="dir" @click="go(s, Some(f.id))">📁 {{ f.name }}</div>
        <p v-if="folders(s).mapC(_.isEmpty)" class="muted small pad">フォルダはありません</p>
      </template>
    </div>
    <div class="actions">
      <button @click="close()">キャンセル</button>
      <button class="primary" :disabled="canDrop(s).mapC(!_)" @click="run(s, None)">{{ okLabel }}</button>
    </div>
  </Modal>
</template>

<style scoped>
.path { margin: .6rem 0 .3rem; font-size: .88rem; }
.box { border: 1px solid var(--line); border-radius: 9px; height: 300px; overflow-y: auto; }
.dir { padding: .5rem .8rem; cursor: pointer; border-bottom: 1px solid var(--line); }
.dir:hover { background: var(--hover); }
.pad { padding: .8rem; }
</style>
