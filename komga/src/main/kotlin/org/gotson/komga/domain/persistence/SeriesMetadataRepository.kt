package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.SeriesMetadata

interface SeriesMetadataRepository {
  fun findById(seriesId: String): SeriesMetadata

  fun findByIdOrNull(seriesId: String): SeriesMetadata?

  fun insert(metadata: SeriesMetadata)

  /** CUSTOM FORK: 批量插入。 */
  fun insert(metadatas: Collection<SeriesMetadata>)

  fun update(metadata: SeriesMetadata)

  fun delete(seriesId: String)

  fun delete(seriesIds: Collection<String>)

  fun count(): Long
}
