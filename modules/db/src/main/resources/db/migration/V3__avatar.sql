-- 画面から登録したアイコン（S3 のキー）。登録している間は IdP のアイコンで上書きしない
alter table app_user add column avatar_key text;
