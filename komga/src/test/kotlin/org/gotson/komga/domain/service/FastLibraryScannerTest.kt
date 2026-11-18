package org.gotson.komga.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class FastLibraryScannerTest {
  private val scanner = FastLibraryScanner()
  private val exts = setOf("cbz")

  private fun touch(
    dir: Path,
    name: String,
  ): Path {
    Files.createDirectories(dir)
    val file = dir.resolve(name)
    Files.writeString(file, "x")
    return file
  }

  // 数据库里存的是 URL.toString()（file:/...），不是 URI.toString()（file:///...）。
  private fun urlOf(file: Path): String = file.toUri().toURL().toString()

  @Test
  fun ourFileUrlBuilderMatchesPathToUri(
    @TempDir tmp: Path,
  ) {
    val file = touch(tmp, "abc.cbz")
    // 这两者必须逐字节一致：fast scan 用前者、数据库里存的是后者。
    // 不一致的后果是每轮都把已有文件当成新书重复插入。
    assertThat(URI("file", "", file.toAbsolutePath().toString(), null).toURL().toString())
      .isEqualTo(file.toUri().toURL().toString())
  }

  @Test
  fun emptyDbSeesEveryFileAsNew(
    @TempDir tmp: Path,
  ) {
    val root = tmp.resolve("lib")
    val bucket = root.resolve("oneshots/1-50000")
    touch(bucket, "1.cbz")
    touch(bucket, "2.cbz")

    val plan = scanner.planOneshots(root, "oneshots", emptyMap(), exts)!!

    assertThat(plan.newPaths).hasSize(2)
    assertThat(plan.goneBookIds).isEmpty()
    assertThat(plan.diskCount).isEqualTo(2)
    assertThat(plan.dbCount).isEqualTo(0)
  }

  @Test
  fun matchingDbUrlsProduceNoWork(
    @TempDir tmp: Path,
  ) {
    val root = tmp.resolve("lib")
    val bucket = root.resolve("oneshots/1-50000")
    val one = touch(bucket, "1.cbz")
    val two = touch(bucket, "2.cbz")
    val db = mapOf(urlOf(one) to "id1", urlOf(two) to "id2")

    val plan = scanner.planOneshots(root, "oneshots", db, exts)!!

    assertThat(plan.newPaths).isEmpty()
    assertThat(plan.goneBookIds).isEmpty()
    assertThat(plan.diskCount).isEqualTo(2)
    assertThat(plan.dbCount).isEqualTo(2)
  }

  @Test
  fun newFileReportedAndMissingFileSoftDeleted(
    @TempDir tmp: Path,
  ) {
    val root = tmp.resolve("lib")
    val bucket = root.resolve("oneshots/1-50000")
    val existing = touch(bucket, "1.cbz")
    touch(bucket, "2.cbz")
    // 库里有一个磁盘上已不存在的书
    val db = mapOf(urlOf(existing) to "id1", "file:/gone/9.cbz" to "id9")

    val plan = scanner.planOneshots(root, "oneshots", db, exts)!!

    assertThat(plan.newPaths.map { it.fileName.toString() }).containsExactly("2.cbz")
    assertThat(plan.goneBookIds).containsExactly("id9")
  }

  @Test
  fun nonOneshotLayoutBailsOut(
    @TempDir tmp: Path,
  ) {
    val root = tmp.resolve("lib")
    Files.createDirectories(root)
    assertThat(scanner.planOneshots(root, "oneshots", emptyMap(), exts)).isNull()
  }

  @Test
  fun otherExtensionsIgnored(
    @TempDir tmp: Path,
  ) {
    val root = tmp.resolve("lib")
    val bucket = root.resolve("oneshots/1-50000")
    touch(bucket, "1.cbz")
    touch(bucket, "notes.txt")

    val plan = scanner.planOneshots(root, "oneshots", emptyMap(), exts)!!

    assertThat(plan.newPaths).hasSize(1)
    assertThat(plan.newPaths[0].fileName.toString()).isEqualTo("1.cbz")
  }
}
