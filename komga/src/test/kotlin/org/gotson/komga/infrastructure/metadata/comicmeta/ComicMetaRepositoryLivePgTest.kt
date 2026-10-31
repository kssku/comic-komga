package org.gotson.komga.infrastructure.metadata.comicmeta

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * LIVE integration test: talks to the real PostgreSQL `comic_meta` database, no mocks.
 *
 * Opt-in only. Skipped unless COMIC_META_LIVE_TEST=true, so CI and normal `gradle test`
 * stay offline. Credentials come from the environment and are never printed.
 *
 *   COMIC_META_LIVE_TEST=true \
 *   COMIC_META_DB_URL=jdbc:postgresql://127.0.0.1:55432/comic_meta \
 *   COMIC_META_DB_USER=comic \
 *   COMIC_META_DB_PASSWORD=... \
 *   gradle :komga:test --tests '*ComicMetaRepositoryLivePgTest*'
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComicMetaRepositoryLivePgTest {
  private var repo: ComicMetaRepository? = null

  @BeforeAll
  fun setUp() {
    assumeTrue(System.getenv("COMIC_META_LIVE_TEST") == "true", "live PG test disabled")
    val url = System.getenv("COMIC_META_DB_URL").orEmpty()
    val user = System.getenv("COMIC_META_DB_USER").orEmpty()
    val pass = System.getenv("COMIC_META_DB_PASSWORD").orEmpty()
    assumeTrue(url.isNotBlank() && user.isNotBlank(), "missing PG connection env")
    repo =
      ComicMetaRepository(
        enabledByConfig = true,
        dbUrl = url,
        dbUser = user,
        dbPassword = pass,
        sourceId = "wnacg",
      )
  }

  @AfterAll
  fun tearDown() {
    repo?.destroy()
  }

  @Test
  fun `connected and returns title plus tags for a tagged work`() {
    val patch = repo!!.findBookMetadata("187597")
    assertThat(patch).isNotNull
    assertThat(patch!!.title).isNotBlank()
    assertThat(patch.tags).isNotNull
    println("LIVE 187597 -> title=${patch.title} tags=${patch.tags}")
  }

  @Test
  fun `returns title and empty tags for an untagged work`() {
    val patch = repo!!.findBookMetadata("13748")
    assertThat(patch).isNotNull
    assertThat(patch!!.title).isNotBlank()
    assertThat(patch.tags).isNullOrEmpty()
    println("LIVE 13748 -> title=${patch.title} tags=${patch.tags}")
  }

  @Test
  fun `returns null for an unknown comic id`() {
    val patch = repo!!.findBookMetadata("999999999")
    assertThat(patch).isNull()
    println("LIVE 999999999 -> $patch")
  }

  @Test
  fun `illegal source-id is rejected and never queries`() {
    val bad =
      ComicMetaRepository(
        enabledByConfig = true,
        dbUrl = System.getenv("COMIC_META_DB_URL"),
        dbUser = System.getenv("COMIC_META_DB_USER"),
        dbPassword = System.getenv("COMIC_META_DB_PASSWORD"),
        sourceId = "xxx",
      )
    try {
      assertThat(bad.findBookMetadata("13748")).isNull()
      println("LIVE source-id=xxx -> null (whitelist rejected)")
    } finally {
      bad.destroy()
    }
  }
}