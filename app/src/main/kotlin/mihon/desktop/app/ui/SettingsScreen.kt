package mihon.desktop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mihon.desktop.loader.backup.BackupManager
import mihon.desktop.loader.backup.AutoBackupScheduler
import mihon.desktop.loader.backup.tachibk.TachibkManager
import mihon.desktop.loader.prefs.AppPreferences
import mihon.desktop.loader.tracker.Tracker
import mihon.desktop.loader.tracker.TrackerManager
import mihon.desktop.app.i18n.Strings
import mihon.desktop.app.i18n.t
import java.awt.Desktop
import java.awt.FileDialog
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings screen with theme, reading direction, notifications, backup/restore,
 * and update interval.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onUpdateIntervalChanged: (Long) -> Unit,
    currentIntervalMinutes: Long,
    updateEnabled: Boolean = true,
    onUpdateEnabledChanged: (Boolean) -> Unit = {},
    currentTheme: String = "system",
    onThemeChanged: (String) -> Unit = {},
    currentReadingDirection: String = "ltr",
    onReadingDirectionChanged: (String) -> Unit = {},
    systemNotificationsEnabled: Boolean = true,
    onSystemNotificationsEnabledChanged: (Boolean) -> Unit = {},
    onManageExtensions: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val backupManager = remember { BackupManager() }
    val tachibkManager = remember { TachibkManager() }

    // ── Trackers ─────────────────────────────────────────────────────────
    val trackerManager = remember { TrackerManager.shared() }
    /** tracker name -> (logged in, username). Refreshed after login/logout. */
    var trackerStates by remember { mutableStateOf<Map<String, Pair<Boolean, String?>>>(emptyMap()) }
    var loginTracker by remember { mutableStateOf<Tracker?>(null) }
    var showMalClientIdDialog by remember { mutableStateOf(false) }

    fun refreshTrackerStates() {
        trackerStates = trackerManager.trackers.associate { t ->
            t.name to (t.isLoggedIn() to t.username())
        }
    }
    LaunchedEffect(Unit) { refreshTrackerStates() }

    var backupInProgress by remember { mutableStateOf(false) }
    var importInProgress by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<String?>(null) }
    // Success flag drives the result row's color — the message text itself
    // is localized, so prefix sniffing ("Imported …") can't be used.
    var importResultSuccess by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var backups by remember { mutableStateOf(backupManager.listBackups()) }
    var intervalMinutes by remember { mutableFloatStateOf(currentIntervalMinutes.toFloat()) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var currentLanguage by remember {
        mutableStateOf(
            AppPreferences.getString(AppPreferences.KEY_APP_LANGUAGE, AppPreferences.DEFAULT_APP_LANGUAGE)
        )
    }
    var showDirectionDialog by remember { mutableStateOf(false) }
    var showDohProviderDialog by remember { mutableStateOf(false) }
    var showAutoBackupIntervalDialog by remember { mutableStateOf(false) }
    var confirmOverwriteFile by remember { mutableStateOf<File?>(null) }
    var dohProvider by remember {
        mutableStateOf(
            AppPreferences.getString(AppPreferences.KEY_DOH_PROVIDER, AppPreferences.DEFAULT_DOH_PROVIDER)
                ?: AppPreferences.DEFAULT_DOH_PROVIDER,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("settings_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common_back")) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            // Appearance section
            item {
                Text(
                    t("section_appearance"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                val themeLabel = when (currentTheme) {
                    "dark" -> t("theme_dark")
                    "light" -> t("theme_light")
                    else -> t("theme_system")
                }
                ListItem(
                    headlineContent = { Text(t("settings_theme")) },
                    supportingContent = { Text(themeLabel) },
                    leadingContent = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
                    modifier = Modifier.clickable { showThemeDialog = true },
                )
            }
            item {
                val currentPresetId = AppPreferences.getString(
                    AppPreferences.KEY_THEME_PRESET,
                    AppPreferences.DEFAULT_THEME_PRESET,
                )
                val presetName = themePresetFor(currentPresetId).name
                ListItem(
                    headlineContent = { Text(t("settings_theme_preset")) },
                    supportingContent = { Text(presetName) },
                    leadingContent = { Icon(Icons.Filled.Palette, contentDescription = null) },
                    modifier = Modifier.clickable { showPresetDialog = true },
                )
            }
            item {
                val languageLabel = when (currentLanguage) {
                    "en" -> t("language_english")
                    "id" -> t("language_indonesian")
                    else -> t("language_system")
                }
                ListItem(
                    headlineContent = { Text(t("settings_app_language")) },
                    supportingContent = { Text(languageLabel) },
                    leadingContent = { Icon(Icons.Filled.Language, contentDescription = null) },
                    modifier = Modifier.clickable { showLanguageDialog = true },
                )
            }

            // Reader section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    t("section_reader"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                val dirLabel = when (currentReadingDirection) {
                    "rtl" -> t("direction_rtl")
                    else -> t("direction_ltr")
                }
                ListItem(
                    headlineContent = { Text(t("settings_reading_direction")) },
                    supportingContent = { Text(dirLabel) },
                    leadingContent = { Icon(Icons.Filled.TextFields, contentDescription = null) },
                    modifier = Modifier.clickable { showDirectionDialog = true },
                )
            }
            item {
                // Applies live: the reader observes KEY_READER_PRELOAD changes
                // and rebuilds its preload window around the current page.
                var preload by remember {
                    mutableFloatStateOf(
                        AppPreferences.getInt(
                            AppPreferences.KEY_READER_PRELOAD,
                            AppPreferences.DEFAULT_READER_PRELOAD,
                        ).coerceIn(0, 10).toFloat(),
                    )
                }
                ListItem(
                    headlineContent = { Text(t("settings_preload", preload.toInt())) },
                    supportingContent = {
                        Column {
                            Text(t("settings_preload_hint"))
                            Slider(
                                value = preload,
                                onValueChange = { preload = it },
                                onValueChangeFinished = {
                                    AppPreferences.setInt(AppPreferences.KEY_READER_PRELOAD, preload.toInt())
                                },
                                valueRange = 0f..10f,
                                steps = 9,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth()) {
                                Text("0", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.weight(1f))
                                Text("10", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                )
            }
            item {
                // Global brightness filter: a black scrim in the reader.
                // Applies live via the AppPreferences listener (see ReaderScreen).
                var brightnessOn by remember {
                    mutableStateOf(
                        AppPreferences.getBoolean(AppPreferences.KEY_READER_BRIGHTNESS_ENABLED, false),
                    )
                }
                var brightness by remember {
                    mutableFloatStateOf(
                        AppPreferences.getInt(
                            AppPreferences.KEY_READER_BRIGHTNESS,
                            AppPreferences.DEFAULT_READER_BRIGHTNESS,
                        ).coerceIn(0, 100).toFloat(),
                    )
                }
                ListItem(
                    headlineContent = { Text(t("settings_brightness_filter")) },
                    supportingContent = {
                        Column {
                            Text(t("settings_brightness_hint"))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Slider(
                                    value = brightness,
                                    onValueChange = { brightness = it },
                                    // Persist once per drag, not per tick.
                                    onValueChangeFinished = {
                                        AppPreferences.setInt(AppPreferences.KEY_READER_BRIGHTNESS, brightness.toInt())
                                    },
                                    valueRange = 0f..100f,
                                    enabled = brightnessOn,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${brightness.toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                                Switch(
                                    checked = brightnessOn,
                                    onCheckedChange = { enabled ->
                                        brightnessOn = enabled
                                        AppPreferences.setBoolean(AppPreferences.KEY_READER_BRIGHTNESS_ENABLED, enabled)
                                    },
                                )
                            }
                        }
                    },
                )
            }

            // Trackers section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    t("section_trackers"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            trackerManager.trackers.forEach { tracker ->
                item(key = "tracker-${tracker.name}") {
                    val (loggedIn, username) = trackerStates[tracker.name] ?: (false to null)
                    ListItem(
                        headlineContent = { Text(prettyTrackerName(tracker.name)) },
                        supportingContent = {
                            Text(
                                when {
                                    loggedIn && username != null -> t("logged_in_as", username)
                                    loggedIn -> t("logged_in")
                                    else -> t("not_logged_in")
                                },
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.TrackChanges, contentDescription = null) },
                        trailingContent = {
                            TextButton(onClick = {
                                if (loggedIn) {
                                    tracker.logout()
                                    refreshTrackerStates()
                                } else {
                                    loginTracker = tracker
                                }
                            }) {
                                Text(if (loggedIn) t("action_log_out") else t("action_log_in"))
                            }
                        },
                    )
                }
            }
            item(key = "tracker-mal-client-id") {
                val customClientId = AppPreferences.getString(AppPreferences.KEY_TRACKER_MAL_CLIENT_ID, "")
                ListItem(
                    headlineContent = { Text(t("mal_client_id")) },
                    supportingContent = {
                        Text(
                            if (customClientId.isBlank()) t("mal_client_default")
                            else t("mal_client_custom", customClientId.take(8)),
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.Extension, contentDescription = null) },
                    modifier = Modifier.clickable { showMalClientIdDialog = true },
                )
            }

            // Downloads section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    t("section_downloads"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                // Applies live: the shared DownloadQueue reads this preference
                // whenever it picks the next job.
                var concurrency by remember {
                    mutableFloatStateOf(
                        AppPreferences.getInt(
                            AppPreferences.KEY_DOWNLOAD_CONCURRENCY,
                            AppPreferences.DEFAULT_DOWNLOAD_CONCURRENCY,
                        ).toFloat(),
                    )
                }
                ListItem(
                    headlineContent = { Text(t("settings_concurrency", concurrency.toInt())) },
                    supportingContent = {
                        Column {
                            Slider(
                                value = concurrency,
                                onValueChange = { concurrency = it },
                                onValueChangeFinished = {
                                    AppPreferences.setInt(AppPreferences.KEY_DOWNLOAD_CONCURRENCY, concurrency.toInt())
                                },
                                valueRange = 1f..4f,
                                steps = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth()) {
                                Text("1", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.weight(1f))
                                Text("4", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                )
            }

            // Notifications section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    t("section_notifications"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(t("settings_system_notifications")) },
                    supportingContent = { Text(t("settings_system_notifications_hint")) },
                    trailingContent = {
                        Switch(
                            checked = systemNotificationsEnabled,
                            onCheckedChange = onSystemNotificationsEnabledChanged,
                        )
                    },
                )
            }

            // Background updates section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    t("section_background_updates"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(t("settings_check_updates")) },
                    supportingContent = { Text(t("settings_check_updates_hint")) },
                    trailingContent = {
                        Switch(checked = updateEnabled, onCheckedChange = onUpdateEnabledChanged)
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(t("settings_interval", intervalMinutes.toInt())) },
                    supportingContent = {
                        Column {
                            Slider(
                                value = intervalMinutes,
                                onValueChange = { intervalMinutes = it },
                                onValueChangeFinished = {
                                    onUpdateIntervalChanged(intervalMinutes.toLong())
                                },
                                valueRange = 15f..240f,
                                steps = 14,
                                enabled = updateEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            androidx.compose.foundation.layout.Row(
                                Modifier.fillMaxWidth(),
                            ) {
                                Text(t("interval_15_min"), style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.weight(1f))
                                Text(t("interval_4_hours"), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                )
            }

            // Network section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    t("section_network"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                // Read once at startup to build the shared OkHttpClient
                // (DesktopExtensionRuntime.bootstrap) — requires a restart.
                var dohEnabled by remember {
                    mutableStateOf(
                        AppPreferences.getBoolean(AppPreferences.KEY_DOH_ENABLED, AppPreferences.DEFAULT_DOH_ENABLED),
                    )
                }
                ListItem(
                    headlineContent = { Text(t("settings_doh")) },
                    supportingContent = { Text(t("settings_doh_hint")) },
                    trailingContent = {
                        Switch(
                            checked = dohEnabled,
                            onCheckedChange = { enabled ->
                                dohEnabled = enabled
                                AppPreferences.setBoolean(AppPreferences.KEY_DOH_ENABLED, enabled)
                            },
                        )
                    },
                )
            }
            item {
                val providerLabel = when (dohProvider) {
                    "cloudflare" -> t("doh_cloudflare")
                    else -> t("doh_google")
                }
                ListItem(
                    headlineContent = { Text(t("settings_doh_provider")) },
                    supportingContent = { Text(t("requires_restart", providerLabel)) },
                    modifier = Modifier.clickable { showDohProviderDialog = true },
                )
            }

            // Extensions section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    t("section_extensions"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(t("settings_manage_extensions")) },
                    supportingContent = { Text(t("settings_manage_extensions_hint")) },
                    leadingContent = { Icon(Icons.Filled.Extension, contentDescription = null) },
                    modifier = Modifier.clickable { onManageExtensions() },
                )
            }

            // Storage section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    t("section_storage"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                val downloadDir = java.io.File(System.getProperty("user.home"), ".mihon-desktop/downloads")
                val cacheDir = mihon.desktop.loader.ExtensionLoader.extensionCacheDir
                val imageCacheDir = java.io.File(System.getProperty("user.home"), ".mihon-desktop/image-cache")
                val downloadSize = if (downloadDir.exists()) downloadDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
                val cacheSize = if (cacheDir.exists()) cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
                val imageSize = if (imageCacheDir.exists()) imageCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
                ListItem(
                    headlineContent = { Text(t("storage_downloads")) },
                    supportingContent = { Text(formatBytes(downloadSize)) },
                )
                ListItem(
                    headlineContent = { Text(t("storage_extension_cache")) },
                    supportingContent = { Text(formatBytes(cacheSize)) },
                )
                ListItem(
                    headlineContent = { Text(t("storage_image_cache")) },
                    supportingContent = { Text(formatBytes(imageSize)) },
                )
                ListItem(
                    headlineContent = { Text(t("storage_total")) },
                    supportingContent = { Text(formatBytes(downloadSize + cacheSize + imageSize)) },
                )
            }

            // Backup section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    t("section_backup"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(t("settings_create_backup")) },
                    supportingContent = { Text(t("settings_create_backup_hint")) },
                    leadingContent = { Icon(Icons.Filled.Backup, contentDescription = null) },
                    modifier = Modifier.clickable(enabled = !backupInProgress) {
                        scope.launch {
                            backupInProgress = true
                            runCatching { backupManager.exportBackup() }
                                .onSuccess { backups = backupManager.listBackups() }
                                .onFailure { importResult = Strings.get("backup_failed", it.message); importResultSuccess = false }
                            backupInProgress = false
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(t("settings_restore_backup")) },
                    supportingContent = { Text(t("settings_restore_backup_hint")) },
                    leadingContent = { Icon(Icons.Filled.Restore, contentDescription = null) },
                    modifier = Modifier.clickable(enabled = !importInProgress) {
                        showImportDialog = true
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(t("settings_import_tachibk")) },
                    supportingContent = { Text(t("settings_import_tachibk_hint")) },
                    leadingContent = { Icon(Icons.Filled.Restore, contentDescription = null) },
                    modifier = Modifier.clickable(enabled = !importInProgress) {
                        // Modal AWT file picker (runs on the UI/EDT thread);
                        // only the chosen file's IO moves off it.
                        pickTachibkFile(FileDialog.LOAD)?.let { file ->
                            scope.launch {
                                importInProgress = true
                                runCatching { tachibkManager.import(file) }
                                    .onSuccess { result ->
                                        // Localized here (not in TachibkManager):
                                        // extension-loader has no string table.
                                        // Skipped/categories segments only show
                                        // when non-zero, mirroring the old
                                        // English-only summary(). Strings.get
                                        // (not t): coroutine context, and the
                                        // sibling result messages do the same.
                                        importResult = buildString {
                                            append(Strings.get("tachibk_summary", result.imported, result.updated, result.readChapters))
                                            if (result.skipped > 0) append(Strings.get("tachibk_summary_skipped", result.skipped))
                                            if (result.categoriesCreated > 0) {
                                                append(Strings.get("tachibk_summary_categories", result.categoriesCreated))
                                            }
                                        }
                                        importResultSuccess = true
                                        backups = backupManager.listBackups()
                                    }
                                    .onFailure { err -> importResult = Strings.get("import_failed", err.message); importResultSuccess = false }
                                importInProgress = false
                            }
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(t("settings_export_tachibk")) },
                    supportingContent = { Text(t("settings_export_tachibk_hint")) },
                    leadingContent = { Icon(Icons.Filled.Backup, contentDescription = null) },
                    modifier = Modifier.clickable(enabled = !backupInProgress) {
                        pickTachibkFile(FileDialog.SAVE)?.let { file ->
                            if (file.exists()) {
                                confirmOverwriteFile = file
                            } else {
                                scope.launch {
                                    backupInProgress = true
                                    runCatching { tachibkManager.export(file) }
                                        .onSuccess { importResult = Strings.get("exported_file", it.name); importResultSuccess = true }
                                        .onFailure { err -> importResult = Strings.get("export_failed", err.message); importResultSuccess = false }
                                    backupInProgress = false
                                }
                            }
                        }
                    },
                )
            }

            // Automatic backups section
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    t("section_auto_backup"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item {
                var autoBackupEnabled by remember {
                    mutableStateOf(
                        AppPreferences.getBoolean(
                            AppPreferences.KEY_AUTO_BACKUP_ENABLED,
                            AppPreferences.DEFAULT_AUTO_BACKUP_ENABLED,
                        )
                    )
                }
                ListItem(
                    headlineContent = { Text(t("settings_auto_backup")) },
                    supportingContent = { Text(t("settings_auto_backup_hint")) },
                    trailingContent = {
                        Switch(
                            checked = autoBackupEnabled,
                            onCheckedChange = { enabled ->
                                autoBackupEnabled = enabled
                                AppPreferences.setBoolean(AppPreferences.KEY_AUTO_BACKUP_ENABLED, enabled)
                            },
                        )
                    },
                )
            }
            item {
                var autoBackupDays by remember {
                    mutableStateOf(
                        AppPreferences.getInt(
                            AppPreferences.KEY_AUTO_BACKUP_INTERVAL_DAYS,
                            AppPreferences.DEFAULT_AUTO_BACKUP_INTERVAL_DAYS,
                        )
                    )
                }
                ListItem(
                    headlineContent = { Text(t("settings_auto_frequency", autoBackupDays)) },
                    supportingContent = { Text(t("settings_auto_frequency_hint")) },
                    modifier = Modifier.clickable { showAutoBackupIntervalDialog = true },
                )
            }
            item {
                var autoTachibk by remember {
                    mutableStateOf(
                        AppPreferences.getBoolean(
                            AppPreferences.KEY_AUTO_BACKUP_TACHIBK,
                            AppPreferences.DEFAULT_AUTO_BACKUP_TACHIBK,
                        )
                    )
                }
                ListItem(
                    headlineContent = { Text(t("settings_auto_tachibk")) },
                    supportingContent = { Text(t("settings_auto_tachibk_hint")) },
                    trailingContent = {
                        Switch(
                            checked = autoTachibk,
                            onCheckedChange = { enabled ->
                                autoTachibk = enabled
                                AppPreferences.setBoolean(AppPreferences.KEY_AUTO_BACKUP_TACHIBK, enabled)
                            },
                        )
                    },
                )
            }
            item {
                val lastAt = AppPreferences.getLong(AppPreferences.KEY_AUTO_BACKUP_LAST_AT, 0L)
                ListItem(
                    headlineContent = { Text(t("settings_last_backup")) },
                    supportingContent = {
                        Text(
                            if (lastAt == 0L) t("last_backup_never")
                            else t(
                                "last_backup_at",
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(lastAt)),
                                AutoBackupScheduler.MAX_KEEP,
                            )
                        )
                    },
                )
            }

            if (importResult != null) {
                item {
                    ListItem(
                        headlineContent = {
                            Text(
                                importResult ?: "",
                                color = if (importResultSuccess) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null)
                        },
                    )
                }
            }

            // List existing backups
            if (backups.isNotEmpty()) {
                item {
                    Text(
                        t("available_backups"),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(backups) { file ->
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(Date(file.lastModified()))
                    val sizeKb = file.length() / 1024
                    ListItem(
                        headlineContent = { Text(file.name) },
                        supportingContent = { Text("$date · ${sizeKb} KB") },
                        modifier = Modifier.clickable {
                            scope.launch {
                                importInProgress = true
                                runCatching { backupManager.importBackup(file) }
                                    .onSuccess { count -> importResult = Strings.get("imported_entries", count); importResultSuccess = true }
                                    .onFailure { err -> importResult = Strings.get("import_failed", err.message); importResultSuccess = false }
                                importInProgress = false
                            }
                        },
                    )
                }
            }
        }
    }

    // Theme dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(t("settings_theme")) },
            text = {
                Column {
                    listOf(
                        "system" to t("theme_system"),
                        "light" to t("theme_light"),
                        "dark" to t("theme_dark"),
                    ).forEach { (value, label) ->
                        Text(
                            label,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                .clickable {
                                    onThemeChanged(value)
                                    showThemeDialog = false
                                },
                            color = if (currentTheme == value) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text(t("common_cancel")) }
            },
        )
    }

    // Theme preset dialog: name + light/dark swatch preview per preset.
    // Selecting writes the preference; Main.kt observes it and swaps the
    // color scheme live -- no restart, no callback plumbing needed here.
    if (showPresetDialog) {
        val currentPresetId = AppPreferences.getString(
            AppPreferences.KEY_THEME_PRESET,
            AppPreferences.DEFAULT_THEME_PRESET,
        )
        AlertDialog(
            onDismissRequest = { showPresetDialog = false },
            title = { Text(t("settings_theme_preset")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    themePresets.forEach { preset ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    AppPreferences.setString(AppPreferences.KEY_THEME_PRESET, preset.id)
                                    showPresetDialog = false
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                preset.swatchColors().forEach { color ->
                                    Box(
                                        Modifier
                                            .size(18.dp)
                                            .background(color, RoundedCornerShape(4.dp))
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                preset.name,
                                color = if (currentPresetId == preset.id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresetDialog = false }) { Text(t("common_cancel")) }
            },
        )
    }

    // Reading direction dialog
    if (showDirectionDialog) {
        AlertDialog(
            onDismissRequest = { showDirectionDialog = false },
            title = { Text(t("settings_reading_direction")) },
            text = {
                Column {
                    listOf(
                        "ltr" to t("direction_ltr"),
                        "rtl" to t("direction_rtl"),
                    ).forEach { (value, label) ->
                        Text(
                            label,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                .clickable {
                                    onReadingDirectionChanged(value)
                                    showDirectionDialog = false
                                },
                            color = if (currentReadingDirection == value) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDirectionDialog = false }) { Text(t("common_cancel")) }
            },
        )
    }

    // App language dialog: writes the preference and live-swaps the string
    // table (Strings.setLocale bumps a tick every t() call site observes).
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(t("settings_app_language")) },
            text = {
                Column {
                    listOf(
                        "system" to t("language_system"),
                        "en" to t("language_english"),
                        "id" to t("language_indonesian"),
                    ).forEach { (value, label) ->
                        Text(
                            label,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                .clickable {
                                    currentLanguage = value
                                    AppPreferences.setString(AppPreferences.KEY_APP_LANGUAGE, value)
                                    Strings.setLocale(value)
                                    showLanguageDialog = false
                                },
                            color = if (currentLanguage == value) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text(t("common_cancel")) }
            },
        )
    }

    // DoH provider dialog
    if (showDohProviderDialog) {
        val currentProvider = dohProvider
        AlertDialog(
            onDismissRequest = { showDohProviderDialog = false },
            title = { Text(t("settings_doh_provider")) },
            text = {
                Column {
                    listOf(
                        "google" to t("doh_google"),
                        "cloudflare" to t("doh_cloudflare"),
                    ).forEach { (value, label) ->
                        Text(
                            label,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                .clickable {
                                    dohProvider = value
                                    AppPreferences.setString(AppPreferences.KEY_DOH_PROVIDER, value)
                                    showDohProviderDialog = false
                                },
                            color = if (currentProvider == value) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDohProviderDialog = false }) { Text(t("common_cancel")) }
            },
        )
    }

    // Import dialog
    if (showImportDialog) {
        val availableBackups = backupManager.listBackups()
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(t("backup_select_title")) },
            text = {
                if (availableBackups.isEmpty()) {
                    Text(t("backup_none_found"))
                } else {
                    Column {
                        for (file in availableBackups) {
                            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                                .format(Date(file.lastModified()))
                            Text(
                                "${file.name}\n$date",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                    .clickable {
                                        showImportDialog = false
                                        scope.launch {
                                            importInProgress = true
                                            runCatching { backupManager.importBackup(file) }
                                                .onSuccess { count -> importResult = Strings.get("imported_entries", count); importResultSuccess = true }
                                                .onFailure { err -> importResult = Strings.get("import_failed", err.message); importResultSuccess = false }
                                            importInProgress = false
                                        }
                                    },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImportDialog = false }) { Text(t("common_cancel")) }
            },
        )
    }
    // Auto-backup interval dialog
    if (showAutoBackupIntervalDialog) {
        var autoBackupDays by remember {
            mutableStateOf(
                AppPreferences.getInt(
                    AppPreferences.KEY_AUTO_BACKUP_INTERVAL_DAYS,
                    AppPreferences.DEFAULT_AUTO_BACKUP_INTERVAL_DAYS,
                )
            )
        }
        AlertDialog(
            onDismissRequest = { showAutoBackupIntervalDialog = false },
            title = { Text(t("backup_frequency_title")) },
            text = {
                Column {
                    listOf(1, 2, 3, 7).forEach { days ->
                        Text(
                            if (days == 1) t("every_day") else t("every_n_days", days),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                .clickable {
                                    autoBackupDays = days
                                    AppPreferences.setInt(AppPreferences.KEY_AUTO_BACKUP_INTERVAL_DAYS, days)
                                    showAutoBackupIntervalDialog = false
                                },
                            color = if (autoBackupDays == days) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAutoBackupIntervalDialog = false }) { Text(t("common_cancel")) }
            },
        )
    }

    // Overwrite confirmation for a .tachibk export onto an existing file
    confirmOverwriteFile?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmOverwriteFile = null },
            title = { Text(t("backup_overwrite_title")) },
            text = { Text(t("backup_overwrite_text", target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val file = confirmOverwriteFile
                    confirmOverwriteFile = null
                    if (file != null) {
                        scope.launch {
                            backupInProgress = true
                            runCatching { tachibkManager.export(file) }
                                .onSuccess { importResult = Strings.get("exported_file", it.name); importResultSuccess = true }
                                .onFailure { err -> importResult = Strings.get("export_failed", err.message); importResultSuccess = false }
                            backupInProgress = false
                        }
                    }
                }) { Text(t("action_overwrite")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverwriteFile = null }) { Text(t("common_cancel")) }
            },
        )
    }

    // Tracker login dialog (token-paste / code-paste flow per tracker)
    loginTracker?.let { tracker ->
        TrackerLoginDialog(
            tracker = tracker,
            onDismiss = { loginTracker = null },
            onSuccess = {
                loginTracker = null
                refreshTrackerStates()
            },
        )
    }

    // MAL OAuth client id override dialog
    if (showMalClientIdDialog) {
        var clientId by remember {
            mutableStateOf(AppPreferences.getString(AppPreferences.KEY_TRACKER_MAL_CLIENT_ID, ""))
        }
        AlertDialog(
            onDismissRequest = { showMalClientIdDialog = false },
            title = { Text(t("mal_client_id")) },
            text = {
                Column {
                    Text(
                        t("mal_client_dialog_hint"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it.trim() },
                        label = { Text(t("mal_client_field")) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    AppPreferences.setString(AppPreferences.KEY_TRACKER_MAL_CLIENT_ID, clientId)
                    showMalClientIdDialog = false
                }) { Text(t("common_save")) }
            },
            dismissButton = {
                TextButton(onClick = { showMalClientIdDialog = false }) { Text(t("common_cancel")) }
            },
        )
    }
}

/**
 * Modal native file picker for `.tachibk` files. Blocking (AWT modal dialog
 * on the UI/EDT thread); returns null when the user cancels.
 */
private fun pickTachibkFile(mode: Int): File? {
    val dialog = FileDialog(
        null as java.awt.Frame?,
        if (mode == FileDialog.LOAD) Strings.get("filedialog_import_title") else Strings.get("filedialog_export_title"),
        mode,
    )
    // No-op on Windows (known AWT limitation: setFilenameFilter only works
    // on platforms with a native filename filter); the dialog just shows all
    // files there. On Linux/macOS it filters to .tachibk.
    dialog.setFilenameFilter { _, name -> name.endsWith(".tachibk") }
    if (mode == FileDialog.SAVE) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        dialog.file = "mihon-desktop-$timestamp"
    }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
