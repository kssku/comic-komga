package org.gotson.komga.infrastructure.metadata.comicmeta

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.BookMetadataPatch
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

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
 */
@Service
class ComicMetaRepository(
  @param:Value("\${komga.comic-meta.db-path:}") private val dbPath: String,
  @param:Value("\${komga.comic-meta.source-id:wnacg}") private val sourceId: String,
) {
  private val enabled: Boolean = dbPath.isNotBlank() && Files.isReadable(Paths.get(dbPath))

  init {
    if (dbPath.isNotBlank()) {
      if (enabled) logger.info { "ComicMeta database enabled: $dbPath (source=$sourceId)" }
      else logger.warn { "ComicMeta database configured but not readable: $dbPath" }
    } else {
      logger.info { "ComicMeta database not configured, disabled" }
    }
  }

  private fun connect(): Connection? =
    try {
      DriverManager.getConnection("jdbc:sqlite:file:$dbPath?mode=ro")
    } catch (e: Exception) {
      logger.error(e) { "Could not open ComicMeta database" }
      null
    }

  /**
   * Look up title and tags for a book.
   * @param comicId book file name without extension (e.g. "13748" for 13748.cbz)
   * @return patch with title/tags, or null when the book is unknown or DB disabled
   */
  fun findBookMetadata(comicId: String): BookMetadataPatch? {
    if (!enabled) return null
    return try {
      connect()?.use { conn ->
        val title = queryTitle(conn, comicId)
        val tags = queryTags(conn, comicId)
        if (title == null && tags.isEmpty()) null
        else BookMetadataPatch(
          title = title?.ifBlank { null },
          tags = tags.ifEmpty { null },
        )
      }
    } catch (e: Exception) {
      logger.error(e) { "Error querying ComicMeta for comicId=$comicId" }
      null
    }
  }

  private fun queryTitle(conn: Connection, comicId: String): String? {
    val sql = "SELECT title FROM comics WHERE source_id = ? AND comic_id = ? LIMIT 1"
    conn.prepareStatement(sql).use { st ->
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
}