# Roadmap

This document outlines the project phases and future plans.

## Current Status

All planned phases are complete. The project is functional and can:
- Browse, install, and update extensions from the live keiyoushi catalog
- Read manga through loaded extensions (paged LTR/RTL, webtoon, dual-page, transitions, fullscreen)
- Maintain a persistent library with reading progress, categories, and stats
- Search globally across all installed sources
- Download chapters for offline reading (queue with concurrency, pause/cancel/retry)
- Migrate the library between sources (4-step wizard, auto/manual matching)
- Sync reading progress to AniList and MyAnimeList
- Export/import backups (JSON and Mihon-compatible `.tachibk`) with auto-backup scheduling
- Show OS notifications and update badges, in English or Indonesian, with 8 theme presets

See [README.md](../README.md) for feature overview.

## Phase 1 — Harden the Loader ✅

**Goal:** Ensure extensions load and run correctly on JVM.

### Completed

- [x] Test extensions across multisrc families for missing stubs
- [x] Add missing stub classes/interceptors:
  - `Injekt.get<Application>()`
  - `UncaughtExceptionInterceptor`
  - `UserAgentInterceptor`
  - `CloudflareInterceptor` (challenge detection + retry; JCEF solver optional)
  - `okhttp3.brotli`/`okhttp3.zstd`
  - `android.os.SystemClock`
- [x] `Application` has real `filesDir`/`cacheDir` under `~/.mihon-desktop`
- [x] `ConfigurableSource.getSourcePreferences()` persists to real files
- [x] Extension catalog: fetch keiyoushi's `repo.json` → `index_v2` → gzip+protobuf
- [x] `CatalogClient` and `ExtensionDownloader` are `suspend` functions
- [x] `ExtensionDownloader` verifies downloads against sha256 manifest
- [x] HTTPS enforcement: every catalog-derived URL is rejected unless `https` (`requireHttps`)

## Phase 2 — Real UI ✅

**Goal:** Build a functional Compose Desktop GUI.

### Completed

- [x] Compose Desktop wired into `app` module
- [x] Navigation via `sealed interface Screen` (5 tabs: Library, Updates, History, Browse, More)
- [x] Screens:
  - `LibraryScreen` — saved manga grid (home screen), search/sort, category filter
  - `CatalogScreen` — search/install from keiyoushi catalog
  - `SourceBrowseScreen` — popular/search grid for one source, with full source-filter side panel
  - `MangaDetailScreen` — manga details + chapters (select-mode batch download, mark read/unread)
  - `ReaderScreen` — page-by-page reader with zoom/pan
- [x] Library persistence via SQLDelight
- [x] Reading progress tracking
- [x] Reader features:
  - Zoom/pan (scroll wheel, trackpad pinch)
  - Keyboard navigation (←/→, PgUp/PgDn)
  - Webtoon/vertical-scroll toggle
  - Dual-page mode (per-manga; RTL pairs ordered right-to-left)
  - Page transitions: none/slide/fade (per-manga; paged mode only)
  - Fullscreen (F key — borderless window via dispose/undecorated/re-show in Main.kt)
  - Brightness filter (global; black scrim, does not change system brightness)
  - Page preload tuning (global, 0-10; in-memory window around current page + disk cache)
  - Retry failed page (R key / button; paged + webtoon)
  - Skipped: continuous horizontal scroll mode (low value alongside LTR/RTL paged + webtoon)
  - Skipped: wide-image-shown-single in dual page (aspect ratio unknown before decode)
- [x] Downloads (offline chapter storage)
- [x] Library refresh ("Check for updates")
- [x] Global search (MultiSourceSearchScreen reachable from the Library tab)

## Phase 3 — Platform Integrations ✅

**Goal:** Add background processes and optional features.

### Completed

- [x] Background library updates:
  - `LibraryUpdateScheduler` using `ScheduledExecutorService`
  - Runs every 60 minutes (configurable), can be disabled entirely (Settings toggle)
  - Results shown as badges on library manga cards
- [x] Cloudflare bypass:
  - `CloudflareInterceptor` detects challenges (`cf-mitigated` header, 403/503 + bounded body scan)
  - `JcefCloudflareSolver` uses JCEF for JS challenges
  - JCEF is `compileOnly` — add to runtime classpath when needed
  - Without JCEF: one browser-UA retry, then a clear `CloudflareChallengeException`
- [x] Network hardening:
  - DNS-over-HTTPS (`DohDns`: Google/Cloudflare, opt-in, system-DNS fallback)
  - Settings toggle + provider selector (applies after restart)
- [x] QuickJS support:
  - `DesktopJavaScriptEngine` uses real QuickJS via JNI
  - `app.cash.quickjs.QuickJs` compatibility shim in `platform-compat` (backed by `io.github.dokar3:quickjs-kt-jvm`) keeps extension FQCNs working
  - Bundled by default; falls back to `javax.script` (GraalJS) if the native lib can't load
- [x] Backups:
  - `BackupManager` exports/imports library as JSON
  - Mihon `.tachibk` import/export (see Phase 4)
  - Timestamped backup files under `~/.mihon-desktop/backups/`
  - Settings screen has create/restore buttons
- [x] Packaging:
  - `jpackage` task creates native installers
  - dmg (macOS), deb (Linux), exe (Windows)
  - Uses `packageUberJarForCurrentOS` for fat jar

## Phase 4 — Library & Platform Features ✅

**Goal:** Reach practical daily-reader parity with Mihon's core loop.

### Completed

- [x] Categories:
  - `category`/`mangaCategory` tables (SQLDelight), create/rename/delete/reorder
  - Library category filter, per-manga assignment dialog (MangaDetail)
  - Stats per category; categories carried through migration and `.tachibk` export
- [x] Migration:
  - `MigrationEngine` — source-to-source move of library manga with reading state,
    categories, and optional chapter re-download; works even when the old source is dead
    (chapter numbers recovered from stored chapter names, no network round-trip)
  - `MigrateMangaScreen` 4-step wizard (pick manga → pick target → options → run)
    with exact-match auto-matching and a manual-match prompt for ambiguous cases
  - Per-manga atomic DB transaction, failure isolation (one bad manga never aborts the batch)
- [x] Extension updates:
  - `ExtensionUpdateManager` version check (versionCode, else version-name compare)
  - Update badge on the Browse tab, per-extension update, "Update all"
  - `applyUpdate` validates the new jar (sha256 + zip) before touching the old file,
    re-points DB rows at the new jar, and retires the old one — no restart needed
- [x] Trackers (AniList + MyAnimeList):
  - Token-paste OAuth (AniList implicit grant; MAL PKCE with code paste)
  - Tokens in `~/.mihon-desktop/tracker-auth.properties` (owner-only perms, excluded from backups)
  - Bind manga via search dialog (MangaDetail), progress pushed from the reader
    (monotonic, fire-and-forget, no-op in incognito), login/logout in Settings
- [x] `.tachibk` interop (Mihon v0.20.4-compatible):
  - Hand-written protobuf codec (`TachibkCodec`), no protoc/codegen
  - Import merges by `(sourceId, url)`; export is structurally valid for Android Mihon
    (cross-checked against kotlinx.serialization.protobuf in tests)
  - Auto-backup scheduler (`backups/auto/`, interval, newest-5 rotation, JSON or `.tachibk`)
- [x] Download manager queue:
  - FIFO with N parallel chapters (default 2, 1–4, live-adjustable in Settings)
  - Pause/cancel/retry with exponential backoff; one summary notification per drained batch
- [x] Stats screen (library totals, per-source/per-category breakdowns, 7/30-day reading activity)
- [x] OS notifications (AWT `SystemTray`, headless-safe) + in-app notification center
- [x] Theme presets (8 Mihon-inspired light/dark schemes)
- [x] Localization (EN/ID, 393 keys each, live locale switch without restart)
- [x] Per-source settings UI (edit a `ConfigurableSource`'s persisted preferences)
- [x] Testing & CI:
  - 377 JUnit tests across `platform-compat`, `extension-loader`, and `app`
  - Integration harness builds a synthetic extension jar in-test (no network, no real
    extension shipped) and drives the full load pipeline
  - GitHub Actions CI (`.github/workflows/ci.yml`): Temurin 21, `./gradlew build` on push/PR

## Known Issues

### Current Limitations

- **JCEF natives are large** — Each platform's native bundle is ~100MB
- **Trailer/extension data beyond the subset** — `.tachibk` import skips tracker bindings,
  preferences, and other unknown fields (they aren't re-emitted on export)
- **Download queue is in-memory** — Restarting the app clears pending/failed queue entries
  (completed chapters persist as DB rows + files)

### Bugs

- None reported yet

## Future Considerations

### Potential Features

- **Continuous horizontal scroll** reader mode (skipped for now — low value alongside LTR/RTL paged + webtoon)
- **Wide-image-shown-single in dual page** (aspect ratio unknown before decode)
- **More trackers** — Kitsu, Shikimori, Komga
- **Tracker data in `.tachibk` export** — currently library-only
- **`QuickJSInterceptor` stub** — some extensions probe for it; not yet present in `platform-compat`
- **Sidecar signing** — the sha256 sidecar is tamper-evident but same-user forgeable; a full fix needs signing
- **Single shared JDBC connection** — concurrent DB access is serialized at the query layer; a connection pool is a possible future improvement

### Not Planned

- Feature-parity Android widget/Biometric/Shizuku equivalents
- General-purpose Android API compatibility layer
- Android-specific security models (biometric, Shizuku)

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines.

## Research

See [RESEARCH.md](RESEARCH.md) for the investigation into running Mihon extensions on desktop.
