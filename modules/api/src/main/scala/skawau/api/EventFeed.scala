package skawau.api

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic
import skawau.core.ErrorCode
import skawau.db.*

import java.time.Instant
import scala.concurrent.duration.*

/**
 * 変更の配信（SSE）。各ノードが1秒ごとに新しい変更履歴と通知を DB で確かめ、関係する人（所有者・共有の相手）の画面へ知らせる
 * （ノードどうしの連絡は不要。DB がどのノードの変更も持っているため）。
 */
final class EventFeed(
  activity: ActivityStore,
  topic: Topic[IO, FeedItem]) {

  def loop: IO[Nothing] =
    (activity.latestEvent, IO.realTimeInstant).tupled.flatMap {
      (
        start,
        now
      ) =>
        {
          def step(
            last: Long,
            since: Instant
          ): IO[(Long, Instant)] =
            for {
              evs <- activity.audienceAfter(last)
              _ <- evs.groupMapReduce(_._2)(_._1)(math.max).toList.traverse_(
                (
                  u,
                  id
                ) => topic.publish1(FeedItem.Changed(u, id))
              )
              ns <- activity.notifiedSince(since)
              _ <- ns.traverse_(
                (
                  u,
                  _
                ) => topic.publish1(FeedItem.Notified(u))
              )
            } yield (evs.map(_._1).maxOption.fold(last)(math.max(last, _)), (since :: ns.map(_._2)).max)
          def go(
            last: Long,
            since: Instant
          ): IO[Nothing] =
            IO.sleep(1.second) *> step(last, since).handleErrorWith(e =>
              sbirds.observe.Log.report(ErrorCode.EventFeedFailed, e.toString, e).as((last, since))
            )
              .flatMap(
                (
                  l,
                  s
                ) => go(l, s)
              )
          go(start, now)
        }
    }

  def subscribe: Stream[IO, FeedItem] = topic.subscribe(256)
}
