# Documentation

Welcome to the Mihon Desktop documentation.

## Getting Started

- [README](../README.md) — Project overview, quick start, features
- [Contributing](../CONTRIBUTING.md) — Development guidelines, how to contribute

## Architecture

- [Architecture](ARCHITECTURE.md) — Module structure, design decisions, data flow
- [Research](RESEARCH.md) — Investigation into running Mihon extensions on desktop
- [Roadmap](ROADMAP.md) — Project phases and future plans
- [Navigation Implementation Plan](NAVIGATION-IMPLEMENTATION-PLAN.md) — The 5-tab navigation rework plan (implemented; includes deviations)

## Key Concepts

### Extension Loading

Mihon Desktop loads Android extensions from [keiyoushi/extensions](https://github.com/keiyoushi/extensions) on a plain JVM. The key insight: keiyoushi publishes each extension as both an Android `.apk` and a JVM `.jar`, and the `.jar` contains standard JVM bytecode.

See [RESEARCH.md](RESEARCH.md) §3 for the full investigation.

### Android Stubs

Extension bytecode references Android types by name, but only a small subset is actually used. We provide JVM classes with:
- Exact FQCNs matching extension expectations
- Just enough behavior to satisfy `source-api`'s own code
- Documented gaps (e.g., without JCEF on the classpath the `CloudflareInterceptor` detects challenges, retries once with a browser User-Agent, then fails with a clear error)

See [RESEARCH.md](RESEARCH.md) §4-5 for the investigation process.

### Module Dependency Chain

```
platform-compat  →  source-api  →  extension-loader  →  app
```

Each module depends only on the one below. No reverse dependencies allowed.

See [ARCHITECTURE.md](ARCHITECTURE.md) for module details.

## Development

### Prerequisites

- **JDK 21+** (no Android SDK required)
- **Git**

### Commands

```bash
./gradlew build              # compile all modules + run all tests
./gradlew :app:run           # launch GUI
./gradlew :app:jpackage      # create native installer
./gradlew test               # run all tests (377, JUnit 4)
./gradlew :extension-loader:test   # extension-loader only (323 tests)
```

CI (`.github/workflows/ci.yml`) runs `./gradlew build` on every push/PR
(ubuntu-latest, Temurin 21). Tests are headless-safe; the extension-loading
pipeline is covered by an integration harness that builds a synthetic extension
jar in-test (no network, no real extension shipped).

### Testing Extensions

1. Run the app: `./gradlew :app:run`
2. Browse extensions in the catalog
3. Install and load an extension
4. If it fails, read the stack trace for missing symbols
5. Add stubs to `platform-compat` as needed

See [RESEARCH.md](RESEARCH.md) §4-5 for real examples.

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

## Troubleshooting

### Missing Stubs

**Symptom:** `NoClassDefFoundError` or `InjektionException` at runtime

**Solution:** Add the missing class to `platform-compat` with the exact FQCN referenced in the stack trace.

See [RESEARCH.md](RESEARCH.md) §4-5 for examples.

### Extension Won't Load

**Symptom:** Extension loads but crashes when making requests

**Solution:** Check if the extension requires:
- Specific OkHttp interceptors (add to `NetworkHelper`)
- QuickJS engine (bundled by default; if it was removed, re-add `io.github.dokar3:quickjs-kt-jvm`)
- Cloudflare bypass (add `me.friwi:jcefmaven` to classpath)

### Build Fails

**Symptom:** Build errors related to missing dependencies

**Solution:** Ensure JDK 21+ is installed and configured:

```bash
java -version  # should show 21+
```

## Further Reading

- [Mihon GitHub](https://github.com/mihonapp/mihon) — Original Android manga reader
- [keiyoushi/extensions](https://github.com/keiyoushi/extensions) — Extension repository
- [Suwayomi](https://github.com/Suwayomi/Suwayomi-Server) — Prior art for JVM-based extension loading

## License

This project is licensed under the Apache License, Version 2.0 — see [LICENSE](../LICENSE) for details.
