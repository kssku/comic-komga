-- CUSTOM FORK: scan-time series lookup is (library_id = ? AND url = ?) for every scanned
-- series. Without an index that is a full scan of the library per series: with one series
-- per book (LRR path mode, one-shot archives) a 26k-book scan degrades from ~17 to ~0
-- series/s. Composite (LIBRARY_ID, URL) keeps the lookup logarithmic.
CREATE INDEX IF NOT EXISTS idx__series__library_id_url on SERIES (LIBRARY_ID, URL);
