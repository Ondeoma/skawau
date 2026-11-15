-- 外部のサービス（skea など）への認可（OAuth 2.0 の認可コード + PKCE）

-- 認可コード（60秒で失効。一度だけ使える。ハッシュだけを保存する）
create table oauth_code (
  code_hash      bytea primary key,
  client_id      text not null,
  redirect_uri   text not null,
  user_id        uuid not null references app_user(id) on delete cascade,
  code_challenge text not null,
  scope          text not null,
  expires_at     timestamptz not null,
  used_at        timestamptz
);
create index oauth_code_expires_idx on oauth_code (expires_at);

-- 認可で発行した API キーのクライアント（許可し直したときに前のキーを失効させる）
alter table api_key add column oauth_client text;
create index api_key_oauth_idx on api_key (user_id, oauth_client) where oauth_client is not null;
