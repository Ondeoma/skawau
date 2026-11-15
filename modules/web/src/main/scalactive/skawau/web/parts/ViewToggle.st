<script lang="scala">
import skawau.web.*

// 一覧・タイルの切り替え（すべての一覧で共通. この端末に記憶する）
def cls(on: Boolean) = Layout.grid.mapC(g => if (g == on) "small active" else "small ghost")
</script>

<template>
  <span class="toggle">
    <button :class="cls(false)" title="一覧" @click="Layout.setGrid(false)">☰</button>
    <button :class="cls(true)" title="タイル" @click="Layout.setGrid(true)">▦</button>
  </span>
</template>

<style scoped>
.toggle { display: inline-flex; gap: .3rem; }
.toggle .active { background: var(--accent-weak); color: var(--accent); border-color: var(--accent); }
</style>
