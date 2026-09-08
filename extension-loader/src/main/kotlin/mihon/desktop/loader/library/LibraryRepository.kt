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
    suspend fun markAsRead(sourceId: Long, mangaUrl: String, chapterUrl: String) = withContext(Dispatchers.IO) {
        readChapterQueries.insertOrReplace(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            chapterUrl = chapterUrl,
            readAt = System.currentTimeMillis(),
        )
    }

    /** Mark all chapters in a manga as read. */
    suspend fun markAllAsRead(sourceId: Long, mangaUrl: String, chapterUrls: List<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        database.transaction {
            for (url in chapterUrls) {
                readChapterQueries.insertOrReplace(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                    chapterUrl = url,
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
     */
    suspend fun recordNewChapters(
        sourceId: Long,
        mangaUrl: String,
        mangaTitle: String,
        thumbnailUrl: String?,
        packageName: String,
        jarFileName: String,
        chapters: List<SChapter>,
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
                fetchedAt = now,
            )
        }
        database.transaction {
            for (entry in entries) {
                updateHistoryQueries.insertOrReplace(
                    sourceId = entry.sourceId,
                    mangaUrl = entry.mangaUrl,
                    mangaTitle = entry.mangaTitle,
                    thumbnailUrl = entry.thumbnailUrl,
                    chapterUrl = entry.chapterUrl,
                    chapterName = entry.chapterName,
                    chapterNumber = entry.chapterNumber,
                    packageName = entry.packageName,
                    jarFileName = entry.jarFileName,
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

    /** Delete all update history entries. */
    suspend fun clearAllUpdates() = withContext(Dispatchers.IO) {
        updateHistoryQueries.deleteAll()
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
