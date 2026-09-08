# Architecture

## Overview

Mihon Desktop is a JVM-based manga reader that loads Android extensions from [keiyoushi/extensions](https://github.com/keiyoushi/extensions) without an Android runtime. The architecture is modular, with a strict dependency chain and minimal Android stub layer.

## Module Graph

```
platform-compat  →  source-api  →  extension-loader  →  app
```

Each module depends only on the one below. No reverse dependencies allowed.

## Module Details

### `platform-compat`

**Purpose:** Android/androidx stand-ins + networking

This module provides JVM-native classes with the exact fully-qualified names that extension bytecode references. Two categories:

1. **Android Stand-ins** — Minimal implementations of Android types:
   - `android.content.Context` — file path resolution
   - `android.content.SharedPreferences` — properties file-backed persistence
   - `android.app.Application` — singleton with `filesDir`/`cacheDir`
   - `android.net.Uri` — string wrapper
   - `android.graphics.*` — no-op shapes
   - `android.os.SystemClock` — `System.currentTimeMillis()` wrapper

2. **Networking** — OkHttp-based networking:
   - `NetworkHelper` — OkHttpClient with required interceptors
   - `Requests.kt` — `GET`/`POST`/`PUT`/`DELETE` helpers with cache control
   - `OkHttpExtensions.kt` — Rx/coroutine bridging
   - `AndroidCookieJar` — in-memory cookie storage
   - `CloudflareInterceptor` — detects 403/503 challenges, delegates to JCEF solver

**Critical Constraint:** FQCNs are load-bearing. Renaming or repackaging any class breaks all extensions at runtime.

### `source-api`

**Purpose:** Mihon's Source/HttpSource/CatalogueSource contracts

This is upstream Mihon's `source-api` module, copied unmodified except for:
- Dropping `@Stable`/`@androidx.compose.runtime` annotations (no runtime effect)
- Replacing Kotlin context receivers with explicit parameters (non-behavioral)

**Critical Constraint:** Must stay close to upstream. This is the contract extension jars were compiled against.

### `extension-loader`

**Purpose:** Extension discovery, loading, catalog, downloads, library DB

Key components:

- **`ExtensionMetadataReader`** — Parses `AndroidManifest.xml` as plain text XML to extract source class names, display name, NSFW flag
- **`ExtensionLoader`** — Opens `URLClassLoader` over extension JAR, instantiates source classes
- **`DesktopExtensionRuntime`** — Registers Injekt singletons (`Context`, `Application`, `NetworkHelper`, `Json`)
- **`catalog/`** — Fetches keiyoushi catalog:
  - `CatalogClient` — Resolves `repo.json` → `index_v2` → gzip+protobuf catalog
  - `ExtensionDownloader` — Downloads+caches JARs, verifies sha256 manifest
  - `GitHubReleaseAssetResolver` — Fallback for jar/apk in different releases
- **`library/`** — SQLDelight schema:
  - `libraryManga` — Saved manga with extension metadata
  - `readingProgress` — Last chapter+page per manga
  - `downloadedChapters` — Offline chapter storage
- **`backup/`** — `BackupManager` for JSON export/import
- **`download/`** — `DownloadManager` for offline chapter storage
- **`cache/`** — `ImageCache` with LRU eviction
- **`js/`** — `DesktopJavaScriptEngine` (QuickJS → javax.script fallback)
- **`log/`** — `Logger` for structured logging
- **`prefs/`** — `AppPreferences` for settings persistence

### `app`

**Purpose:** Compose Desktop UI

Single-window application with `sealed interface Screen` navigation:

| Screen | Purpose |
|--------|---------|
| `Library` | Home screen, saved manga grid |
| `Catalog` | Browse/install from keiyoushi catalog |
| `SourceBrowse` | Popular/search grid for one source |
| `MangaDetail` | Manga details + chapter list |
| `Reader` | Page-by-page reader with zoom/pan |
| `Settings` | Configuration (updates, theme, reading direction) |
| `DownloadManager` | Offline chapter management |
| `ExtensionManagement` | Installed extension management |
| `MultiSourceSearch` | Search across multiple sources |
| `Notifications` | Notification center |

Entry point: `mihon.desktop.app.MainKt`

## Data Flow

### Extension Loading

1. User selects extension from catalog
2. `ExtensionDownloader` downloads JAR to `~/.mihon-desktop/extension-cache/`
3. `ExtensionLoader` opens `URLClassLoader` over JAR
4. `DesktopExtensionRuntime.bootstrap()` registers singletons (if not already)
5. Source class instantiated via reflection
6. Source methods called for manga listing, chapter fetching, page loading

### Library Management

1. User adds manga to library via `MangaDetailScreen`
2. `LibraryRepository` inserts into `libraryManga` table
3. `LibraryUpdateScheduler` periodically checks for new chapters
4. `ReadingProgress` updated on every page turn in reader

### Offline Reading

1. User downloads chapters via `DownloadManagerScreen`
2. `DownloadManager` fetches pages, stores in `~/.mihon-desktop/downloads/`
3. `ReaderScreen` checks for local files before network fetch

## Design Decisions

### Why Extension JARs, Not APKs

Extension JARs contain standard JVM bytecode (`CAFEBABE` magic, class file version 55). APKs contain DEX bytecode (Dalvik/ART) which cannot be loaded by `URLClassLoader`. See [RESEARCH.md](RESEARCH.md) §3 for details.

### Why Minimal Android Stubs

Extension bytecode references Android types by name, but only a small subset is actually used. We provide JVM classes with:
- Exact FQCNs matching extension expectations
- Just enough behavior to satisfy `source-api`'s own code
- Documented gaps (e.g., `CloudflareInterceptor` is pass-through without JCEF)

Missing stubs manifest as `NoClassDefFoundError` at runtime, not compile time. See [RESEARCH.md](RESEARCH.md) §4-5 for the investigation process.

### Why Copy Upstream Mihon Source

`source-api` is the contract extension JARs were compiled against. Keeping it byte-for-byte close to upstream:
- Ensures compatibility with all extensions
- Makes upstream changes easy to diff against
- Prevents subtle behavioral differences

### Why SQLDelight

Same generated query code Android would use, just a different `SqlDriver` implementation. This ensures:
- Schema compatibility with upstream Mihon
- Type-safe queries
- Migration support

## Testing

Unit tests in `extension-loader/src/test/` use JUnit 4:

```bash
./gradlew :extension-loader:test
```

No integration tests or UI tests exist yet. Testing new extensions requires:
1. Running the app
2. Loading the extension
3. Reading stack traces for missing symbols
4. Adding stubs to `platform-compat`

See [RESEARCH.md](RESEARCH.md) §4-5 for real examples.

## Performance Considerations

- **Image caching:** `AsyncImage` uses LRU disk cache keyed on URL string
- **Network caching:** OkHttp with 10MB cache, `GET` helper applies 10-minute cache control
- **Background updates:** `ScheduledExecutorService` runs every 60 minutes (configurable)
- **Coroutine usage:** All network calls are `suspend` + `Dispatchers.IO`

## Security Considerations

- Extension JARs are verified against keiyoushi's sha256 manifest
- Verification is skipped (not blocking) if manifest can't be fetched
- No sandboxing of loaded extensions (they run in the same JVM)
- Optional JCEF integration for Cloudflare bypass

## Future Considerations

- **Image caching:** Proper LRU disk cache for manga pages
- **Chapter sorting/filtering:** Currently listed in source order
- **Batch operations:** Download all chapters, batch-remove from library
- **Notification system:** Chapter update notifications

See [ROADMAP.md](ROADMAP.md) for planned features.
