package skawau.api

import sbirds.shared.AppError
import cats.data.EitherT
import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import skawau.core.*
import skawau.db.*
import sbirds.observe.{Metrics, Telemetry}
import skawau.shared.*
import skawau.storage.{BlobMeta, BlobStore}

import java.security.MessageDigest
import java.util.UUID
import java.util.zip.{ZipEntry, ZipOutputStream}

/** 同じ名前があるときの扱い。 */
enum OnConflict {
  case Fail, Rename, Overwrite
}

object OnConflict {
  def parse(s: Option[String]): OnConflict = s.map(_.trim.toLowerCase) match {
    case Some("rename") => Rename
    case Some("overwrite") => Overwrite
    case _ => Fail
  }
}

/**
 * ファイル操作（REST・WebDAV・公開リンク・ONLYOFFICE で共通）。権限の確認、容量の確認、S3 への保存、DB の更新をまとめる。
 * 権限の規則: 中身の書き換えはそのノードの編集権限、作成はフォルダの編集権限、
 * 名前の変更・移動・削除は「親フォルダ」の編集権限（共有された起点そのものは受け取った人が消したり動かしたりできない）。
 */
final class FileService(nodes: NodeStore, users: UserStore, blobs: Option[BlobStore], cfg: ApiConfig) {

  private def store: Res[BlobStore] = IO.pure(blobs.toRight(AppError(ErrorCode.StorageNotConfigured)))

  private def newKey(): String = s"files/${UUID.randomUUID()}"

  // ---------------------------------------------------------------- 権限

  /** 見えなければ 404、権限が足りなければ 403。 */
  def node(v: Viewer, id: UUID, need: Access): Res[(Node, Access)] =
    nodes.access(v, id).map {
      case None => Left(AppError(ErrorCode.NodeNotFound))
      case Some((_, a)) if !(a >= need) =>
        Left(AppError(if need == Access.Owner then ErrorCode.OwnerOnly else ErrorCode.EditPermissionRequired))
      case Some(x) => Right(x)
    }

  def folder(v: Viewer, id: UUID, need: Access): Res[(Node, Access)] =
    node(v, id, need).map(_.flatMap((n, a) => if n.isDir then Right((n, a)) else Left(AppError(ErrorCode.NotAFolder))))

  /** 名前の変更・移動・削除ができるか（親フォルダの編集権限）。 */
  def canDetach(v: Viewer, n: Node): Res[Unit] =
    n.parentId match {
      case None => IO.pure(Left(AppError(ErrorCode.RootNotOperable)))
      case Some(p) => nodes.access(v, p).map {
          case Some((_, a)) if a.canEdit => Right(())
          case _ => Left(AppError(ErrorCode.SharedRootLocked))
        }
    }

  // ---------------------------------------------------------------- 容量

  def quotaOf(u: User): Option[Long] = u.quota.orElse(cfg.defaultQuota)

  def quota(u: User): IO[QuotaDto] = nodes.usage(u.id).map(QuotaDto(_, quotaOf(u)))

  /** 所有者の容量に add バイト足せるか。 */
  def checkQuota(ownerId: UUID, add: Long): Res[Unit] =
    users.find(ownerId).flatMap {
      case None => IO.pure(Left(AppError(ErrorCode.OwnerNotFound)))
      case Some(u) =>
        quotaOf(u) match {
          case None => IO.pure(Right(()))
          case Some(limit) =>
            nodes.usage(ownerId).map(used =>
              if used + add > limit then
                Left(AppError(
                  ErrorCode.QuotaExceeded,
                  s"容量が足りません（使用 ${Bytes.format(used)} / 上限 ${Bytes.format(limit)}）"
                ))
              else Right(())
            )
        }
    }

  // ---------------------------------------------------------------- 中身の保存

  private def sha256(p: Path): IO[String] = IO.blocking {
    val md = MessageDigest.getInstance("SHA-256")
    val in = java.nio.file.Files.newInputStream(p.toNioPath)
    try {
      val buf = new Array[Byte](64 * 1024)
      Iterator.continually(in.read(buf)).takeWhile(_ >= 0).foreach(n => md.update(buf, 0, n))
    } finally in.close()
    md.digest().map(b => f"${b & 0xff}%02x").mkString
  }

  /** 一時ファイルを S3 に置く。 */
  def putFile(tmp: Path, name: String): Res[BlobInfo] =
    store.flatMap(_.traverse { b =>
      for {
        size <- Files[IO].size(tmp)
        sum <- sha256(tmp)
        key = newKey()
        ct = FileKind.contentType(name)
        _ <- b.put(key, tmp, ct)
      } yield BlobInfo(key, size, ct, sum)
    })

  /** 本文を一時ファイルに受け取る（上限を超えたら 413）。 */
  def receive[A](body: Stream[IO, Byte])(f: Path => Res[A]): Res[A] =
    Files[IO].tempFile.use { tmp =>
      body.take(cfg.maxUploadBytes + 1).through(Files[IO].writeAll(tmp)).compile.drain *>
        Files[IO].size(tmp).flatMap { size =>
          if size > cfg.maxUploadBytes then
            IO.pure(Left(AppError(ErrorCode.FileTooLarge, s"ファイルが大きすぎます（上限 ${Bytes.format(cfg.maxUploadBytes)}）")))
          else f(tmp)
        }
    }

  def receiveBytes[A](bytes: Array[Byte])(f: Path => Res[A]): Res[A] = receive(Stream.emits(bytes.toSeq))(f)

  private def trashBlob(info: BlobInfo): IO[Unit] = blobs.traverse_(_.delete(info.key).handleError(_ => ()))

  // EitherT で失敗（AppError）を途中で抜ける。R は Res を EitherT にしたもの
  private type R[A] = EitherT[IO, AppError, A]
  private def R[A](r: Res[A]): R[A] = EitherT(r)
  private def lift[A](io: IO[A]): R[A] = EitherT.liftF(io)
  private def failed[A](f: AppError): R[A] = EitherT.leftT(f)
  private def check(cond: Boolean, f: => AppError): R[Unit] = EitherT.cond[IO](cond, (), f)
  private def validName(name: String): R[Unit] =
    Names.invalid(name).fold(EitherT.rightT[IO, AppError](()))(m => failed(AppError(ErrorCode.FileNameInvalid, m)))

  /** S3 に置いた中身を使う処理。失敗したら置いた中身を消す。 */
  private def withBlob[A](tmp: Path, name: String)(f: BlobInfo => Res[A]): R[A] =
    R(putFile(tmp, name)).flatMap(info => R(f(info).flatTap(r => trashBlob(info).whenA(r.isLeft))))

  /**
   * フォルダにファイルを置く。同じ名前のファイルがあれば conflict に従う（Overwrite は新しい版にする）。
   * relPath を渡すと途中のフォルダを作る（フォルダごとのアップロード）。
   */
  def upload(actor: Actor,
             v: Viewer,
             folderId: UUID,
             relPath: List[String],
             name0: String,
             tmp: Path,
             conflict: OnConflict): Res[Node] = {
    val name = Names.normalize(name0)
    Telemetry.span("file.upload", "via" -> actor.via)((for {
      _ <- (name :: relPath).traverse_(validName)
      dir0 <- R(folder(v, folderId, Access.Edit)).map(_._1)
      dir <- R(nodes.ensureFolders(dir0, relPath, actor))
      size <- lift(Files[IO].size(tmp))
      _ <- R(checkQuota(dir.ownerId, size))
      existing <- lift(nodes.childNamed(dir.id, name))
      n <- existing match {
        case Some(ex) if ex.isDir => failed(AppError(ErrorCode.FolderNameTaken, s"「$name」という名前のフォルダがあります"))
        case Some(ex) if conflict == OnConflict.Overwrite => R(writeTo(actor, ex, tmp))
        case Some(_) if conflict == OnConflict.Fail => failed(AppError(ErrorCode.NodeExists, s"「$name」はすでにあります"))
        case Some(_) => lift(nodes.uniqueName(dir.id, name)).flatMap(nm =>
            withBlob(tmp, nm)(nodes.createFile(dir, nm, _, actor))
          )
        case None => withBlob(tmp, name)(nodes.createFile(dir, name, _, actor))
      }
    } yield n).value).flatTap {
      // アップロード（REST・WebDAV・公開リンク・MCP）の数とバイト数
      case Right(n) => Metrics.count("skawau.files.uploaded", "via" -> actor.via) *>
          Metrics.add("skawau.files.uploaded_bytes", n.size, "via" -> actor.via)
      case Left(_) => IO.unit
    }
  }

  /** 既存のファイルの中身を置き換える（新しい版）。権限は呼び出し側で確かめること。 */
  def writeTo(actor: Actor,
              n: Node,
              tmp: Path,
              kind: String = EventKind.Updated,
              expectedVersion: Option[Int] = None): Res[Node] =
    Telemetry.span("file.write", "via" -> actor.via)((for {
      _ <- check(!n.isDir, AppError(ErrorCode.FolderNotWritable))
      size <- lift(Files[IO].size(tmp))
      _ <- R(checkQuota(n.ownerId, math.max(0L, size - n.size)))
      r <- withBlob(tmp, n.name) { info =>
        // 中身が同じなら版を増やさない（Document Server が変更なしで保存を知らせてくることがあるため）
        if n.sha256.contains(info.sha256) && n.size == info.size then trashBlob(info).as(Right(n))
        else nodes.replaceContent(n, info, actor, cfg.versionsKept, kind, expectedVersion)
      }
    } yield r).value)

  /** 空のファイル（Office は最小の文書）を作る。 */
  def createEmpty(actor: Actor, v: Viewer, folderId: UUID, name0: String): Res[Node] = {
    val name = Names.normalize(name0)
    receiveBytes(OfficeTemplates.blank(FileKind.ext(name)))(tmp =>
      upload(actor, v, folderId, Nil, name, tmp, OnConflict.Fail)
    )
  }

  def createFolder(actor: Actor, v: Viewer, parentId: UUID, name0: String): Res[Node] = {
    val name = Names.normalize(name0)
    (validName(name) *>
      R(folder(v, parentId, Access.Edit)).flatMap((p, _) => R(nodes.createFolder(p, name, actor)))).value
  }

  // ---------------------------------------------------------------- 名前の変更・移動・コピー・削除

  def rename(actor: Actor, v: Viewer, id: UUID, name0: String): Res[Node] = {
    val name = Names.normalize(name0)
    (for {
      _ <- validName(name)
      n <- R(node(v, id, Access.View)).map(_._1)
      _ <- R(canDetach(v, n))
      r <- if n.name == name then EitherT.rightT[IO, AppError](n) else R(nodes.rename(n, name, actor))
    } yield r).value
  }

  /** 移動またはコピー。 */
  def transfer(actor: Actor,
               v: Viewer,
               id: UUID,
               targetId: UUID,
               copy: Boolean,
               conflict: OnConflict,
               newName: Option[String] = None): Res[Node] = {
    def sizeOf(n: Node): IO[Long] =
      if n.isDir then nodes.subtree(n).map(_.filterNot(_._1.isDir).map(_._1.size).sum) else IO.pure(n.size)
    (for {
      n <- R(node(v, id, Access.View)).map(_._1)
      target <- R(folder(v, targetId, Access.Edit)).map(_._1)
      name = newName.map(Names.normalize).getOrElse(n.name)
      _ <- validName(name)
      _ <- R(canDetach(v, n)).whenA(!copy)
      // 所有者の違うフォルダへ移すと所有者ごと移るので、ほかの人のものは移せない（編集権限だけで他人のものを奪えないように）
      _ <- check(
        copy || target.ownerId == n.ownerId || n.ownerId == v.userId,
        AppError(ErrorCode.MoveAcrossOwners)
      )
      // コピーと、所有者の違うフォルダへの移動は、移った先の所有者の容量を使う
      _ <- (lift(sizeOf(n)).flatMap(sz => R(checkQuota(target.ownerId, sz)))).whenA(copy || target.ownerId != n.ownerId)
      r <- if !copy && n.parentId.contains(target.id) && n.name == name then EitherT.rightT[IO, AppError](n)
      else
        for {
          existing <- lift(nodes.childNamed(target.id, name))
          nm <- existing match {
            case Some(ex) if ex.id == n.id => EitherT.rightT[IO, AppError](name)
            case Some(_) if conflict == OnConflict.Fail =>
              failed(AppError(ErrorCode.MoveTargetExists, s"移動先に「$name」があります"))
            case Some(_) if conflict == OnConflict.Rename => lift(nodes.uniqueName(target.id, name))
            case Some(ex) => R(nodes.trash(ex, actor)).as(name)
            case None => EitherT.rightT[IO, AppError](name)
          }
          moved <- if copy then R(store).flatMap(b => R(nodes.copy(n, target, nm, actor, k => copyBlob(b, k))))
          else R(nodes.move(n, target, nm, actor))
        } yield moved
    } yield r).value
  }

  private def copyBlob(b: BlobStore, from: String): IO[String] = {
    val to = newKey()
    b.copy(from, to).flatMap(ok =>
      if ok then IO.pure(to) else IO.raiseError(new RuntimeException(s"中身が見つかりません: $from"))
    )
  }

  def trash(actor: Actor, v: Viewer, id: UUID): Res[Unit] =
    (R(node(v, id, Access.View)).map(_._1).flatTap(n => R(canDetach(v, n))).flatMap(n =>
      R(nodes.trash(n, actor))
    )).value

  // ---------------------------------------------------------------- 版

  def restoreVersion(actor: Actor, n: Node, versionId: UUID): Res[Node] =
    (for {
      ver <- EitherT.fromOptionF(nodes.version(n.id, versionId), AppError(ErrorCode.VersionNotFound))
      b <- R(store)
      _ <- R(checkQuota(n.ownerId, math.max(0L, ver.size - n.size)))
      key <- lift(copyBlob(b, ver.blobKey))
      info = BlobInfo(key, ver.size, ver.contentType.getOrElse(FileKind.contentType(n.name)), "")
      r <- R(nodes.replaceContent(n, info, actor, cfg.versionsKept, EventKind.VersionRestored).flatTap(r =>
        trashBlob(info).whenA(r.isLeft)
      ))
    } yield r).value

  // ---------------------------------------------------------------- 取り出し

  def read(key: String, range: Option[(Long, Option[Long])]): IO[Option[(BlobMeta, Stream[IO, Byte])]] =
    blobs.fold(IO.pure(None))(_.get(key, range))

  /** ZIP にまとめて返す（フォルダは中身ごと。entries は ZIP の中のパスとノード）。 */
  def zip(entries: List[(String, Node)]): Stream[IO, Byte] =
    fs2.io.readOutputStream[IO](64 * 1024) { os =>
      Resource.make(IO(new ZipOutputStream(os)))(z => IO.blocking(z.close())).use { z =>
        entries.traverse_ {
          (path, n) =>
            IO.blocking {
              val safe = path.split('/').map(Names.zipSegment).mkString("/")
              val e = new ZipEntry(if n.isDir then safe + "/" else safe)
              e.setTime(n.updatedAt.toEpochMilli)
              z.putNextEntry(e)
            } *>
              n.blobKey.filterNot(_ => n.isDir).traverse_(k =>
                read(
                  k,
                  None
                ).flatMap(_.traverse_(_._2.chunks.evalMap(c => IO.blocking(z.write(c.toArray))).compile.drain))
              ) *>
              IO.blocking(z.closeEntry())
        }
      }
    }

  /** 選んだノード（フォルダは中身ごと）の ZIP。 */
  def zipOf(items: List[Node]): IO[Stream[IO, Byte]] = zipEntries(items).map(zip)

  /** ZIP の中身（選んだノードと、フォルダならその中身）。同じ名前は「名前 (2)」にする。 */
  private def zipEntries(items: List[Node]): IO[List[(String, Node)]] =
    items.foldLeftM((Set.empty[String], List.empty[(String, Node)])) {
      case ((taken, acc), n) => {
        val top = Names.unique(n.name, taken)
        (if n.isDir then
           nodes.subtree(n).map(_.map((c, p) => s"$top/$p" -> c))
         else IO.pure(Nil))
          .map(sub => (taken + top, acc ++ ((top -> n) :: sub)))
      }
    }.map(_._2)
}
