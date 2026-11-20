package org.gotson.komga.domain.service

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.gotson.komga.infrastructure.hash.Hasher
import org.junit.jupiter.api.Test
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path

/**
 * CUSTOM FORK: LRR path mode decides whether an archive is a one-shot or a volume of a series.
 *
 * Rules under test:
 *  - <root>/<oneshotDir>/...                       -> one book, its own series, oneshot = true
 *  - <root>/<seriesDir>/<bucket>/<seriesId>/<vol>  -> volumes of the same seriesId aggregate
 *                                                     across buckets into ONE series
 *  - <root>/<seriesDir>/<seriesId>/<vol>           -> not the expected shape, no aggregation
 *
 * Getting these wrong silently merges every one-shot of a bucket into a single giant series,
 * or splits one series into one series per bucket.
 */
class LrrPathModeTest {
  private fun scanner(enabled: Boolean = true) =
    FileSystemScanner(
      emptyList(),
      emptyList(),
      Hasher(),
      KomgaProperties().apply { lrrPathMode.enabled = enabled },
    )

  private fun touch(
    root: Path,
    relative: String,
  ) {
    val path = root.resolve(relative)
    Files.createDirectories(path.parent)
    Files.createFile(path)
  }

  private fun newRoot(fs: FileSystem): Path {
    val root = fs.getPath("/root")
    Files.createDirectory(root)
    return root
  }

  @Test
  fun `archive under oneshots becomes its own single-book series`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = newRoot(fs)
      touch(root, "oneshots/1-50000/405530/405530.cbz")
      touch(root, "oneshots/400001-450000/405183/405183.cbz")

      val scan = scanner().scanRootFolder(root).series

      assertThat(scan).hasSize(2)
      assertThat(scan.keys).allMatch { it.oneshot }
      assertThat(scan.values).allMatch { it.size == 1 }
      assertThat(scan.values.flatten().map { it.name }).containsExactlyInAnyOrder("405530", "405183")
    }
  }

  @Test
  fun `archive directly inside oneshots is still a one-shot`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = newRoot(fs)
      touch(root, "oneshots/405530.cbz")

      val scan = scanner().scanRootFolder(root).series

      assertThat(scan).hasSize(1)
      assertThat(scan.keys.single().oneshot).isTrue()
    }
  }

  @Test
  fun `same series id in two buckets is aggregated into one series`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = newRoot(fs)
      touch(root, "series/1-50000/abc123/001.cbz")
      touch(root, "series/50001-100000/abc123/002.cbz")

      val scan = scanner().scanRootFolder(root).series

      assertThat(scan).hasSize(1)
      val books = scan.values.single()
      assertThat(books).hasSize(2)
      assertThat(books.map { it.name }).containsExactly("001", "002")
      assertThat(scan.keys.single().oneshot).isFalse()
    }
  }

  @Test
  fun `different series ids in the same bucket stay separate`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = newRoot(fs)
      touch(root, "series/1-50000/aaa/001.cbz")
      touch(root, "series/1-50000/bbb/001.cbz")

      val scan = scanner().scanRootFolder(root).series

      assertThat(scan).hasSize(2)
      assertThat(scan.values).allMatch { it.size == 1 }
    }
  }

  @Test
  fun `series without the bucket layer is not aggregated`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = newRoot(fs)
      touch(root, "series/abc123/001.cbz")

      val scan = scanner().scanRootFolder(root).series

      // Not the <seriesDir>/<bucket>/<seriesId>/<volume> shape, so LRR aggregation does not
      // apply and the parent directory becomes a plain series.
      assertThat(scan).hasSize(1)
      assertThat(scan.keys.single().oneshot).isFalse()
      assertThat(scan.values.single()).hasSize(1)
    }
  }

  @Test
  fun `one-shot and series with the same id are never merged`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = newRoot(fs)
      touch(root, "oneshots/1-50000/abc123/abc123.cbz")
      touch(root, "series/1-50000/abc123/001.cbz")

      val scan = scanner().scanRootFolder(root).series

      assertThat(scan).hasSize(2)
      assertThat(scan.keys.count { it.oneshot }).isEqualTo(1)
      assertThat(scan.keys.count { !it.oneshot }).isEqualTo(1)
    }
  }

  @Test
  fun `lrr path mode disabled falls back to parent directory grouping`() {
    Jimfs.newFileSystem(Configuration.unix()).use { fs ->
      val root = newRoot(fs)
      touch(root, "oneshots/1-50000/405530/405530.cbz")
      touch(root, "oneshots/1-50000/405531/405531.cbz")

      val scan = scanner(enabled = false).scanRootFolder(root).series

      // Without LRR mode these are plain per-directory series, none flagged oneshot.
      assertThat(scan).hasSize(2)
      assertThat(scan.keys).noneMatch { it.oneshot }
    }
  }
}
