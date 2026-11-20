package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.BookAction
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.HistoricalEvent
import org.gotson.komga.domain.model.ImageConversionException
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.MarkSelectedPreference
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MediaExtensionEpub
import org.gotson.komga.domain.model.MediaNotReadyException
import org.gotson.komga.domain.model.MediaProfile
import org.gotson.komga.domain.model.NoThumbnailFoundException
import org.gotson.komga.domain.model.R2Progression
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.SearchCondition
import org.gotson.komga.domain.model.SearchContext
import org.gotson.komga.domain.model.SearchOperator
import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.TypedBytes
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookProjectionRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.HistoricalEventRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ReadListRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.ThumbnailBookRepository
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.gotson.komga.infrastructure.configuration.KomgaSettingsProvider
import org.gotson.komga.infrastructure.hash.Hasher
import org.gotson.komga.infrastructure.hash.KoreaderHasher
import org.gotson.komga.infrastructure.image.ImageConverter
import org.gotson.komga.infrastructure.image.ImageType
import org.gotson.komga.infrastructure.metadata.localartwork.LocalArtworkProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.net.URLDecoder
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isWritable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists
import kotlin.io.path.toPath
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

@Service
class BookLifecycle(
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val bookProjectionRepository: BookProjectionRepository,
  private val readProgressRepository: ReadProgressRepository,
  private val thumbnailBookRepository: ThumbnailBookRepository,
  private val readListRepository: ReadListRepository,
  private val libraryRepository: LibraryRepository,
  private val bookAnalyzer: BookAnalyzer,
  private val imageConverter: ImageConverter,
  private val eventPublisher: ApplicationEventPublisher,
  private val transactionTemplate: TransactionTemplate,
  private val hasher: Hasher,
  private val hasherKoreader: KoreaderHasher,
  private val historicalEventRepository: HistoricalEventRepository,
  private val komgaSettingsProvider: KomgaSettingsProvider,
  private val komgaProperties: KomgaProperties,
  private val localArtworkProvider: LocalArtworkProvider,
  @Qualifier("pdfImageType")
  private val pdfImageType: ImageType,
) {
  private val resizeTargetFormat = ImageType.JPEG

  // CUSTOM FORK: single-flight + negative cache for on-demand (lazy) thumbnail generation.
  // On a remote mount (CloudDrive2/115) opening an archive to extract a cover is a
  // multi-second I/O. A grid of covers fires N concurrent requests for the same
  // bookId before any of them persists a thumbnail, so every one of them pays the
  // full cost. In-flight requests now share one generation, and a recent failure is
  // remembered briefly so a broken book is not retried on every scroll.
  private val inflightThumbnailGenerations = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()

  private val thumbnailFailureCache =
    Caffeine
      .newBuilder()
      .maximumSize(8192)
      .expireAfterWrite(60, TimeUnit.SECONDS)
      .build<String, Long>()

  // CUSTOM FORK: single-flight + negative cache for on-demand analysis, mirroring the
  // thumbnail path above. Opening one book fires several requests (pages list, then
  // page images) before any of them persists MEDIA, and each analysis opens the whole
  // archive on a remote mount. Concurrent callers share one run; a recent failure is
  // remembered briefly so a broken book is not re-read on every request.
  private val inflightAnalyses = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

  private val analysisFailureCache =
    Caffeine
      .newBuilder()
      .maximumSize(8192)
      .expireAfterWrite(60, TimeUnit.SECONDS)
      .build<String, Long>()

  /**
   * CUSTOM FORK: analyze one book on demand, for the lazy (zero-I/O) scan path.
   *
   * A scan in this fork never analyzes a book, so its MEDIA row stays UNKNOWN and the
   * page list is not available. The first time a client actually opens the book we
   * analyze it once here, persist MEDIA + MEDIA_PAGE, and every later request is served
   * from the database without touching the archive.
   *
   * Returns true when the book ends up [Media.Status.READY].
   */
  fun analyzeOnDemand(book: Book): Boolean {
    if (!komgaProperties.analysis.onDemand) return false

    // Already analyzed and usable: nothing to do, no I/O.
    mediaRepository.findByIdOrNull(book.id)?.let { media ->
      if (media.status == Media.Status.READY && media.pages.isNotEmpty()) return true
      // OUTDATED / ERROR / UNSUPPORTED are decided states; do not hammer the archive.
      if (media.status == Media.Status.UNSUPPORTED) return false
    }

    if (analysisFailureCache.getIfPresent(book.id) != null) {
      logger.debug { "On-demand analysis recently failed for book ${book.id}, skipping retry" }
      return false
    }

    // Single-flight: the claim is made with putIfAbsent, NOT computeIfAbsent, because the
    // mapping function of computeIfAbsent runs under the ConcurrentHashMap bucket lock and
    // the work here is a multi-second archive read on a remote mount.
    val mine = CompletableFuture<Boolean>()
    val existing = inflightAnalyses.putIfAbsent(book.id, mine)
    if (existing == null) {
      try {
        val ok = tryAnalyzeAndPersist(book)
        if (!ok) analysisFailureCache.put(book.id, System.currentTimeMillis())
        mine.complete(ok)
      } catch (e: Throwable) {
        analysisFailureCache.put(book.id, System.currentTimeMillis())
        mine.completeExceptionally(e)
      } finally {
        // Only drop OUR entry: a newer request may have installed a fresh future.
        inflightAnalyses.remove(book.id, mine)
      }
    }

    val future = existing ?: mine
    return try {
      future.get(5, TimeUnit.MINUTES)
    } catch (e: Exception) {
      logger.warn(e) { "On-demand analysis failed for book ${book.id}" }
      false
    }
  }

  private fun tryAnalyzeAndPersist(book: Book): Boolean {
    logger.info { "On-demand analysis for book: $book" }
    // Dimensions are deliberately skipped: the page-list DTO tolerates null width/height,
    // and decoding every page on a remote mount is exactly the cost being avoided.
    val media = bookAnalyzer.analyze(book, analyzeDimensions = false)

    transactionTemplate.executeWithoutResult {
      mediaRepository.update(media)
    }

    eventPublisher.publishEvent(DomainEvent.BookUpdated(book))

    return if (media.status == Media.Status.READY) {
      logger.info { "On-demand analysis done for book: $book (${media.pageCount} pages)" }
      true
    } else {
      logger.warn { "On-demand analysis failed for book: $book (${media.status} / ${media.comment})" }
      false
    }
  }

  fun analyzeAndPersist(book: Book): Set<BookAction> {
    logger.info { "Analyze and persist book: $book" }
    val media = bookAnalyzer.analyze(book, libraryRepository.findById(book.libraryId).analyzeDimensions)

    transactionTemplate.executeWithoutResult {
      // if the number of pages has changed, delete all read progress for that book
      mediaRepository.findById(book.id).let { previous ->
        if (previous.status == Media.Status.OUTDATED && previous.pageCount != media.pageCount) {
          val adjustedProgress =
            readProgressRepository
              .findAllByBookId(book.id)
              .map { it.copy(page = if (it.completed) media.pageCount else 1) }
          if (adjustedProgress.isNotEmpty()) {
            logger.info { "Number of pages differ, adjust read progress for book" }
            readProgressRepository.save(adjustedProgress)
          }
        }
      }

      mediaRepository.update(media)
    }

    eventPublisher.publishEvent(DomainEvent.BookUpdated(book))

    return if (media.status == Media.Status.READY) {
      // CUSTOM FORK: in lazy thumbnail mode, skip eager thumbnail generation during scan.
      // Thumbnails are then produced on demand (when the book is first opened).
      if (komgaProperties.thumbnailMode == KomgaProperties.ThumbnailMode.LAZY) {
        logger.debug { "Thumbnail mode is lazy, skipping thumbnail generation during analysis for book: $book" }
        setOf(BookAction.REFRESH_METADATA)
      } else {
        setOf(BookAction.GENERATE_THUMBNAIL, BookAction.REFRESH_METADATA)
      }
    } else {
      emptySet()
    }
  }

  fun hashAndPersist(book: Book) {
    val library = libraryRepository.findById(book.libraryId)
    if (!library.hashFiles)
      return logger.info { "File hashing is disabled for the library, it may have changed since the task was submitted, skipping" }

    logger.info { "Hash and persist book: $book" }
    if (book.fileHash.isBlank()) {
      // CUSTOM FORK (feat/sha1-relpath-hash): path-relative hash, no file I/O.
      val hash = hasher.computePathHash(book.path, library.root)
      bookRepository.update(book.copy(fileHash = hash))
    } else {
      logger.info { "Book already has a hash, skipping" }
    }
  }

  fun hashKoreaderAndPersist(book: Book) {
    if (!libraryRepository.findById(book.libraryId).hashKoreader)
      return logger.info { "File hashing for Koreader is disabled for the library, it may have changed since the task was submitted, skipping" }

    logger.info { "Hash Koreader and persist book: $book" }
    if (book.fileHashKoreader.isBlank()) {
      val hash = hasherKoreader.computeHash(book.path)
      bookRepository.update(book.copy(fileHashKoreader = hash))
    } else {
      logger.info { "Book already has a Koreader hash, skipping" }
    }
  }

  fun hashPagesAndPersist(book: Book) {
    if (!libraryRepository.findById(book.libraryId).hashPages)
      return logger.info { "Page hashing is disabled for the library, it may have changed since the task was submitted, skipping" }

    logger.info { "Hash and persist pages for book: $book" }

    mediaRepository.update(bookAnalyzer.hashPages(BookWithMedia(book, mediaRepository.findById(book.id))))
  }

  fun generateThumbnailAndPersist(book: Book) {
    logger.info { "Generate thumbnail and persist for book: $book" }
    try {
      addThumbnailForBook(bookAnalyzer.generateThumbnail(BookWithMedia(book, mediaRepository.findById(book.id))), MarkSelectedPreference.IF_NONE_OR_GENERATED)
    } catch (ex: NoThumbnailFoundException) {
      logger.error { "Error while creating thumbnail" }
    } catch (ex: Exception) {
      logger.error(ex) { "Error while creating thumbnail" }
    }
  }

  fun addThumbnailForBook(
    thumbnail: ThumbnailBook,
    markSelected: MarkSelectedPreference,
  ): ThumbnailBook {
    when (thumbnail.type) {
      ThumbnailBook.Type.GENERATED -> {
        // only one generated thumbnail is allowed
        thumbnailBookRepository.deleteByBookIdAndType(thumbnail.bookId, ThumbnailBook.Type.GENERATED)
        thumbnailBookRepository.insert(thumbnail.copy(selected = false))
      }

      ThumbnailBook.Type.SIDECAR -> {
        // delete existing thumbnail with the same url
        thumbnailBookRepository
          .findAllByBookIdAndType(thumbnail.bookId, setOf(ThumbnailBook.Type.SIDECAR))
          .filter { it.url == thumbnail.url }
          .forEach {
            thumbnailBookRepository.delete(it.id)
          }
        thumbnailBookRepository.insert(thumbnail.copy(selected = false))
      }

      ThumbnailBook.Type.USER_UPLOADED -> {
        thumbnailBookRepository.insert(thumbnail.copy(selected = false))
      }
    }

    val selected =
      when (markSelected) {
        MarkSelectedPreference.YES -> true
        MarkSelectedPreference.IF_NONE_OR_GENERATED -> {
          val selectedThumbnail = thumbnailBookRepository.findSelectedByBookIdOrNull(thumbnail.bookId)
          selectedThumbnail == null || selectedThumbnail.type == ThumbnailBook.Type.GENERATED
        }

        MarkSelectedPreference.NO -> false
      }

    if (selected)
      thumbnailBookRepository.markSelected(thumbnail)
    else
      thumbnailsHouseKeeping(thumbnail.bookId)

    val newThumbnail = thumbnail.copy(selected = selected)
    eventPublisher.publishEvent(DomainEvent.ThumbnailBookAdded(newThumbnail))
    return newThumbnail
  }

  fun deleteThumbnailForBook(thumbnail: ThumbnailBook) {
    require(thumbnail.type == ThumbnailBook.Type.USER_UPLOADED) { "Only uploaded thumbnails can be deleted" }
    thumbnailBookRepository.delete(thumbnail.id)
    thumbnailsHouseKeeping(thumbnail.bookId)
    eventPublisher.publishEvent(DomainEvent.ThumbnailBookDeleted(thumbnail))
  }

  /**
   * CUSTOM FORK: request-path thumbnail lookup that NEVER touches the library mount.
   *
   * On CloudDrive2/115 every stat against book.path is a remote round-trip, and the
   * original implementation stat'ed the selected thumbnail (ThumbnailBook.exists())
   * and then fell into thumbnailsHouseKeeping(), which stat'ed again. With
   * thumbnail.generate = false and an empty THUMBNAIL_BOOK table, that meant one
   * unconditional FUSE stat per cover request, hammering the mount from every Tomcat
   * thread. Order is now:
   *
   *  1. selected row in THUMBNAIL_BOOK, returned as-is with NO exists() probe;
   *  2. bookId-sharded cover under artworkMirrorRoot (a LOCAL host directory,
   *     see LocalArtworkProvider.findMirroredBookThumbnail);
   *  3. null, letting the client render its placeholder.
   *
   * thumbnailsHouseKeeping() is deliberately NOT called here: its per-row exists()
   * belongs to the low-frequency write paths (add/delete), not to every GET.
   */
  fun getThumbnail(bookId: String): ThumbnailBook? {
    thumbnailBookRepository.findSelectedByBookIdOrNull(bookId)?.let { return it }

    // CUSTOM FORK: content-addressed mirror lookup (key = sha256 of the book path relative
    // to komga.artwork-hash-base). I/O is confined to artworkMirrorRoot, which lives outside
    // the FUSE content subtree, so this costs no remote traffic.
    bookRepository.findByIdOrNull(bookId)?.let { book ->
      localArtworkProvider.findMirroredBookThumbnailByPath(book.id, book.path)?.let { return it }
    }

    // Legacy id-sharded mirror, for trees written before artworkHashBase existed.
    localArtworkProvider.findMirroredBookThumbnail(bookId)?.let { return it }

    return null
  }

  fun getThumbnailBytes(
    bookId: String,
    resizeTo: Int? = null,
  ): TypedBytes? {
    readThumbnailBytes(bookId, resizeTo)?.let { return it }

    // CUSTOM FORK: in lazy thumbnail mode, thumbnails are not generated during scan.
    // Generate on demand the first time a missing thumbnail is requested.
    if (komgaProperties.thumbnailMode == KomgaProperties.ThumbnailMode.LAZY) {
      // CUSTOM FORK: generation is disabled by default, so a missing thumbnail never
      // opens the archive. On a metered/remote mount (CloudDrive2/115) browsing covers
      // must not cost traffic. Reading the book itself is unaffected.
      if (!komgaProperties.thumbnail.generate) {
        logger.debug { "Thumbnail generation is disabled, not generating thumbnail for book $bookId" }
        return null
      }

      if (thumbnailFailureCache.getIfPresent(bookId) != null) {
        logger.debug { "Thumbnail generation recently failed for book $bookId, skipping retry" }
        return null
      }

      // Single-flight: concurrent requests for the same book share one generation.
      //
      // The claim is made with putIfAbsent, NOT computeIfAbsent: the mapping function of
      // computeIfAbsent runs while holding the ConcurrentHashMap bucket lock, and the work
      // here is a multi-second archive read on a remote mount. Holding the bucket lock
      // would serialize unrelated books that happen to share a bucket. The winner of
      // putIfAbsent does the I/O outside any lock; losers just await the same future.
      // The future carries the RAW generated bytes, not a resized result: callers with
      // different resizeTo values share one generation and resize their own copy.
      val mine = CompletableFuture<ByteArray?>()
      val existing = inflightThumbnailGenerations.putIfAbsent(bookId, mine)
      if (existing == null) {
        try {
          val generated = tryGenerateThumbnail(bookId)
          if (generated == null) thumbnailFailureCache.put(bookId, System.currentTimeMillis())
          mine.complete(generated)
        } catch (e: Throwable) {
          thumbnailFailureCache.put(bookId, System.currentTimeMillis())
          mine.completeExceptionally(e)
        } finally {
          // Only drop OUR entry. If a waiter timed out and a newer request installed a
          // fresh future, an unconditional remove would evict that newer entry and let a
          // third request start a second full generation.
          inflightThumbnailGenerations.remove(bookId, mine)
        }
      }

      val future = existing ?: mine
      val raw =
        try {
          future.get(5, TimeUnit.MINUTES)
        } catch (e: Exception) {
          logger.warn(e) { "On-demand thumbnail generation failed for book $bookId" }
          null
        } ?: return null

      if (resizeTo == null) return TypedBytes(raw, resizeTargetFormat.mediaType)

      return try {
        TypedBytes(
          imageConverter.resizeImageToByteArray(raw, resizeTargetFormat, resizeTo),
          resizeTargetFormat.mediaType,
        )
      } catch (e: Exception) {
        logger.error(e) { "Resize on-demand thumbnail of book $bookId to $resizeTo: failed" }
        null
      }
    }

    return null
  }

  /**
   * CUSTOM FORK: run the actual on-demand generation and return the bytes, or null.
   * Kept separate so the single-flight wrapper stays readable.
   */
  private fun tryGenerateThumbnail(bookId: String): ByteArray? {
    val book = bookRepository.findByIdOrNull(bookId) ?: return null
    logger.info { "Thumbnail missing for book $bookId, generating on demand (lazy mode)" }
    generateThumbnailAndPersist(book)
    // Read back the raw stored bytes; resizing happens per caller, outside the shared future.
    return getThumbnail(bookId)?.let { thumbnail ->
      when {
        thumbnail.thumbnail != null -> thumbnail.thumbnail
        thumbnail.url != null -> File(thumbnail.url.toURI()).readBytes()
        else -> null
      }
    }
  }

  private fun readThumbnailBytes(
    bookId: String,
    resizeTo: Int?,
  ): TypedBytes? {
    getThumbnail(bookId)?.let {
      val thumbnailBytes =
        when {
          it.thumbnail != null -> it.thumbnail
          it.url != null -> File(it.url.toURI()).readBytes()
          else -> return null
        }

      if (resizeTo != null) {
        try {
          return TypedBytes(
            imageConverter.resizeImageToByteArray(thumbnailBytes, resizeTargetFormat, resizeTo),
            resizeTargetFormat.mediaType,
          )
        } catch (e: Exception) {
          logger.error(e) { "Resize thumbnail of book $bookId to $resizeTo: failed" }
        }
      }

      return TypedBytes(thumbnailBytes, it.mediaType)
    }
    return null
  }

  fun getThumbnailBytesOriginal(bookId: String): TypedBytes? {
    val thumbnail = getThumbnail(bookId) ?: return null
    return if (thumbnail.type == ThumbnailBook.Type.GENERATED) {
      val book = bookRepository.findByIdOrNull(bookId) ?: return null
      val media = mediaRepository.findById(book.id)
      bookAnalyzer.getPoster(BookWithMedia(book, media))
    } else {
      getThumbnailBytes(bookId)
    }
  }

  fun getThumbnailBytesByThumbnailId(thumbnailId: String): TypedBytes? =
    thumbnailBookRepository.findByIdOrNull(thumbnailId)?.let { thumbnail ->
      getBytesFromThumbnailBook(thumbnail)?.let { bytes ->
        TypedBytes(bytes, thumbnail.mediaType)
      }
    }

  private fun getBytesFromThumbnailBook(thumbnail: ThumbnailBook): ByteArray? =
    when {
      thumbnail.thumbnail != null -> thumbnail.thumbnail
      thumbnail.url != null -> File(thumbnail.url.toURI()).readBytes()
      else -> null
    }

  private fun thumbnailsHouseKeeping(bookId: String) {
    logger.info { "House keeping thumbnails for book: $bookId" }
    val all =
      thumbnailBookRepository
        .findAllByBookId(bookId)
        .mapNotNull {
          if (!it.exists()) {
            logger.warn { "Thumbnail doesn't exist, removing entry" }
            thumbnailBookRepository.delete(it.id)
            null
          } else {
            it
          }
        }

    val selected = all.filter { it.selected }
    when {
      selected.size > 1 -> {
        logger.info { "More than one thumbnail is selected, removing extra ones" }
        thumbnailBookRepository.markSelected(selected[0])
      }

      selected.isEmpty() && all.isNotEmpty() -> {
        logger.info { "Book has no selected thumbnail, choosing one automatically" }
        thumbnailBookRepository.markSelected(all.first())
      }
    }
  }

  fun findBookThumbnailsToRegenerate(forBiggerResultOnly: Boolean): Collection<String> =
    if (forBiggerResultOnly) {
      thumbnailBookRepository.findAllBookIdsByThumbnailTypeAndDimensionSmallerThan(ThumbnailBook.Type.GENERATED, komgaSettingsProvider.thumbnailSize.maxEdge)
    } else {
      bookRepository.findAll(SearchCondition.Deleted(SearchOperator.IsFalse), SearchContext.empty(), Pageable.unpaged()).content.map { it.id }
    }

  @Throws(
    ImageConversionException::class,
    MediaNotReadyException::class,
    IndexOutOfBoundsException::class,
  )
  fun getBookPage(
    book: Book,
    number: Int,
    convertTo: ImageType? = null,
    resizeTo: Int? = null,
  ): TypedBytes {
    val media = mediaRepository.findById(book.id)
    val pageContent = bookAnalyzer.getPageContent(BookWithMedia(book, media), number)
    val pageMediaType =
      if (media.profile == MediaProfile.PDF)
        pdfImageType.mediaType
      else
        media.pages[number - 1].mediaType

    if (resizeTo != null) {
      val convertedPage =
        try {
          imageConverter.resizeImageToByteArray(pageContent, resizeTargetFormat, resizeTo)
        } catch (e: Exception) {
          logger.error(e) { "Resize page #$number of book $book to $resizeTo: failed" }
          throw e
        }
      return TypedBytes(convertedPage, resizeTargetFormat.mediaType)
    } else {
      convertTo?.let {
        val msg = "Convert page #$number of book $book from $pageMediaType to ${it.mediaType}"
        if (!imageConverter.supportedReadMediaTypes.contains(pageMediaType)) {
          throw ImageConversionException("$msg: unsupported read format $pageMediaType")
        }
        if (!imageConverter.supportedWriteMediaTypes.contains(it.mediaType)) {
          throw ImageConversionException("$msg: unsupported write format ${it.mediaType}")
        }
        if (pageMediaType == it.mediaType) {
          logger.warn { "$msg: same format, no need for conversion" }
          return@let
        }

        logger.info { msg }
        val convertedPage =
          try {
            imageConverter.convertImage(pageContent, it.imageIOFormat)
          } catch (e: Exception) {
            logger.error(e) { "$msg: conversion failed" }
            throw e
          }
        return TypedBytes(convertedPage, it.mediaType)
      }

      return TypedBytes(pageContent, pageMediaType)
    }
  }

  fun deleteOne(book: Book) {
    logger.info { "Delete book id: ${book.id}" }

    transactionTemplate.executeWithoutResult {
      readProgressRepository.deleteByBookId(book.id)
      readListRepository.removeBookFromAll(book.id)

      mediaRepository.delete(book.id)
      thumbnailBookRepository.deleteByBookId(book.id)
      bookMetadataRepository.delete(book.id)
      bookProjectionRepository.delete(book.id)

      bookRepository.delete(book.id)
    }

    eventPublisher.publishEvent(DomainEvent.BookDeleted(book))
  }

  fun softDeleteMany(books: Collection<Book>) {
    logger.info { "Soft delete books: $books" }
    val deletedDate = LocalDateTime.now()
    bookRepository.update(books.map { it.copy(deletedDate = deletedDate) })

    books.forEach { eventPublisher.publishEvent(DomainEvent.BookUpdated(it)) }
  }

  fun deleteMany(books: Collection<Book>) {
    val bookIds = books.map { it.id }
    logger.info { "Delete book ids: $bookIds" }

    transactionTemplate.executeWithoutResult {
      readProgressRepository.deleteByBookIds(bookIds)
      readListRepository.removeBooksFromAll(bookIds)

      mediaRepository.delete(bookIds)
      thumbnailBookRepository.deleteByBookIds(bookIds)
      bookMetadataRepository.delete(bookIds)
      bookProjectionRepository.delete(bookIds)

      bookRepository.delete(bookIds)
    }

    books.forEach { eventPublisher.publishEvent(DomainEvent.BookDeleted(it)) }
  }

  fun markReadProgress(
    book: Book,
    user: KomgaUser,
    page: Int,
  ) {
    val media = mediaRepository.findById(book.id)
    require(page in 1..media.pageCount) { "Page argument ($page) must be within 1 and book page count (${media.pageCount})" }

    val locator =
      if (media.profile == MediaProfile.EPUB) {
        require(media.epubDivinaCompatible) { "epub book is not Divina compatible" }

        val extension =
          mediaRepository.findExtensionByIdOrNull(book.id) as? MediaExtensionEpub
            ?: throw IllegalArgumentException("Epub extension not found")
              .also { logger.error { "Epub extension not found for book ${book.id}. Book should be re-analyzed." } }
        extension.positions[page - 1]
      } else {
        null
      }

    val progress = ReadProgress(book.id, user.id, page, page == media.pageCount, locator = locator)

    readProgressRepository.save(progress)
    eventPublisher.publishEvent(DomainEvent.ReadProgressChanged(progress))
  }

  fun markReadProgressCompleted(
    bookId: String,
    user: KomgaUser,
  ) {
    val media = mediaRepository.findById(bookId)

    val progress = ReadProgress(bookId, user.id, media.pageCount, true)
    readProgressRepository.save(progress)
    eventPublisher.publishEvent(DomainEvent.ReadProgressChanged(progress))
  }

  fun deleteReadProgress(
    book: Book,
    user: KomgaUser,
  ) {
    readProgressRepository.findByBookIdAndUserIdOrNull(book.id, user.id)?.let { progress ->
      readProgressRepository.delete(book.id, user.id)
      eventPublisher.publishEvent(DomainEvent.ReadProgressDeleted(progress))
    }
  }

  fun markProgression(
    book: Book,
    user: KomgaUser,
    newProgression: R2Progression,
  ) {
    readProgressRepository.findByBookIdAndUserIdOrNull(book.id, user.id)?.let { savedProgress ->
      check(
        newProgression.modified
          .withZoneSameInstant(ZoneId.systemDefault())
          .toLocalDateTime()
          .isAfter(savedProgress.readDate),
      ) { "Progression is older than existing" }
    }

    val media = mediaRepository.findById(book.id)
    requireNotNull(media.profile) { "Media has no profile" }
    val progress =
      when (media.profile!!) {
        MediaProfile.DIVINA,
        MediaProfile.PDF,
        -> {
          require(newProgression.locator.locations?.position in 1..media.pageCount) { "Page argument (${newProgression.locator.locations?.position}) must be within 1 and book page count (${media.pageCount})" }
          ReadProgress(
            book.id,
            user.id,
            newProgression.locator.locations!!.position!!,
            newProgression.locator.locations.position == media.pageCount,
            newProgression.modified.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(),
            newProgression.device.id,
            newProgression.device.name,
            newProgression.locator,
          )
        }

        MediaProfile.EPUB -> {
          val href =
            newProgression.locator.href
              .replaceAfter("#", "")
              .removeSuffix("#")
              .let { URLDecoder.decode(it, Charsets.UTF_8) }
          require(href in media.files.map { it.fileName }) { "Resource does not exist in book: $href" }
          requireNotNull(newProgression.locator.locations?.progression) { "location.progression is required" }

          val extension =
            mediaRepository.findExtensionByIdOrNull(book.id) as? MediaExtensionEpub
              ?: throw IllegalArgumentException("Epub extension not found")
                .also { logger.error { "Epub extension not found for book ${book.id}. Book should be re-analyzed." } }
          // match progression with positions
          val matchingPositions = extension.positions.filter { it.href == href }
          val matchedPosition =
            if (extension.isFixedLayout && matchingPositions.size == 1)
              matchingPositions.first()
            else
              matchingPositions.firstOrNull { it.locations!!.progression == newProgression.locator.locations!!.progression }
                ?: run {
                  // no exact match
                  val before = matchingPositions.filter { it.locations!!.progression!! < newProgression.locator.locations!!.progression!! }.maxByOrNull { it.locations!!.position!! }
                  val after = matchingPositions.filter { it.locations!!.progression!! > newProgression.locator.locations!!.progression!! }.minByOrNull { it.locations!!.position!! }
                  if (before == null || after == null || before.locations!!.position!! > after.locations!!.position!!)
                    throw IllegalArgumentException("Invalid progression")
                  before
                }

          val totalProgression = matchedPosition.locations?.totalProgression
          ReadProgress(
            book.id,
            user.id,
            totalProgression?.let { (media.pageCount * it).roundToInt() } ?: 0,
            totalProgression?.let { it >= 0.99F } ?: false,
            newProgression.modified.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(),
            newProgression.device.id,
            newProgression.device.name,
            newProgression.locator.copy(
              // use the type we have instead of the one provided
              type = matchedPosition.type,
              // if no koboSpan is provided, use the one we matched
              koboSpan = newProgression.locator.koboSpan ?: matchedPosition.koboSpan,
              // don't trust the provided total progression, the one from Kobo can be wrong
              locations = newProgression.locator.locations?.copy(totalProgression = totalProgression),
            ),
          )
        }
      }

    readProgressRepository.save(progress)
    eventPublisher.publishEvent(DomainEvent.ReadProgressChanged(progress))
  }

  fun deleteBookFiles(book: Book) {
    if (book.path.notExists()) return logger.info { "Cannot delete book file, path does not exist: ${book.path}" }
    if (!book.path.isWritable()) return logger.info { "Cannot delete book file, path is not writable: ${book.path}" }

    val thumbnails =
      thumbnailBookRepository
        .findAllByBookIdAndType(book.id, setOf(ThumbnailBook.Type.SIDECAR))
        .mapNotNull { it.url?.toURI()?.toPath() }
        .filter { it.exists() && it.isWritable() }

    if (book.path.deleteIfExists()) {
      logger.info { "Deleted file: ${book.path}" }
      historicalEventRepository.insert(HistoricalEvent.BookFileDeleted(book, "File was deleted by user request"))
    }
    thumbnails.forEach {
      if (it.deleteIfExists()) logger.info { "Deleted file: $it" }
    }

    if (book.path.parent
        .listDirectoryEntries()
        .isEmpty()
    )
      if (book.path.parent.deleteIfExists()) {
        logger.info { "Deleted directory: ${book.path.parent}" }
        historicalEventRepository.insert(HistoricalEvent.SeriesFolderDeleted(book.seriesId, book.path.parent, "Folder was deleted because it was empty"))
      }

    softDeleteMany(listOf(book))
  }
}
