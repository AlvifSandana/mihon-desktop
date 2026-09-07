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
page-by-page reader. Everything you add to the library and every page you read is saved
to a local sqlite database (`~/.mihon-desktop/library.db`) and survives restarting the
app.

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
platform-compat/    Android/androidx stand-ins + networking
source-api/          Mihon's Source/HttpSource/CatalogueSource contracts, unmodified
extension-loader/    discovers + loads an extension .jar on a plain JVM
app/                 Compose Desktop UI: catalog, source browse, manga detail, reader
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

The sample jars under `samples/extensions/` are still useful for exercising
`ExtensionLoader`/`platform-compat` directly without going through the catalog UI or a
network call — see `extension-loader`'s tests/usages, or load one with:

```kotlin
DesktopExtensionRuntime.bootstrap()
val sources = ExtensionLoader.load(File("samples/extensions/tachiyomi-all.comicgrowl-v1.4.13.jar")).sources
```

To try an extension not yet exercised this way: if it throws `NoClassDefFoundError` or
`InjektionException`, that's a missing stub — see the troubleshooting loop described at
the end of [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Status

The extension-loading foundation is solid and the GUI covers the core loop end-to-end:
browse the catalog, install an extension, add manga to a persistent library, view a
manga's chapters, read a chapter and resume it later. Still missing: chapter downloads
(offline reading), reader zoom/pan, background library updates, Cloudflare bypass, and
packaged installers. See [`docs/ROADMAP.md`](docs/ROADMAP.md) for the full picture and
what's explicitly out of scope (this is not trying to become a general Android
compatibility layer).

## Known gaps

- **Cloudflare-protected sites will fail.** Upstream solves the JS challenge with an
  Android WebView; the stand-in here is a pass-through. See `docs/RESEARCH.md` §6.
- Extensions that execute JS via QuickJS aren't supported yet (none of the sample
  extensions need it, but some in the wild do).

## Relationship to Suwayomi

[Suwayomi](https://github.com/Suwayomi/Suwayomi-Server) already solved this problem years
ago with a much broader `AndroidCompat` module, and has production desktop/web clients
today. If you just want to read manga on desktop using the Tachiyomi/Mihon extension
catalog, use Suwayomi. This project exists to explore keeping Mihon's own codebase
lineage and UI/UX on desktop instead, now that the actual Android-stub surface needed
turned out to be much smaller than expected.
