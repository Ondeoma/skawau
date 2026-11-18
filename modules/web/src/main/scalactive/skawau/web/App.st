<script lang="scala">
import io.github.ondeoma.scalactive.components.routes.RouterComponent
import org.scalajs.dom
import skawau.shared.*
import skawau.web.parts.*
import skawau.web.views.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 画面全体の枠: ヘッダー（検索・通知・メニュー）、左のメニュー（ファイル・最近・共有・ごみ箱…）、ルートごとの画面.
// 公開リンク（/s/…）と Office のエディタ（/office/…）は枠なしの全画面.
case class State(ready: RV[Boolean] = RV(false),
                 menu: RV[Boolean] = RV(false),
                 q: RV[String] = RV(""))

def initState() = State()

def uuid(s: String): Option[UUID] = scala.util.Try(UUID.fromString(s)).toOption

val routes: PathPatternRoutes = List(
  ("/", _ => FilesView(folderId = None)),
  ("/files", _ => FilesView(folderId = None)),
  ("/files/:id", r => FilesView(folderId = uuid(r.pathname.groups("id")))),
  ("/view/:id", r => ViewerPage(fileId = r.pathname.groups("id"))),
  ("/office/:id", r => OfficeView(fileId = r.pathname.groups("id"), token = None)),
  ("/recent", _ => HitsView(mode = "recent")),
  ("/favorites", _ => HitsView(mode = "favorites")),
  ("/shared", _ => HitsView(mode = "shared")),
  ("/search", _ => HitsView(mode = "search")),
  ("/shared-by-me", _ => SharedByMeView()),
  ("/trash", _ => TrashView()),
  ("/activity", _ => ActivityView()),
  ("/notifications", _ => NotificationView()),
  ("/settings", _ => SettingsView()),
  ("/admin", _ => AdminView()),
  ("/login", _ => LoginView()),
  ("/s/:token/office/:id", r => OfficeView(fileId = r.pathname.groups("id"), token = Some(r.pathname.groups("token")))),
  ("/s/:token", r => PublicView(token = r.pathname.groups("token"))),
)

def path(h: String): String = new dom.URL(h).pathname

/** ヘッダーと左のメニューを出さない画面（公開リンク・Office のエディタ・ログイン）. */
def bare = Router.href.mapC { h => val p = path(h); p.startsWith("/s/") || p.startsWith("/office/") || p.startsWith("/login") }
def framed = bare.mapC(!_)

def mounted(s: State): Unit = {
  Fallback.install(routes)
  Store.report(Api.clientConfig())(c => Store.config := Some(c))
  val p = dom.window.location.pathname
  if (p.startsWith("/login") || p.startsWith("/s/")) s.ready := true
  else Store.report(Api.me()) { me =>
    Store.me := Some(me)
    Observe.setUser(me.id.toString)
    Store.refresh()
    Store.connect()
    s.ready := true
  }
}

def search(s: State): Unit = {
  Store.sideOpen := false
  Router.go("/search?q=" + Api.enc(s.q.v.trim))
}

def logout(): Unit = Api.logout().onComplete(r => dom.window.location.href = r.getOrElse("/"))

/** 左のメニューの項目（今いる場所なら強調）. */
def navClass(prefix: String) = Router.href.mapC { h =>
  val p = path(h)
  val on = if (prefix == "/files") p == "/" || p == "/files" || p.startsWith("/files/") else p.startsWith(prefix)
  if (on) "active" else ""
}

def userName = Store.me.mapC(_.map(_.name).getOrElse(""))
def meBrief = Store.me.mapC(_.map(m => UserBrief(m.id, m.name, m.avatarUrl)))
def unreadLabel = Store.unread.mapC(u => if (u.unread > 99) "99+" else u.unread.toString)
def hasUnread = Store.unread.mapC(_.unread > 0)
def loggedIn = Store.me.mapC(_.isDefined)
def quotaText = Store.me.mapC(_.map(m => Fmt.quota(m.quota)).getOrElse(""))
def quotaStyle = Store.me.mapC(_.map(m => s"width: ${math.max(2, Fmt.quotaPercent(m.quota))}%").getOrElse("width: 0"))
def quotaClass = Store.me.mapC(_.map(m => Fmt.quotaPercent(m.quota)).map(p => if (p >= 95) "bar full" else if (p >= 80) "bar warn" else "bar").getOrElse("bar"))
def hasLimit = Store.me.mapC(_.exists(_.quota.limit.isDefined))
def sideClass = Store.sideOpen.mapC(o => if (o) "side open" else "side")
</script>

<template>
  <header v-if="framed" class="top">
    <button class="ghost only-sp burger" @click="Store.sideOpen := !Store.sideOpen.v">☰</button>
    <RouterLink to="/files" class="logo"><span class="brand"><img src="/logo.svg" alt="" width="28" height="28">skawau</span></RouterLink>
    <form v-if="loggedIn" class="search hide-sp" @submit.prevent="search(s)">
      <input type="search" v-model="s.q" placeholder="ファイル・フォルダを検索">
    </form>
    <div class="spacer"></div>
    <RouterLink v-if="loggedIn" to="/notifications" class="icon bell">
      🔔<span v-if="hasUnread" class="dot">{{ unreadLabel }}</span>
    </RouterLink>
    <div v-if="loggedIn" class="me">
      <button class="ghost me-btn" @click="s.menu := !s.menu.v"><Avatar :user="meBrief.v" :size="24" /><span class="hide-sp">{{ userName }}</span> ▾</button>
      <div v-if="s.menu" class="menu-bg" @click="s.menu := false"></div>
      <div v-if="s.menu" class="menu" @click="s.menu := false">
        <RouterLink to="/settings">個人設定・WebDAV</RouterLink>
        <RouterLink to="/notifications">通知</RouterLink>
        <RouterLink v-if="Store.isAdmin" to="/admin">管理</RouterLink>
        <a href="#" @click.prevent="logout()">ログアウト</a>
      </div>
    </div>
  </header>
  <div v-if="framed" class="shell">
    <nav :class="sideClass" @click="Store.sideOpen := false">
      <RouterLink to="/files" :class='navClass("/files")'><span class="ic">📁</span>すべてのファイル</RouterLink>
      <RouterLink to="/search" :class='navClass("/search").mapC(_ + " only-sp")'><span class="ic">🔍</span>検索</RouterLink>
      <RouterLink to="/recent" :class='navClass("/recent")'><span class="ic">🕘</span>最近</RouterLink>
      <RouterLink to="/favorites" :class='navClass("/favorites")'><span class="ic">⭐</span>お気に入り</RouterLink>
      <div class="sep"></div>
      <RouterLink to="/shared" :class='navClass("/shared")'><span class="ic">👥</span>共有されたもの</RouterLink>
      <RouterLink to="/shared-by-me" :class='navClass("/shared-by-me")'><span class="ic">🔗</span>共有したもの</RouterLink>
      <div class="sep"></div>
      <RouterLink to="/activity" :class='navClass("/activity")'><span class="ic">📰</span>アクティビティ</RouterLink>
      <RouterLink to="/trash" :class='navClass("/trash")'><span class="ic">🗑️</span>ごみ箱</RouterLink>
      <div v-if="loggedIn" class="quota">
        <div>容量: {{ quotaText }}</div>
        <div v-if="hasLimit" :class="quotaClass"><span :style="quotaStyle"></span></div>
      </div>
    </nav>
    <main class="main">
      <RouterComponent v-if="s.ready" :routes="routes" />
    </main>
  </div>
  <RouterComponent v-if="bare" :routes="routes" />
  <ShareDialog v-if="Store.shareFor.mapC(_.isDefined)" />
  <MoveDialog v-if="Store.moveReq.mapC(_.isDefined)" />
  <Preview v-if="Store.preview.mapC(_.isDefined)" />
  <UploadPanel />
  <DialogHost />
  <div class="toasts">
    <div v-for="t in Store.toasts" :key="t.id" :class='if (t.error) "toast error" else "toast"'>
      <span class="grow" @click="t.link.foreach(l => Router.go(l)); Store.dismiss(t.id)">{{ t.text }}</span>
      <button @click="Store.dismiss(t.id)">×</button>
    </div>
  </div>
</template>

<style scoped>
.top { position: sticky; top: 0; z-index: 100; height: var(--header); display: flex; align-items: center; gap: .7rem; padding: 0 .9rem;
  background: var(--bg); border-bottom: 1px solid var(--line); }
.brand { font-weight: 800; font-size: 1.15rem; color: var(--accent); letter-spacing: .02em; display: inline-flex; align-items: center; gap: .45rem;
  white-space: nowrap; }
.brand img { display: block; border-radius: 7px; }
.burger { font-size: 1.2rem; padding: .2rem .5rem; }
.search { flex: 0 1 460px; min-width: 0; margin-left: 1.2rem; }
.search input { width: 100%; }
.spacer { flex: 1; }
.icon { font-size: 1.1rem; padding: .2rem .4rem; border-radius: 7px; color: var(--fg); position: relative; }
.icon:hover { background: var(--hover); text-decoration: none; }
.dot { position: absolute; top: -4px; right: -6px; background: var(--danger); color: #fff; border-radius: 999px; font-size: .62rem; padding: 0 .35rem; line-height: 1.4; }
.me { position: relative; }
.menu { position: absolute; right: 0; top: 2.4rem; background: var(--bg); border: 1px solid var(--line); border-radius: 9px; box-shadow: var(--shadow);
  min-width: 200px; padding: .3rem; display: flex; flex-direction: column; z-index: 200; }
.menu-bg { position: fixed; inset: 0; z-index: 199; }
.main { min-width: 0; }
@media (max-width: 720px) { .search { margin-left: 0; } .brand { font-size: 1rem; } }
</style>
