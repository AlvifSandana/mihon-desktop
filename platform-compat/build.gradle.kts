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
    api(libs.okhttp.zstd)
    api(libs.okio)
    api(libs.jsoup)
    api(libs.rxjava)
    api(libs.injekt)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.json.okio)

    // Optional: JCEF for real Cloudflare bypass.
    // compileOnly so the app works without it; natives are ~100MB per platform.
    compileOnly(libs.jcefmaven)
}
