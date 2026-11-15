package skawau.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom
import skawau.shared.*

import java.util.UUID
import scala.scalajs.js

/** ファイル・フォルダへの共通の操作（一覧・検索・詳細などから使う）. */
object FileOps {

  def kind(n: NodeDto): String = FileKind.kind(n.name, n.isDir)
  def icon(n: NodeDto): String = FileKind.icon(kind(n))

  /** ONLYOFFICE で開けるか. */
  def officeOpenable(n: NodeDto): Boolean = !n.isDir && Store.office.isDefined && FileKind.officeType(n.name).isDefined

  /** 開く: フォルダは移動、Office は ONLYOFFICE、画像・動画・PDF・テキストはプレビュー、それ以外はダウンロード. */
  def open(n: NodeDto, siblings: List[NodeDto] = Nil): Unit =
    if (n.isDir) Router.go(s"/files/${n.id}")
    else if (officeOpenable(n)) Router.go(s"/office/${n.id}")
    else if (FileKind.previewable(kind(n))) {
      val list = (if (siblings.exists(_.id == n.id)) siblings else List(n)).filter(x => !x.isDir && FileKind.previewable(kind(x)))
      Store.preview := Some(PreviewReq(list, math.max(0, list.indexWhere(_.id == n.id))))
    } else download(n)

  def download(n: NodeDto): Unit = Api.download(Api.contentUrl(n.id, download = true))

  def downloadMany(ns: List[NodeDto]): Unit = ns match {
    case List(one) if !one.isDir => download(one)
    case _                       => Api.download(Api.zipUrl(ns.map(_.id)))
  }

  def rename(n: NodeDto): Unit =
    Dialog.prompt("新しい名前", n.name, "名前を変更") { name =>
      if (name.trim.nonEmpty && name.trim != n.name) Store.report(Api.rename(n.id, name.trim))(_ => Store.changed())
    }

  def trash(ns: List[NodeDto])(after: => Unit = ()): Unit = if (ns.nonEmpty) {
    val what = if (ns.size == 1) s"「${ns.head.name}」" else s"${ns.size}件"
    Dialog.confirm(s"$what をごみ箱に移動しますか？（ごみ箱から元に戻せます）", "ごみ箱へ") {
      Store.report(Api.trash(ns.map(_.id))) { r =>
        if (r.errors.nonEmpty) Dialog.alert("移動できなかったもの:\n" + r.errors.mkString("\n"))
        else Store.toast(s"$what をごみ箱に移動しました", Some("/trash"))
        Store.changed()
        after
      }
    }
  }

  def toggleFavorite(n: NodeDto): Unit = Store.report(Api.favorite(n.id, !n.favorite)) { _ =>
    Store.toast(if (n.favorite) "お気に入りから外しました" else "お気に入りに追加しました")
    Store.changed()
  }

  def newFolder(parent: UUID): Unit =
    Dialog.prompt("新しいフォルダの名前", "新しいフォルダ", "作成") { name =>
      if (name.trim.nonEmpty) Store.report(Api.mkdir(parent, name.trim))(_ => Store.changed())
    }

  /** 新しいファイル（Office は作ったあとエディタで開く）. */
  def newFile(parent: UUID, ext: String, label: String): Unit =
    Dialog.prompt(s"新しい${label}の名前", s"新しい$label.$ext", "作成") { name0 =>
      val name = name0.trim
      if (name.nonEmpty) {
        val fixed = if (FileKind.ext(name) == ext) name else s"$name.$ext"
        Store.report(Api.newFile(parent, fixed)) { n => Store.changed(); if (officeOpenable(n) || ext == "txt" || ext == "md") open(n, List(n)) }
      }
    }

  /**
   * 行のメニューの項目. openIt は「開く」の動き（同じフォルダの前後に送れるよう、一覧の側で決める）、
   * details を渡すと「詳細・版・履歴」を出す. 実際に許されるかはサーバーが確かめる（共有された起点の名前の変更など）.
   */
  def actions(n: NodeDto, openIt: () => Unit, details: Option[NodeDto => Unit] = None): List[MenuItem] = {
    val edit  = Perm.canEdit(n.perm)
    val owner = n.perm == Perm.Owner
    List(
      Some(MenuItem(if (n.isDir) "開く" else if (officeOpenable(n)) "ONLYOFFICE で開く" else "開く", openIt)),
      Some(MenuItem(if (n.isDir) "ZIP でダウンロード" else "ダウンロード", () => downloadMany(List(n)))),
      Option.when(owner)(MenuItem("共有…", () => Store.shareFor := Some(n), sep = true)),
      Some(MenuItem(if (n.favorite) "お気に入りから外す" else "お気に入りに追加", () => toggleFavorite(n), sep = !owner)),
      Some(MenuItem("リンクをコピー", () => copyLink(n))),
      details.map(f => MenuItem(if (n.isDir) "詳細・履歴" else "詳細・版・履歴", () => f(n))),
      Option.when(edit)(MenuItem("名前を変更", () => rename(n), sep = true)),
      Option.when(edit)(MenuItem("移動…", () => Store.moveReq := Some(MoveReqUi(List(n), copy = false, n.parentId)))),
      Some(MenuItem("コピー…", () => Store.moveReq := Some(MoveReqUi(List(n), copy = true, n.parentId)), sep = !edit)),
      Option.when(edit)(MenuItem("ごみ箱へ", () => trash(List(n))(), danger = true, sep = true))
    ).flatten
  }

  /** 画像の縮小表示に使う URL（大きすぎるものは出さない）. */
  def thumb(n: NodeDto): Option[String] =
    Option.when(!n.isDir && kind(n) == FileKind.Image && n.size <= 15L * 1024 * 1024 && FileKind.ext(n.name) != "svg")(s"${Api.contentUrl(n.id)}?v=${n.version}")

  def copyLink(n: NodeDto): Unit = {
    val url = dom.window.location.origin + (if (n.isDir) s"/files/${n.id}" else s"/view/${n.id}")
    Dom.copy(url, "リンクをコピーしました（共有している人だけが開けます）")
  }

}

/** 一覧の行（path は場所。検索・最近などで出す）. */
final case class Row(node: NodeDto, path: Option[String] = None)

/** 行のメニューの項目（sep は前に区切り線）. */
final case class MenuItem(label: String, run: () => Unit, danger: Boolean = false, sep: Boolean = false)

/** 一覧の中でのドラッグ（ファイル・フォルダを別のフォルダへ動かす）. */
object Drag {
  val Mime = "application/x-skawau-ids"

  def start(ids: List[UUID], e: dom.Event): Unit = {
    val dt = e.asInstanceOf[dom.DragEvent].dataTransfer
    dt.setData(Mime, ids.mkString(","))
    dt.setData("text/plain", ids.size.toString + " 件")
    dt.effectAllowed = dom.DataTransferEffectAllowedKind.move
  }

  /** ドロップされた ID（一覧の中からのドラッグのとき）. */
  def ids(e: dom.Event): List[UUID] = {
    val dt = e.asInstanceOf[dom.DragEvent].dataTransfer
    Option(dt).map(_.getData(Mime)).filter(_ != null).toList.flatMap(_.split(',').toList).flatMap(s => scala.util.Try(UUID.fromString(s)).toOption)
  }

  def isInternal(e: dom.Event): Boolean = {
    val dt = e.asInstanceOf[dom.DragEvent].dataTransfer
    dt != null && dt.types.toList.contains(Mime)
  }
}
