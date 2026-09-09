package mihon.desktop.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import mihon.desktop.app.ui.CategoriesScreen
import mihon.desktop.app.ui.DownloadManagerScreen
import mihon.desktop.app.ui.ExtensionManagementScreen
import mihon.desktop.app.ui.ExtensionRef
import mihon.desktop.app.ui.HistoryScreen
import mihon.desktop.app.ui.LibraryScreen
import mihon.desktop.app.ui.MangaDetailScreen
import mihon.desktop.app.ui.MigrateMangaScreen
import mihon.desktop.app.ui.MoreScreen
import mihon.desktop.app.ui.MultiSourceSearchScreen
import mihon.desktop.app.ui.NotificationsScreen
import mihon.desktop.app.ui.ReaderScreen
import mihon.desktop.app.ui.Screen
import mihon.desktop.app.ui.SettingsScreen
import mihon.desktop.app.ui.SourceBrowseScreen
import mihon.desktop.app.ui.SourceSettingsScreen
import mihon.desktop.app.ui.StatsScreen
import mihon.desktop.app.ui.Tab
import mihon.desktop.app.ui.UpdatesScreen
import mihon.desktop.app.ui.themePresetFor
import mihon.desktop.app.ui.toRef
import mihon.desktop.app.i18n.Strings
import mihon.desktop.app.i18n.t
import mihon.desktop.loader.DesktopExtensionRuntime
import mihon.desktop.loader.catalog.ExtensionUpdateManager
import mihon.desktop.loader.library.LibraryRepository
import mihon.desktop.loader.library.LibraryUpdateScheduler
import mihon.desktop.loader.library.NotificationManager
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
    // Apply the saved UI language before the first frame (live switches go
    // through Strings.setLocale from the Settings screen).
    Strings.init(
        AppPreferences.getString(AppPreferences.KEY_APP_LANGUAGE, AppPreferences.DEFAULT_APP_LANGUAGE)
    )
    // Localize key-based notifications from extension-loader (it can't depend
    // on the app's string table): keys resolve against the live locale at
    // display time. Unset (tests, headless) shows the raw key.
    NotificationManager.resolver = { key, args -> Strings.get(key, *args) }
    // Honor the "Show system notifications" setting from the very first
    // notification (scheduler/library-update) onward.
    NotificationManager.setSystemNotificationsEnabled(
        AppPreferences.getBoolean(AppPreferences.KEY_SYSTEM_NOTIFICATIONS, true)
    )

    application {
        val savedInterval = AppPreferences.getLong(
            AppPreferences.KEY_UPDATE_INTERVAL,
            AppPreferences.DEFAULT_UPDATE_INTERVAL,
        )
        val savedUpdateEnabled = AppPreferences.getBoolean(AppPreferences.KEY_UPDATE_ENABLED, true)
        var updateEnabled by remember { mutableStateOf(savedUpdateEnabled) }
        var updateInterval by remember { mutableLongStateOf(savedInterval) }
        // Bumped once per scheduler run that finds updates, so the badge
        // refreshes without the user switching tabs.
        var schedulerTick by remember { mutableIntStateOf(0) }
        // The scheduler is kept as state: its interval is fixed at construction
        // time, and stop() is terminal (the executor can't be restarted), so
        // interval changes and enabled/disabled flips both recreate it.
        var scheduler by remember(updateEnabled) {
            mutableStateOf(LibraryUpdateScheduler(intervalMinutes = updateInterval))
        }
        // Only the app-launch start runs immediately; every recreation waits a
        // full interval first so changing settings never triggers a full
        // library refresh.
        var isAppLaunch by remember { mutableStateOf(true) }

        // Start the background scheduler (unless updates are disabled) and
        // stop on app close / replacement. Flipping the enabled flag recreates
        // the scheduler, which restarts this effect.
        DisposableEffect(scheduler) {
            // Attach the listener before start() so the immediate first run
            // can't race a not-yet-attached listener.
            scheduler.setUpdateListener { _ -> schedulerTick++ }
            val runImmediately = isAppLaunch
            isAppLaunch = false
            if (updateEnabled) scheduler.start(runImmediately)
            onDispose { scheduler.stop() }
        }

        // Automatic backups: checks on app start (after a short delay) and
        // hourly afterwards whether the newest backup is too old. No-op while
        // the "Back up automatically" setting is off.
        val autoBackupScheduler = remember { mihon.desktop.loader.backup.AutoBackupScheduler() }
        DisposableEffect(Unit) {
            autoBackupScheduler.start()
            onDispose { autoBackupScheduler.stop() }
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

            // ── Fullscreen (reader) ─────────────────────────────────────
            // "Fullscreen" = borderless window: the JFrame decorations are
            // removed and the app chrome (navigation rail, reader bars) hides.
            // JFrame.isUndecorated can only change while the window is NOT
            // displayable, so the toggle disposes and re-shows the window --
            // the Compose content tree survives the round-trip. If the toggle
            // fails on some platform, we keep the decorations and only hide
            // the chrome.
            var fullscreen by remember { mutableStateOf(false) }
            LaunchedEffect(fullscreen) {
                val frame = window
                if (frame.isUndecorated != fullscreen) {
                    try {
                        frame.dispose()
                        frame.isUndecorated = fullscreen
                    } catch (t: Throwable) {
                        Logger.w("Main", "Fullscreen decoration toggle failed: ${t.message}")
                    }
                    // Always re-show, even if the toggle threw halfway: after
                    // a successful dispose() a skipped re-show would leave the
                    // window hidden forever with no way back.
                    frame.isVisible = true
                    frame.toFront()
                }
            }
            // Never stay borderless once the reader is gone.
            LaunchedEffect(pushedScreen) {
                if (pushedScreen !is Screen.Reader && fullscreen) fullscreen = false
            }

            var themeMode by remember {
                mutableStateOf(AppPreferences.getString(AppPreferences.KEY_THEME, AppPreferences.DEFAULT_THEME))
            }
            var themePresetId by remember {
                mutableStateOf(
                    AppPreferences.getString(AppPreferences.KEY_THEME_PRESET, AppPreferences.DEFAULT_THEME_PRESET)
                )
            }
            var readingDirection by remember {
                mutableStateOf(AppPreferences.getString(AppPreferences.KEY_READING_DIRECTION, AppPreferences.DEFAULT_READING_DIRECTION))
            }
            var systemNotifications by remember {
                mutableStateOf(AppPreferences.getBoolean(AppPreferences.KEY_SYSTEM_NOTIFICATIONS, true))
            }

            // Live theme-preset switching: the Settings dialog only writes the
            // preference; this listener applies it without a restart (same
            // pattern as the reader's incognito handling).
            DisposableEffect(Unit) {
                val listener: (String) -> Unit = { key ->
                    if (key == AppPreferences.KEY_THEME_PRESET) {
                        themePresetId = AppPreferences.getString(
                            AppPreferences.KEY_THEME_PRESET,
                            AppPreferences.DEFAULT_THEME_PRESET,
                        )
                    }
                }
                AppPreferences.addListener(listener)
                onDispose { AppPreferences.removeListener(listener) }
            }

            val isDark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            val colorScheme = themePresetFor(themePresetId).scheme(isDark)

            // Back dispatcher for detail screens
            val goBack: () -> Unit = { pushedScreen = null }

            // ── Updates badge: unseen count since last Updates-tab visit ──
            val repo = remember { LibraryRepository() }
            var updateCount by remember { mutableLongStateOf(0L) }
            // schedulerTick lives next to the scheduler (see above) and is
            // bumped once per run that finds updates.
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

            // ── Extension updates: background check + Browse-tab badge ────
            // Shared manager: the badge and every extension screen observe
            // the same pendingUpdates flow, so applying an update anywhere
            // refreshes the count everywhere.
            val extensionUpdates = remember { ExtensionUpdateManager.default }
            var extensionUpdateCount by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                // Background check on app start: non-blocking, failures are
                // logged -- a reachable-later catalog just means no badge yet.
                runCatching { extensionUpdates.checkForUpdates() }
                    .onFailure { Logger.w("Main", "Extension update check failed: ${it.message}") }
                extensionUpdates.pendingUpdates.collect { pending ->
                    // OS notification only on the empty -> pending transition,
                    // so repeated checks (or the badge refreshing) don't
                    // re-notify the same updates.
                    if (extensionUpdateCount == 0 && pending.isNotEmpty()) {
                        NotificationManager.notify(
                            title = Strings.get("notif_extension_updates_title"),
                            message = Strings.get("notif_extension_updates_message", pending.size),
                        )
                    }
                    extensionUpdateCount = pending.size
                }
            }

            MaterialTheme(colorScheme = colorScheme) {
                Row(Modifier.fillMaxSize()) {
                    // ── Navigation Rail (left sidebar) ──────────────────
                    // Hidden while the reader is fullscreen.
                    if (!fullscreen) {
                        NavigationRail {
                            Tab.entries.forEach { tab ->
                                NavigationRailItem(
                                    selected = selectedTab == tab && pushedScreen == null,
                                    onClick = {
                                        selectedTab = tab
                                        pushedScreen = null
                                    },
                                    icon = {
                                        val badgeCount = when (tab) {
                                            Tab.Updates -> updateCount
                                            Tab.Browse -> extensionUpdateCount.toLong()
                                            else -> 0L
                                        }
                                        if (badgeCount > 0) {
                                            BadgedBox(badge = { Badge { Text("$badgeCount") } }) {
                                                Icon(tab.icon, contentDescription = t(tab.labelKey))
                                            }
                                        } else {
                                            Icon(tab.icon, contentDescription = t(tab.labelKey))
                                        }
                                    },
                                    label = { Text(t(tab.labelKey)) },
                                )
                            }
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
                                    onSourceSettingsSelected = { source ->
                                        pushedScreen = Screen.SourceSettings(source)
                                    },
                                    onMigrateSource = { sourceId, sourceName ->
                                        pushedScreen = Screen.MigrateManga(sourceId, sourceName)
                                    },
                                )

                        Tab.More -> MoreScreen(
                            onOpenSettings = { pushedScreen = Screen.Settings },
                            onOpenDownloads = { pushedScreen = Screen.DownloadManager },
                            onOpenExtensions = { pushedScreen = Screen.ExtensionManagement },
                            onSearchGlobally = { pushedScreen = Screen.MultiSourceSearch },
                            onOpenNotifications = { pushedScreen = Screen.Notifications },
                            onOpenCategories = { pushedScreen = Screen.Categories },
                            onOpenStats = { pushedScreen = Screen.Stats },
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

                        is Screen.SourceSettings -> SourceSettingsScreen(
                            source = current.source,
                            onBack = { pushedScreen = current.backTo },
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
                            onOpenSourceSettings = {
                                pushedScreen = Screen.SourceSettings(current.source, backTo = current)
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
                            fullscreen = fullscreen,
                            onFullscreenChanged = { fullscreen = it },
                            onBack = {
                                // Always restore decorations when leaving the reader.
                                fullscreen = false
                                pushedScreen = current.backTo
                            },
                        )

                        is Screen.Settings -> SettingsScreen(
                            onBack = goBack,
                            onUpdateIntervalChanged = { minutes ->
                                updateInterval = minutes
                                AppPreferences.setLong(AppPreferences.KEY_UPDATE_INTERVAL, minutes)
                                // Recreate the scheduler so the new interval takes
                                // effect immediately (DisposableEffect restarts it).
                                // While updates are disabled the scheduler isn't
                                // running; re-enabling recreates it via the
                                // remember(updateEnabled) key with the new interval.
                                if (updateEnabled) {
                                    scheduler = LibraryUpdateScheduler(intervalMinutes = minutes)
                                }
                            },
                            currentIntervalMinutes = updateInterval,
                            updateEnabled = updateEnabled,
                            onUpdateEnabledChanged = { enabled ->
                                updateEnabled = enabled
                                AppPreferences.setBoolean(AppPreferences.KEY_UPDATE_ENABLED, enabled)
                                // The flag flip itself recreates the scheduler and
                                // the DisposableEffect starts/stops it accordingly.
                            },
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
                            systemNotificationsEnabled = systemNotifications,
                            onSystemNotificationsEnabledChanged = { enabled ->
                                systemNotifications = enabled
                                AppPreferences.setBoolean(AppPreferences.KEY_SYSTEM_NOTIFICATIONS, enabled)
                                NotificationManager.setSystemNotificationsEnabled(enabled)
                            },
                            onManageExtensions = { pushedScreen = Screen.ExtensionManagement },
                        )

                        is Screen.DownloadManager -> DownloadManagerScreen(
                            onBack = goBack,
                        )

                        is Screen.MultiSourceSearch -> MultiSourceSearchScreen(
                            onMangaSelected = { extensionRef, source, manga ->
                                pushedScreen = Screen.MangaDetail(extensionRef, source, manga, backTo = Screen.MultiSourceSearch)
                            },
                            onBack = goBack,
                        )

                        is Screen.ExtensionManagement -> ExtensionManagementScreen(
                            onBack = goBack,
                        )

                        is Screen.Notifications -> NotificationsScreen(
                            onBack = goBack,
                        )

                        is Screen.Categories -> CategoriesScreen(
                            onBack = goBack,
                        )

                        is Screen.Stats -> StatsScreen(
                            onBack = goBack,
                        )

                        is Screen.MigrateManga -> MigrateMangaScreen(
                            sourceId = current.sourceId,
                            sourceName = current.sourceName,
                            onBack = goBack,
                            onFinished = {
                                selectedTab = Tab.Library
                                pushedScreen = null
                            },
                        )

                        else -> goBack()
                    }
                }
            }
        }
    }
}
