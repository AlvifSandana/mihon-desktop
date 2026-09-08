package mihon.desktop.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Update
import androidx.compose.ui.graphics.vector.ImageVector
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import mihon.desktop.loader.catalog.CatalogExtension

/**
 * Enough to reopen a source later without a catalog fetch: the jar to reload from the
 * local cache, and the package name to display/persist alongside it.
 */
data class ExtensionRef(val packageName: String, val jarFileName: String, val displayName: String)

fun CatalogExtension.toRef() = ExtensionRef(packageName, jarFileName, name)

/** Top-level navigation tabs. */
enum class Tab(val label: String, val icon: ImageVector) {
    Library("Library", Icons.AutoMirrored.Filled.LibraryBooks),
    Updates("Updates", Icons.Filled.Update),
    History("History", Icons.Filled.History),
    Browse("Browse", Icons.Filled.Public),
    More("More", Icons.Filled.MoreHoriz),
}

/** Navigation screens. */
sealed interface Screen {
    // ── Tab screens ────────────────────────────────────────────────────
    data object Library : Screen
    data object Updates : Screen
    data object History : Screen
    data object Browse : Screen
    data object More : Screen

    // ── Detail screens (pushed on top of tabs) ─────────────────────────
    data object Settings : Screen
    data object DownloadManager : Screen
    data object ExtensionManagement : Screen
    data object MultiSourceSearch : Screen
    data object Notifications : Screen
    data class SourceBrowse(val extension: CatalogExtension, val source: Source) : Screen
    data class MangaDetail(
        val extensionRef: ExtensionRef,
        val source: Source,
        val manga: SManga,
        val backTo: Screen,
    ) : Screen
    data class Reader(
        val source: Source,
        val manga: SManga,
        val chapters: List<SChapter>,
        val chapterIndex: Int,
        val initialPageIndex: Int = 0,
        val backTo: Screen,
    ) : Screen
}
