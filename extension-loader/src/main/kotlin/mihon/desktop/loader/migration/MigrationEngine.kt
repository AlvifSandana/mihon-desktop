package mihon.desktop.loader.migration

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mihon.desktop.loader.download.DownloadManager
import mihon.desktop.loader.library.CategoryRepository
import mihon.desktop.loader.library.LibraryManga
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.library.ReadingProgress
import kotlin.math.abs

/**
 * The source to migrate into: the loaded [Source] plus the extension metadata a
 * new library row needs (mirrors what SourcesTab persists for a freshly
 * browsed source).
 */
data class MigrationTarget(
    val source: Source,
    val packageName: String,
    val jarFileName: String,
    val extensionName: String,
)

/** Per-run migration options. */
data class MigrationOptions(
    val includeCategories: Boolean = true,
    val includeReadChapters: Boolean = true,
    /** Re-download chapters that were downloaded (or read) on the old source. */
    val downloadChapters: Boolean = false,
    /** Remove the old library entry (and its downloads) after a successful migration. */
    val deleteFromLibrary: Boolean = true,
)

/** A downloaded chapter on the old source: enough to match it on the target. */
data class DownloadedChapterRef(val chapterUrl: String, val chapterName: String)

/**
 * Downloads seam for [MigrationEngine]. Production wiring is
 * [DownloadManagerMigrationDownloads]; tests inject a fake to observe calls.
 */
interface MigrationDownloads {
    suspend fun downloadedChapters(sourceId: Long, mangaUrl: String): List<DownloadedChapterRef>
    suspend fun deleteAllForManga(sourceId: Long, mangaUrl: String)
    suspend fun enqueueDownload(source: Source, mangaUrl: String, chapter: SChapter)
}

/** [MigrationDownloads] backed by the real [DownloadManager]. */
class DownloadManagerMigrationDownloads(
    private val manager: DownloadManager = DownloadManager(),
) : MigrationDownloads {
    override suspend fun downloadedChapters(sourceId: Long, mangaUrl: String): List<DownloadedChapterRef> =
        manager.downloadedChapterRecords(sourceId, mangaUrl).map {
            DownloadedChapterRef(chapterUrl = it.chapterUrl, chapterName = it.chapterName)
        }

    override suspend fun deleteAllForManga(sourceId: Long, mangaUrl: String) {
        manager.deleteAllForManga(sourceId, mangaUrl)
    }

    /**
     * Downloads directly (not via DownloadQueue): migration wants deterministic
     * sequential completion with its own failure isolation, so it bypasses the
     * queue's workers/retry/notifications entirely.
     */
    override suspend fun enqueueDownload(source: Source, mangaUrl: String, chapter: SChapter) {
        manager.downloadChapter(source, mangaUrl, chapter)
    }
}

/** One search hit on the target source. [exactMatch] = normalized titles equal. */
data class MigrationCandidate(val manga: SManga, val exactMatch: Boolean)

/** Outcome of migrating (or failing to migrate) one manga. */
sealed interface MigrationStatus {
    data class Migrated(val toTitle: String) : MigrationStatus
    /** Target search returned nothing. */
    data object NoMatch : MigrationStatus
    /** Auto-search found candidates but none was an unambiguous exact match. */
    data object NeedsManualMatch : MigrationStatus
    /** User explicitly skipped this manga in manual mode. */
    data object Skipped : MigrationStatus
    data class Failed(val error: String) : MigrationStatus
}

data class MigrationResult(val manga: LibraryManga, val status: MigrationStatus)

/**
 * Source-to-source migration: moves library manga from one source to another,
 * copying reading state and categories Mihon-style (desktop-simplified).
 *
 * Old chapter numbers are recovered from the read/download chapter names stored
 * in the DB (the same "Chapter 12.5" parsing MangaDetailScreen uses), so no
 * network round-trip to the old source is needed -- migration still works when
 * the old source is dead, which is the usual reason for migrating.
 *
 * All network calls are suspend + [Dispatchers.IO]; DB writes for one manga run
 * inside [NonCancellable] as a single atomic transaction
 * (LibraryRepository.applyMigration), so cancelling the batch -- or a failure
 * midway through the writes -- never leaves a half-written migration. One
 * failing manga never aborts the batch (failure isolation).
 */
class MigrationEngine(
    private val libraryRepository: LibraryRepository = LibraryRepository(),
    private val categoryRepository: CategoryRepository = CategoryRepository(),
    private val downloads: MigrationDownloads = DownloadManagerMigrationDownloads(),
) {

    /**
     * Searches [target] for [title] and returns ranked candidates, capped at
     * [limit]: exact normalized-title matches first, then startsWith, then
     * contains, then the rest (source order preserved within each tier).
     */
    suspend fun findCandidates(target: Source, title: String, limit: Int = 10): List<MigrationCandidate> {
        val catalogue = target as? CatalogueSource ?: return emptyList()
        val page = withContext(Dispatchers.IO) {
            catalogue.getSearchManga(1, title, FilterList())
        }
        if (page.mangas.isEmpty()) return emptyList()
        val normalized = normalizeTitle(title)
        data class Ranked(val sourceOrder: Int, val normalizedTitle: String, val manga: SManga)
        return page.mangas
            .mapIndexed { index, manga -> Ranked(index, normalizeTitle(manga.title), manga) }
            .sortedWith(
                compareBy(
                    { rankTier(normalized, it.normalizedTitle) },
                    { it.sourceOrder },
                ),
            )
            .take(limit)
            .map { ranked ->
                MigrationCandidate(manga = ranked.manga, exactMatch = ranked.normalizedTitle == normalized)
            }
    }

    /**
     * Migrates a single manga onto [target], using [match] (auto-found or
     * user-picked) as the new source's manga. On success the new library row,
     * read state, reading progress and categories exist on the target; the old
     * entry (and its downloads) is removed when [MigrationOptions.deleteFromLibrary].
     *
     * Network failure before any DB write → [MigrationStatus.Failed], old manga untouched.
     */
    suspend fun migrateManga(
        old: LibraryManga,
        target: MigrationTarget,
        match: SManga,
        options: MigrationOptions,
    ): MigrationStatus {
        // Defense in depth: the UI filters same-source targets, but migrating
        // onto the source being left would collapse old and new state onto the
        // same (sourceId, mangaUrl) keys.
        if (target.source.id == old.sourceId) {
            return MigrationStatus.Failed("target source is the same as the source being migrated from")
        }

        // Fetch details + chapters from the target. Cancellable, off the UI thread.
        val update = try {
            withContext(Dispatchers.IO) {
                target.source.getMangaUpdate(match, emptyList(), fetchDetails = true, fetchChapters = true)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return MigrationStatus.Failed(e.message ?: e.toString())
        }
        val newManga = update.manga
        val newChapters = update.chapters

        // Read old state from the DB (names carry the chapter numbers). Read
        // rows are only needed to copy read state; the download path matches
        // against the downloads seam's own records instead.
        val oldReadRows = if (options.includeReadChapters) {
            libraryRepository.readChapterRows(old.sourceId, old.mangaUrl)
        } else {
            emptyList()
        }
        val oldProgress = if (options.includeReadChapters) {
            libraryRepository.progressFor(old.sourceId, old.mangaUrl)
        } else {
            null
        }
        val oldDownloaded = if (options.downloadChapters) {
            downloads.downloadedChapters(old.sourceId, old.mangaUrl)
        } else {
            emptyList()
        }
        val oldCategoryIds = if (options.includeCategories) {
            categoryRepository.categoriesForManga(old.sourceId, old.mangaUrl).map { it.id }
        } else {
            emptyList()
        }

        // Match chapters up front (pure CPU) so the DB phase below stays tight.
        val readMatches = oldReadRows
            .mapNotNull { matchChapter(parseChapterNumber(it.chapterName), it.chapterName, newChapters) }
            .distinctBy { it.url }
        val progressMatch = oldProgress?.let {
            matchChapter(parseChapterNumber(it.chapterName), it.chapterName, newChapters)
        }
        val downloadMatches = oldDownloaded
            .mapNotNull { matchChapter(parseChapterNumber(it.chapterName), it.chapterName, newChapters) }
        val progressCopy = if (oldProgress != null && progressMatch != null) {
            ReadingProgress(
                sourceId = target.source.id,
                mangaUrl = newManga.url,
                chapterUrl = progressMatch.url,
                chapterName = progressMatch.name,
                pageIndex = oldProgress.pageIndex,
                updatedAt = 0L, // applyMigration stamps a fresh timestamp
            )
        } else {
            null
        }

        // DB writes: one atomic repository-level transaction (add + seed +
        // reads + progress + categories + remove-old), finished even if the
        // user cancels mid-way. readMatches/progressCopy/oldCategoryIds are
        // already empty/null when their option is off.
        withContext(NonCancellable + Dispatchers.IO) {
            libraryRepository.applyMigration(
                sourceId = target.source.id,
                packageName = target.packageName,
                jarFileName = target.jarFileName,
                extensionName = target.extensionName,
                mangaUrl = newManga.url,
                title = newManga.title,
                thumbnailUrl = newManga.thumbnail_url,
                author = newManga.author,
                chapters = newChapters,
                readChapters = readMatches,
                progress = progressCopy,
                categoryIds = oldCategoryIds,
                oldSourceId = if (options.deleteFromLibrary) old.sourceId else null,
                oldMangaUrl = if (options.deleteFromLibrary) old.mangaUrl else null,
            )
            if (options.deleteFromLibrary) {
                downloads.deleteAllForManga(old.sourceId, old.mangaUrl)
            }
        }

        // Re-download chapters the user had offline. Network-bound, cancellable;
        // one failed chapter never fails the (already completed) migration.
        if (options.downloadChapters) {
            val wanted = (if (options.includeReadChapters) readMatches else emptyList()) + downloadMatches
            for (chapter in wanted.distinctBy { it.url }) {
                currentCoroutineContext().ensureActive()
                try {
                    downloads.enqueueDownload(target.source, newManga.url, chapter)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // Skip this chapter, keep going.
                }
            }
        }
        return MigrationStatus.Migrated(newManga.title)
    }

    /**
     * Migrates a list of manga with failure isolation: each item gets its own
     * [MigrationResult]; a network error on one item becomes
     * [MigrationStatus.Failed] and the batch continues. Cancellation stops the
     * loop between items and propagates.
     *
     * [resolver] picks the match for each manga from its ranked candidates.
     * The default auto-resolver returns the manga only when exactly one
     * exact-title match exists; otherwise (no exact match, or several) the item
     * is reported as [MigrationStatus.NeedsManualMatch] and left untouched.
     */
    suspend fun migrateBatch(
        entries: List<LibraryManga>,
        target: MigrationTarget,
        options: MigrationOptions,
        onItemStart: (index: Int, total: Int, manga: LibraryManga) -> Unit = { _, _, _ -> },
        onItemResult: (index: Int, total: Int, result: MigrationResult) -> Unit = { _, _, _ -> },
        resolver: suspend (manga: LibraryManga, candidates: List<MigrationCandidate>) -> SManga? =
            { _, candidates -> candidates.singleOrNull { it.exactMatch }?.manga },
    ): List<MigrationResult> {
        val results = mutableListOf<MigrationResult>()
        entries.forEachIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            onItemStart(index, entries.size, entry)
            val status = try {
                val candidates = findCandidates(target.source, entry.title)
                if (candidates.isEmpty()) {
                    MigrationStatus.NoMatch
                } else {
                    resolver(entry, candidates)?.let { match ->
                        migrateManga(entry, target, match, options)
                    } ?: MigrationStatus.NeedsManualMatch
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                MigrationStatus.Failed(e.message ?: e.toString())
            }
            val result = MigrationResult(entry, status)
            results += result
            onItemResult(index, entries.size, result)
        }
        return results
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private const val CHAPTER_NUMBER_TOLERANCE = 0.001

        /** lowercases, trims, collapses internal whitespace. */
        fun normalizeTitle(value: String): String =
            value.trim().lowercase().replace(WHITESPACE, " ")

        /**
         * Extracts a chapter number from a chapter name (e.g. "Chapter 12.5" -> 12.5,
         * "Ch 1" -> 1). Same heuristics as MangaDetailScreen's chapter filter.
         */
        fun parseChapterNumber(name: String): Double? {
            val match = Regex("""[Cc]h(?:apter|\.)?\s*([\d.]+)""").find(name)
                ?: Regex("""\b([\d]+(?:\.[\d]+)?)\b""").find(name)
            return match?.groupValues?.get(1)?.toDoubleOrNull()
        }

        /** 0 = exact, 1 = startsWith, 2 = contains, 3 = rest (by source order). */
        internal fun rankTier(needle: String, candidate: String): Int = when {
            candidate == needle -> 0
            candidate.startsWith(needle) -> 1
            candidate.contains(needle) -> 2
            else -> 3
        }

        /**
         * Finds the new-source chapter for an old chapter: same chapter number
         * (±[CHAPTER_NUMBER_TOLERANCE]) first. A *numbered* old chapter that
         * misses by number only matches an exactly equal normalized name -- a
         * contains-check would let "Ch 2" match "Chapter 20". Unnumbered old
         * chapters (no parsable number) fall back to normalized name contains,
         * their only available signal.
         */
        internal fun matchChapter(oldNumber: Double?, oldName: String, newChapters: List<SChapter>): SChapter? {
            val needle = normalizeTitle(oldName)
            if (oldNumber != null) {
                newChapters.firstOrNull { abs(it.chapter_number.toDouble() - oldNumber) <= CHAPTER_NUMBER_TOLERANCE }
                    ?.let { return it }
                if (needle.isNotEmpty()) {
                    newChapters.firstOrNull { normalizeTitle(it.name) == needle }?.let { return it }
                }
            } else if (needle.isNotEmpty()) {
                newChapters.firstOrNull { normalizeTitle(it.name).contains(needle) }?.let { return it }
            }
            return null
        }
    }
}
