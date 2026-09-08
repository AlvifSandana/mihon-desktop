# Navigation Implementation Plan: 5-Tab Layout

Replicate mihon Android's 5-tab navigation (Library, Updates, History, Browse, More) into mihon-desktop.

> **Status: IMPLEMENTED** (all phases + remaining work + follow-ups). Post-implementation review
> fixes applied: chapter-diffing for updates, legacy DB repair, incognito gating,
> path-validated jar loading, off-main-thread IO, badge "unseen" semantics,
> scheduler rescheduling, reachable global search/notifications.
>
> Follow-ups also done: baseline seeding at library-add time (existing chapters
> never flood Updates), `chapterName` in readChapters (History shows names),
> LoadedExtension cache keyed on (path, lastModified) (fixes URLClassLoader
> leak, shares Source instances), sha256 sidecar tamper-evidence at jar load
> time, and unit tests for the diff/migration/validation logic (18 new tests).
> Known remaining: sidecar is same-user forgeable (full fix needs signing),
> single shared JDBC connection, incognito toggle not live in open reader.

## Current State

**mihon-desktop** now: `when(screen)` flat switch, no navigation chrome. 10 screen definitions, Library as hub with icon buttons.

**Target (mihon Android)**: Voyager `TabNavigator` + `NavigationSuiteScaffold` with 5 tabs.

## Gap Analysis

| Tab | Android | Desktop Now | Gap |
|-----|---------|-------------|-----|
| **Library** | Category pager + grid | Grid + search/sort | Exists, needs tab wrapper |
| **Updates** | Flat list of chapter updates grouped by date | None | Need `updateHistory` DB table + screen |
| **History** | Flat list of read chapters grouped by date | None | DB has `readChapters` table, need screen |
| **Browse** | 3 sub-tabs: Sources, Extensions, Migration | `CatalogScreen` (extensions only) | Need restructure + Sources + Migration |
| **More** | Menu list (settings, categories, stats, downloads) | Separate `SettingsScreen`, `DownloadManagerScreen` | Need menu screen |

---

## Phase 1: Data Layer (extension-loader module)

### 1a. Add `updateHistory` table

New `.sq` file:

```sql
-- extension-loader/src/main/sqldelight/mihon/desktop/loader/library/UpdateHistory.sq

CREATE TABLE IF NOT EXISTS updateHistory (
    sourceId INTEGER NOT NULL,
    mangaUrl TEXT NOT NULL,
    mangaTitle TEXT NOT NULL,
    thumbnailUrl TEXT,
    chapterUrl TEXT NOT NULL,
    chapterName TEXT NOT NULL,
    chapterNumber REAL,
    fetchedAt INTEGER NOT NULL,
    PRIMARY KEY (sourceId, chapterUrl)
);
```

Add queries to `LibraryRepository`:
- `allUpdates(): List<UpdateHistory>` - ordered by `fetchedAt DESC`
- `insertUpdates(...)` - batch insert during library refresh
- `clearOldUpdates(olderThanMillis)` - cleanup

### 1b. History queries (already exists)

`readChapters` table already exists. Add queries to `LibraryRepository`:
- `historyEntries(): List<ReadChapterWithManga>` - JOIN `readChapters` + `libraryManga` for title/thumbnail, ordered by `readAt DESC`
- `clearHistory(sourceId, chapterUrl)` - delete single entry
- `clearAllHistory()` - delete all

---

## Phase 2: Navigation Shell (app module)

### 2a. New `Screen.kt` structure

```kotlin
sealed interface Screen {
    // Tab screens (top-level)
    data object Library : Screen
    data object Updates : Screen
    data object History : Screen
    data object Browse : Screen  // replaces Catalog
    data object More : Screen

    // Detail screens (pushed on top)
    data object Settings : Screen
    data object DownloadManager : Screen
    data object ExtensionManagement : Screen
    data object MultiSourceSearch : Screen
    data object Notifications : Screen
    data class SourceBrowse(...) : Screen
    data class MangaDetail(...) : Screen
    data class Reader(...) : Screen
}
```

### 2b. New `Main.kt` - Navigation shell

Replace `when(screen)` with `NavigationSuiteScaffold` + tab logic.

---

## Phase 3: New Screens

### 3a. `UpdatesScreen.kt`

```
UpdatesScreen
├── TopAppBar (title + filter button)
├── LazyColumn
│   └── items grouped by date header
│       └── UpdateItem (thumbnail, title, chapter name, time ago)
└── Click → pushedScreen = MangaDetail
```

Data: `LibraryRepository.allUpdates()` + merge with live check from sources.

### 3b. `HistoryScreen.kt`

```
HistoryScreen
├── TopAppBar (title + search + clear all)
├── LazyColumn
│   └── items grouped by date header
│       └── HistoryItem (thumbnail, title, chapter name, "Resume" button, time ago)
└── Click → pushedScreen = MangaDetail
   Resume → pushedScreen = Reader
```

Data: `LibraryRepository.historyEntries()`.

### 3c. `BrowseScreen.kt` (replaces `CatalogScreen`)

```
BrowseScreen
├── TabbedScreen (3 sub-tabs via HorizontalPager)
│   ├── [0] SourcesTab
│   │   └── list enabled sources grouped by language
│   │   └── click → pushedScreen = SourceBrowse
│   ├── [1] ExtensionsTab (current CatalogScreen logic)
│   │   └── list installed/available extensions
│   │   └── click → ExtensionDetails
│   └── [2] MigrationTab
│       └── list sources to migrate from
│       └── click → MigrateMangaScreen
```

### 3d. `MoreScreen.kt`

```
MoreScreen
├── Header (app logo/version)
├── Downloaded Only toggle
├── Incognito Mode toggle
├── Download Queue → pushedScreen = DownloadManager
├── Categories → pushedScreen = CategoryScreen (future)
├── Stats → (future)
├── Settings → pushedScreen = Settings
├── About → pushedScreen = Settings (about section)
```

---

## Phase 4: Refactor Existing Screens

| Screen | Change |
|--------|--------|
| `LibraryScreen` | Strip TopAppBar actions (search/downloads/settings icons → move to More/Browse tabs). Keep search + grid. |
| `CatalogScreen` | Becomes `ExtensionsTab` inside `BrowseScreen`. Remove standalone scaffolding. |
| `SourceBrowseScreen` | No change, stays as detail screen. |
| `MangaDetailScreen` | `backTo` points to parent tab instead of flat screen. |
| `ReaderScreen` | Same, `backTo` adjusted. |
| `SettingsScreen` | Remove `onBack = { screen = Screen.Library }`, now `pushedScreen = null`. |
| `DownloadManagerScreen` | Same back adjustment. |

---

## Phase 5: Badge System

| Tab | Badge Source | Logic |
|-----|-------------|-------|
| **Updates** | `updateHistory` table count since last visit | Show unread update count |
| **Browse** | Extension update count (new `.apk` available) | Show extension update count |
| **Library** | Unread notification count (already exists) | Move from TopAppBar to badge |

---

## Execution Order

```
Phase 1  →  Data layer (DB table + queries)
Phase 2  →  Navigation shell (Screen.kt + Main.kt refactor)
Phase 3  →  New screens (Updates, History, Browse, More)
Phase 4  →  Refactor existing screens (strip scaffolding, fix back navigation)
Phase 5  →  Badge system
```

Each phase gated by: `build-error-resolver` → `code-reviewer` → `security-reviewer`

## Files Changed/Created

**New files:**
- `extension-loader/.../library/UpdateHistory.sq`
- `app/.../ui/UpdatesScreen.kt`
- `app/.../ui/HistoryScreen.kt`
- `app/.../ui/BrowseScreen.kt`
- `app/.../ui/MoreScreen.kt`
- `app/.../ui/SourcesTab.kt` (inside BrowseScreen or separate)
- `app/.../ui/MigrationTab.kt`

**Modified files:**
- `app/.../ui/Screen.kt` - add `Updates`, `History`, `Browse`, `More`
- `app/.../Main.kt` - replace `when(screen)` with `NavigationSuiteScaffold` + tab logic
- `app/.../ui/LibraryScreen.kt` - strip TopAppBar actions
- `app/.../ui/CatalogScreen.kt` - refactor into `ExtensionsTab`
- `extension-loader/.../library/LibraryRepository.kt` - add update/history queries

## Risks

1. **Back navigation complexity** - Desktop has no hardware back button. Need explicit back-to-tab logic.
2. **`NavigationSuiteScaffold` availability** - Material3 adaptive navigation might need compose-material3-adaptive dependency.
3. **DB migration** - Adding `updateHistory` table to existing DB needs `ensureTables()` update.
4. **State management** - Tab state must survive screen switches (rememberSaveable equivalent on desktop).
