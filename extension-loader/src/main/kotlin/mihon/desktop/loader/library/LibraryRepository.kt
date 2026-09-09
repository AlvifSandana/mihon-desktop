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
    private val mangaCategoryQueries = database.mangaCategoryQueries
    private val categoryQueries = database.categoryQueries
    private val trackerQueries = database.trackerQueries

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
        upsertLibraryManga(
            sourceId = sourceId,
            packageName = packageName,
            jarFileName = jarFileName,
            extensionName = extensionName,
            mangaUrl = mangaUrl,
            title = title,
            thumbnailUrl = thumbnailUrl,
            author = author,
        )
    }

    /**
     * add()'s upsert semantics without its own dispatcher hop, so it can run
     * inside a caller's transaction (see [applyMigration]): when the manga is
     * already in the library, metadata is updated in place, keeping the row id
     * (and addedAt) stable -- mangaCategory mappings reference the id and
     * INSERT OR REPLACE would assign a fresh one, orphaning them.
     */
    private fun upsertLibraryManga(
        sourceId: Long,
        packageName: String,
        jarFileName: String,
        extensionName: String,
        mangaUrl: String,
        title: String,
        thumbnailUrl: String?,
        author: String?,
    ) {
        val existing = libraryQueries.selectOne(sourceId, mangaUrl).executeAsOneOrNull()
        if (existing != null) {
            libraryQueries.updateMeta(
                packageName = packageName,
                jarFileName = jarFileName,
                extensionName = extensionName,
                title = title,
                thumbnailUrl = thumbnailUrl,
                author = author,
                id = existing.id,
            )
        } else {
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
    }

    suspend fun remove(sourceId: Long, mangaUrl: String) = withContext(Dispatchers.IO) {
        // One atomic purge: mappings, history, read state, progress and the
        // library row disappear together or not at all.
        database.transaction {
            purgeManga(sourceId, mangaUrl)
        }
    }

    /**
     * Re-points every library and update-history row for [packageName] at
     * [newJarFileName], atomically. Called after an extension update swaps in
     * a jar under a new file name -- persisted rows must never reference the
     * deleted old jar, or reopening library entries (which load via
     * `ExtensionLoader.loadCached(jarFileName)`) breaks.
     */
    suspend fun rebindJarFileName(packageName: String, newJarFileName: String) = withContext(Dispatchers.IO) {
        database.transaction {
            libraryQueries.updateJarFileName(newJarFileName = newJarFileName, packageName = packageName)
            updateHistoryQueries.updateJarFileName(newJarFileName = newJarFileName, packageName = packageName)
        }
    }

    /**
     * Purges every row belonging to one manga -- category mappings, tracker
     * bindings, update history (baselines included; re-adding re-seeds them),
     * read chapters, reading progress, then the library row itself. Runs
     * inside the caller's transaction.
     */
    private fun purgeManga(sourceId: Long, mangaUrl: String) {
        libraryQueries.selectOne(sourceId, mangaUrl).executeAsOneOrNull()?.let { manga ->
            mangaCategoryQueries.deleteForManga(manga.id)
            trackerQueries.deleteForManga(manga.id)
        }
        libraryQueries.delete(sourceId, mangaUrl)
        updateHistoryQueries.deleteForManga(sourceId, mangaUrl)
        readChapterQueries.deleteAllForManga(sourceId, mangaUrl)
        progressQueries.deleteForManga(sourceId, mangaUrl)
    }

    /**
     * Applies one manga's source-to-source migration as a single atomic write:
     * adds (or refreshes) the new library row, seeds the new source's chapters
     * as baseline so the first library refresh doesn't flood Updates, marks the
     * matched chapters read, copies reading progress and category mappings, and
     * purges the old entry -- all or nothing. A failure midway can never leave
     * both entries half-migrated.
     *
     * SQLDelight transactions are thread-local, so this cannot be composed from
     * the suspend methods above ([add], [markAllAsRead], [remove], ...) or from
     * CategoryRepository's -- their `withContext` hops would break the
     * transaction. Every write is therefore inlined here. The migration engine
     * pre-matches chapters and pre-reads old state; this method only persists.
     *
     * @param chapters the target source's full chapter list, seeded as baseline
     *   (insertOrIgnore keeps it idempotent when re-migrating onto an entry
     *   that already has history).
     * @param readChapters chapters to mark as read on the target, already
     *   matched; empty = copy no read state.
     * @param progress reading progress to copy; only `chapterUrl`,
     *   `chapterName` and `pageIndex` are used -- the row is keyed to the new
     *   entry and stamped with a fresh `updatedAt` regardless of the fields.
     *   Null = save no progress.
     * @param categoryIds categories to map onto the new row, replacing any
     *   existing mappings (same semantics as
     *   CategoryRepository.setMangaCategories); empty = leave mappings
     *   untouched.
     * @param oldSourceId/oldMangaUrl the old entry to purge in the same
     *   transaction; null (both) = keep it (deleteFromLibrary = false).
     */
    suspend fun applyMigration(
        sourceId: Long,
        packageName: String,
        jarFileName: String,
        extensionName: String,
        mangaUrl: String,
        title: String,
        thumbnailUrl: String?,
        author: String?,
        chapters: List<SChapter>,
        readChapters: List<SChapter>,
        progress: ReadingProgress?,
        categoryIds: List<Long>,
        oldSourceId: Long?,
        oldMangaUrl: String?,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        database.transaction {
            // New row: same upsert semantics as add() (stable row id).
            upsertLibraryManga(
                sourceId = sourceId,
                packageName = packageName,
                jarFileName = jarFileName,
                extensionName = extensionName,
                mangaUrl = mangaUrl,
                title = title,
                thumbnailUrl = thumbnailUrl,
                author = author,
            )
            // Seed the target's chapters as known-but-not-new (baseline).
            for (ch in chapters) {
                updateHistoryQueries.insertOrIgnore(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                    mangaTitle = title,
                    thumbnailUrl = thumbnailUrl,
                    chapterUrl = ch.url,
                    chapterName = ch.name,
                    chapterNumber = ch.chapter_number.toDouble(),
                    packageName = packageName,
                    jarFileName = jarFileName,
                    baseline = 1L,
                    fetchedAt = now,
                )
            }
            // Read state + progress copy.
            for (ch in readChapters) {
                readChapterQueries.insertOrReplace(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                    chapterUrl = ch.url,
                    chapterName = ch.name,
                    readAt = now,
                )
            }
            progress?.let { p ->
                progressQueries.upsert(
                    sourceId = sourceId,
                    mangaUrl = mangaUrl,
                    chapterUrl = p.chapterUrl,
                    chapterName = p.chapterName,
                    pageIndex = p.pageIndex,
                    updatedAt = now,
                )
            }
            // Category mappings (same replace-and-skip-unknown semantics as
            // CategoryRepository.setMangaCategories, inlined for thread-locality).
            if (categoryIds.isNotEmpty()) {
                libraryQueries.selectOne(sourceId, mangaUrl).executeAsOneOrNull()?.let { newRow ->
                    mangaCategoryQueries.deleteForManga(newRow.id)
                    for (categoryId in categoryIds.distinct()) {
                        if (categoryQueries.selectById(categoryId).executeAsOneOrNull() != null) {
                            mangaCategoryQueries.insertOrIgnore(newRow.id, categoryId)
                        }
                    }
                }
            }
            // Old entry: purged in the same transaction so both entries can
            // never coexist after a partial failure.
            if (oldSourceId != null && oldMangaUrl != null) {
                purgeManga(oldSourceId, oldMangaUrl)
            }
        }
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

    /**
     * Read-chapter rows (url + name + readAt) for a manga. Migration needs the
     * chapter names to recover chapter numbers when matching on the new source.
     */
    suspend fun readChapterRows(sourceId: Long, mangaUrl: String): List<ReadChapters> = withContext(Dispatchers.IO) {
        readChapterQueries.selectForManga(sourceId, mangaUrl).executeAsList()
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

    /** Save webtoon mode preference for a manga, preserving the other reader prefs. */
    suspend fun setWebtoonMode(sourceId: Long, mangaUrl: String, webtoonMode: Boolean) = withContext(Dispatchers.IO) {
        val existing = readerPrefQueries.selectOne(sourceId, mangaUrl).executeAsOneOrNull()
        readerPrefQueries.upsert(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            webtoonMode = if (webtoonMode) 1L else 0L,
            dualPageMode = existing?.dualPageMode ?: 0L,
            pageTransition = existing?.pageTransition ?: "none",
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** Get dual-page mode preference for a manga. */
    suspend fun getDualPageMode(sourceId: Long, mangaUrl: String): Boolean = withContext(Dispatchers.IO) {
        readerPrefQueries.selectOne(sourceId, mangaUrl).executeAsOneOrNull()?.dualPageMode == 1L
    }

    /** Save dual-page mode preference for a manga, preserving the other reader prefs. */
    suspend fun setDualPageMode(sourceId: Long, mangaUrl: String, dualPageMode: Boolean) = withContext(Dispatchers.IO) {
        val existing = readerPrefQueries.selectOne(sourceId, mangaUrl).executeAsOneOrNull()
        readerPrefQueries.upsert(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            webtoonMode = existing?.webtoonMode ?: 0L,
            dualPageMode = if (dualPageMode) 1L else 0L,
            pageTransition = existing?.pageTransition ?: "none",
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** Get page transition preference for a manga: "none", "slide", or "fade". */
    suspend fun getPageTransition(sourceId: Long, mangaUrl: String): String = withContext(Dispatchers.IO) {
        readerPrefQueries.selectOne(sourceId, mangaUrl).executeAsOneOrNull()?.pageTransition ?: "none"
    }

    /** Save page transition preference for a manga, preserving the other reader prefs. */
    suspend fun setPageTransition(sourceId: Long, mangaUrl: String, pageTransition: String) = withContext(Dispatchers.IO) {
        val existing = readerPrefQueries.selectOne(sourceId, mangaUrl).executeAsOneOrNull()
        readerPrefQueries.upsert(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            webtoonMode = existing?.webtoonMode ?: 0L,
            dualPageMode = existing?.dualPageMode ?: 0L,
            pageTransition = pageTransition,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Save all reader preferences for a manga in a single upsert. Avoids the
     * three sequential read-modify-write setters racing or clobbering each
     * other when several prefs change together (the reader's save path).
     */
    suspend fun setReaderPrefs(
        sourceId: Long,
        mangaUrl: String,
        webtoonMode: Boolean,
        dualPageMode: Boolean,
        pageTransition: String,
    ) = withContext(Dispatchers.IO) {
        readerPrefQueries.upsert(
            sourceId = sourceId,
            mangaUrl = mangaUrl,
            webtoonMode = if (webtoonMode) 1L else 0L,
            dualPageMode = if (dualPageMode) 1L else 0L,
            pageTransition = pageTransition,
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
