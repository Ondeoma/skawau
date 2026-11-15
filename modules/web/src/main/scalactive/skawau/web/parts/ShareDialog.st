<script lang="scala">
import skawau.web.*
import org.scalajs.dom
import skawau.shared.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 共有のダイアログ（Store.shareFor のファイル・フォルダ）: ユーザー・グループへの共有と、公開リンク.
case class State(shares: RV[List[ShareDto]] = RV(Nil),
                 q: RV[String] = RV(""),
                 found: RV[ShareeDto] = RV(ShareeDto(Nil, Nil)),
                 perm: RV[String] = RV(Perm.View),
                 timer: RV[Option[Int]] = RV(None))

def initState() = State()

def node: Option[NodeDto] = Store.shareFor.v

def load(s: State): Unit = node.foreach(n => Store.report(Api.shares(n.id))(s.shares := _))

def mounted(s: State): Unit = { load(s); search(s, "") }

def close(): Unit = { Store.shareFor := None; Store.changed() }

def search(s: State, q: String): Unit = {
  s.q := q
  s.timer.v.foreach(dom.window.clearTimeout)
  s.timer := Some(dom.window.setTimeout(() => Api.sharees(q).foreach(s.found := _), 250))
}

def people(s: State) = s.shares.mapC(_.filter(_.kind != ShareKind.Link))
def links(s: State) = s.shares.mapC(_.filter(_.kind == ShareKind.Link))
def taken(s: State) = s.shares.mapC(_.flatMap(sh => sh.user.map(_.id).orElse(sh.group.map(_.id))).toSet)
def users(s: State) = computed { val t = taken(s).v; s.found.v.users.filterNot(u => t.contains(u.id)) }
def groups(s: State) = computed { val t = taken(s).v; s.found.v.groups.filterNot(g => t.contains(g.id)) }

def add(s: State, kind: String, user: Option[UUID], group: Option[UUID]): Unit = node.foreach { n =>
  Store.report(Api.createShare(n.id, CreateShareReq(kind, user, group, s.perm.v, None, None, None))) { _ => load(s); Store.changed() }
}

def createLink(s: State): Unit = node.foreach { n =>
  Store.report(Api.createShare(n.id, CreateShareReq(ShareKind.Link, None, None, Perm.View, None, None, None))) { sh =>
    sh.url.foreach(u => Dom.copy(u, "公開リンクを作成し、コピーしました"))
    load(s)
    Store.changed()
  }
}

def update(s: State, sh: ShareDto, r: UpdateShareReq, msg: String = ""): Unit =
  Store.report(Api.updateShare(sh.id, r)) { _ => if (msg.nonEmpty) Store.toast(msg); load(s) }

def remove(s: State, sh: ShareDto): Unit = {
  val what = if (sh.kind == ShareKind.Link) "この公開リンクを削除しますか？（リンクを知っている人も開けなくなります）" else "共有を解除しますか？"
  Dialog.confirm(what, "解除する") { Store.report(Api.deleteShare(sh.id)) { _ => load(s); Store.changed() } }
}

def setPassword(s: State, sh: ShareDto): Unit =
  Dialog.prompt("リンクのパスワード（開く人に別の手段で伝えてください）", "", "設定する") { p =>
    if (p.trim.nonEmpty) update(s, sh, UpdateShareReq(None, Some(p.trim), None, None), "パスワードを設定しました")
  }

def setLabel(s: State, sh: ShareDto): Unit =
  Dialog.prompt("リンクの名前（どこで使うリンクかのメモ）", sh.label.getOrElse(""), "保存") { l =>
    update(s, sh, UpdateShareReq(None, None, None, Some(l.trim)))
  }

def permOptions(sh: ShareDto): List[(String, String)] =
  List(Perm.View -> "閲覧のみ", Perm.Edit -> "編集可") ++
    (if (sh.kind == ShareKind.Link && node.exists(_.isDir)) List(Perm.Upload -> "アップロードのみ") else Nil)

def chip(cur: String, v: String): String = if (cur == v) "chip active" else "chip"
def title = Store.shareFor.mapC(_.map(n => s"「${n.name}」を共有").getOrElse(""))
def who(sh: ShareDto): String = sh.user.map(_.name).orElse(sh.group.map(g => s"${g.name}（グループ・${g.members}人）")).getOrElse("")
def isDir = Store.shareFor.mapC(_.exists(_.isDir))
def permHelp = isDir.mapC(d => if (d) "編集可: 中のファイルの追加・変更・削除ができます" else "編集可: 中身を書き換えられます（ONLYOFFICE での編集を含む）")
def newPermChip(s: State, v: String) = Ui.active(s.perm, v, "chip")
</script>

<template>
  <Modal :onClose="_ => close()" :wide="true">
    <h2>{{ title }}</h2>
    <h3>ユーザー・グループ</h3>
    <div class="row">
      <input type="search" class="grow" :value="s.q" @input="search(s, Dom.value(event))" placeholder="名前・メール・グループで探す">
      <span class="chips">
        <button :class="newPermChip(s, Perm.View)" @click="s.perm := Perm.View">閲覧のみ</button>
        <button :class="newPermChip(s, Perm.Edit)" @click="s.perm := Perm.Edit">編集可</button>
      </span>
    </div>
    <p class="muted small">{{ permHelp }}</p>
    <div class="found">
      <button v-for="u in users(s)" :key="u.id" class="cand" @click="add(s, ShareKind.User, Some(u.id), None)">
        <Avatar :user="Some(u)" :size="22" /> {{ u.name }} <span class="muted">＋</span>
      </button>
      <button v-for="g in groups(s)" :key="g.id" class="cand" @click="add(s, ShareKind.Group, None, Some(g.id))">
        👥 {{ g.name }} <span class="muted small">{{ g.members }}人</span> <span class="muted">＋</span>
      </button>
    </div>
    <div class="list">
      <div v-for="sh in people(s)" :key="sh.id" class="item">
        <span class="grow">{{ who(sh) }}</span>
        <span class="chips">
          <button v-for="o in permOptions(sh)" :class="chip(sh.permission, o._1)" @click="update(s, sh, UpdateShareReq(Some(o._1), None, None, None))">{{ o._2 }}</button>
        </span>
        <input type="date" :value='sh.expiresAt.getOrElse("")' title="期限（空なら無期限）"
          @change="update(s, sh, UpdateShareReq(None, None, Some(Dom.value(event)), None))">
        <button class="ghost small danger" title="解除" @click="remove(s, sh)">✕</button>
      </div>
    </div>
    <h3>公開リンク</h3>
    <p class="muted small">リンクを知っている人は、ログインしなくても開けます。パスワードと期限を付けられます。</p>
    <div v-for="sh in links(s)" :key="sh.id" class="link">
      <div class="row">
        <input type="text" class="grow url" :value='sh.url.getOrElse("")' readonly @focus="Dom.selectAll(event)">
        <button class="small" @click='Dom.copy(sh.url.getOrElse(""), "リンクをコピーしました")'>コピー</button>
        <button class="ghost small danger" title="削除" @click="remove(s, sh)">✕</button>
      </div>
      <div class="row small opts">
        <span class="chips">
          <button v-for="o in permOptions(sh)" :class="chip(sh.permission, o._1)" @click="update(s, sh, UpdateShareReq(Some(o._1), None, None, None))">{{ o._2 }}</button>
        </span>
        <label>期限 <input type="date" :value='sh.expiresAt.getOrElse("")' @change="update(s, sh, UpdateShareReq(None, None, Some(Dom.value(event)), None))"></label>
        <button v-if="!sh.hasPassword" class="small" @click="setPassword(s, sh)">🔒 パスワードを設定</button>
        <button v-if="sh.hasPassword" class="small" @click='update(s, sh, UpdateShareReq(None, Some(""), None, None), "パスワードを外しました")'>🔓 パスワードを外す</button>
        <a href="#" @click.prevent="setLabel(s, sh)">{{ sh.label.getOrElse("名前を付ける") }}</a>
      </div>
    </div>
    <p><button @click="createLink(s)">🔗 公開リンクを作成</button></p>
    <div class="actions"><button class="primary" @click="close()">閉じる</button></div>
  </Modal>
</template>

<style scoped>
h3 { margin-top: 1.2rem; }
.found { display: flex; flex-wrap: wrap; gap: .35rem; margin: .4rem 0; max-height: 9rem; overflow-y: auto; }
.cand { border-radius: 999px; font-size: .84rem; padding: .2rem .7rem; }
.item { display: flex; align-items: center; gap: .5rem; padding: .45rem 0; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
.link { border: 1px solid var(--line); border-radius: 9px; padding: .6rem; margin-bottom: .6rem; }
.url { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .8rem; }
.opts { margin-top: .4rem; }
.chips .chip { font-size: .78rem; padding: .15rem .6rem; }
</style>
