package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.SearchCondition
import org.gotson.komga.domain.model.SearchContext
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.math.BigDecimal
import java.net.URL

interface BookRepository {
  fun findByIdOrNull(bookId: String): Book?

  fun findNotDeletedByLibraryIdAndUrlOrNull(
    libraryId: String,
    url: URL,
  ): Book?

  fun findAll(): Collection<Book>

  fun findAllBySeriesId(seriesId: String): Collection<Book>

  fun findAllBySeriesIds(seriesIds: Collection<String>): Collection<Book>

  fun findAllNotDeletedByLibraryIdAndUrlNotIn(
    libraryId: String,
    urls: Collection<URL>,
  ): Collection<Book>

  /**
   * CUSTOM FORK: 名字 diff 扫描只需要 (id, url)，不加载完整 Book 对象。
   * 15 万本走完整实体反序列化会多占几百 MB，而这里只用两个字符串。
   */
  fun findAllIdAndUrlByLibraryIdNotDeleted(libraryId: String): Collection<Pair<String, String>>

  fun findAll(
    searchCondition: SearchCondition.Book?,
    searchContext: SearchContext,
    pageable: Pageable,
  ): Page<Book>

  fun findAllDeletedByFileSize(fileSize: Long): Collection<Book>

  fun findAllByLibraryIdAndWithEmptyHash(libraryId: String): Collection<Book>

  fun findAllByLibraryIdAndWithEmptyHashKoreader(libraryId: String): Collection<Book>

  fun findAllByHashKoreader(hashKoreader: String): Collection<Book>

  fun findAllByLibraryIdAndMediaTypes(
    libraryId: String,
    mediaTypes: Collection<String>,
  ): Collection<Book>

  fun findAllByLibraryIdAndMismatchedExtension(
    libraryId: String,
    mediaType: String,
    extension: String,
  ): Collection<Book>

  fun getLibraryIdOrNull(bookId: String): String?

  fun getSeriesIdOrNull(bookId: String): String?

  fun findFirstIdInSeriesOrNull(seriesId: String): String?

  fun findLastIdInSeriesOrNull(seriesId: String): String?

  fun findFirstUnreadIdInSeriesOrNull(
    seriesId: String,
    userId: String,
  ): String?

  fun findAllIdsBySeriesId(seriesId: String): Collection<String>

  fun findAllIdsByLibraryId(libraryId: String): Collection<String>

  fun existsById(bookId: String): Boolean

  fun insert(book: Book)

  fun insert(books: Collection<Book>)

  fun update(book: Book)

  fun update(books: Collection<Book>)

  fun delete(bookId: String)

  fun delete(bookIds: Collection<String>)

  fun deleteAll()

  fun count(): Long

  fun countGroupedByLibraryId(): Map<String, Int>

  fun getFilesizeGroupedByLibraryId(): Map<String, BigDecimal>
}
