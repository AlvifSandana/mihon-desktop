package mihon.desktop.app.ui

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

/** Simple back-stack-free navigation: the app only ever shows one of these at a time. */
sealed interface Screen {
    data object Library : Screen
    data object Catalog : Screen
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
