package org.gotson.komga.infrastructure.metadata.comicmeta

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * Unit tests for [ComicMetaRepository] against the PostgreSQL schema.
 *
 * The JDBC layer is mocked (no Testcontainers in this project) so the tests stay fast and
 * offline, while still asserting the three things that actually broke in the SQLite -> PG
 * migration: the table-name prefix, the two-hop tag join, and the title fallback to the work.
 * Real-server behaviour (read-only session, scram auth) is covered by the manual PG probe.
 */
class ComicMetaRepositoryTest {
  private val url = "jdbc:postgresql://db.example:5432/comic_meta"
  private val user = "comic"
  private val pass = "secret-not-real"

  private lateinit var conn: Connection
  private lateinit var setSessionStmt: PreparedStatement
  private lateinit var titleStmt: PreparedStatement
  private lateinit var tagsStmt: PreparedStatement
  private lateinit var titleRs: ResultSet
  private lateinit var tagsRs: ResultSet

  /** SQL handed to each prepared statement, in call order. */
  private val preparedSql = mutableListOf<String>()

  @BeforeEach
  fun setUp() {
    // It is a field, so it survives between tests: without this the `first { ... }`
    // lookups below would return SQL captured by an earlier test (e.g. prefix "wnacg").
    preparedSql.clear()
    conn = mockk(relaxed = true)
    setSessionStmt = mockk(relaxed = true)
    titleStmt = mockk(relaxed = true)
    tagsStmt = mockk(relaxed = true)
    titleRs = mockk(relaxed = true)
    tagsRs = mockk(relaxed = true)

    every { conn.createStatement() } returns setSessionStmt
    every { conn.prepareStatement(any()) } answers {
      val sql = firstArg<String>()
      preparedSql += sql
      if (sql.contains("_works")) titleStmt else tagsStmt
    }
    every { titleStmt.executeQuery() } returns titleRs
    every { tagsStmt.executeQuery() } returns tagsRs

    // default: no title row, no tag rows
    every { titleRs.next() } returns false
    every { tagsRs.next() } returns false
  }

  @AfterEach
  fun tearDown() {
    unmockkStatic(DriverManager::class)
  }

  private fun givenConnection() {
    mockkStatic(DriverManager::class)
    every { DriverManager.getConnection(url, user, pass) } returns conn
  }

  private fun repo(
    source: String = "wnacg",
    enabled: Boolean = true,
    dbUrl: String = url,
    cooldown: Long = ComicMetaRepository.RETRY_COOLDOWN_MS,
  ) = ComicMetaRepository(
    enabledByConfig = enabled,
    dbUrl = dbUrl,
    dbUser = user,
    dbPassword = pass,
    sourceId = source,
    retryCooldownMs = cooldown,
  )

  @Test
  fun `given title row when looking up then the volume title is returned`() {
    givenConnection()
    every { titleRs.next() } returns true
    every { titleRs.getString(1) } returns "Volume Title"

    val patch = repo().findBookMetadata("13748")

    assertThat(patch).isNotNull
    assertThat(patch!!.title).isEqualTo("Volume Title")
    assertThat(patch.tags).isNull() // no tags -> null per BookMetadataPatch contract
  }

  @Test
  fun `given tag rows when looking up then all tag names are collected`() {
    givenConnection()
    every { tagsRs.next() } returnsMany listOf(true, true, false)
    every { tagsRs.getString(1) } returnsMany listOf("tagA", "tagB")

    val patch = repo().findBookMetadata("13748")

    assertThat(patch).isNotNull
    assertThat(patch!!.tags).containsExactly("tagA", "tagB")
  }

  @Test
  fun `given volume without tags when looking up then tags are empty and not fabricated`() {
    givenConnection()
    every { titleRs.next() } returns true
    every { titleRs.getString(1) } returns "Only Title"

    val patch = repo().findBookMetadata("13748")

    assertThat(patch).isNotNull
    assertThat(patch!!.title).isEqualTo("Only Title")
    assertThat(patch.tags).isNull()
  }

  @Test
  fun `given unknown comic when looking up then null is returned`() {
    givenConnection()

    assertThat(repo().findBookMetadata("nope")).isNull()
  }

  @Test
  fun `given source prefix when querying then every table is prefixed with it`() {
    givenConnection()
    repo(source = "jm").findBookMetadata("42")

    val titleSql = preparedSql.first { it.contains("_works") }
    val tagsSql = preparedSql.first { it.contains("_tags") }

    assertThat(titleSql).contains("FROM jm_volumes v")
    assertThat(titleSql).contains("LEFT JOIN jm_works w ON w.work_id = v.work_id")
    assertThat(tagsSql).contains("FROM jm_volumes v")
    assertThat(tagsSql).contains("JOIN jm_work_tags wt ON wt.work_id = v.work_id")
    assertThat(tagsSql).contains("JOIN jm_tags t ON t.id = wt.tag_id")
    // the old flat schema must be gone
    assertThat(titleSql + tagsSql).doesNotContain("FROM comics ")
    assertThat(titleSql + tagsSql).doesNotContain("comics_tags")
    assertThat(titleSql + tagsSql).doesNotContain("source_id")
  }

  @Test
  fun `given comicId when querying then it is bound as a single parameter`() {
    givenConnection()
    repo().findBookMetadata("13748")

    verify(exactly = 1) { titleStmt.setString(1, "13748") }
    verify(exactly = 1) { tagsStmt.setString(1, "13748") }
  }

  @Test
  fun `given non-whitelisted sourceId then no connection is attempted`() {
    mockkStatic(DriverManager::class)
    every { DriverManager.getConnection(any<String>(), any(), any()) } throws AssertionError("must not connect")

    val patch = repo(source = "jm_works; DROP TABLE x--").findBookMetadata("13748")

    assertThat(patch).isNull()
    verify(exactly = 0) { DriverManager.getConnection(any<String>(), any(), any()) }
  }

  @Test
  fun `given disabled config then no connection is attempted`() {
    mockkStatic(DriverManager::class)
    every { DriverManager.getConnection(any<String>(), any(), any()) } throws AssertionError("must not connect")

    assertThat(repo(enabled = false).findBookMetadata("13748")).isNull()
    verify(exactly = 0) { DriverManager.getConnection(any<String>(), any(), any()) }
  }

  @Test
  fun `given blank url then no connection is attempted`() {
    mockkStatic(DriverManager::class)
    every { DriverManager.getConnection(any<String>(), any(), any()) } throws AssertionError("must not connect")

    assertThat(repo(dbUrl = "").findBookMetadata("13748")).isNull()
    verify(exactly = 0) { DriverManager.getConnection(any<String>(), any(), any()) }
  }

  @Test
  fun `given connection failure when cooldown has not elapsed then it is not retried`() {
    mockkStatic(DriverManager::class)
    every { DriverManager.getConnection(url, user, pass) } throws SQLException("connection refused")

    val r = repo(cooldown = 3_600_000L)
    assertThat(r.findBookMetadata("13748")).isNull()

    // even if the DB comes back, the cooldown must keep it a no-op
    every { DriverManager.getConnection(url, user, pass) } returns conn
    assertThat(r.findBookMetadata("13748")).isNull()
    verify(exactly = 1) { DriverManager.getConnection(url, user, pass) }
  }

  @Test
  fun `given connection failure when cooldown elapsed then a retry recovers`() {
    mockkStatic(DriverManager::class)
    every { DriverManager.getConnection(url, user, pass) } throws SQLException("connection refused")

    val r = repo(cooldown = 0L)
    assertThat(r.findBookMetadata("13748")).isNull()

    every { DriverManager.getConnection(url, user, pass) } returns conn
    every { titleRs.next() } returns true
    every { titleRs.getString(1) } returns "Recovered"

    val patch = r.findBookMetadata("13748")
    assertThat(patch).isNotNull
    assertThat(patch!!.title).isEqualTo("Recovered")
  }

  @Test
  fun `given query throws after connect then lookup degrades to null and disables`() {
    givenConnection()
    every { titleStmt.executeQuery() } throws SQLException("relation does not exist")

    val r = repo(cooldown = 3_600_000L)
    assertThat(r.findBookMetadata("13748")).isNull()
    // connection was closed and the repository latched into cooldown
    verify { conn.close() }
    assertThat(r.findBookMetadata("13748")).isNull()
  }

  @Test
  fun `given session opened then read only transaction is requested`() {
    givenConnection()

    repo().findBookMetadata("13748")

    verify { setSessionStmt.execute("SET TRANSACTION READ ONLY") }
  }

  // --- isComicMetaManaged: the guard that keeps a missing DB row from opening the archive ---

  @Test
  fun `given book under source root when managed check then it is owned by comic-meta`() {
    // no JDBC at all: the guard is configuration-only on purpose
    assertThat(repo().isComicMetaManaged(Path.of("/content/wnacg/oneshots/1-50000/13748.cbz"))).isTrue()
  }

  @Test
  fun `given book outside source root when managed check then it is not owned`() {
    assertThat(repo().isComicMetaManaged(Path.of("/elsewhere/wnacg/oneshots/13748.cbz"))).isFalse()
  }

  @Test
  fun `given unknown first segment when managed check then it is not owned`() {
    assertThat(repo().isComicMetaManaged(Path.of("/content/unknown/oneshots/13748.cbz"))).isFalse()
  }

  @Test
  fun `given single source configured when managed check then segment still has to match`() {
    val r = repo(source = "wnacg")
    assertThat(r.isComicMetaManaged(Path.of("/content/wnacg/oneshots/13748.cbz"))).isTrue()
    assertThat(r.isComicMetaManaged(Path.of("/content/jm/oneshots/13748.cbz"))).isFalse()
  }

  @Test
  fun `given authoritative disabled when managed check then nothing is owned`() {
    val r =
      ComicMetaRepository(
        enabledByConfig = true,
        dbUrl = url,
        dbUser = user,
        dbPassword = pass,
        sourceId = "*",
        authoritative = false,
      )
    assertThat(r.isComicMetaManaged(Path.of("/content/wnacg/oneshots/13748.cbz"))).isFalse()
  }

  @Test
  fun `given repository disabled by config when managed check then nothing is owned`() {
    assertThat(repo(enabled = false).isComicMetaManaged(Path.of("/content/wnacg/oneshots/13748.cbz"))).isFalse()
  }

  @Test
  fun `given database unreachable when managed check then the book is still owned`() {
    // deliberately no working connection: connection health must NOT re-enable archive reads,
    // otherwise a DB outage turns into one cloud download per book
    mockkStatic(DriverManager::class)
    every { DriverManager.getConnection(url, user, pass) } throws SQLException("connection refused")
    val r = repo(cooldown = 3_600_000L)
    assertThat(r.findBookMetadata("13748")).isNull()
    assertThat(r.isComicMetaManaged(Path.of("/content/wnacg/oneshots/13748.cbz"))).isTrue()
  }
}