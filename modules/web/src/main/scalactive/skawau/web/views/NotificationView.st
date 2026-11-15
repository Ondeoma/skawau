<script lang="scala">
import skawau.web.*
import skawau.shared.*
import skawau.web.parts.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 通知（共有された・公開リンクからアップロードされた など）.
case class State(items: RV[List[NotificationDto]] = RV(Nil), unread: RV[Boolean] = RV(false))

def initState() = State()

def load(s: State): Unit = Store.report(Api.notifications(s.unread.v))(s.items := _)

def mounted(s: State): Unit = {
  pageTitle("通知")
  load(s)
  watch(Store.unread, false)((_, _) => load(s))
}

def open(n: NotificationDto): Unit = {
  if (!n.read) Api.markRead(List(n.id)).foreach(_ => Store.refresh())
  n.link.foreach(l => Router.go(l))
}

def readAll(s: State): Unit = Store.report(Api.markRead(Nil)) { _ => Store.refresh(); load(s) }

def toggle(s: State, on: Boolean): Unit = { s.unread := on; load(s) }
def chip(s: State, on: Boolean) = Ui.active(s.unread, on, "chip")
def cls(n: NotificationDto): String = if (n.read) "item" else "item unread"
</script>

<template>
  <div class="page narrow">
    <div class="row"><h1 class="grow">通知</h1><button class="small" @click="readAll(s)">すべて既読にする</button></div>
    <div class="chips filter">
      <button :class="chip(s, false)" @click="toggle(s, false)">すべて</button>
      <button :class="chip(s, true)" @click="toggle(s, true)">未読</button>
    </div>
    <div v-for="n in s.items" :key="n.id" :class="cls(n)" @click="open(n)">
      <Avatar :user="n.actor" :size="30" />
      <div class="grow"><div>{{ n.title }}</div><div class="muted small">{{ n.body }}・{{ Fmt.ago(n.at) }}</div></div>
    </div>
    <p v-if="s.items.mapC(_.isEmpty)" class="empty">通知はありません</p>
  </div>
</template>

<style scoped>
.filter { margin-bottom: 1rem; }
.item { display: flex; gap: .7rem; padding: .7rem .5rem; border-bottom: 1px solid var(--line); cursor: pointer; border-radius: 6px; }
.item:hover { background: var(--hover); }
.unread { background: var(--accent-weak); }
</style>
