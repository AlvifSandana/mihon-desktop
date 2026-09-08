package mihon.desktop.app.ui

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fetches a manga thumbnail via the source's HTTP client (with its Cloudflare
 * bypass/headers). Shared by Library, Updates, and History screens.
 */
internal fun fetchThumbnailBytes(source: Source?, url: String): ByteArray? {
    val httpSource = source as? HttpSource ?: return null
    return httpSource.client.newCall(GET(url, httpSource.headers)).execute().use { response ->
        if (response.isSuccessful) response.body.bytes() else null
    }
}

/** "just now", "5m ago", "3h ago", "2d ago", or "Mar 3". */
internal fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${diff / TimeUnit.MINUTES.toMillis(1)}m ago"
        diff < TimeUnit.DAYS.toMillis(1) -> "${diff / TimeUnit.HOURS.toMillis(1)}h ago"
        diff < TimeUnit.DAYS.toMillis(7) -> "${diff / TimeUnit.DAYS.toMillis(1)}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}

/** Bucket label for date-grouped lists: Today / Yesterday / This week / "MMM d, yyyy". */
internal fun formatDateGroupLabel(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < TimeUnit.DAYS.toMillis(1) -> "Today"
        diff < TimeUnit.DAYS.toMillis(2) -> "Yesterday"
        diff < TimeUnit.DAYS.toMillis(7) -> "This week"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
