# Roadmap

This document outlines the project phases and future plans.

## Current Status

All planned phases are complete. The project is functional and can:
- Browse and install extensions from the live keiyoushi catalog
- Read manga through loaded extensions
- Maintain a persistent library with reading progress
- Download chapters for offline reading
- Export/import backups

See [README.md](../README.md) for feature overview.

## Phase 1 — Harden the Loader ✅

**Goal:** Ensure extensions load and run correctly on JVM.

### Completed

- [x] Test extensions across multisrc families for missing stubs
- [x] Add missing stub classes/interceptors:
  - `Injekt.get<Application>()`
  - `UncaughtExceptionInterceptor`
  - `UserAgentInterceptor`
  - `CloudflareInterceptor` (placeholder)
  - `okhttp3.brotli`/`okhttp3.zstd`
  - `android.os.SystemClock`
- [x] `Application` has real `filesDir`/`cacheDir` under `~/.mihon-desktop`
- [x] `ConfigurableSource.getSourcePreferences()` persists to real files
- [x] Extension catalog: fetch keiyoushi's `repo.json` → `index_v2` → gzip+protobuf
- [x] `CatalogClient` and `ExtensionDownloader` are `suspend` functions
- [x] `ExtensionDownloader` verifies downloads against sha256 manifest

## Phase 2 — Real UI ✅

**Goal:** Build a functional Compose Desktop GUI.

### Completed

- [x] Compose Desktop wired into `app` module
- [x] Navigation via `sealed interface Screen`
- [x] Screens:
  - `LibraryScreen` — saved manga grid (home screen)
  - `CatalogScreen` — search/install from keiyoushi catalog
  - `SourceBrowseScreen` — popular/search grid for one source
  - `MangaDetailScreen` — manga details + chapters
  - `ReaderScreen` — page-by-page reader with zoom/pan
- [x] Library persistence via SQLDelight
- [x] Reading progress tracking
- [x] Reader features:
  - Zoom/pan (scroll wheel, trackpad pinch)
  - Keyboard navigation (←/→, PgUp/PgDn)
  - Webtoon/vertical-scroll toggle
- [x] Downloads (offline chapter storage)
- [x] Library refresh ("Check for updates")

## Phase 3 — Platform Integrations ✅

**Goal:** Add background processes and optional features.

### Completed

- [x] Background library updates:
  - `LibraryUpdateScheduler` using `ScheduledExecutorService`
  - Runs every 60 minutes (configurable)
  - Results shown as badges on library manga cards
- [x] Cloudflare bypass:
  - `CloudflareInterceptor` detects 403/503 challenges
  - `JcefCloudflareSolver` uses JCEF for JS challenges
  - JCEF is `compileOnly` — add to runtime classpath when needed
  - Falls back to pass-through without JCEF
- [x] QuickJS support:
  - `DesktopJavaScriptEngine` uses `app.cash.quickjs:quickjs-jvm`
  - Falls back to `javax.script` (Nashorn/GraalJS)
  - Both are `compileOnly`
- [x] Backups:
  - `BackupManager` exports/imports library as JSON
  - Timestamped backup files under `~/.mihon-desktop/backups/`
  - Settings screen has create/restore buttons
- [x] Packaging:
  - `jpackage` task creates native installers
  - dmg (macOS), deb (Linux), exe (Windows)
  - Uses `packageUberJarForCurrentOS` for fat jar

## Known Issues

### Current Limitations

- **JCEF natives are large** — Each platform's native bundle is ~100MB
- **No image caching** — Pages are fetched fresh each time (except downloads)
- **No chapter sorting/filtering** — Chapters listed in source order
- **No batch operations** — Can't download all chapters at once or batch-remove from library

### Bugs

- None reported yet

## Future Considerations

### Potential Features

- **Image caching** — Proper LRU disk cache for manga pages
- **Chapter sorting/filtering** — Sort by name, date, or read status
- **Batch operations** — Download all, batch-remove, etc.
- **Notification system** — Chapter update notifications
- **Multi-language support** — UI localization
- **Theme customization** — Custom color schemes

### Not Planned

- Feature-parity Android widget/Biometric/Shizuku equivalents
- General-purpose Android API compatibility layer
- Android-specific security models (biometric, Shizuku)

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for development guidelines.

## Research

See [RESEARCH.md](RESEARCH.md) for the investigation into running Mihon extensions on desktop.
