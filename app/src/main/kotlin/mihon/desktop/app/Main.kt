package mihon.desktop.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import mihon.desktop.app.ui.CatalogScreen
import mihon.desktop.app.ui.DownloadManagerScreen
import mihon.desktop.app.ui.ExtensionManagementScreen
import mihon.desktop.app.ui.LibraryScreen
import mihon.desktop.app.ui.MangaDetailScreen
import mihon.desktop.app.ui.MultiSourceSearchScreen
import mihon.desktop.app.ui.NotificationsScreen
import mihon.desktop.app.ui.ReaderScreen
import mihon.desktop.app.ui.Screen
import mihon.desktop.app.ui.SettingsScreen
import mihon.desktop.app.ui.SourceBrowseScreen
import mihon.desktop.app.ui.toRef
import mihon.desktop.loader.DesktopExtensionRuntime
import mihon.desktop.loader.library.LibraryUpdateScheduler
import mihon.desktop.loader.log.Logger
import mihon.desktop.loader.prefs.AppPreferences

// Window state preference keys
private const val KEY_WINDOW_X = "window.x"
private const val KEY_WINDOW_Y = "window.y"
private const val KEY_WINDOW_WIDTH = "window.width"
private const val KEY_WINDOW_HEIGHT = "window.height"

fun main() {
    Logger.init()
    DesktopExtensionRuntime.bootstrap()

    application {
        val savedInterval = AppPreferences.getLong(
            AppPreferences.KEY_UPDATE_INTERVAL,
            AppPreferences.DEFAULT_UPDATE_INTERVAL,
        )
        val scheduler = remember { LibraryUpdateScheduler(intervalMinutes = savedInterval) }

        // Start the background scheduler and stop on app close
        DisposableEffect(Unit) {
            scheduler.start()
            onDispose { scheduler.stop() }
        }

        // Restore window state
        val savedX = AppPreferences.getInt(KEY_WINDOW_X, -1)
        val savedY = AppPreferences.getInt(KEY_WINDOW_Y, -1)
        val savedWidth = AppPreferences.getInt(KEY_WINDOW_WIDTH, 900)
        val savedHeight = AppPreferences.getInt(KEY_WINDOW_HEIGHT, 700)

        val windowState = rememberWindowState(
            size = DpSize(savedWidth.dp, savedHeight.dp),
            position = if (savedX >= 0 && savedY >= 0) WindowPosition(savedX.dp, savedY.dp) else WindowPosition.PlatformDefault,
        )

        // Save window state on close
        DisposableEffect(Unit) {
            onDispose {
                val pos = windowState.position
                val size = windowState.size
                if (pos is WindowPosition.Absolute) {
                    AppPreferences.setInt(KEY_WINDOW_X, pos.x.value.toInt())
                    AppPreferences.setInt(KEY_WINDOW_Y, pos.y.value.toInt())
                }
                AppPreferences.setInt(KEY_WINDOW_WIDTH, size.width.value.toInt())
                AppPreferences.setInt(KEY_WINDOW_HEIGHT, size.height.value.toInt())
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            title = "Mihon Desktop",
            state = windowState,
        ) {
            var screen by remember { mutableStateOf<Screen>(Screen.Library) }
            var updateInterval by remember { mutableLongStateOf(savedInterval) }
            var themeMode by remember {
                mutableStateOf(AppPreferences.getString(AppPreferences.KEY_THEME, AppPreferences.DEFAULT_THEME))
            }
            var readingDirection by remember {
                mutableStateOf(AppPreferences.getString(AppPreferences.KEY_READING_DIRECTION, AppPreferences.DEFAULT_READING_DIRECTION))
            }

            val isDark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()

            MaterialTheme(colorScheme = colorScheme) {
                when (val current = screen) {
                    is Screen.Library -> LibraryScreen(
                        onOpenManga = { extensionRef, source, manga ->
                            screen = Screen.MangaDetail(extensionRef, source, manga, backTo = Screen.Library)
                        },
                        onBrowseExtensions = { screen = Screen.Catalog },
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenDownloads = { screen = Screen.DownloadManager },
                        onSearchGlobally = { screen = Screen.MultiSourceSearch },
                        onOpenNotifications = { screen = Screen.Notifications },
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
                        readingDirection = readingDirection,
                        onBack = { screen = current.backTo },
                    )

                    is Screen.Settings -> SettingsScreen(
                        onBack = { screen = Screen.Library },
                        onUpdateIntervalChanged = { minutes ->
                            updateInterval = minutes
                            AppPreferences.setLong(AppPreferences.KEY_UPDATE_INTERVAL, minutes)
                        },
                        currentIntervalMinutes = updateInterval,
                        currentTheme = themeMode,
                        onThemeChanged = { theme ->
                            themeMode = theme
                            AppPreferences.setString(AppPreferences.KEY_THEME, theme)
                        },
                        currentReadingDirection = readingDirection,
                        onReadingDirectionChanged = { dir ->
                            readingDirection = dir
                            AppPreferences.setString(AppPreferences.KEY_READING_DIRECTION, dir)
                        },
                        onManageExtensions = { screen = Screen.ExtensionManagement },
                    )

                    is Screen.DownloadManager -> DownloadManagerScreen(
                        onBack = { screen = Screen.Library },
                    )

                    is Screen.MultiSourceSearch -> MultiSourceSearchScreen(
                        onMangaSelected = { source, manga ->
                            // Find the extension that loaded this source
                            screen = Screen.Catalog // simplified - go back to catalog
                        },
                        onBack = { screen = Screen.Library },
                    )

                    is Screen.ExtensionManagement -> ExtensionManagementScreen(
                        onBack = { screen = Screen.Library },
                    )

                    is Screen.Notifications -> NotificationsScreen(
                        onBack = { screen = Screen.Library },
                    )
                }
            }
        }
    }
}
