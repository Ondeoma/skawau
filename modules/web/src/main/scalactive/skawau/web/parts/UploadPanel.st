<script lang="scala">
import skawau.web.*

// アップロードの進み具合（画面の左下）.
def shown = Uploads.items.mapC(_.nonEmpty)
def summary = Uploads.items.mapC { is =>
  val done = is.count(_.state == "done")
  val err  = is.count(_.state == "error")
  s"アップロード $done / ${is.size}" + (if (err > 0) s"（失敗 $err）" else "")
}
def barStyle(i: UploadItem): String = s"width: ${i.percent}%"
def mark(i: UploadItem): String = i.state match {
  case "done"  => "✅"
  case "error" => "⚠️"
  case "uploading" => s"${i.percent}%"
  case _       => "待機中"
}
</script>

<template>
  <div v-if="shown" class="uploads">
    <div class="row"><b class="grow small">{{ summary }}</b>
      <button v-if="Uploads.active.mapC(!_)" class="ghost small" @click="Uploads.clearDone()">✕</button></div>
    <div v-for="i in Uploads.items" :key="i.id" class="u">
      <div class="row"><span class="grow nm">{{ i.name }}</span><span class="muted">{{ mark(i) }}</span></div>
      <div v-if='i.state == "uploading"' class="bar"><span :style="barStyle(i)"></span></div>
      <div v-if='i.state == "error"' class="error">{{ i.error }}</div>
    </div>
  </div>
</template>

<style scoped>
.nm { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
