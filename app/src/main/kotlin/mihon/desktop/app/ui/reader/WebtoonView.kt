package mihon.desktop.app.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.desktop.app.i18n.t
import mihon.desktop.app.ui.AsyncImage
import mihon.desktop.loader.download.DownloadManager

/**
 * Webtoon / vertical-scroll mode: all pages stacked vertically in a [LazyColumn],
 * each rendered at full width. No zoom — the user scrolls naturally.
 */
@Composable
internal fun WebtoonView(
    pages: List<Page>,
    httpSource: HttpSource?,
    downloadManager: DownloadManager,
    sourceId: Long,
    isOffline: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = modifier) {
        itemsIndexed(pages) { index, page ->
            AsyncImage(
                key = page.url + page.index,
                modifier = Modifier.fillMaxWidth(),
                errorContent = { onRetry ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Text(
                            t("reader_page_failed", index + 1),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRetry) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Text(t("common_retry"))
                        }
                    }
                },
            ) {
                loadPageBytes(downloadManager, httpSource, page, sourceId, isOffline)
            }
        }
    }
}
