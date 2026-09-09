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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.drop
import mihon.desktop.app.i18n.t
import mihon.desktop.app.ui.AsyncImage
import mihon.desktop.loader.download.DownloadManager

/**
 * Webtoon / vertical-scroll mode: all pages stacked vertically in a [LazyColumn],
 * each rendered at full width. No zoom — the user scrolls naturally.
 *
 * @param onPageChanged called with the first visible page index as the user scrolls,
 *   so the parent can persist reading progress.
 * @param onAtEnd called once when the user scrolls to the very last page of the
 *   chapter, so the parent can navigate to the next chapter.
 */
@Composable
internal fun WebtoonView(
    pages: List<Page>,
    httpSource: HttpSource?,
    downloadManager: DownloadManager,
    sourceId: Long,
    isOffline: Boolean,
    chapterUrl: String,
    onPageChanged: (Int) -> Unit,
    onAtEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Track scroll position and report the visible page index to the parent
    // so reading progress is saved in webtoon mode too.
    LaunchedEffect(Unit) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (index in pages.indices) {
                    onPageChanged(index)
                }
            }
    }

    // Detect when the user scrolls to the very last page and fire onAtEnd.
    // Skip the initial emission (drop(1)) so we don't trigger onAtEnd when
    // restoring a saved position near the end on chapter open.
    if (pages.isNotEmpty()) {
        LaunchedEffect(Unit) {
            snapshotFlow {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val totalItems = listState.layoutInfo.totalItemsCount
                lastVisible == totalItems - 1
            }
                .drop(1) // skip initial snapshot
                .collect { atEnd ->
                    if (atEnd) onAtEnd()
                }
        }
    }

    LazyColumn(state = listState, modifier = modifier) {
        itemsIndexed(pages) { index, page ->
            AsyncImage(
                key = "$chapterUrl:${page.url}:${page.index}",
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
