package skawau.app

import cats.effect.*
import cats.syntax.all.*
import skawau.api.*
import sbirds.auth.OidcConfig
import skawau.db.*
import sbirds.storage.S3Config

/** 設定は環境変数から読む。 */
object Config {
  private def opt(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)
  private def int(name: String): Option[Int] = opt(name).flatMap(_.toIntOption)
  private def long(name: String): Option[Long] = opt(name).flatMap(_.toLongOption)
  private def enabled(name: String): Boolean = opt(name).exists(_.equalsIgnoreCase("true"))
  private def required(name: String): Either[String, String] = opt(name).toRight(s"環境変数 $name を設定してください")

  /** ONLYOFFICE_URL を設定したときだけ有効。JWT の秘密は Document Server の JWT_SECRET と同じ値。 */
  private def office(pub: String): Either[String, Option[OfficeConfig]] =
    opt("ONLYOFFICE_URL") match {
      case None => Right(None)
      case Some(url) =>
        opt("ONLYOFFICE_JWT_SECRET") match {
          case None => Left("ONLYOFFICE_URL を設定したときは ONLYOFFICE_JWT_SECRET（Document Server の JWT_SECRET と同じ値）も設定してください")
          case Some(sec) if sec.length < 32 => Left("ONLYOFFICE_JWT_SECRET は32文字以上にしてください（推測されると保存の知らせを偽造されます）")
          case Some(sec) =>
            Right(Some(OfficeConfig(
              url.stripSuffix("/"),
              opt("ONLYOFFICE_INTERNAL_URL").getOrElse(url).stripSuffix("/"),
              opt("ONLYOFFICE_APP_URL").getOrElse(pub).stripSuffix("/"),
              sec
            )))
        }
    }

  /** OAUTH_CLIENTS: [{"id":"skea","secret":"…","redirectUri":"https://…/callback","name":"skea","scopes":["read"]}]（scopes は省略すると read だけ） */
  private def oauthClients: Either[String, List[OAuthClient]] =
    opt("OAUTH_CLIENTS").fold(Right(Nil)) { s =>
      io.circe.parser.parse(s).flatMap(_.as[List[io.circe.Json]]).left.map(_ =>
        "OAUTH_CLIENTS は JSON の配列で指定してください"
      ).flatMap(_.traverse { j =>
        val c = j.hcursor
        (c.get[String]("id"), c.get[String]("secret"), c.get[String]("redirectUri")).tupled.toOption
          .filter((id, sec, uri) =>
            id.nonEmpty && sec.length >= 16 && (uri.startsWith("https://") || uri.startsWith("http://localhost"))
          )
          .map((id, sec, uri) =>
            OAuthClient(
              id,
              sec,
              uri,
              c.get[String]("name").getOrElse(id),
              c.get[List[String]]("scopes").getOrElse(List("read"))
            )
          )
          .filter(c => c.scopes.nonEmpty && c.scopes.forall(Set("read", "write")))
          .toRight("OAUTH_CLIENTS の各要素には id・secret（16文字以上）・redirectUri（https://）を指定してください（scopes は read・write）")
      })
    }

  def load: IO[AppConfig] = IO.fromEither(
    (for {
      dbUrl <- required("DATABASE_URL")
      dbUser <- required("DATABASE_USER")
      dbPass <- required("DATABASE_PASSWORD")
      pub <- required("PUBLIC_URL").map(_.stripSuffix("/"))
      devLogin = enabled("DEV_LOGIN")
      oidc <- (opt("OIDC_ISSUER"), opt("OIDC_CLIENT_ID"), opt("OIDC_CLIENT_SECRET")) match {
        case (Some(i), Some(c), Some(s)) =>
          Right(Some(OidcConfig(i, c, s, s"$pub/auth/callback", opt("OIDC_GROUPS_CLAIM").getOrElse("groups"))))
        case (None, None, None) if devLogin => Right(None)
        case _ => Left("OIDC_ISSUER・OIDC_CLIENT_ID・OIDC_CLIENT_SECRET を設定してください（開発中は DEV_LOGIN=true で省略できます）")
      }
      secret <- opt("APP_SECRET") match {
        case Some(s) if s.length >= 16 => Right(s)
        case Some(_) => Left("APP_SECRET は16文字以上にしてください")
        // 固定の値は、開発用のログインで OIDC も https もないときだけ（本番の設定の誤りで使われないように）
        case None if devLogin && oidc.isEmpty && !pub.startsWith("https://") => Right("skawau-development-secret")
        case None => Left("APP_SECRET（16文字以上の秘密の値）を設定してください")
      }
      off <- office(pub)
      clients <- oauthClients
    } yield AppConfig(
      port = int("HTTP_PORT").getOrElse(8080),
      db = DbConfig(dbUrl, dbUser, dbPass, int("DATABASE_POOL_SIZE").getOrElse(10)),
      oidc = oidc,
      api = ApiConfig(
        adminGroups = opt("ADMIN_GROUPS").map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty),
        cookieSecure = pub.startsWith("https://"),
        publicUrl = pub,
        appSecret = secret,
        devLogin = devLogin,
        maxUploadBytes = long("MAX_UPLOAD_MB").getOrElse(10240L) * 1024 * 1024,
        defaultQuota = long("DEFAULT_QUOTA_MB").filter(_ > 0).map(_ * 1024 * 1024),
        versionsKept = int("VERSIONS_KEPT").getOrElse(30),
        trashDays = int("TRASH_DAYS").getOrElse(30),
        office = off,
        oauthClients = clients
      ),
      staticDir = opt("STATIC_DIR"),
      s3 = opt("S3_ENDPOINT").map(ep =>
        S3Config(
          ep,
          opt("S3_REGION").getOrElse("us-east-1"),
          opt("S3_BUCKET").getOrElse("skawau"),
          opt("S3_ACCESS_KEY").getOrElse(""),
          opt("S3_SECRET_KEY").getOrElse(""),
          !opt("S3_PATH_STYLE").contains("false")
        )
      ),
      nodeId = opt("NODE_ID").getOrElse(java.net.InetAddress.getLocalHost.getHostName + "-" +
        java.util.UUID.randomUUID().toString.take(8))
    )).left.map(new IllegalArgumentException(_))
  )
}
