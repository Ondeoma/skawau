<script lang="scala">
import skawau.web.*
import skawau.shared.*
import skawau.web.parts.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 場所つきの一覧: 最近（recent）・お気に入り（favorites）・共有されたもの（shared）・検索（search、?q=）.
case class Props(mode: String)

case class State(hits: RV[List[NodeHitDto]] = RV(Nil),
                 loaded: RV[Boolean] = RV(false),
                 selected: RV[Set[UUID]] = RV(Set.empty),
                 q: RV[String] = RV(""))

def initState() = State(q = RV(Router.query.v.get("q").getOrElse("")))

def title(props: Props): String = props.mode match {
  case "recent"    => "最近のファイル"
  case "favorites" => "お気に入り"
  case "shared"    => "共有されたもの"
  case _           => "検索"
}

def fetch(s: State, props: Props): Option[Future[List[NodeHitDto]]] = props.mode match {
  case "recent"    => Some(Api.recent())
  case "favorites" => Some(Api.favorites())
  case "shared"    => Some(Api.sharedWithMe())
  case _           => Option(s.q.v.trim).filter(_.nonEmpty).map(Api.search)
}

def load(s: State, props: Props): Unit = fetch(s, props) match {
  case None => s.hits := Nil; s.loaded := true
  case Some(f) => f.onComplete {
    case Success(hs) => s.hits := hs; s.loaded := true
    case Failure(e)  => Toasts.error(e)
  }
}

def mounted(s: State, props: Props): Unit = {
  pageTitle(title(props))
  load(s, props)
  watch(Store.tick, false)((_, _) => load(s, props))
}

def rows(s: State, props: Props) = s.hits.mapC(_.map(h => Row(h.node, Option.when(props.mode != "shared")(h.path))))
def nodes(s: State): List[NodeDto] = s.hits.v.map(_.node)
def open(s: State, n: NodeDto): Unit = FileOps.open(n, nodes(s))
/** 「フォルダを開く」は場所が分かる一覧だけ（共有されたものの起点の親は見られないため）. */
def actions(s: State, props: Props)(n: NodeDto): List[MenuItem] =
  FileOps.actions(n, () => open(s, n)) ++
    n.parentId.filter(_ => props.mode != "shared").map(p => MenuItem("フォルダを開く", () => Router.go(s"/files/$p"), sep = true)).toList

def search(s: State): Unit = Router.go("/search?q=" + Api.enc(s.q.v.trim))
def selected(s: State): List[NodeDto] = nodes(s).filter(n => s.selected.v.contains(n.id))
def hasSel(s: State) = s.selected.mapC(_.nonEmpty)

def empty(props: Props): String = props.mode match {
  case "recent"    => "最近更新したファイルはありません"
  case "favorites" => "お気に入りはありません。一覧の ⋯ から「お気に入りに追加」できます"
  case "shared"    => "共有されたものはありません"
  case _           => "見つかりませんでした"
}
</script>

<template>
  <div class="page wide">
    <h1>{{ title(props) }}</h1>
    <form v-if='props.mode == "search"' class="row" @submit.prevent="search(s)">
      <input type="search" class="grow q" v-model="s.q" placeholder="名前で検索（ひらがな・カタカナ、全角・半角の違いは区別しません）" v-focus>
      <button type="submit" class="primary">検索</button>
    </form>
    <div class="toolbar">
      <div v-if="hasSel(s)" class="selbar">
        <b class="small">{{ s.selected.mapC(_.size) }} 件を選択</b>
        <button class="small" @click="FileOps.downloadMany(selected(s))">⬇️ ダウンロード</button>
        <button class="small" @click="Store.moveReq := Some(MoveReqUi(selected(s), true, None))">コピー</button>
        <button class="small ghost" @click="s.selected := Set.empty">選択を解除</button>
      </div>
      <span class="grow"></span>
      <ViewToggle />
    </div>
    <FileTable v-if="s.loaded" :rows="rows(s, props)" :selected="s.selected" :grid="Layout.gridC" :onOpen="n => open(s, n)"
      :actions="actions(s, props)" :showPath='props.mode != "shared"' :thumb="FileOps.thumb" :empty="empty(props)" />
  </div>
</template>

<style scoped>
.q { max-width: 640px; }
</style>
