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
}
