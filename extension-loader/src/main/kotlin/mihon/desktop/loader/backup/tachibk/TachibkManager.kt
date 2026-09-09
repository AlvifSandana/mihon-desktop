package mihon.desktop.loader.backup.tachibk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.loader.ExtensionLoader
import mihon.desktop.loader.log.Logger
import mihon.desktop.loader.prefs.AppPreferences
import mihon.desktop.loader.library.LibraryDatabase
import mihon.desktop.loader.library.LibraryManga
import mihon.desktop.loader.library.MihonDesktopDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "TachibkManager"

/** A source resolvable to an installed extension jar (or an existing library row). */
data class InstalledSource(
    val sourceId: Long,
    val name: String,
    val packageName: String,
    val jarFileName: String,
    val extensionName: String,
)

/**
 * Summary of a `.tachibk` import. Raw counts only — presentation belongs to
 * the app layer (extension-loader has no string table), see the Settings
 * screen's `tachibk_summary*` keys.
 */
data class TachibkImportResult(
    val imported: Int,
    val updated: Int,
    val skipped: Int,
    val readChapters: Int,
    val categoriesCreated: Int,
)

/**
 * Mihon `.tachibk` interop: imports Android Mihon backups into the desktop
 * library and exports the desktop library as a Mihon-compatible backup.
 *
 * ## Import merge semantics (mirrors [mihon.desktop.loader.backup.BackupManager.importBackup])
 * - A manga is matched by `(sourceId, url)`. Existing rows are updated in
 *   place via `updateMeta` (row id + `addedAt` preserved, so mangaCategory
 *   mappings never orphan); new rows are inserted with the backup's
 *   `dateAdded` (epoch ms) as `addedAt`.
 * - Chapters with `read = true` become `readChapters` rows; `readAt` comes
 *   from the backup's per-chapter history (`lastRead`, epoch ms), falling
 *   back to `dateFetch`, then to now. Merge never regresses: an existing
 *   row keeps `readAt = max(existing, backup)` (Mihon max() semantics).
 * - `readingProgress` (one row per manga) is upserted only when the backup
 *   actually contains read state AND is at least as new as the local row:
 *   the target chapter is the read chapter with the most recent history
 *   timestamp (falling back to the last read chapter by `sourceOrder`),
 *   with `lastPageRead` as the page index — or the local page index when
 *   the backup's is 0/absent. A stale backup never overwrites newer local
 *   progress.
 * - Categories are matched by exact name (same as Mihon's CategoriesRestorer);
 *   missing ones are created. Manga category assignments (category *order*
 *   values in the backup) replace the manga's current mappings, but only
 *   when the backup lists at least one category for that manga — a backup
 *   without category data leaves local mappings untouched.
 *
 * ## Skipped sources
 * Our `libraryManga` schema needs `packageName`/`jarFileName`/`extensionName`
 * (NOT NULL) to reopen a row — every screen resolves the source by loading
 * the jar by name. Rows whose `sourceId` cannot be resolved to (a) an
 * installed extension's source or (b) an existing library row with the same
 * sourceId are **skipped, not imported as zombies**: a row with empty strings
 * would show in the library but could never load its source, refresh, or be
 * read. Skipped manga are recoverable by installing the extension and
 * re-importing. The count is reported to the user.
 *
 * ## Export
 * Serializes all library manga, their read chapters + history timestamps,
 * reading progress, categories and assignments, plus a `backupSources` list
 * of the distinct sources referenced. Output is structurally valid for
 * Android Mihon (field numbers verified, see [TachibkModels]) — chapters
 * outside our data model (unread chapters, tracking, preferences) are simply
 * absent, which Mihon tolerates.
 */
class TachibkManager(
    private val database: MihonDesktopDatabase = LibraryDatabase.get(),
    /**
     * Seam for tests: sourceId → installed source. Default scans the
     * extension cache dir and loads every jar (cached by [ExtensionLoader];
     * broken jars are skipped).
     */
    private val installedSourcesProvider: () -> Map<Long, InstalledSource> = ::scanInstalledSources,
    /**
     * Notified after every successful import/export so the auto-backup clock
     * resets. Default writes the [AppPreferences] timestamp; tests pass a no-op.
     */
    private val onBackupActivity: () -> Unit = {
        runCatching { AppPreferences.setLong(AppPreferences.KEY_AUTO_BACKUP_LAST_AT, System.currentTimeMillis()) }
    },
) {
    private val libraryQueries = database.libraryMangaQueries
    private val progressQueries = database.readingProgressQueries
    private val readChapterQueries = database.readChapterQueries
    private val categoryQueries = database.categoryQueries
    private val mangaCategoryQueries = database.mangaCategoryQueries

    private val backupsDir = File(System.getProperty("user.home"), ".mihon-desktop/backups")

    // ── Import ──────────────────────────────────────────────────────────

    /**
     * Imports a `.tachibk` file (gzipped or raw protobuf). Existing data is
     * merged, never replaced. Throws [TachibkFormatException] for malformed
     * input and [TachibkGzip.BackupSizeExceededException] for oversize
     * payloads.
     */
    suspend fun import(file: File): TachibkImportResult = withContext(Dispatchers.IO) {
        val payload = TachibkGzip.readBackupPayload(file)
        val backup = TachibkCodec.decode(payload)
        importBackup(backup)
    }

    /** The merge, split out so tests can feed a crafted [TachibkBackup] directly. */
    fun importBackup(backup: TachibkBackup): TachibkImportResult {
        val now = System.currentTimeMillis()
        val installedSources = runCatching(installedSourcesProvider)
            .onFailure { Logger.w(TAG, "Failed to scan installed sources: ${it.message}") }
            .getOrDefault(emptyMap())

        var imported = 0
        var updated = 0
        var skipped = 0
        var chaptersRead = 0
        var categoriesCreated = 0

        database.transaction {
            // 1. Categories: match by exact name (Mihon semantics), create the
            //    missing ones. order → name is resolved per manga below.
            val existingCategories = categoryQueries.selectAll().executeAsList()
            val nameToId = HashMap<String, Long>(existingCategories.size + backup.backupCategories.size)
            for (c in existingCategories) nameToId[c.name] = c.id
            val orderByCategory = HashMap<Long, TachibkCategory>(backup.backupCategories.size)
            for (bc in backup.backupCategories) {
                orderByCategory[bc.order] = bc
                if (!nameToId.containsKey(bc.name)) {
                    // Divergence from Mihon: CategoriesRestorer creates missing
                    // categories with order = max(order)+1; we reuse the backup's
                    // order value. Harmless — category listing is ORDER BY
                    // sortOrder, id, so ordering stays stable either way.
                    categoryQueries.insert(bc.name, bc.order)
                    // UNIQUE(name): a concurrent create loses the race and
                    // selectByName still resolves the winner's row.
                    val id = categoryQueries.selectByName(bc.name).executeAsOneOrNull()?.id
                    if (id != null) {
                        nameToId[bc.name] = id
                        categoriesCreated++
                    }
                }
            }

            // 2. Source resolution for rows the installed extensions can't
            //    provide: existing library rows with the same sourceId keep
            //    importing (their jar/package refs are already good).
            val existingRefsBySource = HashMap<Long, Triple<String, String, String>>()
            for (row in libraryQueries.selectAll().executeAsList()) {
                if (row.sourceId !in existingRefsBySource) {
                    existingRefsBySource[row.sourceId] = Triple(row.packageName, row.jarFileName, row.extensionName)
                }
            }

            for (m in backup.backupManga) {
                val ref: Triple<String, String, String>? =
                    installedSources[m.source]?.let { Triple(it.packageName, it.jarFileName, it.extensionName) }
                        ?: existingRefsBySource[m.source]
                if (ref == null) {
                    skipped++
                    continue
                }
                val (packageName, jarFileName, extensionName) = ref

                val existing = libraryQueries.selectOne(m.source, m.url).executeAsOneOrNull()
                if (existing != null) {
                    libraryQueries.updateMeta(
                        packageName = packageName,
                        jarFileName = jarFileName,
                        extensionName = extensionName,
                        title = m.title.ifEmpty { existing.title },
                        thumbnailUrl = m.thumbnailUrl ?: existing.thumbnailUrl,
                        author = m.author ?: existing.author,
                        id = existing.id,
                    )
                    updated++
                } else {
                    libraryQueries.insertOrReplace(
                        sourceId = m.source,
                        packageName = packageName,
                        jarFileName = jarFileName,
                        extensionName = extensionName,
                        mangaUrl = m.url,
                        title = m.title,
                        thumbnailUrl = m.thumbnailUrl,
                        author = m.author ?: m.artist,
                        addedAt = if (m.dateAdded > 0) m.dateAdded else now,
                    )
                    imported++
                }

                // 3. Read chapters + history timestamps. Mihon merge semantics:
                // never regress — readAt keeps max(existing, backup).
                val historyByChapter = HashMap<String, Long>(m.history.size)
                for (h in m.history) {
                    if (h.lastRead > (historyByChapter[h.chapterUrl] ?: 0L)) {
                        historyByChapter[h.chapterUrl] = h.lastRead
                    }
                }
                val readChapters = m.chapters.filter { it.read }
                for (ch in readChapters) {
                    val existingRead = readChapterQueries.selectOne(m.source, ch.url).executeAsOneOrNull()
                    val backupReadAt = historyByChapter[ch.url]
                        ?: ch.dateFetch.takeIf { it > 0 }
                        ?: now
                    val readAt = if (existingRead != null) maxOf(existingRead.readAt, backupReadAt) else backupReadAt
                    readChapterQueries.insertOrReplace(
                        sourceId = m.source,
                        mangaUrl = m.url,
                        chapterUrl = ch.url,
                        chapterName = ch.name,
                        readAt = readAt,
                    )
                    // Count only genuinely new rows: re-importing the same
                    // backup must not inflate the "chapters read" summary.
                    if (existingRead == null) chaptersRead++
                }

                // 4. Reading progress: the chapter the reader should resume
                //    at. Prefer the most recently read chapter by history
                //    timestamp; fall back to the last read chapter in source
                //    order. Never clobber local progress with nothing, and
                //    never regress: the backup wins only when its history
                //    timestamp is >= the local row's updatedAt (Mihon max()
                //    semantics; local null = fresh manga, backup wins).
                val localProgress = progressQueries.selectForManga(m.source, m.url).executeAsOneOrNull()
                val progressChapter = readChapters
                    .filter { (historyByChapter[it.url] ?: 0L) > 0L }
                    .maxByOrNull { historyByChapter[it.url]!! }
                    ?: readChapters.maxByOrNull { it.sourceOrder }
                if (progressChapter != null) {
                    val backupUpdatedAt = historyByChapter[progressChapter.url]
                        ?: progressChapter.dateFetch.takeIf { it > 0 }
                        ?: now
                    if (localProgress == null || backupUpdatedAt >= localProgress.updatedAt) {
                        progressQueries.upsert(
                            sourceId = m.source,
                            mangaUrl = m.url,
                            chapterUrl = progressChapter.url,
                            chapterName = progressChapter.name,
                            // Backup page wins only when it actually has one
                            // (0 = absent on the wire); otherwise keep local.
                            pageIndex = if (progressChapter.lastPageRead > 0) {
                                progressChapter.lastPageRead
                            } else {
                                localProgress?.pageIndex ?: 0L
                            },
                            updatedAt = backupUpdatedAt,
                        )
                    }
                }

                // 5. Category assignments (order values → category by name).
                if (m.categories.isNotEmpty()) {
                    libraryQueries.selectOne(m.source, m.url).executeAsOneOrNull()?.let { row ->
                        mangaCategoryQueries.deleteForManga(row.id)
                        for (order in m.categories.distinct()) {
                            val name = orderByCategory[order]?.name ?: continue
                            val categoryId = nameToId[name] ?: continue
                            mangaCategoryQueries.insertOrIgnore(row.id, categoryId)
                        }
                    }
                }
            }
        }

        markBackupActivity()
        return TachibkImportResult(imported, updated, skipped, chaptersRead, categoriesCreated)
    }

    // ── Export ──────────────────────────────────────────────────────────

    /**
     * Exports the library as a Mihon-compatible gzipped `.tachibk`.
     * Returns the file written; [target] null → default backups dir.
     *
     * @throws IllegalStateException when the library is empty — mirroring
     * Mihon's BackupCreator, which refuses to write a backup containing no
     * manga (an empty `backupManga` list is absent on the wire, and Android
     * Mihon rejects a Backup without it).
     */
    suspend fun export(target: File? = null): File = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val installedSources = runCatching(installedSourcesProvider)
            .onFailure { Logger.w(TAG, "Failed to scan installed sources for export: ${it.message}") }
            .getOrDefault(emptyMap())

        val categories = categoryQueries.selectAll().executeAsList()
        val categoryIdsByManga = HashMap<Long, List<Long>>()
        for (mapping in mangaCategoryQueries.selectMangaIdsByCategory().executeAsList()) {
            categoryIdsByManga[mapping.mangaId] = (categoryIdsByManga[mapping.mangaId] ?: emptyList()) + mapping.categoryId
        }
        val categoryOrderById = categories.associate { it.id to it.sortOrder }

        val mangaRows = libraryQueries.selectAll().executeAsList()
        if (mangaRows.isEmpty()) {
            throw IllegalStateException("Nothing to back up — library is empty")
        }
        val backupManga = mangaRows.map { row ->
            val progress = progressQueries.selectForManga(row.sourceId, row.mangaUrl).executeAsOneOrNull()
            val readRows = readChapterQueries.selectForManga(row.sourceId, row.mangaUrl).executeAsList()
            TachibkManga(
                source = row.sourceId,
                url = row.mangaUrl,
                title = row.title,
                author = row.author,
                status = 0,
                thumbnailUrl = row.thumbnailUrl,
                dateAdded = row.addedAt,
                favorite = true,
                chapters = readRows.mapIndexed { index, rc ->
                    // Subset caveats (accepted): `sourceOrder` here is READ
                    // order (the readChapters row order), not the source's
                    // chapter order, and `chapterNumber` stays 0 — we only
                    // persist read chapters, and Mihon re-fetches the real
                    // chapter list on restore anyway.
                    TachibkChapter(
                        url = rc.chapterUrl,
                        name = rc.chapterName,
                        read = true,
                        lastPageRead = if (progress?.chapterUrl == rc.chapterUrl) progress.pageIndex else 0L,
                        sourceOrder = index.toLong(),
                    )
                },
                history = readRows.map { rc ->
                    // lastRead must never be 0: proto3 default omission would
                    // drop the field, and Mihon's BackupHistory has no default
                    // for it — its restore errors out per-manga. A readAt of 0
                    // (legacy row) is stamped with the export time instead.
                    TachibkHistory(chapterUrl = rc.chapterUrl, lastRead = if (rc.readAt > 0) rc.readAt else now)
                },
                categories = (categoryIdsByManga[row.id] ?: emptyList())
                    .mapNotNull { categoryOrderById[it] },
            )
        }

        // O(n) source-row lookup: one representative row per distinct sourceId
        // (first wins — selectAll orders by addedAt DESC, same row the
        // previous `mangaRows.first { it.sourceId == ... }` picked).
        val rowBySourceId = HashMap<Long, LibraryManga>()
        for (row in mangaRows) rowBySourceId.putIfAbsent(row.sourceId, row)

        val backup = TachibkBackup(
            backupManga = backupManga,
            backupCategories = categories.map { TachibkCategory(name = it.name, order = it.sortOrder) },
            backupSources = mangaRows
                .map { it.sourceId }
                .distinct()
                .map { sourceId ->
                    val row = rowBySourceId.getValue(sourceId)
                    TachibkSource(
                        name = installedSources[sourceId]?.name ?: row.extensionName,
                        sourceId = sourceId,
                    )
                },
        )

        val bytes = TachibkGzip.gzip(TachibkCodec.encode(backup))
        val file = target ?: defaultExportFile()
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)

        markBackupActivity()
        file
    }

    private fun defaultExportFile(): File {
        backupsDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(backupsDir, "mihon-desktop-$timestamp.tachibk")
    }

    /** Manual export/import actions refresh the auto-backup clock too. */
    private fun markBackupActivity() {
        runCatching(onBackupActivity)
    }

    companion object {
        /**
         * Scans the extension cache for installed jars and maps every source
         * they provide. Broken/unloadable jars are skipped (logged at debug);
         * this feeds import source resolution and export source names.
         */
        fun scanInstalledSources(): Map<Long, InstalledSource> {
            val dir = ExtensionLoader.extensionCacheDir
            val jars = dir.listFiles { f -> f.isFile && f.name.endsWith(".jar") } ?: return emptyMap()
            val result = HashMap<Long, InstalledSource>()
            for (jar in jars) {
                val loaded = runCatching { ExtensionLoader.load(jar) }.getOrNull() ?: continue
                for (source in loaded.sources) {
                    result[source.id] = InstalledSource(
                        sourceId = source.id,
                        name = source.name,
                        packageName = loaded.metadata.packageName,
                        jarFileName = jar.name,
                        extensionName = loaded.metadata.name ?: jar.name,
                    )
                }
            }
            return result
        }
    }
}
