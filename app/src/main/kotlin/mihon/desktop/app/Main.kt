package mihon.desktop.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import mihon.desktop.app.ui.BrowseScreen
import mihon.desktop.app.ui.DownloadManagerScreen
import mihon.desktop.app.ui.ExtensionManagementScreen
import mihon.desktop.app.ui.ExtensionRef
import mihon.desktop.app.ui.HistoryScreen
import mihon.desktop.app.ui.LibraryScreen
import mihon.desktop.app.ui.MangaDetailScreen
import mihon.desktop.app.ui.MoreScreen
import mihon.desktop.app.ui.MultiSourceSearchScreen
import mihon.desktop.app.ui.NotificationsScreen
import mihon.desktop.app.ui.ReaderScreen
import mihon.desktop.app.ui.Screen
import mihon.desktop.app.ui.SettingsScreen
import mihon.desktop.app.ui.SourceBrowseScreen
import mihon.desktop.app.ui.Tab
import mihon.desktop.app.ui.UpdatesScreen
import mihon.desktop.app.ui.toRef
import mihon.desktop.loader.DesktopExtensionRuntime
import mihon.desktop.loader.library.LibraryRepository
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
        // Kept as state: changing the update interval in Settings recreates the
        // scheduler (the interval is fixed at construction time).
        var scheduler by remember { mutableStateOf(LibraryUpdateScheduler(intervalMinutes = savedInterval)) }

        // Start the background scheduler and stop on app close / replacement
        DisposableEffect(scheduler) {
            scheduler.start()
            onDispose { scheduler.stop() }
        }

        // Restore window state
        val savedX = AppPreferences.getInt(KEY_WINDOW_X, -1)
        val savedY = AppPreferences.getInt(KEY_WINDOW_Y, -1)
        val savedWidth = AppPreferences.getInt(KEY_WINDOW_WIDTH, 1100)
        val savedHeight = AppPreferences.getInt(KEY_WINDOW_HEIGHT, 750)

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
            var selectedTab by remember { mutableStateOf(Tab.Library) }
            var pushedScreen by remember { mutableStateOf<Screen?>(null) }
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

            // Back dispatcher for detail screens
            val goBack: () -> Unit = { pushedScreen = null }

            // ── Updates badge: unseen count since last Updates-tab visit ──
            val repo = remember { LibraryRepository() }
            var updateCount by remember { mutableLongStateOf(0L) }
            // Bumped by the scheduler whenever it finds new chapters, so the
            // badge refreshes without the user switching tabs.
            var schedulerTick by remember { mutableIntStateOf(0) }
            LaunchedEffect(scheduler) {
                scheduler.setUpdateListener { _, _ -> schedulerTick++ }
            }
            LaunchedEffect(selectedTab, pushedScreen, schedulerTick) {
                if (selectedTab == Tab.Updates && pushedScreen == null) {
                    // Visiting the Updates tab marks everything as seen.
                    AppPreferences.setLong(AppPreferences.KEY_UPDATES_LAST_SEEN, System.currentTimeMillis())
                    updateCount = 0
                } else {
                    val lastSeen = AppPreferences.getLong(AppPreferences.KEY_UPDATES_LAST_SEEN, 0L)
                    updateCount = repo.updateCountSince(lastSeen)
                }
            }

            MaterialTheme(colorScheme = colorScheme) {
                Row(Modifier.fillMaxSize()) {
                    // ── Navigation Rail (left sidebar) ──────────────────
                    NavigationRail {
                        Tab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = selectedTab == tab && pushedScreen == null,
                                onClick = {
                                    selectedTab = tab
                                    pushedScreen = null
                                },
                                icon = {
                                    if (tab == Tab.Updates && updateCount > 0) {
                                        BadgedBox(badge = { Badge { Text("$updateCount") } }) {
                                            Icon(tab.icon, contentDescription = tab.label)
                                        }
                                    } else {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }

                    // ── Content area ────────────────────────────────────
                    when (val current = pushedScreen) {
                        null -> {
                            // Tab content
                            when (selectedTab) {
                                Tab.Library -> LibraryScreen(
                                    onOpenManga = { extensionRef, source, manga ->
                                        pushedScreen = Screen.MangaDetail(extensionRef, source, manga, backTo = Screen.Library)
                                    },
                                    onBrowseExtensions = { selectedTab = Tab.Browse },
                                )

                                Tab.Updates -> UpdatesScreen(
                                    onOpenManga = { extensionRef, source, manga ->
                                        pushedScreen = Screen.MangaDetail(extensionRef, source, manga, backTo = Screen.Updates)
                                    },
                                )

                                Tab.History -> HistoryScreen(
                                    onOpenManga = { extensionRef, source, manga ->
                                        pushedScreen = Screen.MangaDetail(extensionRef, source, manga, backTo = Screen.History)
                                    },
                                )

                                Tab.Browse -> BrowseScreen(
                                    onSourceSelected = { extension, source ->
                                        pushedScreen = Screen.SourceBrowse(extension, source)
                                    },
                                )

                                Tab.More -> MoreScreen(
                                    onOpenSettings = { pushedScreen = Screen.Settings },
                                    onOpenDownloads = { pushedScreen = Screen.DownloadManager },
                                    onOpenExtensions = { pushedScreen = Screen.ExtensionManagement },
                                    onSearchGlobally = { pushedScreen = Screen.MultiSourceSearch },
                                    onOpenNotifications = { pushedScreen = Screen.Notifications },
                                )
                            }
                        }

                        // ── Detail screens ──────────────────────────────
                        is Screen.SourceBrowse -> SourceBrowseScreen(
                            extension = current.extension,
                            source = current.source,
                            onMangaSelected = { manga ->
                                pushedScreen = Screen.MangaDetail(current.extension.toRef(), current.source, manga, backTo = current)
                            },
                            onBack = goBack,
                        )

                        is Screen.MangaDetail -> MangaDetailScreen(
                            extensionRef = current.extensionRef,
                            source = current.source,
                            manga = current.manga,
                            onChapterSelected = { chapters, chapterIndex, initialPageIndex ->
                                pushedScreen = Screen.Reader(
                                    source = current.source,
                                    manga = current.manga,
                                    chapters = chapters,
                                    chapterIndex = chapterIndex,
                                    initialPageIndex = initialPageIndex,
                                    backTo = current,
                                )
                            },
                            onBack = { pushedScreen = current.backTo },
                        )

                        is Screen.Reader -> ReaderScreen(
                            source = current.source,
                            manga = current.manga,
                            chapters = current.chapters,
                            initialChapterIndex = current.chapterIndex,
                            initialPageIndex = current.initialPageIndex,
                            readingDirection = readingDirection,
                            onBack = { pushedScreen = current.backTo },
                        )

                        is Screen.Settings -> SettingsScreen(
                            onBack = goBack,
                            onUpdateIntervalChanged = { minutes ->
                                updateInterval = minutes
                                AppPreferences.setLong(AppPreferences.KEY_UPDATE_INTERVAL, minutes)
                                // Recreate the scheduler so the new interval takes
                                // effect immediately (DisposableEffect restarts it).
                                scheduler = LibraryUpdateScheduler(intervalMinutes = minutes)
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
                            onManageExtensions = { pushedScreen = Screen.ExtensionManagement },
                        )

                        is Screen.DownloadManager -> DownloadManagerScreen(
                            onBack = goBack,
                        )

                        is Screen.MultiSourceSearch -> MultiSourceSearchScreen(
                            onMangaSelected = { _, _ -> goBack() },
                            onBack = goBack,
                        )

                        is Screen.ExtensionManagement -> ExtensionManagementScreen(
                            onBack = goBack,
                        )

                        is Screen.Notifications -> NotificationsScreen(
                            onBack = goBack,
                        )

                        else -> goBack()
                    }
                }
            }
        }
    }
}
