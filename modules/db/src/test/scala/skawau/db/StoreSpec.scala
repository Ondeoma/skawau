package skawau.db

import sbirds.shared.AppError
import cats.effect.*
import cats.syntax.all.*
import doobie.implicits.*
import munit.CatsEffectSuite
import skawau.core.*

import java.util.UUID

/** DB を使うテスト。TEST_DATABASE_URL がなければ飛ばす（例: jdbc:postgresql://localhost:54340/skawau_test）。 */
class StoreSpec extends CatsEffectSuite {

  private val cfg: Option[DbConfig] = sys.env.get("TEST_DATABASE_URL").map(url =>
    DbConfig(
      url,
      sys.env.getOrElse("TEST_DATABASE_USER", "skawau"),
      sys.env.getOrElse("TEST_DATABASE_PASSWORD", "skawau")
    )
  )

  private val xaFixture = ResourceSuiteLocalFixture(
    "xa",
    cfg.fold(Resource.eval(IO.raiseError[doobie.Transactor[IO]](new RuntimeException("no db"))))(c =>
      Resource.eval(Database.migrate(c)) *> Database.transactor(c)
    )
  )

  override def munitFixtures = if cfg.isDefined then List(xaFixture) else Nil

  private final case class Env(xa: doobie.Transactor[IO], users: UserStore, nodes: NodeStore, shares: ShareStore)

  private def withDb(name: String)(body: Env => IO[Unit]): Unit =
    test(name) {
      if cfg.isEmpty then IO(assume(false, "TEST_DATABASE_URL が未設定"))
      else {
        val xa = xaFixture()
        body(Env(xa, new UserStore(xa), new NodeStore(xa), new ShareStore(xa)))
      }
    }

  private def user(e: Env, name: String): IO[User] = e.users.upsert(s"test:${UUID.randomUUID()}", name, None, None)
  private def actor(u: User) = Actor(Some(u.id), "web")
  private def blob(size: Long = 10) =
    BlobInfo(s"files/${UUID.randomUUID()}", size, "text/plain", UUID.randomUUID().toString)
  private def ok[A](io: IO[Either[AppError, A]]): IO[A] = io.map(_.fold(e => fail(e.message), identity))

  withDb("フォルダ・ファイルの作成、同じ名前は大文字小文字を区別せず拒否") { e =>
    for {
      u <- user(e, "所有者")
      root <- e.nodes.root(u.id)
      root2 <- e.nodes.root(u.id)
      _ = assertEquals(root.id, root2.id)
      dir <- ok(e.nodes.createFolder(root, "資料", actor(u)))
      _ = assertEquals(dir.ancestors, List(root.id))
      f <- ok(e.nodes.createFile(dir, "a.txt", blob(), actor(u)))
      _ = assertEquals(f.ancestors, List(root.id, dir.id))
      dup <- e.nodes.createFile(dir, "A.TXT", blob(), actor(u))
      _ = assert(dup.left.exists(_.status == 409))
      p <- e.nodes.path(f)
      _ = assertEquals(p, "/資料/a.txt")
    } yield ()
  }

  withDb("共有の権限は祖先から引き継がれ、グループでも効く") { e =>
    for {
      a <- user(e, "共有する人")
      b <- user(e, "受け取る人")
      c <- user(e, "グループの人")
      _ <- e.users.syncGroups(c.id, List(s"g-${UUID.randomUUID()}"))
      gc <- e.users.withGroups(c.id).map(_.get._2)
      root <- e.nodes.root(a.id)
      dir <- ok(e.nodes.createFolder(root, "共有", actor(a)))
      sub <- ok(e.nodes.createFolder(dir, "中", actor(a)))
      f <- ok(e.nodes.createFile(sub, "x.txt", blob(), actor(a)))
      vb = Viewer(b.id, Nil)
      vc = Viewer(c.id, gc.map(_.id))
      none <- e.nodes.access(vb, f.id)
      _ = assertEquals(none, None)
      _ <- ok(e.shares.create(dir.id, a.id, "user", Some(b.id), None, "view", None, None, None, None))
      _ <- ok(e.shares.create(sub.id, a.id, "group", None, Some(gc.head.id), "edit", None, None, None, None))
      ab <- e.nodes.access(vb, f.id)
      _ = assertEquals(ab.map(_._2), Some(Access.View))
      ac <- e.nodes.access(vc, f.id)
      _ = assertEquals(ac.map(_._2), Some(Access.Edit))
      acd <- e.nodes.access(vc, dir.id)
      _ = assertEquals(acd, None) // グループには中のフォルダだけを共有した
      sw <- e.nodes.sharedWithMe(vb)
      _ = assertEquals(sw.map(_._1.id), List(dir.id))
      cs <- e.nodes.crumbs(vb, List(f))
      _ = assertEquals(cs(f.id)._1.map(_.id), List(dir.id, sub.id, f.id)) // 共有の起点から
      _ = assert(cs(f.id)._2)
    } yield ()
  }

  withDb("移動で子孫の祖先を付け替え、自分の中へは移せない") { e =>
    for {
      u <- user(e, "移動")
      root <- e.nodes.root(u.id)
      a <- ok(e.nodes.createFolder(root, "A", actor(u)))
      b <- ok(e.nodes.createFolder(root, "B", actor(u)))
      c <- ok(e.nodes.createFolder(a, "C", actor(u)))
      f <- ok(e.nodes.createFile(c, "f.txt", blob(), actor(u)))
      _ <- ok(e.nodes.move(a, b, "A", actor(u)))
      f2 <- e.nodes.find(f.id).map(_.get)
      _ = assertEquals(f2.ancestors, List(root.id, b.id, a.id, c.id))
      b2 <- e.nodes.find(b.id).map(_.get)
      bad <- e.nodes.move(b2, c, "B", actor(u))
      _ = assert(bad.isLeft)
    } yield ()
  }

  withDb("ごみ箱: 中身は見えなくなり、元の場所へ戻せる（同じ名前があれば別名）") { e =>
    for {
      u <- user(e, "ごみ箱")
      root <- e.nodes.root(u.id)
      dir <- ok(e.nodes.createFolder(root, "消す", actor(u)))
      f <- ok(e.nodes.createFile(dir, "f.txt", blob(), actor(u)))
      _ <- ok(e.nodes.trash(dir, actor(u)))
      gone <- e.nodes.find(f.id)
      _ = assertEquals(gone, None)
      list <- e.nodes.trashList(u.id)
      _ = assert(list.exists(t => t.id == dir.id && t.originalPath == "/"))
      _ <- ok(e.nodes.createFolder(root, "消す", actor(u)))
      back <- e.nodes.restore(dir, actor(u))
      _ = assertEquals(back.name, "消す (2)")
      f2 <- e.nodes.find(f.id)
      _ = assert(f2.isDefined)
    } yield ()
  }

  withDb("上書きで版が残り、古い版・完全に削除した中身は blob_trash に積まれる") { e =>
    for {
      u <- user(e, "版")
      root <- e.nodes.root(u.id)
      f <- ok(e.nodes.createFile(root, s"v-${UUID.randomUUID()}.txt", blob(), actor(u)))
      f2 <- ok(e.nodes.replaceContent(f, blob(20), actor(u), keep = 1))
      f3 <- ok(e.nodes.replaceContent(f2, blob(30), actor(u), keep = 1))
      _ = assertEquals(f3.version, 3)
      vs <- e.nodes.versions(f.id)
      _ = assertEquals(vs.map(_._1.version), List(2)) // keep = 1
      stale <- e.nodes.replaceContent(f3, blob(), actor(u), keep = 1, expectedVersion = Some(2))
      _ = assert(stale.left.exists(_.status == 409))
      first <- sql"select count(*) from blob_trash where key = ${f.blobKey.get}".query[Int].unique.transact(e.xa)
      _ = assertEquals(first, 1)
      _ <- ok(e.nodes.trash(f3, actor(u)))
      _ <- e.nodes.purge(f3, actor(u))
      left <- sql"select count(*) from blob_trash where key = ${f3.blobKey.get} or key = ${f2.blobKey.get}".query[
        Int
      ].unique.transact(e.xa)
      _ = assertEquals(left, 2)
      any <- e.nodes.findAny(f.id)
      _ = assertEquals(any, None)
    } yield ()
  }

  withDb("登録したアイコンは IdP のアイコンで上書きせず、差し替えた前の画像は後で消す") { e =>
    val sub = s"test:${UUID.randomUUID()}"
    for {
      u <- e.users.upsert(sub, "アイコン", None, Some("https://idp.example/a.png"))
      _ <- e.users.setAvatar(u.id, Some("avatars/k1"), Some("/api/users/x/avatar?v=1"))
      u2 <- e.users.upsert(sub, "アイコン", None, Some("https://idp.example/b.png"))
      _ = assertEquals(u2.avatarUrl, Some("/api/users/x/avatar?v=1"))
      _ <- e.users.setAvatar(u.id, Some("avatars/k2"), Some("/api/users/x/avatar?v=2"))
      _ <- e.users.setAvatar(u.id, None, None)
      key <- e.users.avatarKey(u.id)
      _ = assertEquals(key, None)
      gone <-
        sql"select count(*) from blob_trash where key in ('avatars/k1', 'avatars/k2')".query[Int].unique.transact(
          e.xa
        )
      _ = assertEquals(gone, 2)
      u3 <- e.users.upsert(sub, "アイコン", None, Some("https://idp.example/c.png"))
      _ = assertEquals(u3.avatarUrl, Some("https://idp.example/c.png"))
    } yield ()
  }

  withDb("所有者の違うフォルダへの移動: ごみ箱の中身は元の所有者に残り、共有は新しい所有者のものになる") { e =>
    for {
      a <- user(e, "渡す人")
      b <- user(e, "受け取る人")
      ra <- e.nodes.root(a.id)
      rb <- e.nodes.root(b.id)
      dir <- ok(e.nodes.createFolder(ra, "渡す", actor(a)))
      keep <- ok(e.nodes.createFile(dir, "k.txt", blob(), actor(a)))
      gone <- ok(e.nodes.createFile(dir, "t.txt", blob(), actor(a)))
      _ <- ok(e.nodes.trash(gone, actor(a)))
      shId <- ok(e.shares.create(
        dir.id,
        a.id,
        "link",
        None,
        None,
        "view",
        Some(UUID.randomUUID().toString),
        None,
        None,
        None
      ))
      _ <- ok(e.nodes.move(dir, rb, "渡す", actor(a)))
      k2 <- e.nodes.find(keep.id).map(_.get)
      _ = assertEquals(k2.ownerId, b.id)
      t2 <- e.nodes.trashed(a.id, gone.id)
      _ = assert(t2.exists(_.ancestors == List(ra.id))) // 元の所有者のルートの下へ外れる
      none <- e.nodes.trashed(b.id, gone.id)
      _ = assertEquals(none, None)
      sh2 <- e.shares.find(shId)
      _ = assertEquals(sh2.map(_.ownerId), Some(b.id))
    } yield ()
  }

  withDb("容量には以前の版も数え、名前を変えると Content-Type も変わる") { e =>
    for {
      u <- user(e, "容量")
      root <- e.nodes.root(u.id)
      f <- ok(e.nodes.createFile(root, "a.html", blob(100), actor(u)))
      _ <- ok(e.nodes.replaceContent(f, blob(1), actor(u), keep = 5))
      used <- e.nodes.usage(u.id)
      _ = assertEquals(used, 101L)
      f2 <- e.nodes.find(f.id).map(_.get)
      r <- ok(e.nodes.rename(f2, "a.pdf", actor(u)))
      _ = assertEquals(r.contentType, Some("application/pdf"))
    } yield ()
  }

  withDb("コピーはフォルダの中身ごと、新しい ID と祖先で作る") { e =>
    for {
      u <- user(e, "コピー")
      root <- e.nodes.root(u.id)
      a <- ok(e.nodes.createFolder(root, "元", actor(u)))
      s <- ok(e.nodes.createFolder(a, "中", actor(u)))
      _ <- ok(e.nodes.createFile(s, "x.txt", blob(), actor(u)))
      dest <- ok(e.nodes.createFolder(root, "先", actor(u)))
      c <- ok(e.nodes.copy(a, dest, "元", actor(u), k => IO.pure(k + "-copy")))
      sub <- e.nodes.subtree(c)
      _ = assertEquals(sub.map(_._2), List("中", "中/x.txt"))
      _ = assert(sub.forall((n, _) => n.ancestors.take(3) == List(root.id, dest.id, c.id)))
      _ = assert(sub.find(_._2 == "中/x.txt").flatMap(_._1.blobKey).exists(_.endsWith("-copy")))
    } yield ()
  }

  withDb("名前の検索で % と _ は文字として扱う") { e =>
    for {
      u <- user(e, "検索")
      root <- e.nodes.root(u.id)
      tag = UUID.randomUUID().toString.take(6)
      _ <- ok(e.nodes.createFile(root, s"${tag}a_b.txt", blob(), actor(u)))
      _ <- ok(e.nodes.createFile(root, s"${tag}axb.txt", blob(), actor(u)))
      _ <- ok(e.nodes.createFile(root, s"${tag}100%.txt", blob(), actor(u)))
      hit1 <- e.nodes.search(Viewer(u.id, Nil), s"${tag}a_b", 10)
      _ = assertEquals(hit1.map(_._1.name), List(s"${tag}a_b.txt"))
      hit2 <- e.nodes.search(Viewer(u.id, Nil), "100%", 10)
      _ = assert(hit2.exists(_._1.name == s"${tag}100%.txt") && !hit2.exists(_._1.name.contains("axb")))
    } yield ()
  }
}
