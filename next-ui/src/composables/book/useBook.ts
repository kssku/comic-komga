import { useCurrentUser } from '@/colada/users'
import { useGetLibrariesByViewId } from '@/composables/libraries'
import type { BookDto } from '@/generated/openapi'
import { MediaStatus } from '@/types/MediaStatus'

export function useBook(book: MaybeRefOrGetter<BookDto>) {
  const { hasRole } = useCurrentUser()
  const { libraries } = useGetLibrariesByViewId(() => toValue(book).libraryId)

  const isDeleted = computed(() => toValue(book).deleted)
  const isUnavailable = computed(() => isDeleted.value || libraries.value?.[0]?.unavailable)

  const isNotReady = computed(() => toValue(book).media.status !== MediaStatus.Ready)

  // CUSTOM FORK: a lazy (zero-I/O) scan leaves a book UNKNOWN until the reader opens it -
  // the first page-list request analyzes it once, server-side. Reading must therefore NOT
  // wait for analysis: only states that can never be served (ERROR / UNSUPPORTED) block it.
  // Analysis stays an optional extra (page count, dimensions, cover).
  const canRead = computed(() => {
    const status = toValue(book).media.status
    const readable = status === MediaStatus.Ready || status === MediaStatus.Unknown
    return readable && hasRole('PAGE_STREAMING') && !isUnavailable.value
  })

  const isEpubReader = computed(() => {
    const b = toValue(book)
    return b.media.mediaProfile.toLowerCase() === 'epub' && !b.media.epubDivinaCompatible
  })

  const format = computed(() => {
    const b = toValue(book)
    if (b.media.mediaType.includes('x-rar-compressed')) return 'CBR'
    if (b.media.mediaType === 'application/zip') return 'CBZ'
    if (b.media.mediaType === 'application/pdf') return 'PDF'
    if (b.media.mediaType === 'application/epub+zip') return 'EPUB'
    // CUSTOM FORK: an unanalyzed book has no media type yet - fall back to the file extension
    // so the details page is not blank before the first read.
    if (!b.media.mediaType) return b.url.split('.').pop()?.split('?')[0]?.toUpperCase() ?? ''
    return b.media.mediaType
  })

  return {
    isUnavailable,
    canRead,
    isEpubReader,
    isNotReady,
    isDeleted,
    format,
  }
}
