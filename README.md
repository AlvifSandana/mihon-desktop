# Mihon Desktop

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21+-green.svg)](https://openjdk.org/projects/jdk/21/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Compose%20Desktop-1.12.0-blue.svg)](https://www.jetbrains.com/compose/)

A desktop manga reader that runs [Mihon](https://github.com/mihonapp/mihon)'s Android extensions on a plain JVM — no Android SDK, emulator, or dex-to-JVM bridge required.

## What This Does

Mihon Desktop loads real extension `.jar` files from [keiyoushi/extensions](https://github.com/keiyoushi/extensions) and reads manga through them:

- Browse, install, and update extensions from the live keiyoushi catalog (~1400 extensions)
- Search manga per-source or globally across all installed sources, with full source filters
- View details and chapters (sort, filter, batch download)
- Read chapters with zoom/pan, webtoon mode, dual-page, transitions, and fullscreen
- Persistent library with reading progress, categories, and reading stats
- Download chapters for offline reading with a concurrent, pausable download queue
- Migrate your library between sources (auto/manual title matching)
- Sync reading progress to AniList and MyAnimeList
- Export/import backups (JSON, or Mihon-compatible `.tachibk`) with optional auto-backup
- OS notifications, 8 theme presets, English/Indonesian UI

All powered by a minimal Android stub layer — just enough classes with the right names to satisfy extension bytecode.

## Quick Start

### Prerequisites

- **JDK 21+** (no Android SDK required)

### Run

```bash
git clone https://github.com/your-username/mihon-desktop.git
cd mihon-desktop
./gradlew :app:run
```

### Build

```bash
./gradlew build              # compile all modules
./gradlew :app:fatJar        # create single executable JAR (~94MB)
./gradlew :app:jpackage      # create native installer (dmg/deb/exe)
```

### Run from JAR

After building the fat JAR:

```bash
java -jar app/build/libs/mihon-desktop-all.jar
```

Single file, no installation required. Just needs JDK 21+ installed.

## Features

| Feature | Status |
|---------|--------|
| Extension catalog | ✅ Browse/search ~1400 live keiyoushi extensions |
| Extension installation | ✅ One-click download and install |
| Extension updates | ✅ Version check, update badge, per-extension and "update all" |
| Library management | ✅ Persistent manga library with progress |
| Categories | ✅ Create/rename/reorder, per-manga assignment, library filter |
| Global search | ✅ Search across all installed sources at once |
| Chapter reader | ✅ Zoom/pan, keyboard nav, webtoon, dual-page, transitions, fullscreen |
| Offline reading | ✅ Download queue with concurrency, pause/cancel/retry |
| Batch chapter actions | ✅ Select-mode download, mark all read/unread |
| Migration | ✅ Move the library between sources (4-step wizard) |
| Trackers | ✅ AniList + MyAnimeList sync (token-paste OAuth) |
| Stats | ✅ Library totals, per-source/category breakdowns, reading activity |
| Background updates | ✅ Automatic library update checks (configurable/disableable) |
| Backups | ✅ JSON export/import + Mihon-compatible `.tachibk` + auto-backup |
| Notifications | ✅ In-app center + OS notifications (tray) |
| Themes | ✅ 8 light/dark presets |
| Localization | ✅ English and Indonesian, live switch |
| DNS-over-HTTPS | ✅ Optional (Google/Cloudflare) with system-DNS fallback |
| Cloudflare bypass | ✅ Challenge detection + UA retry; JCEF solver when added |
| QuickJS support | ✅ Real QuickJS engine bundled by default (5 platforms) |

## Architecture

```
platform-compat  →  source-api  →  extension-loader  →  app
```

| Module | Purpose |
|--------|---------|
| `platform-compat` | Android stand-ins (Context, SharedPreferences) + networking (DoH, Cloudflare) + QuickJS shim |
| `source-api` | Mihon's Source/HttpSource/CatalogueSource contracts |
| `extension-loader` | Extension discovery/loading/updates, catalog, download queue, library DB, migration, trackers, backups |
| `app` | Compose Desktop UI (5-tab), i18n, themes |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed module documentation.

## Optional Dependencies

The QuickJS JavaScript engine (needed by JS-executing extensions — obfuscated
sources, Cloudflare-bypass scripts) is **bundled by default** as
`io.github.dokar3:quickjs-kt-jvm`, exposed to extensions through an
`app.cash.quickjs` compatibility shim in `platform-compat` (the exact package
extension jars are compiled against). Self-contained natives ship for Linux
x64/aarch64, macOS x64/aarch64, and Windows x64 — no system libraries required.

Tracker login uses token-paste OAuth: AniList's implicit grant has you paste an
access token, MyAnimeList's PKCE flow has you paste the redirect URL (or just
the `code` value). No embedded browser or localhost callback server needed.

Add to runtime classpath for full feature set:

```kotlin
// Cloudflare bypass (JCEF) — natives are ~100MB per platform
implementation("me.friwi:jcefmaven:146.0.10")
```

Without JCEF the app still works — Cloudflare challenges are detected
(`cf-mitigated` header), retried once with a browser User-Agent, and persistent
challenges fail with a clear `CloudflareChallengeException` instead of a parse
error.

## Data Storage

All data is stored in `~/.mihon-desktop/`:

| Path | Contents |
|------|----------|
| `library.db` | Library, progress, categories, history, tracker bindings |
| `extension-cache/` | Downloaded extension JARs |
| `downloads/` | Downloaded chapter pages |
| `backups/` | Manual backup files |
| `backups/auto/` | Automatic backups (JSON / `.tachibk`, newest 5 per format) |
| `http-cache/` | Network response cache |
| `prefs/` | Extension preferences |
| `app.properties` | App settings (theme, language, DoH, download concurrency, …) |
| `tracker-auth.properties` | Tracker OAuth tokens (owner-only perms, excluded from backups) |
| `jcef-bundle/` | JCEF natives (if enabled) |

## How It Works

See [docs/RESEARCH.md](docs/RESEARCH.md) for the full investigation, but the short version:

1. keiyoushi publishes each extension as both an Android `.apk` and a JVM `.jar`
2. The `.jar` contains standard JVM bytecode (`CAFEBABE` magic, class file version 55)
3. A plain `URLClassLoader` loads the classes — no dex-to-JVM bridge needed
4. Extension bytecode references Android types by name (`android.content.Context`, etc.)
5. We supply JVM classes with those exact names and just enough behavior to work

The stub layer is surprisingly small: a few Android classes + three OkHttp interceptors.

## Relationship to Suwayomi

[Suwayomi](https://github.com/Suwayomi/Suwayomi-Server) already solved this problem with a much broader `AndroidCompat` module and has production desktop/web clients. If you just want to read manga on desktop, use Suwayomi.

This project exists to explore keeping Mihon's own codebase lineage and UI/UX on desktop, now that the actual Android-stub surface needed turned out to be much smaller than expected.

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Development Guidelines

- **JDK 21+** required
- Follow existing code style
- Keep `platform-compat` FQCNs unchanged (they're load-bearing)
- Keep `source-api` close to upstream Mihon
- Test with real extensions from keiyoushi

## Documentation

- [Architecture](docs/ARCHITECTURE.md) — module structure and design decisions
- [Research](docs/RESEARCH.md) — investigation into running Mihon extensions on desktop
- [Roadmap](docs/ROADMAP.md) — project phases and future plans

## License

This project is licensed under the Apache License, Version 2.0 — see [LICENSE](LICENSE) for details.

See [NOTICE.md](NOTICE.md) for code provenance and attribution.

## Acknowledgments

- [Mihon](https://github.com/mihonapp/mihon) — the original Android manga reader
- [keiyoushi/extensions](https://github.com/keiyoushi/extensions) — the extension repository
- [Suwayomi](https://github.com/Suwayomi/Suwayomi-Server) — prior art for JVM-based extension loading
