package org.gotson.komga.infrastructure.metadata.comicmeta

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.BookMetadataPatch
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Read-only access to the external comic metadata database (PostgreSQL).
 *
 * Purpose: provide book title and tags WITHOUT opening the book archive (CBZ),
 * so that metadata refresh never triggers a download from the cloud drive.
 *
 * MIGRATION NOTE (SQLite -> PostgreSQL): this repository used to read a local
 * `comic_meta.db` SQLite file through `komga.comic-meta.db-path`. That property is
 * GONE and will not be read anymore. The PostgreSQL schema is not a flat table: the
 * three sources (jm / pica / wnacg) are distinguished by a TABLE NAME PREFIX instead of
 * a `source_id` column, and tags hang off the work (two hops: volume -> work -> tags).
 * See [TABLE_PREFIXES] for the whitelist and [queryTitle] / [queryTags] for the SQL.
 *
 * Enable by setting `komga.comic-meta.enabled=true` and a `komga.comic-meta.db-url`
 * (plus user/password). When disabled, unset or unreachable, all lookups return null
 * and Komga falls back to its normal behavior.
 *
 * Connection strategy: a single cached read-only connection is reused across lookups
 * (synchronized, since a JDBC connection is not safe for concurrent use).
 *
 * Read-only guarantee: the connection is created with [Connection.TRANSACTION_READ_ONLY]
 * and the session is additionally pinned with `SET TRANSACTION READ ONLY` right after
 * connecting, so even a bad SQL statement cannot mutate the metadata DB. We do NOT rely
 * on `conn.isReadOnly = true` alone because the PostgreSQL driver implements it as a
 * hint that is only applied when a transaction starts.
 *
 * Failure strategy: a connection/query failure disables lookups (they become cheap
 * no-ops) so an unreachable DB does not flood the log on bulk metadata refreshes. This
 * is a COOLDOWN rather than a permanent disable: after [retryCooldownMs] the next lookup
 * is allowed one reconnection attempt.
 */
@Service
class ComicMetaRepository(
  @param:Value("\${komga.comic-meta.enabled:false}") private val enabledByConfig: Boolean = false,
  @param:Value("\${komga.comic-meta.db-url:}") private val dbUrl: String = "",
  @param:Value("\${komga.comic-meta.db-user:}") private val dbUser: String = "",
  @param:Value("\${komga.comic-meta.db-password:}") private val dbPassword: String = "",
  // CUSTOM FORK: TABLE NAME PREFIXES of the sources, comma-separated, or "*" for all known.
  // Multi-source is required for a library tree holding wnacg + jm + pica side by side:
  // numeric comic ids overlap heavily between wnacg and jm, so a single prefix silently
  // returns the WRONG work's title and tags for every id collision.
  @param:Value("\${komga.comic-meta.source-id:wnacg}") private val sourceId: String = DEFAULT_SOURCE_ID,
  // CUSTOM FORK: root the library tree is mounted at, used to derive the source of a book
  // from its path ("<root>/<source>/oneshots/..."). Empty disables path-based source lookup.
  @param:Value("\${komga.comic-meta.source-root:/content}") private val sourceRoot: String = DEFAULT_SOURCE_ROOT,
  // Injectable so tests can drive the retry path without sleeping. Tune the default
  // (currently 60s) if recovery latency after a temporary outage matters more than log noise.
  @param:Value("\${komga.comic-meta.retry-cooldown-ms:60000}") private val retryCooldownMs: Long = RETRY_COOLDOWN_MS,
  // CUSTOM FORK: when true, books under [sourceRoot] whose source is configured are OWNED by the
  // comic-meta database. If the database does not know a book, Komga must NOT fall back to opening
  // the archive (on a 115/CloudDrive2 FUSE mount that means downloading the whole CBZ from the
  // cloud just to read one XML entry). Set false only for a mixed tree where books without a
  // comic-meta row still need their embedded ComicInfo.xml.
  @param:Value("\${komga.comic-meta.authoritative:true}") private val authoritative: Boolean = true,
) : DisposableBean {
  /** Whitelisted table prefixes; empty means lookups stay disabled. */
  private val tablePrefixes: Set<String> =
    sourceId
      .split(",")
      .map { it.trim().lowercase() }
      .filter { it in TABLE_PREFIXES || it == ALL_SOURCES }
      .let { configured ->
        when {
          configured.isEmpty() -> emptySet()
          ALL_SOURCES in configured -> TABLE_PREFIXES
          else -> configured.toSet()
        }
      }

  /** True when more than one source is configured, i.e. a book path is needed to pick a table. */
  private val multiSource: Boolean = tablePrefixes.size > 1

  @Volatile
  private var enabled: Boolean = enabledByConfig && dbUrl.isNotBlank() && tablePrefixes.isNotEmpty()

  /** CUSTOM FORK: whether lookups are live, so callers can decide to request metadata refresh. */
  val isEnabled: Boolean
    get() = enabled

  @Volatile
  private var connection: Connection? = null

  /** Wall-clock timestamp (ms) of the last failure, 0 when no failure has occurred yet. */
  @Volatile
  private var lastFailureAt: Long = 0L

  private val lock = Any()
  private val failureLogged = AtomicBoolean(false)
  private val retryWarned = AtomicBoolean(false)

  /** Set on the first successful query after a failure so recovery is logged exactly once. */
  private val recoveryPending = AtomicBoolean(false)

  init {
    when {
      tablePrefixes.isEmpty() ->
        logger.error {
          "ComicMeta source-id '$sourceId' is not one of $TABLE_PREFIXES (or '*'), metadata lookups disabled"
        }
      !enabledByConfig ->
        logger.info { "ComicMeta database not enabled (komga.comic-meta.enabled=false), disabled" }
      dbUrl.isBlank() ->
        logger.info { "ComicMeta database URL not configured, disabled" }
      else ->
        // never log the URL verbatim: it may carry credentials in the userinfo part
        logger.info {
          "ComicMeta database enabled: sources=$tablePrefixes, source-root='$sourceRoot' (url host hidden)"
        }
    }
  }

  /**
   * Get the cached connection, opening it on first use.
   * Returns null (and disables the repository) if the database cannot be opened.
   */
  private fun connection(): Connection? {
    connection?.let { return it }
    synchronized(lock) {
      connection?.let { return it }
      if (!enabled) return null
      return try {
        val conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)
        try {
          // Pin the session read-only. TRUNCATE/CREATE/DELETE are rejected by the server
          // with "cannot execute ... in a read-only transaction".
          conn.createStatement().use { it.execute("SET TRANSACTION READ ONLY") }
          conn.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        } catch (e: Exception) {
          conn.close()
          throw e
        }
        logger.debug { "ComicMeta connected, table prefixes=$tablePrefixes" }
        conn.also { connection = it }
      } catch (e: Exception) {
        disable("Could not open ComicMeta database (sources=$tablePrefixes)", e)
        null
      }
    }
  }

  private fun disable(message: String, cause: Exception?) {
    enabled = false
    lastFailureAt = System.currentTimeMillis()
    // arm the recovery log: the next successful query proves the connection works again
    recoveryPending.set(true)
    closeConnection()
    if (failureLogged.compareAndSet(false, true)) {
      logger.error(cause) { "$message — ComicMeta lookups disabled, will retry after ${retryCooldownMs}ms cooldown" }
    }
  }

  /**
   * Re-arm lookups when the cooldown after the last failure has elapsed.
   * Only the timestamp is checked: no sleeping, no busy-wait, no background thread.
   * @return true when this call is allowed to attempt a (re)connect
   */
  private fun retryAllowedNow(): Boolean {
    if (enabled) return true
    // misconfiguration (bad source-id / disabled / no URL) is never retried:
    // the timestamp gate below would otherwise hammer a database we must not touch
    if (!enabledByConfig || dbUrl.isBlank() || tablePrefixes.isEmpty()) return false
    val failedAt = lastFailureAt
    if (failedAt == 0L) {
      // enabled but never failed: nothing to cool down, the reconnect is allowed now
      return synchronized(lock) {
        if (!enabled) {
          enabled = true
          if (retryWarned.compareAndSet(false, true)) {
            logger.warn { "ComicMeta connection retry armed (sources=$tablePrefixes)" }
          }
        }
        enabled
      }
    }
    if (System.currentTimeMillis() - failedAt < retryCooldownMs) {
      return false
    }
    synchronized(lock) {
      if (enabled) return true
      enabled = true
      if (retryWarned.compareAndSet(false, true)) {
        logger.warn { "ComicMeta database cooldown elapsed, retrying connection (sources=$tablePrefixes)" }
      }
      return true
    }
  }

  private fun closeConnection() {
    synchronized(lock) {
      connection?.let {
        try {
          it.close()
        } catch (_: Exception) {
          // best effort on shutdown/failure
        }
      }
      connection = null
    }
  }

  /**
   * Look up title and tags for a book.
   * @param comicId book file name without extension (e.g. "13748" for 13748.cbz),
   *   matching `{p}_volumes.comic_id`
   * @return patch with title/tags, or null when the book is unknown or DB disabled
   */
  /**
   * CUSTOM FORK: resolve the source (table prefix) of a book from its path.
   *
   * "<sourceRoot>/<source>/..." -> "<source>", when that source is configured.
   * Returns null when only one source is configured (nothing to resolve), when path-based
   * lookup is unavailable, or when the first segment is not a configured source.
   */
  fun siteForPath(bookPath: Path): String? {
    if (!multiSource) return tablePrefixes.firstOrNull()
    val root = sourceRoot.trim().trimEnd('/')
    if (root.isEmpty()) return null
    val full = bookPath.toString()
    if (!full.startsWith("$root/")) return null
    val first = full.substring(root.length + 1).substringBefore('/').lowercase()
    return first.takeIf { it in tablePrefixes }
  }

  /**
   * CUSTOM FORK: strict source resolution used by [isComicMetaManaged].
   *
   * Unlike [siteForPath] it ALSO requires the path to live under [sourceRoot], and it works for a
   * single configured source too — "the first segment is not a configured source" must not be
   * silently reported as the only source when we are about to skip opening the archive.
   */
  private fun managedSourceForPath(bookPath: Path): String? {
    val root = sourceRoot.trim().trimEnd('/')
    if (root.isEmpty()) return null
    val full = bookPath.toString()
    if (!full.startsWith("$root/")) return null
    return full.substring(root.length + 1).substringBefore('/').lowercase().takeIf { it in tablePrefixes }
  }

  /**
   * CUSTOM FORK: true when this book is OWNED by the comic-meta database, i.e. metadata must be
   * taken from the database alone and the archive must never be opened for a missing row.
   *
   * Deliberately does NOT consult [enabled] / [retryAllowedNow]: an unreachable database is
   * exactly the moment we must not trigger a cloud download per book. Configuration decides, not
   * connection health.
   */
  fun isComicMetaManaged(bookPath: Path): Boolean {
    if (!authoritative) return false
    if (!enabledByConfig || dbUrl.isBlank() || tablePrefixes.isEmpty()) return false
    return managedSourceForPath(bookPath) != null
  }

  private fun singleSourceOrNull(): String? = if (!multiSource) tablePrefixes.firstOrNull() else null

  /**
   * Single-source convenience overload; returns null when several sources are configured,
   * because the id alone cannot tell which table to read.
   */
  fun findBookMetadata(comicId: String): BookMetadataPatch? {
    val prefix = singleSourceOrNull() ?: return null
    return findBookMetadata(prefix, comicId)
  }

  /**
   * Look up title and tags for a book in one specific source.
   * @param site table prefix (jm / pica / wnacg), must be one of the configured sources
   * @param comicId book file name without extension, matching `{site}_volumes.comic_id`
   */
  fun findBookMetadata(site: String, comicId: String): BookMetadataPatch? {
    if (!retryAllowedNow()) return null
    val prefix = site.trim().lowercase().takeIf { it in tablePrefixes } ?: return null
    return try {
      synchronized(lock) {
        val conn = connection() ?: return null
        val title = queryTitle(conn, prefix, comicId)
        val tags = queryTags(conn, prefix, comicId)
        // both queries completed without throwing: the connection is genuinely usable again,
        // so clear the failure latch and report recovery exactly once
        if (recoveryPending.compareAndSet(true, false)) {
          failureLogged.set(false)
          retryWarned.set(false)
          logger.info { "ComicMeta database recovered, queries re-enabled (source=$prefix)" }
        }
        if (title == null && tags.isEmpty()) null
        else BookMetadataPatch(
          title = title?.ifBlank { null },
          tags = tags.ifEmpty { null },
        )
      }
    } catch (e: Exception) {
      disable("Error querying ComicMeta for comicId=$comicId (source=$prefix)", e)
      null
    }
  }

  /**
   * CUSTOM FORK: batch existence check used by the scanner.
   *
   * A bulk scan cannot afford one round-trip per book just to decide whether to refresh its
   * metadata. This answers "which of these comic ids does the DB know" in one query per 500
   * ids, so only the known ones get a metadata refresh task (and never fall back to opening
   * the archive).
   */
  /**
   * Single-source convenience overload; returns an empty set when several sources are
   * configured, because the ids alone cannot tell which table to read.
   */
  fun findKnownComicIds(comicIds: Collection<String>): Set<String> {
    val prefix = singleSourceOrNull() ?: return emptySet()
    return findKnownComicIds(prefix, comicIds)
  }

  /**
   * CUSTOM FORK: batch existence check for ONE source.
   * @param site table prefix (jm / pica / wnacg), must be one of the configured sources
   */
  fun findKnownComicIds(site: String, comicIds: Collection<String>): Set<String> {
    if (comicIds.isEmpty() || !retryAllowedNow()) return emptySet()
    val prefix = site.trim().lowercase().takeIf { it in tablePrefixes } ?: return emptySet()
    return try {
      synchronized(lock) {
        val conn = connection() ?: return emptySet()
        val known = LinkedHashSet<String>()
        comicIds.chunked(500).forEach { chunk ->
          val placeholders = chunk.joinToString(",") { "?" }
          conn.prepareStatement("SELECT comic_id FROM ${prefix}_volumes WHERE comic_id IN (${placeholders})").use { st ->
            st.queryTimeout = QUERY_TIMEOUT_SECONDS
            chunk.forEachIndexed { index, id -> st.setString(index + 1, id) }
            st.executeQuery().use { rs ->
              while (rs.next()) known.add(rs.getString(1))
            }
          }
        }
        known
      }
    } catch (e: Exception) {
      disable("Error querying known comic ids (source=${prefix})", e)
      emptySet()
    }
  }

  /**
   * CUSTOM FORK (performance): batch title+tags for many comic ids in ONE query.
   *
   * The scanner used to enqueue one metadata refresh task per new book. On a first ingest that is
   * 190k tasks, and every task is a write transaction on SQLite's single write connection — the
   * dominant cost of a from-zero build. Since the database already owns the whole metadata for a
   * book, it can be fetched in bulk here and written together with the book itself.
   *
   * @return map of comicId -> patch; ids absent from the database are simply missing from the map
   */
  fun findBookMetadataBatch(
    site: String,
    comicIds: Collection<String>,
  ): Map<String, BookMetadataPatch> {
    if (comicIds.isEmpty() || !retryAllowedNow()) return emptyMap()
    val prefix = site.trim().lowercase().takeIf { it in tablePrefixes } ?: return emptyMap()
    return try {
      synchronized(lock) {
        val conn = connection() ?: return emptyMap()
        val out = LinkedHashMap<String, BookMetadataPatch>()
        comicIds.chunked(500).forEach { chunk ->
          val placeholders = chunk.joinToString(",") { "?" }
          val sql =
            """
            SELECT v.comic_id,
                   COALESCE(NULLIF(v.title, ''), w.title) AS title,
                   string_agg(t.name, chr(31)) AS tags
              FROM ${prefix}_volumes v
              LEFT JOIN ${prefix}_works w ON w.work_id = v.work_id
              LEFT JOIN ${prefix}_work_tags wt ON wt.work_id = v.work_id
              LEFT JOIN ${prefix}_tags t ON t.id = wt.tag_id
             WHERE v.comic_id IN ($placeholders)
             GROUP BY v.comic_id, COALESCE(NULLIF(v.title, ''), w.title)
            """.trimIndent()
          conn.prepareStatement(sql).use { st ->
            st.queryTimeout = QUERY_TIMEOUT_SECONDS
            chunk.forEachIndexed { index, id -> st.setString(index + 1, id) }
            st.executeQuery().use { rs ->
              while (rs.next()) {
                val id = rs.getString(1) ?: continue
                val title = rs.getString(2)?.trim()?.ifBlank { null }
                val tags =
                  rs.getString(3)
                    ?.split('\u001f')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?.ifEmpty { null }
                if (title != null || tags != null) {
                  out[id] = BookMetadataPatch(title = title, tags = tags)
                }
              }
            }
          }
        }
        if (recoveryPending.compareAndSet(true, false)) {
          failureLogged.set(false)
          retryWarned.set(false)
          logger.info { "ComicMeta database recovered, queries re-enabled (source=$prefix)" }
        }
        out
      }
    } catch (e: Exception) {
      disable("Error batch-querying ComicMeta (source=$prefix)", e)
      emptyMap()
    }
  }

  /**
   * Title: the volume title wins, falling back to the work title when blank/NULL.
   * The prefix is whitelisted, so string interpolation here cannot inject SQL.
   */
  private fun queryTitle(conn: Connection, prefix: String, comicId: String): String? {
    val sql =
      """
      SELECT COALESCE(NULLIF(v.title, ''), w.title) AS title
        FROM ${prefix}_volumes v
        LEFT JOIN ${prefix}_works w ON w.work_id = v.work_id
       WHERE v.comic_id = ?
       LIMIT 1
      """.trimIndent()
    conn.prepareStatement(sql).use { st ->
      st.queryTimeout = QUERY_TIMEOUT_SECONDS
      st.setString(1, comicId)
      st.executeQuery().use { rs ->
        if (rs.next()) return rs.getString(1)
      }
    }
    return null
  }

  /**
   * Tags: volume -> work -> work_tags -> tags (two hops, tags are attached to the work).
   * Returns an empty set when the volume has no tags; never null.
   */
  private fun queryTags(conn: Connection, prefix: String, comicId: String): Set<String> {
    val sql =
      """
      SELECT t.name
        FROM ${prefix}_volumes v
        JOIN ${prefix}_work_tags wt ON wt.work_id = v.work_id
        JOIN ${prefix}_tags t ON t.id = wt.tag_id
       WHERE v.comic_id = ?
      """.trimIndent()
    val result = LinkedHashSet<String>()
    conn.prepareStatement(sql).use { st ->
      st.queryTimeout = QUERY_TIMEOUT_SECONDS
      st.setString(1, comicId)
      st.executeQuery().use { rs ->
        while (rs.next()) {
          val name = rs.getString(1)?.trim()
          if (!name.isNullOrBlank()) result.add(name)
        }
      }
    }
    return result
  }

  override fun destroy() {
    closeConnection()
  }

  companion object {
    private const val QUERY_TIMEOUT_SECONDS = 10

    /** Source prefixes present in the PostgreSQL metadata database. Anything else is rejected. */
    val TABLE_PREFIXES = setOf("jm", "pica", "wnacg")

    /** Wildcard accepted in `source-id` to mean every known source. */
    const val ALL_SOURCES = "*"

    const val DEFAULT_SOURCE_ID = "wnacg"

    const val DEFAULT_SOURCE_ROOT = "/content"

    /** Default cooldown before a disabled repository may retry; tune via config or constructor. */
    const val RETRY_COOLDOWN_MS = 60_000L
  }
}