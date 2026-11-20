package skawau.web

import skawau.shared.*

import scala.scalajs.js

/** 表示の補助. */
object Fmt {

  // 日時の表示（2026/11/15 09:30・「3分前」）は sbirds-lib
  export sbirds.web.Dates.{ago, dateTime}

  def bytes(n: Long): String = Bytes.format(n)

  def today(): String = isoDate(new js.Date())

  def isoDate(d: js.Date): String =
    f"${d.getFullYear().toInt}%04d-${d.getMonth().toInt + 1}%02d-${d.getDate().toInt}%02d"

  def addDays(d: String, n: Int): String = {
    val dt = new js.Date(d + "T00:00:00")
    dt.setDate(dt.getDate() + n)
    isoDate(dt)
  }

  def initials(name: String): String = name.trim.take(1)

  /** 容量の表示（「1.2 GB / 10 GB」）. */
  def quota(q: QuotaDto): String = q.limit.fold(s"${bytes(q.used)} 使用中")(l => s"${bytes(q.used)} / ${bytes(l)}")

  def quotaPercent(q: QuotaDto): Int =
    q.limit.filter(_ > 0).fold(0)(l => math.min(100, (q.used.toDouble / l * 100).toInt))

}
