package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

private val nameDiffLogger = KotlinLogging.logger {}

/**
 * CUSTOM FORK: 按「名字」做增量扫描。
 *
 * 背景：Komga 上游每次扫描都用 Files.walkFileTree 对**每个文件** stat 一次。
 * 本地磁盘上 stat 是纳秒级，这个设计没问题；但我们的库在 CloudDrive2 的 FUSE
 * 挂载上（154 经 NFS 访问），一次 stat 要 4–55 ms：
 *
 *     150,000 文件 x 18 ms = 45 分钟（串行，实测更久）
 *
 * 而 readdir 只需要 1.7 秒（find 全树的实测值）。本类只 readdir 拿文件名，
 * 与数据库里已有的 (id, url) 比对，产出「新增」与「消失」两份清单；只有新增的
 * 那几个文件需要 stat（由 FileSystemScanner.scanFile 在构造 Book 时完成）。
 *
 * 限制：只处理 LRR 的 oneshots 布局 (<root>/<oneshotDir>/<bucket>/<file>.cbz)。
 * 其他布局返回 null，调用方回退到全量扫描。
 */
class FastLibraryScanner {
  data class Plan(
    val newPaths: List<Path>,
    val goneBookIds: List<String>,
    val diskCount: Int,
    val dbCount: Int,
  )

  fun planOneshots(
    root: Path,
    oneshotDir: String,
    dbIdByUrl: Map<String, String>,
    extensions: Set<String>,
    scope: Path? = null,
  ): Plan? {
    val base = root.resolve(oneshotDir)
    if (!Files.isDirectory(base)) return null

    val diskPaths = mutableListOf<Path>()
    if (scope != null) {
      // 作用域扫描：只 readdir 这一个目录（一个桶，几千个文件）。
      // dbIdByUrl 已由调用方裁剪到同一作用域，所以这里的 gone 判定是安全的。
      Files.newDirectoryStream(scope).use { files ->
        for (file in files) {
          if (Files.isRegularFile(file) && file.extension.lowercase() in extensions) diskPaths.add(file)
        }
      }
    } else {
      Files.newDirectoryStream(base).use { buckets ->
        for (bucket in buckets) {
          // 目录数量很少（本库 9 个），这一次 stat 值得付。
          if (!Files.isDirectory(bucket)) continue
          Files.newDirectoryStream(bucket).use { files ->
            for (file in files) {
              // 关键：这里**不** stat。扩展名是纯字符串判断。
              if (file.extension.lowercase() in extensions) diskPaths.add(file)
            }
          }
        }
      }
    }

    val diskUrls = HashSet<String>(diskPaths.size * 2)
    diskPaths.forEach { diskUrls.add(fileUrl(it).toString()) }

    val newPaths = diskPaths.filter { fileUrl(it).toString() !in dbIdByUrl }
    val goneIds = dbIdByUrl.filterKeys { it !in diskUrls }.values.toList()

    nameDiffLogger.info {
      "Name-diff scan: disk=${diskPaths.size} db=${dbIdByUrl.size} new=${newPaths.size} gone=${goneIds.size}"
    }
    return Plan(newPaths, goneIds, diskPaths.size, dbIdByUrl.size)
  }

  /** 与 FileSystemScanner 里的实现一致：不触发额外 stat。 */
  private fun fileUrl(path: Path): URL = URI("file", "", path.toAbsolutePath().toString(), null).toURL()
}
