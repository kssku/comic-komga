package org.gotson.komga.infrastructure.metadata.comicmeta

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.BookMetadataPatch
import org.sqlite.SQLiteConfig
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Read-only access to the external comic metadata database (comic_meta.db).
 *
 * Purpose: provide book title and tags WITHOUT opening the book archive (CBZ),
 * so that metadata refresh never triggers a download from the cloud drive.
 *
 * Enable by setting `komga.comic-meta.db-path` to the database file location.
 * When unset or unreadable, all lookups return null and Komga falls back to
 * its normal behavior.
 *
 * Connection strategy: a single cached read-only connection is reused across lookups
 * (synchronized, since SQLite connections are not thread-safe for concurrent use).
 *
 * Failure strategy: a connection/query failure disables lookups (they become cheap no-ops)
 * so a missing or locked DB does not flood the log on bulk metadata refreshes. This is a
 * COOLDOWN rather than a permanent disable: after [retryCooldownMs] the next lookup is
 * allowed one reconnection attempt. A transient lock therefore recovers on its own without
 * restarting the container, while a permanently missing DB costs at most one attempt per
 * cooldown window.
 */
@Service
class ComicMetaRepository(
  @param:Value("\${komga.comic-meta.db-path:}") private val dbPath: String,
  @param:Value("\${komga.comic-meta.source-id:wnacg}") private val sourceId: String,
  // Injectable so tests can drive the retry path without sleeping. Tune the default
  // (currently 60s) if recovery latency after a temporary lock matters more than log noise.
  @param:Value("\${komga.comic-meta.retry-cooldown-ms:60000}") private val retryCooldownMs: Long = RETRY_COOLDOWN_MS,
) : DisposableBean {
  @Volatile
  private var enabled: Boolean = dbPath.isNotBlank() && Files.isReadable(Paths.get(dbPath))

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
    if (dbPath.isNotBlank()) {
      if (enabled) logger.info { "ComicMeta database enabled: $dbPath (source=$sourceId)" }
      else logger.warn { "ComicMeta database configured but not readable: $dbPath" }
    } else {
      logger.info { "ComicMeta database not configured, disabled" }
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
      // Re-check readability at open time: the DB may have been removed or replaced
      // since construction, which must degrade to "disabled" instead of erroring per book.
      if (!Files.isReadable(Paths.get(dbPath))) {
        disable("ComicMeta database no longer readable: $dbPath", null)
        return null
      }
      return try {
        val config =
          SQLiteConfig().apply {
            setReadOnly(true)
            setBusyTimeout(BUSY_TIMEOUT_MS)
          }
        config.createConnection("jdbc:sqlite:file:$dbPath?mode=ro").also { connection = it }
      } catch (e: Exception) {
        disable("Could not open ComicMeta database: $dbPath", e)
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
    val failedAt = lastFailureAt
    // never failed => disabled because the DB was absent at construction; let the same
    // cooldown gate it so a DB created later is still picked up without a restart
    if (failedAt == 0L) {
      if (!Files.isReadable(Paths.get(dbPath))) return false
    } else if (System.currentTimeMillis() - failedAt < retryCooldownMs) {
      return false
    }
    synchronized(lock) {
      if (enabled) return true
      if (!Files.isReadable(Paths.get(dbPath))) {
        // keep the cooldown running from the ORIGINAL failure so a missing DB is not
        // re-stat'ed on every single lookup
        if (failedAt == 0L) lastFailureAt = System.currentTimeMillis()
        return false
      }
      enabled = true
      if (retryWarned.compareAndSet(false, true)) {
        logger.warn { "ComicMeta database cooldown elapsed, retrying connection: $dbPath" }
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
   * @param comicId book file name without extension (e.g. "13748" for 13748.cbz)
   * @return patch with title/tags, or null when the book is unknown or DB disabled
   */
  fun findBookMetadata(comicId: String): BookMetadataPatch? {
    if (!retryAllowedNow()) return null
    return try {
      synchronized(lock) {
        val conn = connection() ?: return null
        val title = queryTitle(conn, comicId)
        val tags = queryTags(conn, comicId)
        // both queries completed without throwing: the connection is genuinely usable again,
        // so clear the failure latch and report recovery exactly once
        if (recoveryPending.compareAndSet(true, false)) {
          failureLogged.set(false)
          retryWarned.set(false)
          logger.info { "ComicMeta database recovered, queries re-enabled: $dbPath" }
        }
        if (title == null && tags.isEmpty()) null
        else BookMetadataPatch(
          title = title?.ifBlank { null },
          tags = tags.ifEmpty { null },
        )
      }
    } catch (e: Exception) {
      disable("Error querying ComicMeta for comicId=$comicId", e)
      null
    }
  }

  private fun queryTitle(conn: Connection, comicId: String): String? {
    val sql = "SELECT title FROM comics WHERE source_id = ? AND comic_id = ? LIMIT 1"
    conn.prepareStatement(sql).use { st ->
      st.queryTimeout = QUERY_TIMEOUT_SECONDS
      st.setString(1, sourceId)
      st.setString(2, comicId)
      st.executeQuery().use { rs ->
        if (rs.next()) return rs.getString(1)
      }
    }
    return null
  }

  private fun queryTags(conn: Connection, comicId: String): Set<String> {
    val sql = "SELECT t.name FROM tags t JOIN comics_tags ct ON ct.tag_id = t.id AND ct.source_id = t.source_id WHERE ct.source_id = ? AND ct.comic_id = ?"
    val result = LinkedHashSet<String>()
    conn.prepareStatement(sql).use { st ->
      st.queryTimeout = QUERY_TIMEOUT_SECONDS
      st.setString(1, sourceId)
      st.setString(2, comicId)
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
    private const val BUSY_TIMEOUT_MS = 5_000
    private const val QUERY_TIMEOUT_SECONDS = 10

    /** Default cooldown before a disabled repository may retry; tune via config or constructor. */
    const val RETRY_COOLDOWN_MS = 60_000L
  }
}