package skawau.api

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import skawau.db.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/** 画面に知らせる出来事（ファイルの変更、ユーザーへの通知）。 */
enum FeedItem:
  case Changed(userId: UUID, eventId: Long)
  case Notified(userId: UUID)

/**
 * 変更の配信（SSE）。各ノードが1秒ごとに新しい変更履歴と通知を DB で確かめ、関係する人（所有者・共有の相手）の画面へ知らせる
 * （ノードどうしの連絡は不要。DB がどのノードの変更も持っているため）。
 */
final class EventFeed(activity: ActivityStore, topic: Topic[IO, FeedItem]):

  def loop: IO[Nothing] =
    (activity.latestEvent, IO.realTimeInstant).tupled.flatMap { (start, now) =>
      def step(last: Long, since: Instant): IO[(Long, Instant)] =
        for
          evs <- activity.audienceAfter(last)
          _   <- evs.groupMapReduce(_._2)(_._1)(math.max).toList.traverse_((u, id) => topic.publish1(FeedItem.Changed(u, id)))
          ns  <- activity.notifiedSince(since)
          _   <- ns.traverse_((u, _) => topic.publish1(FeedItem.Notified(u)))
        yield (evs.map(_._1).maxOption.fold(last)(math.max(last, _)), (since :: ns.map(_._2)).max)
      def go(last: Long, since: Instant): IO[Nothing] =
        IO.sleep(1.second) *> step(last, since).handleErrorWith(e => skawau.observe.Log.println(s"[skawau] event feed failed: $e").as((last, since)))
          .flatMap((l, s) => go(l, s))
      go(start, now)
    }

  def subscribe: Stream[IO, FeedItem] = topic.subscribe(256)

final class StreamRoutes(feed: EventFeed, val sessions: SessionAuth) extends RouteSupport:

  private def sse(kind: String, data: Json): ServerSentEvent = ServerSentEvent(data = Some(data.noSpaces), eventType = Some(kind))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    /** 自分に関係するファイルの変更（change）と通知（notify）。届いたら画面が表示中の一覧を取り直す。 */
    case req @ GET -> Root / "api" / "stream" =>
      authed(req) { s =>
        val events = feed.subscribe.collect {
          case FeedItem.Changed(u, id) if u == s.userId => sse("change", Json.obj("latest" -> id.asJson))
          case FeedItem.Notified(u) if u == s.userId    => sse("notify", Json.obj())
        }
        // 接続が切られないよう 20 秒ごとに空の行を送る
        val ping = Stream.awakeEvery[IO](20.seconds).as(ServerSentEvent(comment = Some("ping")))
        val body = Stream.emit(ServerSentEvent(retry = Some(5.seconds), comment = Some("hello"))) ++ events.merge(ping)
        // 接続中の数（skorimako のゲージ）
        Ok(body.through(s => Stream.bracket(skawau.observe.Metrics.upDown("skawau.sse.connections", 1))(_ => skawau.observe.Metrics.upDown("skawau.sse.connections", -1)) >> s))
          .map(_.putHeaders("Cache-Control" -> "no-cache", "X-Accel-Buffering" -> "no"))
      }
  }
