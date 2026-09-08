# mihon-desktop

A desktop manga reader exploring whether [Mihon](https://github.com/mihonapp/mihon)'s
manga source extensions — the ones published at
[keiyoushi/extensions](https://github.com/keiyoushi/extensions) for Android — can run on
a plain JVM desktop app, without an Android runtime.

**They can.** A Compose Desktop GUI browses the live keiyoushi catalog, installs an
extension's real `.jar` (no Android SDK, emulator, or dex-to-JVM bridge involved), and
reads manga through it: search a source, add manga to a persistent library, view a
manga's chapter list, read a chapter, resume where you left off.

```
./gradlew :app:run
```

opens on your **Library** — empty on first run, with a button to browse extensions.
From there: an extension list (search/install from keiyoushi's ~1400 live extensions) →
a source's popular/search manga grid → a manga's details and chapters (with an
add-to-library toggle and a "Continue reading" card once you have progress) → a
page-by-page reader with zoom/pan and webtoon mode. Everything you add to the library
and every page you read is saved to a local sqlite database (`~/.mihon-desktop/library.db`)
and survives restarting the app.

## Why this is possible — the short version

- keiyoushi publishes each extension as **two** artifacts: the Android `.apk` and a
  `.jar`. The `.jar` contains genuine JVM `.class` bytecode (not Android DEX), because
  it's the same artifact [Suwayomi](https://github.com/Suwayomi/Suwayomi-Server) (a
  JVM-based Tachiyomi server) already depends on.
- The bytecode still references a handful of Android types by name
  (`android.content.Context`, `eu.kanade.tachiyomi.network.NetworkHelper`, a few OkHttp
  interceptors). Supplying small, JVM-native classes with those exact names is enough —
  no Android SDK, emulator, or dex-to-JVM bridge required.

Full writeup, including the actual investigation (`file`/`unzip`/`javap` on both
artifacts, what broke and why on the first few extensions tried) is in
[`docs/RESEARCH.md`](docs/RESEARCH.md).

## Project layout

```
platform-compat/    Android/androidx stand-ins + networking + Cloudflare bypass
source-api/          Mihon's Source/HttpSource/CatalogueSource contracts, unmodified
extension-loader/    discovers + loads an extension .jar, downloads, backups, QuickJS
app/                 Compose Desktop UI: catalog, source browse, manga detail, reader, settings
samples/extensions/  a few real extension jars for trying the loader against
docs/                RESEARCH.md, ARCHITECTURE.md, ROADMAP.md
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for how the modules fit together and
the design decisions behind them.

## Building and running

Requires JDK 21+. No Android SDK needed anywhere in this project.

```
./gradlew build
./gradlew :app:run
```

`:app:run` opens the GUI. It talks straight to keiyoushi's live catalog (~1400
extensions) — search, click one to download+install its real `.jar` to
`~/.mihon-desktop/extension-cache/` (sha256-verified against `release-assets.json`), and
it drops you into that source's popular-manga grid. See `docs/RESEARCH.md` §7 for how the
catalog is actually structured (it's gzip+protobuf, not the JSON file its filename
suggests).

### Packaging native installers

```
./gradlew :app:jpackage
```

Creates a native installer for your platform (dmg on macOS, deb on Linux, exe on Windows)
using `jpackage` bundled with JDK 14+.

## Features

- **Extension catalog** — browse/search ~1400 live keiyoushi extensions, install with one click
- **Library** — persistent manga library with reading progress, manual refresh for updates
- **Reader** — zoom/pan (scroll wheel, trackpad pinch), keyboard navigation, webtoon/vertical-scroll mode
- **Offline reading** — download chapters for offline access, auto-loads from disk when available
- **Backups** — export/import library as JSON, timestamped backup files
- **Settings** — configurable background update interval, theme toggle, reading direction
- **Cloudflare bypass** — optional JCEF integration for Cloudflare-protected sites
- **QuickJS** — optional real QuickJS engine for JS-executing extensions

### Optional dependencies

Add to runtime classpath for full feature set:

```kotlin
// Real QuickJS engine for JS-executing extensions
implementation("app.cash.quickjs:quickjs-jvm:0.9.2")

// Cloudflare bypass (JCEF) — natives are ~100MB per platform
implementation("me.friwi:jcefmaven:146.0.10")
```

Without these, the app still works — extensions that check interceptor presence don't
crash, and JS execution falls back to `javax.script` (Nashorn/GraalJS if available).

## Status

All ROADMAP phases complete. See [`docs/ROADMAP.md`](docs/ROADMAP.md) for details.

## Known gaps

- **JCEF natives are large.** Each platform's native bundle is ~100MB. They're downloaded
  on first run by `jcefmaven` and cached in `~/.mihon-desktop/jcef-bundle/`.
- **No image caching.** Pages are fetched fresh each time (except downloads). A proper
 LRU disk cache would improve repeated reads.
- **No chapter sorting/filtering** in the reader. Chapters are listed in source order.
- **No batch operations.** Can't download all chapters at once or batch-remove from library.

## Relationship to Suwayomi

[Suwayomi](https://github.com/Suwayomi/Suwayomi-Server) already solved this problem years
ago with a much broader `AndroidCompat` module, and has production desktop/web clients
today. If you just want to read manga on desktop using the Tachiyomi/Mihon extension
catalog, use Suwayomi. This project exists to explore keeping Mihon's own codebase
lineage and UI/UX on desktop instead, now that the actual Android-stub surface needed
turned out to be much smaller than expected.
