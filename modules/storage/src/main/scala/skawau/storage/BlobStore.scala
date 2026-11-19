package skawau.storage

import cats.effect.*
import fs2.Stream
import fs2.io.file.Path

/** 取り出した中身の情報。total は全体の大きさ、range は返す範囲（Range 指定のとき。両端を含む）。 */
final case class BlobMeta(contentType: String, size: Long, total: Long, range: Option[(Long, Long)])

/** バイナリ（ファイルの中身）の保存先。S3 互換ストレージ（開発は ScalaS3）。 */
trait BlobStore {
  def put(key: String, file: Path, contentType: String): IO[Unit]

  /** range は (開始, 終了)。終了が None なら最後まで。 */
  def get(key: String, range: Option[(Long, Option[Long])] = None): IO[Option[(BlobMeta, Stream[IO, Byte])]]
  def delete(key: String): IO[Unit]

  /** 中身を別のキーに複製する（コピー・版の復元用）。元がなければ false。 */
  def copy(from: String, to: String): IO[Boolean]
}
