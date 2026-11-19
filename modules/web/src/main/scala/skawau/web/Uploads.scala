package skawau.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom
import skawau.shared.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success}

/** アップロードの順番待ちと進み具合（画面の左下に出す）. */
final case class UploadItem(
  id: Int,
  name: String,
  loaded: Double,
  total: Double,
  state: String,
  error: String) {
  def percent: Int = if (total <= 0) 0 else math.min(100, (loaded / total * 100).toInt)
}

object Uploads {

  val items: RV[List[UploadItem]] = RV(Nil)
  private var seq = 0
  private val Parallel = 3

  private def update(
    id: Int
  )(
    f: UploadItem => UploadItem
  ): Unit = items := items.v.map(i => if (i.id == id) f(i) else i)

  def clearDone(): Unit = items := items.v.filter(i => i.state == "waiting" || i.state == "uploading")

  def active = items.mapC(_.exists(i => i.state == "waiting" || i.state == "uploading"))

  /**
   * ファイルを送る. files は (ファイル, フォルダの中の相対パス). url は (名前, 相対パス) から送り先の URL を作る.
   * 同時に3つまで送り、終わるたびに表示を取り直す.
   */
  def start(
    files: List[(dom.File, String)],
    url: (String, String) => String
  ): Future[Unit] = {
    val jobs = files.map {
      (
        f,
        p
      ) =>
        seq += 1
        (seq, f, p)
    }
    items := items.v.filter(_.state != "done") ++ jobs.map(
      (
        id,
        f,
        _
      ) => UploadItem(id, f.name, 0, f.size.toDouble, "waiting", "")
    )
    val queue = scala.collection.mutable.Queue.from(jobs)
    def worker(): Future[Unit] =
      if (queue.isEmpty) Future.successful(())
      else {
        val (id, f, p) = queue.dequeue()
        update(id)(_.copy(state = "uploading"))
        val (fut, _) = Api.upload[NodeDto](
          url(f.name, p),
          f,
          (
            l,
            t
          ) => update(id)(_.copy(loaded = l, total = t))
        )
        fut.transformWith { r =>
          r match {
            case Success(_) => update(id)(i => i.copy(state = "done", loaded = i.total))
            case Failure(e) => update(id)(_.copy(state = "error", error = e.getMessage))
          }
          Store.changed()
          worker()
        }
      }
    Future.sequence(List.fill(Parallel)(worker())).map { _ =>
      val errs = items.v.count(_.state == "error")
      if (errs == 0) dom.window.setTimeout(() => if (!active.v) clearDone(), 4000)
    }
  }

  /**
   * ドロップされたもの（フォルダを含む）を (ファイル, 相対パス) にする. 相対パスはフォルダの中のパス（"a/b"。直下は ""）. 空のフォルダは除く.
   * フォルダは webkitGetAsEntry でたどる（Chrome・Edge・Firefox・Safari で使える）. 項目はドロップのイベントの中で読む必要があるので、最初に同期で取り出す.
   */
  def collect(
    dt: dom.DataTransfer
  ): Future[List[(dom.File, String)]] = {
    val items = dt.asInstanceOf[js.Dynamic].items
    val files = if (js.isUndefined(items) || items == null) Nil
    else (0 until items.length.asInstanceOf[Int]).map(i => items.selectDynamic(i.toString)).filter(_.kind.toString ==
      "file").toList
    val entries = files.map(i => if (js.isUndefined(i.webkitGetAsEntry)) null else i.webkitGetAsEntry())
    if (entries.isEmpty || entries.exists(e => e == null || js.isUndefined(e)))
      Future.successful(Option(dt.files).toList.flatMap(fs => (0 until fs.length).map(i => (fs(i), ""))))
    else sequentially(entries)(walk(_, ""))
  }

  private def sequentially[A, B](
    xs: List[A]
  )(
    f: A => Future[List[B]]
  ): Future[List[B]] =
    xs.foldLeft(Future.successful(List.empty[B]))(
      (
        acc,
        x
      ) => acc.flatMap(a => f(x).map(a ++ _))
    )

  /** FileSystemEntry の、成功と失敗のコールバックを取る関数を Future にする. */
  private def callback[A](
    f: (js.Function1[A, Unit], js.Function1[js.Any, Unit]) => Any
  ): Future[A] = {
    val p = scala.concurrent.Promise[A]()
    f((a: A) => { p.trySuccess(a); () }, (e: js.Any) => { p.tryFailure(js.JavaScriptException(e)); () })
    p.future
  }

  private def walk(
    entry: js.Dynamic,
    base: String
  ): Future[List[(dom.File, String)]] =
    if (entry.isFile.asInstanceOf[Boolean]) callback[dom.File](
      (
        ok,
        ng
      ) => entry.file(ok, ng)
    ).map(f => List((f, base)))
    else if (entry.isDirectory.asInstanceOf[Boolean]) {
      val dir = if (base.isEmpty) entry.name.toString else s"$base/${entry.name}"
      val reader = entry.createReader()
      // readEntries は一度に最大 100 件しか返さないため、空になるまで繰り返す
      def readAll(
        acc: List[js.Dynamic]
      ): Future[List[js.Dynamic]] =
        callback[js.Array[js.Dynamic]](
          (
            ok,
            ng
          ) => reader.readEntries(ok, ng)
        )
          .flatMap(b => if (b.length == 0) Future.successful(acc) else readAll(acc ++ b.toList))
      readAll(Nil).flatMap(sequentially(_)(walk(_, dir)))
    } else Future.successful(Nil)

  /** input type=file（webkitdirectory ならフォルダ）の change で選ばれたもの（同じファイルを選び直せるよう、入力欄は空にする）. */
  def picked(
    e: dom.Event
  ): List[(dom.File, String)] = {
    val input = e.target.asInstanceOf[dom.HTMLInputElement]
    val files = Option(input.files).toList.flatMap(fs => (0 until fs.length).map(fs(_))).map { f =>
      val rel = Option(
        f.asInstanceOf[js.Dynamic].webkitRelativePath.asInstanceOf[js.UndefOr[String]]
      ).flatMap(_.toOption).getOrElse("")
      (f, rel.split('/').dropRight(1).mkString("/"))
    }
    input.value = ""
    files
  }

  /** ドロップされたもの（ブラウザの外からのファイルのときだけ. 既定の動きは止める）. */
  def dropped(
    e: dom.Event
  ): Option[Future[List[(dom.File, String)]]] =
    Option.when(Dom.hasFiles(e)) { e.preventDefault(); collect(e.asInstanceOf[dom.DragEvent].dataTransfer) }

}
