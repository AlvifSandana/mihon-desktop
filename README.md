# Mihon Desktop

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-21+-green.svg)](https://openjdk.org/projects/jdk/21/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Compose%20Desktop-1.12.0-blue.svg)](https://www.jetbrains.com/compose/)

A desktop manga reader that runs [Mihon](https://github.com/mihonapp/mihon)'s Android extensions on a plain JVM — no Android SDK, emulator, or dex-to-JVM bridge required.

## What This Does

Mihon Desktop loads real extension `.jar` files from [keiyoushi/extensions](https://github.com/keiyoushi/extensions) and reads manga through them:

- Browse and install from the live keiyoushi catalog (~1400 extensions)
- Search manga, view details and chapters
- Read chapters with zoom/pan and webtoon mode
- Persistent library with reading progress
- Download chapters for offline reading
- Export/import backups

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
| Library management | ✅ Persistent manga library with progress |
| Chapter reader | ✅ Zoom/pan, keyboard nav, webtoon mode |
| Offline reading | ✅ Download chapters for offline access |
| Background updates | ✅ Automatic library update checks |
| Backups | ✅ Export/import library as JSON |
| Cloudflare bypass | ✅ Optional JCEF integration |
| QuickJS support | ✅ Optional real QuickJS engine |

## Architecture

```
platform-compat  →  source-api  →  extension-loader  →  app
```

| Module | Purpose |
|--------|---------|
| `platform-compat` | Android stand-ins (Context, SharedPreferences) + networking |
| `source-api` | Mihon's Source/HttpSource/CatalogueSource contracts |
| `extension-loader` | Extension discovery, loading, catalog, downloads, library DB |
| `app` | Compose Desktop UI |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed module documentation.

## Optional Dependencies

Add to runtime classpath for full feature set:

```kotlin
// Real QuickJS engine for JS-executing extensions
implementation("app.cash.quickjs:quickjs-jvm:0.9.2")

// Cloudflare bypass (JCEF) — natives are ~100MB per platform
implementation("me.friwi:jcefmaven:146.0.10")
```

Without these, the app still works — extensions that check interceptor presence don't crash, and JS execution falls back to `javax.script` (Nashorn/GraalJS if available).

## Data Storage

All data is stored in `~/.mihon-desktop/`:

| Path | Contents |
|------|----------|
| `library.db` | Library manga and reading progress |
| `extension-cache/` | Downloaded extension JARs |
| `downloads/` | Downloaded chapter pages |
| `backups/` | Library backup files |
| `http-cache/` | Network response cache |
| `prefs/` | Extension preferences |
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
