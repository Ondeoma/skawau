/**
 * ONLYOFFICE（Document Server）のエディタを開く。api.js は Document Server から一度だけ読み込む。
 * 戻り値の destroy() でエディタを閉じる。
 */
const loaded = new Map()

function loadScript(url) {
  if (!loaded.has(url)) {
    loaded.set(url, new Promise((resolve, reject) => {
      const s = document.createElement('script')
      s.src = url
      s.onload = () => resolve()
      s.onerror = () => { loaded.delete(url); reject(new Error('Document Server に接続できません（' + url + '）')) }
      document.head.appendChild(s)
    }))
  }
  return loaded.get(url)
}

export async function loadOffice(serverUrl, containerId, config) {
  await loadScript(serverUrl.replace(/\/$/, '') + '/web-apps/apps/api/documents/api.js')
  if (!window.DocsAPI) throw new Error('Document Server の api.js を読み込めませんでした')
  const cfg = Object.assign({ width: '100%', height: '100%', type: window.innerWidth < 720 ? 'mobile' : 'desktop' }, config)
  // DocEditor は渡した要素を iframe に置き換えるため、画面の部品が持つ要素の中に置き場所を作って渡す
  const container = document.getElementById(containerId)
  if (!container) throw new Error('エディタの置き場所がありません')
  container.innerHTML = ''
  const ph = document.createElement('div')
  ph.id = containerId + '-placeholder'
  container.appendChild(ph)
  const editor = new window.DocsAPI.DocEditor(ph.id, cfg)
  return { destroy: () => { try { editor.destroyEditor() } catch (e) { /* 閉じ済み */ } } }
}
