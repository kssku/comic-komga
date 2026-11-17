package org.gotson.komga.domain.service

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.BookPage
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * CUSTOM FORK: in-memory cache of a book's page list, keyed by book id.
 *
 * Opening a book reads its ZIP central directory, which on a CloudDrive2/115 FUSE
 * mount is a remote round-trip. The result is small (a few hundred [BookPage] rows)
 * and does not change unless the file itself is replaced, so it is worth holding in
 * memory for a while instead of paying that read again on every open.
 *
 * This class is deliberately NOT wired into the existing call chain yet: it is a
 * standalone component. [BookPageListResolver] is its only intended reader.
 *
 * Design notes:
 *  - Every entry carries the file fingerprint it was built from ([LocalDateTime]
 *    lastModified + size). A lookup compares the stored fingerprint against the
 *    current one and treats a mismatch as a miss, so replacing a book file can never
 *    serve a stale page list.
 *  - Eviction is weighted by PAGE COUNT, not entry count: 500 one-page books and 3
 *    books of 200 pages should not compete on equal footing, and the point of the
 *    limit is bounding retained rows.
 *  - [Caffeine.expireAfterAccess] rather than `expireAfterWrite`: a book that is
 *    being read continuously should not be evicted mid-read just because it was
 *    cached a while ago.
 */
@Service
class BookPageMemoryCache(
  private val komgaProperties: KomgaProperties,
) {
  /** Immutable snapshot of a page list plus the file identity it was produced from. */
  data class CachedPageList(
    val bookFileLastModified: LocalDateTime,
    val bookFileSize: Long,
    val pages: List<BookPage>,
    val cachedAt: LocalDateTime = LocalDateTime.now(),
  ) {
    fun matches(
      fingerprintLastModified: LocalDateTime,
      fingerprintSize: Long,
    ): Boolean = bookFileLastModified == fingerprintLastModified && bookFileSize == fingerprintSize
  }

  // CUSTOM FORK: built eagerly from configuration. `maximumWeight` and the TTL are
  // both read from KomgaProperties so the cache can be tuned or disabled at runtime
  // configuration level without touching call sites.
  private val cache =
    Caffeine
      .newBuilder()
      .maximumWeight(komgaProperties.analysis.pageCacheMaxTotalPages.toLong())
      // Weigher returns Int; a single book's page count is far below Int.MAX_VALUE.
      .weigher<String, CachedPageList> { _, value -> value.pages.size }
      .expireAfterAccess(komgaProperties.analysis.pageCacheTtlMinutes.toLong(), TimeUnit.MINUTES)
      .build<String, CachedPageList>()

  /**
   * Returns the cached page list for [bookId] only when the stored fingerprint still
   * matches the caller's current fingerprint. A mismatch is treated as a miss; the
   * stale entry is left for Caffeine to evict on its own (removing it here would race
   * with a concurrent [put] of the fresh value).
   */
  fun get(
    bookId: String,
    fingerprintLastModified: LocalDateTime,
    fingerprintSize: Long,
  ): List<BookPage>? {
    if (!enabled()) return null

    val cached = cache.getIfPresent(bookId) ?: return null
    if (!cached.matches(fingerprintLastModified, fingerprintSize)) {
      logger.debug {
        "Page list cache fingerprint mismatch for book $bookId " +
          "(cached: ${cached.bookFileLastModified}/${cached.bookFileSize}, " +
          "current: $fingerprintLastModified/$fingerprintSize)"
      }
      return null
    }
    return cached.pages
  }

  /** Stores the page list for [bookId], tagged with the fingerprint it was built from. */
  fun put(
    bookId: String,
    fingerprintLastModified: LocalDateTime,
    fingerprintSize: Long,
    pages: List<BookPage>,
  ) {
    if (!enabled()) return
    if (pages.isEmpty()) return

    cache.put(
      bookId,
      CachedPageList(
        bookFileLastModified = fingerprintLastModified,
        bookFileSize = fingerprintSize,
        pages = pages,
      ),
    )
  }

  /** Drops one book's entry. Intended for explicit invalidation, not for normal flow. */
  fun invalidate(bookId: String) {
    cache.invalidate(bookId)
  }

  private fun enabled(): Boolean = komgaProperties.analysis.pageCacheEnabled
}