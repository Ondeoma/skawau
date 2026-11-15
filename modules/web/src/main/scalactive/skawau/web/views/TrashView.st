<script lang="scala">
import skawau.web.*
import skawau.shared.*

// ごみ箱（自分が所有するもの. ほかの人が共有のフォルダの中で消したものも入る）.
case class State(items: RV[List[TrashItemDto]] = RV(Nil), loaded: RV[Boolean] = RV(false))

def initState() = State()

def load(s: State): Unit = Store.report(Api.trashList()) { xs => s.items := xs; s.loaded := true }

def mounted(s: State): Unit = {
  pageTitle("ごみ箱")
  load(s)
  watch(Store.tick, false)((_, _) => load(s))
}

def restore(t: TrashItemDto): Unit =
  Store.report(Api.restoreTrash(t.id)) { _ => Store.toast(s"「${t.name}」を元に戻しました"); Store.changed() }

def purge(t: TrashItemDto): Unit =
  Dialog.confirm(s"「${t.name}」を完全に削除しますか？元に戻せません。", "完全に削除") {
    Store.report(Api.purgeTrash(t.id)) { _ => Store.changed() }
  }

def empty(): Unit =
  Dialog.confirm("ごみ箱を空にしますか？中のものはすべて完全に削除され、元に戻せません。", "ごみ箱を空にする") {
    Store.report(Api.emptyTrash()) { _ => Store.toast("ごみ箱を空にしました"); Store.changed() }
  }

def icon(t: TrashItemDto): String = FileKind.icon(FileKind.kind(t.name, t.isDir))
def days = Store.config.mapC(_.map(c => s"ごみ箱のものは ${c.trashDays} 日たつと自動で完全に削除されます。").getOrElse(""))
def total(s: State) = s.items.mapC(xs => if (xs.isEmpty) "" else s"${xs.size}件・${Fmt.bytes(xs.map(_.size).sum)}")
</script>

<template>
  <div class="page wide">
    <div class="row"><h1 class="grow">ごみ箱</h1>
      <span class="muted small">{{ total(s) }}</span>
      <button v-if="s.items.mapC(_.nonEmpty)" class="danger" @click="empty()">ごみ箱を空にする</button></div>
    <p class="muted small">{{ days }}</p>
    <div v-if="s.loaded" class="table-wrap">
      <table class="list">
        <thead><tr><th>名前</th><th class="hide-sp">元の場所</th><th class="hide-sp">削除した人</th><th>削除</th><th></th></tr></thead>
        <tbody>
          <tr v-for="t in s.items" :key="t.id">
            <td><div class="fname"><span class="ficon">{{ icon(t) }}</span><span class="nm">{{ t.name }}</span>
              <span class="muted small">{{ Fmt.bytes(t.size) }}</span></div></td>
            <td class="small muted hide-sp">{{ t.originalPath }}</td>
            <td class="small muted hide-sp">{{ t.deletedBy.map(_.name).getOrElse("") }}</td>
            <td class="small muted nowrap">{{ Fmt.ago(t.deletedAt) }}</td>
            <td class="nowrap"><button class="small" @click="restore(t)">元に戻す</button>
              <button class="small danger" @click="purge(t)">完全に削除</button></td>
          </tr>
          <tr v-if="s.items.mapC(_.isEmpty)"><td colspan="5" class="empty">ごみ箱は空です</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
