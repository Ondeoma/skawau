# skawau

<p align="center"><img src="frontend/public/logo.svg" width="128" height="128" alt="skawau のロゴ"></p>

skawau は、チームのための Web ファイル共有システムです。
ONLYOFFICE と連携して、Word・Excel・PowerPoint の文書をブラウザで閲覧・編集できます。ライセンス: MIT

- **ファイル・フォルダ**: アップロード（ボタン・ドラッグ＆ドロップ・フォルダごと、進み具合の表示、同じ名前は「上書き（新しい版）・両方残す・送らない」を選べる）、
  ダウンロード（フォルダ・複数選択は ZIP）、新しいフォルダ、名前の変更、移動・コピー（行をフォルダやパンくずへドラッグしても移動できる）、
  一覧・タイル表示、並べ替え、絞り込み、画像のサムネイル
- **プレビュー**: 画像・動画（シーク可）・音声・PDF・テキスト。テキスト（Markdown・ソースコードなど）は画面で編集して保存できる
- **ONLYOFFICE**: docx・xlsx・pptx・odt・ods・odp などをブラウザで編集（複数人の同時編集も Document Server が行う）。doc・xls・ppt などは閲覧。
  「＋ 新規」から空の文書・スプレッドシート・プレゼンテーションを作れる。保存した内容は新しい版として残る
- **版の管理**: 上書きするたびに以前の内容を版として残す（既定で30版）。版のダウンロード・復元
- **ごみ箱**: 削除はごみ箱へ（既定で30日後に自動で完全に削除）。元の場所へ戻す（元のフォルダがなければルートへ）、完全に削除、空にする
- **共有**: ユーザー・グループ（IdP のグループ）へ、閲覧のみ・編集可で共有。期限を付けられる。共有されたものは「共有されたもの」に出る
- **公開リンク**: ログインなしで開けるリンク。閲覧のみ・編集可（アップロード・Office の編集）・アップロードのみ（フォルダ。ほかのファイルは見せない）、
  パスワード、期限、名前（メモ）
- **お気に入り・最近・検索**: 名前の検索は日本語の部分一致（PGroonga。ひらがな・カタカナ、全角・半角の違いを吸収）
- **アクティビティ・通知**: 自分のファイルと共有されたものの中の変更の履歴（誰が・いつ・何を、WebDAV・ONLYOFFICE・公開リンクの経路つき）、
  共有されたとき・リンクからアップロードされたときの通知
- **自動同期**: 変更はサーバーから画面へ即座に知らせる（Server-Sent Events）。ほかの人が共有のフォルダに置いたファイルもすぐ一覧に出る
- **WebDAV**: `/dav/` に、Finder・エクスプローラー・Cyberduck・rclone などからアプリパスワードで接続できる（削除はごみ箱へ、上書きは新しい版）
- **アイコン**: 個人設定で画像を登録できる（正方形に切り取って縮める）。登録しない人は IdP のアイコンか名前の1文字目
- **容量の上限**: 既定の上限（`DEFAULT_QUOTA_MB`）と、ユーザーごとの上限（管理画面）
- **認証・権限**: OIDC、システム管理者・一般。管理者でもほかの人のファイルの中身は見られない（共有されたものだけ）
- **API・MCP**: ユーザーごとのスコープ付き APIキー（アプリパスワード）で REST API を呼べる（一覧は `GET /api/endpoints`）。
  MCP（`POST /mcp`）で AI からファイルを探す・読む（docx・xlsx・pptx は文字を取り出す）・書く・移動・共有ができる
- **外部のサービスとの連携**: skea（タスク管理）・skiwi（Wiki）に貼った skawau の URL に、見る人の権限でファイル名を出す。
  skaka（Web 会議）は会議の録画を本人の skawau に保存し、参加者と共有する（OAuth 2.0 で、サービスごとに許したスコープだけ）
- **冗長化**: アプリはステートレスで複数ノードで動く。PostgreSQL は Patroni、ファイルの中身は S3 互換ストレージ
- レスポンシブ対応、ダークモード対応

設計は [docs/design.md](docs/design.md)、構築・運用は [docs/operations.md](docs/operations.md) を参照してください。
兄弟プロジェクトの skea（タスク管理）・skiwi（Wiki）・skaka（Web 会議）と連携します。skea・skiwi と同じ技術・同じ運用方針で作っています。

## 構成

| モジュール | 内容 |
|---|---|
| `modules/shared` | バックエンドと画面（Scala.js）で共有する API の型（circe）、ファイルの種類の判定 |
| `modules/core` | ドメインの純粋なロジック（モデル・名前の規則・Office のひな形） |
| `modules/db` | doobie のストア（ファイル・共有・変更履歴・ユーザー）、Flyway のマイグレーション、ジョブのリース |
| `modules/auth` | OIDC（Authorization Code + PKCE）、APIキー、JWT（ONLYOFFICE）、パスワードのハッシュ |
| `modules/storage` | S3 互換ストレージ（ファイルの中身。Range 取得） |
| `modules/api` | http4s のルート（REST・公開リンク・ONLYOFFICE・WebDAV・SSE・MCP・OAuth）とファイル操作のサービス |
| `modules/app` | 起動・設定（環境変数）・画面の配信・定期処理 |
| `modules/web` | 画面（[Scalactive](https://github.com/Ondeoma/scalactive) の `.st` 単一ファイルコンポーネント） |
| `frontend/` | Vite。Scala.js の出力をまとめてビルドする（フォルダのドロップ・ONLYOFFICE の読み込みの小さな JS を含む） |
| `deploy/` | Docker のひな形（アプリ・ScalaS3・本番の例） |

技術: Scala 3.3 LTS、Cats Effect 3、http4s 1.0（マイルストーン版）、doobie、Flyway、PostgreSQL 17 + PGroonga、S3 互換ストレージ、
Scala.js + Scalactive、ONLYOFFICE Docs（Document Server）。

## 開発

必要なもの: JDK 21、sbt、Node.js、Docker。

```bash
docker compose -f docker-compose.dev.yml up -d --wait   # PostgreSQL+PGroonga (:54340)、ScalaS3 (:3911)、ONLYOFFICE (:8093)
scripts/dev-restart.sh                                   # バックエンドを :8092 で起動（scripts/dev-env.sh の設定。DEV_LOGIN=true）
(cd frontend && npm install && npm run dev)              # 画面の開発サーバー http://localhost:5176（/api などは :8092 へ中継）
sbt ~web/fastLinkJS                                      # 画面の変更を再リンクし続ける（ブラウザを再読み込みすると反映）
```

- 開発用のログイン（`DEV_LOGIN=true`）では、名前を入れるだけでログインできます（グループも指定できる）。本番では使わないでください。
- ONLYOFFICE の Document Server は起動に1分ほどかかります。開発用の設定では、Document Server からアプリへ `host.docker.internal:8092` で届きます。
- `scripts/smoke.sh` で API（共有・公開リンク・版・ごみ箱・WebDAV を含む）を一通り確認できます。

テスト（DB のテストは `TEST_DATABASE_URL` があるときだけ動く）:

```bash
TEST_DATABASE_URL=jdbc:postgresql://localhost:54340/skawau_test sbt test
```

**DB マイグレーション**（`modules/db/src/main/resources/db/migration`）: スキーマを変えるときは新しい番号の `V<n>__*.sql` を追加し、適用済みのファイルは書き換えないでください。

## 配布・起動

```bash
scripts/dist.sh                   # dist/lib/*.jar と dist/static（画面）を作る
java -cp 'dist/lib/*' skawau.app.Main
```

Docker で動かす例（アプリ・DB・S3 互換ストレージ・ONLYOFFICE）は [deploy/docker-compose.example.yml](deploy/docker-compose.example.yml)。

主な環境変数（すべては `modules/app/src/main/scala/skawau/app/Main.scala` の `Config`）:

| 変数 | 内容 |
|---|---|
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | PostgreSQL（PGroonga が必要）。冗長構成では3台を並べて `targetServerType=primary` |
| `PUBLIC_URL` | 公開 URL（`https://` なら Cookie に Secure が付く）。公開リンク・WebDAV の URL にも使う |
| `OIDC_ISSUER` / `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` | OIDC。Redirect URI は `${PUBLIC_URL}/auth/callback`。ログアウトでは IdP からもログアウトし、`${PUBLIC_URL}/` に戻る（sruru 以外の IdP では、この戻り先も登録する） |
| `OIDC_GROUPS_CLAIM` | 所属グループのクレーム名（既定 `groups`）。グループへの共有に使う |
| `ADMIN_GROUPS` | システム管理者にするグループ（カンマ区切り）。画面からも管理者を付けられる |
| `APP_SECRET` | 16文字以上の秘密の値（公開リンクの Cookie・Document Server に渡す URL の署名） |
| `S3_ENDPOINT` / `S3_BUCKET` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` / `S3_REGION` / `S3_PATH_STYLE` | ファイルの中身の保存先（必須） |
| `ONLYOFFICE_URL` | ブラウザから見た Document Server の URL（未設定なら ONLYOFFICE 連携なし。Office 文書はダウンロードだけ） |
| `ONLYOFFICE_JWT_SECRET` | Document Server の `JWT_SECRET` と同じ値（必須。32文字以上） |
| `ONLYOFFICE_INTERNAL_URL` | このサーバーから見た Document Server の URL（既定は `ONLYOFFICE_URL`） |
| `ONLYOFFICE_APP_URL` | Document Server から見たこのサーバーの URL（既定は `PUBLIC_URL`） |
| `MAX_UPLOAD_MB` | 1ファイルの上限（既定 10240 = 10GB） |
| `DEFAULT_QUOTA_MB` | ユーザーの容量の既定の上限（未設定なら無制限。管理画面でユーザーごとに変えられる） |
| `VERSIONS_KEPT` | 残す以前の版の数（既定 30） |
| `TRASH_DAYS` | ごみ箱のものを完全に削除するまでの日数（既定 30） |
| `STATIC_DIR` | ビルド済みの画面（`dist/static`） |
| `NODE_ID` | ノード名（ログ・定期処理のリース） |
| `OAUTH_CLIENTS` | 認可を求める外部のサービス（skea・skiwi・skaka）。`[{"id":"skea","secret":"…（16文字以上）","redirectUri":"https://…/api/integrations/skawau/callback","name":"skea"}]`。`scopes`（既定 `["read"]`）で求めてよいスコープを決める（skaka は `["read","write"]`） |
| `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_HEADERS` | ログ・メトリクス・トレースの送り先（skorimako の URL と `Authorization=Bearer sko_…`（ingest のキー））。未設定なら送らない。そのほか OpenTelemetry の標準の `OTEL_*` が使える |
| `LOG_FORMAT` / `LOG_LEVEL` | 標準出力のログの形（`text`（既定）・`json`）と水準（既定 `INFO`）。ログにはトレースの ID と操作したユーザーが付く |
| `SKORIMAKO_RUM_TOKEN` / `SKORIMAKO_RUM_URL` / `SKORIMAKO_RUM_SAMPLE_RATE` | 画面のブラウザの観測（skorimako の RUM）。トークンは skorimako の管理の画面で登録したアプリのもの。URL を省略すると `SKORIMAKO_URL` |
| `VAPID_SUBJECT` | プッシュ通知（PWA）の連絡先（`mailto:…` か `https://…`。省略すると `PUBLIC_URL`）。VAPID の鍵は初回の起動で作って DB に保存する |
| `DEV_LOGIN` | 開発用のログインを有効にする（OIDC を設定しない場合のみ） |

## WebDAV・API・MCP

- 画面の「個人設定」でアプリパスワード（`skawau_` で始まる APIキー）を作ります。スコープは読み書き・読み取りのみ。
- **WebDAV**: `${PUBLIC_URL}/dav/` に Basic 認証（ユーザー名は任意、パスワードにアプリパスワード）で接続します。例（rclone）:

```bash
rclone copy ./資料 :webdav:資料 --webdav-url https://skawau.example.com/dav/ --webdav-vendor other --webdav-user me --webdav-pass "$(rclone obscure skawau_xxxx)"
```

- **REST API**: `Authorization: Bearer <キー>` で、画面と同じ API（`/api/...`）を呼べます。一覧は `GET /api/endpoints`。
  アップロードは `PUT /api/files/{フォルダID}/upload?name=…`（本文がそのまま中身）、パスでの参照は `GET /api/files/resolve?path=/資料/2026`。
  変更履歴には経路（api・mcp・dav など）が残ります。

```bash
curl -H "Authorization: Bearer skawau_xxxx" https://skawau.example.com/api/files/root
curl -H "Authorization: Bearer skawau_xxxx" -T 報告書.pdf "https://skawau.example.com/api/files/<フォルダID>/upload?name=報告書.pdf&conflict=overwrite"
```

- **MCP**: `POST /mcp`（Streamable HTTP、JSON 応答）に APIキー（Bearer）で接続します。個人設定の「API・MCP」に接続のコマンドが出ます。例（Claude Code）:

```bash
claude mcp add --transport http skawau https://skawau.example.com/mcp --header "Authorization: Bearer skawau_xxxxxxxx"
```

MCP のツール: `get_me`・`list_folder`・`search_files`・`get_file`・`read_file`（テキストはそのまま、docx・xlsx・pptx は文字を取り出す）・`write_file`・
`create_folder`・`create_office_file`・`rename`・`move`（コピーも）・`delete`（ごみ箱へ）・`list_recent`・`list_shared_with_me`・`share`・`create_link`・
`list_versions`・`list_activity`・`list_trash`・`restore_from_trash`、および画面でできるすべての操作のための `list_api_endpoints`・`call_api`。
ファイルは ID か、自分のルートからのパス（`/資料/2026/報告.docx`）で指定します。どのツールも内部で REST API を同じキーで呼ぶので、権限・スコープは REST API と同じです
（「読み取りのみ」のキーでは変更のツールは使えません）。

## skea・skiwi・skaka との連携

skea・skiwi に貼った skawau のファイル・フォルダの URL（`/view/{ID}`・`/office/{ID}`・`/files/{ID}`）に、見る人の権限でファイル名を出すための仕組みです。
取り決めは skea・skiwi の `docs/skawau-integration.md` にあります。skaka（Web 会議）は同じ仕組みで、会議の録画を本人の skawau に保存します（skaka の `docs/design.md`）。

- `OAUTH_CLIENTS` に登録したサービスが、OAuth 2.0（認可コード + PKCE、S256 のみ）でユーザーごとにトークンを受け取ります
  （`/oauth/authorize`・`/oauth/token`・`/oauth/revoke`）。スコープは `scope` で求めたもの（既定は `read`）で、そのサービスの `scopes` に含まれるものだけ。
  skaka は `read write` を求め、録画を `PUT /api/files/{ルート}/upload?path=skaka 録画/…` で置き、会議の参加者と共有します。
- トークンは「<サービス名> 連携」というアプリパスワードとして個人設定に表示され、そこから失効できます。WebDAV には使えず、書き込みは write を許したサービスだけです。許可し直すと前のものは失効します。
- `POST /api/files/lookup {"ids":[…]}`（100件まで）で、見られるもの（自分のもの・共有されたもの）だけ
  `{"files":[{"id","name","folder","isDir","mimeType"}]}` を返します（ごみ箱・存在しない・権限なしは区別せず返さない）。
- `POST /api/users/lookup {"emails":[…]}`（100件まで）で、メールアドレスが完全に一致する（大文字・小文字は区別しない）ユーザーを
  `{"users":[{"id","name","email"}]}` で返します（skaka が録画を参加者と共有するため）。
- 開発用の値（`scripts/dev-env.sh`）: skea は `skea` / `skea-dev-secret-0123456789` / `http://localhost:8090/api/integrations/skawau/callback`、
  skiwi は `skiwi` / `skiwi-dev-secret-0123456789` / `http://localhost:5173/api/integrations/skawau/callback`、
  skaka は `skaka` / `skaka-dev-secret-0123456789` / `http://localhost:5177/api/integrations/skawau/callback`（`read write`）。
  連携元に入れる skawau の URL は `http://localhost:5176`（Vite が `/oauth`・`/api` を :8092 へ中継し、127.0.0.1 で待ち受ける）。
