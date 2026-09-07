package mihon.desktop.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import mihon.desktop.app.ui.CatalogScreen
import mihon.desktop.app.ui.MangaDetailScreen
import mihon.desktop.app.ui.ReaderScreen
import mihon.desktop.app.ui.Screen
import mihon.desktop.app.ui.SourceBrowseScreen
import mihon.desktop.loader.DesktopExtensionRuntime

fun main() {
    DesktopExtensionRuntime.bootstrap()

    application {
        Window(onCloseRequest = ::exitApplication, title = "Mihon Desktop") {
            MaterialTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.Catalog) }

                when (val current = screen) {
                    is Screen.Catalog -> CatalogScreen(
                        onSourceSelected = { extension, source ->
                            screen = Screen.SourceBrowse(extension, source)
                        },
                    )

                    is Screen.SourceBrowse -> SourceBrowseScreen(
                        extension = current.extension,
                        source = current.source,
                        onMangaSelected = { manga ->
                            screen = Screen.MangaDetail(current.source, manga)
                        },
                        onBack = { screen = Screen.Catalog },
                    )

                    is Screen.MangaDetail -> MangaDetailScreen(
                        source = current.source,
                        manga = current.manga,
                        onChapterSelected = { chapters, index ->
                            screen = Screen.Reader(current.source, current.manga, chapters, index)
                        },
                        onBack = { screen = Screen.Catalog },
                    )

                    is Screen.Reader -> ReaderScreen(
                        source = current.source,
                        manga = current.manga,
                        chapters = current.chapters,
                        initialChapterIndex = current.chapterIndex,
                        onBack = { screen = Screen.MangaDetail(current.source, current.manga) },
                    )
                }
            }
        }
    }
}
