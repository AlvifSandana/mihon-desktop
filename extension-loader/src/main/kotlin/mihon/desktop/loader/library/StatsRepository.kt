package mihon.desktop.loader.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only aggregate queries for the Stats screen: library totals,
 * per-source and per-category breakdowns, download counts and recent reading
 * activity (7/30-day windows), computed entirely in SQL.
 *
 * Every method is suspend + [Dispatchers.IO], matching the other repositories.
 *
 * @param now clock seam for tests; defaults to the wall clock. The 7/30-day
 *   windows are cut at `now() - threshold` and are inclusive at the boundary.
 */
class StatsRepository(
    private val database: MihonDesktopDatabase = LibraryDatabase.get(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val statsQueries = database.statsQueries

    /**
     * One-shot snapshot for the Stats screen. Fetched in a single IO hop so
     * the UI renders from an immutable value instead of observing partial
     * results mid-query.
     */
    data class LibraryStats(
        val totalManga: Long,
        val mangaPerSource: List<MangaPerSource>,
        val totalReadChapters: Long,
        val readChaptersPerSource: List<ReadChaptersPerSource>,
        val totalDownloadedChapters: Long,
        val categoryCount: Long,
        /** Categories in user-defined order, each with its mapped manga count. */
        val mangaPerCategory: List<SelectAllWithCounts>,
        /** Library manga belonging to no category (the "Default" row). */
        val mangaWithNoCategory: Long,
        val readChaptersLast7Days: Long,
        val readChaptersLast30Days: Long,
        val averageChaptersPerManga: Double,
    )

    /**
     * One-shot snapshot for the Stats screen, read inside a single
     * transaction so concurrent writes can't leak a torn view (e.g. total
     * manga counted before an add while the per-source breakdown is counted
     * after it). The UI renders from an immutable value instead of observing
     * partial results mid-query.
     */
    suspend fun snapshot(): LibraryStats = withContext(Dispatchers.IO) {
        database.transactionWithResult {
            val totalManga = statsQueries.countLibraryManga().executeAsOne()
            val totalRead = statsQueries.countReadChapters().executeAsOne()
            val currentTime = now()
            val mangaInCategories = statsQueries.countMangaInCategories().executeAsOne()
            LibraryStats(
                totalManga = totalManga,
                mangaPerSource = statsQueries.mangaPerSource().executeAsList(),
                totalReadChapters = totalRead,
                readChaptersPerSource = statsQueries.readChaptersPerSource().executeAsList(),
                totalDownloadedChapters = statsQueries.countDownloadedChapters().executeAsOne(),
                categoryCount = statsQueries.countCategories().executeAsOne(),
                mangaPerCategory = database.categoryQueries.selectAllWithCounts().executeAsList(),
                mangaWithNoCategory = (totalManga - mangaInCategories).coerceAtLeast(0),
                readChaptersLast7Days = statsQueries.countReadChaptersSince(currentTime - DAY_MILLIS * 7).executeAsOne(),
                readChaptersLast30Days = statsQueries.countReadChaptersSince(currentTime - DAY_MILLIS * 30).executeAsOne(),
                averageChaptersPerManga = if (totalManga > 0) totalRead.toDouble() / totalManga else 0.0,
            )
        }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
