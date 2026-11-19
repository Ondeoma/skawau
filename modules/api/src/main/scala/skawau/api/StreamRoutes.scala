package skawau.api

import cats.effect.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*

import java.util.UUID
import scala.concurrent.duration.*

/** 画面に知らせる出来事（ファイルの変更、ユーザーへの通知）。 */
enum FeedItem {
  case Changed(
    userId: UUID,
    eventId: Long)
  case Notified(
    userId: UUID)
}

final class StreamRoutes(feed: EventFeed, val sessions: SessionAuth) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    /** 自分に関係するファイルの変更（change）と通知（notify）。届いたら画面が表示中の一覧を取り直す。 */
    case req @ GET -> Root / "api" / "stream" => getStream(req)
  }

  private def getStream(req: Request[IO]): IO[Response[IO]] =
    authed(req) { s =>
      val events = feed.subscribe.collect {
        case FeedItem.Changed(u, id) if u == s.userId => sse("change", Json.obj("latest" -> id.asJson))
        case FeedItem.Notified(u) if u == s.userId => sse("notify", Json.obj())
      }
      // 接続が切られないよう 20 秒ごとに空の行を送る
      val ping = Stream.awakeEvery[IO](20.seconds).as(ServerSentEvent(comment = Some("ping")))
      val body = Stream.emit(ServerSentEvent(retry = Some(5.seconds), comment = Some("hello"))) ++
        events.merge(ping)
      // 接続中の数（skorimako のゲージ）
      Ok(body.through(s =>
        Stream.bracket(sbirds.observe.Metrics.upDown("skawau.sse.connections", 1))(_ =>
          sbirds.observe.Metrics.upDown("skawau.sse.connections", -1)
        ) >> s
      ))
        .map(_.putHeaders("Cache-Control" -> "no-cache", "X-Accel-Buffering" -> "no"))
    }

  private def sse(kind: String, data: Json): ServerSentEvent =
    ServerSentEvent(data = Some(data.noSpaces), eventType = Some(kind))

}
