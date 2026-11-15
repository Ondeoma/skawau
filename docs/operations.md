# 構築・運用ガイド

## 1. 構成の選び方

| 規模 | 構成 |
|---|---|
| 小規模（1台） | [deploy/docker-compose.example.yml](../deploy/docker-compose.example.yml)。アプリ・PostgreSQL・S3 互換ストレージ・Document Server を1台で動かし、前に HTTPS のリバースプロキシを置く |
| 冗長化（3台） | skea・skiwi と同じ構成（各ノードに skawau-app・Patroni + PostgreSQL・etcd、入口は keepalived + HAProxy）。手順は skea の docs/operations.md と同じで、名前を skawau に読み替える |

どちらの構成でも、アプリはステートレスです（状態は PostgreSQL と S3 互換ストレージだけ）。

## 2. 必要なもの

- PostgreSQL 17 + PGroonga（名前の日本語検索）。冗長化する場合は Patroni。PGroonga の索引をレプリカへ複製するため、
  `shared_preload_libraries = pgroonga_wal_resource_manager` と `pgroonga.enable_wal_resource_manager = on` を全ノードに設定する（skea と同じ注意）。
- S3 互換ストレージ（パス形式でアクセスできるもの）。バケット（既定 `skawau`）がなければ起動時に作る。
- OIDC の IdP（Redirect URI は `${PUBLIC_URL}/auth/callback`、Scopes は `openid profile email`、`groups` クレームを入れるとグループへの共有と `ADMIN_GROUPS` に使える）。
- ONLYOFFICE を使う場合は ONLYOFFICE Docs（Document Server）。

## 3. ONLYOFFICE（Document Server）の設定

| 項目 | 設定 |
|---|---|
| JWT | Document Server の `JWT_ENABLED=true`、`JWT_SECRET` と skawau の `ONLYOFFICE_JWT_SECRET` を同じ値にする（32文字以上のランダム値） |
| URL | `ONLYOFFICE_URL`（ブラウザから）、`ONLYOFFICE_INTERNAL_URL`（skawau から。保存した文書を取りに行く）、`ONLYOFFICE_APP_URL`（Document Server から skawau へ） |
| ネットワーク | Document Server → skawau の `/onlyoffice/download/*`・`/onlyoffice/callback/*` に届くこと。skawau → Document Server の `/cache/files/*` に届くこと |
| プライベートアドレス | Document Server は既定でプライベートアドレスへの通信を断る。同じ社内ネットワーク・同じ Docker のネットワークで動かすときは `ALLOW_PRIVATE_IP_ADDRESS=true` |
| HTTPS | skawau を https で公開するなら Document Server も https にする（混在コンテンツになるため） |

- 保存は Document Server が行います。全員がエディタを閉じてから約10秒後、または保存ボタン（Ctrl+S）で、新しい版として skawau に送られてきます。
- 文書を開けないとき: ブラウザの開発者ツールで `api.js` が読めているか、Document Server のログ（`/var/log/onlyoffice/documentserver/docservice/out.log`）に
  `Error while downloading` が出ていないか（`ONLYOFFICE_APP_URL` に届かない・プライベートアドレスの制限）を確認します。
- 保存されないとき: skawau のログの `onlyoffice save failed`、Document Server から `ONLYOFFICE_APP_URL` に届くか、JWT の秘密が一致しているかを確認します。

## 4. リバースプロキシの注意

- アップロードの本文の上限を `MAX_UPLOAD_MB` 以上にする（nginx は `client_max_body_size`）。大きなファイルのためタイムアウトを長めにする。
- SSE（`/api/stream`）はバッファしない（`X-Accel-Buffering: no` を付けている。HAProxy は `timeout tunnel` を長めにする）。
- WebDAV のメソッド（PROPFIND・MKCOL・MOVE・COPY・LOCK・UNLOCK・PROPPATCH）を通す。Windows のエクスプローラーは https が必要。

## 5. バックアップ

| 対象 | 方法 | 頻度（例） |
|---|---|---|
| PostgreSQL | pgBackRest（WAL アーカイブ＋フル／差分）、1台なら `pg_dump` | フル: 毎週、差分: 毎日 |
| ファイルの中身（`skawau` バケット） | `rclone sync` で別の拠点へ複製 | 毎日 |
| 設定 | 環境変数（`APP_SECRET`・`ONLYOFFICE_JWT_SECRET` を含む） | 変更時 |

DB と S3 は別々に取るため、復旧後に DB にあって S3 にないファイルが出ることがあります（取得の時刻の差の間に作られたもの）。
DB を先に、S3 を後に取ると、この差を小さくできます（S3 にだけある古い中身は害がありません）。

## 6. 日常運用

### 監視項目
| 対象 | 確認方法 |
|---|---|
| アプリ | `GET /healthz`（DB がプライマリで書ける状態なら 200） |
| S3 の後片付け | `select count(*), max(attempts) from blob_trash`（増え続けるなら S3 に書けていない） |
| 容量 | 管理画面の「使用量」、`select sum(size) from node` |
| Document Server | `GET ${ONLYOFFICE_URL}/healthcheck` |

### 定期処理
- ごみ箱の期限切れ（`TRASH_DAYS`）の完全削除: 1時間ごと（1ノードだけ）。
- 完全に削除したファイル・古い版の S3 の中身の削除: 30秒ごと（全ノードで取り合う）。
- 180日より古い変更履歴・通知、360日より古い監査ログの削除: 1時間ごと。

### アプリの更新
1台ずつ入れ替えます。DB マイグレーションを含む更新は、後方互換なスキーマ変更（列の追加など）に限ります（旧版が同時に動くため）。
