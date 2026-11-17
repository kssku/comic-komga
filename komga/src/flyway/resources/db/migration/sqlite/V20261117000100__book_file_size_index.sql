-- CUSTOM FORK: restoring books after a rescan looks up deleted books by file size
-- (BookDao.findAllDeletedByFileSize). BOOK has no index on FILE_SIZE, so that lookup
-- scans the whole table; with one book per archive (LRR path mode) a 150k-book scan is
-- O(n^2) and the persist phase degrades to a crawl.
CREATE INDEX IF NOT EXISTS idx__book__file_size on BOOK (FILE_SIZE);
