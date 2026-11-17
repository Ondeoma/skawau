<script lang="scala">
import skawau.web.*
import skawau.shared.*
import org.scalajs.dom
import skawau.web.parts.{Avatar, PushToggle}

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 個人設定: 名前・アイコン・画面の色・容量、通知（プッシュ通知）、WebDAV の接続先とアプリパスワード（APIキー）.
case class State(name: RV[String] = RV(""),
                 keys: RV[List[ApiKeyDto]] = RV(Nil),
                 keyName: RV[String] = RV(""),
                 readOnly: RV[Boolean] = RV(false),
                 days: RV[String] = RV(""),
                 created: RV[Option[String]] = RV(None),
                 uploading: RV[Boolean] = RV(false),
                 error: RV[String] = RV(""))

def initState() = State()

def loadKeys(s: State): Unit = Store.report(Api.apiKeys())(s.keys := _)

def mounted(s: State): Unit = {
  pageTitle("個人設定")
  Store.me.v.foreach(m => s.name := m.name)
  loadKeys(s)
}

def saveName(s: State): Unit =
  Store.report(Api.updateMe(s.name.v.trim)) { _ => Store.toast("保存しました"); Store.reloadMe() }

/** アイコンの登録（正方形に切り取って 256px に縮めてから送る）. */
def uploadAvatar(s: State, e: dom.Event): Unit = {
  val input = e.target.asInstanceOf[dom.HTMLInputElement]
  Option(input.files).filter(_.length > 0).map(_(0)).foreach { file =>
    s.uploading := true
    Images.squareThumb(file).flatMap(Api.setAvatar).onComplete { r =>
      s.uploading := false
      input.value = ""
      r match {
        case Success(_) => Store.toast("アイコンを登録しました"); Store.reloadMe()
        case Failure(e) => Store.error(e)
      }
    }
  }
}

def removeAvatar(): Unit = Dialog.confirm("アイコンの画像を外しますか？（名前の1文字目の表示に戻ります）", "外す") {
  Store.report(Api.removeAvatar()) { _ => Store.toast("アイコンを外しました"); Store.reloadMe() }
}

def meBrief = Store.me.mapC(_.map(m => UserBrief(m.id, m.name, m.avatarUrl)))
def hasAvatar = Store.me.mapC(_.exists(_.avatarUrl.exists(_.startsWith("/api/users/"))))

def create(s: State): Unit = {
  s.error := ""
  val scopes = if (s.readOnly.v) List("read") else List("read", "write")
  Api.createApiKey(CreateApiKeyReq(s.keyName.v.trim, scopes, s.days.v.trim.toIntOption)).onComplete {
    case Success(k) => s.created := Some(k.key); s.keyName := ""; loadKeys(s)
    case Failure(e) => s.error := e.getMessage
  }
}

def revoke(s: State, k: ApiKeyDto): Unit =
  Dialog.confirm(s"「${k.name}」を失効させますか？このパスワードを使っている機器はつながらなくなります。", "失効させる") {
    Store.report(Api.revokeApiKey(k.id))(_ => loadKeys(s))
  }

def davUrl = Store.config.mapC(_.map(_.webdavUrl).getOrElse(""))
def mcpUrl = Store.config.mapC(_.map(_.mcpUrl).getOrElse(""))
def apiBase = Store.config.mapC(_.map(_.mcpUrl.stripSuffix("/mcp")).getOrElse(""))
def keyOrPlaceholder(s: State): String = s.created.v.getOrElse("skawau_xxxxxxxx")
def claudeCmd(s: State) = computed { s"claude mcp add --transport http skawau ${mcpUrl.v} --header \"Authorization: Bearer ${keyOrPlaceholder(s)}\"" }
def curlCmd(s: State) = computed { s"curl -H \"Authorization: Bearer ${keyOrPlaceholder(s)}\" ${apiBase.v}/api/files/root" }
def quota = Store.me.mapC(_.map(m => Fmt.quota(m.quota)).getOrElse(""))
def userName = Store.me.mapC(_.map(_.name).getOrElse(""))
def themeChip(v: String) = Ui.active(Theme.current, v, "chip")
def scopes(k: ApiKeyDto): String = if (k.scopes.contains("write")) "読み書き" else "読み取りのみ"
def hasError(s: State) = s.error.mapC(_.nonEmpty)
</script>

<template>
  <div class="page narrow">
    <h1>個人設定</h1>
    <section class="panel">
      <h2>プロフィール</h2>
      <div class="row avatar-row">
        <Avatar :user="meBrief.v" :size="64" />
        <div>
          <label class="btn small">画像を選ぶ<input type="file" accept="image/png,image/jpeg,image/gif,image/webp" class="hidden" @change="uploadAvatar(s, event)"></label>
          <button v-if="hasAvatar" class="ghost small" @click="removeAvatar()">外す</button>
          <span v-if="s.uploading" class="muted small">アップロード中…</span>
          <div class="muted small">PNG・JPEG・GIF・WebP（正方形に切り取って縮めます）</div>
        </div>
      </div>
      <form class="row" @submit.prevent="saveName(s)">
        <label class="field grow"><span>表示名</span><input type="text" v-model="s.name"></label>
        <button type="submit">保存</button>
      </form>
      <p class="muted small">容量: {{ quota }}</p>
      <div class="field"><span>画面の色</span>
        <div class="chips"><button v-for="t in Theme.options" :class="themeChip(t._1)" @click="Theme.set(t._1)">{{ t._2 }}</button></div></div>
    </section>

    <section class="panel">
      <h2>通知</h2>
      <p><PushToggle /></p>
      <p class="muted small">共有されたとき・公開リンクからアップロードされたときの通知（右上の 🔔）を、この端末にも知らせます。</p>
    </section>

    <section class="panel">
      <h2>WebDAV（パソコン・スマートフォンから使う）</h2>
      <p class="small">エクスプローラー（Windows）、Finder（macOS）、Cyberduck、rclone などから、次の URL にアプリパスワードで接続できます。
        ユーザー名は何でもよく（{{ userName }} など）、パスワードに下で作ったアプリパスワードを入れます。</p>
      <div class="row"><input type="text" class="grow mono" :value="davUrl" readonly @focus="Dom.selectAll(event)">
        <button class="small" @click="Dom.copy(davUrl.v, &quot;URL をコピーしました&quot;)">コピー</button></div>
      <ul class="small muted">
        <li>macOS: Finder の「移動 → サーバへ接続」に URL を入れる</li>
        <li>Windows: エクスプローラーの「PC → ネットワークの場所を追加」（https の URL が必要です）</li>
        <li>rclone: <code>rclone config</code> で種類に webdav、vendor に other を選ぶ</li>
      </ul>
    </section>

    <section class="panel">
      <h2>API・MCP（AI から使う）</h2>
      <p class="small">下で作ったアプリパスワードを <code>Authorization: Bearer &lt;キー&gt;</code> に付けると、画面と同じ REST API を呼べます
        （一覧: <a href="/api/endpoints" target="_blank">/api/endpoints</a>）。権限はあなたと同じで、「読み取りのみ」のキーでは変更できません。</p>
      <div class="row"><input type="text" class="grow mono" :value="curlCmd(s)" readonly @focus="Dom.selectAll(event)">
        <button class="small" @click="Dom.copy(curlCmd(s).v, &quot;コマンドをコピーしました&quot;)">コピー</button></div>
      <p class="small">MCP（Model Context Protocol）に対応した AI（Claude Code・Claude Desktop など）から、ファイルを探す・読む（docx・xlsx・pptx は文字を取り出す）・
        書く・移動・共有ができます。接続先: <code>{{ mcpUrl }}</code>（Streamable HTTP）。例（Claude Code）:</p>
      <div class="row"><input type="text" class="grow mono" :value="claudeCmd(s)" readonly @focus="Dom.selectAll(event)">
        <button class="small" @click="Dom.copy(claudeCmd(s).v, &quot;コマンドをコピーしました&quot;)">コピー</button></div>
      <p class="muted small">AI からの変更はアクティビティに「MCP（AI）」と出ます。削除はごみ箱へ、上書きは新しい版になるので元に戻せます。</p>
    </section>

    <section class="panel">
      <h2>アプリパスワード（APIキー）</h2>
      <p class="muted small">WebDAV や API（<code>Authorization: Bearer &lt;キー&gt;</code>）で使います。機器ごとに作ると、なくしたときにその機器だけ止められます。</p>
      <div v-if="s.created.mapC(_.isDefined)" class="created">
        <p><b>作成しました。このパスワードは今しか表示されません。</b></p>
        <div class="row"><input type="text" class="grow mono" :value='s.created.v.getOrElse("")' readonly @focus="Dom.selectAll(event)">
          <button class="small" @click='Dom.copy(s.created.v.getOrElse(""))'>コピー</button>
          <button class="small ghost" @click="s.created := None">閉じる</button></div>
      </div>
      <form class="row" @submit.prevent="create(s)">
        <input type="text" class="grow" v-model="s.keyName" placeholder="名前（例: 自宅の Mac）">
        <input type="number" class="days" min="1" v-model="s.days" placeholder="有効日数">
        <label class="small"><input type="checkbox" v-model="s.readOnly"> 読み取りのみ</label>
        <button type="submit" class="primary">作成</button>
      </form>
      <p v-if="hasError(s)" class="error">{{ s.error }}</p>
      <div class="table-wrap keys">
        <table class="list">
          <thead><tr><th>名前</th><th>キー</th><th>権限</th><th class="hide-sp">最終使用</th><th class="hide-sp">期限</th><th></th></tr></thead>
          <tbody>
            <tr v-for="k in s.keys" :key="k.id">
              <td>{{ k.name }}</td><td><code class="small">{{ k.prefix }}…</code></td><td class="small">{{ scopes(k) }}</td>
              <td class="small muted hide-sp">{{ k.lastUsedAt.map(Fmt.ago).getOrElse("未使用") }}</td>
              <td class="small muted hide-sp">{{ k.expiresAt.map(Fmt.dateTime).getOrElse("なし") }}</td>
              <td><button class="small danger" @click="revoke(s, k)">失効</button></td>
            </tr>
            <tr v-if="s.keys.mapC(_.isEmpty)"><td colspan="6" class="empty">アプリパスワードはありません</td></tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .85rem; }
.days { width: 7rem; }
.created { border: 1px solid var(--ok); border-radius: 9px; padding: .6rem .8rem; margin-bottom: .8rem; }
.keys { margin-top: .8rem; }
.avatar-row { margin-bottom: .8rem; gap: 1rem; }
</style>
