# Research notes: can Mihon's extension ecosystem run outside Android?

This document records the investigation that this project's architecture is based on. It
answers one question in detail: **can a JVM desktop app reuse the same manga source
extensions Mihon/Tachiyomi and its extension repo (keiyoushi/extensions) already
publish for Android — and if so, what exactly has to stand in for Android to make that
work?**

Short answer: yes, and the stand-in layer is much smaller than "reimplement Android."
Sections below walk through why.

## 1. Mihon itself is not multiplatform

Mihon ([mihonapp/mihon](https://github.com/mihonapp/mihon)) is a single-flavor Android
app (Jetpack Compose, Voyager navigation, WorkManager, Glance widgets, Biometric,
Shizuku). Its module graph splits cleanly, though, and the split matters:

| Module | What's in it | Portable to plain JVM? |
|---|---|---|
| `domain`, `data`, `core-metadata` | business logic, SQLDelight, kotlinx.serialization/datetime | yes, close to as-is |
| `core/common` | networking (OkHttp/Okio — plain JVM libraries), a small JS engine binding | mostly, one Android-only dependency (QuickJS-android) |
| `source-api` | the `Source`/`HttpSource`/`CatalogueSource` contracts every extension implements | **yes, verified** — no Android imports in the interfaces themselves, only in a couple of default-method conveniences (`ConfigurableSource`, some model fields) |
| `i18n` | already true Kotlin Multiplatform (moko-resources) | yes — proof the team has already done this once |
| `presentation-core`, `presentation-widget`, `app` | UI, Activities/Services, WorkManager, Biometric, Glance | no — Android-only |
| Reader stack (`PhotoView`, `SubsamplingScaleImageView`, `webgpuviewer`, `flexibleAdapter`) | View-based reader UI | no — needs a full Compose Desktop rewrite (out of scope for this doc) |

The one item that actually blocks everything else: **how extensions are packaged and
loaded.**

## 2. How Mihon loads an extension (`ExtensionLoader.kt`)

Read directly from `eu.kanade.tachiyomi.extension.util.ExtensionLoader` in the Mihon
source:

- Extensions are Android **APKs**, installed like any other app, carrying a
  `<uses-feature android:name="tachiyomi.extension" />` marker.
- Mihon enumerates them via `PackageManager.getInstalledPackages(...)`.
- Metadata — display name, which class implements the source, NSFW flag — comes from
  `<meta-data>` entries in the APK's `AndroidManifest.xml`, read through
  `ApplicationInfo.metaData` (a `PackageManager` API).
- The actual code loads through `DelegateLastClassLoaderCompat(appInfo.sourceDir, ...)`
  — `appInfo.sourceDir` is the path to the APK, and the classes inside are compiled to
  **DEX bytecode** (Dalvik/ART), not JVM `.class` files.
- Trust is established by checking the APK's signing certificate via
  `PackageInfo.signingInfo`.

Every one of those — `PackageManager`, DEX bytecode, APK signing — is Android-specific
and has no equivalent in a plain JVM process. This is the piece that made "just recompile
for desktop" look infeasible at first.

## 3. The `.jar` keiyoushi already publishes

keiyoushi's extension repo ([keiyoushi/extensions](https://github.com/keiyoushi/extensions))
distributes each extension as **two artifacts**, per its `release-assets.json`:

```json
"eu.kanade.tachiyomi.extension.all.ahottie": {
  "apk": { "name": "tachiyomi-all.ahottie-v1.6.4.apk", "sha256": "..." },
  "jar": { "name": "tachiyomi-all.ahottie-v1.6.4.jar", "sha256": "..." }
}
```

Downloading and inspecting both for the same extension (`comicgrowl`) shows they are
genuinely different artifacts, not the same file renamed:

```
$ file comicgrowl.jar
comicgrowl.jar: Android package (APK), with AndroidManifest.xml   # zip container, but:

$ unzip -l comicgrowl.jar
  AndroidManifest.xml
  eu/kanade/tachiyomi/a/a/a/a.class      <- real .class files, not classes.dex
  eu/kanade/tachiyomi/b/a/A.class
  ...
  keiyoushi/source/Generated.class
  res/*.png, resources.arsc
  META-INF/CERT.SF, CERT.RSA, MANIFEST.MF

$ xxd keiyoushi/source/Generated.class | head -1
00000000: cafe babe 0000 0037 ...      <- CAFEBABE magic, major version 55 = Java 11 class file
```

```
$ unzip -l comicgrowl.apk
  classes.dex        <- Dalvik bytecode, the thing ExtensionLoader.kt actually needs
  AndroidManifest.xml (binary AXML in the real .apk)
  res/*.png, resources.arsc
```

Two findings from this that shaped everything downstream:

1. **The `.jar`'s `AndroidManifest.xml` is plain-text XML**, not compiled binary AXML.
   A stock `javax.xml.parsers.DocumentBuilder` reads it directly — see
   [`ExtensionMetadataReader`](../extension-loader/src/main/kotlin/mihon/desktop/loader/ExtensionMetadata.kt).
2. **The `.jar`'s classes are standard JVM bytecode** (`CAFEBABE`, class file version
   55). A plain `java.net.URLClassLoader` loads them with no dex-to-JVM bridge needed —
   this is the single biggest reason a desktop build is tractable without reimplementing
   something like Suwayomi's `AndroidCompat` (a full Android API shim over the Dalvik/ART
   gap) from scratch.

This `.jar` exists because keiyoushi's extensions also serve
[Suwayomi/Tachidesk](https://github.com/Suwayomi/Suwayomi-Server), a JVM-based Tachiyomi
server. We're consuming the same artifact that project already depends on — nothing here
is a hack keiyoushi didn't intend to support.

## 4. What still has to be stubbed: reading the bytecode's own symbol table

Bytecode being JVM-native means the classes will *load*. It doesn't mean they'll *run* --
the classes still reference Android types by fully-qualified name wherever the original
Kotlin source imported them, and the JVM will throw `NoClassDefFoundError` the moment
one of those types is actually touched.

Grepping the compiled `.class` files for `android/` string constants gives the exact list
to satisfy for a given extension:

```
android/app/Application
android/content/Context
android/content/SharedPreferences
android/graphics/Bitmap, BitmapFactory, Canvas, Paint, Rect
android/net/Uri
android/os/SystemClock
android/webkit/CookieManager        (only if the extension bundles its own cookie-jar-alike)
```

None of these need a real Android runtime underneath — they need a JVM class with the
same fully-qualified name and just enough real behavior to satisfy what `source-api`'s
own code (which we compile ourselves, unmodified, against these types) actually calls.
That code is [`android.content.Context`](../platform-compat/src/main/kotlin/android/content/Context.kt),
[`SharedPreferences`](../platform-compat/src/main/kotlin/android/content/SharedPreferences.kt),
[`Application`](../platform-compat/src/main/kotlin/android/app/Application.kt) (in-memory
prefs), a `Uri` wrapping a string, and no-op `android.graphics.*` shapes.

Two more things surfaced only by actually *running* multiple extensions end-to-end
(see §5), not by static analysis:

- Several multisrc template libraries (shared base classes many extensions in the same
  family — e.g. MangaThemesia-derived sites — compile straight into every extension jar
  that uses them) **defensively assert specific OkHttp interceptors are present** on the
  client before making a request: `UncaughtExceptionInterceptor`, `UserAgentInterceptor`,
  `CloudflareInterceptor`. If `NetworkHelper.client` doesn't carry all three, they throw
  `IllegalStateException` rather than silently degrading.
- `okhttp3.brotli.Brotli` and `okhttp3.zstd.Zstd` (real, non-Android OkHttp companion
  artifacts Mihon bundles) need to be on the classpath too, for the same reason.

## 5. Proof: running real extensions end-to-end

Three unrelated extensions, three different multisrc template families, downloaded
unmodified from keiyoushi's GitHub Releases and run against the live sites through the
stub layer described above (see `app`'s CLI entry point, or the earlier raw PoC in
`git log` history of this repo):

| Extension | Result |
|---|---|
| Comic Growl (`tachiyomi-all.comicgrowl`) | 32 manga returned |
| MangaK / mangabuddy (`tachiyomi-en.mangabuddy`) | 24 manga returned, `hasNextPage=true` |
| Temple Scan (`tachiyomi-en.templescan`) | 277 manga returned |

Each failure encountered along the way corresponded to exactly one missing stub class or
interceptor, fixed by adding ~10-30 lines that either faithfully reproduce the upstream
Mihon file (`UncaughtExceptionInterceptor`, `UserAgentInterceptor`, `SystemClock`) or
consciously stand in for something Android-specific with a documented gap
(`CloudflareInterceptor` — see below).

## 6. Known gaps and how they resolved

- **Cloudflare / JS-challenge bypass.** Upstream's `CloudflareInterceptor` drives an
  `android.webkit.WebView` to solve the challenge page. There's no WebView on a JVM
  desktop. The interceptor here now detects challenges properly (`cf-mitigated`
  header + bounded body scan) and retries once with a browser-like User-Agent;
  without JCEF on the classpath a persistent challenge fails with a clear
  `CloudflareChallengeException` instead of a parse error. Actually solving the
  JS challenge still needs an embedded browser engine (JCEF/KCEF are the most
  likely candidates) — `JcefCloudflareSolver` drives it when present.
- **QuickJS / in-source JS execution.** Solved: `platform-compat` ships an
  `app.cash.quickjs.QuickJs` compatibility shim (the FQCN extension bytecode
  links against) backed by `io.github.dokar3:quickjs-kt-jvm`, whose
  self-contained JNI natives cover Linux x64/aarch64, macOS x64/aarch64 and
  Windows x64. The original `app.cash.quickjs:quickjs-jvm` was rejected: its
  Linux native links against system `libc++.so.1`/`libc++abi.so.1`, which
  stock distros don't ship.
- **Extensions that read Android resources or assets beyond the manifest/icon** (rare,
  but the interface allows it) aren't covered by this stub layer yet.

## 7. Getting the catalog itself (not just one known jar)

Everything above assumes you already have a specific extension jar's URL. Getting *the
list* of available extensions turned out to have two more undocumented gotchas.

**The obvious URL is a decoy.** `https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json`
looks like the catalog -- it's literally named that -- but fetching it returns a 2-entry
JSON list named `"Outdated App"` / `"Update to Mihon 0.20.1+"`. This isn't a caching
artifact (confirmed with a cache-busting query param); it's a deliberate stub keiyoushi
serves at that legacy path to nudge any client still speaking the old plain-JSON protocol
to update, rather than silently failing. The real resolution chain, read out of upstream
Mihon's `ExtensionStoreService.fetch`/`getExtensions`:

```
repo.json  (small JSON: { "index_v2": "<url>", "meta": {...} })
    -> index_v2 points at ...
index.pb   (gzip-compressed, protobuf-encoded NetworkExtensionStore message)
    -> .extensionList.extensions: the real ~1400-entry catalog
```

Both files are gzip-compressed on the wire (`1f 8b` magic bytes) despite no
`Content-Encoding` header forcing that on the HTTP client -- upstream handles this with
an explicit peek-and-decompress step (`decompressIfGzipped()`), which
`mihon.desktop.loader.catalog.CatalogClient` replicates.

**The catalog only has `apkUrl`, and deriving the jar URL from it isn't always right.**
The protobuf schema (`NetworkExtensionStore.Extension.Resources`) carries an `apkUrl`
field and no `jarUrl` field. Swapping `.apk` -> `.jar` on it works for *most* extensions
(both files are built and uploaded together as part of the same release), but not all:
testing this against live extensions found at least one (`bunmanga` v1.6.54) where the
`.apk` and `.jar` for the identical version live in **different** GitHub Releases --
apparently a jar rebuild isn't always triggered by the same commit/workflow run as the
apk rebuild, even though `release-assets.json` confirms a jar genuinely exists for that
version. `mihon.desktop.loader.catalog.GitHubReleaseAssetResolver` handles this: try the
derived URL first (cheap, works most of the time), and if it 404s, scan the most recent
GitHub Releases' asset lists for the exact filename. This is a bounded scan, not a full
repo-history search -- see that class's kdoc for the trade-off.

## 8. Why not just build on Suwayomi instead?

[Suwayomi](https://github.com/Suwayomi/Suwayomi-Server) already solved this exact problem
years ago, with a dedicated `AndroidCompat` module, and ships working desktop
(Tachidesk-JUI) and web clients today. If the goal is "read manga on desktop using the
Tachiyomi/Mihon extension catalog" as a *user*, Suwayomi is the pragmatic answer and this
project doesn't need to exist.

This project exists because the stub surface turned out to be far smaller than expected
(a few Android classes + three interceptors, not a general Android compatibility layer),
which makes a from-scratch, Mihon-code-based desktop client a tractable alternative when
the goal is specifically to keep Mihon's own UI/UX and codebase lineage rather than adopt
a different app's architecture.

## 9. `.tachibk` backup format findings (Mihon interop)

Implementing `.tachibk` import/export surfaced several facts about Mihon's backup format
that aren't written down anywhere upstream (see `extension-loader/.../backup/tachibk/TachibkModels.kt`
for the source links, verified against Mihon v0.20.4):

- **There is no `.proto` file anymore.** Mihon encodes backups with
  kotlinx.serialization.protobuf over `@ProtoNumber`-annotated model classes
  (`Backup.kt`, `BackupManga.kt`, …). The old Tachiyomi `.proto` era files that float
  around the internet disagree with current Mihon on several field numbers.
- **Concrete deviations from the old `.proto` assumptions:** `BackupChapter` has no
  `lastReadAt` (its `lastPageRead` *is* field 6), `BackupManga.status` is field 8,
  `BackupCategory.flags` is field 100, and `BackupManga.categories` holds category
  **order values**, not indices — the restorer resolves them via
  `backupCategories.associateBy { it.order }`.
- **kotlinx.protobuf follows proto3 default omission**: a field written with its
  zero-value is dropped from the wire. For fields *with* defaults that's harmless, but
  `BackupHistory.lastRead` has **no default** — encoding it as 0 omits the field and
  Mihon's restorer errors out per-manga. Our exporter stamps `lastRead` with the export
  time instead of 0 (`TachibkManager`).
- **Validation strategy that worked**: don't trust either side's wire format claims —
  round-trip our hand-written codec's output through the real
  `kotlinx.serialization.protobuf` serializer (the exact one Android Mihon uses) in
  `TachibkKotlinxInteropTest`. That test caught every field-number mistake before it
  reached a real device.
- **Import subset policy**: manga whose source is neither installed nor referenced by an
  existing library row are skipped (and counted) rather than imported as rows with an
  empty `jarFileName` — such a row can never resolve to a loadable source on desktop.
