plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":source-api"))
    implementation(libs.kotlinx.serialization.protobuf)
    // exposed as api: MihonDesktopDatabase (and its Transacter supertype, which
    // references QueryResult) is part of this module's public API via LibraryDatabase/
    // LibraryRepository, so callers need the sqldelight runtime on their classpath too.
    api(libs.sqldelight.driver)
    api(libs.sqldelight.coroutines)

    // QuickJS for JS-executing extensions (obfuscated sources, CF-bypass
    // scripts). The `app.cash.quickjs.QuickJs` API that extension jars link
    // against is provided by the compatibility shim in :platform-compat
    // (backed by io.github.dokar3:quickjs-kt-jvm) — no extra dependency here.

    // Protobuf wire primitives for the hand-written Mihon `.tachibk` codec
    // (backup/tachibk). Only CodedInputStream/CodedOutputStream are used —
    // no generated messages, no protoc plugin.
    implementation(libs.protobuf.java)

    // Optional: JCEF for Cloudflare bypass.
    // compileOnly so the app works without it; natives are ~100MB per platform.
    compileOnly(libs.jcefmaven)

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
}

sqldelight {
    databases {
        create("MihonDesktopDatabase") {
            packageName.set("mihon.desktop.loader.library")
        }
    }
}
