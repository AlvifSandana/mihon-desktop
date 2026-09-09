package mihon.desktop.loader.tracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.loader.library.LibraryDatabase
import mihon.desktop.loader.library.MihonDesktopDatabase
import mihon.desktop.loader.library.Tracker as TrackerRow

/**
 * Persists tracker bindings: one row per (library manga, tracker). Rows are
 * created when the user binds a manga via a tracker's search dialog and
 * deleted when the manga leaves the library (LibraryRepository.purgeManga,
 * same explicit-delete pattern as mangaCategory).
 */
class TrackerRepository(private val database: MihonDesktopDatabase = LibraryDatabase.get()) {
    private val trackerQueries = database.trackerQueries
    private val libraryQueries = database.libraryMangaQueries

    /** Resolves a library manga's row id, or null when it isn't in the library. */
    suspend fun mangaIdFor(sourceId: Long, mangaUrl: String): Long? = withContext(Dispatchers.IO) {
        libraryQueries.selectOne(sourceId, mangaUrl).executeAsOneOrNull()?.id
    }

    /** All bindings for a library manga (by source + url; empty when not in library). */
    suspend fun forManga(sourceId: Long, mangaUrl: String): List<TrackEntry> {
        val mangaId = mangaIdFor(sourceId, mangaUrl) ?: return emptyList()
        return forMangaId(mangaId)
    }

    /** All bindings for a library manga row id. */
    suspend fun forMangaId(mangaId: Long): List<TrackEntry> = withContext(Dispatchers.IO) {
        trackerQueries.selectForManga(mangaId).executeAsList().map { it.toEntry() }
    }

    suspend fun all(): List<TrackEntry> = withContext(Dispatchers.IO) {
        trackerQueries.selectAll().executeAsList().map { it.toEntry() }
    }

    /**
     * Insert-or-update by (mangaId, trackerName), keeping the row id stable.
     * Returns the stored entry (id + fresh updatedAt). The whole
     * update-then-insert-then-select sequence runs in one transaction so
     * concurrent upserts of the same (mangaId, trackerName) can never
     * interleave (e.g. two trackers finishing a push at once).
     */
    suspend fun upsert(entry: TrackEntry): TrackEntry = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        database.transactionWithResult {
            trackerQueries.updateEntry(
                remoteId = entry.remoteId,
                title = entry.title,
                status = entry.status.ordinal.toLong(),
                score = entry.score,
                lastChapterRead = entry.lastChapterRead,
                updatedAt = now,
                mangaId = entry.mangaId,
                trackerName = entry.trackerName,
            )
            if (trackerQueries.selectForMangaAndTracker(entry.mangaId, entry.trackerName).executeAsOneOrNull() == null) {
                trackerQueries.insertOrIgnore(
                    mangaId = entry.mangaId,
                    trackerName = entry.trackerName,
                    remoteId = entry.remoteId,
                    title = entry.title,
                    status = entry.status.ordinal.toLong(),
                    score = entry.score,
                    lastChapterRead = entry.lastChapterRead,
                    updatedAt = now,
                )
            }
            trackerQueries.selectForMangaAndTracker(entry.mangaId, entry.trackerName)
                .executeAsOne()
                .toEntry()
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        trackerQueries.deleteById(id)
    }

    /** Removes every binding of one manga (called from LibraryRepository.purgeManga). */
    suspend fun deleteAllForManga(mangaId: Long) = withContext(Dispatchers.IO) {
        trackerQueries.deleteForManga(mangaId)
    }
}

/** Generated row -> domain entry; `status` is the [TrackStatus] ordinal. */
internal fun TrackerRow.toEntry(): TrackEntry = TrackEntry(
    id = id,
    mangaId = mangaId,
    trackerName = trackerName,
    remoteId = remoteId,
    title = title,
    status = TrackStatus.fromInt(status.toInt()),
    score = score,
    lastChapterRead = lastChapterRead,
    updatedAt = updatedAt,
)
