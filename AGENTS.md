# AGENTS.md

## Build & Run

```bash
./gradlew build                  # compile all modules
./gradlew :app:run               # launch GUI (JDK 21+)
./gradlew :app:fatJar            # single executable JAR (~94MB)
./gradlew :app:jpackage          # native installer (dmg/deb/exe)
```

Run fat JAR: `java -jar app/build/libs/mihon-desktop-all.jar`

No Android SDK, emulator, or Docker required. Pure JVM.

## Module Dependency Chain

```
platform-compat  →  source-api  →  extension-loader  →  app
```

Each module depends only on the one below. Never add a reverse dependency.

## Critical Constraints

- **JDK 21+** — all modules use `jvmToolchain(21)`
- **platform-compat FQCNs are load-bearing** — extension bytecode references `android.content.Context`, `eu.kanade.tachiyomi.network.NetworkHelper`, etc. by exact name. Renaming/repackaging any of these breaks every extension silently at class-load time (not compile time).
- **source-api must stay close to upstream Mihon** — it's the contract extension jars were compiled against. Only strip things that don't affect behavior (context receivers, compose annotations).
- **Extension jars only, never .apk** — `ExtensionLoader.load` opens `.jar` assets via `URLClassLoader`. The `.apk`'s `classes.dex` cannot be loaded by JVM.

## Testing

```bash
./gradlew test               # all modules
./gradlew :extension-loader:test
./gradlew :platform-compat:test
./gradlew :app:test
```

377 JUnit 4 tests across three modules (extension-loader 323, platform-compat 35, app 19).
Includes an integration harness (`extension-loader/src/test/.../integration/`) that builds
a synthetic extension jar in-test (in-memory javac, plain-text manifest, zero network) and
drives the full discovery → sidecar → metadata → `URLClassLoader` → instantiation pipeline.
No UI/E2E tests; AWT-touching code is guarded headless-safe. CI runs `./gradlew build`
on every push/PR (`.github/workflows/ci.yml`, Temurin 21).

## Common Pitfalls

- Missing Android stubs manifest as `NoClassDefFoundError`/`InjektionException` at runtime, not compile time. When testing a new extension, read the stack trace for the missing symbol and add it to `platform-compat`.
- `DesktopExtensionRuntime.bootstrap()` must be called once before any `ExtensionLoader.load()`. It registers Injekt singletons (`Context`, `Application`, `NetworkHelper`, `Json`).
- `AsyncImage` uses an LRU disk cache keyed on URL string. Passing a non-String key skips caching.
- Network calls in Compose screens must be `suspend` + `Dispatchers.IO`. Never call OkHttp sync on the UI thread.
- SQLDelight schema lives in `extension-loader/src/main/sqldelight/`. Generated code goes to `build/generated/sqldelight/`.
- SQLite `INSERT OR REPLACE` assigns a fresh row id, orphaning rows that reference the old one (mangaCategory, tracker bindings). Upsert by select-then-update to keep ids stable — see `LibraryRepository.upsertLibraryManga`.
- `DownloadQueue` workers launch with `CoroutineStart.ATOMIC` deliberately: a default-start coroutine cancelled before first dispatch skips its body entirely, leaving the job stuck RUNNING with no cleanup. Don't "simplify" it away.
- kotlinx.serialization.protobuf fields without defaults (e.g. Mihon's `BackupHistory.lastRead`) must never be encoded as proto3 zero-values — the field gets omitted and the consumer errors. `TachibkManager` stamps `lastRead` with the export time instead of 0.

## Architecture Quick Reference

- `platform-compat/` — Android stand-ins (Context, SharedPreferences, etc.) + networking (OkHttp, DoH, Cloudflare bypass, QuickJS shim)
- `source-api/` — Mihon's Source/HttpSource/CatalogueSource contracts, unmodified
- `extension-loader/` — extension discovery/loading/updates, catalog, downloads + queue, library DB (categories, stats), migration, trackers, backups (JSON + `.tachibk`), QuickJS
- `app/` — Compose Desktop UI, 5-tab navigation via `sealed interface Screen`, i18n (EN/ID), theme presets

See `docs/ARCHITECTURE.md` for full module details.

## Documentation

- `README.md` — Project overview, quick start, features
- `CONTRIBUTING.md` — Development guidelines, how to contribute
- `docs/ARCHITECTURE.md` — Module structure, design decisions, data flow
- `docs/RESEARCH.md` — Investigation into running Mihon extensions on desktop
- `docs/ROADMAP.md` — Project phases and future plans
- `docs/README.md` — Documentation hub
