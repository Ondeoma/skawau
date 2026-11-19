package skawau.shared

/** ファイル名（拡張子）からの種類の判定. アイコン・プレビュー・ONLYOFFICE で開けるかに使う. */
object FileKind {

  val Folder = "folder"
  val Image = "image"
  val Video = "video"
  val Audio = "audio"
  val Pdf = "pdf"
  val Text = "text"
  val Word = "word"
  val Cell = "cell"
  val Slide = "slide"
  val Archive = "archive"
  val Other = "file"

  /** 小文字の拡張子（なければ空）. */
  def ext(
    name: String
  ): String = {
    val i = name.lastIndexOf('.')
    if (i <= 0 || i == name.length - 1) "" else name.substring(i + 1).toLowerCase
  }

  /** 拡張子を除いた名前. */
  def base(
    name: String
  ): String = {
    val i = name.lastIndexOf('.')
    if (i <= 0) name else name.substring(0, i)
  }

  private val images = Set("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "avif")
  private val videos = Set("mp4", "webm", "mov", "m4v", "ogv")
  private val audios = Set("mp3", "wav", "ogg", "oga", "m4a", "flac", "aac", "opus")
  private val texts = Set(
    "txt",
    "md",
    "markdown",
    "csv",
    "tsv",
    "log",
    "json",
    "xml",
    "yaml",
    "yml",
    "toml",
    "ini",
    "conf",
    "properties",
    "html",
    "htm",
    "css",
    "js",
    "mjs",
    "ts",
    "tsx",
    "jsx",
    "scala",
    "sc",
    "sbt",
    "java",
    "kt",
    "py",
    "rb",
    "go",
    "rs",
    "c",
    "h",
    "cpp",
    "hpp",
    "cs",
    "php",
    "sh",
    "bash",
    "zsh",
    "fish",
    "sql",
    "vue",
    "st",
    "env",
    "gitignore",
    "dockerfile"
  )
  private val archives = Set("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar")

  /** ONLYOFFICE で編集できる形式（documentType ごと）. */
  val officeEdit: Map[String, String] = Map(
    "docx" -> Word,
    "docm" -> Word,
    "dotx" -> Word,
    "odt" -> Word,
    "rtf" -> Word,
    "xlsx" -> Cell,
    "xlsm" -> Cell,
    "xltx" -> Cell,
    "ods" -> Cell,
    "pptx" -> Slide,
    "pptm" -> Slide,
    "potx" -> Slide,
    "odp" -> Slide
  )

  /** ONLYOFFICE で閲覧だけできる形式（古い形式など）. */
  val officeView: Map[String, String] = Map(
    "doc" -> Word,
    "dot" -> Word,
    "epub" -> Word,
    "fb2" -> Word,
    "xps" -> Word,
    "djvu" -> Word,
    "xls" -> Cell,
    "xlt" -> Cell,
    "ppt" -> Slide,
    "pps" -> Slide,
    "ppsx" -> Slide,
    "pot" -> Slide
  )

  /** ONLYOFFICE の documentType（word / cell / slide）. 開けない形式は None. */
  def officeType(
    name: String
  ): Option[String] = { val e = ext(name); officeEdit.get(e).orElse(officeView.get(e)) }

  def officeEditable(
    name: String
  ): Boolean = officeEdit.contains(ext(name))

  def kind(
    name: String,
    isDir: Boolean = false
  ): String =
    if (isDir) Folder
    else {
      val e = ext(name)
      if (images(e)) Image
      else if (videos(e)) Video
      else if (audios(e)) Audio
      else if (e == "pdf") Pdf
      else if (officeEdit.contains(e) || officeView.contains(e)) officeType(name).get
      else if (texts(e) || name.toLowerCase == "dockerfile" || name.toLowerCase == "makefile") Text
      else if (archives(e)) Archive
      else Other
    }

  def icon(
    kind: String
  ): String = kind match {
    case Folder => "📁"
    case Image => "🖼️"
    case Video => "🎬"
    case Audio => "🎵"
    case Pdf => "📕"
    case Text => "📄"
    case Word => "📘"
    case Cell => "📗"
    case Slide => "📙"
    case Archive => "🗜️"
    case _ => "📎"
  }

  /** 画面の中で開けるか（画像・動画・音声・PDF・テキスト）. */
  def previewable(
    kind: String
  ): Boolean = Set(Image, Video, Audio, Pdf, Text)(kind)

  /** 画面で直接書き換えられるテキスト（大きすぎるものは除く）. */
  def textEditable(
    name: String,
    size: Long
  ): Boolean = kind(name) == Text && size <= 2L * 1024 * 1024

  private val types: Map[String, String] = Map(
    "png" -> "image/png",
    "jpg" -> "image/jpeg",
    "jpeg" -> "image/jpeg",
    "gif" -> "image/gif",
    "webp" -> "image/webp",
    "svg" -> "image/svg+xml",
    "bmp" -> "image/bmp",
    "ico" -> "image/x-icon",
    "avif" -> "image/avif",
    "mp4" -> "video/mp4",
    "webm" -> "video/webm",
    "mov" -> "video/quicktime",
    "m4v" -> "video/x-m4v",
    "ogv" -> "video/ogg",
    "mp3" -> "audio/mpeg",
    "wav" -> "audio/wav",
    "ogg" -> "audio/ogg",
    "oga" -> "audio/ogg",
    "m4a" -> "audio/mp4",
    "flac" -> "audio/flac",
    "aac" -> "audio/aac",
    "opus" -> "audio/opus",
    "pdf" -> "application/pdf",
    "txt" -> "text/plain",
    "md" -> "text/markdown",
    "csv" -> "text/csv",
    "tsv" -> "text/tab-separated-values",
    "json" -> "application/json",
    "xml" -> "application/xml",
    "html" -> "text/html",
    "htm" -> "text/html",
    "css" -> "text/css",
    "js" -> "text/javascript",
    "yaml" -> "application/yaml",
    "yml" -> "application/yaml",
    "zip" -> "application/zip",
    "gz" -> "application/gzip",
    "tar" -> "application/x-tar",
    "7z" -> "application/x-7z-compressed",
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "doc" -> "application/msword",
    "xls" -> "application/vnd.ms-excel",
    "ppt" -> "application/vnd.ms-powerpoint",
    "odt" -> "application/vnd.oasis.opendocument.text",
    "ods" -> "application/vnd.oasis.opendocument.spreadsheet",
    "odp" -> "application/vnd.oasis.opendocument.presentation",
    "rtf" -> "application/rtf",
    "epub" -> "application/epub+zip"
  )

  /** 拡張子からの Content-Type（分からなければ application/octet-stream）. */
  def contentType(
    name: String
  ): String = types.getOrElse(ext(name), "application/octet-stream")

  /** ブラウザでそのまま表示してよい形式（それ以外は保存させる. HTML・SVG はスクリプトを含み得るので表示させない）. */
  def inlineSafe(
    name: String
  ): Boolean = {
    val k = kind(name)
    val e = ext(name)
    (k == Image && e != "svg") || k == Video || k == Audio || k == Pdf ||
    (k == Text && e != "html" && e != "htm" && e != "svg" && e != "xml")
  }

}

/** 表示の補助（バイト数など. 画面とサーバーで同じ表記にする）. */
object Bytes {
  def format(
    n: Long
  ): String =
    if (n < 1024) s"$n B"
    else if (n < 1024L * 1024) f"${n / 1024.0}%.1f KB"
    else if (n < 1024L * 1024 * 1024) f"${n / 1024.0 / 1024.0}%.1f MB"
    else f"${n / 1024.0 / 1024.0 / 1024.0}%.2f GB"
}
