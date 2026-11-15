/**
 * ドロップされたもの（ファイル・フォルダ）を [{ file, path }] にする。path はフォルダの中の相対パス（"a/b"。直下は ""）。
 * フォルダは webkitGetAsEntry でたどる（Chrome・Edge・Firefox・Safari で使える）。
 */
export async function collectDropped(dataTransfer) {
  const items = Array.from(dataTransfer.items || []).filter((i) => i.kind === 'file')
  const entries = items.map((i) => (i.webkitGetAsEntry ? i.webkitGetAsEntry() : null))
  if (entries.length === 0 || entries.some((e) => !e)) {
    return Array.from(dataTransfer.files || []).map((file) => ({ file, path: '' }))
  }
  const out = []
  for (const e of entries) await walk(e, '', out)
  return out
}

async function walk(entry, base, out) {
  if (entry.isFile) {
    const file = await new Promise((res, rej) => entry.file(res, rej))
    out.push({ file, path: base })
  } else if (entry.isDirectory) {
    const dir = base ? `${base}/${entry.name}` : entry.name
    const reader = entry.createReader()
    // readEntries は一度に最大 100 件しか返さないため、空になるまで繰り返す
    for (;;) {
      const batch = await new Promise((res, rej) => reader.readEntries(res, rej))
      if (batch.length === 0) break
      for (const c of batch) await walk(c, dir, out)
    }
    if (out.every((o) => !o.path.startsWith(dir))) out.push({ file: null, path: dir }) // 空のフォルダ
  }
}
