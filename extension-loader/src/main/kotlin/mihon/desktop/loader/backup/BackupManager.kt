package mihon.desktop.loader.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.desktop.loader.library.LibraryDatabase
import mihon.desktop.loader.library.MihonDesktopDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports and imports the library database as a JSON file.
 *
 * The backup contains all library manga entries, reading progress, and downloaded
 * chapter metadata (not the actual page files). The JSON is human-readable and
 * versioned with a `version` field for forward compatibility.
 *
 * Backups are stored under `~/.mihon-desktop/backups/` with timestamped filenames.
 */
class BackupManager(database: MihonDesktopDatabase = LibraryDatabase.get()) {
    private val libraryQueries = database.libraryMangaQueries
    private val progressQueries = database.readingProgressQueries
    private val downloadQueries = database.downloadedChapterQueries
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val backupsDir = File(System.getProperty("user.home"), ".mihon-desktop/backups")

    /**
     * Export the current library to a JSON file.
     * Returns the path to the created backup file.
     */
    suspend fun exportBackup(): File = withContext(Dispatchers.IO) {
        backupsDir.mkdirs()

        val libraryManga = libraryQueries.selectAll().executeAsList().map { entry ->
            BackupLibraryManga(
                sourceId = entry.sourceId,
                packageName = entry.packageName,
                jarFileName = entry.jarFileName,
                extensionName = entry.extensionName,
                mangaUrl = entry.mangaUrl,
                title = entry.title,
                thumbnailUrl = entry.thumbnailUrl,
                author = entry.author,
                addedAt = entry.addedAt,
            )
        }

        val readingProgress = mutableMapOf<String, BackupReadingProgress>()
        for (entry in libraryManga) {
            val progress = progressQueries.selectForManga(entry.sourceId, entry.mangaUrl).executeAsOneOrNull()
            if (progress != null) {
                val key = "${entry.sourceId}:${entry.mangaUrl}"
                readingProgress[key] = BackupReadingProgress(
                    sourceId = progress.sourceId,
                    mangaUrl = progress.mangaUrl,
                    chapterUrl = progress.chapterUrl,
                    chapterName = progress.chapterName,
                    pageIndex = progress.pageIndex,
                    updatedAt = progress.updatedAt,
                )
            }
        }

        val downloadedChapters = mutableMapOf<String, List<BackupDownloadedChapter>>()
        for (entry in libraryManga) {
            val downloads = downloadQueries.selectForManga(entry.sourceId, entry.mangaUrl).executeAsList()
            if (downloads.isNotEmpty()) {
                val key = "${entry.sourceId}:${entry.mangaUrl}"
                downloadedChapters[key] = downloads.map { dl ->
                    BackupDownloadedChapter(
                        sourceId = dl.sourceId,
                        mangaUrl = dl.mangaUrl,
                        chapterUrl = dl.chapterUrl,
                        chapterName = dl.chapterName,
                        pageCount = dl.pageCount,
                        downloadedAt = dl.downloadedAt,
                    )
                }
            }
        }

        val backup = BackupData(
            version = BACKUP_VERSION,
            exportedAt = System.currentTimeMillis(),
            libraryManga = libraryManga,
            readingProgress = readingProgress,
            downloadedChapters = downloadedChapters,
        )

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(backupsDir, "mihon-desktop-backup-$timestamp.json")
        file.writeText(json.encodeToString(BackupData.serializer(), backup))
        file
    }

    /**
     * Import a backup from a JSON file. Existing data is merged (not replaced).
     * Returns the number of manga entries imported.
     */
    suspend fun importBackup(file: File): Int = withContext(Dispatchers.IO) {
        val data = json.decodeFromString(BackupData.serializer(), file.readText())

        for (manga in data.libraryManga) {
            libraryQueries.insertOrReplace(
                sourceId = manga.sourceId,
                packageName = manga.packageName,
                jarFileName = manga.jarFileName,
                extensionName = manga.extensionName,
                mangaUrl = manga.mangaUrl,
                title = manga.title,
                thumbnailUrl = manga.thumbnailUrl,
                author = manga.author,
                addedAt = manga.addedAt,
            )
        }

        for ((_, progress) in data.readingProgress) {
            progressQueries.upsert(
                sourceId = progress.sourceId,
                mangaUrl = progress.mangaUrl,
                chapterUrl = progress.chapterUrl,
                chapterName = progress.chapterName,
                pageIndex = progress.pageIndex,
                updatedAt = progress.updatedAt,
            )
        }

        for ((_, downloads) in data.downloadedChapters) {
            for (dl in downloads) {
                downloadQueries.insertOrReplace(
                    sourceId = dl.sourceId,
                    mangaUrl = dl.mangaUrl,
                    chapterUrl = dl.chapterUrl,
                    chapterName = dl.chapterName,
                    pageCount = dl.pageCount,
                    downloadedAt = dl.downloadedAt,
                )
            }
        }

        data.libraryManga.size
    }

    /**
     * List available backup files, newest first.
     */
    fun listBackups(): List<File> {
        backupsDir.mkdirs()
        return backupsDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" && it.name.startsWith("mihon-desktop-backup") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    companion object {
        private const val BACKUP_VERSION = 1
    }
}

@Serializable
data class BackupData(
    val version: Int,
    val exportedAt: Long,
    val libraryManga: List<BackupLibraryManga>,
    val readingProgress: Map<String, BackupReadingProgress>,
    val downloadedChapters: Map<String, List<BackupDownloadedChapter>>,
)

@Serializable
data class BackupLibraryManga(
    val sourceId: Long,
    val packageName: String,
    val jarFileName: String,
    val extensionName: String,
    val mangaUrl: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val author: String? = null,
    val addedAt: Long,
)

@Serializable
data class BackupReadingProgress(
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
    val chapterName: String,
    val pageIndex: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupDownloadedChapter(
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
    val chapterName: String,
    val pageCount: Long,
    val downloadedAt: Long,
)
