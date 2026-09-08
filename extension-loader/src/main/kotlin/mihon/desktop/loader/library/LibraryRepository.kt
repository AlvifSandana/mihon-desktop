package mihon.desktop.loader.library

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists which manga the user has added to their library, which extension jar/source
 * provides them, and per-manga reading progress -- the pieces needed to reopen a library
 * entry (re-load its jar, reconstruct its [eu.kanade.tachiyomi.source.model.SManga]) and
 * to resume a chapter where the user left off, without any network round-trip.
 */
class LibraryRepository(private val database: MihonDesktopDatabase = LibraryDatabase.get()) {
    private val libraryQueries = database.libraryMangaQueries
    private val progressQueries = database.readingProgressQueries
    private val readChapterQueries = database.readChapterQueries
    private val readerPrefQueries = database.readerPreferencesQueries
    private val updateHistoryQueries = database.updateHistoryQueries

    suspend fun all(): List<LibraryManga> = withContext(Dispatchers.IO) {
        libraryQueries.selectAll().executeAsList()
    }

    suspend fun isFavorite(sourceId: Long, mangaUrl: String): Boolean = withContext(Dispatchers.IO) {
        libraryQueries.selectOne(sourceId, mangaUrl).executeAsOneOrNull() != null
    }

    suspend fun add(
        sourceId: Long,
        packageName: String,
        jarFileName: String,
        extensionName: String,
        mangaUrl: String,
        title: String,
        thumbnailUrl: String?,
        author: String?,
    ) = withContext(Dispatchers.IO) {
        libraryQueries.insertOrReplace(
            sourceId = sourceId,
            packageName = packageName,
            jarFileName = jarFileName,
            extensionName = extensionName,
            mangaUrl = mangaUrl,
            title = title,
            thumbnailUrl = thumbnailUrl,
            author = author,
            addedAt = System.currentTimeMillis(),
        )
    }

    suspend fun remove(sourceId: Long, mangaUrl: String) = withContext(Dispatchers.IO) {
        libraryQueries.delete(sourceId, mangaUrl)
        // Also purge this manga's update history (baselines included): if the
        // manga is re-added later, its chapters are re-seeded as baseline, so
        // nothing is lost and nothing floods Updates.
        updateHistoryQueries.deleteForManga(sourceId, mangaUrl)
    }

    suspend fun progressFor(sourceId: Long, mangaUrl: String): ReadingProgress? = withContext(Dispatchers.IO) {
        progressQueries.selectForManga(sourceId, mangaUrl).executeAsOneOrNull()
    }

    suspend fun saveProgress(
        sourceId: Long,
        mangaUrl: String,
        chapterUrl: String,
        chapterName: String,
        pageIndex: Int,
    ) = withContext(Dispatchers.IO) {
        progressQueries.upsert(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
            chapterName = chapterName,
            pageIndex = pageIndex.toLong(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** Check if a chapter has been marked as read. */
    suspend fun isChapterRead(sourceId: Long, chapterUrl: String): Boolean = withContext(Dispatchers.IO) {
        readChapterQueries.selectOne(sourceId, chapterUrl).executeAsOneOrNull() != null
    }

    /** Get all read chapter URLs for a manga. */
    suspend fun readChapters(sourceId: Long, mangaUrl: String): Set<String> = withContext(Dispatchers.IO) {
        readChapterQueries.selectForManga(sourceId, mangaUrl).executeAsList()
            .map { it.chapterUrl }.toSet()
    }

    /** Mark a single chapter as read. */
    suspend fun markAsRead(sourceId: Long, mangaUrl: String, chapterUrl: String, chapterName: String) = withContext(Dispatchers.IO) {
        readChapterQueries.insertOrReplace(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
            chapterName = chapterName,
            readAt = System.currentTimeMillis(),
        )
    }

    /** Mark all given chapters as read. */
    suspend fun markAllAsRead(sourceId: Long, mangaUrl: String, chapters: List<SChapter>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        database.transaction {
            for (ch in chapters) {
                readChapterQueries.insertOrReplace(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                    chapterUrl = ch.url,
                    chapterName = ch.name,
                    readAt = now,
                )
            }
        }
    }

    /** Unmark a chapter as read. */
    suspend fun markAsUnread(sourceId: Long, chapterUrl: String) = withContext(Dispatchers.IO) {
        readChapterQueries.delete(sourceId, chapterUrl)
    }

    /** Get webtoon mode preference for a manga. */
    suspend fun getWebtoonMode(sourceId: Long, mangaUrl: String): Boolean = withContext(Dispatchers.IO) {
        readerPrefQueries.selectOne(sourceId, mangaUrl).executeAsOneOrNull()?.webtoonMode == 1L
    }

    /** Save webtoon mode preference for a manga. */
    suspend fun setWebtoonMode(sourceId: Long, mangaUrl: String, webtoonMode: Boolean) = withContext(Dispatchers.IO) {
        readerPrefQueries.upsert(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            webtoonMode = if (webtoonMode) 1L else 0L,
            updatedAt = System.currentTimeMillis(),
        )
    }

    // ── Update History ──────────────────────────────────────────────────

    /** Get all update history entries, most recent first. */
    suspend fun allUpdates(): List<UpdateHistory> = withContext(Dispatchers.IO) {
        updateHistoryQueries.selectAll().executeAsList()
    }

    /** Number of update entries recorded after [timestamp] (epoch ms). */
    suspend fun updateCountSince(timestamp: Long): Long = withContext(Dispatchers.IO) {
        updateHistoryQueries.countSince(timestamp).executeAsOne()
    }

    /**
     * Diffs [chapters] (the source's full chapter list) against the update
     * history already recorded for this manga, persists only the chapters we
     * have never seen before, and returns them.
     *
     * This is what makes the Updates tab show *new* chapters rather than every
     * chapter of every library manga on every refresh.
     *
     * @param baseline when true, chapters are recorded as a *seed* -- known but
     *   never shown in Updates. Used when a manga is added to the library, so
     *   the chapters that already existed at add-time don't flood the Updates
     *   list on the first refresh.
     */
    suspend fun recordNewChapters(
        sourceId: Long,
        mangaUrl: String,
        mangaTitle: String,
        thumbnailUrl: String?,
        packageName: String,
        jarFileName: String,
        chapters: List<SChapter>,
        baseline: Boolean = false,
    ): List<UpdateHistory> = withContext(Dispatchers.IO) {
        val known = updateHistoryQueries.selectChapterUrls(sourceId, mangaUrl).executeAsList().toSet()
        val fresh = chapters.filter { it.url !in known }
        if (fresh.isEmpty()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        val entries = fresh.map { ch ->
            UpdateHistory(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
                mangaTitle = mangaTitle,
                thumbnailUrl = thumbnailUrl,
                chapterUrl = ch.url,
                chapterName = ch.name,
                chapterNumber = ch.chapter_number.toDouble(),
                packageName = packageName,
                jarFileName = jarFileName,
                baseline = if (baseline) 1L else 0L,
                fetchedAt = now,
            )
        }
        database.transaction {
            for (entry in entries) {
                // OR IGNORE: a concurrent scheduler/refresh run may have inserted
                // the same chapter already -- first writer wins, no clobbering.
                updateHistoryQueries.insertOrIgnore(
                    sourceId = entry.sourceId,
                    mangaUrl = entry.mangaUrl,
                    mangaTitle = entry.mangaTitle,
                    thumbnailUrl = entry.thumbnailUrl,
                    chapterUrl = entry.chapterUrl,
                    chapterName = entry.chapterName,
                    chapterNumber = entry.chapterNumber,
                    packageName = entry.packageName,
                    jarFileName = entry.jarFileName,
                    baseline = entry.baseline,
                    fetchedAt = entry.fetchedAt,
                )
            }
        }
        entries
    }

    /** Delete update history entries older than [olderThanMillis] (epoch ms). */
    suspend fun clearOldUpdates(olderThanMillis: Long) = withContext(Dispatchers.IO) {
        updateHistoryQueries.deleteOlderThan(olderThanMillis)
    }

    /**
     * Dismisses all visible updates. Rows are converted to baseline instead of
     * deleted: the chapters stay "known" so the next refresh doesn't resurrect
     * them, they just never appear in the Updates list again.
     */
    suspend fun clearAllUpdates() = withContext(Dispatchers.IO) {
        updateHistoryQueries.dismissAll()
    }

    // ── History (read chapters with manga info) ─────────────────────────

    /** Get all read chapters joined with manga info, most recent first. */
    suspend fun historyEntries(): List<SelectAllWithManga> = withContext(Dispatchers.IO) {
        readChapterQueries.selectAllWithManga().executeAsList()
    }

    /** Delete a single history entry. */
    suspend fun clearHistory(sourceId: Long, chapterUrl: String) = withContext(Dispatchers.IO) {
        readChapterQueries.delete(sourceId, chapterUrl)
    }

    /** Delete all history entries. */
    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        readChapterQueries.deleteAll()
    }
}
