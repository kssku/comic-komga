package org.gotson.komga.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URL
import java.time.LocalDateTime

class MediaExtensionDivinaTest {
  private val analyzedAt = LocalDateTime.of(2026, 10, 1, 12, 0, 0)

  private fun book(
    fileLastModified: LocalDateTime,
    fileSize: Long,
  ) = Book(
    name = "book",
    url = URL("file:/book.cbz"),
    fileLastModified = fileLastModified,
    fileSize = fileSize,
  )

  @Test
  fun `given same lastModified and size when matching then returns true`() {
    val extension = MediaExtensionDivina(bookFileLastModified = analyzedAt, bookFileSize = 1024L)

    assertThat(extension.matches(book(analyzedAt, 1024L))).isTrue()
  }

  @Test
  fun `given different lastModified when matching then returns false`() {
    val extension = MediaExtensionDivina(bookFileLastModified = analyzedAt, bookFileSize = 1024L)

    assertThat(extension.matches(book(analyzedAt.plusSeconds(1), 1024L))).isFalse()
  }

  @Test
  fun `given different file size when matching then returns false`() {
    val extension = MediaExtensionDivina(bookFileLastModified = analyzedAt, bookFileSize = 1024L)

    assertThat(extension.matches(book(analyzedAt, 2048L))).isFalse()
  }

  @Test
  fun `given missing lastModified when matching then returns false`() {
    val extension = MediaExtensionDivina(bookFileLastModified = null, bookFileSize = 1024L)

    assertThat(extension.matches(book(analyzedAt, 1024L))).isFalse()
  }

  @Test
  fun `given missing file size when matching then returns false`() {
    val extension = MediaExtensionDivina(bookFileLastModified = analyzedAt, bookFileSize = null)

    assertThat(extension.matches(book(analyzedAt, 1024L))).isFalse()
  }

  @Test
  fun `given empty extension when matching then returns false`() {
    assertThat(MediaExtensionDivina().matches(book(analyzedAt, 1024L))).isFalse()
  }
}