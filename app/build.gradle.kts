import java.io.File
import org.gradle.jvm.tasks.Jar

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
    @Suppress("DEPRECATION")
    implementation(compose.material3)
    @Suppress("DEPRECATION")
    implementation(compose.materialIconsExtended)

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("mihon.desktop.app.MainKt")
}

// Version used for installer metadata (--app-version) and the fat-jar manifest.
// CI release builds pass -PappVersion=<x.y.z> derived from the git tag
// (see .github/workflows/release.yml); local builds fall back to this default.
val appVersion = (findProperty("appVersion") as String?) ?: "1.0.0"

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

// ---------------------------------------------------------------------------
// jpackage: create native installers (dmg/exe/msi/deb/rpm)
//
// Requires JDK 14+ (jpackage is bundled). Run with:
//   ./gradlew :app:jpackage
//
// Packages the fat jar (which already bundles the current-OS Compose/Skiko
// natives via `compose.desktop.currentOs`), so it must run on the target OS.
// Output goes to build/jpackage/
// ---------------------------------------------------------------------------
tasks.register("jpackage") {
    group = "distribution"
    description = "Create native installer using jpackage (requires JDK 14+)"

    dependsOn("fatJar")

    doLast {
        val libsDir = File("${layout.buildDirectory.get()}/libs")
        val fatJar = libsDir.listFiles()?.singleOrNull { it.name.endsWith("-all.jar") }
            ?: throw GradleException("No fat jar found in ${libsDir}. Run :app:fatJar first.")

        // Stage the fat jar alone: jpackage copies *everything* under --input
        // into the app image, and libs/ also holds the thin project jar.
        // Wipe both dirs first so stale installers/fat jars from earlier
        // local builds can't leak into the app image or the CI artifact glob.
        val inputDir = File("${layout.buildDirectory.get()}/jpackage-input")
        val outputDir = File("${layout.buildDirectory.get()}/jpackage")
        inputDir.deleteRecursively()
        outputDir.deleteRecursively()
        inputDir.mkdirs()
        fatJar.copyTo(File(inputDir, fatJar.name), overwrite = true)

        // Windows binaries carry a ".exe" suffix that java.io.File.exists()
        // does not resolve implicitly, so probe both names.
        val jpackageExec = System.getProperty("java.home")?.let { home ->
            listOf("jpackage", "jpackage.exe")
                .map { File(File(home, "bin"), it) }
                .firstOrNull { it.isFile }?.absolutePath
        } ?: throw GradleException("jpackage not found. Ensure you're using JDK 14+.")

        outputDir.mkdirs()

        val os = System.getProperty("os.name").lowercase()
        val appName = "Mihon Desktop"

        val args = mutableListOf(
            jpackageExec,
            "--input", inputDir.absolutePath,
            "--main-jar", fatJar.name,
            "--main-class", "mihon.desktop.app.MainKt",
            "--name", appName,
            "--app-version", appVersion,
            "--dest", outputDir.absolutePath,
            "--verbose",
        )

        when {
            os.contains("mac") -> {
                args.addAll(listOf("--type", "dmg"))
            }
            os.contains("linux") -> {
                args.addAll(listOf("--type", "deb"))
            }
            os.contains("win") -> {
                args.addAll(listOf("--type", "exe"))
            }
        }

        val process = ProcessBuilder(args)
            .directory(outputDir)
            .redirectErrorStream(true)
            .start()
        // Drain output BEFORE waitFor(): --verbose easily exceeds the 64KB
        // pipe buffer and would otherwise deadlock the process on write.
        // readText() blocks until EOF, i.e. until the process exits.
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("jpackage failed (exit $exitCode):\n$output")
        }

        logger.lifecycle("Installer created in: ${outputDir.absolutePath}")
    }
}

// ---------------------------------------------------------------------------
// fatJar: create a single executable JAR with all dependencies bundled
//
// Run with:
//   ./gradlew :app:fatJar
//
// Output goes to build/libs/mihon-desktop.jar
// Run with: java -jar mihon-desktop.jar
// ---------------------------------------------------------------------------
tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Create a single executable JAR with all dependencies"

    archiveClassifier.set("all")
    archiveBaseName.set("mihon-desktop")
    // Empty version keeps the stable name mihon-desktop-all.jar (matches the
    // run command documented in README/AGENTS.md). CI renames per release.
    archiveVersion.set("")

    manifest {
        attributes(
            "Main-Class" to "mihon.desktop.app.MainKt",
            "Implementation-Title" to "Mihon Desktop",
            "Implementation-Version" to appVersion,
        )
    }

    // Include all project classes
    from(sourceSets.main.get().output)

    // Include all runtime dependencies
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    })

    // Exclude signatures and other META-INF that would conflict
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/NOTICE", "META-INF/LICENSE")
}
