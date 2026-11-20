package skawau.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{ACursor, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Authorization
import sbirds.mcp.{McpCaller, McpServer, McpTool}
import sbirds.mcp.McpSchema.*
import sbirds.http.Errors
import skawau.core.*
import skawau.shared.*

/**
 * MCP（Model Context Protocol、Streamable HTTP の JSON 応答）。`POST /mcp` に APIキー（Bearer）で接続する。
 * どのツールも内部で REST API を同じキーで呼ぶので、権限・スコープ・変更履歴（経路 mcp）は REST API と同じになる。
 * 専用のツールがない操作も list_api_endpoints と call_api でできる（画面でできることはすべて）。
 */
final class McpRoutes(api: HttpRoutes[IO], sessions: SessionAuth, publicUrl: String) extends Http4sDsl[IO] {

  // POST /mcp（JSON-RPC）と GET・DELETE /mcp（405）は sbirds.mcp.McpServer
  lazy val routes: HttpRoutes[IO] = McpServer.routes(host) <+> HttpRoutes.of[IO] {
    case GET -> Root / "api" / "endpoints" => getApiEndpoints()
  }

  private def getApiEndpoints(): IO[Response[IO]] =
    Ok(ApiCatalog.all.map(e =>
      Json.obj("method" -> e.method.asJson, "path" -> e.path.asJson, "description" -> e.description.asJson)
    ))

  private val base = publicUrl.stripSuffix("/")

  /** read_file で返す文字数の上限（既定）。 */
  private val DefaultChars = 100000

  // ------------------------------------------------------------------ REST API の内部呼び出し（sbirds.mcp.McpCaller）に足す、skawau の部分

  private type Caller = McpCaller

  extension (call: Caller) {

    /** 中身をバイト列のまま受け取る（read_file）。 */
    private def bytes(uri: Uri): IO[Either[String, Array[Byte]]] =
      api.run(Request[IO](Method.GET, uri).putHeaders(call.auth, McpHeader -> "1")).value.flatMap {
        case None => IO.pure(Left("404 見つかりません"))
        case Some(r) if r.status.isSuccess => r.body.compile.to(Array).map(Right(_))
        case Some(r) => r.bodyText.compile.string.map { s =>
            Left(
              s"${r.status.code} ${parse(s).toOption.flatMap(_.hcursor.get[String]("error").toOption).getOrElse(r.status.reason)}"
            )
          }
      }

    private def putText(uri: Uri, text: String): IO[Either[String, Json]] =
      call(Method.PUT, uri, Some(Json.fromString(text)))
  }

  // ------------------------------------------------------------------ ツール

  private type Result = McpTool.Result
  private type Tool = McpTool[Caller]
  private def Tool(name: String, description: String, schema: Json, readOnly: Boolean, destructive: Boolean = false)(
    run: (Caller, ACursor) => Result): Tool = McpTool(name, description, schema, readOnly, destructive)(run)
  private def path(segments: String*): Uri = McpCaller.path(segments*)

  private val fileArg = "ファイル・フォルダの ID、または自分のルートからのパス（/資料/2026/報告.docx）"

  private def opt(a: ACursor, k: String): Option[String] = a.get[String](k).toOption.map(_.trim).filter(_.nonEmpty)

  /** ID またはパスを、ノードの情報（NodeHitDto の JSON: node・path）にする。 */
  private def resolve(call: Caller, v: String): IO[Either[String, Json]] =
    uuidOpt(v) match {
      case Some(id) => call(Method.GET, path("api", "files", id.toString))
      case None if v.trim == "/" || v.trim.isEmpty =>
        call(Method.GET, path("api", "files", "root")).map(_.map(f =>
          Json.obj("node" -> f.hcursor.downField("folder").focus.getOrElse(Json.Null), "path" -> "/".asJson)
        ))
      case None => call(Method.GET, path("api", "files", "resolve").withQueryParam("path", v))
    }

  private def resolveId(call: Caller, v: String): IO[Either[String, String]] =
    resolve(call, v).map(_.map(h => s(h.hcursor.downField("node").focus.getOrElse(Json.Null), "id")))

  /** ID またはパスで指定したファイルの ID で処理する。 */
  private def onFile[A](call: Caller, ref: String)(f: String => IO[Either[String, A]]): IO[Either[String, A]] =
    resolveId(call, ref).flatMap(_.flatTraverse(f))

  /** 一覧の1行（「📁 名前 / 12.3 KB / 更新 / id」）。 */
  private def line(n: Json, where: Option[String] = None): String = {
    val c = n.hcursor
    val dir = c.get[Boolean]("isDir").getOrElse(false)
    val name = s(n, "name")
    val size = if dir then "フォルダ" else Bytes.format(c.get[Long]("size").getOrElse(0L))
    val owner = c.downField("owner").get[String]("name").getOrElse("")
    s"- ${FileKind.icon(FileKind.kind(name, dir))} $name（$size、更新 ${s(n, "updatedAt").take(
        16
      )}、所有者 $owner、権限 ${s(n, "perm")}${where.fold("")(w => s"、場所 $w")}） id=${s(n, "id")}"
  }

  private def hits(j: Json): String = {
    val xs = j.asArray.toList.flatten
    if xs.isEmpty then "（ありません）"
    else
      xs.map(h =>
        line(h.hcursor.downField("node").focus.getOrElse(Json.Null), h.hcursor.get[String]("path").toOption)
      ).mkString("\n")
  }

  private def urlOf(n: Json): String = {
    val id = s(n, "id")
    if n.hcursor.get[Boolean]("isDir").getOrElse(false) then s"$base/files/$id" else s"$base/view/$id"
  }

  /** 移動・コピーの結果。 */
  private def batch(j: Json, verb: String): String = {
    val done = j.hcursor.downField("done").as[List[Json]].getOrElse(Nil)
    val errs = j.hcursor.downField("errors").as[List[String]].getOrElse(Nil)
    (s"${done.size}件を$verb しました" ::
      done.map(n => s"- ${s(n, "name")} id=${s(n, "id")}") ++ errs.map(e => s"- できなかったもの: $e")).mkString("\n")
  }

  /** 共有の相手（名前・メール・グループ名・ID）を、ユーザーかグループの ID にする。 */
  private def sharee(call: Caller, who: String): IO[Either[String, (String, String)]] =
    call(Method.GET, path("api", "sharees").withQueryParam("q", who)).map(_.flatMap { j =>
      val us = j.hcursor.downField("users").as[List[Json]].getOrElse(Nil)
      val gs = j.hcursor.downField("groups").as[List[Json]].getOrElse(Nil)
      us.find(u => s(u, "name") == who || s(u, "id") == who).orElse(us.headOption).map(u =>
        ShareKind.User -> s(u, "id")
      )
        .orElse(gs.find(g => s(g, "name") == who || s(g, "id") == who).orElse(gs.headOption).map(g =>
          ShareKind.Group -> s(g, "id")
        ))
        .toRight(s"共有の相手「$who」が見つかりません")
    })

  private val tools: List[Tool] = List(
    Tool("get_me", "自分（この APIキーの持ち主）の情報、容量の使用量、キーのスコープ。", schema(Nil), readOnly = true)((call, _) =>
      call(Method.GET, path("api", "me")).asText
    ),

    Tool(
      "list_folder",
      "フォルダの中身（名前・大きさ・更新・ID）。folder を省くと自分のルート。",
      schema(Nil, "folder" -> str(fileArg)),
      readOnly = true
    ) {
      (call, a) =>
        onFile(call, opt(a, "folder").getOrElse("/"))(id =>
          call(Method.GET, path("api", "files", id, "children")).map(_.map { f =>
            val crumbs =
              f.hcursor.downField("path").as[List[Json]].getOrElse(Nil).map(c => s(c, "name")).filter(_.nonEmpty)
            val items = f.hcursor.downField("items").as[List[Json]].getOrElse(Nil)
            (s"フォルダ: /${crumbs.mkString("/")}（${items.size}件） ${urlOf(f.hcursor.downField("folder").focus.getOrElse(Json.Null))}" ::
              (if items.isEmpty then List("（空です）") else items.map(line(_)))).mkString("\n")
          })
        )
    },

    Tool(
      "search_files",
      "名前でファイル・フォルダを探す（自分のもの・共有されたもの。日本語の部分一致、ひらがな・カタカナの違いは区別しない）。",
      schema(List("query"), "query" -> str("探す語（空白区切りで AND）"), "limit" -> int("件数（既定50）")),
      readOnly = true
    ) {
      (call, a) =>
        need(a, "query").withArgs(q =>
          call(
            Method.GET,
            path("api", "search").withQueryParam("q", q).withQueryParam("limit", a.get[Int]("limit").getOrElse(50))
          ).map(_.map(hits))
        )
    },

    Tool(
      "get_file",
      "ファイル・フォルダの情報（場所・大きさ・版・所有者・権限・URL）。",
      schema(List("file"), "file" -> str(fileArg)),
      readOnly = true
    ) {
      (call, a) =>
        need(a, "file").withArgs(f =>
          resolve(call, f).map(_.map(h =>
            h.deepMerge(Json.obj("url" -> urlOf(h.hcursor.downField("node").focus.getOrElse(Json.Null)).asJson)).spaces2
          ))
        )
    },

    Tool(
      "read_file",
      "ファイルの中身を文字で読む。テキスト（txt・md・csv・ソースコードなど）はそのまま、docx・xlsx・pptx は文字だけを取り出す。",
      schema(List("file"), "file" -> str(fileArg), "maxChars" -> int(s"返す文字数の上限（既定 $DefaultChars）")),
      readOnly = true
    ) {
      (call, a) =>
        need(a, "file").withArgs(f =>
          resolve(call, f).flatMap {
            case Left(e) => IO.pure(Left(e))
            case Right(h) =>
              val n = h.hcursor.downField("node").focus.getOrElse(Json.Null)
              val name = s(n, "name")
              val ext = FileKind.ext(name)
              val max = a.get[Int]("maxChars").toOption.filter(_ > 0).getOrElse(DefaultChars)
              if n.hcursor.get[Boolean]("isDir").getOrElse(false) then IO.pure(Left("フォルダです（list_folder を使ってください）"))
              else if FileKind.kind(name) != FileKind.Text && !OfficeText.supports(ext) then
                IO.pure(Left(
                  s"「$name」は文字として読めない形式です（${Bytes.format(n.hcursor.get[Long]("size").getOrElse(0L))}）。画面で開く: ${urlOf(n)}"
                ))
              else if n.hcursor.get[Long]("size").getOrElse(0L) > 50L * 1024 * 1024 then
                IO.pure(Left("大きすぎるため読めません（50MB まで）"))
              else
                call.bytes(path("api", "files", s(n, "id"), "content")).map(_.flatMap { b =>
                  val text = if OfficeText.supports(ext) then OfficeText.extract(ext, b)
                  else Right(new String(b, java.nio.charset.StandardCharsets.UTF_8))
                  text.map(t =>
                    s"# $name（版 ${n.hcursor.get[Int]("version").getOrElse(1)}）\n" +
                      (if t.length > max then t.take(max) + s"\n…（${t.length}文字のうち先頭の $max 文字）" else t)
                  )
                })
          }
        )
    },

    Tool(
      "write_file",
      "テキストのファイルを作る、または置き換える（置き換えは新しい版になり、以前の内容は残る）。",
      schema(
        List("folder", "name", "content"),
        "folder" -> str("置くフォルダ（" + fileArg + "）"),
        "name" -> str("ファイル名（memo.md など）"),
        "content" -> str("中身（UTF-8 のテキスト）"),
        "overwrite" -> bool("同じ名前があれば置き換える（既定 false: エラー）")
      ),
      readOnly = false
    ) {
      (call, a) =>
        (
          need(a, "folder"),
          need(a, "name"),
          a.get[String]("content").toOption.toRight("content を指定してください")
        ).tupled.withArgs {
          (folder, name, content) =>
            onFile(call, folder) { id =>
              val uri = path("api", "files", id, "upload").withQueryParam("name", name)
              val u2 = if a.get[Boolean]("overwrite").getOrElse(false) then
                uri.withQueryParam("conflict", "overwrite")
              else uri
              call.putText(u2, content).map(_.map(n =>
                s"「${s(n, "name")}」を保存しました（版 ${n.hcursor.get[Int]("version").getOrElse(1)}） id=${s(n, "id")} ${urlOf(n)}"
              ))
            }
        }
    },

    Tool(
      "create_folder",
      "フォルダを作る。",
      schema(List("parent", "name"), "parent" -> str("親のフォルダ（" + fileArg + "）"), "name" -> str("フォルダの名前")),
      readOnly = false
    ) {
      (call, a) =>
        (need(a, "parent"), need(a, "name")).tupled.withArgs((p, nm) =>
          onFile(call, p)(id =>
            call(Method.POST, path("api", "files", id, "folders"), Some(Json.obj("name" -> nm.asJson))).map(_.map(n =>
              s"フォルダ「${s(n, "name")}」を作りました id=${s(n, "id")} ${urlOf(n)}"
            ))
          )
        )
    },

    Tool(
      "create_office_file",
      "空の Office 文書を作る（docx・xlsx・pptx。ONLYOFFICE で開ける URL を返す）。",
      schema(
        List("folder", "name"),
        "folder" -> str("置くフォルダ（" + fileArg + "）"),
        "name" -> str("ファイル名（議事録.docx など。拡張子で種類が決まる）")
      ),
      readOnly = false
    ) {
      (call, a) =>
        (need(a, "folder"), need(a, "name")).tupled.withArgs((p, nm) =>
          onFile(call, p)(id =>
            call(Method.POST, path("api", "files", id, "new"), Some(Json.obj("name" -> nm.asJson))).map(_.map(n =>
              s"「${s(n, "name")}」を作りました id=${s(n, "id")} $base/office/${s(n, "id")}"
            ))
          )
        )
    },

    Tool(
      "rename",
      "名前を変える。",
      schema(List("file", "name"), "file" -> str(fileArg), "name" -> str("新しい名前")),
      readOnly = false
    ) {
      (call, a) =>
        (need(a, "file"), need(a, "name")).tupled.withArgs((f, nm) =>
          onFile(call, f)(id =>
            call(Method.PATCH, path("api", "files", id), Some(Json.obj("name" -> nm.asJson))).map(_.map(n =>
              s"「${s(n, "name")}」に変えました"
            ))
          )
        )
    },

    Tool(
      "move",
      "ファイル・フォルダを別のフォルダへ移動する（copy: true ならコピー）。",
      schema(
        List("files", "to"),
        "files" -> strs("動かすもの（" + fileArg + "）の配列"),
        "to" -> str("移動先のフォルダ（" + fileArg + "）"),
        "copy" -> bool("コピーする（既定 false）"),
        "conflict" -> str("移動先に同じ名前があるとき: rename（番号を付ける）/ overwrite（移動先のものはごみ箱へ）。省くとエラー")
      ),
      readOnly = false
    ) {
      (call, a) =>
        (
          a.get[List[String]]("files").toOption.filter(_.nonEmpty).toRight("files を指定してください"),
          need(a, "to")
        ).tupled.withArgs {
          (fs, to) =>
            (fs.traverse(resolveId(call, _)).map(_.sequence), resolveId(call, to)).tupled.flatMap {
              case (Right(ids), Right(target)) =>
                val copy = a.get[Boolean]("copy").getOrElse(false)
                call(
                  Method.POST,
                  path("api", "files", "transfer"),
                  Some(MoveReq(ids.flatMap(uuidOpt), uuidOpt(target).get, copy, opt(a, "conflict")).asJson)
                )
                  .map(_.map(batch(_, if copy then "コピー" else "移動")))
              case (Left(e), _) => IO.pure(Left(e))
              case (_, Left(e)) => IO.pure(Left(e))
            }
        }
    },

    Tool(
      "delete",
      "ファイル・フォルダをごみ箱へ移す（ごみ箱から戻せる）。",
      schema(List("files"), "files" -> strs("消すもの（" + fileArg + "）の配列")),
      readOnly = false,
      destructive = true
    ) {
      (call, a) =>
        a.get[List[String]]("files").toOption.filter(_.nonEmpty).toRight("files を指定してください").withArgs(fs =>
          fs.traverse(resolveId(call, _)).map(_.sequence).flatMap(_.flatTraverse(ids =>
            call(Method.POST, path("api", "files", "delete"), Some(IdsReq(ids.flatMap(uuidOpt)).asJson)).map(_.map {
              j =>
                {
                  val errs = j.hcursor.downField("errors").as[List[String]].getOrElse(Nil)
                  (s"${ids.size - errs.size}件をごみ箱へ移しました" :: errs.map(e => s"- できなかったもの: $e")).mkString("\n")
                }
            })
          ))
        )
    },

    Tool("list_recent", "最近更新したファイル。", schema(Nil, "limit" -> int("件数（既定30）")), readOnly = true) {
      (call, a) =>
        call(
          Method.GET,
          path("api", "recent").withQueryParam("limit", a.get[Int]("limit").getOrElse(30))
        ).map(_.map(hits))
    },

    Tool("list_shared_with_me", "ほかの人から共有されたもの。", schema(Nil), readOnly = true)((call, _) =>
      call(Method.GET, path("api", "shared-with-me")).map(_.map(hits))
    ),

    Tool(
      "share",
      "ファイル・フォルダをユーザーまたはグループと共有する（所有者だけ）。",
      schema(
        List("file", "with"),
        "file" -> str(fileArg),
        "with" -> str("相手（ユーザーの名前・メール、またはグループ名）"),
        "permission" -> str("view（閲覧のみ、既定）/ edit（編集可）"),
        "expiresAt" -> str("期限 yyyy-MM-dd（省くと無期限）")
      ),
      readOnly = false
    ) {
      (call, a) =>
        (need(a, "file"), need(a, "with")).tupled.withArgs((f, who) =>
          (resolveId(call, f), sharee(call, who)).tupled.flatMap {
            case (Right(id), Right((kind, target))) =>
              val req = CreateShareReq(
                kind,
                Option.when(kind == ShareKind.User)(uuidOpt(target)).flatten,
                Option.when(kind == ShareKind.Group)(uuidOpt(target)).flatten,
                opt(a, "permission").getOrElse(Perm.View),
                None,
                opt(a, "expiresAt"),
                None
              )
              call(Method.POST, path("api", "files", id, "shares"), Some(req.asJson.dropNullValues)).map(_.map(sh =>
                s"${sh.hcursor.downField("user").get[String]("name").orElse(sh.hcursor.downField("group").get[String]("name")).getOrElse(who)} と共有しました（${Perm.label(s(sh, "permission"))}）"
              ))
            case (Left(e), _) => IO.pure(Left(e))
            case (_, Left(e)) => IO.pure(Left(e))
          }
        )
    },

    Tool(
      "create_link",
      "公開リンク（ログインなしで開ける URL）を作る（所有者だけ）。",
      schema(
        List("file"),
        "file" -> str(fileArg),
        "permission" -> str("view（既定）/ edit / upload（フォルダにアップロードだけ）"),
        "password" -> str("パスワード（省くとなし）"),
        "expiresAt" -> str("期限 yyyy-MM-dd"),
        "label" -> str("リンクの名前（メモ）")
      ),
      readOnly = false
    ) {
      (call, a) =>
        need(a, "file").withArgs(f =>
          onFile(call, f) { id =>
            {
              val req = CreateShareReq(
                ShareKind.Link,
                None,
                None,
                opt(a, "permission").getOrElse(Perm.View),
                opt(a, "password"),
                opt(a, "expiresAt"),
                opt(a, "label")
              )
              call(Method.POST, path("api", "files", id, "shares"), Some(req.asJson.dropNullValues)).map(_.map(sh =>
                s"公開リンクを作りました（${Perm.label(s(sh, "permission"))}${
                    if sh.hcursor.get[Boolean]("hasPassword").getOrElse(false) then "、パスワードあり" else ""
                  }）: ${s(sh, "url")}"
              ))
            }
          }
        )
    },

    Tool(
      "list_versions",
      "ファイルの版の一覧（先頭が今の版）。戻すには call_api で POST /api/files/{id}/versions/{versionId}/restore。",
      schema(List("file"), "file" -> str(fileArg)),
      readOnly = true
    ) {
      (call, a) =>
        need(a, "file").withArgs(f =>
          onFile(call, f)(id =>
            call(Method.GET, path("api", "files", id, "versions")).map(_.map(j =>
              j.asArray.toList.flatten.map(v =>
                s"- 版 ${v.hcursor.get[Int]("version").getOrElse(0)}${
                    if v.hcursor.get[Boolean]("current").getOrElse(false) then "（今の版）" else ""
                  }: ${s(v, "createdAt").take(16)}、" +
                  s"${v.hcursor.downField("author").get[String]("name").getOrElse("")}、${Bytes.format(v.hcursor.get[Long]("size").getOrElse(0L))} versionId=${s(v, "id")}"
              ).mkString("\n")
            ))
          )
        )
    },

    Tool(
      "list_activity",
      "自分のファイルと共有されたものの中の最近の変更（誰が・いつ・何を）。",
      schema(Nil, "limit" -> int("件数（既定50）")),
      readOnly = true
    ) {
      (call, a) =>
        call(
          Method.GET,
          path("api", "activity").withQueryParam("limit", a.get[Int]("limit").getOrElse(50))
        ).map(_.map(j =>
          j.asArray.toList.flatten.map(e =>
            s"- ${s(e, "at").take(16)} ${e.hcursor.downField("actor").get[String]("name").getOrElse(s(e, "via"))}: ${s(e, "name")} を${EventKind.label(s(e, "kind"))}（${s(e, "via")}）"
          ).mkString("\n")
        ))
    },

    Tool("list_trash", "ごみ箱の中身（自分が所有するもの）。戻すには restore_from_trash。", schema(Nil), readOnly = true) {
      (call, _) =>
        call(Method.GET, path("api", "trash")).map(_.map(j =>
          j.asArray.toList.flatten.map(t =>
            s"- ${s(t, "name")}（元の場所 ${s(t, "originalPath")}、削除 ${s(t, "deletedAt").take(16)}） id=${s(t, "id")}"
          ).mkString("\n") match {
            case "" => "ごみ箱は空です"
            case x => x
          }
        ))
    },

    Tool(
      "restore_from_trash",
      "ごみ箱から元の場所へ戻す。",
      schema(List("id"), "id" -> str("ごみ箱の項目の ID（list_trash を参照）")),
      readOnly = false
    ) {
      (call, a) =>
        need(a, "id").withArgs(id =>
          call(Method.POST, path("api", "trash", id, "restore")).map(_.map(n => s"「${s(n, "name")}」を戻しました ${urlOf(n)}"))
        )
    },

    Tool(
      "list_api_endpoints",
      "REST API の一覧。専用のツールがない操作（共有の変更・版の復元・お気に入りなど）は call_api で行える。",
      schema(Nil),
      readOnly = true
    ) {
      (_, _) =>
        IO.pure(Right(ApiCatalog.all.map(e => s"${e.method} ${e.path} — ${e.description}").mkString("\n")))
    },

    Tool(
      "call_api",
      "任意の REST API を呼ぶ（画面でできることはすべてできる。list_api_endpoints を参照）。権限はこのキーの持ち主と同じ。" +
        "body がオブジェクト・配列なら JSON で送る。body が文字列なら、そのまま本文として送る（中身を置き換える PUT /api/files/{id}/content など。テキストのファイルを作るのは write_file）。",
      schema(
        List("method", "path"),
        "method" -> str("GET / POST / PUT / PATCH / DELETE"),
        "path" -> str("/api/ で始まるパス（クエリを含めてよい）"),
        "body" -> Json.obj("description" -> "本文（オブジェクト・配列は JSON で送る。文字列はそのまま本文になる）".asJson)
      ),
      readOnly = false
    ) {
      (call, a) =>
        (need(a, "method").map(_.toUpperCase), need(a, "path")).tupled.withArgs {
          (m, p) =>
            (
              Method.fromString(m).toOption.filter(x => Set("GET", "POST", "PUT", "PATCH", "DELETE")(x.name)),
              Uri.fromString(p).toOption.filter(_.path.renderString.startsWith("/api/"))
            ) match {
              case (Some(method), Some(uri)) => call(method, uri, a.downField("body").focus.filterNot(_.isNull)).asText
              case (None, _) => IO.pure(Left("method は GET / POST / PUT / PATCH / DELETE です"))
              case _ => IO.pure(Left("path は /api/ で始めてください"))
            }
        }
    }
  )

  // ------------------------------------------------------------------ MCP の枠（sbirds.mcp.McpServer）に差し込む、skawau の部分

  private lazy val host = new McpServer.Host[AuthSession, Caller] {
    val settings = McpServer.Settings(
      "skawau",
      toolFailed = ErrorCode.McpToolFailed,
      maxBatch = 50,
      batchTooLarge = n => s"バッチは${n}件までです",
      // write_file の中身を本文で受けるので、/mcp だけ上限を大きくする
      maxBodyBytes = Some(BodyLimit.Mcp),
      readOnlyDenied = "参照専用の APIキーでは変更できません",
      hideDenied = false
    )
    val tools = McpRoutes.this.tools
    def session(req: Request[IO], auth: Authorization) =
      sessions.fromRequest(Request[IO](Method.GET, Uri.unsafeFromString("/mcp")).putHeaders(auth))
    def unauthorized = Errors.respond(ErrorCode.McpApiKeyRequired)
    def context(req: Request[IO], auth: Authorization, s: AuthSession) =
      new McpCaller(api, auth, List(McpHeader -> "1"), build = McpRoutes.request)
    def canWrite(s: AuthSession) = s.canWrite
    def instructions(s: AuthSession) =
      "skawau（チームのファイル共有）のファイル・フォルダを読み書きするツールです。" +
        "ファイルは ID か、自分のルートからのパス（/資料/2026/報告.docx）で指定します。list_folder でたどり、search_files で名前から探し、" +
        "read_file で中身を読みます（docx・xlsx・pptx は文字だけ）。削除はごみ箱へ、上書きは新しい版になるので元に戻せます。" +
        "専用のツールがない操作は list_api_endpoints で REST API を調べ、call_api で行えます。権限はこの APIキーの持ち主と同じです。"
  }
}

object McpRoutes {

  /**
   * REST API を内部で呼ぶ要求。body が JSON の文字列なら、引用符で囲まず、そのまま本文にする
   * （write_file のアップロードや中身の置き換えは本文がそのまま中身なので、JSON の文字列にすると `"<svg ...\n..."` のように壊れる）。
   */
  def request(method: Method, uri: Uri, body: Option[Json]): Request[IO] = {
    val req = Request[IO](method, uri)
    body match {
      case None => req
      // CirceEntityCodec を import した所では withEntity(String) も JSON の文字列になるので、文字のエンコーダーを明示する
      case Some(j) if j.isString => req.withEntity(j.asString.getOrElse(""))(using EntityEncoder.stringEncoder)
      case Some(j) => req.withEntity(j)
    }
  }
}
