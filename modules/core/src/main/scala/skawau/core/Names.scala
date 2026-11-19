package skawau.core

import skawau.shared.FileKind

/** ファイル名・パスの規則。 */
object Names {

  val MaxLength = 255

  /** 名前を整える（前後の空白を除き、Unicode の正規化を NFC にそろえる。macOS の濁点の分解を防ぐ）。 */
  def normalize(s: String): String = java.text.Normalizer.normalize(s.trim, java.text.Normalizer.Form.NFC)

  /** 使えない名前ならその理由。 */
  def invalid(name: String): Option[String] =
    if name.isEmpty then Some("名前を入力してください")
    else if name.length > MaxLength then Some(s"名前は${MaxLength}文字以内にしてください")
    else if name == "." || name == ".." then Some("その名前は使えません")
    else if name.exists(c => c == '/' || c == '\\' || c.isControl || Bidi(c)) then Some("名前に / \\ や制御文字は使えません")
    else None

  /** 文字の向きを変える制御文字（「txt.exe」を「exe.txt」に見せる偽装に使われる）。 */
  private val Bidi: Set[Char] = Set('\u061c', '\u200e', '\u200f') ++ ('\u202a' to '\u202e') ++ ('\u2066' to '\u2069')

  /** ZIP の中のパスに使える形（万一 / や .. を含む名前があっても、展開先の外へ出ないようにする）。 */
  def zipSegment(name: String): String = {
    val s = name.map(c => if c == '/' || c == '\\' || c.isControl then '_' else c)
    if s.isEmpty || s == "." || s == ".." then "_" else s
  }

  /** 同じ名前があるときの別名（「名前 (2).txt」「名前 (3).txt」…）。taken は小文字で比べる。 */
  def unique(name: String, taken: Set[String]): String = {
    val lower = taken.map(_.toLowerCase)
    if !lower.contains(name.toLowerCase) then name
    else {
      val (base, ext) = {
        val e = FileKind.ext(name)
        if e.isEmpty then (name, "") else (name.dropRight(e.length + 1), "." + name.takeRight(e.length))
      }
      Iterator.from(2).map(i => s"$base ($i)$ext").find(n => !lower.contains(n.toLowerCase)).get
    }
  }

  /** 「a/b/c」をセグメントに分ける（空のセグメントは除く。URL デコード済みのもの）。 */
  def segments(path: String): List[String] = path.split('/').toList.map(normalize).filter(_.nonEmpty)

  /** Content-Disposition のファイル名（RFC 6266 / 5987）。 */
  def contentDisposition(name: String, inline: Boolean): String = {
    val ascii = name.map(c => if c < 0x20 || c > 0x7e || c == '"' || c == '\\' then '_' else c)
    val enc = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    s"""${if inline then "inline" else "attachment"}; filename="$ascii"; filename*=UTF-8''$enc"""
  }
}
