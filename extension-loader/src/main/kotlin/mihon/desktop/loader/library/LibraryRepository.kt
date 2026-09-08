package mihon.desktop.loader.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists which manga the user has added to their library, which extension jar/source
 * provides them, and per-manga reading progress -- the pieces needed to reopen a library
 * entry (re-load its jar, reconstruct its [eu.kanade.tachiyomi.source.model.SManga]) and
 * to resume a chapter where the user left off, without any network round-trip.
 */
class LibraryRepository(database: MihonDesktopDatabase = LibraryDatabase.get()) {
    private val libraryQueries = database.libraryMangaQueries
    private val progressQueries = database.readingProgressQueries
    private val readChapterQueries = database.readChapterQueries
    private val readerPrefQueries = database.readerPreferencesQueries

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
        for (url in chapterUrls) {
            readChapterQueries.insertOrReplace(
                sourceId = sourceId,
                mangaUrl = mangaUrl,
                chapterUrl = url,
                readAt = now,
            )
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
}
