package org.gotson.komga.infrastructure.metadata.comicmeta

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.BookMetadataPatch
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
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
  // Semantically this is now the TABLE NAME PREFIX of the source (jm / pica / wnacg),
  // not a value in a `source_id` column. Validated against a whitelist before use.
  @param:Value("\${komga.comic-meta.source-id:wnacg}") private val sourceId: String = DEFAULT_SOURCE_ID,
  // Injectable so tests can drive the retry path without sleeping. Tune the default
  // (currently 60s) if recovery latency after a temporary outage matters more than log noise.
  @param:Value("\${komga.comic-meta.retry-cooldown-ms:60000}") private val retryCooldownMs: Long = RETRY_COOLDOWN_MS,
) : DisposableBean {
  /** Table prefix, or null when [sourceId] is not whitelisted (lookups then stay disabled). */
  private val tablePrefix: String? = sourceId.trim().lowercase().takeIf { it in TABLE_PREFIXES }

  @Volatile
  private var enabled: Boolean = enabledByConfig && dbUrl.isNotBlank() && tablePrefix != null

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
      tablePrefix == null ->
        logger.error {
          "ComicMeta source-id '$sourceId' is not one of $TABLE_PREFIXES, metadata lookups disabled"
        }
      !enabledByConfig ->
        logger.info { "ComicMeta database not enabled (komga.comic-meta.enabled=false), disabled" }
      dbUrl.isBlank() ->
        logger.info { "ComicMeta database URL not configured, disabled" }
      else ->
        // never log the URL verbatim: it may carry credentials in the userinfo part
        logger.info { "ComicMeta database enabled: source=$tablePrefix (url host hidden)" }
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
      val prefix = tablePrefix ?: return null
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
        logger.debug { "ComicMeta connected, table prefix=$prefix" }
        conn.also { connection = it }
      } catch (e: Exception) {
        disable("Could not open ComicMeta database (source=$prefix)", e)
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
    if (!enabledByConfig || dbUrl.isBlank() || tablePrefix == null) return false
    val failedAt = lastFailureAt
    if (failedAt == 0L) {
      // enabled but never failed: nothing to cool down, the reconnect is allowed now
      return synchronized(lock) {
        if (!enabled) {
          enabled = true
          if (retryWarned.compareAndSet(false, true)) {
            logger.warn { "ComicMeta connection retry armed (source=$tablePrefix)" }
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
        logger.warn { "ComicMeta database cooldown elapsed, retrying connection (source=$tablePrefix)" }
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
  fun findBookMetadata(comicId: String): BookMetadataPatch? {
    if (!retryAllowedNow()) return null
    val prefix = tablePrefix ?: return null
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
  fun findKnownComicIds(comicIds: Collection<String>): Set<String> {
    if (comicIds.isEmpty() || !retryAllowedNow()) return emptySet()
    val prefix = tablePrefix ?: return emptySet()
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

    const val DEFAULT_SOURCE_ID = "wnacg"

    /** Default cooldown before a disabled repository may retry; tune via config or constructor. */
    const val RETRY_COOLDOWN_MS = 60_000L
  }
}