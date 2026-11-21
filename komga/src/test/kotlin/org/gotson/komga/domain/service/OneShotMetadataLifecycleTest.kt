package org.gotson.komga.domain.service

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.BookMetadata
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class OneShotMetadataLifecycleTest {
  private val bookRepository = mockk<BookRepository>()
  private val bookMetadataRepository = mockk<BookMetadataRepository>()
  private val seriesRepository = mockk<SeriesRepository>()
  private val seriesMetadataRepository = mockk<SeriesMetadataRepository>()

  private val lifecycle =
    OneShotMetadataLifecycle(bookRepository, bookMetadataRepository, seriesRepository, seriesMetadataRepository)

  private val series = makeSeries(name = "series title").copy(oneshot = true, id = "series-id")
  private val book = makeBook(name = "book title", id = "book-id", seriesId = "series-id").copy(oneshot = true)

  @Test
  fun `given series edit of a one-shot when syncing then the book metadata follows`() {
    every { seriesRepository.findByIdOrNull("series-id") } returns series
    every { bookRepository.findAllBySeriesId("series-id") } returns listOf(book)
    every { seriesMetadataRepository.findByIdOrNull("series-id") } returns
      SeriesMetadata(title = "edited title", titleSort = "edited title", summary = "edited summary", tags = setOf("t1"), seriesId = "series-id")
    every { bookMetadataRepository.findByIdOrNull("book-id") } returns
      BookMetadata(title = "book title", number = "1", numberSort = 1F, bookId = "book-id")
    val updated = slot<BookMetadata>()
    every { bookMetadataRepository.update(capture(updated)) } returns Unit

    lifecycle.syncBookFromSeries("series-id")

    assertThat(updated.captured.title).isEqualTo("edited title")
    assertThat(updated.captured.summary).isEqualTo("edited summary")
    assertThat(updated.captured.tags).containsExactly("t1")
  }

  @Test
  fun `given book edit of a one-shot when syncing then the series metadata follows`() {
    every { bookRepository.findByIdOrNull("book-id") } returns book
    every { seriesRepository.findByIdOrNull("series-id") } returns series
    every { bookRepository.findAllBySeriesId("series-id") } returns listOf(book)
    every { bookMetadataRepository.findByIdOrNull("book-id") } returns
      BookMetadata(title = "edited title", summary = "edited summary", number = "1", numberSort = 1F, tags = setOf("t1"), bookId = "book-id")
    every { seriesMetadataRepository.findByIdOrNull("series-id") } returns
      SeriesMetadata(title = "series title", titleSort = "series title", seriesId = "series-id")
    val updated = slot<SeriesMetadata>()
    every { seriesMetadataRepository.update(capture(updated)) } returns Unit

    lifecycle.syncSeriesFromBook("book-id")

    assertThat(updated.captured.title).isEqualTo("edited title")
    assertThat(updated.captured.titleSort).isEqualTo("edited title")
    assertThat(updated.captured.tags).containsExactly("t1")
  }

  // The test suite runs with per_class lifecycle, so the mocks are shared between tests and
  // would accumulate recorded calls; clear the call history (stubs stay) between tests.
  @AfterEach
  fun clearRecordedCalls() =
    clearMocks(bookRepository, bookMetadataRepository, seriesRepository, seriesMetadataRepository)

  @Test
  fun `given a multi-volume series when syncing then nothing is touched`() {
    val multiBook = makeBook(name = "multi book", id = "multi-book", seriesId = "multi-series").copy(oneshot = false)
    every { bookRepository.findByIdOrNull("multi-book") } returns multiBook
    every { seriesRepository.findByIdOrNull("multi-series") } returns
      makeSeries(name = "multi").copy(id = "multi-series", oneshot = false)

    lifecycle.syncSeriesFromBook("multi-book")

    verify(exactly = 0) { seriesMetadataRepository.update(any()) }
  }
}
