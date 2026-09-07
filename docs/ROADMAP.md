# Roadmap

This scaffold proves the extension-loading foundation works. It is not close to a usable
manga reader yet. Rough phases, roughly in order:

## Phase 1 — harden the loader

- [x] Test extensions across more multisrc families to find remaining missing stub
  classes/interceptors (`docs/RESEARCH.md` §4-5): `Injekt.get<Application>()`,
  `UncaughtExceptionInterceptor`, `UserAgentInterceptor`, `CloudflareInterceptor`
  (placeholder), `okhttp3.brotli`/`okhttp3.zstd`, `android.os.SystemClock`.
- [x] `Application` now has a real `filesDir`/`cacheDir` (under `~/.mihon-desktop`).
- [x] `ConfigurableSource.getSourcePreferences()` persists to real files
  (`~/.mihon-desktop/prefs/<name>.properties`), not just in-memory — survives a restart.
- [x] Extension *catalog*: `mihon.desktop.loader.catalog` fetches keiyoushi's real
  `repo.json` → `index_v2` → gzip-compressed protobuf catalog (mirroring upstream's
  `ExtensionStoreService`, see `docs/RESEARCH.md` §7), lists all ~1400 live extensions,
  and downloads+caches an extension's `.jar` by package name
  (`./gradlew :app:run --args="eu.kanade.tachiyomi.extension.en.bunmanga"`).
- [x] `CatalogClient.fetchCatalog` and `ExtensionDownloader.download` are now `suspend
  fun`s wrapped in `withContext(Dispatchers.IO)`, safe to call from a UI coroutine scope
  without blocking it.
- [x] `ExtensionDownloader` now verifies every download (and every cache hit) against
  keiyoushi's `release-assets.json` sha256 manifest, deleting and refusing to load
  anything that doesn't match. Verification is skipped (not blocking) if the manifest
  itself can't be fetched.

## Phase 2 — real UI
- [x] Compose Desktop (`org.jetbrains.compose` 1.12.0 + Kotlin's own compose compiler
  plugin) is wired into `app`, replacing the CLI entirely. `mihon.desktop.app.ui` has four
  screens with simple `remember { mutableStateOf<Screen>(...) }` navigation (no
  Voyager/Navigation library, no back stack beyond one level per screen type):
  `CatalogScreen` (search/install from the live keiyoushi catalog),
  `SourceBrowseScreen` (popular/search grid for one loaded source),
  `MangaDetailScreen` (fetches details+chapters via `getMangaUpdate`), and `ReaderScreen`
  (fetches pages via `getPageList`, decodes bytes with `org.jetbrains.skia.Image`).
  Verified to launch and stay stable in a WSLg (`DISPLAY=:0`) session — Skiko falls back
  from GL to a software rasterizer there rather than crashing.
- [ ] `ReaderScreen` is intentionally minimal today: one page at a time, next/prev
  buttons, no zoom/pan/webtoon mode. Upstream's reader is built on
  `PhotoView`/`SubsamplingScaleImageView`, both Android-View-based with no desktop port —
  replacing this needs a real Compose Multiplatform pan/zoom image viewer (evaluate
  existing zoomable-image libraries before writing one from scratch).
- [ ] Library/downloads persistence — a real `domain`/`data` layer (SQLDelight is already
  multiplatform-capable; this is mostly a JDBC-driver-vs-Android-driver swap) instead of
  the in-memory-only state this scaffold has today. Currently nothing survives closing the
  window except the installed-extension jar cache and preferences files.

## Phase 3 — platform integrations
- [ ] Background library updates (replace `WorkManager` with a plain JVM scheduler).
- [ ] Cloudflare bypass for real (see `docs/RESEARCH.md` §6) — needs an embedded browser
  engine (JCEF/KCEF are the leading candidates) wired into `platform-compat`'s
  `CloudflareInterceptor`.
- [ ] QuickJS for extensions that execute JS (not yet needed by any tested extension, but
  will be for some) — needs a JVM-targeted QuickJS binding.
- [ ] Backups — plain filesystem export/import instead of Android SAF.
- [ ] Packaging: `jpackage` (bundled with the JDK) or Conveyor for Windows/macOS/Linux
  installers.

## Explicitly not planned
- Feature-parity Android widget/Biometric/Shizuku equivalents — desktop doesn't need
  home-screen widgets, and biometric/Shizuku are Android-specific security models with no
  meaningful desktop analog.
- A general-purpose Android API compatibility layer. The whole point of this project (see
  `docs/RESEARCH.md`) is that the actual stub surface needed is small and specific; if a
  future need turns out to require broad Android API coverage, adopting or extending
  Suwayomi's `AndroidCompat` is the better move than growing this module indefinitely.
