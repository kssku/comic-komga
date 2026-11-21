package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.BookMetadata
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * CUSTOM FORK: keep the two metadata layers of a ONE-SHOT in step.
 *
 * Komga models a single-volume work as a series holding exactly one book, so a one-shot has TWO
 * metadata rows: SERIES_METADATA and BOOK_METADATA. The lists show the book title in one-shot
 * mode and the series title in series mode, while the book page reads the series metadata as its
 * "one-shot attributes". Editing either side therefore used to leave the other stale, and the
 * same work showed up under two different titles.
 *
 * The scanner writes both sides at insert time; this class covers USER EDITS (and any other path
 * that updates a single side) by mirroring the shared fields to the other layer.
 *
 * Deliberately NOT mirrored: locks and per-layer fields (number, release date, reading direction,
 * publisher, age rating, links) — those only make sense on one layer or belong to importers.
 */
@Service
class OneShotMetadataLifecycle(
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val seriesRepository: SeriesRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
) {
  /** Mirror the series metadata of a one-shot to its single book. */
  fun syncBookFromSeries(seriesId: String) {
    val series = seriesRepository.findByIdOrNull(seriesId) ?: return
    if (!series.oneshot) return

    val books = bookRepository.findAllBySeriesId(seriesId)
    if (books.size != 1) return
    val book = books.first()

    val seriesMetadata = seriesMetadataRepository.findByIdOrNull(seriesId) ?: return
    val bookMetadata = bookMetadataRepository.findByIdOrNull(book.id) ?: return
    if (bookMetadata.sameSharedFieldsAs(seriesMetadata)) return

    bookMetadataRepository.update(bookMetadata.mirrorFrom(seriesMetadata))
    logger.debug { "One-shot sync: series " + series.id + " -> book " + book.id }
  }

  /** Mirror the book metadata of a one-shot to its series. */
  fun syncSeriesFromBook(bookId: String) {
    val book = bookRepository.findByIdOrNull(bookId) ?: return
    if (!book.oneshot) return

    val series = seriesRepository.findByIdOrNull(book.seriesId) ?: return
    if (!series.oneshot) return
    if (bookRepository.findAllBySeriesId(series.id).size != 1) return

    val bookMetadata = bookMetadataRepository.findByIdOrNull(bookId) ?: return
    val seriesMetadata = seriesMetadataRepository.findByIdOrNull(series.id) ?: return
    if (seriesMetadata.sameSharedFieldsAs(bookMetadata)) return

    seriesMetadataRepository.update(seriesMetadata.mirrorFrom(bookMetadata))
    logger.debug { "One-shot sync: book " + book.id + " -> series " + series.id }
  }

  // A book has no titleSort of its own (only a series does), so the shared surface is title,
  // summary and tags.
  private fun BookMetadata.sameSharedFieldsAs(series: SeriesMetadata) =
    title == series.title &&
      summary == series.summary &&
      tags == series.tags

  private fun SeriesMetadata.sameSharedFieldsAs(book: BookMetadata) =
    title == book.title &&
      titleSort == book.title &&
      summary == book.summary &&
      tags == book.tags

  private fun BookMetadata.mirrorFrom(series: SeriesMetadata) =
    copy(
      title = series.title,
      summary = series.summary,
      tags = series.tags,
    )

  private fun SeriesMetadata.mirrorFrom(book: BookMetadata) =
    copy(
      title = book.title,
      titleSort = book.title,
      summary = book.summary,
      tags = book.tags,
    )
}
