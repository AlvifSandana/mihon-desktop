package mihon.desktop.app.ui

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import mihon.desktop.loader.catalog.CatalogExtension

/** Simple back-stack-free navigation: the app only ever shows one of these at a time. */
sealed interface Screen {
    data object Catalog : Screen
    data class SourceBrowse(val extension: CatalogExtension, val source: Source) : Screen
    data class MangaDetail(val source: Source, val manga: SManga) : Screen
    data class Reader(val source: Source, val manga: SManga, val chapters: List<SChapter>, val chapterIndex: Int) : Screen
}
