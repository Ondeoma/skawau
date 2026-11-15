<script lang="scala">
import skawau.web.*
import skawau.shared.*
import skawau.web.parts.*

// アクティビティ: 自分のファイルと、共有されたものの中の変更.
case class State(events: RV[List[EventDto]] = RV(Nil), more: RV[Boolean] = RV(false), kind: RV[String] = RV(""))

def initState() = State()

def load(s: State): Unit = Store.report(Api.activity(None, 100)) { es => s.events := es; s.more := es.size == 100 }

def loadMore(s: State): Unit = s.events.v.lastOption.foreach { last =>
  Store.report(Api.activity(Some(last.id), 100)) { es => s.events := s.events.v ++ es; s.more := es.size == 100 }
}

def mounted(s: State): Unit = {
  pageTitle("アクティビティ")
  load(s)
  watch(Store.tick, false)((_, _) => load(s))
}

def who(e: EventDto): String = e.actor.map(_.name).getOrElse(e.via match {
  case "link"   => "公開リンクの利用者"
  case "office" => "ONLYOFFICE"
  case _        => "システム"
})

def via(e: EventDto): String = e.via match {
  case "dav"    => "WebDAV"
  case "api"    => "API"
  case "mcp"    => "MCP（AI）"
  case "office" => "ONLYOFFICE"
  case "link"   => "公開リンク"
  case _        => ""
}

def icon(e: EventDto): String = FileKind.icon(FileKind.kind(e.name))

def from(e: EventDto): String =
  e.detail.hcursor.get[String]("fromPath").toOption.orElse(e.detail.hcursor.get[String]("from").toOption).fold("")(f => s"（元: $f）")

def filtered(s: State) = computed { val k = s.kind.v; s.events.v.filter(e => k.isEmpty || e.kind == k) }

def chip(s: State, k: String) = Ui.active(s.kind, k, "chip")

def gone(e: EventDto): Boolean = e.kind == EventKind.Deleted || e.kind == EventKind.Trashed
def openEvent(e: EventDto): Unit = if (!gone(e)) Router.go(s"/view/${e.nodeId}")
</script>

<template>
  <div class="page narrow">
    <h1>アクティビティ</h1>
    <div class="chips filter">
      <button :class='chip(s, "")' @click='s.kind := ""'>すべて</button>
      <button :class="chip(s, EventKind.Uploaded)" @click="s.kind := EventKind.Uploaded">アップロード</button>
      <button :class="chip(s, EventKind.Updated)" @click="s.kind := EventKind.Updated">更新</button>
      <button :class="chip(s, EventKind.Shared)" @click="s.kind := EventKind.Shared">共有</button>
      <button :class="chip(s, EventKind.Trashed)" @click="s.kind := EventKind.Trashed">削除</button>
    </div>
    <div class="feed">
      <div v-for="e in filtered(s)" :key="e.id" class="ev">
        <Avatar :user="e.actor" :size="30" />
        <div class="grow">
          <div><b>{{ who(e) }}</b> さんが <span class="clickable" @click="openEvent(e)">{{ icon(e) }} {{ e.name }}</span> を{{ EventKind.label(e.kind) }}しました
            <span class="muted small">{{ from(e) }}</span></div>
          <div class="muted small">{{ Fmt.ago(e.at) }} <span v-if="via(e).nonEmpty">・{{ via(e) }}</span> <span v-if="e.path.nonEmpty">・{{ e.path }}</span></div>
        </div>
      </div>
      <p v-if="filtered(s).mapC(_.isEmpty)" class="empty">アクティビティはありません</p>
    </div>
    <p v-if="s.more"><button @click="loadMore(s)">もっと見る</button></p>
  </div>
</template>

<style scoped>
.filter { margin-bottom: 1rem; }
.ev { display: flex; gap: .7rem; padding: .7rem 0; border-bottom: 1px solid var(--line); align-items: flex-start; }
.clickable { cursor: pointer; color: var(--accent); }
</style>
