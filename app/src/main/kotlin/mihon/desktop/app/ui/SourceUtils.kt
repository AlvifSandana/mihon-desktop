package mihon.desktop.app.ui

import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.desktop.app.i18n.Strings
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
        diff < TimeUnit.MINUTES.toMillis(1) -> Strings.get("time_just_now")
        diff < TimeUnit.HOURS.toMillis(1) -> Strings.get("time_minutes_ago", diff / TimeUnit.MINUTES.toMillis(1))
        diff < TimeUnit.DAYS.toMillis(1) -> Strings.get("time_hours_ago", diff / TimeUnit.HOURS.toMillis(1))
        diff < TimeUnit.DAYS.toMillis(7) -> Strings.get("time_days_ago", diff / TimeUnit.DAYS.toMillis(1))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}

/** Bucket label for date-grouped lists: Today / Yesterday / This week / "MMM d, yyyy". */
internal fun formatDateGroupLabel(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < TimeUnit.DAYS.toMillis(1) -> Strings.get("date_today")
        diff < TimeUnit.DAYS.toMillis(2) -> Strings.get("date_yesterday")
        diff < TimeUnit.DAYS.toMillis(7) -> Strings.get("date_this_week")
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

/**
 * [formatRelativeTime] for composable call sites: reading
 * [Strings.languageTick] subscribes the calling recompose scope, so the label
 * re-renders on a live language swap even when the item's own inputs (the
 * entry) are unchanged.
 */
@Composable
internal fun relativeTimeLabel(timestamp: Long): String {
    Strings.languageTick.intValue
    return formatRelativeTime(timestamp)
}

/**
 * [formatDateGroupLabel] for direct composable use (see [relativeTimeLabel]).
 * For `groupBy` grouping computed inside `remember`, read the tick in
 * composition and add it to the remember keys instead — composable calls
 * aren't allowed inside the (non-composable) calculation lambda:
 * `val tick = Strings.languageTick.intValue; remember(entries, tick) { … }`
 */
@Composable
internal fun dateGroupLabel(timestamp: Long): String {
    Strings.languageTick.intValue
    return formatDateGroupLabel(timestamp)
}
