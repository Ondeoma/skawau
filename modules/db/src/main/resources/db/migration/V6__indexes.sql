-- 定期的な処理が表を全部読まないようにする索引

-- 画面への通知の配信（EventFeed）が、各ノードで1秒ごとに「at > 前回」で新しい通知を探す
create index notification_at_idx on notification (at);

-- 古い変更履歴の削除（1時間ごとに at で探す）。at は追記の順に並ぶので、小さい BRIN で足りる
create index file_event_at_idx on file_event using brin (at);
