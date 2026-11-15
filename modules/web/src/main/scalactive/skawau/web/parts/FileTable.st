<script lang="scala">
import skawau.web.*
import org.scalajs.dom
import skawau.shared.*

import java.util.UUID

// ファイルの一覧（表・タイル）. 選択（チェック）、並べ替え、行のメニュー（⋯ または右クリック）、
// フォルダの行へのドロップ（onDrop: 落としたフォルダとイベント）、行のドラッグ（draggable）.
case class Props(rows: CRV[List[Row]],
                 selected: RV[Set[UUID]],
                 grid: CRV[Boolean],
                 onOpen: NodeDto => Unit,
                 actions: NodeDto => List[MenuItem],
                 showPath: Boolean = false,
                 draggable: Boolean = false,
                 onDrop: Option[(NodeDto, dom.Event) => Unit] = None,
                 thumb: NodeDto => Option[String] = _ => None,
                 empty: String = "ファイルはありません")

case class State(sort: RV[String] = RV(Prefs.get("sort", "name")),
                 asc: RV[Boolean] = RV(Prefs.get("sort.asc", "true") == "true"),
                 menu: RV[Option[(NodeDto, Double, Double)]] = RV(None),
                 over: RV[Option[UUID]] = RV(None))

def initState() = State()

/** 並べ替えた行（向きによらずフォルダが先）. */
def sorted(s: State, props: Props) = computed {
  val (dirs, files) = props.rows.v.partition(_.node.isDir)
  def order(xs: List[Row]): List[Row] = {
    val asc = s.sort.v match {
      case "size"    => xs.sortBy(_.node.size)
      case "updated" => xs.sortBy(_.node.updatedAt.toEpochMilli)
      case _         => xs.sortBy(_.node.name.toLowerCase)
    }
    if (s.asc.v) asc else asc.reverse
  }
  order(dirs) ++ order(files)
}

def sortBy(s: State, k: String): Unit = {
  if (s.sort.v == k) s.asc := !s.asc.v else { s.sort := k; s.asc := k == "name" }
  Prefs.set("sort", s.sort.v); Prefs.set("sort.asc", s.asc.v.toString)
}

def arrow(s: State, k: String) = computed { if (s.sort.v == k) (if (s.asc.v) " ▲" else " ▼") else "" }

def toggle(props: Props, id: UUID, on: Boolean): Unit =
  props.selected := (if (on) props.selected.v + id else props.selected.v - id)

def allChecked(props: Props) = computed { val rs = props.rows.v; rs.nonEmpty && rs.forall(r => props.selected.v.contains(r.node.id)) }

def toggleAll(props: Props, on: Boolean): Unit =
  props.selected := (if (on) props.rows.v.map(_.node.id).toSet else Set.empty)

def isSel(props: Props, id: UUID) = props.selected.mapC(_.contains(id))

def rowClass(s: State, props: Props, n: NodeDto) = computed {
  if (s.over.v.contains(n.id)) "clickable drop-over" else if (props.selected.v.contains(n.id)) "clickable selected" else "clickable"
}

def tileClass(props: Props, n: NodeDto) = props.selected.mapC(sel => if (sel.contains(n.id)) "tile selected" else "tile")

def openMenu(s: State, n: NodeDto, e: dom.Event): Unit = {
  e.preventDefault()
  e.stopPropagation()
  val m = e.asInstanceOf[dom.MouseEvent]
  val x = math.min(m.clientX, dom.window.innerWidth - 230)
  val y = math.min(m.clientY, dom.window.innerHeight - 380)
  s.menu := Some((n, math.max(8, x), math.max(8, y)))
}

def menuItems(s: State, props: Props) = s.menu.mapC(_.toList.flatMap(m => props.actions(m._1)))
def menuStyle(s: State) = s.menu.mapC(_.fold("display:none")((_, x, y) => s"left: ${x}px; top: ${y}px; position: fixed"))
def runItem(s: State, i: MenuItem): Unit = { s.menu := None; i.run() }

/** ドラッグ: 選んでいる行をまとめて動かす（選んでいない行なら、その行だけ）. */
def dragStart(props: Props, n: NodeDto, e: dom.Event): Unit = {
  val ids = if (props.selected.v.contains(n.id)) props.selected.v.toList else List(n.id)
  Drag.start(ids, e)
}

def dragOver(s: State, props: Props, n: NodeDto, e: dom.Event): Unit =
  if (n.isDir && props.onDrop.isDefined && (Drag.isInternal(e) || Dom.hasFiles(e))) {
    e.preventDefault()
    e.stopPropagation()
    if (!s.over.v.contains(n.id)) s.over := Some(n.id)
  }

def dragLeave(s: State, n: NodeDto): Unit = if (s.over.v.contains(n.id)) s.over := None

def drop(s: State, props: Props, n: NodeDto, e: dom.Event): Unit = {
  s.over := None
  if (n.isDir) props.onDrop.foreach { f => e.preventDefault(); e.stopPropagation(); f(n, e) }
}

def isEmpty(props: Props) = props.rows.mapC(_.isEmpty)
def isGrid(props: Props) = props.grid
def isList(props: Props) = props.grid.mapC(!_)
def drag(props: Props): String = if (props.draggable) "true" else "false"
def sizeOf(n: NodeDto): String = if (n.isDir) "" else Fmt.bytes(n.size)
def thumbOf(props: Props, n: NodeDto): String = props.thumb(n).getOrElse("")
def hasThumb(props: Props, n: NodeDto): Boolean = props.thumb(n).isDefined
def ownerNote(n: NodeDto): String = if (Store.myId.contains(n.owner.id)) "" else n.owner.name
</script>

<template>
  <div>
    <div v-if="isList(props)" class="table-wrap">
      <table class="list files">
        <thead>
          <tr>
            <th class="ck"><input type="checkbox" :checked="allChecked(props)" @change="toggleAll(props, Dom.checked(event))"></th>
            <th class="sortable" @click='sortBy(s, "name")'>名前{{ arrow(s, "name") }}</th>
            <th v-if="props.showPath" class="hide-sp">場所</th>
            <th class="sortable num hide-sp" @click='sortBy(s, "size")'>サイズ{{ arrow(s, "size") }}</th>
            <th class="sortable hide-sp" @click='sortBy(s, "updated")'>更新{{ arrow(s, "updated") }}</th>
            <th class="act"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in sorted(s, props)" :key="r.node.id" :class="rowClass(s, props, r.node)" :draggable="drag(props)"
              @dragstart="dragStart(props, r.node, event)" @dragover="dragOver(s, props, r.node, event)" @dragleave="dragLeave(s, r.node)"
              @drop="drop(s, props, r.node, event)" @contextmenu="openMenu(s, r.node, event)" @dblclick="props.onOpen(r.node)">
            <td class="ck" @click="Dom.stop(event)"><input type="checkbox" :checked="isSel(props, r.node.id)" @change="toggle(props, r.node.id, Dom.checked(event))"></td>
            <td>
              <div class="fname">
                <span class="ficon">{{ FileOps.icon(r.node) }}</span>
                <a href="#" class="nm" @click.prevent="props.onOpen(r.node)" :title="r.node.name">{{ r.node.name }}</a>
                <span class="tags">
                  <span v-if="r.node.favorite" class="tag" title="お気に入り">⭐</span>
                  <span v-if="r.node.shared" class="tag" title="共有中">👥</span>
                  <span v-if="r.node.hasLink" class="tag" title="公開リンクあり">🔗</span>
                  <span v-if="ownerNote(r.node).nonEmpty" class="tag" title="所有者">{{ ownerNote(r.node) }}</span>
                </span>
              </div>
            </td>
            <td v-if="props.showPath" class="small muted hide-sp path">{{ r.path.getOrElse("") }}</td>
            <td class="small muted num nowrap hide-sp">{{ sizeOf(r.node) }}</td>
            <td class="small muted nowrap hide-sp" :title="Fmt.dateTime(r.node.updatedAt)">{{ Fmt.ago(r.node.updatedAt) }}</td>
            <td class="act"><button class="ghost small" title="メニュー" @click="openMenu(s, r.node, event)">⋯</button></td>
          </tr>
          <tr v-if="isEmpty(props)"><td colspan="6" class="empty">{{ props.empty }}</td></tr>
        </tbody>
      </table>
    </div>
    <div v-if="isGrid(props)">
      <div class="grid">
        <div v-for="r in sorted(s, props)" :key="r.node.id" :class="tileClass(props, r.node)" :draggable="drag(props)"
             @dragstart="dragStart(props, r.node, event)" @dragover="dragOver(s, props, r.node, event)" @dragleave="dragLeave(s, r.node)"
             @drop="drop(s, props, r.node, event)" @contextmenu="openMenu(s, r.node, event)" @click="props.onOpen(r.node)">
          <input class="tcheck" type="checkbox" :checked="isSel(props, r.node.id)" @click="Dom.stop(event)" @change="toggle(props, r.node.id, Dom.checked(event))">
          <div class="thumb">
            <img v-if="hasThumb(props, r.node)" :src="thumbOf(props, r.node)" alt="" loading="lazy">
            <template v-else>{{ FileOps.icon(r.node) }}</template>
          </div>
          <div class="tname" :title="r.node.name">{{ r.node.name }}</div>
          <button class="ghost small tmenu" @click="openMenu(s, r.node, event)">⋯</button>
        </div>
      </div>
      <p v-if="isEmpty(props)" class="empty">{{ props.empty }}</p>
    </div>
    <div v-if="s.menu.mapC(_.isDefined)" class="menu-scrim" @click="s.menu := None" @contextmenu.prevent="s.menu := None"></div>
    <div v-if="s.menu.mapC(_.isDefined)" class="menu-pop" :style="menuStyle(s)">
      <template v-for="i in menuItems(s, props)">
        <hr v-if="i.sep">
        <button :class='if (i.danger) "danger" else ""' @click="runItem(s, i)">{{ i.label }}</button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.files th.ck, .files td.ck { width: 2.2rem; text-align: center; }
.files th.act, .files td.act { width: 2.6rem; text-align: right; }
.files th.sortable { cursor: pointer; user-select: none; }
.files .num { text-align: right; }
.files td .nm { color: var(--fg); }
.files td .nm:hover { color: var(--accent); }
.files .path { max-width: 18em; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.menu-scrim { position: fixed; inset: 0; z-index: 1100; }
.tmenu { position: absolute; top: .3rem; right: .3rem; background: var(--surface); }
</style>
