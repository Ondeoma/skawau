import './style.css'
import { collectDropped } from './src/drop.js'
import { loadOffice } from './src/office.js'
import { squareThumb } from './src/image.js'

// Scala.js の画面から使う補助（ドロップされたフォルダの展開、ONLYOFFICE のエディタ、アイコンの縮小）
window.skawauDrop = { collect: collectDropped }
window.skawauOffice = { load: loadOffice }
window.skawauImage = { squareThumb }

import 'scalajs:main.js'
