-- CUSTOM FORK: the task queue is polled with
--   SELECT ... WHERE OWNER IS NULL [AND group not blocked]
--   ORDER BY PRIORITY DESC, LAST_MODIFIED_DATE LIMIT 1
-- Without a matching index SQLite scans the WHOLE queue and sorts it (temp b-tree) on every
-- poll. A first-time ingest of 190k books leaves ~570k rows here, which turns a ~2ms poll into
-- ~400ms and serialises all 16 worker threads on the queue's single write connection.
--
-- OWNER must be the FIRST column: the poll always filters "OWNER IS NULL", so SQLite seeks to
-- that slot and then walks PRIORITY/date in index order, stopping at the first row
-- (measured 4-6ms, vs ~400ms). An index on (PRIORITY, date) alone is NOT used, because the
-- query filters on a non-indexed column and the planner falls back to a full sort.
CREATE INDEX IF NOT EXISTS idx__tasks__queue
    ON TASK (OWNER, PRIORITY DESC, LAST_MODIFIED_DATE);
