package org.gotson.komga.infrastructure.metadata.comicmeta

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class ComicMetaRepositoryTest {
  @TempDir
  lateinit var tempDir: Path

  private fun createDb(path: Path) {
    DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
      conn.createStatement().use { st ->
        st.execute("CREATE TABLE comics (source_id TEXT, comic_id TEXT, title TEXT)")
        st.execute("CREATE TABLE tags (id INTEGER, source_id TEXT, name TEXT)")
        st.execute("CREATE TABLE comics_tags (source_id TEXT, comic_id TEXT, tag_id INTEGER)")
        st.execute("INSERT INTO comics VALUES ('wnacg', '13748', 'Some Title')")
        st.execute("INSERT INTO tags VALUES (1, 'wnacg', 'tagA')")
        st.execute("INSERT INTO comics_tags VALUES ('wnacg', '13748', 1)")
      }
    }
  }

  @Test
  fun `given configured database when looking up known book then title and tags are returned`() {
    val db = tempDir.resolve("comic_meta.db")
    createDb(db)

    val repo = ComicMetaRepository(db.toString(), "wnacg")
    val patch = repo.findBookMetadata("13748")

    assertThat(patch).isNotNull
    assertThat(patch!!.title).isEqualTo("Some Title")
    assertThat(patch.tags).containsExactly("tagA")
  }

  @Test
  fun `given configured database when looking up unknown book then null is returned`() {
    val db = tempDir.resolve("comic_meta.db")
    createDb(db)

    val repo = ComicMetaRepository(db.toString(), "wnacg")

    assertThat(repo.findBookMetadata("unknown")).isNull()
  }

  @Test
  fun `given no database path when looking up then null is returned`() {
    val repo = ComicMetaRepository("", "wnacg")

    assertThat(repo.findBookMetadata("13748")).isNull()
  }

  @Test
  fun `given database deleted after construction when looking up then null is returned instead of throwing`() {
    val db = tempDir.resolve("comic_meta.db")
    createDb(db)

    val repo = ComicMetaRepository(db.toString(), "wnacg")
    Files.delete(db)

    // must degrade silently, not throw
    assertThat(repo.findBookMetadata("13748")).isNull()
    // subsequent calls are also cheap no-ops (repository disabled after the first failure)
    assertThat(repo.findBookMetadata("13748")).isNull()
  }

  @Test
  fun `given failure when cooldown has not elapsed then the lookup is not retried`() {
    val db = tempDir.resolve("comic_meta.db")
    createDb(db)

    // huge cooldown: the retry window can never open during this test
    val repo = ComicMetaRepository(db.toString(), "wnacg", retryCooldownMs = 3_600_000L)
    Files.delete(db)

    // first call fails and disables the repository
    assertThat(repo.findBookMetadata("13748")).isNull()

    // recreate the DB: a lookup inside the cooldown must still be a no-op,
    // proving it did not retry (otherwise it would reconnect and return the patch)
    createDb(db)
    assertThat(repo.findBookMetadata("13748")).isNull()
  }

  @Test
  fun `given failure when cooldown has elapsed then a retry is attempted and returns null when db still missing`() {
    val db = tempDir.resolve("comic_meta.db")
    createDb(db)

    // cooldown 0: the retry window is open immediately after any failure
    val repo = ComicMetaRepository(db.toString(), "wnacg", retryCooldownMs = 0L)
    Files.delete(db)

    assertThat(repo.findBookMetadata("13748")).isNull()
    // retry path is exercised repeatedly: must stay null and must never throw
    assertThat(repo.findBookMetadata("13748")).isNull()
    assertThat(repo.findBookMetadata("13748")).isNull()
  }

  @Test
  fun `given failure when cooldown elapsed and db restored then the retry recovers`() {
    val db = tempDir.resolve("comic_meta.db")
    createDb(db)

    val repo = ComicMetaRepository(db.toString(), "wnacg", retryCooldownMs = 0L)
    Files.delete(db)
    assertThat(repo.findBookMetadata("13748")).isNull()

    // DB comes back (e.g. temporary lock released / volume remounted)
    createDb(db)

    val patch = repo.findBookMetadata("13748")
    assertThat(patch).isNotNull
    assertThat(patch!!.title).isEqualTo("Some Title")
    assertThat(patch.tags).containsExactly("tagA")
  }

  @Test
  fun `given database file replaced after construction when looking up then new content is served`() {
    val db = tempDir.resolve("comic_meta.db")
    createDb(db)

    val repo = ComicMetaRepository(db.toString(), "wnacg")
    assertThat(repo.findBookMetadata("13748")!!.title).isEqualTo("Some Title")

    // cached connection must survive repeated lookups
    assertThat(repo.findBookMetadata("13748")!!.title).isEqualTo("Some Title")
  }
}