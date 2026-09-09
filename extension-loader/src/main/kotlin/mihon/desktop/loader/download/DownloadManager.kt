package mihon.desktop.loader.download

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import mihon.desktop.loader.library.LibraryDatabase
import mihon.desktop.loader.library.MihonDesktopDatabase
import mihon.desktop.loader.library.DownloadedChapters
import java.io.File

/**
 * Manages downloading chapter pages to local disk for offline reading.
 *
 * Pages are stored under `~/.mihon-desktop/downloads/<sourceId>/<chapterUrlHash>/`
 * with files named `0000.jpg`, `0001.jpg`, etc. The chapter's page count is tracked
 * in the `downloadedChapters` table so the reader can check availability without
 * hitting the network.
 */
class DownloadManager(database: MihonDesktopDatabase = LibraryDatabase.get()) {
    private val downloadQueries = database.downloadedChapterQueries
    private val downloadsDir = File(System.getProperty("user.home"), ".mihon-desktop/downloads")

    /**
     * Download all pages for [chapter] from [source] and store them on disk.
     * Returns the number of pages downloaded, or throws on failure.
     *
     * Cancellation is cooperative: the loop checks `ensureActive()` between
     * pages (the in-flight page's OkHttp call is cancelled by coroutine
     * cancellation), so a cancelled caller stops at the next page boundary.
     * Pages already written are kept -- a re-run skips them via the
     * file-exists check below. This is what lets [DownloadQueue] pause/resume.
     *
     * @param onProgress optional callback invoked with (currentPage, totalPages) as each page downloads
     */
    suspend fun downloadChapter(
        source: Source,
        mangaUrl: String,
        chapter: SChapter,
        onProgress: (suspend (current: Int, total: Int) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        val httpSource = source as? HttpSource
            ?: throw IllegalArgumentException("Source ${source.name} is not an HttpSource")

        val pages = httpSource.getPageList(chapter)
        val chapterDir = chapterDir(source.id, chapter.url)
        chapterDir.mkdirs()

        var downloaded = 0
        for ((index, page) in pages.withIndex()) {
            ensureActive()
            val pageFile = pageFile(chapterDir, index)
            if (pageFile.exists() && pageFile.length() > 0) {
                downloaded++
                onProgress?.invoke(downloaded, pages.size)
                continue
            }

            if (page.imageUrl == null) {
                page.imageUrl = runCatching { httpSource.getImageUrl(page) }.getOrNull()
            }
            val bytes = httpSource.getImage(page).use { response -> response.body.bytes() }
            pageFile.writeBytes(bytes)
            downloaded++
            onProgress?.invoke(downloaded, pages.size)
        }

        downloadQueries.insertOrReplace(
            sourceId = source.id,
            mangaUrl = mangaUrl,
            chapterUrl = chapter.url,
            chapterName = chapter.name,
            pageCount = pages.size.toLong(),
            downloadedAt = System.currentTimeMillis(),
        )

        pages.size
    }

    /**
     * Check if [chapter] is downloaded (all pages exist on disk).
     */
    suspend fun isChapterDownloaded(sourceId: Long, chapterUrl: String): Boolean = withContext(Dispatchers.IO) {
        val record = downloadQueries.selectOne(sourceId, chapterUrl).executeAsOneOrNull()
            ?: return@withContext false
        val dir = chapterDir(sourceId, chapterUrl)
        // Verify actual files exist and count matches
        val jpgFiles = dir.listFiles()?.filter { file -> file.isFile && file.extension == "jpg" } ?: emptyList()
        jpgFiles.size >= record.pageCount.toInt()
    }

    /**
     * Get the number of downloaded chapters for a manga.
     */
    suspend fun downloadedCount(sourceId: Long, mangaUrl: String): Int = withContext(Dispatchers.IO) {
        downloadQueries.selectForManga(sourceId, mangaUrl).executeAsList().size
    }

    /**
     * Get the page count for a specific downloaded chapter.
     * Returns null if the chapter is not in the download database.
     */
    suspend fun getChapterPageCount(sourceId: Long, chapterUrl: String): Int? = withContext(Dispatchers.IO) {
        downloadQueries.selectOne(sourceId, chapterUrl).executeAsOneOrNull()?.pageCount?.toInt()
    }

    /**
     * Get all downloaded chapter URLs for a manga.
     */
    suspend fun downloadedChapters(sourceId: Long, mangaUrl: String): List<String> = withContext(Dispatchers.IO) {
        downloadQueries.selectForManga(sourceId, mangaUrl).executeAsList().map { record -> record.chapterUrl }
    }

    /**
     * Full downloaded-chapter records (url, name, pageCount, ...) for a manga.
     * Migration needs the chapter names to match old downloads on the new source.
     */
    suspend fun downloadedChapterRecords(sourceId: Long, mangaUrl: String): List<DownloadedChapters> =
        withContext(Dispatchers.IO) {
            downloadQueries.selectForManga(sourceId, mangaUrl).executeAsList()
        }

    /**
     * Read a downloaded page's bytes from disk.
     * Returns null if not downloaded or file missing.
     */
    suspend fun readPage(sourceId: Long, chapterUrl: String, pageIndex: Int): ByteArray? = withContext(Dispatchers.IO) {
        val file = pageFile(chapterDir(sourceId, chapterUrl), pageIndex)
        if (file.exists() && file.length() > 0) file.readBytes() else null
    }

    /**
     * Delete a downloaded chapter's files and database record.
     */
    suspend fun deleteChapter(sourceId: Long, chapterUrl: String) = withContext(Dispatchers.IO) {
        val dir = chapterDir(sourceId, chapterUrl)
        dir.listFiles()?.forEach { file -> file.delete() }
        dir.delete()
        downloadQueries.delete(sourceId, chapterUrl)
    }

    /**
     * Delete all downloaded chapters for a manga.
     */
    suspend fun deleteAllForManga(sourceId: Long, mangaUrl: String) = withContext(Dispatchers.IO) {
        val chapters = downloadQueries.selectForManga(sourceId, mangaUrl).executeAsList()
        for (record in chapters) {
            val dir = chapterDir(sourceId, record.chapterUrl)
            dir.listFiles()?.forEach { file -> file.delete() }
            dir.delete()
        }
        downloadQueries.deleteAllForManga(sourceId, mangaUrl)
    }

    /**
     * Total bytes used by downloads for a manga.
     */
    fun diskUsage(sourceId: Long, mangaUrl: String): Long {
        val chapters = downloadQueries.selectForManga(sourceId, mangaUrl).executeAsList()
        return chapters.sumOf { record ->
            val dir = chapterDir(sourceId, record.chapterUrl)
            dir.listFiles()?.sumOf { file -> file.length() } ?: 0L
        }
    }

    /**
     * Get all downloaded chapters across all manga.
     */
    suspend fun allDownloads(): List<DownloadedChapters> = withContext(Dispatchers.IO) {
        downloadQueries.selectAll().executeAsList()
    }

    /**
     * Total bytes used by all downloads.
     */
    fun totalDiskUsage(): Long {
        return downloadsDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /**
     * Delete all downloads.
     */
    suspend fun deleteAllDownloads() = withContext(Dispatchers.IO) {
        downloadsDir.listFiles()?.forEach { dir ->
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
        downloadQueries.deleteAll()
    }

    private fun chapterDir(sourceId: Long, chapterUrl: String): File {
        val hash = chapterUrl.hashCode().toUInt().toString(16)
        return File(downloadsDir, "$sourceId/$hash")
    }

    private fun pageFile(chapterDir: File, pageIndex: Int): File {
        return File(chapterDir, "%04d.jpg".format(pageIndex))
    }
}
