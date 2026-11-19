package skawau.api

import cats.effect.*
import cats.syntax.all.*
import skawau.core.ErrorCode
import skawau.db.ActivityStore
import sbirds.observe.{Log, Metrics, Telemetry}
import sbirds.push.{PushMessage, PushService}

import java.util.UUID

/**
 * 利用者への通知（画面の 🔔）。通知を作り、その人がこの端末で受け取る設定（PWA のプッシュ通知）にしていれば、
 * ブラウザを閉じていても知らせる。プッシュは裏で送り、失敗しても呼び出し元の処理は止めない。
 */
final class Notifier(
  activity: ActivityStore,
  push: Option[PushService]) {

  def notify(
    users: List[UUID],
    kind: String,
    title: String,
    body: String,
    link: Option[String],
    actor: Option[UUID]
  ): IO[Unit] =
    Telemetry.span("notify.send", "kind" -> kind)(
      activity.notify(users, kind, title, body, link, actor) *>
        Metrics.add("skawau.notifications.sent", users.size.toLong, "kind" -> kind) *>
        push.traverse_(p =>
          users.traverse_(u =>
            p.send(
              u,
              PushMessage(title, body.take(500), link.getOrElse("/"), Some(s"$kind:${link.getOrElse("")}"))
            ).start.void
          )
        )
    ).handleErrorWith(e => Log.report(ErrorCode.NotifyFailed, e.toString, e))
}
