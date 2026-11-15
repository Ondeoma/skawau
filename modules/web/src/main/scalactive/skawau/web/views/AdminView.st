<script lang="scala">
import skawau.web.*
import skawau.shared.*
import skawau.web.parts.*

// 管理（システム管理者）: ユーザー（管理者・無効・容量の上限）、全体の使用量、監査ログ.
// 管理者でもほかの人のファイルの中身は見られない（共有されたものだけ）.
case class State(tab: RV[String] = RV("users"),
                 users: RV[List[AdminUserDto]] = RV(Nil),
                 q: RV[String] = RV(""),
                 stats: RV[Option[StatsDto]] = RV(None),
                 audit: RV[List[AuditDto]] = RV(Nil))

def initState() = State()

def load(s: State): Unit = {
  Store.report(Api.adminUsers(s.q.v))(s.users := _)
  Store.report(Api.stats())(x => s.stats := Some(x))
  Store.report(Api.audit())(s.audit := _)
}

def mounted(s: State): Unit = { pageTitle("管理"); load(s) }

def update(s: State, u: AdminUserDto, r: UpdateUserReq): Unit =
  Store.report(Api.updateUser(u.id, r))(_ => load(s))

def setQuota(s: State, u: AdminUserDto): Unit =
  Dialog.prompt(s"${u.name} さんの容量の上限（GB。空にすると既定の上限）", u.quota.fold("")(q => f"${q / 1024.0 / 1024 / 1024}%.1f"), "保存") { v =>
    val mb = v.trim.toDoubleOption.map(gb => (gb * 1024).toLong).getOrElse(-1L)
    update(s, u, UpdateUserReq(None, None, Some(mb)))
  }

def tab(s: State, v: String) = Ui.active(s.tab, v)
def isTab(s: State, v: String) = s.tab.mapC(_ == v)
def me(u: AdminUserDto): Boolean = Store.myId.contains(u.id)
def usage(u: AdminUserDto): String = u.quota.fold(Fmt.bytes(u.used))(q => s"${Fmt.bytes(u.used)} / ${Fmt.bytes(q)}")
def facts(s: State) = s.stats.mapC(_.toList.flatMap(x => List(
  "ユーザー" -> x.users.toString, "ファイル" -> x.files.toString, "フォルダ" -> x.folders.toString,
  "ファイルの合計" -> Fmt.bytes(x.bytes), "以前の版" -> Fmt.bytes(x.versionBytes), "ごみ箱" -> Fmt.bytes(x.trashBytes),
  "共有" -> x.shares.toString, "公開リンク" -> x.links.toString)))
</script>

<template>
  <div class="page">
    <h1>管理</h1>
    <nav class="tabs">
      <button :class='tab(s, "users")' @click='s.tab := "users"'>ユーザー</button>
      <button :class='tab(s, "stats")' @click='s.tab := "stats"'>使用量</button>
      <button :class='tab(s, "audit")' @click='s.tab := "audit"'>監査ログ</button>
    </nav>
    <div v-if='isTab(s, "users")'>
      <form class="row" @submit.prevent="load(s)"><input type="search" v-model="s.q" placeholder="名前・メールで絞り込み"><button type="submit">検索</button></form>
      <p class="muted small">ユーザーは初めてログインしたときに作られます。IdP のグループ（ADMIN_GROUPS）に入っている人は、ここでの設定によらず管理者です。</p>
      <div class="table-wrap">
        <table class="list">
          <thead><tr><th>名前</th><th class="hide-sp">メール</th><th class="hide-sp">グループ</th><th>使用量</th><th class="hide-sp">最終ログイン</th><th>管理者</th><th>無効</th></tr></thead>
          <tbody>
            <tr v-for="u in s.users" :key="u.id">
              <td><Avatar :user="Some(UserBrief(u.id, u.name, u.avatarUrl))" :size="22" /> {{ u.name }}</td>
              <td class="small hide-sp">{{ u.email.getOrElse("") }}</td>
              <td class="small hide-sp">{{ u.groups.mkString(", ") }}</td>
              <td class="small nowrap">{{ usage(u) }} <button class="ghost small" title="容量の上限" @click="setQuota(s, u)">✏️</button></td>
              <td class="small hide-sp">{{ u.lastLoginAt.map(Fmt.ago).getOrElse("") }}</td>
              <td><input type="checkbox" :checked="u.isAdmin" :disabled="me(u)" @change="update(s, u, UpdateUserReq(Some(Dom.checked(event)), None, None))"></td>
              <td><input type="checkbox" :checked="u.disabled" :disabled="me(u)" @change="update(s, u, UpdateUserReq(None, Some(Dom.checked(event)), None))"></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div v-if='isTab(s, "stats")'>
      <div class="stats">
        <div v-for="f in facts(s)" class="card stat"><div class="muted small">{{ f._1 }}</div><div class="num">{{ f._2 }}</div></div>
      </div>
    </div>
    <div v-if='isTab(s, "audit")' class="table-wrap">
      <table class="list">
        <thead><tr><th>日時</th><th>操作者</th><th>経路</th><th>操作</th><th>対象</th><th>詳細</th></tr></thead>
        <tbody>
          <tr v-for="a in s.audit" :key="a.id">
            <td class="small nowrap">{{ Fmt.dateTime(a.at) }}</td>
            <td class="small">{{ a.actor.map(_.name).getOrElse("") }}</td>
            <td class="small">{{ a.via }}</td>
            <td><code class="small">{{ a.action }}</code></td>
            <td class="small">{{ a.target.getOrElse("").take(8) }}</td>
            <td class="small muted">{{ a.detail.noSpaces.take(120) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.stats { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: .8rem; }
.num { font-size: 1.4rem; font-weight: 700; margin-top: .2rem; }
</style>
