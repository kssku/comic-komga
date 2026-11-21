package org.gotson.komga.infrastructure.configuration

import jakarta.annotation.PostConstruct
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.convert.DurationUnit
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated
import org.sqlite.SQLiteConfig.JournalMode
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

@Component
@ConfigurationProperties(prefix = "komga")
@Validated
class KomgaProperties {
  @PostConstruct
  private fun makeDirs() {
    try {
      Path(database.file).parent.createDirectories()
      Path(tasksDb.file).parent.createDirectories()
    } catch (_: Exception) {
    }
  }

  @Positive
  var pageHashing: Int = 3

  /**
   * Thumbnail generation mode.
   *
   * CUSTOM FORK: on remote/FUSE mounts (CloudDrive2 over 115), generating a thumbnail
   * on ingest reads the archive contents (to extract the first page), which is exactly
   * what the zero-I/O scan path avoids. `lazy` (default) skips ingest-time generation,
   * so a full-library scan never opens an archive. Thumbnails then come either from
   * external sources or on-demand generation.
   *
   * - `lazy` (default): do NOT generate thumbnails on ingest
   * - `auto`: upstream behaviour, generate on ingest
   *
   * Set via environment variable `KOMGA_THUMBNAIL_MODE`.
   */
  var thumbnailMode: ThumbnailMode = ThumbnailMode.LAZY

  enum class ThumbnailMode {
    LAZY,
    AUTO,
  }

  /**
   * CUSTOM FORK: LANraragi-compatible series aggregation.
   *
   * Upstream (and this fork by default) treats "the directory a book sits in" as its
   * series. That is wrong for a library laid out by the LANraragi ingestion tooling,
   * where one series is sharded across bucket directories:
   *
   *   <seriesDir>/<bucket>/<seriesId>/<volume>.cbz
   *
   * With this mode enabled a book's series identity is <seriesId>, so volumes of one
   * series are collected across buckets, and the book's number is parsed from the
   * archive name so volumes sort by number instead of by ingest order.
   *
   * Disabled by default: enabling it changes series identity for an existing library,
   * so a full rescan is required. Set via `komga.lrr-path-mode.enabled`
   * (environment variable `KOMGA_LRR_PATH_MODE_ENABLED`).
   */
  var lrrPathMode: LrrPathModeProperties = LrrPathModeProperties()

  class LrrPathModeProperties {
    var enabled: Boolean = false

    /** Directory whose child layout is <bucket>/<seriesId>/<volume>. */
    var seriesDir: String = "series"

    /** Directory whose archives stay standalone and are never aggregated. */
    var oneshotDir: String = "oneshots"
  }

  /**
   * CUSTOM FORK: on-demand thumbnail generation switch.
   *
   * With `generate = false` (default) a missing thumbnail is never produced by opening
   * the archive on request: browsing covers reads nothing from the remote mount.
   * Reading a book is unaffected.
   *
   * Set via `komga.thumbnail.generate` or environment variable `KOMGA_THUMBNAIL_GENERATE`.
   */
  var thumbnail = Thumbnail()

  class Thumbnail {
    var generate: Boolean = false
  }

  // CUSTOM FORK: analyze a book on first page-list access (KOMGA_ANALYSIS_ON_DEMAND).
  var analysis = Analysis()

  // CUSTOM FORK: name-diff scanning, see [org.gotson.komga.domain.service.FastLibraryScanner].
  // Off by default: it changes how deletions are detected (names instead of full walk),
  // so it is opt-in per deployment.
  var scan = Scan()

  class Scan {
    var nameDiffEnabled: Boolean = false
  }

  // CUSTOM FORK: guard rail for the library-wide buttons.
  //
  // "Analyze library" and "Refresh all metadata" iterate EVERY book of the library. On this
  // deployment a library holds 150k books living on a 115/CloudDrive2 mount, and an analysis
  // reads each archive from the cloud: pressing the button once would pull ~1.3 TB and enqueue
  // ~150k tasks behind a single SQLite writer. The actions are still available (they are
  // legitimate on small libraries), but above this size they are refused with an explicit
  // message instead of quietly melting the box.
  var bulkActionLimit: Int = 5000

  class Analysis {
    var onDemand: Boolean = true

    // CUSTOM FORK: in-memory page-list cache (BookPageMemoryCache). Set to false to
    // bypass it entirely and fall back to reading the archive on every resolve.
    var pageCacheEnabled: Boolean = true

    // CUSTOM FORK: TTL of the page-list cache, applied as expireAfterAccess so a book
    // that is being read is not evicted mid-read.
    var pageCacheTtlMinutes: Int = 60

    // CUSTOM FORK: cache capacity measured in PAGES, not entries, so a few large books
    // cannot crowd out many small ones (or vice versa).
    var pageCacheMaxTotalPages: Int = 100_000

    // CUSTOM FORK: upper bound on a single page-list resolution (one archive read).
    var pageResolveTimeoutSeconds: Int = 30
  }

  @Positive
  var epubDivinaLetterCountThreshold: Int = 15

  /**
   * CUSTOM FORK: root of a mirror tree holding one cover image per book, sharded by the
   * first two characters of the book id (matching lrr's `substr($id,0,2)`), which keeps
   * every shard well under 115's per-directory file limit.
   * Example: <root>/ab/abcdef123456.jpg for book id "abcdef123456".
   * The tree lives on a LOCAL filesystem; only a handful of direct existence checks are
   * made per book, never a directory listing. Empty (default) disables mirror lookups.
   */
  var artworkMirrorRoot: String = ""

  /**
   * CUSTOM FORK: base path used to derive the artwork mirror key from a book path.
   *
   * The key is sha256(relative path)[:16], so the cover tree is addressed by CONTENT
   * (the comic's archive path) instead of by an id that changes whenever the library is
   * rebuilt or re-imported. Every tool in the project derives the same key from the same
   * relative path, with no mapping table.
   *
   * Example with "/content" as base:
   *   /content/wnacg/oneshots/400001-450000/405530/405530.cbz
   *   -> sha256("wnacg/oneshots/400001-450000/405530/405530.cbz")[:16]
   *   -> <artworkMirrorRoot>/ab/abcdef0123456789.jpg
   *
   * Empty (default) keeps the legacy behaviour: key = book id, sharded by its first 2 chars.
   */
  var artworkHashBase: String = ""

  /**
   * CUSTOM FORK: when true, a cover lookup that misses the mirror tree returns nothing
   * instead of falling back to listing the book directory (a network round-trip per book on
   * a FUSE mount). Enable once the mirror tree is fully populated and the library lives on a
   * metered/remote mount.
   */
  var artworkMirrorOnly: Boolean = false

  var oauth2AccountCreation: Boolean = false

  var oidcEmailVerification: Boolean = true

  var jdbcSessionEnabled: Boolean = true

  var database = Database()

  var tasksDb = Database()

  var sessionsDb = Database()

  var cors = Cors()

  var lucene = Lucene()

  var configDir: String? = null

  var kobo = Kobo()

  val fonts = Fonts()

  class Cors {
    var allowedOrigins: List<String> = emptyList()
  }

  class Database {
    @get:NotBlank
    var file: String = ""

    @get:Positive
    var batchChunkSize: Int = 1000

    @get:Positive
    var poolSize: Int? = null

    @get:Positive
    var maxPoolSize: Int = 1

    var journalMode: JournalMode? = JournalMode.WAL

    @DurationUnit(ChronoUnit.SECONDS)
    var busyTimeout: Duration? = null

    var pragmas: Map<String, String> = emptyMap()

    var checkLocalFilesystem: Boolean = true
  }

  class Fonts {
    @get:NotBlank
    var dataDirectory: String = ""
  }

  class Lucene {
    @get:NotBlank
    var dataDirectory: String = ""

    var indexAnalyzer = IndexAnalyzer()

    @DurationUnit(ChronoUnit.SECONDS)
    var commitDelay: Duration = Duration.ofSeconds(2)

    class IndexAnalyzer {
      @get:Positive
      var minGram: Int = 3

      @get:Positive
      var maxGram: Int = 10

      var preserveOriginal: Boolean = true
    }
  }

  class Kobo {
    @get:Positive
    var syncItemLimit: Int = 100

    var kepubifyPath: String? = null
  }
}
