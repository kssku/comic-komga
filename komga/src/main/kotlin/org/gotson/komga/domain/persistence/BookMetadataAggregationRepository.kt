package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.BookMetadataAggregation

interface BookMetadataAggregationRepository {
  fun findById(seriesId: String): BookMetadataAggregation

  fun findByIdOrNull(seriesId: String): BookMetadataAggregation?

  fun insert(metadata: BookMetadataAggregation)

  /** CUSTOM FORK: 批量插入。 */
  fun insert(metadatas: Collection<BookMetadataAggregation>)

  fun update(metadata: BookMetadataAggregation)

  fun delete(seriesId: String)

  fun delete(seriesIds: Collection<String>)

  fun count(): Long
}
