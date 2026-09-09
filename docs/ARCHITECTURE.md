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
    - `NetworkHelper` — OkHttpClient with required interceptors; optional DNS-over-HTTPS (`DohDns`, see below)
    - `Requests.kt` — `GET`/`POST`/`PUT`/`DELETE` helpers with cache control
    - `OkHttpExtensions.kt` — Rx/coroutine bridging
    - `AndroidCookieJar` — in-memory cookie storage
    - `DohDns` — opt-in DNS-over-HTTPS (Google `dns.google` / Cloudflare `cloudflare-dns.com`) with automatic fallback to system DNS; read once at bootstrap (restart to apply)
    - `CloudflareInterceptor` — challenge detection via `cf-mitigated` header + bounded body scan; JCEF solver when available, browser-UA retry + clear `CloudflareChallengeException` otherwise

3. **QuickJS shim** — `app.cash.quickjs.QuickJs`/`QuickJsException`:
   - Extension jars reference `app.cash.quickjs.QuickJs` by exact FQCN (same binding Mihon Android links). The real `app.cash.quickjs:quickjs-jvm` artifact can't be used on desktop: its Linux native requires system `libc++.so.1`/`libc++abi.so.1` (not shipped by stock distros).
   - The shim reimplements the cashapp API surface extensions actually use — `create()`, `evaluate(String[, filename])`, `compile(String, String)`, `execute(ByteArray)` (bytecode is portable across instances), `close()`, `QuickJsException` — on top of `io.github.dokar3:quickjs-kt-jvm`, whose natives are self-contained for Linux x64/aarch64, macOS x64/aarch64, and Windows x64.
   - `set()`/`get()` Java-object marshaling is absent (no extension in the wild uses it); the methods exist for binary compatibility and throw a descriptive `QuickJsException`.

**Critical Constraint:** FQCNs are load-bearing. Renaming or repackaging any class breaks all extensions at runtime.

### `source-api`

**Purpose:** Mihon's Source/HttpSource/CatalogueSource contracts

This is upstream Mihon's `source-api` module, copied unmodified except for:
- Dropping `@Stable`/`@androidx.compose.runtime` annotations (no runtime effect)
- Replacing Kotlin context receivers with explicit parameters (non-behavioral)

**Critical Constraint:** Must stay close to upstream. This is the contract extension jars were compiled against.

### `extension-loader`

**Purpose:** Extension discovery, loading, updates, catalog, downloads, library DB, migration, trackers, backups

Key components:

- **`ExtensionMetadataReader`** — Parses `AndroidManifest.xml` as plain text XML to extract source class names, display name, NSFW flag
- **`ExtensionLoader`** — Opens `URLClassLoader` over extension JAR, instantiates source classes; caches `LoadedExtension`s keyed on `(path, lastModified, length)` so an updated jar reloads fresh without a restart
- **`JarIntegrity`** — sha256 sidecar written at install/update time, verified at jar load (tamper-evidence; same-user forgeable until signing exists)
- **`DesktopExtensionRuntime`** — Registers Injekt singletons (`Context`, `Application`, `NetworkHelper`, `Json`)
- **`catalog/`** — Fetches keiyoushi catalog and keeps extensions current:
  - `CatalogClient` — Resolves `repo.json` → `index_v2` → gzip+protobuf catalog; every catalog-derived URL passes `requireHttps` (non-https rejected before it reaches OkHttp)
  - `ExtensionDownloader` — Downloads+caches JARs, verifies sha256 manifest
  - `GitHubReleaseAssetResolver` — Fallback for jar/apk in different releases
  - `ExtensionUpdateManager` — Update pipeline: `discoverInstalled` parses cached jars' manifests only (no class loading); `findUpdates` compares against the catalog (`VersionComparator`: versionCode when present, else version-name compare); `applyUpdate` downloads the new jar (sha256 + zip validated **before** the old file is touched), re-points `libraryManga`/`updateHistory` rows at the new jar file name, then retires the old jar via `ExtensionLoader.invalidateForUpdate`. One update per package at a time (Mutex). `pendingUpdates` is a `StateFlow` the Browse badge and extension screens observe
- **`library/`** — SQLDelight schema:
  - `libraryManga` — Saved manga with extension metadata
  - `readingProgress` — Last chapter+page per manga
  - `downloadedChapters` — Offline chapter storage
  - `readChapters` — History (read-at timestamps, chapter names)
  - `updateHistory` — Chapter updates feed (Updates tab)
  - `category` (`id`, unique `name`, `sortOrder`) + `mangaCategory` (`mangaId`, `categoryId` PK pair, FK cascades) — Mihon-style categories; assignment via `MangaDetail` dialog, filter via `LibraryScreen`, ordering preserved through migration and `.tachibk` export (as category **order** values, Mihon's semantics)
  - `tracker` — One row per (library manga, tracker), kept stable by select-then-update upserts
  - `readerPreferences` — Per-manga reader settings (direction, dual page, transition)
  - `StatsRepository` — Read-only aggregate snapshot in one transaction: totals, per-source/per-category breakdowns, download counts, 7/30-day reading windows
  - `NotificationManager` + `AwtSystemNotifier` — In-app notification center + OS notifications via AWT `SystemTray` (EDT-only, lazy registration with retry, headless-safe no-op)
- **`migration/`** — `MigrationEngine`: source-to-source library migration. Candidates ranked (exact normalized-title → startsWith → contains); auto-migrate unambiguous exact matches, everything else goes to a manual-match prompt. Copies reading state and categories, optionally re-downloads chapters and deletes the old entry. Old chapter numbers are recovered from stored chapter names (no network round-trip — works when the old source is dead). Per-manga DB writes run in one `NonCancellable` transaction; one failing manga never aborts the batch
- **`tracker/`** — Tracking sync (AniList, MyAnimeList):
  - `Tracker` interface: `prepareLogin()` returns an authorize URL + paste instructions; `login(paste)` verifies the pasted credential
  - `AniListTracker` — implicit OAuth grant (paste the access token); `MyAnimeListTracker` — PKCE (paste the `code=` redirect URL or raw code; custom client id via preference, bundled default otherwise)
  - `TrackerAuthStore` — tokens in `~/.mihon-desktop/tracker-auth.properties`, owner-only POSIX permissions, atomic rewrites; deliberately excluded from every backup format
  - `TrackerRepository` — persists bindings in the `tracker` table
  - `TrackerManager` — sync hooks: `pushChapterRead` from the reader's chapter-finished path and MangaDetail's mark-as-read. Fire-and-forget, monotonic (never regresses the remote), no-op in incognito mode
- **`backup/`** — Three layers:
  - `BackupManager` — JSON v1 export/import (full fidelity: library, reading progress, download metadata)
  - `AutoBackupScheduler` — automatic backups into `backups/auto/` on an interval (default off), rotating to keep the newest 5 per format, JSON and/or `.tachibk`
  - `backup/tachibk/` — Mihon `.tachibk` interop (gzipped protobuf):
    - `TachibkCodec` — hand-written protobuf codec over `CodedInputStream`/`CodedOutputStream` (no protoc/codegen; the only new dependency is `com.google.protobuf:protobuf-java`). Field numbers verified against Mihon v0.20.4's `@ProtoNumber` models — see `TachibkModels.kt` for the upstream URLs and the deviations from the old Tachiyomi `.proto` era (no `lastReadAt`, `lastPageRead` is field 6, `BackupManga.status` is 8, `BackupCategory.flags` is 100, manga categories are `order` values not indices)
    - `TachibkGzip` — gzip magic sniffing + capped decompression (256 MB)
    - `TachibkManager` — import/export. Import merges like the JSON path (match by `(sourceId, url)`, `updateMeta` in place, stable row ids). Manga whose source is neither installed nor referenced by an existing library row are **skipped, not imported as zombie rows** (an empty `jarFileName` can never load a source); the count is reported in the import summary. Export writes library, read chapters, history timestamps, progress and categories — structurally valid for Android Mihon (cross-checked against kotlinx.serialization.protobuf, the exact serializer Mihon uses, in `TachibkKotlinxInteropTest`)
- **`download/`** — Offline chapter storage, two layers:
  - `DownloadManager` — direct page fetch + disk write + DB record for one chapter
  - `DownloadQueue` — the queue UI drives: FIFO with N parallel chapters (default 2, 1–4 via the Settings slider, read live at every worker pickup so changes apply without a restart). Retry with exponential backoff (1s/4s/16s, max attempts; `Error` fails immediately — deterministic, no retry). Pause stops pickup and cancels running workers (already-written pages kept, resume skips them); cancel removes jobs. Exactly one summary notification per drained batch. Workers launch with `CoroutineStart.ATOMIC` so a cancel between launch and first dispatch still reaches cleanup. Queue is in-memory; completed chapters persist as DB rows + files
- **`filters/`** — `FilterUiState`: maps a source's `FilterList` (all filter types: text, select, checkbox, checkbox-group, tri-state, sort) to the side-panel UI in `SourceBrowseScreen`
- **`cache/`** — `ImageCache` with LRU eviction
- **`js/`** — `DesktopJavaScriptEngine`: real QuickJS (via the `app.cash.quickjs` compatibility shim in `platform-compat`, backed by `io.github.dokar3:quickjs-kt-jvm`) with a `javax.script` fallback; a runtime probe picks the engine and failures degrade to a clear error instead of a crash
- **`log/`** — `Logger` for structured logging
- **`prefs/`** — `AppPreferences` for settings persistence (properties-file backed, change listeners for reactive UI)

### `app`

**Purpose:** Compose Desktop UI

Single-window application, 5-tab navigation (`NavigationRail`) with detail screens pushed on top:

| Screen | Purpose |
|--------|---------|
| `Library` (tab) | Saved manga grid, search/sort, category filter |
| `Updates` (tab) | Chapter update feed, grouped by date |
| `History` (tab) | Read chapters, grouped by date |
| `Browse` (tab) | Sources / Extensions / Migration sub-tabs; extension-update badge |
| `More` (tab) | Incognito + downloaded-only toggles, categories, stats, downloads, settings, about |
| `Settings` | Updates, theme preset (8), language, reader, DoH, backups, trackers, notifications |
| `DownloadManager` | Live queue view + downloaded chapters |
| `ExtensionManagement` | Installed extensions, update/update-all, uninstall |
| `MultiSourceSearch` | Global search across installed sources |
| `Notifications` | In-app notification center |
| `Categories` | Create/rename/delete/reorder categories |
| `Stats` | Library statistics snapshot |
| `MigrateManga` | 4-step migration wizard (pick manga → pick target → options → run) |
| `SourceBrowse` | Popular/search grid for one source, filter side panel |
| `SourceSettings` | Edit a `ConfigurableSource`'s persisted preferences |
| `MangaDetail` | Manga details + chapter list (sort, filters, batch download, categories, tracking) |
| `Reader` | Paged (LTR/RTL, dual-page, transitions) + webtoon; fullscreen, brightness, preload, retry |

**i18n:** `Strings` object — UTF-8 properties bundles on the classpath (`strings.properties` English fallback, `strings_id.properties` Indonesian; 393 keys each). Lookup: active locale → English → raw key. Live locale switch: `languageTick` state bumps and every `t()` call site recomposes. `extension-loader` (which can't depend on the app's string table) posts notification message *keys* resolved through a `NotificationManager.resolver` seam at display time.

**Theming:** `ThemePresets` — 8 hand-tuned presets (Default, Lavender, Strawberry Daiquiri, Midnight Dune, Green Apple, Teal Turkey, Tako, Yotsuba), each with explicit light and dark Material schemes; the choice persists via `AppPreferences`.

Entry point: `mihon.desktop.app.MainKt`

## Data Flow

### Extension Loading

1. User selects extension from catalog
2. `ExtensionDownloader` downloads JAR to `~/.mihon-desktop/extension-cache/`
3. `ExtensionLoader` opens `URLClassLoader` over JAR
4. `DesktopExtensionRuntime.bootstrap()` registers singletons (if not already)
5. Source class instantiated via reflection
6. Source methods called for manga listing, chapter fetching, page loading

### Extension Update

1. `ExtensionUpdateManager.discoverInstalled()` reads cached jars' manifests (no class loading)
2. `findUpdates()` matches against the catalog; `pendingUpdates` drives the Browse badge
3. `applyUpdate()`: download new jar → validate sha256 + zip → re-point `libraryManga`/`updateHistory` rows → retire old jar
4. Next `ExtensionLoader.load` sees the changed (path, lastModified, length) and re-instantiates sources — no restart

### Library Management

1. User adds manga to library via `MangaDetailScreen`
2. `LibraryRepository` inserts into `libraryManga` table
3. `LibraryUpdateScheduler` periodically checks for new chapters (can be disabled/retuned in Settings; scheduler is recreated on settings change)
4. `ReadingProgress` updated on every page turn in reader
5. Chapter-finished also fires `TrackerManager.pushChapterRead` (unless incognito)

### Offline Reading

1. User queues chapters (single or select-mode batch) via `MangaDetailScreen`
2. `DownloadQueue` runs N parallel workers: page fetch → disk write → DB record, with retry/backoff
3. Drained batch posts one summary notification (in-app + optional OS tray)
4. `ReaderScreen` checks for local files before network fetch

### Migration

1. Browse → Migration tab picks a source with library manga
2. `MigrateMangaScreen` wizard: pick manga → pick target source → options (categories, read chapters, re-download, delete old) → run
3. `MigrationEngine` searches the target (ranked candidates), auto-migrates exact matches, prompts for the rest
4. Per-manga atomic DB transaction; failures are isolated and reported per manga

## Design Decisions

### Why Extension JARs, Not APKs

Extension JARs contain standard JVM bytecode (`CAFEBABE` magic, class file version 55). APKs contain DEX bytecode (Dalvik/ART) which cannot be loaded by `URLClassLoader`. See [RESEARCH.md](RESEARCH.md) §3 for details.

### Why Minimal Android Stubs

Extension bytecode references Android types by name, but only a small subset is actually used. We provide JVM classes with:
- Exact FQCNs matching extension expectations
- Just enough behavior to satisfy `source-api`'s own code
- Documented gaps (e.g., Cloudflare challenges are unsolvable without JCEF on the classpath)

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

### Why a Hand-Written `.tachibk` Codec

Mihon encodes `.tachibk` with kotlinx.serialization.protobuf over `@ProtoNumber`-annotated models. Rather than copying those model classes (and their transitive dependencies) or adding protoc/codegen, `TachibkCodec` hand-writes the wire format over `protobuf-java`'s `CodedInputStream`/`CodedOutputStream`. Encoding rules mirror kotlinx: proto3 defaults are omitted, unknown fields skipped on decode and never emitted. Correctness is pinned by `TachibkKotlinxInteropTest`, which round-trips our output through the real kotlinx serializer (the one Android Mihon uses).

One sharp edge discovered: fields **without** defaults (Mihon's `BackupHistory.lastRead`) must never be written as proto3 zero-values — kotlinx omits them and Mihon's restorer errors per-manga. `TachibkManager` stamps `lastRead` with the export time instead of 0.

### Why dokar's QuickJS, Not cashapp's

Extension jars link against `app.cash.quickjs.QuickJs` by exact FQCN, so the API surface must match. But the cashapp `quickjs-jvm` artifact's Linux native requires system `libc++.so.1`/`libc++abi.so.1`, which stock distros don't ship — unusable for a "single jar, no system deps" desktop app. `io.github.dokar3:quickjs-kt-jvm` ships self-contained natives for all five desktop platforms, so the shim keeps the cashapp FQCN/API and swaps the engine underneath. (Pinned at 1.0.5: newer releases need Kotlin metadata our toolchain can't read — see `gradle/libs.versions.toml`.)

## Testing

377 JUnit 4 tests across three modules:

```bash
./gradlew test                    # everything
./gradlew :extension-loader:test  # 323
./gradlew :platform-compat:test   # 35
./gradlew :app:test               # 19
```

- **Integration harness** (`extension-loader/src/test/.../integration/`): builds a synthetic extension jar in-test — two tiny Java sources compiled in-memory by the system javac, a plain-text `AndroidManifest.xml`, zipped into a temp dir — and drives the full discovery → integrity sidecar → metadata → `URLClassLoader` → instantiation → loader-cache pipeline. Hermetic, zero network, no real extension shipped.
- **Fixture patterns:** repositories take injectable databases/clock seams (`TemporaryFolder`, in-memory SQLite); networked components take fake `OkHttpClient`s or executor seams (`Tracker`, `DownloadQueue` backoff sleeper); AWT-touching code checks `SystemTray.isSupported` and degrades silently.
- **Interop test:** `TachibkKotlinxInteropTest` cross-checks the hand-written codec against kotlinx.serialization.protobuf.

CI (`.github/workflows/ci.yml`) runs `./gradlew build` on every push/PR (ubuntu-latest, Temurin 21) and uploads test reports on failure. No UI/E2E tests exist; testing a brand-new extension still means running the app, loading it, and reading stack traces for missing stubs (see [RESEARCH.md](RESEARCH.md) §4-5).

## Performance Considerations

- **Image caching:** `AsyncImage` uses LRU disk cache keyed on URL string
- **Network caching:** OkHttp with 10MB cache, `GET` helper applies 10-minute cache control
- **Background updates:** `ScheduledExecutorService` runs every 60 minutes (configurable, disableable)
- **Coroutine usage:** All network calls are `suspend` + `Dispatchers.IO`
- **Download concurrency:** queue runs 1–4 parallel chapter workers (default 2), pages sequential within a chapter
- **Stats:** computed entirely in SQL inside one transaction, rendered from an immutable snapshot

## Security Considerations

- Extension JARs are verified against keiyoushi's sha256 manifest at download, and against a local sha256 sidecar at load (tamper-evident; same-user forgeable until signing exists)
- Every catalog-derived URL is rejected unless https (`requireHttps`)
- Verification is skipped (not blocking) if the manifest can't be fetched
- No sandboxing of loaded extensions (they run in the same JVM)
- Tracker tokens live in `~/.mihon-desktop/tracker-auth.properties` with owner-only POSIX permissions, atomic rewrites, and are excluded from all backup formats
- Optional JCEF integration for Cloudflare bypass

## Future Considerations

- **More trackers** — Kitsu, Shikimori, Komga
- **Tracker data in `.tachibk` export** — currently library-only
- **`QuickJSInterceptor` stub** — some extensions probe for it
- **Sidecar signing** — tamper-evidence without a forgeability guarantee today

See [ROADMAP.md](ROADMAP.md) for planned features.
