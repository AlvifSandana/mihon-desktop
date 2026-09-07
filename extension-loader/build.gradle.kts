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
}

sqldelight {
    databases {
        create("MihonDesktopDatabase") {
            packageName.set("mihon.desktop.loader.library")
        }
    }
}
