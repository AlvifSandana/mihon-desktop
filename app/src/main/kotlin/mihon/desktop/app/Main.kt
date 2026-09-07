package mihon.desktop.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import mihon.desktop.app.ui.CatalogScreen
import mihon.desktop.app.ui.LibraryScreen
import mihon.desktop.app.ui.MangaDetailScreen
import mihon.desktop.app.ui.ReaderScreen
import mihon.desktop.app.ui.Screen
import mihon.desktop.app.ui.SourceBrowseScreen
import mihon.desktop.app.ui.toRef
import mihon.desktop.loader.DesktopExtensionRuntime

fun main() {
    DesktopExtensionRuntime.bootstrap()

    application {
        Window(onCloseRequest = ::exitApplication, title = "Mihon Desktop") {
            MaterialTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.Library) }

                when (val current = screen) {
                    is Screen.Library -> LibraryScreen(
                        onOpenManga = { extensionRef, source, manga ->
                            screen = Screen.MangaDetail(extensionRef, source, manga, backTo = Screen.Library)
                        },
                        onBrowseExtensions = { screen = Screen.Catalog },
                    )

                    is Screen.Catalog -> CatalogScreen(
                        onSourceSelected = { extension, source ->
                            screen = Screen.SourceBrowse(extension, source)
                        },
                        onBack = { screen = Screen.Library },
                    )

                    is Screen.SourceBrowse -> SourceBrowseScreen(
                        extension = current.extension,
                        source = current.source,
                        onMangaSelected = { manga ->
                            screen = Screen.MangaDetail(current.extension.toRef(), current.source, manga, backTo = current)
                        },
                        onBack = { screen = Screen.Catalog },
                    )

                    is Screen.MangaDetail -> MangaDetailScreen(
                        extensionRef = current.extensionRef,
                        source = current.source,
                        manga = current.manga,
                        onChapterSelected = { chapters, chapterIndex, initialPageIndex ->
                            screen = Screen.Reader(
                                source = current.source,
                                manga = current.manga,
                                chapters = chapters,
                                chapterIndex = chapterIndex,
                                initialPageIndex = initialPageIndex,
                                backTo = current,
                            )
                        },
                        onBack = { screen = current.backTo },
                    )

                    is Screen.Reader -> ReaderScreen(
                        source = current.source,
                        manga = current.manga,
                        chapters = current.chapters,
                        initialChapterIndex = current.chapterIndex,
                        initialPageIndex = current.initialPageIndex,
                        onBack = { screen = current.backTo },
                    )
                }
            }
        }
    }
}
