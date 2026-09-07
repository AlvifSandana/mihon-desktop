# Architecture

## Module graph

```
platform-compat   <- Android/androidx stand-ins + networking (no Android SDK involved)
      ^
      |
 source-api       <- Mihon's own Source/HttpSource/CatalogueSource contracts, unmodified
      ^
      |
extension-loader  <- discovers + loads a keiyoushi/Mihon extension .jar on a plain JVM
      ^
      |
    app           <- Compose Desktop GUI: catalog, source browse, manga detail, reader
```

Each module depends only on the one below it; nothing here depends on Android Gradle
Plugin, an Android SDK, or an emulator. `./gradlew build` runs on any JVM 21+ toolchain.

### `platform-compat`

Two unrelated things live here together because upstream Mihon also keeps them together
(in `core/common`) and extension bytecode references types from both:

1. **Android stand-ins** (`android.content.Context`/`SharedPreferences`,
   `android.app.Application`, `android.net.Uri`, `android.graphics.*`,
   `android.os.SystemClock`) — enough of each type's surface for `source-api`'s own code
   (compiled here, unmodified from upstream) and extension bytecode to resolve against,
   nothing more. See `docs/RESEARCH.md` §4 for exactly which symbols were needed and why.
2. **Networking** (`eu.kanade.tachiyomi.network.*`) — a trimmed-down `NetworkHelper` (just
   an `OkHttpClient` with the three interceptors extensions defensively check for),
   `Requests.kt` (`GET`/`POST`/...), `OkHttpExtensions.kt` (the Rx/coroutine bridging
   helpers `HttpSource` calls), and an in-memory `AndroidCookieJar`.

Everything in this module is either copied verbatim from Mihon's actual source (where a
file is genuinely platform-agnostic Kotlin, e.g. `Requests.kt`, `RxCoroutineBridge.kt`) or
is a from-scratch, explicitly-documented stand-in (the `android.*` package, `NetworkHelper`,
`CloudflareInterceptor`). The kdoc on each file says which.

### `source-api`

This is upstream Mihon's `source-api` module's Kotlin files, unmodified except for
stripping two things that don't affect behavior: Kotlin *context receivers* in
`OkHttpExtensions.parseAs` (replaced with an explicit `Json` parameter — the function
isn't on the hot path any sample extension has exercised yet) and the
`@Stable`/`androidx.compose.runtime` annotation on `FilterList` (a Compose-only compiler
hint with no runtime effect).

Keeping this module byte-for-byte close to upstream is deliberate: it's the contract
every extension jar was actually compiled against, and any accidental behavior change
here is exactly the kind of bug that only shows up against some extensions and not
others.

### `extension-loader`

The desktop-specific part:

- `ExtensionMetadataReader` — parses an extension jar's `AndroidManifest.xml` as plain
  text XML (see `docs/RESEARCH.md` §3 for why that works) to find the source class name(s),
  factory class, display name, and NSFW flag.
- `ExtensionLoader` — opens a `URLClassLoader` over the jar and instantiates the source
  class(es), handling both a plain `Source` and a `SourceFactory` (multi-source jars).
- `DesktopExtensionRuntime` — registers the Injekt singletons (`Context`, `Application`,
  `NetworkHelper`, `Json`) that loaded source classes expect to find via `Injekt.get<T>()`.
  Call `bootstrap()` once before the first `ExtensionLoader.load(...)`.
- `catalog/` — fetching the *list* of available extensions and downloading one by package
  name, instead of requiring a local jar path:
  - `CatalogClient` — resolves keiyoushi's `repo.json` → `index_v2` → gzip+protobuf
    catalog into a `List<CatalogExtension>` (see `docs/RESEARCH.md` §7 for why that's a
    three-hop, two-codec chain rather than one JSON fetch).
  - `GitHubReleaseAssetResolver` — fallback for when an extension's `.jar` isn't in the
    same GitHub Release as its `.apk` (a real, observed quirk — see its kdoc).
  - `ExtensionDownloader` — downloads+caches a `CatalogExtension`'s jar to a local
    directory, trying the fast-path URL before falling back to the resolver above, then
    verifying the result against keiyoushi's `release-assets.json` sha256 manifest
    (skipped, not blocking, if that manifest can't be fetched). A cached file that fails
    verification is deleted and re-downloaded rather than silently loaded.

This mirrors Mihon's own `eu.kanade.tachiyomi.extension.util.ExtensionLoader` /
`ExtensionStoreService`, with `PackageManager`/`DexClassLoader` swapped for
`DocumentBuilder`/`URLClassLoader`, and Android's own network stack reused as-is
(OkHttp is already plain JVM).

### `app`

A Compose Desktop GUI (`org.jetbrains.compose` 1.12.0 on Kotlin 2.2.20's own Compose
compiler plugin). `Main.kt` calls `DesktopExtensionRuntime.bootstrap()` once, then hosts a
single `Window` whose content switches on a `sealed interface Screen`
(`mihon.desktop.app.ui.Screen`) held in one `remember { mutableStateOf<Screen>(...) }` —
no navigation library, no back stack beyond "one screen of each kind is ever live":

- `CatalogScreen` — searches/installs from the live keiyoushi catalog (`CatalogClient` +
  `ExtensionDownloader`), then either navigates straight to `SourceBrowse` (single-source
  jar) or shows a picker dialog (multi-source jar).
- `SourceBrowseScreen` — a `CatalogueSource`'s popular-manga grid, with search and
  "load more" pagination.
- `MangaDetailScreen` — calls `getMangaUpdate(fetchDetails = true, fetchChapters = true)`
  and lists chapters.
- `ReaderScreen` — calls `getPageList`, resolves each `Page`'s `imageUrl` via
  `HttpSource.getImageUrl` when null, fetches bytes via `HttpSource.getImage`, and decodes
  them with `org.jetbrains.skia.Image.makeFromEncoded(...).toComposeImageBitmap()`
  (`AsyncImage.kt`). One page visible at a time with next/prev buttons that cross chapter
  boundaries — no zoom/pan yet, see `docs/ROADMAP.md`.

Run it with:

```
./gradlew :app:run
```

Every network call a screen makes goes through `CatalogClient`/`ExtensionDownloader`'s
`suspend fun`s (`Dispatchers.IO`-wrapped) or a loaded source's own suspend API, launched
from `rememberCoroutineScope()` — nothing blocks the Compose UI thread.

Note for this environment: under WSLg (`DISPLAY=:0` via Wayland, no real GPU passthrough),
Skiko logs `Cannot create Linux GL context` once at startup and falls back to a software
rasterizer; the window still renders and stays stable. This is expected here, not a bug in
this project.

## Design decisions worth knowing before extending this

- **Extension jars, not `.apk`s.** `ExtensionLoader.load` only ever downloads/opens the
  `.jar` asset from a keiyoushi release, never the `.apk` — the `.apk`'s `classes.dex`
  cannot be loaded by a JVM `URLClassLoader` at all.
- **`platform-compat` types must keep their exact upstream fully-qualified names.**
  Extension bytecode was compiled against `android.content.Context`,
  `eu.kanade.tachiyomi.network.NetworkHelper`, etc. by name — renaming or repackaging any
  of these breaks every extension that references them, silently, at class-load time
  rather than compile time.
- **Prefer copying upstream Mihon source over writing new logic**, whenever the file in
  question doesn't actually import anything Android-specific. This keeps `source-api`
  trustworthy as *the* contract, and makes future upstream changes easy to diff against.
- **A missing stub surfaces as `NoClassDefFoundError`/`ExceptionInInitializerError` /
  `InjektionException`, not a compile error.** When testing a new extension for the first
  time, expect to iterate: run it, read the one missing symbol out of the stack trace, add
  it, re-run. `docs/RESEARCH.md` §4-5 walks through several real examples of this loop.
