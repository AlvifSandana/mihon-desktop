plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":extension-loader"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
}

application {
    mainClass.set("mihon.desktop.app.MainKt")
}

tasks.named<JavaExec>("run") {
    // so relative sample paths from the README (run from the repo root) resolve correctly
    workingDir = rootProject.projectDir
}

// Compose Multiplatform's desktop runtime ships OS-specific jars that share a simple
// name across classifiers; the `application` plugin's dist tasks need to be told to
// just pick one instead of failing on the "duplicate".
tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
