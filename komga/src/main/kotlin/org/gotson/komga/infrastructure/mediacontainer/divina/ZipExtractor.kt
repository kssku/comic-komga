package org.gotson.komga.infrastructure.mediacontainer.divina

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import net.greypanther.natsort.CaseInsensitiveSimpleNaturalComparator
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.gotson.komga.domain.model.MediaContainerEntry
import org.gotson.komga.domain.model.MediaType
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.gotson.komga.infrastructure.util.getZipEntryBytes
import org.gotson.komga.infrastructure.util.use
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Service
class ZipExtractor(
  private val contentDetector: ContentDetector,
) : DivinaExtractor {
  private val natSortComparator: Comparator<String> = CaseInsensitiveSimpleNaturalComparator.getInstance()

  /**
   * CUSTOM FORK: one open ZIP per recently read book, instead of one per page request.
   *
   * Opening a ZipFile reads the end-of-central-directory and the central directory; on the
   * CloudDrive2/115 mount each open is a (sometimes large) transfer, so serving a 200-page
   * book used to re-read that directory 200 times. The handle is kept for a few books and
   * closed when evicted; Apache Commons ZipFile is not thread-safe, so reads synchronise on
   * the instance.
   */
  private val openArchives =
    Caffeine
      .newBuilder()
      .maximumSize(8)
      .expireAfterAccess(10, TimeUnit.MINUTES)
      .removalListener<Path, ZipFile> { _, zip, _ -> runCatching { zip?.close() } }
      .build<Path, ZipFile>()

  override fun mediaTypes(): List<String> = listOf(MediaType.ZIP.type)

  /**
   * CUSTOM FORK: every entry's metadata comes from the ZIP central directory alone.
   *
   * `setIgnoreLocalFileHeader(true)` is mandatory here, not an optimisation: without it the
   * ZipFile constructor resolves each entry's local file header (a seek to `localHeaderOffset`
   * plus a 30-byte read). On a mount that cannot seek cheaply that is one sparse random read
   * per page, so a 200-page book still paid 200 transfers just to build the page list.
   *
   * Upstream opened every archive entry (`zip.getInputStream`) to sniff its magic bytes
   * and, when asked, to read the image header for its dimension. On the CloudDrive2/115
   * FUSE mount each of those opens costs hundreds of milliseconds, so a 200-page book
   * meant 200 network round-trips just to build the page list.
   *
   * The page list is now derived entirely from metadata already present in the ZIP
   * central directory:
   *  - mediaType from the entry NAME (in-memory Tika MIME lookup, no I/O)
   *  - fileSize  from `entry.size`
   *  - dimension is NOT read, and is always null
   *
   * `analyzeDimensions` is intentionally not consumed: honouring it would require
   * opening each entry, which is precisely what this fork avoids. Dimension is a
   * display hint only; see ContentDetector.detectMediaTypeByName for the type trade-off.
   */
  override fun getEntries(
    path: Path,
    analyzeDimensions: Boolean,
  ): List<MediaContainerEntry> =
    ZipFile.builder().setPath(path).setIgnoreLocalFileHeader(true).use { zip ->
      val entries = zip.entries.toList().filter { !it.isDirectory }

      // CUSTOM FORK: encryption is flagged in the central directory (bit 0 of the general
      // purpose flags), so it can be detected with zero I/O. Upstream discovered encrypted
      // archives only by failing to decrypt entry contents while sniffing magic bytes.
      // Now that entries are typed by NAME, that failure never happens, so an encrypted
      // archive would silently be reported as READY and only break at read time. Fail here
      // instead, with the same ERROR status upstream produced (ERR_1008 via the generic
      // exception handler in BookAnalyzer).
      if (entries.any { it.generalPurposeBit.usesEncryption() }) {
        throw IllegalStateException("Encrypted ZIP archives are not supported")
      }

      entries
        .map { entry ->
          val mediaType = contentDetector.detectMediaTypeByName(entry.name)
          val fileSize = if (entry.size == ArchiveEntry.SIZE_UNKNOWN) null else entry.size
          MediaContainerEntry(name = entry.name, mediaType = mediaType, dimension = null, fileSize = fileSize)
        }.sortedWith(compareBy(natSortComparator) { it.name })
    }

  override fun getEntryStream(
    path: Path,
    entryName: String,
  ): ByteArray {
    val zip =
      try {
        openArchives.get(path) {
          ZipFile.builder().setPath(path).setUseUnicodeExtraFields(true).setIgnoreLocalFileHeader(true).get()
        }
      } catch (e: Exception) {
        logger.debug(e) { "Could not cache ZIP handle for $path, falling back" }
        return getZipEntryBytes(path, entryName)
      }

    return synchronized(zip) {
      val entry = zip.getEntry(entryName)
      if (entry == null) {
        // entry may only be resolvable through its local header (unicode extra fields)
        getZipEntryBytes(path, entryName)
      } else {
        zip.getInputStream(entry).use { it.readBytes() }
      }
    }
  }
}
