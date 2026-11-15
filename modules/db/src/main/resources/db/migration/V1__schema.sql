-- skawau のスキーマ（初版）
-- 適用済みのマイグレーションは書き換えないこと。変更は新しい番号の V<n>__*.sql を追加する。

create extension if not exists pgroonga;

-- ------------------------------------------------------------------ ユーザー・認証

create table app_user (
  id            uuid primary key,
  sub           text not null unique,            -- OIDC の sub
  name          text not null,
  email         text,
  avatar_url    text,
  is_admin      boolean not null default false,  -- 画面で付けた管理者権限（ADMIN_GROUPS の所属者は別途管理者）
  disabled      boolean not null default false,
  quota_bytes   bigint,                          -- 容量の上限（null なら DEFAULT_QUOTA_MB）
  created_at    timestamptz not null default now(),
  last_login_at timestamptz
);
create index app_user_name_idx on app_user using pgroonga (name);

create table user_group (
  id   uuid primary key,
  name text not null unique
);
create index user_group_name_idx on user_group using pgroonga (name);

create table group_member (
  group_id uuid not null references user_group(id) on delete cascade,
  user_id  uuid not null references app_user(id) on delete cascade,
  primary key (group_id, user_id)
);
create index group_member_user_idx on group_member (user_id);

create table session (
  token_hash bytea primary key,
  user_id    uuid not null references app_user(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);
create index session_expires_idx on session (expires_at);

-- OIDC のログイン途中の状態（複数ノードのどこに戻ってきてもよいよう DB に置く）
create table login_state (
  state      text primary key,
  verifier   text not null,
  nonce      text not null,
  return_to  text not null default '/',
  created_at timestamptz not null default now()
);

-- APIキー（WebDAV のアプリパスワードを兼ねる）
create table api_key (
  id           uuid primary key,
  user_id      uuid not null references app_user(id) on delete cascade,
  name         text not null,
  prefix       text not null,                    -- 画面に出す先頭部分（skawau_xxxx）
  key_hash     bytea not null unique,
  scopes       text[] not null,                  -- read / write / admin
  created_at   timestamptz not null default now(),
  expires_at   timestamptz,
  last_used_at timestamptz,
  revoked_at   timestamptz
);
create index api_key_user_idx on api_key (user_id);

-- ------------------------------------------------------------------ ファイル・フォルダ

-- ファイルとフォルダ。各ユーザーにルートのフォルダ（parent_id が null、名前は空）が1つある。
-- ancestors はルートから親までの ID（共有の判定・ごみ箱の中の判定・移動時の付け替えに使う）。
-- ごみ箱に入れたものは deleted_at を付けた起点の行だけで表す（子孫の行はそのまま。起点が消えていれば見えない）。
create table node (
  id              uuid primary key,
  owner_id        uuid not null references app_user(id) on delete cascade,
  parent_id       uuid references node(id) on delete cascade,
  ancestors       uuid[] not null default '{}',
  name            text not null,
  is_dir          boolean not null,
  size            bigint not null default 0,      -- ファイルの大きさ（フォルダは 0）
  content_type    text,
  blob_key        text,                           -- S3 のキー（ファイルのみ）
  sha256          text,
  etag            text not null,                  -- 中身・名前が変わるたびに変える（WebDAV・OnlyOffice の文書キー）
  version         integer not null default 1,     -- 中身の版（上書きのたびに増える）
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  modified_by     uuid references app_user(id) on delete set null,
  deleted_at      timestamptz,                    -- ごみ箱に入れた日時（起点の行だけ）
  deleted_by      uuid references app_user(id) on delete set null,
  trash_path      text                            -- ごみ箱に入れたときの元の場所（表示用）
);
create unique index node_root_uq on node (owner_id) where parent_id is null;
create unique index node_name_uq on node (parent_id, lower(name)) where deleted_at is null and parent_id is not null;
create index node_parent_idx on node (parent_id);
create index node_ancestors_idx on node using gin (ancestors);
create index node_owner_updated_idx on node (owner_id, updated_at desc) where not is_dir;
create index node_trash_idx on node (owner_id, deleted_at) where deleted_at is not null;
create index node_name_search_idx on node using pgroonga (name)
  with (tokenizer = 'TokenNgram("unify_alphabet", false, "unify_digit", false, "unify_symbol", false)',
        normalizers = 'NormalizerNFKC150("unify_kana", true, "unify_kana_case", true, "unify_hyphen_and_prolonged_sound_mark", true, "unify_middle_dot", true)');

-- 以前の版（今の版は node に持つ）
create table file_version (
  id           uuid primary key,
  node_id      uuid not null references node(id) on delete cascade,
  version      integer not null,
  size         bigint not null,
  content_type text,
  blob_key     text not null,
  sha256       text,
  created_at   timestamptz not null,               -- その版が作られた日時
  created_by   uuid references app_user(id) on delete set null
);
create index file_version_node_idx on file_version (node_id, version desc);

-- 消す S3 のオブジェクト（完全に削除したファイル・古い版）。DB の削除と同じトランザクションで積み、ワーカーが消す
create table blob_trash (
  key        text primary key,
  queued_at  timestamptz not null default now(),
  attempts   integer not null default 0
);

create table favorite (
  user_id    uuid not null references app_user(id) on delete cascade,
  node_id    uuid not null references node(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, node_id)
);

-- ------------------------------------------------------------------ 共有

-- kind: user（ユーザーへ）/ group（グループへ）/ link（公開リンク）
-- permission: view / edit、link のフォルダは upload（アップロードだけ）も
create table share (
  id            uuid primary key,
  node_id       uuid not null references node(id) on delete cascade,
  owner_id      uuid not null references app_user(id) on delete cascade,
  kind          text not null check (kind in ('user', 'group', 'link')),
  user_id       uuid references app_user(id) on delete cascade,
  group_id      uuid references user_group(id) on delete cascade,
  permission    text not null check (permission in ('view', 'edit', 'upload')),
  token         text unique,
  password_hash text,
  expires_at    date,
  label         text,
  created_at    timestamptz not null default now(),
  check ((kind = 'user') = (user_id is not null)),
  check ((kind = 'group') = (group_id is not null)),
  check ((kind = 'link') = (token is not null))
);
create index share_node_idx on share (node_id);
create index share_user_idx on share (user_id) where user_id is not null;
create index share_group_idx on share (group_id) where group_id is not null;
create unique index share_user_uq on share (node_id, user_id) where kind = 'user';
create unique index share_group_uq on share (node_id, group_id) where kind = 'group';

-- ------------------------------------------------------------------ 変更履歴・通知

-- 変更履歴。owner_id はファイルの所有者、lineage は対象の祖先と自分（共有の相手に知らせる範囲を決める）
create table file_event (
  id        bigserial primary key,
  node_id   uuid not null,
  owner_id  uuid not null,
  lineage   uuid[] not null,
  actor_id  uuid references app_user(id) on delete set null,
  via       text not null,                       -- web / api / dav / link / office
  kind      text not null,
  name      text not null,
  path      text not null default '',
  detail    jsonb not null default '{}',
  at        timestamptz not null default now()
);
create index file_event_owner_idx on file_event (owner_id, id desc);
create index file_event_node_idx on file_event (node_id, id desc);
create index file_event_lineage_idx on file_event using gin (lineage);

create table notification (
  id        uuid primary key,
  user_id   uuid not null references app_user(id) on delete cascade,
  kind      text not null,
  title     text not null,
  body      text not null default '',
  link      text,
  actor_id  uuid references app_user(id) on delete set null,
  at        timestamptz not null default now(),
  read_at   timestamptz
);
create index notification_user_idx on notification (user_id, at desc);

-- ------------------------------------------------------------------ 運用

-- 複数ノードのうち1つだけで動かす定期処理のリース
create table job_lock (
  name       text primary key,
  holder     text not null,
  expires_at timestamptz not null
);

create table audit_log (
  id        bigserial primary key,
  actor_id  uuid references app_user(id) on delete set null,
  via       text not null,
  action    text not null,
  target    text,
  detail    jsonb not null default '{}',
  at        timestamptz not null default now()
);
create index audit_log_at_idx on audit_log (at desc);
