package org.gotson.komga.domain.service

import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DirectoryNotFoundException
import org.gotson.komga.domain.model.ScanResult
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.Sidecar
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.gotson.komga.infrastructure.hash.Hasher
import org.gotson.komga.infrastructure.sidecar.SidecarBookConsumer
import org.gotson.komga.infrastructure.sidecar.SidecarSeriesConsumer
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString
import kotlin.io.path.readAttributes
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

@Service
class FileSystemScanner(
  private val sidecarBookConsumers: List<SidecarBookConsumer>,
  private val sidecarSeriesConsumers: List<SidecarSeriesConsumer>,
  private val hasher: Hasher,
  private val komgaProperties: KomgaProperties,
) {
  private data class TempSidecar(
    val name: String,
    val url: URL,
    val lastModifiedTime: LocalDateTime,
    val type: Sidecar.Type? = null,
  )

  private val sidecarBookPrefilter = sidecarBookConsumers.flatMap { it.getSidecarBookPrefilter() }

  fun scanRootFolder(
    root: Path,
    forceDirectoryModifiedTime: Boolean = false,
    oneshotsDir: String? = null,
    scanCbx: Boolean = true,
    scanPdf: Boolean = true,
    scanEpub: Boolean = true,
    directoryExclusions: Set<String> = emptySet(),
  ): ScanResult {
    val scanForExtensions =
      buildList {
        if (scanCbx) addAll(listOf("cbz", "zip", "cbr", "rar"))
        if (scanPdf) add("pdf")
        if (scanEpub) add("epub")
      }
    logger.info { "Scanning folder: $root" }
    logger.info { "Scan for extensions: $scanForExtensions" }
    logger.info { "Excluded directory patterns: $directoryExclusions" }
    logger.info { "Force directory modified time: $forceDirectoryModifiedTime" }

    // CUSTOM FORK: book IDs are SHA1 of the path relative to the library root's
    // PARENT, matching LANraragi. The library root is needed at book-construction
    // time, so it is captured here and threaded down to pathToBook().
    val libraryRootUrl = root.toUri().toURL()

    // CUSTOM FORK (LRR path mode): when enabled, a book's series is the <seriesId>
    // segment of <seriesDir>/<bucket>/<seriesId>/<volume>, not its parent directory.
    // Books accumulate here across buckets and are emitted after the walk.
    val lrrPathMode = komgaProperties.lrrPathMode
    val lrrEnabled = lrrPathMode.enabled
    val lrrBooks = linkedMapOf<String, MutableList<Book>>()
    val lrrSeriesByKey = linkedMapOf<String, Series>()
    // CUSTOM FORK (LRR path mode): archives under <oneshotDir> are single-volume works.
    // LRR keeps them strictly apart from series; Komga already models that as a one-book
    // series with Series.oneshot = true, which the UI marks, filters and redirects on.
    val lrrOneshotBooks = mutableListOf<Book>()

    if (!(Files.isDirectory(root) && Files.isReadable(root)))
      throw DirectoryNotFoundException("Folder is not accessible: $root", "ERR_1016")

    val scannedSeries = mutableMapOf<Series, List<Book>>()
    val scannedSidecars = mutableListOf<Sidecar>()

    val scanDuration = measureTime {
      // path is the series directory
      val pathToSeries = mutableMapOf<Path, Series>()
      val pathToSeriesSidecars = mutableMapOf<Path, MutableList<Sidecar>>()
      // path is the book's parent directory, ie the series directory
      val pathToBooks = mutableMapOf<Path, MutableList<Book>>()
      val pathToBookSidecars = mutableMapOf<Path, MutableList<TempSidecar>>()

      Files.walkFileTree(
        root,
        setOf(FileVisitOption.FOLLOW_LINKS),
        Integer.MAX_VALUE,
        object : FileVisitor<Path> {
          override fun preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes,
          ): FileVisitResult {
            logger.trace { "preVisit: $dir (regularFile:${attrs.isRegularFile}, directory:${attrs.isDirectory}, symbolicLink:${attrs.isSymbolicLink}, other:${attrs.isOther})" }
            if (dir.name.startsWith(".") ||
              directoryExclusions.any { exclude ->
                dir.pathString.contains(exclude, true)
              }
            )
              return FileVisitResult.SKIP_SUBTREE

            pathToSeries[dir] =
              Series(
                name = dir.name.ifBlank { dir.pathString },
                url = directoryUrlFromPath(dir),
                fileLastModified = attrs.getUpdatedTime(),
              )

            return FileVisitResult.CONTINUE
          }

          override fun visitFile(
            file: Path,
            attrs: BasicFileAttributes,
          ): FileVisitResult {
            logger.trace { "visitFile: $file (regularFile:${attrs.isRegularFile}, directory:${attrs.isDirectory}, symbolicLink:${attrs.isSymbolicLink}, other:${attrs.isOther})" }
            if (!attrs.isSymbolicLink && !attrs.isDirectory) {
              if (scanForExtensions.contains(file.extension.lowercase()) &&
                !file.name.startsWith(".")
              ) {
                val book = pathToBook(file, attrs, libraryRootUrl)
                val lrrSeries =
                  if (lrrEnabled) lrrSeriesFor(file, root, lrrPathMode.seriesDir, lrrPathMode.oneshotDir) else null
                when {
                  // CUSTOM FORK (LRR path mode): a book under <oneshotDir> is its own
                  // single-volume work, never a volume of a series. It is emitted below as
                  // a one-book series with oneshot = true, which is what the UI keys on.
                  lrrEnabled && lrrIsOneshotFile(file, root, lrrPathMode.oneshotDir) -> {
                    lrrOneshotBooks.add(book.copy(oneshot = true))
                  }

                  lrrSeries != null -> {
                    // CUSTOM FORK (LRR path mode): collect volumes of one <seriesId> across
                    // bucket directories, numbered from the archive name so they sort by
                    // volume rather than by ingest order.
                    val (seriesId, seriesDir) = lrrSeries
                    val numberedBook = book.copy(number = parseVolumeNumber(file.nameWithoutExtension))
                    lrrBooks.getOrPut(seriesId) { mutableListOf() }.add(numberedBook)
                    // CUSTOM FORK (LRR path mode): the series URL is its identity (Komga
                    // matches scanned series by URL). Files arrive in walk order, which on a
                    // FUSE mount is not deterministic, so always keep the lexicographically
                    // smallest bucket directory instead of the first one seen - otherwise a
                    // rescan creates a second series with the same name, leaving the old one
                    // without books.
                    val candidate =
                      Series(
                        name = seriesId,
                        url = directoryUrlFromPath(seriesDir),
                        fileLastModified = attrs.getUpdatedTime(),
                      )
                    lrrSeriesByKey.merge(seriesId, candidate) { current, _ ->
                      if (current.url.toString() <= candidate.url.toString()) current else candidate
                    }
                  }

                  else -> {
                    file.parent.let { key ->
                      pathToBooks.merge(key, mutableListOf(book)) { prev, one -> prev.union(one).toMutableList() }
                    }
                  }
                }
              }

              sidecarSeriesConsumers
                .firstOrNull { consumer ->
                  consumer.getSidecarSeriesFilenames().any { file.name.equals(it, ignoreCase = true) }
                }?.let {
                  val sidecar = Sidecar(file.toUri().toURL(), file.parent.toUri().toURL(), attrs.getUpdatedTime(), it.getSidecarSeriesType(), Sidecar.Source.SERIES)
                  pathToSeriesSidecars.merge(file.parent, mutableListOf(sidecar)) { prev, one -> prev.union(one).toMutableList() }
                }

              // book sidecars can't be exactly matched during a file visit
              // this prefilters files to reduce the candidates
              if (sidecarBookPrefilter.any { it.matches(file.name) }) {
                val sidecar = TempSidecar(file.name, file.toUri().toURL(), attrs.getUpdatedTime())
                pathToBookSidecars.merge(file.parent, mutableListOf(sidecar)) { prev, one -> prev.union(one).toMutableList() }
              }
            }

            return FileVisitResult.CONTINUE
          }

          override fun visitFileFailed(
            file: Path?,
            exc: IOException?,
          ): FileVisitResult {
            logger.warn { "Could not access: $file" }
            return FileVisitResult.SKIP_SUBTREE
          }

          override fun postVisitDirectory(
            dir: Path,
            exc: IOException?,
          ): FileVisitResult {
            logger.trace { "postVisit: $dir" }
            val books = pathToBooks[dir]
            val tempSeries = pathToSeries[dir]
            if (!books.isNullOrEmpty() && tempSeries !== null) {
              if (!oneshotsDir.isNullOrBlank() && dir.pathString.contains(oneshotsDir, true)) {
                books.forEach { book ->
                  val series =
                    Series(
                      name = book.name,
                      url = book.url,
                      fileLastModified = book.fileLastModified,
                      oneshot = true,
                    )
                  scannedSeries[series] = listOf(book.copy(oneshot = true))
                }
              } else {
                val series =
                  if (forceDirectoryModifiedTime)
                    tempSeries.copy(fileLastModified = maxOf(tempSeries.fileLastModified, books.maxOf { it.fileLastModified }))
                  else
                    tempSeries

                scannedSeries[series] = books

                // only add series sidecars if series has books
                pathToSeriesSidecars[dir]?.let { scannedSidecars.addAll(it) }
              }

              // book sidecars are matched here, with the actual list of books
              books.forEach { book ->
                val sidecars =
                  pathToBookSidecars[dir]
                    ?.mapNotNull { sidecar ->
                      sidecarBookConsumers.firstOrNull { it.isSidecarBookMatch(book.name, sidecar.name) }?.let {
                        sidecar to it.getSidecarBookType()
                      }
                    }?.toMap() ?: emptyMap()
                pathToBookSidecars[dir]?.minusAssign(sidecars.keys)

                sidecars.mapTo(scannedSidecars) { (sidecar, type) ->
                  Sidecar(sidecar.url, book.url, sidecar.lastModifiedTime, type, Sidecar.Source.BOOK)
                }
              }
            }

            return FileVisitResult.CONTINUE
          }
        },
      )
    }

    // CUSTOM FORK (LRR path mode): emit the cross-bucket series collected above.
    lrrBooks.forEach { (seriesId, books) ->
      scannedSeries[lrrSeriesByKey.getValue(seriesId)] = books.sortedWith(compareBy<Book> { it.number }.thenBy { it.name })
    }

    // CUSTOM FORK (LRR path mode): every book under <oneshotDir> becomes its own series.
    // Url = the book's url and a single book are the same shape upstream uses for oneshots,
    // so covers, reading and the series->book redirect all behave as before.
    lrrOneshotBooks.forEach { book ->
      scannedSeries[
        Series(
          name = book.name,
          url = book.url,
          fileLastModified = book.fileLastModified,
          oneshot = true,
        ),
      ] = listOf(book)
    }

    // CUSTOM FORK: log after the LRR series have been flushed in, so the counts match what
    // was actually persisted (the walk alone knows nothing about cross-bucket series).
    val countOfBooks = scannedSeries.values.sumOf { it.size }
    logger.info { "Scanned ${scannedSeries.size} series, $countOfBooks books, and ${scannedSidecars.size} sidecars in $scanDuration" }

    return ScanResult(scannedSeries, scannedSidecars)
  }

  fun scanFile(
    path: Path,
    libraryRoot: URL? = null,
  ): Book? {
    if (!path.exists()) return null

    return pathToBook(path, path.readAttributes(), libraryRoot)
  }

  fun scanBookSidecars(path: Path): List<Sidecar> {
    val bookBaseName = path.nameWithoutExtension
    val parent = path.parent
    return parent
      .listDirectoryEntries()
      .filter { candidate -> sidecarBookPrefilter.any { it.matches(candidate.name) } }
      .mapNotNull { candidate ->
        sidecarBookConsumers.firstOrNull { it.isSidecarBookMatch(bookBaseName, candidate.name) }?.let {
          Sidecar(candidate.toUri().toURL(), parent.toUri().toURL(), candidate.readAttributes<BasicFileAttributes>().getUpdatedTime(), it.getSidecarBookType(), Sidecar.Source.BOOK)
        }
      }
  }

  /**
   * CUSTOM FORK: 构造 file: URL，**不触发额外的 stat**。
   *
   * [java.nio.file.Path.toUri] 内部走 UnixUriUtils.toUri，会 stat 一次来判断
   * 是否目录（决定要不要补尾斜杠）。在 NFS 这类网络挂载上，这一次 stat 就是
   * 一次 RPC——15 万个文件就是 15 万次多余往返，实测把全库扫描从分钟级拖到
   * 小时级（线程栈会停在 UnixNativeDispatcher.stat0）。
   *
   * 调用方从 walker 的 attrs 已经知道是文件还是目录，所以这里直接按已知类型
   * 拼接，编码规则与 Path.toUri 一致（URI 构造器做百分号编码）。
   */
  private fun fileUrlFromPath(path: Path): URL =
    URI("file", "", path.toAbsolutePath().toString(), null).toURL()

  private fun directoryUrlFromPath(path: Path): URL =
    URI("file", "", path.toAbsolutePath().toString() + "/", null).toURL()

  private fun pathToBook(
    path: Path,
    attrs: BasicFileAttributes,
    libraryRoot: URL? = null,
  ): Book =
    Book(
      name = path.nameWithoutExtension,
      url = fileUrlFromPath(path),
      fileLastModified = attrs.getUpdatedTime(),
      fileSize = attrs.size(),
      id = computeBookId(path, libraryRoot),
    )

  /**
   * CUSTOM FORK: 构造 Book 但**不 stat**。
   *
   * 只用于「一次性把整库登记进索引」的场景：此时 15 万个文件都需要一条记录，
   * 但我们不关心它们的 size/mtime（name-diff 扫描本来也不检测内容变化）。
   * 每次都 stat 会让首扫退化成几十次 stat 往返。
   *
   * 代价：FILE_SIZE=0、FILE_LAST_MODIFIED=epoch。之后要补齐就跑一次深度扫描。
   */
  fun bookWithoutStat(
    path: Path,
    libraryRoot: URL? = null,
  ): Book =
    Book(
      name = path.nameWithoutExtension,
      url = fileUrlFromPath(path),
      fileLastModified = LocalDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC")),
      fileSize = 0,
      id = computeBookId(path, libraryRoot),
    )

  /**
   * CUSTOM FORK: book identity is SHA1 of the path relative to the library root's
   * PARENT, byte-identical to LANraragi's `compute_id`, so the two systems share
   * one ID space and one cover pool.
   *
   * NOTE: series IDs are still TSIDs (`Series` in visitPreVisitDirectory passes no
   * `id`). Only book IDs were migrated to path hashes; series identity is unchanged.
   *
   * FALLBACK: when the library root is unknown (e.g. scanFile() for a book that is
   * not being scanned through a library scan) or the hash cannot be computed, the
   * TSID default is used instead of failing the scan. Ingesting a book must never
   * break because the hash was unavailable.
   */
  private fun computeBookId(
    path: Path,
    libraryRoot: URL?,
  ): String =
    try {
      if (libraryRoot == null) {
        TsidCreator.getTsid256().toString()
      } else {
        hasher.computePathHash(path, libraryRoot)
      }
    } catch (e: Exception) {
      logger.warn(e) { "Failed to compute path hash for $path (root: $libraryRoot), falling back to TSID" }
      TsidCreator.getTsid256().toString()
    }

  /**
   * CUSTOM FORK (LRR path mode): resolve <seriesDir>/<bucket>/<seriesId>/<volume>
   * from a book path. Returns null when the path does not follow that layout, lives
   * under <oneshotDir>, or the relative path cannot be computed.
   */
  private fun lrrSeriesFor(
    file: Path,
    root: Path,
    seriesDir: String,
    oneshotDir: String,
  ): Pair<String, Path>? {
    if (seriesDir.isBlank()) return null

    val segments =
      try {
        root.relativize(file).map { it.toString() }
      } catch (_: IllegalArgumentException) {
        return null
      }
    if (segments.isEmpty()) return null

    val seriesIndex = segments.lastIndexOf(seriesDir)
    // Layout is exactly <seriesDir>/<bucket>/<seriesId>/<file>: two segments between
    // <seriesDir> and the archive, deeper paths are deliberately not aggregated.
    if (seriesIndex < 0 || seriesIndex + 3 != segments.lastIndex) return null
    if (oneshotDir.isNotBlank() && segments.take(seriesIndex + 1).any { it == oneshotDir }) return null

    val seriesId = segments[seriesIndex + 2]
    if (seriesId.isBlank()) return null

    val seriesDirectory = segments.take(seriesIndex + 3).fold(root) { acc, segment -> acc.resolve(segment) }
    return seriesId to seriesDirectory
  }

  /**
   * CUSTOM FORK (LRR path mode): true when the archive lives under <oneshotDir>, i.e.
   * <root>/<oneshotDir>/<bucket>/<file>. Those are single-volume works by convention and
   * must never be merged into a series, nor become one giant "bucket" series.
   */
  private fun lrrIsOneshotFile(
    file: Path,
    root: Path,
    oneshotDir: String,
  ): Boolean {
    if (oneshotDir.isBlank()) return false
    val segments =
      try {
        root.relativize(file).map { it.toString() }
      } catch (_: IllegalArgumentException) {
        return false
      }
    return segments.dropLast(1).any { it == oneshotDir }
  }

  /** CUSTOM FORK (LRR path mode): volume number = the last integer in the archive name. */
  private fun parseVolumeNumber(baseName: String): Int = VOLUME_NUMBER_PATTERN.findAll(baseName).lastOrNull()?.value?.toIntOrNull() ?: 0
}

private val VOLUME_NUMBER_PATTERN = Regex("""\d+""")

fun BasicFileAttributes.getUpdatedTime(): LocalDateTime = maxOf(creationTime(), lastModifiedTime()).toLocalDateTime()

fun FileTime.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(this.toInstant(), ZoneId.systemDefault())
