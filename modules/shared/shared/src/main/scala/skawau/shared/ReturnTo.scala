package skawau.shared

/** ログイン後に戻る先（同じサイトのパスだけ。サーバーと画面で同じ判定を使う）。 */
object ReturnTo {
  def safe(
    p: String
  ): Boolean =
    p.startsWith("/") && !p.startsWith("//") && !p.startsWith("/\\") && !p.exists(c => c.isControl || c == '\\')

  def orRoot(
    p: Option[String]
  ): String = p.filter(safe).getOrElse("/")
}
