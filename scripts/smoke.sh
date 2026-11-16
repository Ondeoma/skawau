#!/usr/bin/env bash
# API を一通り確かめる（開発用のログインが有効なサーバーに対して）。例: scripts/smoke.sh http://localhost:8092
set -euo pipefail
BASE=${1:-http://localhost:8092}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
A="$WORK/a.cookie"; B="$WORK/b.cookie"
ok()   { printf '  \033[32mOK\033[0m %s\n' "$1"; }
ng()   { printf '  \033[31mNG\033[0m %s\n' "$1"; exit 1; }
j()    { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1], {'d': d}))" "$1"; }
call() { local c=$1; shift; curl -sS -b "$c" -c "$c" "$@"; }

echo "ログイン"
curl -sS -c "$A" -H 'Content-Type: application/json' -d '{"name":"スモーク太郎","admin":true,"groups":["smoke-team"]}' "$BASE/auth/dev-login" >/dev/null
curl -sS -c "$B" -H 'Content-Type: application/json' -d '{"name":"スモーク花子","groups":["smoke-team"]}' "$BASE/auth/dev-login" >/dev/null
ME_B=$(call "$B" "$BASE/api/me" | j "d['id']")
ok "2人でログイン"

echo "フォルダ・アップロード"
ROOT=$(call "$A" "$BASE/api/files/root" | j "d['folder']['id']")
DIR=$(call "$A" -H 'Content-Type: application/json' -d "{\"name\":\"スモーク $(date +%s)\"}" "$BASE/api/files/$ROOT/folders" | j "d['id']")
echo "こんにちは 1" > "$WORK/hello.txt"
F=$(call "$A" -T "$WORK/hello.txt" "$BASE/api/files/$DIR/upload?name=hello.txt" | j "d['id']")
[ "$(call "$A" "$BASE/api/files/$F/content")" = "こんにちは 1" ] && ok "アップロードして読み出せる" || ng "読み出し"
code=$(call "$A" -o /dev/null -w '%{http_code}' -T "$WORK/hello.txt" "$BASE/api/files/$DIR/upload?name=hello.txt")
[ "$code" = 409 ] && ok "同じ名前は 409" || ng "同じ名前 $code"
echo "こんにちは 2" > "$WORK/hello.txt"
V=$(call "$A" -T "$WORK/hello.txt" "$BASE/api/files/$DIR/upload?name=hello.txt&conflict=overwrite" | j "d['version']")
[ "$V" = 2 ] && ok "上書きで版が進む" || ng "版 $V"
NV=$(call "$A" "$BASE/api/files/$F/versions" | j "len(d)")
[ "$NV" = 2 ] && ok "版の一覧（今の版と以前の版）" || ng "版の一覧 $NV"
call "$A" -T "$WORK/hello.txt" "$BASE/api/files/$DIR/upload?name=a.txt&path=sub/deep" >/dev/null
SUB=$(call "$A" "$BASE/api/files/$DIR/children" | j "[i['id'] for i in d['items'] if i['name']=='sub'][0]")
ok "途中のフォルダを作ってアップロード"
R=$(curl -sS -b "$A" -H 'Range: bytes=0-2' -o /dev/null -w '%{http_code}' "$BASE/api/files/$F/content")
[ "$R" = 206 ] && ok "Range で一部を取れる" || ng "Range $R"

echo "Office の新規作成"
for k in docx xlsx pptx; do
  D=$(call "$A" -H 'Content-Type: application/json' -d "{\"name\":\"新規.$k\"}" "$BASE/api/files/$DIR/new" | j "d['id']")
  call "$A" -o "$WORK/new.$k" "$BASE/api/files/$D/content?download=1"
  python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).testzip()" "$WORK/new.$k" && ok "$k は正しい ZIP"
done
OFF=$(call "$A" -o /dev/null -w '%{http_code}' "$BASE/api/files/$D/office")
[ "$OFF" = 200 ] || [ "$OFF" = 400 ] && ok "Office の設定（$OFF: 400 は ONLYOFFICE 未設定）" || ng "office $OFF"

echo "共有"
code=$(call "$B" -o /dev/null -w '%{http_code}' "$BASE/api/files/$DIR/children")
[ "$code" = 404 ] && ok "共有前は見えない" || ng "共有前 $code"
call "$A" -H 'Content-Type: application/json' -d "{\"kind\":\"user\",\"userId\":\"$ME_B\",\"permission\":\"view\"}" "$BASE/api/files/$DIR/shares" >/dev/null
[ "$(call "$B" "$BASE/api/shared-with-me" | j "any(h['node']['id']=='$DIR' for h in d)")" = True ] && ok "共有されたものに出る" || ng "共有されたもの"
code=$(call "$B" -o /dev/null -w '%{http_code}' -T "$WORK/hello.txt" "$BASE/api/files/$DIR/upload?name=b.txt")
[ "$code" = 403 ] && ok "閲覧のみでは書けない" || ng "閲覧のみ $code"
[ "$(call "$B" "$BASE/api/notifications/unread" | j "d['unread']")" -ge 1 ] && ok "共有の通知" || ng "通知"
SID=$(call "$A" "$BASE/api/files/$DIR/shares" | j "d[0]['id']")
call "$A" -X PATCH -H 'Content-Type: application/json' -d '{"permission":"edit"}' "$BASE/api/shares/$SID" >/dev/null
code=$(call "$B" -o /dev/null -w '%{http_code}' -T "$WORK/hello.txt" "$BASE/api/files/$SUB/upload?name=b.txt")
[ "$code" = 200 ] && ok "編集可なら中にアップロードできる" || ng "編集可 $code"
code=$(call "$B" -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d "{\"ids\":[\"$DIR\"]}" "$BASE/api/files/delete")
[ "$(call "$A" -o /dev/null -w '%{http_code}' "$BASE/api/files/$DIR")" = 200 ] && ok "共有の起点は受け取った人が消せない" || ng "起点の削除"

echo "公開リンク"
LINK=$(call "$A" -H 'Content-Type: application/json' -d '{"kind":"link","permission":"view","password":"secret-pass"}' "$BASE/api/files/$DIR/shares" | j "d['token']")
[ "$(curl -sS "$BASE/api/public/$LINK" | j "d['needsPassword']")" = True ] && ok "パスワードが必要" || ng "needsPassword"
[ "$(call "$A" "$BASE/api/shared-by-me" | j "sorted(x['share']['kind'] for x in d if x['node']['id']=='$DIR')")" = "['link', 'user']" ] && ok "共有したもの（ユーザーとリンク）" || ng "共有したもの"
code=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/api/public/$LINK/folder")
[ "$code" = 401 ] && ok "パスワードなしでは見えない" || ng "パスワードなし $code"
L="$WORK/l.cookie"
curl -sS -c "$L" -H 'Content-Type: application/json' -d '{"password":"secret-pass"}' "$BASE/api/public/$LINK/auth" >/dev/null
N=$(curl -sS -b "$L" "$BASE/api/public/$LINK/folder" | j "len(d['items'])")
[ "$N" -ge 3 ] && ok "パスワードを入れると見える（$N 件）" || ng "リンクの中身 $N"
[ "$(curl -sS -b "$L" "$BASE/api/public/$LINK/content?id=$F")" = "こんにちは 2" ] && ok "リンクからダウンロード" || ng "リンクのダウンロード"
code=$(curl -sS -b "$L" -o /dev/null -w '%{http_code}' -T "$WORK/hello.txt" "$BASE/api/public/$LINK/upload?name=x.txt")
[ "$code" = 403 ] && ok "閲覧のリンクではアップロードできない" || ng "リンクのアップロード $code"
curl -sS -b "$L" -o "$WORK/l.zip" "$BASE/api/public/$LINK/zip"
python3 -c "import zipfile,sys; print(len(zipfile.ZipFile(sys.argv[1]).namelist()))" "$WORK/l.zip" | grep -qv '^0$' && ok "フォルダを ZIP でダウンロード"

echo "移動・コピー・ごみ箱"
C=$(call "$A" -H 'Content-Type: application/json' -d "{\"ids\":[\"$F\"],\"targetId\":\"$SUB\",\"copy\":true}" "$BASE/api/files/transfer" | j "d['done'][0]['id']")
call "$A" -H 'Content-Type: application/json' -d "{\"ids\":[\"$C\"],\"targetId\":\"$DIR\",\"copy\":false,\"conflict\":\"rename\"}" "$BASE/api/files/transfer" | j "d['done'][0]['name']" | grep -q 'hello (2).txt' && ok "コピーして、同じ名前は (2) にして移動"
call "$A" -H 'Content-Type: application/json' -d "{\"ids\":[\"$C\"]}" "$BASE/api/files/delete" >/dev/null
[ "$(call "$A" "$BASE/api/trash" | j "any(t['id']=='$C' for t in d)")" = True ] && ok "ごみ箱に入る" || ng "ごみ箱"
call "$A" -X POST "$BASE/api/trash/$C/restore" >/dev/null
[ "$(call "$A" -o /dev/null -w '%{http_code}' "$BASE/api/files/$C")" = 200 ] && ok "ごみ箱から戻せる" || ng "復元"
[ "$(call "$A" "$BASE/api/search?q=hello" | j "len(d)")" -ge 2 ] && ok "名前で検索" || ng "検索"
[ "$(call "$A" "$BASE/api/recent" | j "len(d)")" -ge 1 ] && ok "最近のファイル" || ng "最近"

echo "WebDAV"
KEY=$(call "$A" -H 'Content-Type: application/json' -d '{"name":"smoke","scopes":["read","write"]}' "$BASE/api/me/api-keys" | j "d['key']")
code=$(curl -sS -o "$WORK/pf.xml" -w '%{http_code}' -u "x:$KEY" -X PROPFIND -H 'Depth: 1' "$BASE/dav/")
[ "$code" = 207 ] && ok "PROPFIND" || ng "PROPFIND $code"
curl -sS -u "x:$KEY" -X MKCOL "$BASE/dav/dav-test-$$" -o /dev/null -w '%{http_code}' | grep -q 201 && ok "MKCOL"
curl -sS -u "x:$KEY" -T "$WORK/hello.txt" "$BASE/dav/dav-test-$$/%E3%81%82.txt" -o /dev/null -w '%{http_code}' | grep -q 201 && ok "PUT（日本語の名前）"
[ "$(curl -sS -u "x:$KEY" "$BASE/dav/dav-test-$$/%E3%81%82.txt")" = "こんにちは 2" ] && ok "GET" || ng "GET"
curl -sS -u "x:$KEY" -X MOVE -H "Destination: $BASE/dav/dav-test-$$/moved.txt" "$BASE/dav/dav-test-$$/%E3%81%82.txt" -o /dev/null -w '%{http_code}' | grep -q 201 && ok "MOVE"
curl -sS -u "x:$KEY" -X DELETE "$BASE/dav/dav-test-$$" -o /dev/null -w '%{http_code}' | grep -q 204 && ok "DELETE（ごみ箱へ）"
code=$(curl -sS -o /dev/null -w '%{http_code}' -u "x:wrong" -X PROPFIND "$BASE/dav/")
[ "$code" = 401 ] && ok "間違ったパスワードは 401" || ng "認証 $code"

echo "OAuth（skea 連携）"
CID=${OAUTH_CLIENT_ID:-skea}; CSEC=${OAUTH_CLIENT_SECRET:-skea-dev-secret-0123456789}
CB=${OAUTH_REDIRECT:-http://localhost:8090/api/integrations/skawau/callback}
VER=$(python3 -c "import secrets; print(secrets.token_urlsafe(48))")
CH=$(python3 -c "import hashlib,base64,sys; print(base64.urlsafe_b64encode(hashlib.sha256(sys.argv[1].encode()).digest()).rstrip(b'=').decode())" "$VER")
Q="response_type=code&client_id=$CID&redirect_uri=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1],safe=''))" "$CB")&scope=read&state=st1&code_challenge=$CH&code_challenge_method=S256"
code=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/oauth/authorize?response_type=code&client_id=nope&redirect_uri=x")
[ "$code" = 400 ] && ok "不正な client_id はリダイレクトしない" || ng "不正な client $code"
PAGE=$(call "$A" "$BASE/oauth/authorize?$Q")
CSRF=$(echo "$PAGE" | python3 -c "import sys,re; print(re.search(r'name=\"csrf\" value=\"([^\"]+)', sys.stdin.read()).group(1))")
echo "$PAGE" | grep -q "ファイル名を読み取ります" && ok "許可の画面"
LOC=$(call "$A" -o /dev/null -w '%{redirect_url}' --data-urlencode "csrf=$CSRF" --data-urlencode "decision=allow" --data-urlencode "client_id=$CID" \
  --data-urlencode "redirect_uri=$CB" --data-urlencode "state=st1" --data-urlencode "code_challenge=$CH" --data-urlencode "code_challenge_method=S256" \
  --data-urlencode "response_type=code" --data-urlencode "scope=read" "$BASE/oauth/authorize")
OCODE=$(python3 -c "import urllib.parse,sys; q=urllib.parse.parse_qs(urllib.parse.urlparse(sys.argv[1]).query); assert q['state']==['st1']; print(q['code'][0])" "$LOC")
ok "許可すると code と state を付けて戻る"
TOK=$(curl -sS -u "$CID:$CSEC" -d grant_type=authorization_code --data-urlencode "code=$OCODE" --data-urlencode "redirect_uri=$CB" --data-urlencode "code_verifier=$VER" "$BASE/oauth/token" | j "d['access_token']")
ok "トークンの発行"
code=$(curl -sS -o /dev/null -w '%{http_code}' -u "$CID:$CSEC" -d grant_type=authorization_code --data-urlencode "code=$OCODE" --data-urlencode "redirect_uri=$CB" --data-urlencode "code_verifier=$VER" "$BASE/oauth/token")
[ "$code" = 400 ] && ok "認可コードは一度だけ" || ng "コードの再利用 $code"
N=$(curl -sS -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d "{\"ids\":[\"$F\",\"$DIR\",\"$(python3 -c 'import uuid;print(uuid.uuid4())')\"]}" "$BASE/api/files/lookup" | j "sorted((f['name'], f['folder']) for f in d['files'])")
echo "$N" | grep -q "hello.txt" && echo "$N" | grep -q "True" && ok "lookup（見られるものだけ）: $N" || ng "lookup $N"
code=$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOK" -X PUT --data x "$BASE/api/files/$DIR/upload?name=y.txt")
[ "$code" = 403 ] && ok "トークンは読み取り専用" || ng "読み取り専用 $code"
code=$(curl -sS -o /dev/null -w '%{http_code}' -u "x:$TOK" -X PROPFIND "$BASE/dav/")
[ "$code" = 401 ] && ok "連携のトークンは WebDAV に使えない" || ng "WebDAV $code"
curl -sS -u "$CID:$CSEC" --data-urlencode "token=$TOK" "$BASE/oauth/revoke" >/dev/null
code=$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"ids":[]}' "$BASE/api/files/lookup")
[ "$code" = 401 ] && ok "失効したトークンは 401" || ng "失効 $code"

echo "MCP"
rpc() { curl -sS -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$2" "$BASE/mcp"; }
tool() { rpc "$1" "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}"; }
MK=$(call "$A" -H 'Content-Type: application/json' -d '{"name":"mcp","scopes":["read","write"]}' "$BASE/api/me/api-keys" | j "d['key']")
RK=$(call "$A" -H 'Content-Type: application/json' -d '{"name":"mcp-ro","scopes":["read"]}' "$BASE/api/me/api-keys" | j "d['key']")
[ "$(rpc "$MK" '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' | j "d['result']['serverInfo']['name']")" = skawau ] && ok "initialize" || ng "initialize"
NT=$(rpc "$MK" '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | j "len(d['result']['tools'])")
[ "$NT" -ge 15 ] && ok "tools/list（$NT 個）" || ng "tools/list $NT"
DIRP=$(call "$A" "$BASE/api/files/$DIR" | j "d['path'].rstrip('/') + '/' + d['node']['name']")
tool "$MK" write_file "{\"folder\":\"$DIRP\",\"name\":\"ai-memo.md\",\"content\":\"# AI のメモ\\nこんにちは\"}" | j "d['result']['content'][0]['text']" | grep -q "保存しました" && ok "write_file（パスで指定）"
tool "$MK" read_file "{\"file\":\"$DIRP/ai-memo.md\"}" | j "d['result']['content'][0]['text']" | grep -q "こんにちは" && ok "read_file（テキスト）" || ng "read_file"
tool "$MK" read_file "{\"file\":\"$DIRP/新規.pptx\"}" | j "d['result']['content'][0]['text']" | grep -q "スライド 1" && ok "read_file（pptx の文字を取り出す）" || ng "read_file pptx"
tool "$MK" list_folder "{\"folder\":\"$DIR\"}" | j "d['result']['content'][0]['text']" | grep -q "ai-memo.md" && ok "list_folder（ID で指定）" || ng "list_folder"
tool "$MK" search_files '{"query":"ai-memo"}' | j "d['result']['content'][0]['text']" | grep -q "ai-memo.md" && ok "search_files" || ng "search_files"
[ "$(tool "$RK" write_file "{\"folder\":\"$DIR\",\"name\":\"x.md\",\"content\":\"x\"}" | j "d['result']['isError']")" = True ] && ok "読み取りのみのキーでは書けない" || ng "読み取りのみ"
tool "$MK" call_api '{"method":"GET","path":"/api/me"}' | j "d['result']['content'][0]['text']" | grep -q "スモーク太郎" && ok "call_api" || ng "call_api"
call "$A" "$BASE/api/files/$DIR/activity" | j "[e['via'] for e in d if e['name']=='ai-memo.md']" | grep -q mcp && ok "変更履歴の経路が mcp" || ng "経路"
code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/mcp")
[ "$code" = 401 ] && ok "キーなしは 401" || ng "MCP 認証 $code"
[ "$(curl -sS "$BASE/api/endpoints" | j "len(d)")" -ge 30 ] && ok "API の一覧（/api/endpoints）" || ng "endpoints"

echo "アイコン"
printf '\x89PNG\r\n\x1a\n' > "$WORK/a.png"; printf 'IHDR' >> "$WORK/a.png"
AV=$(call "$A" -X PUT -H 'Content-Type: image/png' --data-binary @"$WORK/a.png" "$BASE/api/me/avatar" | j "d['avatarUrl']")
[ "$(call "$B" "$BASE$AV" | head -c 4 | tail -c 3)" = PNG ] && ok "登録したアイコンをほかの人が見られる" || ng "アイコン $AV"
code=$(call "$A" -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: image/svg+xml' --data-binary '<svg/>' "$BASE/api/me/avatar")
[ "$code" = 400 ] && ok "SVG は受け付けない" || ng "SVG $code"
call "$A" -X DELETE "$BASE/api/me/avatar" >/dev/null
[ "$(call "$A" "$BASE/api/me" | j "d['avatarUrl']")" = None ] && ok "アイコンを外せる" || ng "外す"

echo "後片付け"
call "$A" -H 'Content-Type: application/json' -d "{\"ids\":[\"$DIR\"]}" "$BASE/api/files/delete" >/dev/null
call "$A" -X DELETE "$BASE/api/trash" >/dev/null
ok "すべて完了"
