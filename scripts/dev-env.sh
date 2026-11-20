# 開発用の環境変数（source scripts/dev-env.sh）。docker-compose.dev.yml の DB・ScalaS3・ONLYOFFICE を使う
export DATABASE_URL=jdbc:postgresql://localhost:54340/skawau DATABASE_USER=skawau DATABASE_PASSWORD=skawau
export PUBLIC_URL=http://localhost:8092 HTTP_PORT=8092
export DEV_LOGIN=true
# 回数の制限（IP ごとの1分あたり）。手元ではすべての要求が 127.0.0.1 から来るので、開発中は緩める
export RATE_LIMIT_AUTH=${RATE_LIMIT_AUTH:-300} RATE_LIMIT_PUBLIC=${RATE_LIMIT_PUBLIC:-1000}
export S3_ENDPOINT=http://localhost:3911 S3_BUCKET=skawau S3_ACCESS_KEY=skawau S3_SECRET_KEY=skawauskawauskawau
# ONLYOFFICE: ブラウザとこのサーバーからは localhost:8093、Document Server からこのサーバーへは host.docker.internal:8092
export ONLYOFFICE_URL=http://localhost:8093 ONLYOFFICE_APP_URL=http://host.docker.internal:8092
export ONLYOFFICE_JWT_SECRET=skawau-dev-onlyoffice-secret-0123456789
# skea・skiwi 連携（skawau のファイル名を出す。開発用。本番では secret をランダムな値にする）
export OAUTH_CLIENTS='[{"id":"skea","secret":"skea-dev-secret-0123456789","redirectUri":"http://localhost:8090/api/integrations/skawau/callback","name":"skea"},{"id":"skiwi","secret":"skiwi-dev-secret-0123456789","redirectUri":"http://localhost:5173/api/integrations/skawau/callback","name":"skiwi"},{"id":"skaka","secret":"skaka-dev-secret-0123456789","redirectUri":"http://localhost:5177/api/integrations/skawau/callback","name":"skaka","scopes":["read","write"]}]'
