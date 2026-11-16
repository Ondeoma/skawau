package skawau.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{ACursor, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{`WWW-Authenticate`, Allow, Authorization}
import skawau.core.*
import skawau.shared.*

/** REST API の一覧（MCP の list_api_endpoints と GET /api/endpoints）。 */
object ApiCatalog:
  final case class Endpoint(method: String, path: String, description: String)
  val all: List[Endpoint] = List(
    Endpoint("GET", "/api/me", "自分の情報（容量の使用量・キーのスコープ）"),
    Endpoint("PATCH", "/api/me", "自分の表示名を変える {name}"),
    Endpoint("GET", "/api/users/{id}/avatar", "ユーザーのアイコンの画像（画面から登録したもの）"),
    Endpoint("GET", "/api/files/root", "自分のルートのフォルダとその中身"),
    Endpoint("GET", "/api/files/resolve?path=/a/b", "パス（自分のルートから）でファイル・フォルダを引く"),
    Endpoint("GET", "/api/files/{id}", "ファイル・フォルダの情報と場所"),
    Endpoint("GET", "/api/files/{id}/children", "フォルダの中身とパンくず"),
    Endpoint("POST", "/api/files/{folderId}/folders", "フォルダを作る {name}"),
    Endpoint("POST", "/api/files/{folderId}/new", "空のファイルを作る（docx・xlsx・pptx は最小の文書） {name}"),
    Endpoint("PUT", "/api/files/{folderId}/upload?name=&path=&conflict=rename|overwrite", "アップロード（本文がそのまま中身。path は途中のフォルダ a/b）"),
    Endpoint("GET", "/api/files/{id}/content?download=1", "中身（フォルダは ZIP。Range に対応）"),
    Endpoint("PUT", "/api/files/{id}/content?version=", "中身を置き換える（新しい版。version を渡すと競合を検出）"),
    Endpoint("GET", "/api/files/zip?ids=a,b", "まとめて ZIP でダウンロード"),
    Endpoint("PATCH", "/api/files/{id}", "名前を変える {name}"),
    Endpoint("POST", "/api/files/transfer", "移動・コピー {ids, targetId, copy, conflict?: rename|overwrite}"),
    Endpoint("POST", "/api/files/delete", "ごみ箱へ移す {ids}"),
    Endpoint("PUT", "/api/files/{id}/favorite", "お気に入りに追加（DELETE で外す）"),
    Endpoint("GET", "/api/files/{id}/versions", "版の一覧（先頭が今の版）"),
    Endpoint("GET", "/api/files/{id}/versions/{versionId}/content", "以前の版の中身"),
    Endpoint("POST", "/api/files/{id}/versions/{versionId}/restore", "以前の版に戻す（今の内容は新しい版として残る）"),
    Endpoint("DELETE", "/api/files/{id}/versions/{versionId}", "以前の版を消す（所有者）"),
    Endpoint("GET", "/api/files/{id}/activity", "ファイル・フォルダ（中身を含む）の変更履歴"),
    Endpoint("GET", "/api/files/{id}/office", "ONLYOFFICE のエディタの設定"),
    Endpoint("POST", "/api/files/lookup", "ID からまとめて名前を引く {ids}（読み取り専用のキーで使える）"),
    Endpoint("POST", "/api/users/lookup", "メールアドレスが完全に一致するユーザーを引く {emails}（読み取り専用のキーで使える）"),
    Endpoint("GET", "/api/recent?limit=", "最近更新したファイル"),
    Endpoint("GET", "/api/favorites", "お気に入り"),
    Endpoint("GET", "/api/search?q=", "名前で検索（日本語の部分一致）"),
    Endpoint("GET", "/api/shared-with-me", "共有されたもの"),
    Endpoint("GET", "/api/shared-by-me", "自分が共有したもの"),
    Endpoint("GET", "/api/activity?before=&limit=", "自分に関係する変更履歴"),
    Endpoint("GET", "/api/trash", "ごみ箱"),
    Endpoint("POST", "/api/trash/{id}/restore", "ごみ箱から元の場所へ戻す"),
    Endpoint("DELETE", "/api/trash/{id}", "ごみ箱から完全に削除する"),
    Endpoint("DELETE", "/api/trash", "ごみ箱を空にする"),
    Endpoint("GET", "/api/sharees?q=", "共有の相手の候補（ユーザー・グループ）"),
    Endpoint("GET", "/api/files/{id}/shares", "付いている共有（所有者）"),
    Endpoint("POST", "/api/files/{id}/shares", "共有する {kind: user|group|link, userId?, groupId?, permission: view|edit|upload, password?, expiresAt?, label?}"),
    Endpoint("PATCH", "/api/shares/{id}", "共有を変える {permission?, password?, expiresAt?, label?}（空文字で外す）"),
    Endpoint("DELETE", "/api/shares/{id}", "共有を解除する"),
    Endpoint("GET", "/api/notifications?unread=true", "通知"),
    Endpoint("POST", "/api/notifications/read", "通知を既読にする {ids}（空ならすべて）"),
    Endpoint("GET", "/api/admin/users", "ユーザーの一覧（管理者・admin スコープ）"),
    Endpoint("PATCH", "/api/admin/users/{id}", "ユーザーを変える {isAdmin?, disabled?, quotaMb?}（管理者）"),
    Endpoint("GET", "/api/admin/stats", "全体の使用量（管理者）"),
    Endpoint("GET", "/api/admin/audit", "監査ログ（管理者）")
  )

/**
 * MCP（Model Context Protocol、Streamable HTTP の JSON 応答）。`POST /mcp` に APIキー（Bearer）で接続する。
 * どのツールも内部で REST API を同じキーで呼ぶので、権限・スコープ・変更履歴（経路 mcp）は REST API と同じになる。
 * 専用のツールがない操作も list_api_endpoints と call_api でできる（画面でできることはすべて）。
 */
final class McpRoutes(api: HttpRoutes[IO], sessions: SessionAuth, publicUrl: String) extends Http4sDsl[IO]:

  private val base     = publicUrl.stripSuffix("/")
  private val Versions = List("2025-06-18", "2025-03-26", "2024-11-05")
  /** read_file で返す文字数の上限（既定）。 */
  private val DefaultChars = 100000

  // ------------------------------------------------------------------ REST API の内部呼び出し

  private final class Caller(auth: Authorization):
    private def run(req0: Request[IO]): IO[Either[String, Response[IO]]] =
      api.run(req0.putHeaders(auth, McpHeader -> "1")).value.flatMap {
        case None                        => IO.pure(Left("404 見つかりません"))
        case Some(r) if r.status.isSuccess => IO.pure(Right(r))
        case Some(r) => r.bodyText.compile.string.map { s =>
            Left(s"${r.status.code} ${parse(s).toOption.flatMap(_.hcursor.get[String]("error").toOption).getOrElse(r.status.reason)}")
          }
      }

    def apply(method: Method, uri: Uri, body: Option[Json] = None): IO[Either[String, Json]] =
      val req = body.fold(Request[IO](method, uri))(Request[IO](method, uri).withEntity(_))
      run(req).flatMap(_.flatTraverse(r => r.bodyText.compile.string.map(s => Right(if s.isEmpty then Json.Null else parse(s).getOrElse(Json.fromString(s))))))

    def bytes(uri: Uri): IO[Either[String, Array[Byte]]] = run(Request[IO](Method.GET, uri)).flatMap(_.traverse(_.body.compile.to(Array)))

    def putText(uri: Uri, text: String): IO[Either[String, Json]] =
      run(Request[IO](Method.PUT, uri).withEntity(text)).flatMap(_.flatTraverse(r => r.bodyText.compile.string.map(s => Right(parse(s).getOrElse(Json.Null)))))

  private def path(segments: String*): Uri = Uri(path = Uri.Path(segments.toVector.map(Uri.Path.Segment(_)), absolute = true))

  // ------------------------------------------------------------------ ツール

  private type Result = IO[Either[String, String]]

  private final case class Tool(name: String, description: String, schema: Json, readOnly: Boolean, destructive: Boolean = false)(
      val run: (Caller, ACursor) => Result):
    def json: Json = Json.obj(
      "name" -> name.asJson, "description" -> description.asJson, "inputSchema" -> schema,
      "annotations" -> Json.obj("readOnlyHint" -> readOnly.asJson, "destructiveHint" -> destructive.asJson, "openWorldHint" -> false.asJson)
    )

  private def schema(required: List[String], props: (String, Json)*): Json =
    Json.obj("type" -> "object".asJson, "properties" -> Json.obj(props*), "required" -> required.asJson)
  private def str(d: String): Json  = Json.obj("type" -> "string".asJson, "description" -> d.asJson)
  private def int(d: String): Json  = Json.obj("type" -> "integer".asJson, "description" -> d.asJson)
  private def bool(d: String): Json = Json.obj("type" -> "boolean".asJson, "description" -> d.asJson)
  private def strs(d: String): Json = Json.obj("type" -> "array".asJson, "items" -> Json.obj("type" -> "string".asJson), "description" -> d.asJson)
  private def obj(d: String): Json  = Json.obj("type" -> "object".asJson, "description" -> d.asJson)

  private val fileArg = "ファイル・フォルダの ID、または自分のルートからのパス（/資料/2026/報告.docx）"

  private def need(a: ACursor, k: String): Either[String, String] = a.get[String](k).toOption.map(_.trim).filter(_.nonEmpty).toRight(s"$k を指定してください")
  private def opt(a: ACursor, k: String): Option[String] = a.get[String](k).toOption.map(_.trim).filter(_.nonEmpty)
  private def s(j: Json, k: String): String = j.hcursor.get[String](k).getOrElse("")

  extension [A](e: Either[String, A]) private def withArgs(f: A => Result): Result = e.fold(err => IO.pure(Left(err)), f)
  extension (r: IO[Either[String, Json]]) private def asText: Result = r.map(_.map(_.spaces2))

  /** ID またはパスを、ノードの情報（NodeHitDto の JSON: node・path）にする。 */
  private def resolve(call: Caller, v: String): IO[Either[String, Json]] =
    uuidOpt(v) match
      case Some(id) => call(Method.GET, path("api", "files", id.toString))
      case None if v.trim == "/" || v.trim.isEmpty => call(Method.GET, path("api", "files", "root")).map(_.map(f => Json.obj("node" -> f.hcursor.downField("folder").focus.getOrElse(Json.Null), "path" -> "/".asJson)))
      case None => call(Method.GET, path("api", "files", "resolve").withQueryParam("path", v))

  private def resolveId(call: Caller, v: String): IO[Either[String, String]] = resolve(call, v).map(_.map(h => s(h.hcursor.downField("node").focus.getOrElse(Json.Null), "id")))

  /** ID またはパスで指定したファイルの ID で処理する。 */
  private def onFile[A](call: Caller, ref: String)(f: String => IO[Either[String, A]]): IO[Either[String, A]] =
    resolveId(call, ref).flatMap(_.flatTraverse(f))

  /** 一覧の1行（「📁 名前 / 12.3 KB / 更新 / id」）。 */
  private def line(n: Json, where: Option[String] = None): String =
    val c     = n.hcursor
    val dir   = c.get[Boolean]("isDir").getOrElse(false)
    val name  = s(n, "name")
    val size  = if dir then "フォルダ" else Bytes.format(c.get[Long]("size").getOrElse(0L))
    val owner = c.downField("owner").get[String]("name").getOrElse("")
    s"- ${FileKind.icon(FileKind.kind(name, dir))} $name（$size、更新 ${s(n, "updatedAt").take(16)}、所有者 $owner、権限 ${s(n, "perm")}${where.fold("")(w => s"、場所 $w")}） id=${s(n, "id")}"

  private def hits(j: Json): String =
    val xs = j.asArray.toList.flatten
    if xs.isEmpty then "（ありません）"
    else xs.map(h => line(h.hcursor.downField("node").focus.getOrElse(Json.Null), h.hcursor.get[String]("path").toOption)).mkString("\n")

  private def urlOf(n: Json): String =
    val id = s(n, "id")
    if n.hcursor.get[Boolean]("isDir").getOrElse(false) then s"$base/files/$id" else s"$base/view/$id"

  /** 移動・コピーの結果。 */
  private def batch(j: Json, verb: String): String =
    val done = j.hcursor.downField("done").as[List[Json]].getOrElse(Nil)
    val errs = j.hcursor.downField("errors").as[List[String]].getOrElse(Nil)
    (s"${done.size}件を$verb しました" :: done.map(n => s"- ${s(n, "name")} id=${s(n, "id")}") ++ errs.map(e => s"- できなかったもの: $e")).mkString("\n")

  /** 共有の相手（名前・メール・グループ名・ID）を、ユーザーかグループの ID にする。 */
  private def sharee(call: Caller, who: String): IO[Either[String, (String, String)]] =
    call(Method.GET, path("api", "sharees").withQueryParam("q", who)).map(_.flatMap { j =>
      val us = j.hcursor.downField("users").as[List[Json]].getOrElse(Nil)
      val gs = j.hcursor.downField("groups").as[List[Json]].getOrElse(Nil)
      us.find(u => s(u, "name") == who || s(u, "id") == who).orElse(us.headOption).map(u => ShareKind.User -> s(u, "id"))
        .orElse(gs.find(g => s(g, "name") == who || s(g, "id") == who).orElse(gs.headOption).map(g => ShareKind.Group -> s(g, "id")))
        .toRight(s"共有の相手「$who」が見つかりません")
    })

  private val tools: List[Tool] = List(
    Tool("get_me", "自分（この APIキーの持ち主）の情報、容量の使用量、キーのスコープ。", schema(Nil), readOnly = true)((call, _) => call(Method.GET, path("api", "me")).asText),

    Tool("list_folder", "フォルダの中身（名前・大きさ・更新・ID）。folder を省くと自分のルート。", schema(Nil, "folder" -> str(fileArg)), readOnly = true) { (call, a) =>
      onFile(call, opt(a, "folder").getOrElse("/"))(id =>
        call(Method.GET, path("api", "files", id, "children")).map(_.map { f =>
          val crumbs = f.hcursor.downField("path").as[List[Json]].getOrElse(Nil).map(c => s(c, "name")).filter(_.nonEmpty)
          val items  = f.hcursor.downField("items").as[List[Json]].getOrElse(Nil)
          (s"フォルダ: /${crumbs.mkString("/")}（${items.size}件） ${urlOf(f.hcursor.downField("folder").focus.getOrElse(Json.Null))}" ::
            (if items.isEmpty then List("（空です）") else items.map(line(_)))).mkString("\n")
        }))
    },

    Tool("search_files", "名前でファイル・フォルダを探す（自分のもの・共有されたもの。日本語の部分一致、ひらがな・カタカナの違いは区別しない）。",
      schema(List("query"), "query" -> str("探す語（空白区切りで AND）"), "limit" -> int("件数（既定50）")), readOnly = true) { (call, a) =>
      need(a, "query").withArgs(q => call(Method.GET, path("api", "search").withQueryParam("q", q).withQueryParam("limit", a.get[Int]("limit").getOrElse(50))).map(_.map(hits)))
    },

    Tool("get_file", "ファイル・フォルダの情報（場所・大きさ・版・所有者・権限・URL）。", schema(List("file"), "file" -> str(fileArg)), readOnly = true) { (call, a) =>
      need(a, "file").withArgs(f => resolve(call, f).map(_.map(h => h.deepMerge(Json.obj("url" -> urlOf(h.hcursor.downField("node").focus.getOrElse(Json.Null)).asJson)).spaces2)))
    },

    Tool("read_file", "ファイルの中身を文字で読む。テキスト（txt・md・csv・ソースコードなど）はそのまま、docx・xlsx・pptx は文字だけを取り出す。",
      schema(List("file"), "file" -> str(fileArg), "maxChars" -> int(s"返す文字数の上限（既定 $DefaultChars）")), readOnly = true) { (call, a) =>
      need(a, "file").withArgs(f => resolve(call, f).flatMap {
        case Left(e) => IO.pure(Left(e))
        case Right(h) =>
          val n    = h.hcursor.downField("node").focus.getOrElse(Json.Null)
          val name = s(n, "name")
          val ext  = FileKind.ext(name)
          val max  = a.get[Int]("maxChars").toOption.filter(_ > 0).getOrElse(DefaultChars)
          if n.hcursor.get[Boolean]("isDir").getOrElse(false) then IO.pure(Left("フォルダです（list_folder を使ってください）"))
          else if FileKind.kind(name) != FileKind.Text && !OfficeText.supports(ext) then
            IO.pure(Left(s"「$name」は文字として読めない形式です（${Bytes.format(n.hcursor.get[Long]("size").getOrElse(0L))}）。画面で開く: ${urlOf(n)}"))
          else if n.hcursor.get[Long]("size").getOrElse(0L) > 50L * 1024 * 1024 then IO.pure(Left("大きすぎるため読めません（50MB まで）"))
          else call.bytes(path("api", "files", s(n, "id"), "content")).map(_.flatMap { b =>
            val text = if OfficeText.supports(ext) then OfficeText.extract(ext, b) else Right(new String(b, java.nio.charset.StandardCharsets.UTF_8))
            text.map(t => s"# $name（版 ${n.hcursor.get[Int]("version").getOrElse(1)}）\n" +
              (if t.length > max then t.take(max) + s"\n…（${t.length}文字のうち先頭の $max 文字）" else t))
          })
      })
    },

    Tool("write_file", "テキストのファイルを作る、または置き換える（置き換えは新しい版になり、以前の内容は残る）。",
      schema(List("folder", "name", "content"), "folder" -> str("置くフォルダ（" + fileArg + "）"), "name" -> str("ファイル名（memo.md など）"),
        "content" -> str("中身（UTF-8 のテキスト）"), "overwrite" -> bool("同じ名前があれば置き換える（既定 false: エラー）")), readOnly = false) { (call, a) =>
      (need(a, "folder"), need(a, "name"), a.get[String]("content").toOption.toRight("content を指定してください")).tupled.withArgs { (folder, name, content) =>
        onFile(call, folder) { id =>
          val uri = path("api", "files", id, "upload").withQueryParam("name", name)
          val u2  = if a.get[Boolean]("overwrite").getOrElse(false) then uri.withQueryParam("conflict", "overwrite") else uri
          call.putText(u2, content).map(_.map(n => s"「${s(n, "name")}」を保存しました（版 ${n.hcursor.get[Int]("version").getOrElse(1)}） id=${s(n, "id")} ${urlOf(n)}"))
        }
      }
    },

    Tool("create_folder", "フォルダを作る。", schema(List("parent", "name"), "parent" -> str("親のフォルダ（" + fileArg + "）"), "name" -> str("フォルダの名前")),
      readOnly = false) { (call, a) =>
      (need(a, "parent"), need(a, "name")).tupled.withArgs((p, nm) => onFile(call, p)(id =>
        call(Method.POST, path("api", "files", id, "folders"), Some(Json.obj("name" -> nm.asJson))).map(_.map(n => s"フォルダ「${s(n, "name")}」を作りました id=${s(n, "id")} ${urlOf(n)}"))))
    },

    Tool("create_office_file", "空の Office 文書を作る（docx・xlsx・pptx。ONLYOFFICE で開ける URL を返す）。",
      schema(List("folder", "name"), "folder" -> str("置くフォルダ（" + fileArg + "）"), "name" -> str("ファイル名（議事録.docx など。拡張子で種類が決まる）")),
      readOnly = false) { (call, a) =>
      (need(a, "folder"), need(a, "name")).tupled.withArgs((p, nm) => onFile(call, p)(id =>
        call(Method.POST, path("api", "files", id, "new"), Some(Json.obj("name" -> nm.asJson))).map(_.map(n => s"「${s(n, "name")}」を作りました id=${s(n, "id")} $base/office/${s(n, "id")}"))))
    },

    Tool("rename", "名前を変える。", schema(List("file", "name"), "file" -> str(fileArg), "name" -> str("新しい名前")), readOnly = false) { (call, a) =>
      (need(a, "file"), need(a, "name")).tupled.withArgs((f, nm) => onFile(call, f)(id =>
        call(Method.PATCH, path("api", "files", id), Some(Json.obj("name" -> nm.asJson))).map(_.map(n => s"「${s(n, "name")}」に変えました"))))
    },

    Tool("move", "ファイル・フォルダを別のフォルダへ移動する（copy: true ならコピー）。",
      schema(List("files", "to"), "files" -> strs("動かすもの（" + fileArg + "）の配列"), "to" -> str("移動先のフォルダ（" + fileArg + "）"),
        "copy" -> bool("コピーする（既定 false）"), "conflict" -> str("移動先に同じ名前があるとき: rename（番号を付ける）/ overwrite（移動先のものはごみ箱へ）。省くとエラー")),
      readOnly = false) { (call, a) =>
      (a.get[List[String]]("files").toOption.filter(_.nonEmpty).toRight("files を指定してください"), need(a, "to")).tupled.withArgs { (fs, to) =>
        (fs.traverse(resolveId(call, _)).map(_.sequence), resolveId(call, to)).tupled.flatMap {
          case (Right(ids), Right(target)) =>
            val copy = a.get[Boolean]("copy").getOrElse(false)
            call(Method.POST, path("api", "files", "transfer"), Some(MoveReq(ids.flatMap(uuidOpt), uuidOpt(target).get, copy, opt(a, "conflict")).asJson))
              .map(_.map(batch(_, if copy then "コピー" else "移動")))
          case (Left(e), _) => IO.pure(Left(e))
          case (_, Left(e)) => IO.pure(Left(e))
        }
      }
    },

    Tool("delete", "ファイル・フォルダをごみ箱へ移す（ごみ箱から戻せる）。", schema(List("files"), "files" -> strs("消すもの（" + fileArg + "）の配列")),
      readOnly = false, destructive = true) { (call, a) =>
      a.get[List[String]]("files").toOption.filter(_.nonEmpty).toRight("files を指定してください").withArgs(fs =>
        fs.traverse(resolveId(call, _)).map(_.sequence).flatMap(_.flatTraverse(ids =>
          call(Method.POST, path("api", "files", "delete"), Some(IdsReq(ids.flatMap(uuidOpt)).asJson)).map(_.map { j =>
            val errs = j.hcursor.downField("errors").as[List[String]].getOrElse(Nil)
            (s"${ids.size - errs.size}件をごみ箱へ移しました" :: errs.map(e => s"- できなかったもの: $e")).mkString("\n")
          }))))
    },

    Tool("list_recent", "最近更新したファイル。", schema(Nil, "limit" -> int("件数（既定30）")), readOnly = true) { (call, a) =>
      call(Method.GET, path("api", "recent").withQueryParam("limit", a.get[Int]("limit").getOrElse(30))).map(_.map(hits))
    },

    Tool("list_shared_with_me", "ほかの人から共有されたもの。", schema(Nil), readOnly = true)((call, _) => call(Method.GET, path("api", "shared-with-me")).map(_.map(hits))),

    Tool("share", "ファイル・フォルダをユーザーまたはグループと共有する（所有者だけ）。",
      schema(List("file", "with"), "file" -> str(fileArg), "with" -> str("相手（ユーザーの名前・メール、またはグループ名）"),
        "permission" -> str("view（閲覧のみ、既定）/ edit（編集可）"), "expiresAt" -> str("期限 yyyy-MM-dd（省くと無期限）")), readOnly = false) { (call, a) =>
      (need(a, "file"), need(a, "with")).tupled.withArgs((f, who) =>
        (resolveId(call, f), sharee(call, who)).tupled.flatMap {
          case (Right(id), Right((kind, target))) =>
            val req = CreateShareReq(kind, Option.when(kind == ShareKind.User)(uuidOpt(target)).flatten, Option.when(kind == ShareKind.Group)(uuidOpt(target)).flatten,
              opt(a, "permission").getOrElse(Perm.View), None, opt(a, "expiresAt"), None)
            call(Method.POST, path("api", "files", id, "shares"), Some(req.asJson.dropNullValues)).map(_.map(sh =>
              s"${sh.hcursor.downField("user").get[String]("name").orElse(sh.hcursor.downField("group").get[String]("name")).getOrElse(who)} と共有しました（${Perm.label(s(sh, "permission"))}）"))
          case (Left(e), _) => IO.pure(Left(e))
          case (_, Left(e)) => IO.pure(Left(e))
        })
    },

    Tool("create_link", "公開リンク（ログインなしで開ける URL）を作る（所有者だけ）。",
      schema(List("file"), "file" -> str(fileArg), "permission" -> str("view（既定）/ edit / upload（フォルダにアップロードだけ）"),
        "password" -> str("パスワード（省くとなし）"), "expiresAt" -> str("期限 yyyy-MM-dd"), "label" -> str("リンクの名前（メモ）")), readOnly = false) { (call, a) =>
      need(a, "file").withArgs(f => onFile(call, f) { id =>
        val req = CreateShareReq(ShareKind.Link, None, None, opt(a, "permission").getOrElse(Perm.View), opt(a, "password"), opt(a, "expiresAt"), opt(a, "label"))
        call(Method.POST, path("api", "files", id, "shares"), Some(req.asJson.dropNullValues)).map(_.map(sh =>
          s"公開リンクを作りました（${Perm.label(s(sh, "permission"))}${if sh.hcursor.get[Boolean]("hasPassword").getOrElse(false) then "、パスワードあり" else ""}）: ${s(sh, "url")}"))
      })
    },

    Tool("list_versions", "ファイルの版の一覧（先頭が今の版）。戻すには call_api で POST /api/files/{id}/versions/{versionId}/restore。",
      schema(List("file"), "file" -> str(fileArg)), readOnly = true) { (call, a) =>
      need(a, "file").withArgs(f => onFile(call, f)(id => call(Method.GET, path("api", "files", id, "versions")).map(_.map(j =>
        j.asArray.toList.flatten.map(v => s"- 版 ${v.hcursor.get[Int]("version").getOrElse(0)}${if v.hcursor.get[Boolean]("current").getOrElse(false) then "（今の版）" else ""}: ${s(v, "createdAt").take(16)}、" +
          s"${v.hcursor.downField("author").get[String]("name").getOrElse("")}、${Bytes.format(v.hcursor.get[Long]("size").getOrElse(0L))} versionId=${s(v, "id")}").mkString("\n")))))
    },

    Tool("list_activity", "自分のファイルと共有されたものの中の最近の変更（誰が・いつ・何を）。", schema(Nil, "limit" -> int("件数（既定50）")), readOnly = true) { (call, a) =>
      call(Method.GET, path("api", "activity").withQueryParam("limit", a.get[Int]("limit").getOrElse(50))).map(_.map(j =>
        j.asArray.toList.flatten.map(e => s"- ${s(e, "at").take(16)} ${e.hcursor.downField("actor").get[String]("name").getOrElse(s(e, "via"))}: ${s(e, "name")} を${EventKind.label(s(e, "kind"))}（${s(e, "via")}）").mkString("\n")))
    },

    Tool("list_trash", "ごみ箱の中身（自分が所有するもの）。戻すには restore_from_trash。", schema(Nil), readOnly = true) { (call, _) =>
      call(Method.GET, path("api", "trash")).map(_.map(j => j.asArray.toList.flatten.map(t =>
        s"- ${s(t, "name")}（元の場所 ${s(t, "originalPath")}、削除 ${s(t, "deletedAt").take(16)}） id=${s(t, "id")}").mkString("\n") match
        case "" => "ごみ箱は空です"
        case x  => x))
    },

    Tool("restore_from_trash", "ごみ箱から元の場所へ戻す。", schema(List("id"), "id" -> str("ごみ箱の項目の ID（list_trash を参照）")), readOnly = false) { (call, a) =>
      need(a, "id").withArgs(id => call(Method.POST, path("api", "trash", id, "restore")).map(_.map(n => s"「${s(n, "name")}」を戻しました ${urlOf(n)}")))
    },

    Tool("list_api_endpoints", "REST API の一覧。専用のツールがない操作（共有の変更・版の復元・お気に入りなど）は call_api で行える。", schema(Nil), readOnly = true) { (_, _) =>
      IO.pure(Right(ApiCatalog.all.map(e => s"${e.method} ${e.path} — ${e.description}").mkString("\n")))
    },

    Tool("call_api", "任意の REST API を呼ぶ（画面でできることはすべてできる。list_api_endpoints を参照）。JSON の API だけ（アップロードは write_file）。権限はこのキーの持ち主と同じ。",
      schema(List("method", "path"), "method" -> str("GET / POST / PUT / PATCH / DELETE"), "path" -> str("/api/ で始まるパス（クエリを含めてよい）"),
        "body" -> obj("JSON の本文")), readOnly = false) { (call, a) =>
      (need(a, "method").map(_.toUpperCase), need(a, "path")).tupled.withArgs { (m, p) =>
        (Method.fromString(m).toOption.filter(x => Set("GET", "POST", "PUT", "PATCH", "DELETE")(x.name)), Uri.fromString(p).toOption.filter(_.path.renderString.startsWith("/api/"))) match
          case (Some(method), Some(uri)) => call(method, uri, a.downField("body").focus.filterNot(_.isNull)).asText
          case (None, _) => IO.pure(Left("method は GET / POST / PUT / PATCH / DELETE です"))
          case _         => IO.pure(Left("path は /api/ で始めてください"))
      }
    }
  )

  private val byName = tools.map(t => t.name -> t).toMap

  // ------------------------------------------------------------------ JSON-RPC

  private def ok(id: Json, result: Json): Json = Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id, "result" -> result)
  private def rpcFail(id: Json, code: Int, msg: String): Json =
    Json.obj("jsonrpc" -> "2.0".asJson, "id" -> id, "error" -> Json.obj("code" -> code.asJson, "message" -> msg.asJson))
  private def toolResult(r: Either[String, String]): Json =
    Json.obj("content" -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> r.merge.asJson)), "isError" -> r.isLeft.asJson)

  private def handle(call: Caller, s: AuthSession, msg: Json): IO[Option[Json]] =
    val c      = msg.hcursor
    val params = c.downField("params")
    c.downField("id").focus match
      case None => IO.pure(None) // 通知（notifications/initialized など）には応答しない
      case Some(id) =>
        c.get[String]("method").getOrElse("") match
          case "initialize" =>
            val asked = params.get[String]("protocolVersion").toOption
            IO.pure(Some(ok(id, Json.obj(
              "protocolVersion" -> asked.filter(Versions.contains).getOrElse(Versions.head).asJson,
              "capabilities"    -> Json.obj("tools" -> Json.obj("listChanged" -> false.asJson)),
              "serverInfo"      -> Json.obj("name" -> "skawau".asJson, "version" -> "0.1.0".asJson),
              "instructions" -> ("skawau（チームのファイル共有）のファイル・フォルダを読み書きするツールです。" +
                "ファイルは ID か、自分のルートからのパス（/資料/2026/報告.docx）で指定します。list_folder でたどり、search_files で名前から探し、" +
                "read_file で中身を読みます（docx・xlsx・pptx は文字だけ）。削除はごみ箱へ、上書きは新しい版になるので元に戻せます。" +
                "専用のツールがない操作は list_api_endpoints で REST API を調べ、call_api で行えます。権限はこの APIキーの持ち主と同じです。").asJson
            ))))
          case "ping"       => IO.pure(Some(ok(id, Json.obj())))
          case "tools/list" => IO.pure(Some(ok(id, Json.obj("tools" -> tools.map(_.json).asJson))))
          case "tools/call" =>
            val name = params.get[String]("name").getOrElse("")
            byName.get(name) match
              case None => IO.pure(Some(rpcFail(id, -32602, s"unknown tool: $name")))
              case Some(t) if !s.canWrite && !t.readOnly => IO.pure(Some(ok(id, toolResult(Left("参照専用の APIキーでは変更できません")))))
              case Some(t) =>
                val args = params.downField("arguments").focus.filter(_.isObject).getOrElse(Json.obj()).hcursor
                t.run(call, args).handleError(e => Left(s"内部エラー: ${e.getMessage}")).map(r => Some(ok(id, toolResult(r))))
          case "resources/list" => IO.pure(Some(ok(id, Json.obj("resources" -> Json.arr()))))
          case "prompts/list"   => IO.pure(Some(ok(id, Json.obj("prompts" -> Json.arr()))))
          case other            => IO.pure(Some(rpcFail(id, -32601, s"method not found: $other")))

  private val unauthorized =
    Response[IO](Status.Unauthorized).putHeaders(`WWW-Authenticate`(Challenge("Bearer", "skawau"))).withEntity(ErrorDto("APIキー（Authorization: Bearer）が必要です"))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "mcp" =>
      req.headers.get[Authorization] match
        case Some(auth @ Authorization(Credentials.Token(AuthScheme.Bearer, _))) =>
          sessions.fromRequest(Request[IO](Method.GET, Uri.unsafeFromString("/mcp")).putHeaders(auth)).flatMap {
            case None => IO.pure(unauthorized)
            case Some(session) =>
              val call = Caller(auth)
              req.as[Json].attempt.flatMap {
                case Left(_) => BadRequest(rpcFail(Json.Null, -32700, "parse error"))
                case Right(j) =>
                  j.asArray match
                    case Some(batch) => batch.toList.traverse(handle(call, session, _)).map(_.flatten).flatMap(rs => if rs.isEmpty then Accepted() else Ok(rs.asJson))
                    case None        => handle(call, session, j).flatMap(_.fold(Accepted())(Ok(_)))
              }
          }
        case _ => IO.pure(unauthorized)

    case (GET | DELETE) -> Root / "mcp" => MethodNotAllowed(Allow(Method.POST))

    case GET -> Root / "api" / "endpoints" =>
      Ok(ApiCatalog.all.map(e => Json.obj("method" -> e.method.asJson, "path" -> e.path.asJson, "description" -> e.description.asJson)))
  }
