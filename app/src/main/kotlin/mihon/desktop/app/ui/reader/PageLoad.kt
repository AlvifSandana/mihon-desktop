package mihon.desktop.app.ui.reader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.desktop.loader.cache.ImageCache
import mihon.desktop.loader.download.DownloadManager

/**
 * Load page bytes: from disk if offline, otherwise from the source's network
 * API (through the shared LRU disk [ImageCache] so revisited pages don't
 * re-download).
 */
internal suspend fun loadPageBytes(
    downloadManager: DownloadManager,
    httpSource: HttpSource?,
    page: Page,
    sourceId: Long,
    isOffline: Boolean,
    cache: ImageCache? = null,
): ByteArray? {
    if (isOffline) {
        val pageIndex = page.url.toIntOrNull() ?: page.index
        return downloadManager.readPage(sourceId, page.url, pageIndex)
    }
    if (httpSource == null) return null
    if (page.imageUrl == null) {
        page.imageUrl = runCatching { httpSource.getImageUrl(page) }.getOrNull()
    }
    val url = page.imageUrl ?: return null
    cache?.let { runCatching { it.get(url) }.getOrNull() }?.let { return it }
    val bytes = runCatching { httpSource.getImage(page).use { it.body.bytes() } }.getOrNull() ?: return null
    cache?.let { runCatching { it.put(url, bytes) } }
    return bytes
}
