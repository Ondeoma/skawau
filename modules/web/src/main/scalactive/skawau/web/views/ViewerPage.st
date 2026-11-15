<script lang="scala">
import skawau.web.*
import skawau.shared.*
import skawau.web.parts.*

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// ファイル1つの画面（/view/{id}. 通知・リンクから開く）. フォルダは一覧へ、Office は ONLYOFFICE へ移る.
case class Props(fileId: String)

case class State(hit: RV[Option[NodeHitDto]] = RV(None), error: RV[String] = RV(""))

def initState() = State()

def mounted(s: State, props: Props): Unit =
  scala.util.Try(UUID.fromString(props.fileId)).toOption match {
    case None => s.error := "ファイルが見つかりません"
    case Some(id) =>
      Api.node(id).onComplete {
        case Success(h) if h.node.isDir => Router.go(s"/files/${h.node.id}")
        case Success(h) if FileOps.officeOpenable(h.node) => Router.go(s"/office/${h.node.id}")
        case Success(h) => s.hit := Some(h); pageTitle(h.node.name)
        case Failure(e) => s.error := e.getMessage
      }
  }

def nodes(s: State) = s.hit.mapC(_.toList.map(_.node))
def previewable(n: NodeDto): Boolean = FileKind.previewable(FileKind.kind(n.name))
</script>

<template>
  <div class="page">
    <p v-if="s.error.mapC(_.nonEmpty)" class="error">{{ s.error }}</p>
    <div v-for="n in nodes(s)" :key="n.id">
      <div class="row head">
        <span class="big">{{ FileOps.icon(n) }}</span>
        <div class="grow"><h1>{{ n.name }}</h1>
          <div class="muted small">{{ Fmt.bytes(n.size) }}・{{ Fmt.dateTime(n.updatedAt) }}・所有者 {{ n.owner.name }}</div></div>
        <button @click="FileOps.download(n)">⬇️ ダウンロード</button>
        <button v-if="n.parentId.isDefined" @click='Router.go("/files/" + n.parentId.get)'>📁 フォルダを開く</button>
        <button v-if="n.perm == Perm.Owner" @click="Store.shareFor := Some(n)">👥 共有</button>
      </div>
      <FileContent v-if="previewable(n)" :node="n" />
      <p v-else class="empty">このファイルは画面では開けません。ダウンロードしてください。</p>
    </div>
  </div>
</template>

<style scoped>
.head { margin-bottom: 1rem; align-items: flex-start; }
.head h1 { margin: 0; word-break: break-all; }
.big { font-size: 2.2rem; }
</style>
