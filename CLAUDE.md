# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

Mihon Desktop is a JVM-based manga reader that loads real Mihon (Android) extension `.jar` files from [keiyoushi/extensions](https://github.com/keiyoushi/extensions) and runs them on a plain JVM — no Android SDK, emulator, or dex-to-JVM bridge. It works because keiyoushi publishes each extension as both an `.apk` and a `.jar`; the `.jar` is standard JVM bytecode (`CAFEBABE`, class file version 55) that a plain `URLClassLoader` can load directly. Extension bytecode references Android types (`android.content.Context`, etc.) by exact name, so this repo supplies a minimal set of JVM classes with those exact names — see `docs/RESEARCH.md` for the full investigation.

## Build & Run

```bash
./gradlew build                  # compile all modules + run all tests
./gradlew :app:run                # launch GUI (JDK 21+)
./gradlew :app:fatJar             # single executable JAR (~94MB) -> app/build/libs/mihon-desktop-all.jar
./gradlew :app:jpackage           # native installer (dmg/deb/exe), requires JDK 14+ for jpackage binary
./gradlew test                    # all tests (JUnit 4): platform-compat 35, extension-loader 323, app 19 = 377
```

Run fat JAR directly: `java -jar app/build/libs/mihon-desktop-all.jar`

No Android SDK, emulator, or Docker required — pure JVM, JDK 21+ (`jvmToolchain(21)` in every module).
CI: `.github/workflows/ci.yml` runs `./gradlew build` (Temurin 21, ubuntu-latest) on every push/PR.

## Module dependency chain

```
platform-compat  →  source-api  →  extension-loader  →  app
```

Each module depends only on the one below it. Never add a reverse dependency. `settings.gradle.kts` declares all four; `build.gradle.kts` at root only wires shared plugins.

- **`platform-compat/`** — Android/androidx stand-ins (`android.content.Context`, `android.content.SharedPreferences`, `android.app.Application`, `android.net.Uri`, `android.graphics.*`, `android.os.SystemClock`) plus OkHttp-based networking (`NetworkHelper`, `Requests.kt`, `AndroidCookieJar`, `DohDns`, `CloudflareInterceptor`) and the `app.cash.quickjs.QuickJs` compatibility shim (backed by `io.github.dokar3:quickjs-kt-jvm`).
- **`source-api/`** — Mihon's own `Source`/`HttpSource`/`CatalogueSource` contracts, copied from upstream and only stripped of things with no runtime effect (Compose `@Stable` annotations, Kotlin context receivers replaced with explicit params).
- **`extension-loader/`** — extension discovery/loading/updates (`ExtensionUpdateManager`), keiyoushi catalog client, downloads + `DownloadQueue`, library DB (SQLDelight: library, categories, stats, update history, tracker bindings), `MigrationEngine`, trackers (AniList/MAL), backups (JSON + `.tachibk` + auto-backup), image cache, JS engine, logging, prefs.
- **`app/`** — Compose Desktop UI, single window, 5-tab navigation via `sealed interface Screen` (Library/Updates/History/Browse/More), i18n EN/ID (`Strings`), 8 theme presets. Entry point `mihon.desktop.app.MainKt`.

### `extension-loader` key components

- `ExtensionMetadataReader` — parses `AndroidManifest.xml` as plain-text XML (source class names, display name, NSFW flag)
- `ExtensionLoader` — opens a `URLClassLoader` over the extension JAR and instantiates source classes via reflection; cache keyed on (path, lastModified, length); sha256 sidecar tamper-evidence at jar load time
- `DesktopExtensionRuntime` — registers Injekt singletons (`Context`, `Application`, `NetworkHelper`, `Json`); `bootstrap()` must run once before any `ExtensionLoader.load()`
- `catalog/` — `CatalogClient` resolves `repo.json` → `index_v2` → gzip+protobuf catalog (https-only via `requireHttps`); `ExtensionDownloader` downloads/caches JARs and verifies sha256; `GitHubReleaseAssetResolver` is the jar/apk fallback across releases; `ExtensionUpdateManager` detects/applies updates (validate new jar before swap, re-point DB rows, no restart needed)
- `library/` (SQLDelight, schema in `src/main/sqldelight/`, generated code in `build/generated/sqldelight/`) — `libraryManga`, `readingProgress`, `downloadedChapters`, `readChapters`, `updateHistory`, `category`, `mangaCategory`, `tracker`, `readerPreferences`, stats queries; `NotificationManager` + `AwtSystemNotifier` (OS tray notifications)
- `migration/` — `MigrationEngine`: source-to-source library migration, per-manga atomic transaction, works with a dead old source
- `tracker/` — `Tracker` interface, `AniListTracker` (implicit grant) + `MyAnimeListTracker` (PKCE), token-paste login, `TrackerAuthStore` (`~/.mihon-desktop/tracker-auth.properties`, excluded from backups), `TrackerManager` sync hooks (reader chapter-finished, monotonic, no-op in incognito)
- `backup/` — `BackupManager` JSON export/import; `AutoBackupScheduler`; `backup/tachibk/` — hand-written protobuf codec (Mihon v0.20.4-compatible, no codegen), gzip sniffing + capped decompression, import merge / export
- `download/` — `DownloadManager` offline chapter storage + `DownloadQueue` (N parallel, pause/cancel/retry with backoff, one drain notification per batch)
- `cache/` — `ImageCache`, LRU disk cache keyed on URL string (a non-`String` key skips caching)
- `js/` — `DesktopJavaScriptEngine` (real QuickJS via the `app.cash.quickjs` shim in platform-compat, `javax.script` fallback)

### `app` screens (`sealed interface Screen`)

Tabs: Library (grid, search/sort, category filter), Updates, History, Browse (Sources/Extensions/Migration), More (incognito, downloaded-only, categories, stats, downloads, settings, about). Pushed: Settings, DownloadManager, ExtensionManagement, MultiSourceSearch (global search), Notifications, Categories, Stats, MigrateManga, SourceBrowse, SourceSettings, MangaDetail, Reader.

## Critical constraints

- **`platform-compat` FQCNs are load-bearing.** Extension bytecode references classes like `android.content.Context` and `eu.kanade.tachiyomi.network.NetworkHelper` by exact name. Renaming or repackaging any of them breaks every extension silently at class-load time, not compile time.
- **`source-api` must stay close to upstream Mihon.** It's the contract extension jars were compiled against — only strip things with no behavioral effect (Compose annotations, context receivers).
- **Extension jars only, never `.apk`.** `ExtensionLoader.load` opens `.jar` assets via `URLClassLoader`; an `.apk`'s `classes.dex` cannot be loaded by the JVM.
- **`DesktopExtensionRuntime.bootstrap()` must run once before any `ExtensionLoader.load()`** — it registers the Injekt singletons extensions expect to find.
- Network calls in Compose screens must be `suspend` + `Dispatchers.IO`; never call OkHttp synchronously on the UI thread.
- No sandboxing — loaded extensions run in the same JVM as the app.

## Common pitfalls

- Missing Android stubs show up as `NoClassDefFoundError` / `InjektionException` **at runtime**, not at compile time. When testing a new extension, read the stack trace for the missing symbol and add a minimal stub to `platform-compat`.
- QuickJS (`io.github.dokar3:quickjs-kt-jvm` behind the `app.cash.quickjs` shim in platform-compat) is bundled by default — JS-executing extensions work out of the box. JCEF remains optional (`me.friwi:jcefmaven`) — without it, Cloudflare-gated extensions won't get a JCEF solver, but nothing crashes.
- SQLite `INSERT OR REPLACE` assigns a fresh row id, orphaning referencing rows (mangaCategory, tracker bindings). Upsert by select-then-update — see `LibraryRepository.upsertLibraryManga`.
- `DownloadQueue` workers launch with `CoroutineStart.ATOMIC` deliberately (a default-start coroutine cancelled before first dispatch skips its body, leaving the job stuck RUNNING with no cleanup). Don't "simplify" it away.
- kotlinx.serialization.protobuf fields without defaults (e.g. Mihon's `BackupHistory.lastRead`) must never be encoded as proto3 zero-values — the field gets omitted and the consumer errors. `TachibkManager` stamps `lastRead` with the export time instead of 0.

## Dependency versions (`gradle/libs.versions.toml`)

Kotlin 2.2.20, Compose Desktop 1.12.0, coroutines/serialization 1.11.0, OkHttp 5.5.0, SQLDelight 2.3.2, Injekt (mihonapp fork via JitPack), quickjs-kt 1.0.5 (pinned — newer needs Kotlin ≥2.4 metadata; see `libs.versions.toml`), protobuf-java 4.36.1 (`.tachibk` codec only). Repositories: Maven Central, Google, JitPack.

## Documentation map

- `README.md` — project overview, quick start, features, data storage layout (`~/.mihon-desktop/`)
- `CONTRIBUTING.md` — per-module contribution rules, testing guidance, commit message style, PR checklist
- `docs/ARCHITECTURE.md` — full module/data-flow/design-decision writeup
- `docs/RESEARCH.md` — the investigation into why Mihon extensions can run on a plain JVM
- `docs/ROADMAP.md` — project phases and future plans
- `docs/NAVIGATION-IMPLEMENTATION-PLAN.md` — the 5-tab navigation rework plan (implemented)
- `docs/README.md` — documentation hub
