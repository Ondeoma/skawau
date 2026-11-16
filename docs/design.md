# skawau の設計

skawau は、クラウドストレージ（ファイルの保存・共有・Office 文書の編集）に機能を絞った、チームのためのファイル共有システムです。
兄弟プロジェクトの skea（タスク管理）・skiwi（Wiki）と同じ技術・同じ運用方針で作ります。
skea・skiwi・skaka（Web 会議）とは OAuth で連携します（8章）。

## 1. 要件サマリ

| 区分 | 内容 |
|---|---|
| テナント | シングルテナント（マルチテナントは持たない） |
| 認証 | OIDC（Authorization Code + PKCE）。Cookie のセッション、ユーザー単位のスコープ付き APIキー（WebDAV のアプリパスワードを兼ねる） |
| 権限 | システム権限（管理者・一般）。ファイルは所有者と、共有された人（閲覧のみ・編集可）。管理者でもほかの人のファイルは見られない |
| データ | ユーザーごとのルートのフォルダ → フォルダ・ファイル（任意の階層） |
| 画面 | すべてのファイル、最近、お気に入り、共有されたもの、共有したもの、アクティビティ、ごみ箱、検索、通知、個人設定、管理、公開リンク、Office のエディタ |
| Office | ONLYOFFICE Docs（Document Server）。ブラウザで閲覧・編集（同時編集）。保存は新しい版 |
| 版・ごみ箱 | 上書きで以前の内容を版として残す（既定30版）。削除はごみ箱へ（既定30日で完全に削除） |
| 共有 | ユーザー・グループへの共有（閲覧のみ・編集可・期限）、公開リンク（閲覧のみ・編集可・アップロードのみ、パスワード、期限） |
| 同期 | サーバーが変更を画面へ即座に知らせ（SSE）、画面は表示中の一覧を取り直す（つながらないときは15秒ごとに確認） |
| 外部の道具 | WebDAV（クラス1 + 形だけのロック）。REST API |
| 基盤 | PostgreSQL（Patroni で冗長化）+ PGroonga（名前の日本語検索）、S3 互換ストレージ（開発は ScalaS3）、アプリは複数ノードで動かせるステートレス構成 |
| 実装 | Scala 3.3 LTS、Cats Effect 3、http4s 1.0（マイルストーン版）、doobie、Flyway。画面は Scalactive（Scala.js） |

## 2. 全体アーキテクチャ

```
     Browser (Scalactive SPA)            Finder / Explorer / rclone
        │ HTTPS（REST / SSE）                 │ WebDAV（/dav/、アプリパスワード）
        │      ┌───────────────────────┐      │
        └─────▶│ skawau-app（複数ノード可） │◀─────┘
               │  http4s                │
               └──┬────────┬────────┬───┘
                  │        │        │ ①設定（JWT）を画面へ  ②文書の取得 ③保存の知らせ
          PostgreSQL   S3互換     ONLYOFFICE Document Server ◀── Browser（エディタの iframe）
          +PGroonga   ストレージ
```

- アプリはステートレスです。状態はすべて PostgreSQL と S3 互換ストレージに置きます（セッション・OIDC のログイン途中の状態も DB）。
- 画面の同期は「SSE の知らせ + 取り直し」です。各ノードは1秒ごとに DB の新しい変更履歴（`file_event`）と通知を確かめ、
  関係する人（所有者と共有の相手）の画面へ知らせるだけなので、ノード間の連絡や振り分けの親和性は不要です。
- バックグラウンド処理: S3 の後片付け（`blob_trash` を `FOR UPDATE SKIP LOCKED` で取り合う）は全ノード、
  ごみ箱の期限切れの削除・古い履歴の削除は `job_lock`（期限付きのリース）で1ノードだけが実行します。

## 3. データモデル（PostgreSQL）

詳細は `modules/db/src/main/resources/db/migration`。

- `app_user(id, sub, name, email, avatar_url, avatar_key, is_admin, disabled, quota_bytes, …)`
  … `avatar_key` は画面から登録したアイコン（S3 の `avatars/<ユーザーID>/<UUID>`）。登録している間はログイン時に IdP のアイコンで上書きしない、`user_group`・`group_member`（IdP の groups クレームから同期）
- `session`、`login_state`、`api_key(id, user_id, name, prefix, key_hash, scopes, expires_at, last_used_at, revoked_at)`
- `node(id, owner_id, parent_id, ancestors uuid[], name, is_dir, size, content_type, blob_key, sha256, etag, version, created_at, updated_at,
  modified_by, deleted_at, deleted_by, trash_path)`
  - 各ユーザーにルートのフォルダ（`parent_id` が null、名前は空）が1つある。
  - `ancestors` はルートから親までの ID（GIN 索引）。共有の判定（「自分か祖先に共有があるか」）、見える範囲の検索、移動時の付け替えに使う。
    移動は `ancestors = 新しい親の系列 || ancestors[自分の位置:]` の1つの UPDATE で子孫ごと付け替える。
  - 名前は親の中で一意（大文字小文字を区別しない。`lower(name)` の一意索引。ごみ箱のものは除く）。名前は NFC に正規化する（macOS の濁点の分解を防ぐ）。
  - ごみ箱: 削除した起点の行にだけ `deleted_at` を付ける。子孫の行はそのままで、起点が消えていれば見えない（「生きている」＝自分も祖先も `deleted_at` が null）。
    元の場所へ戻すときに元のフォルダがなければルートへ、同じ名前があれば「名前 (2)」にする。
  - 共有のフォルダの中に作ったものの所有者は、フォルダの所有者（容量もフォルダの所有者に数える）。所有者の違うフォルダへ移すと、子孫ごと所有者を移す。
- `file_version(id, node_id, version, size, content_type, blob_key, sha256, created_at, created_by)` … 以前の版（今の版は `node` に持つ）。
  `VERSIONS_KEPT` を超えた古い版は消す。0バイトの版は残さない（WebDAV のクライアントは空のファイルを作ってから中身を書くため）。
  中身が同じ保存（sha256 が一致）は版を増やさない。
- `blob_trash(key)` … 消す S3 のオブジェクト。DB の削除（完全に削除・古い版）と同じトランザクションで積み、ワーカーが消す（DB と S3 の食い違いを防ぐ）。
- `favorite(user_id, node_id)`
- `share(id, node_id, owner_id, kind, user_id, group_id, permission, token, password_hash, expires_at, label)`
  … `kind` は `user` / `group` / `link`、`permission` は `view` / `edit`（公開リンクのフォルダは `upload` も）。
- `file_event(id, node_id, owner_id, lineage uuid[], actor_id, via, kind, name, path, detail)` … 変更履歴。`lineage`（祖先と自分）で、
  共有の相手に知らせる範囲・アクティビティに出す範囲を決める。所有者以外にはパス（共有の起点より上のフォルダ名）を見せない。
- `notification`、`job_lock`、`audit_log`

ファイルの中身は S3 に `files/<UUID>` のキーで置く（アイコンは `avatars/…`。差し替えた前の画像も `blob_trash` から消す）（中身ごとにキーを分け、重複排除はしない。コピー・版の復元は S3 の複製）。

## 4. 認証・権限

- **ログイン**: OIDC（Authorization Code + PKCE）。ID token の `iss`/`aud`/`exp`/`nonce` を検証（skea・skiwi と同じ）。
  `state` はログインを始めたブラウザの Cookie と照合する（他人が始めたログインの callback を踏ませる攻撃を防ぐ）。
  IdP 未設定の開発環境では `DEV_LOGIN=true` で名前だけのログインを許可する（`APP_SECRET` の固定の既定値も、OIDC も https もないときだけ）。
- **CSRF**: セッションの Cookie は SameSite=Lax。加えて Cookie での更新系の要求は `Sec-Fetch-Site`（なければ `Origin`）で同じオリジンからのものだけを受け付ける
  （同じサイトの別のサブドメインからの送信も拒む）。全応答に `X-Frame-Options: SAMEORIGIN`・`frame-ancestors 'self'`・`nosniff`・`Referrer-Policy` を付ける。
- **APIキー（アプリパスワード）**: `skawau_` で始まるランダム値。ハッシュのみ保存。REST は `Authorization: Bearer`、WebDAV は Basic のパスワード。
  スコープは `read` / `write` / `admin`。キーでキーの発行・失効はできない。WebDAV は Cookie を受け付けない（CSRF を避ける）。
- **ファイルの権限**（実効の権限 = 所有者 > 編集可 > 閲覧のみ）:
  - 所有者はすべて。共有は「自分か祖先に付いた共有の最大」（ユーザーと所属グループ。期限切れは除く）。見えないものは 404。
  - 中身の書き換えはそのノードの編集権限、フォルダの中への作成はフォルダの編集権限。
  - **名前の変更・移動・削除は「親フォルダ」の編集権限**が必要。共有された起点そのものは、受け取った人が消したり動かしたりできない
    （共有された人は起点の親を見られないため、この規則だけで決まる）。
  - 共有の作成・変更は所有者だけ（再共有はしない）。
  - 所有者の違うフォルダへの移動は所有者ごと移るので、自分のものだけ（ほかの人のものはコピーする）。中のごみ箱にあるものは元の所有者のルートの下へ外して残し、
    共有・リンクは新しい所有者のものにする。容量には以前の版も数える。
- **公開リンク**: トークン（推測できない 18 バイトの乱数）で起点と、その子孫だけを扱える。パスワードつきのリンクは PBKDF2 で照合し、
  そのリンクのパスだけに効く署名つき Cookie（24時間。パスワードを変えると無効）を渡す。間違いはリンクごとに15分で10回まで、
  PBKDF2 の計算は同時に4つまで（ノードごと）。`upload` のリンクは中身の一覧・ダウンロードを返さない。
  リンクからの操作は変更履歴に `via = link` として残し、アップロードは所有者に通知する。

## 5. ファイルの取り出し（REST・公開リンク・WebDAV で共通）

- Range（1つの範囲）に対応（動画のシーク）。`ETag`（中身の etag と版）で条件付き GET（304）。
- 表示（inline）は画像・動画・音声・PDF・テキストだけ。HTML・SVG などスクリプトを含み得る形式は、表示の指定でも保存させる（attachment）。
  `Content-Type` はいまの名前から決める（HTML を上げてから名前を .pdf に変えても、HTML として表示されない）。
  PDF 以外には `Content-Security-Policy: sandbox` を付け、万一表示されてもスクリプトを動かさない。`X-Content-Type-Options: nosniff`。
- フォルダ・複数選択は ZIP にまとめてストリームで返す（S3 から順に読みながら書き出す。ファイル名は UTF-8）。

## 6. ONLYOFFICE（Document Server）との連携

1. 画面が `GET /api/files/{id}/office`（公開リンクは `/api/public/{token}/office?id=`）でエディタの設定を受け取り、
   Document Server の `api.js` の `DocsAPI.DocEditor` に渡す。設定全体を `ONLYOFFICE_JWT_SECRET` で署名して `token` に入れる。
2. 設定の `document.url` は `${ONLYOFFICE_APP_URL}/onlyoffice/download/{id}?t=…`。`t` は `APP_SECRET` で署名した期限付き（6時間）の値で、
   Document Server はログインなしで文書を取れる。
3. 編集が終わる（全員が閉じる）か保存ボタン（強制保存）で、Document Server が `editorConfig.callbackUrl`
   （`/onlyoffice/callback/{id}?t=…`）に知らせる。本文の `token`（または Authorization ヘッダー）の JWT を検証し、
   status 2・6 のときは `url` から文書を取って新しい版として保存する。`url` の Document Server の公開 URL は `ONLYOFFICE_INTERNAL_URL` に置き換えて取りに行き、
   それ以外のホストへは取りに行かない。`t` には編集を許したか・誰の権限か（公開リンクはリンクの ID）・期限（7日）を入れ、
   保存のときにその人・そのリンクにいまも編集の権限があるかを確かめ直す。`ONLYOFFICE_JWT_SECRET` は32文字以上。
4. 文書キー（`document.key`）は「ノードID_版」。保存のたびに版が進むので、次に開くと新しい文書として扱われる。
   同時に開いている人は同じキーなので、Document Server の中で同時編集になる。
- 編集できるのは、編集の権限があり、編集できる形式（docx・xlsx・pptx・odt・ods・odp・rtf など）のとき。古い形式（doc・xls・ppt）は閲覧のみ。
- 新しい文書は、外部のひな形ファイルを持たずに最小構成の OOXML をコードで組み立てる（`OfficeTemplates`。docx は 10.5pt・游明朝、pptx は 16:9 のタイトルのスライド）。

## 7. WebDAV

- `/dav/` が自分のルート。PROPFIND（Depth 0 / 1）、GET / HEAD（Range）、PUT（上書きは新しい版）、MKCOL、DELETE（ごみ箱へ）、MOVE / COPY（Overwrite ヘッダー）、
  PROPPATCH（受け付けるだけ）、LOCK / UNLOCK（形だけ。Finder・Windows のため）。
- 共有されたものは WebDAV には出さない（自分のファイルだけ）。

## 8. API・MCP

- REST API は画面が使うものと同じ。`Authorization: Bearer <APIキー>` で呼ぶ（一覧は `GET /api/endpoints`、MCP の `list_api_endpoints` と同じ表 `ApiCatalog`）。
- MCP は `POST /mcp`（Streamable HTTP、JSON 応答、JSON-RPC のバッチ可）。APIキー（Bearer）だけを受け付ける。
  専用のツール（list_folder・read_file・write_file・move・share など）と、「ユーザーができることはすべて」を保証する `list_api_endpoints`・`call_api` を持つ。
- どのツールも内部で REST API を同じキーで呼ぶ（`X-Skawau-Mcp` ヘッダーを付ける）ので、権限・スコープ・監査は REST API と同じになり、変更履歴の経路は `mcp` になる。
- `read_file` は docx・xlsx・pptx から文字だけを取り出す（`OfficeText`。DOCTYPE を拒否して XXE を防ぎ、部品の大きさに上限を設ける）。
- 外部のサービスとの連携（OAuth）で発行したキーは WebDAV に使えない。スコープはサービスごとに許したもの（`OAUTH_CLIENTS` の `scopes`。既定は read）。
  skaka（Web 会議）には write を許し、会議の録画を本人のルートの「skaka 録画」に置かせ、参加者（`POST /api/users/lookup` でメールアドレスから引く）と共有させる。

## 9. 画面の自動同期（SSE）

- 画面は `GET /api/stream`（Server-Sent Events）につなぐ。サーバーは自分に関係する変更（`change`）と通知（`notify`）があると知らせる（20秒ごとに空の行）。
- 知らせを受けると（まとめて1回）表示中の一覧・通知の件数・容量を取り直す。SSE がつながらないときは15秒ごと、つながっている間も念のため60秒ごとに確かめる。

## 10. 冗長構成・デプロイ

- skea・skiwi と同じく、各ノードに `skawau-app`、Patroni + PostgreSQL(+PGroonga)、etcd。入口は keepalived（VIP）と HAProxy。S3 互換ストレージは複製のあるもの。
- `/healthz` は DB のプライマリに書けるときだけ 200。起動時のマイグレーションは Flyway のロックで1回だけ適用される。
- Document Server は別に用意する（1台、または ONLYOFFICE Docs の冗長構成）。アプリのどのノードに保存の知らせが届いてもよい（DB と S3 に書くだけのため）。
- 大きなファイルのアップロード・ダウンロードはストリームで扱う（アップロードは一時ファイルに受けてから S3 へ）。プロキシの本文の上限・タイムアウトに注意する。

## 11. sbt モジュール構成

```
skawau
├─ shared   バックエンドと画面(Scala.js)で共有する API の型、ファイルの種類の判定
├─ core     ドメイン（モデル、名前の規則、Office のひな形・文字の取り出し）
├─ db       doobie のストアと Flyway マイグレーション、ジョブのリース
├─ auth     OIDC クライアント、APIキー、JWT、パスワードのハッシュ
├─ storage  S3 互換ストレージ
├─ api      http4s のルート（REST・公開リンク・ONLYOFFICE・WebDAV・SSE・MCP・OAuth）とファイル操作のサービス
├─ app      起動・設定（環境変数）・画面の配信・定期処理
└─ web      Scalactive(Scala.js)で書いた画面
frontend/   Vite。web の出力をまとめてビルドし、app が配信する
```
