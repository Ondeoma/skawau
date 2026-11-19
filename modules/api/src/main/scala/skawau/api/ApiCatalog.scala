package skawau.api

/** REST API の一覧（MCP の list_api_endpoints と GET /api/endpoints）。 */
object ApiCatalog {
  final case class Endpoint(
    method: String,
    path: String,
    description: String)
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
    Endpoint(
      "PUT",
      "/api/files/{folderId}/upload?name=&path=&conflict=rename|overwrite",
      "アップロード（本文がそのまま中身。path は途中のフォルダ a/b）"
    ),
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
    Endpoint(
      "POST",
      "/api/files/{id}/shares",
      "共有する {kind: user|group|link, userId?, groupId?, permission: view|edit|upload, password?, expiresAt?, label?}"
    ),
    Endpoint("PATCH", "/api/shares/{id}", "共有を変える {permission?, password?, expiresAt?, label?}（空文字で外す）"),
    Endpoint("DELETE", "/api/shares/{id}", "共有を解除する"),
    Endpoint("GET", "/api/notifications?unread=true", "通知"),
    Endpoint("POST", "/api/notifications/read", "通知を既読にする {ids}（空ならすべて）"),
    Endpoint("GET", "/api/admin/users", "ユーザーの一覧（管理者・admin スコープ）"),
    Endpoint("PATCH", "/api/admin/users/{id}", "ユーザーを変える {isAdmin?, disabled?, quotaMb?}（管理者）"),
    Endpoint("GET", "/api/admin/stats", "全体の使用量（管理者）"),
    Endpoint("GET", "/api/admin/audit", "監査ログ（管理者）")
  )
}
