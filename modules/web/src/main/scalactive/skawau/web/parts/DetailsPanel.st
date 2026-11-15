<script lang="scala">
import skawau.web.*
import skawau.shared.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 詳細のパネル（一覧の右）: 情報・共有・版・履歴. node が変わったら読み直す.
case class Props(node: RV[Option[NodeDto]], onClose: Unit => Unit)

case class State(tab: RV[String] = RV("info"),
                 versions: RV[List[VersionDto]] = RV(Nil),
                 events: RV[List[EventDto]] = RV(Nil),
                 shares: RV[List[ShareDto]] = RV(Nil),
                 loadedFor: RV[Option[(UUID, Int)]] = RV(None))

def initState() = State()

def load(s: State, props: Props): Unit = props.node.v.foreach { n =>
  s.loadedFor := Some((n.id, n.version))
  if (!n.isDir) Api.versions(n.id).foreach(s.versions := _) else s.versions := Nil
  Api.nodeActivity(n.id).foreach(s.events := _)
  if (n.perm == Perm.Owner) Api.shares(n.id).foreach(s.shares := _) else s.shares := Nil
}

def mounted(s: State, props: Props): Unit = {
  load(s, props)
  watch(props.node, false)((a, b) => if (a.map(x => (x.id, x.version, x.shared, x.hasLink)) != b.map(x => (x.id, x.version, x.shared, x.hasLink))) load(s, props))
  watch(Store.tick, false)((_, _) => load(s, props))
}

def isFile(props: Props) = props.node.mapC(_.exists(!_.isDir))
def isOwner(props: Props) = props.node.mapC(_.exists(_.perm == Perm.Owner))
def canEdit(props: Props) = props.node.mapC(_.exists(x => Perm.canEdit(x.perm)))
def tabClass(s: State, t: String) = Ui.active(s.tab, t)
def isTab(s: State, t: String) = s.tab.mapC(_ == t)
def name(props: Props) = props.node.mapC(_.map(_.name).getOrElse(""))
def icon(props: Props) = props.node.mapC(_.map(FileOps.icon).getOrElse(""))
def kindLabel(props: Props) = props.node.mapC(_.map { x =>
  if (x.isDir) "フォルダ" else FileKind.ext(x.name).toUpperCase match { case "" => "ファイル"; case e => s"$e ファイル" }
}.getOrElse(""))

/** 詳細の項目（名前 → 値）. */
def facts(props: Props) = props.node.mapC(_.toList.flatMap { x =>
  List(
    Option.when(!x.isDir)("サイズ" -> Fmt.bytes(x.size)),
    Some("更新" -> s"${Fmt.dateTime(x.updatedAt)}${x.modifiedBy.fold("")(u => s"（${u.name}）")}"),
    Some("作成" -> Fmt.dateTime(x.createdAt)),
    Some("所有者" -> x.owner.name),
    Some("あなたの権限" -> Perm.label(x.perm)),
    Option.when(!x.isDir)("版" -> s"${x.version}")
  ).flatten
})

def restore(props: Props, v: VersionDto): Unit = props.node.v.foreach { x =>
  Dialog.confirm(s"版 ${v.version}（${Fmt.dateTime(v.createdAt)}）に戻しますか？\n今の内容は新しい版として残ります。", "この版に戻す", danger = false) {
    Store.report(Api.restoreVersion(x.id, v.id)) { _ => Store.toast(s"版 ${v.version} に戻しました"); Store.changed() }
  }
}

def versionUrl(props: Props, v: VersionDto): String = props.node.v.fold("")(x =>
  if (v.current) Api.contentUrl(x.id, download = true) else s"/api/files/${x.id}/versions/${v.id}/content")

def shareLabel(sh: ShareDto): String = sh.kind match {
  case ShareKind.User  => s"👤 ${sh.user.map(_.name).getOrElse("")}"
  case ShareKind.Group => s"👥 ${sh.group.map(_.name).getOrElse("")}"
  case _               => s"🔗 公開リンク${sh.label.fold("")(l => s"（$l）")}"
}

def eventText(e: EventDto): String = s"${e.actor.map(_.name).getOrElse(e.via match { case "link" => "公開リンク"; case "office" => "ONLYOFFICE"; case _ => "システム" })}: ${EventKind.label(e.kind)}"

def openIt(props: Props): Unit = props.node.v.foreach(x => FileOps.open(x, List(x)))
def share(props: Props): Unit = Store.shareFor := props.node.v
</script>

<template>
  <aside class="panel details">
    <div class="row top">
      <span class="big">{{ icon(props) }}</span>
      <div class="grow"><b class="nm">{{ name(props) }}</b><div class="muted small">{{ kindLabel(props) }}</div></div>
      <button class="ghost small" @click="props.onClose(())">✕</button>
    </div>
    <div class="row actions">
      <button class="small" @click="openIt(props)">開く</button>
      <button class="small" @click="props.node.v.foreach(x => FileOps.downloadMany(List(x)))">ダウンロード</button>
      <button v-if="isOwner(props)" class="small" @click="share(props)">共有…</button>
    </div>
    <nav class="tabs">
      <button :class='tabClass(s, "info")' @click='s.tab := "info"'>詳細</button>
      <button v-if="isOwner(props)" :class='tabClass(s, "shares")' @click='s.tab := "shares"'>共有</button>
      <button v-if="isFile(props)" :class='tabClass(s, "versions")' @click='s.tab := "versions"'>版</button>
      <button :class='tabClass(s, "events")' @click='s.tab := "events"'>履歴</button>
    </nav>
    <div v-if='isTab(s, "info")'>
      <dl class="facts">
        <template v-for="f in facts(props)"><dt>{{ f._1 }}</dt><dd>{{ f._2 }}</dd></template>
      </dl>
    </div>
    <div v-if='isTab(s, "shares")'>
      <p v-if="s.shares.mapC(_.isEmpty)" class="muted small">まだ誰とも共有していません。</p>
      <div v-for="sh in s.shares" :key="sh.id" class="item">
        <div class="grow">{{ shareLabel(sh) }}</div>
        <span class="muted small">{{ Perm.label(sh.permission) }}</span>
      </div>
      <p><button class="small primary" @click="share(props)">共有の設定…</button></p>
    </div>
    <div v-if='isTab(s, "versions")'>
      <p class="muted small">上書きするたびに以前の内容が版として残ります。</p>
      <div v-for="v in s.versions" :key="v.id" class="item">
        <div class="grow">
          <b>版 {{ v.version }}</b><span v-if="v.current" class="badge">今の版</span>
          <div class="muted small">{{ Fmt.dateTime(v.createdAt) }}・{{ v.author.map(_.name).getOrElse("") }}・{{ Fmt.bytes(v.size) }}</div>
        </div>
        <a class="btn small" :href="versionUrl(props, v)" download>⬇️</a>
        <button v-if="!v.current && canEdit(props).v" class="small" @click="restore(props, v)">戻す</button>
      </div>
    </div>
    <div v-if='isTab(s, "events")'>
      <div v-for="e in s.events" :key="e.id" class="item">
        <div class="grow">
          <div class="small">{{ eventText(e) }}</div>
          <div class="muted small">{{ e.name }}・{{ Fmt.ago(e.at) }}</div>
        </div>
      </div>
      <p v-if="s.events.mapC(_.isEmpty)" class="muted small">履歴はありません。</p>
    </div>
  </aside>
</template>

<style scoped>
.details { align-self: start; position: sticky; top: calc(var(--header) + 1rem); max-height: calc(100vh - var(--header) - 2rem); overflow-y: auto; }
.top { align-items: flex-start; }
.big { font-size: 2rem; }
.nm { word-break: break-all; }
.actions { margin: .6rem 0; }
.facts { display: grid; grid-template-columns: auto 1fr; gap: .35rem .8rem; font-size: .86rem; margin: 0; }
.facts dt { color: var(--muted); }
.facts dd { margin: 0; word-break: break-word; }
.item { display: flex; align-items: center; gap: .5rem; padding: .45rem 0; border-bottom: 1px solid var(--line); }
.badge { margin-left: .4rem; }
</style>
