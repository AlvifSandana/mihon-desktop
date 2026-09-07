# Notice

This project is licensed under the Apache License, Version 2.0 (see `LICENSE`), the same
license as [Mihon](https://github.com/mihonapp/mihon)
(Copyright © 2015 Javier Tomás, Copyright © 2024 Mihon Open Source Project) and
[Tachiyomi](https://github.com/tachiyomiorg), which it is not affiliated with.

## Code provenance

- **`source-api/`** is copied from Mihon's own `source-api` module, unmodified except for
  two non-behavioral changes noted in `docs/ARCHITECTURE.md` (dropping a Compose-only
  annotation, and replacing a Kotlin context-receiver signature with an explicit
  parameter). This is intentional: it is the contract every extension jar was compiled
  against, and keeping it byte-for-byte close to upstream is a deliberate design decision,
  not an oversight.
- **`platform-compat/`**'s networking files (`Requests.kt`, `HttpException.kt`,
  `ProgressListener.kt`, `ProgressResponseBody.kt`, `OkHttpExtensions.kt`,
  `UncaughtExceptionInterceptor.kt`, `UserAgentInterceptor.kt`,
  `RxCoroutineBridge.kt`, `JsonObject.kt`) are likewise copied from Mihon's `core/common`
  module, unmodified or trivially adapted.
- **`platform-compat/`**'s `android/`, `androidx/` packages, `NetworkHelper.kt`,
  `AndroidCookieJar.kt`, and `CloudflareInterceptor.kt` are original code written for this
  project — they stand in for Android APIs the extension bytecode references, and are not
  derived from Mihon or Android source.
- **`extension-loader/`** and **`app/`** are original code written for this project,
  though `extension-loader`'s design deliberately mirrors the structure of Mihon's own
  `eu.kanade.tachiyomi.extension.util.ExtensionLoader` (see its kdoc).

## Extensions are not included

No extension code is vendored in this repository. `samples/extensions/*.jar` are
downloaded, unmodified copies of build artifacts published by
[keiyoushi/extensions](https://github.com/keiyoushi/extensions) for local testing of the
loader — see that repository for its own license terms per-extension.
