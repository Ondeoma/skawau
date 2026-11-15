package skawau.storage

import cats.effect.*
import fs2.Stream
import fs2.io.file.Path
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}
import software.amazon.awssdk.services.s3.model.*

import java.net.URI

final case class S3Config(
    endpoint: String,
    region: String,
    bucket: String,
    accessKey: String,
    secretKey: String,
    pathStyle: Boolean = true // 自前の S3 互換ストレージはパス形式が扱いやすい
)

/** S3互換ストレージの実装。AWS SDKの同期クライアントを blocking プールで使う。 */
final class S3BlobStore private (client: S3Client, bucket: String) extends BlobStore:

  def put(key: String, file: Path, contentType: String): IO[Unit] =
    IO.blocking {
      client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
        RequestBody.fromFile(file.toNioPath)
      )
    }.void

  def get(key: String, range: Option[(Long, Option[Long])] = None): IO[Option[(BlobMeta, Stream[IO, Byte])]] =
    val req = GetObjectRequest.builder().bucket(bucket).key(key)
    range.foreach((from, to) => req.range(s"bytes=$from-${to.fold("")(_.toString)}"))
    IO.blocking(client.getObject(req.build()))
      .map { in =>
        val r     = in.response()
        val ct    = Option(r.contentType()).getOrElse("application/octet-stream")
        // Content-Range: bytes 0-99/1234
        val cr    = Option(r.contentRange()).flatMap(v => "bytes (\\d+)-(\\d+)/(\\d+)".r.findFirstMatchIn(v)).map(m => (m.group(1).toLong, m.group(2).toLong, m.group(3).toLong))
        val meta  = cr match
          case Some((a, b, t)) if range.isDefined => BlobMeta(ct, r.contentLength(), t, Some((a, b)))
          case _                                  => BlobMeta(ct, r.contentLength(), r.contentLength(), None)
        Some(meta -> fs2.io.readInputStream(IO.pure[java.io.InputStream](in), 64 * 1024, closeAfterUse = true))
      }
      .recover {
        case _: NoSuchKeyException => None
        case e: S3Exception if e.statusCode() == 416 => None
      }

  def delete(key: String): IO[Unit] =
    IO.blocking(client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())).void

  def copy(from: String, to: String): IO[Boolean] =
    IO.blocking {
      client.copyObject(CopyObjectRequest.builder().sourceBucket(bucket).sourceKey(from).destinationBucket(bucket).destinationKey(to).build())
    }.as(true).recover { case _: NoSuchKeyException => false }

  /** バケットがなければ作成する（開発環境向け。本番では事前作成を推奨）。 */
  def ensureBucket: IO[Unit] =
    IO.blocking {
      try client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
      catch case _: NoSuchBucketException => client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
    }.void

object S3BlobStore:
  def resource(cfg: S3Config): Resource[IO, S3BlobStore] =
    Resource
      .fromAutoCloseable(IO.blocking {
        S3Client
          .builder()
          .endpointOverride(URI.create(cfg.endpoint))
          .region(Region.of(cfg.region))
          .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.accessKey, cfg.secretKey)))
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(cfg.pathStyle).build())
          .httpClientBuilder(ApacheHttpClient.builder())
          .build()
      })
      .map(new S3BlobStore(_, cfg.bucket))
