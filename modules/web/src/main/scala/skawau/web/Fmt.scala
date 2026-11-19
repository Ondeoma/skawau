package skawau.web

import skawau.shared.*

import scala.scalajs.js

/** 表示の補助. */
object Fmt {

  private def opts(
    o: (String, String)*
  ): js.Dynamic = js.Dynamic.literal(o.map(
    (
      k,
      v
    ) => k -> (v: js.Any)
  )*)

  def dateTime(
    t: Timestamp
  ): String =
    t.toDate.asInstanceOf[js.Dynamic].toLocaleString(
      "ja-JP",
      opts(
        "year" -> "numeric",
        "month" -> "2-digit",
        "day" -> "2-digit",
        "hour" -> "2-digit",
        "minute" -> "2-digit"
      )
    ).asInstanceOf[String]

  /** 「3分前」「昨日」など. */
  def ago(
    t: Timestamp
  ): String = {
    val diff = (js.Date.now() - t.toEpochMilli) / 1000
    if (diff < 60) "たった今"
    else if (diff < 3600) s"${(diff / 60).toInt}分前"
    else if (diff < 86400) s"${(diff / 3600).toInt}時間前"
    else if (diff < 86400 * 7) s"${(diff / 86400).toInt}日前"
    else dateTime(t).take(10)
  }

  def bytes(
    n: Long
  ): String = Bytes.format(n)

  def today(): String = isoDate(new js.Date())

  def isoDate(
    d: js.Date
  ): String = f"${d.getFullYear().toInt}%04d-${d.getMonth().toInt + 1}%02d-${d.getDate().toInt}%02d"

  def addDays(
    d: String,
    n: Int
  ): String = {
    val dt = new js.Date(d + "T00:00:00")
    dt.setDate(dt.getDate() + n)
    isoDate(dt)
  }

  def initials(
    name: String
  ): String = name.trim.take(1)

  /** 容量の表示（「1.2 GB / 10 GB」）. */
  def quota(
    q: QuotaDto
  ): String = q.limit.fold(s"${bytes(q.used)} 使用中")(l => s"${bytes(q.used)} / ${bytes(l)}")

  def quotaPercent(
    q: QuotaDto
  ): Int = q.limit.filter(_ > 0).fold(0)(l => math.min(100, (q.used.toDouble / l * 100).toInt))

}
