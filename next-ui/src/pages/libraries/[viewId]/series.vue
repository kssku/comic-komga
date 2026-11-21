<template>
  <v-app-bar class="bg-background px-2">
    <template #prepend>
      <ChipCount :count="totalElements" />
    </template>

    <template #append>
      <div class="d-flex ga-2">
        <!--  CUSTOM FORK: LANraragi keeps single-volume works ("oneshots") apart from
              multi-volume series; Komga only offers that as a checkbox in the filter drawer.
              This toggle drives the very same filter, so the two stay in sync.  -->
        <v-btn-toggle
          v-model="oneshotFilter"
          class="d-none d-md-inline-flex"
          mandatory
          density="compact"
          variant="outlined"
          divided
        >
          <v-btn
            v-for="option in oneshotFilterOptions"
            :key="option.value"
            size="small"
            :value="option.value"
            :text="$formatMessage(option.message)"
          />
        </v-btn-toggle>

        <PosterSizeSlider />

        <PresentationSelector
          v-if="display.smAndUp.value"
          v-model="presentationMode"
          :modes="['grid', 'list']"
          toggle
        />

        <PageSizeSelector
          v-if="isBrowsingPaged"
          v-model="appStore.browsingPageSize"
          allow-unpaged
        />

        <PagingSelector v-model="appStore.browsingPaging" />

        <FilterButton
          :count="filtersCountAll"
          :dot="!sortIsDefault"
          @click="filterDrawer = true"
        />
      </div>
    </template>
  </v-app-bar>

  <TempDrawer v-model="filterDrawer">
    <FilterPanel
      v-model="filters"
      :count="filtersCountAll"
      @clear="filtersClearAll()"
    />

    <v-divider />

    <v-list>
      <v-list-subheader>
        <span>{{ $formatMessage(commonMessages.filterPanelSort) }}</span>
        <SortRestore
          v-if="!sortIsDefault"
          class="position-absolute right-0 me-6"
          @restore="sortRestore()"
        />
      </v-list-subheader>

      <SortList
        v-model="sortActive"
        :items="sortOptions"
        mandatory
      />
    </v-list>
  </TempDrawer>

  <EmptyStateFilterNoResults
    v-if="totalElements === 0 && filtersCountAll > 0"
    @reset="filtersClearAll()"
  />

  <ItemBrowser
    v-else
    v-model:page1="page1"
    :paging="appStore.browsingPaging"
    :items="dataItems as unknown as BookDto[]"
    :presentation-mode="isOneshotMode ? 'grid' : presentationModeEffective"
    :has-next-page="hasNextPage"
    :page-count="pageCount"
    @load-next-page="loadNextPage()"
  >
    <template #default="{ item, isSelected, preSelect, toggleSelect }">
      <!--  CUSTOM FORK: one-shots are single-volume works; LRR lists them as individual
            entries, so this mode renders books, not one-book series cards.  -->
      <BookCard
        v-if="isOneshotMode"
        show-series
        stretch-poster
        :book="item as BookDto"
        :selected="isSelected"
        :pre-select="preSelect"
        :width="display.xs.value ? 'auto' : appStore.gridCardWidth"
        @selection="(_val, event) => toggleSelect(event as MouseEvent)"
      />

      <SeriesCard
        v-else-if="presentationModeEffective === 'grid'"
        :stretch-poster="appStore.stretchPoster"
        :series="item as unknown as SeriesDto"
        :selected="isSelected"
        :pre-select="preSelect"
        :width="display.xs.value ? 'auto' : appStore.gridCardWidth"
        :sort-active="sortActive"
        @selection="(_val, event) => toggleSelect(event as MouseEvent)"
      />

      <SeriesCardWide
        v-else-if="presentationModeEffective === 'list'"
        :stretch-poster="appStore.stretchPoster"
        :series="item as unknown as SeriesDto"
        :selected="isSelected"
        :pre-select="preSelect"
        :width="appStore.gridCardWidth"
        @selection="(_val, event) => toggleSelect(event as MouseEvent)"
      />
    </template>
  </ItemBrowser>
</template>

<script lang="ts" setup>
import { useInfiniteQuery, useQuery } from '@pinia/colada'
import { seriesListQuery, seriesListQueryInfinite } from '@/colada/series'
import { bookListQuery, bookListQueryInfinite } from '@/colada/books'

import { PageRequest, type Sort } from '@/types/PageRequest'
import { useGetLibrariesByViewId } from '@/composables/libraries'
import { useAppStore } from '@/stores/app'
import { usePagination } from '@/composables/pagination'
import { useSelectionStore } from '@/stores/selection'
import { useDisplay } from 'vuetify'
import {
  filtersToConditions,
  schemaFilterIncludeExcludeToConditions,
  schemaFilterReadStatusToConditions,
  schemaFilterStringToConditions,
  valuesToConditions,
} from '@/functions/filter'
import { commonMessages } from '@/utils/i18n/common-messages'
import { defineMessage } from 'vue-intl'
import { useIntlFormatter } from '@/composables/intlFormatter'
import { sortBooks, sortSeries } from '@/types/sort'
import PosterSizeSlider from '@/components/PosterSizeSlider.vue'
import FilterButton from '@/components/filter/FilterButton.vue'
import { usePresentationMode } from '@/composables/presentationMode'
import { storeToRefs } from 'pinia'
import { useFilters } from '@/composables/filter'
import ChipCount from '@/components/ChipCount.vue'
import { useSelectionContextualActions } from '@/composables/selection'
import type { BookDto, SearchConditionBook, SearchConditionSeries, SeriesDto } from '@/generated/openapi'
import { BrowsingContextKey } from '@/functions/browsing-context'
import { useSort } from '@/composables/sort'
import { useLibraryViewStore } from '@/stores/library-view'

const route = useRoute('/libraries/[viewId]/series')
const libraryViewId = route.params.viewId
const { libraryIds } = useGetLibrariesByViewId(libraryViewId)

provide(
  BrowsingContextKey,
  computed(() => ({ type: 'libraryView', id: libraryViewId, subType: 'series' })),
)

const display = useDisplay()
const appStore = useAppStore()
const { isBrowsingScroll, isBrowsingPaged } = storeToRefs(appStore)

const libraryViewStore = useLibraryViewStore()
libraryViewStore.saveState(libraryViewId, 'series')

const viewName = computed(() => `${libraryViewId}_series`)
const { presentationMode, presentationModeEffective } = usePresentationMode(viewName)

const { page0, page1, pageCount } = usePagination()

const selectionStore = useSelectionStore()

const {
  filters,
  clearAll: filtersClearAll,
  countAll: filtersCountAll,
} = useFilters(
  [
    'seriesStatus',
    'read',
    'genre',
    'tag',
    'publisher',
    'sharingLabel',
    'language',
    'year',
    'age',
    'complete',
    'unavailable',
    'oneshot',
    'contributors',
    'notableEntities',
  ],
  {
    viewName: viewName.value,
    persist: true,
  },
)

const { convertSortOptionDescriptor } = useIntlFormatter()
const sortDefault: Sort[] = [{ key: 'metadata.titleSort', order: 'asc' }]
const sortOptions = sortSeries.map((it) => convertSortOptionDescriptor(it))
const {
  sortActive,
  isDefault: sortIsDefault,
  restoreDefault: sortRestore,
} = useSort({
  defaultSort: sortDefault,
  options: sortOptions,
  persist: true,
  viewName: viewName.value,
})

const conds = computed(() => ({
  allOf: [
    valuesToConditions(libraryIds.value, 'libraryId'),
    ...filtersToConditions(filters),
  ] as SearchConditionSeries[],
}))

// clear selection if filter or paging changes
watch([() => JSON.stringify(conds.value), () => appStore.browsingPaging], () =>
  selectionStore.clear(),
)

const apiQuery = computed(() => ({
  condition: conds.value,
}))

// CUSTOM FORK: LANraragi keeps single-volume works ("oneshots") apart from multi-volume
// series. This toolbar toggle drives the very same filter the drawer exposes, so both stay
// in sync, and switching to one-shot mode swaps the whole data source from series to books.
const oneshotFilterOptions = [
  {
    value: 'all',
    message: defineMessage({
      description: 'Series list filter: show both multi-volume series and single-volume works',
      defaultMessage: 'All',
      id: 'Q3XcC5',
    }),
  },
  {
    value: 'series',
    message: defineMessage({
      description: 'Series list filter: multi-volume series only',
      defaultMessage: 'Series',
      id: '6f8q+L',
    }),
  },
  {
    value: 'oneshot',
    message: defineMessage({
      description: 'Series list filter: single-volume works only',
      defaultMessage: 'One-shots',
      id: '42E/dM',
    }),
  },
] as const

const oneshotFilter = computed<'all' | 'series' | 'oneshot'>({
  get() {
    const include = filters.value.oneshot?.filter.i
    return include === 'i' ? 'oneshot' : include === 'e' ? 'series' : 'all'
  },
  set(mode) {
    if (!filters.value.oneshot) return
    filters.value.oneshot.filter.i =
      mode === 'oneshot' ? 'i' : mode === 'series' ? 'e' : undefined
  },
})

const isOneshotMode = computed(() => oneshotFilter.value === 'oneshot')

// Only book-compatible filters are applied here (library, deleted, read status, tag,
// contributors); series-only filters are ignored while this mode is active.
const bookConds = computed(() => ({
  allOf: [
    valuesToConditions(libraryIds.value, 'libraryId'),
    filters.value.unavailable
      ? schemaFilterIncludeExcludeToConditions(filters.value.unavailable.filter, 'deleted')
      : undefined,
    filters.value.read
      ? schemaFilterReadStatusToConditions(filters.value.read.filter)
      : undefined,
    filters.value.tag
      ? schemaFilterStringToConditions(filters.value.tag.filter, 'tag', true)
      : undefined,
    { oneShot: { operator: 'isTrue' as const } },
  ].filter(Boolean) as SearchConditionBook[],
}))

// CUSTOM FORK: one-shot mode lists BOOKS, so it needs the book sort keys (series + number)
// and its own persisted state, independent from the series list sort.
const { sortActive: oneshotSortActive } = useSort({
  defaultSort: [
    { key: 'series', order: 'asc' },
    { key: 'metadata.numberSort', order: 'asc' },
  ] as Sort[],
  options: sortBooks.map((it) => convertSortOptionDescriptor(it)),
  persist: true,
  viewName: `${viewName.value}_oneshots`,
})

const { data: dataPaged } = useQuery(() => ({
  ...seriesListQuery({
    search: { ...apiQuery.value },
    pageRequest: PageRequest.FromPageSize(appStore.browsingPageSize, page0.value, sortActive.value),
  }),
  enabled: isBrowsingPaged.value && !isOneshotMode.value,
}))

const { data: booksPaged } = useQuery(() => ({
  ...bookListQuery({
    search: { condition: bookConds.value },
    pageRequest: PageRequest.FromPageSize(
      appStore.browsingPageSize,
      page0.value,
      oneshotSortActive.value,
    ),
  }),
  enabled: isBrowsingPaged.value && isOneshotMode.value,
}))

watch(dataPaged, (newDataPaged) => {
  if (newDataPaged && !isOneshotMode.value) pageCount.value = newDataPaged.totalPages ?? 0
})

watch(booksPaged, (newBooksPaged) => {
  if (newBooksPaged && isOneshotMode.value) pageCount.value = newBooksPaged.totalPages ?? 0
})

const {
  data: dataInfinite,
  loadNextPage: loadNextSeriesPage,
  hasNextPage: hasNextSeriesPage,
} = useInfiniteQuery(() => ({
  ...seriesListQueryInfinite({ search: { ...apiQuery.value }, sort: sortActive.value }),
  enabled: isBrowsingScroll.value && !isOneshotMode.value,
}))

const {
  data: booksInfinite,
  loadNextPage: loadNextBooksPage,
  hasNextPage: hasNextBooksPage,
} = useInfiniteQuery(() => ({
  ...bookListQueryInfinite({
    search: { condition: bookConds.value },
    sort: oneshotSortActive.value,
  }),
  enabled: isBrowsingScroll.value && isOneshotMode.value,
}))

const dataInfiniteFlat = computed(() =>
  dataInfinite.value?.pages.flatMap((it) => it?.content ?? []),
)
const booksInfiniteFlat = computed(() =>
  booksInfinite.value?.pages.flatMap((it) => it?.content ?? []),
)

const dataItems = computed<SeriesDto[] | BookDto[]>(() => {
  if (isOneshotMode.value) {
    return isBrowsingPaged.value
      ? (booksPaged.value?.content ?? [])
      : (booksInfiniteFlat.value ?? [])
  }
  return isBrowsingPaged.value ? (dataPaged.value?.content ?? []) : (dataInfiniteFlat.value ?? [])
})
const totalElements = computed(() => {
  if (isOneshotMode.value) {
    return isBrowsingPaged.value
      ? booksPaged.value?.totalElements
      : booksInfinite.value?.pages?.[0]?.totalElements
  }
  return isBrowsingPaged.value
    ? dataPaged.value?.totalElements
    : dataInfinite.value?.pages?.[0]?.totalElements
})

const hasNextPage = computed(() =>
  isOneshotMode.value ? hasNextBooksPage.value : hasNextSeriesPage.value,
)
const loadNextPage = () => (isOneshotMode.value ? loadNextBooksPage() : loadNextSeriesPage())

useSelectionContextualActions(dataItems)

const filterDrawer = ref(false)
</script>

<route lang="yaml">
meta:
  requiresRole: USER
  scrollable: true
</route>
