package org.gotson.komga.domain.model

import java.time.LocalDateTime
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

interface MediaExtension

class ProxyExtension private constructor(
  val extensionClassName: String,
) : MediaExtension {
  companion object {
    fun of(extensionClass: String?): ProxyExtension? =
      extensionClass?.let {
        val kClass = Class.forName(extensionClass).kotlin
        if (kClass.qualifiedName != MediaExtension::class.qualifiedName && kClass.isSubclassOf(MediaExtension::class))
          ProxyExtension(extensionClass)
        else
          null
      }
  }

  inline fun <reified T> proxyForType(): Boolean = T::class.qualifiedName == extensionClassName

  fun proxyForType(clazz: KClass<out Any>): Boolean = clazz.qualifiedName == extensionClassName
}

data class MediaExtensionEpub(
  val toc: List<EpubTocEntry> = emptyList(),
  val landmarks: List<EpubTocEntry> = emptyList(),
  val pageList: List<EpubTocEntry> = emptyList(),
  val isFixedLayout: Boolean = false,
  val positions: List<R2Locator> = emptyList(),
) : MediaExtension

/**
 * Fingerprint of the archive file that was analyzed, stored alongside the analyzed [Media].
 *
 * The MEDIA table has no file identity columns, so without this the analysis cache cannot tell
 * whether the book file was replaced after it was analyzed. [bookFileLastModified] and
 * [bookFileSize] mirror `Book.fileLastModified` / `Book.fileSize` at analysis time and are
 * compared against the current book before reusing the cached media.
 */
data class MediaExtensionDivina(
  val bookFileLastModified: LocalDateTime? = null,
  val bookFileSize: Long? = null,
) : MediaExtension {
  fun matches(book: Book): Boolean {
    val analyzedLastModified = bookFileLastModified ?: return false
    val analyzedSize = bookFileSize ?: return false
    return analyzedLastModified == book.fileLastModified && analyzedSize == book.fileSize
  }
}
