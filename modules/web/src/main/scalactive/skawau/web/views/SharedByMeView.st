<script lang="scala">
import skawau.web.*
import skawau.shared.*

// 自分が共有したもの（ユーザー・グループへの共有と公開リンク）.
case class State(items: RV[List[SharedByMeDto]] = RV(Nil), loaded: RV[Boolean] = RV(false))

def initState() = State()

def load(s: State): Unit = Toasts.report(Api.sharedByMe()) { xs => s.items := xs; s.loaded := true }

def mounted(s: State): Unit = {
  pageTitle("共有したもの")
  load(s)
  watch(Store.tick, false)((_, _) => load(s))
}

def target(sh: ShareDto): String = sh.kind match {
  case ShareKind.User  => s"👤 ${sh.user.map(_.name).getOrElse("")}"
  case ShareKind.Group => s"👥 ${sh.group.map(_.name).getOrElse("")}"
  case _               => s"🔗 公開リンク${sh.label.fold("")(l => s"（$l）")}${if (sh.hasPassword) " 🔒" else ""}"
}

def expiry(sh: ShareDto): String = sh.expiresAt.fold("")(d => s"〜$d")
</script>

<template>
  <div class="page wide">
    <h1>共有したもの</h1>
    <div v-if="s.loaded" class="table-wrap">
      <table class="list">
        <thead><tr><th>名前</th><th class="hide-sp">場所</th><th>相手</th><th>権限</th><th class="hide-sp">期限</th><th></th></tr></thead>
        <tbody>
          <tr v-for="x in s.items" :key="x.share.id">
            <td><div class="fname"><span class="ficon">{{ FileOps.icon(x.node) }}</span>
              <a href="#" class="nm" @click.prevent="FileOps.open(x.node, List(x.node))">{{ x.node.name }}</a></div></td>
            <td class="small muted hide-sp">{{ x.path }}</td>
            <td class="small">{{ target(x.share) }}</td>
            <td class="small">{{ Perm.label(x.share.permission) }}</td>
            <td class="small muted hide-sp">{{ expiry(x.share) }}</td>
            <td class="nowrap">
              <button v-if="x.share.url.isDefined" class="small" @click='Dom.copy(x.share.url.getOrElse(""), "リンクをコピーしました")'>コピー</button>
              <button class="small" @click="Store.shareFor := Some(x.node)">設定…</button>
            </td>
          </tr>
          <tr v-if="s.items.mapC(_.isEmpty)"><td colspan="6" class="empty">共有しているものはありません。一覧の ⋯ から「共有…」を選ぶと共有できます</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
