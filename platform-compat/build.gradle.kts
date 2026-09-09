plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.okhttp)
    api(libs.okhttp.brotli)
    api(libs.okhttp.dnsoverhttps)
    api(libs.okhttp.zstd)
    api(libs.okio)
    api(libs.jsoup)
    api(libs.rxjava)
    api(libs.injekt)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.json.okio)

    // QuickJS backend for the `app.cash.quickjs` compatibility shim in this
    // module. dokar's quickjs-kt ships self-contained JNI natives (no system
    // libc++ dependency) for linux x64/aarch64, macOS x64/aarch64 and Windows
    // x64. See app/cash/quickjs/QuickJs.kt for why it is not the cashapp
    // artifact itself.
    implementation(libs.quickjs.kt.jvm)

    // Optional: JCEF for real Cloudflare bypass.
    // compileOnly so the app works without it; natives are ~100MB per platform.
    compileOnly(libs.jcefmaven)

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
}
