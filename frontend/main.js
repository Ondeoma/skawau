import './style.css'
import { collectDropped } from './src/drop.js'
import { loadOffice } from './src/office.js'

// Scala.js の画面から使う補助（ドロップされたフォルダの展開、ONLYOFFICE のエディタ）
window.skawauDrop = { collect: collectDropped }
window.skawauOffice = { load: loadOffice }

import 'scalajs:main.js'
