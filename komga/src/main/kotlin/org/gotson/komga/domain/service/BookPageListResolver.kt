package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.BookPage
import org.gotson.komga.domain.model.MediaType
import org.gotson.komga.domain.model.MediaUnsupportedException
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.gotson.komga.infrastructure.mediacontainer.divina.DivinaExtractor
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.extension

private val logger = KotlinLogging.logger {}

/**
 * CUSTOM FORK: the single entry point for resolving a book's page list WITHOUT any
 * database access.
 *
 * Flow: fingerprint the file -> consult [BookPageMemoryCache] -> on a miss, read the
 * ZIP central directory through the existing [DivinaExtractor] implementations ->
 * map to [BookPage] -> cache the result -> return it.
 *
 * Hard invariant: this class performs NO database write and NO database read of
 * MEDIA / MEDIA_PAGE. There is no `MediaRepository` in the constructor by design.
 * The whole point is to serve a page list from the archive's central directory plus
 * an in-memory cache, so that opening a book never persists analysis state.
 *
 * Reuse, not reimplementation: the archive read goes through the same
 * [DivinaExtractor.getEntries] the analyzer already calls, so the fork's
 * central-directory-only behaviour (no per-entry local-header seek, no dimension
 * probe) is inherited rather than duplicated.
 */
@Service
class BookPageListResolver(
  private val bookPageMemoryCache: BookPageMemoryCache,
  private val contentDetector: ContentDetector,
  extractors: List<DivinaExtractor>,
  private val komgaProperties: KomgaProperties,
) {
  private val divinaExtractors: Map<String, DivinaExtractor> =
    extractors.flatMap { e -> e.mediaTypes().map { it to e } }.toMap()

  // CUSTOM FORK: single-flight, following the pattern already established by
  // BookLifecycle.inflightAnalyses. The claim is made with putIfAbsent and NOT
  // computeIfAbsent, because a computeIfAbsent mapping function runs under the
  // ConcurrentHashMap bucket lock while the work inside is a multi-second archive
  // read on a remote mount; holding the lock for that long would serialize unrelated
  // books behind one slow read.
  private val inflight = ConcurrentHashMap<String, CompletableFuture<List<BookPage>>>()

  /**
   * Resolves the page list of [book].
   *
   * On timeout or failure this returns an empty list rather than throwing. Rationale:
   * the caller's observable contract is "show the pages of this book", and an empty
   * list is the same shape the existing analyzer produces for an unreadable/unsupported
   * book (`Media.status = ERROR` with no pages). Throwing here would force every future
   * call site to add a try/catch around a display path, and a page list that cannot be
   * read is a degraded-but-renderable state, not a programming error. The failure is
   * logged with the book id, so a real problem stays diagnosable.
   */
  fun resolve(book: Book): List<BookPage> {
    val fingerprintLastModified = book.fileLastModified
    val fingerprintSize = book.fileSize

    bookPageMemoryCache.get(book.id, fingerprintLastModified, fingerprintSize)?.let { cached ->
      logger.debug { "Page list cache hit for book ${book.id} (${cached.size} pages)" }
      return cached
    }

    val mine = CompletableFuture<List<BookPage>>()
    val existing = inflight.putIfAbsent(book.id, mine)
    if (existing == null) {
      try {
        val pages = readPageList(book)
        if (pages.isNotEmpty()) {
          bookPageMemoryCache.put(book.id, fingerprintLastModified, fingerprintSize, pages)
        }
        mine.complete(pages)
      } catch (e: Throwable) {
        mine.completeExceptionally(e)
      } finally {
        // Only drop OUR entry: a newer request may have installed a fresh future.
        inflight.remove(book.id, mine)
      }
    }

    val future = existing ?: mine
    return try {
      future.get(komgaProperties.analysis.pageResolveTimeoutSeconds.toLong(), TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
      logger.warn { "Timed out after ${komgaProperties.analysis.pageResolveTimeoutSeconds}s resolving page list for book ${book.id}" }
      // Do not remove the shared future: another caller may still be able to use it,
      // and the owning thread removes its own entry in its finally block.
      emptyList()
    } catch (e: ExecutionException) {
      val cause = e.cause
      if (cause is MediaUnsupportedException) {
        logger.info { "Book ${book.id} is not a supported archive: ${cause.code}" }
      } else {
        logger.warn(cause) { "Failed to resolve page list for book ${book.id}" }
      }
      emptyList()
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      logger.warn { "Interrupted while resolving page list for book ${book.id}" }
      emptyList()
    }
  }

  /**
   * Reads the page list directly from the archive's central directory.
   *
   * Book identity comes from the file extension only: `.cbz` and `.zip` both resolve to
   * the ZIP extractor. `contentDetector.detectMediaType(book.path)` is deliberately NOT
   * used, because it opens the file to sniff magic bytes — a real IO on a FUSE mount,
   * and this class must not pay it just to choose an extractor.
   *
   * `analyzeDimensions` is passed as `false`: with the fork's ZipExtractor that means
   * the page list is built from the central directory alone (entry name, entry size),
   * with no per-entry open. Dimension stays null, which the page-list DTO tolerates.
   *
   * Only entries whose detected media type is an image become pages, mirroring what
   * the analyzer does; the remaining entries are not part of a page list.
   */
  private fun readPageList(book: Book): List<BookPage> {
    val extension = book.path.extension.lowercase()
    val extractor =
      when (extension) {
        "cbz", "zip" -> divinaExtractors[MediaType.ZIP.type]
        else -> null
      }
    if (extractor == null) {
      logger.debug { "No DivinaExtractor for book ${book.id} (extension=$extension)" }
      return emptyList()
    }

    // Dimensions are deliberately not analyzed: honouring the flag would open every
    // entry, which is exactly the remote-mount cost this fork avoids.
    val entries = extractor.getEntries(book.path, analyzeDimensions = false)

    return entries
      .filter { entry -> entry.mediaType?.let { contentDetector.isImage(it) } ?: false }
      .map { entry ->
        BookPage(
          fileName = entry.name,
          mediaType = entry.mediaType!!,
          dimension = entry.dimension,
          fileSize = entry.fileSize,
        )
      }
  }
}