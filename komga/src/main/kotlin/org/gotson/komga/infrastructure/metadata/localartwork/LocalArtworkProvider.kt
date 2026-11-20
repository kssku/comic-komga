package org.gotson.komga.infrastructure.metadata.localartwork

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.io.FilenameUtils
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.Sidecar
import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.ThumbnailSeries
import org.gotson.komga.infrastructure.image.ImageAnalyzer
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.gotson.komga.infrastructure.sidecar.SidecarBookConsumer
import org.gotson.komga.infrastructure.sidecar.SidecarSeriesConsumer
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream
import kotlin.io.path.nameWithoutExtension
import kotlin.streams.asSequence

private val logger = KotlinLogging.logger {}

/**
 * CUSTOM FORK: extensions tried, in order, for a bookId-sharded mirrored cover.
 * jpg comes first because the cover generator always writes jpg.
 */
private val shardedCoverExtensions = listOf("jpg", "jpeg", "png", "webp")

/**
 * CUSTOM FORK: length, in hex characters, of the content-addressed artwork key.
 * 16 hex = 64 bit: for the ~330k comics in the library the expected number of collisions
 * is about 3e-9, while keeping paths short.
 */
private const val ARTWORK_KEY_LENGTH = 16

private const val HEX_CHARS = "0123456789abcdef"

/**
 * CUSTOM FORK: extensions the Tika MIME repository does not know, mapped explicitly.
 *
 * `.tbn` is the sidecar thumbnail extension and is a JPEG in practice, but Tika has no
 * extension entry for it, so a name-only lookup returns `application/octet-stream` and the
 * cover would be silently dropped. Mapping it here keeps the lookup I/O-free (the point of
 * [isImageByName]) while matching what sniffing the content used to produce.
 */
private val mimeByExtension = mapOf("tbn" to "image/jpeg")

/**
 * CUSTOM FORK: dimension cache entry.
 *
 * Identifies a specific version of a sidecar file. A dimension measured from an earlier
 * version of the file must not be reused once the file is replaced, so the key carries
 * the file size and modification time as a cheap version stamp.
 */
private data class DimensionKey(
  val path: String,
  val size: Long,
  val lastModified: Long,
)

@Service
class LocalArtworkProvider(
  private val contentDetector: ContentDetector,
  private val imageAnalyzer: ImageAnalyzer,
  private val komgaProperties: KomgaProperties,
) : SidecarSeriesConsumer,
  SidecarBookConsumer {
  // CUSTOM FORK: gif dropped. Baseline behaviour never accepted it (content sniffing typed
  // it as a non-cover image), and animated gifs make poor covers. tbn is kept: it is a JPEG
  // thumbnail, handled by [mimeByExtension] below because Tika has no entry for it.
  val supportedExtensions = listOf("png", "jpeg", "jpg", "tbn", "webp")
  val supportedSeriesFiles = listOf("cover", "default", "folder", "poster", "series")

  /**
   * CUSTOM FORK: in-memory cache of measured image dimensions.
   *
   * On a remote/FUSE mount (CloudDrive2 over 115) reading a cover to measure it costs
   * real network traffic on every browse request. The key includes size and modification
   * time so a replaced cover is measured again. Bounded to avoid unbounded growth.
   */
  private val dimensionCache =
    Caffeine
      .newBuilder()
      .maximumSize(4096)
      .expireAfterAccess(6, TimeUnit.HOURS)
      .build<DimensionKey, Dimension>()

  /**
   * CUSTOM FORK: fail loudly at startup when the mirror is misconfigured.
   *
   * A mismatched hash base or a missing mirror root makes EVERY cover lookup miss, and the
   * only trace used to be a debug log per book. Surfacing it once at boot turns a silent
   * "no covers anywhere" into an obvious configuration error.
   */
  init {
    val mirrorRoot = komgaProperties.artworkMirrorRoot.trim()
    val hashBase = komgaProperties.artworkHashBase.trim()
    when {
      mirrorRoot.isEmpty() ->
        logger.info { "Artwork mirror disabled (komga.artwork-mirror-root is empty)" }
      !Files.isDirectory(Paths.get(mirrorRoot)) ->
        logger.warn { "Artwork mirror root does not exist: $mirrorRoot - every cover lookup will miss" }
      hashBase.isEmpty() ->
        logger.warn {
          "Artwork mirror root is set but komga.artwork-hash-base is empty: path-addressed lookups are disabled, " +
            "only legacy book-id shards are used"
        }
      else ->
        logger.info {
          "Artwork mirror enabled: root=$mirrorRoot hashBase=$hashBase mirrorOnly=" + komgaProperties.artworkMirrorOnly
        }
    }
  }

  fun getBookThumbnails(book: Book): List<ThumbnailBook> {
    logger.info { "Looking for local thumbnails for book: $book" }
    val bookPath = book.path
    val baseName = bookPath.nameWithoutExtension

    val regex = "${Regex.escape(baseName)}(-\\d+)?".toRegex(RegexOption.IGNORE_CASE)

    // CUSTOM FORK: mirror tree lookup, sharded by book id. Covers live outside the
    // library in <mirrorRoot>/<id[0..1]>/<id>.<ext>, so they are found without ever
    // touching the library layout (which does not exist on this 115 CD2 mount).
    val fromPathMirror = findMirroredBookThumbnailByPath(book.id, bookPath)?.let { listOf(it) } ?: emptyList()
    if (fromPathMirror.isNotEmpty()) return fromPathMirror

    val fromMirror = getMirroredBookThumbnails(book)
    if (fromMirror.isNotEmpty()) return fromMirror

    if (komgaProperties.artworkMirrorOnly) {
      logger.debug { "Artwork mirror only, skipping sidecar listing for book: " + book.id }
      return emptyList()
    }

    return Files.list(bookPath.parent).use { dirStream ->
      dirStream
        .asSequence()
        .filter { Files.isRegularFile(it) }
        .filter { regex.matches(it.nameWithoutExtension) }
        .filter { supportedExtensions.contains(it.extension.lowercase()) }
        // CUSTOM FORK: type by name, not by sniffing magic bytes. This filter runs for
        // every file in the folder on every browse request; opening each one to sniff
        // its content is what turned cover browsing into a network-traffic generator.
        .filter { isImageByName(it) }
        .mapIndexed { index, path ->
          logger.info { "Found file: $path" }
          ThumbnailBook(
            url = path.toUri().toURL(),
            type = ThumbnailBook.Type.SIDECAR,
            bookId = book.id,
            selected = index == 0,
            fileSize = path.fileSize(),
            mediaType = mediaTypeByName(path),
            dimension = getCachedDimension(path) ?: Dimension(0, 0),
          )
        }.toList()
    }
  }

  /**
   * CUSTOM FORK: look for a cover in the bookId-sharded mirror tree:
   * {mirrorRoot}/{bookId first 2 chars}/{bookId}.{ext}
   *
   * Covers live outside the library, sharded by the first two characters of the book id
   * (matching lrr's substr($id,0,2)); 115's per-folder file limit is respected this way
   * and the lookup never depends on the library layout. jpg is tried first because the
   * cover generator always writes jpg.
   *
   * Never lists the shard directory: only a handful of direct existence checks are made.
   * Returns an empty list when disabled, or when no valid cover is found; any failure
   * (missing root, missing shard, permissions, I/O) is logged at debug and swallowed.
   */
  private fun getMirroredBookThumbnails(book: Book): List<ThumbnailBook> {
    val hit = findMirroredBookThumbnail(book.id) ?: return emptyList()
    return listOf(hit)
  }

  /**
   * CUSTOM FORK: request-path lookup of the sharded mirrored cover for a single book id.
   *
   * Same lookup as [getMirroredBookThumbnails], but keyed only by the book id so it can
   * be called from BookLifecycle.getThumbnail() without loading the Book row. All I/O
   * here targets artworkMirrorRoot, which lives on a LOCAL host filesystem: no FUSE
   * syscall is ever issued against the library mount, so this is safe on the request path.
   *
   * Returns null when the mirror is disabled, the shard is missing, or no cover file is
   * present. Every failure is logged at debug and swallowed.
   */
  /**
   * CUSTOM FORK: mirror lookup keyed by the book PATH.
   *
   * key = sha256(<book path relative to komga.artwork-hash-base>)[:16]
   *
   * This is the project-wide identity of a comic: any tool that knows the archive path can
   * derive the cover location with no mapping table, and the key survives library rebuilds,
   * id-space changes and re-imports.
   */
  fun findMirroredBookThumbnailByPath(bookId: String, bookPath: Path): ThumbnailBook? {
    val key = artworkKeyFor(bookPath) ?: return null
    return lookupShardedCover(key, bookId)
  }

  /**
   * Legacy lookup keyed by the Komga book id. Kept for mirror trees that predate
   * [KomgaProperties.artworkHashBase]; new trees should be path-addressed.
   */
  fun findMirroredBookThumbnail(bookId: String): ThumbnailBook? = lookupShardedCover(bookId, bookId)

  fun artworkKeyFor(bookPath: Path): String? {
    val base = komgaProperties.artworkHashBase.trim().trimEnd('/')
    if (base.isEmpty()) return null

    val full = bookPath.toString()
    if (!full.startsWith("$base/")) {
      logger.debug { "Book path is outside artwork-hash-base, skipping: $full" }
      return null
    }
    val relative = full.substring(base.length + 1)

    val digest = MessageDigest.getInstance("SHA-256").digest(relative.toByteArray(StandardCharsets.UTF_8))
    val hex =
      buildString(digest.size * 2) {
        for (b in digest) {
          val v = b.toInt() and 0xFF
          append(HEX_CHARS[v ushr 4])
          append(HEX_CHARS[v and 0x0F])
        }
      }
    return hex.take(ARTWORK_KEY_LENGTH)
  }

  /**
   * CUSTOM FORK: write a generated cover into the mirror tree, addressed by the same
   * content key used for lookups.
   *
   * This replaces storing the bytes in THUMBNAIL_BOOK: the mirror file is the single source
   * of truth for covers, survives library rebuilds, and stays readable by any other tool
   * that can compute the same path hash.
   *
   * Returns the written path, an existing path, or null when the mirror is not configured,
   * not writable, or the book path is outside the hash base. The caller falls back to the
   * database row on null.
   */
  fun persistMirroredCover(bookPath: Path, bytes: ByteArray): Path? {
    val mirrorRoot = komgaProperties.artworkMirrorRoot.trim()
    if (mirrorRoot.isEmpty() || bytes.isEmpty()) return null
    val key = artworkKeyFor(bookPath) ?: return null

    return try {
      val shardDir = Paths.get(mirrorRoot).resolve(key.substring(0, 2))
      Files.createDirectories(shardDir)
      val target = shardDir.resolve("$key.jpg")
      if (Files.isRegularFile(target)) return target

      // Atomic: a half-written cover must never be served, and a reader may be probing
      // this exact path from another thread.
      val tmp = shardDir.resolve("$key.jpg.tmp")
      Files.write(tmp, bytes)
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      logger.info { "Persisted generated cover into the mirror tree: $target (" + bytes.size + " bytes)" }
      target
    } catch (e: Exception) {
      logger.warn(e) { "Could not persist generated cover into the mirror tree for key $key" }
      null
    }
  }

  private fun lookupShardedCover(key: String, bookId: String): ThumbnailBook? {
    val mirrorRoot = komgaProperties.artworkMirrorRoot.trim()
    if (mirrorRoot.isEmpty()) return null

    if (key.length < 2) {
      logger.debug { "Cover key too short for sharded mirror lookup, skipping: $key" }
      return null
    }
    val shard = key.substring(0, 2)

    return try {
      // CUSTOM FORK: resolve on the HOST default filesystem, not on book.path.fileSystem.
      // book.path lives on the CloudDrive2 FUSE mount, and that provider only knows its
      // own mount subtree: asking it for a path under a different mount (covers/) returns
      // a virtual path that isDirectory() rejects WITHOUT issuing any syscall, so the
      // mirror tree was never actually probed. Paths.get() uses the host fs for both.
      val shardDir = java.nio.file.Paths.get(mirrorRoot).resolve(shard)
      if (!Files.isDirectory(shardDir)) {
        logger.debug { "Sharded mirror directory not found, skipping: $shardDir" }
        return null
      }

      val hit =
        shardedCoverExtensions
          .asSequence()
          .map { shardDir.resolve("$key.$it") }
          .firstOrNull { Files.isRegularFile(it) && contentDetector.isImage(contentDetector.detectMediaType(it)) }

      if (hit == null) {
        logger.debug { "No sharded mirrored cover for book: $bookId" }
        return null
      }

      logger.debug { "Found sharded mirrored cover: $hit" }
      ThumbnailBook(
        url = hit.toUri().toURL(),
        type = ThumbnailBook.Type.SIDECAR,
        bookId = bookId,
        selected = true,
        fileSize = hit.fileSize(),
        mediaType = contentDetector.detectMediaType(hit),
        dimension = getCachedDimension(hit) ?: Dimension(0, 0),
      )
    } catch (e: Exception) {
      logger.debug(e) { "Sharded mirror lookup failed for book: $bookId" }
      null
    }
  }

  fun getSeriesThumbnails(series: Series): List<ThumbnailSeries> {
    if (series.oneshot) {
      logger.debug { "Disabled for oneshot series, skipping" }
      return emptyList()
    }

    if (komgaProperties.artworkMirrorOnly) {
      logger.debug { "Artwork mirror only, skipping sidecar listing for series: " + series.id }
      return emptyList()
    }

    logger.info { "Looking for local thumbnails for series: $series" }

    return Files.list(series.path).use { dirStream ->
      dirStream
        .asSequence()
        .filter { Files.isRegularFile(it) }
        .filter { supportedSeriesFiles.contains(it.nameWithoutExtension.lowercase()) }
        .filter { supportedExtensions.contains(it.extension.lowercase()) }
        .filter { isImageByName(it) }
        .mapIndexed { index, path ->
          logger.info { "Found file: $path" }
          ThumbnailSeries(
            url = path.toUri().toURL(),
            seriesId = series.id,
            selected = index == 0,
            type = ThumbnailSeries.Type.SIDECAR,
            fileSize = path.fileSize(),
            mediaType = mediaTypeByName(path),
            dimension = getCachedDimension(path) ?: Dimension(0, 0),
          )
        }.toList()
    }
  }

  /**
   * CUSTOM FORK: resolve an image media type from the file NAME only, with no I/O.
   *
   * [ContentDetector.detectMediaTypeByName] is an in-memory MIME-repository lookup that
   * never opens the file. It returns null for unknown extensions. Callers have already
   * restricted the extension to [supportedExtensions], so the fallback is normally
   * unreachable, but it must still yield a VALID media type: `image/${path.extension}`
   * degrades to the bogus `image/` when the extension is empty, which is not a valid
   * MIME type. `application/octet-stream` keeps the non-null String contract while
   * staying a syntactically valid type.
   */
  private fun mediaTypeByName(path: Path): String = imageMediaTypeByName(path) ?: "application/octet-stream"

  /**
   * CUSTOM FORK: image check by extension instead of content sniffing.
   * Unknown extensions are treated as non-images, matching what the extension allow-list
   * callers already guarantee.
   */
  private fun isImageByName(path: Path): Boolean {
    val mediaType = imageMediaTypeByName(path) ?: return false
    return contentDetector.isImage(mediaType)
  }

  /**
   * CUSTOM FORK: name-only media type, consulting [mimeByExtension] first so that
   * extensions Tika does not know (.tbn) still type correctly. Performs no I/O.
   */
  private fun imageMediaTypeByName(path: Path): String? =
    mimeByExtension[path.extension.lowercase()] ?: contentDetector.detectMediaTypeByName(path.fileName.toString())

  /**
   * CUSTOM FORK: measure a sidecar's dimension once per file version.
   *
   * The cache key includes size and last-modified time, both of which come from the
   * already-stat'ed path, so a cache hit performs no file read at all. A miss opens the
   * file exactly once, as before.
   */
  private fun getCachedDimension(path: Path): Dimension? {
    val key =
      try {
        DimensionKey(
          path = path.toString(),
          size = path.fileSize(),
          lastModified = path.getLastModifiedTime().toMillis(),
        )
      } catch (e: Exception) {
        // Can't stat the file: fall back to a direct read so behaviour is unchanged.
        logger.debug(e) { "Could not stat sidecar, measuring without cache: $path" }
        return imageAnalyzer.getDimension(path.inputStream())
      }

    dimensionCache.getIfPresent(key)?.let { return it }

    val dimension = imageAnalyzer.getDimension(path.inputStream())
    if (dimension != null) dimensionCache.put(key, dimension)
    return dimension
  }

  override fun getSidecarBookType(): Sidecar.Type = Sidecar.Type.ARTWORK

  override fun getSidecarBookPrefilter(): List<Regex> = supportedExtensions.map { ext -> ".*(-\\d+)?\\.$ext".toRegex(RegexOption.IGNORE_CASE) }

  override fun isSidecarBookMatch(
    basename: String,
    sidecar: String,
  ): Boolean = "${Regex.escape(basename)}(-\\d+)?".toRegex(RegexOption.IGNORE_CASE).matches(FilenameUtils.getBaseName(sidecar))

  override fun getSidecarSeriesType(): Sidecar.Type = Sidecar.Type.ARTWORK

  override fun getSidecarSeriesFilenames(): List<String> =
    supportedSeriesFiles.flatMap { filename ->
      supportedExtensions.map { ext -> "$filename.$ext" }
    }
}
