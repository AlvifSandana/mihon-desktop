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
// Output goes to build/jpackage/
// ---------------------------------------------------------------------------
tasks.register("jpackage") {
    group = "distribution"
    description = "Create native installer using jpackage (requires JDK 14+)"

    dependsOn("packageUberJarForCurrentOS")

    doLast {
        val jarDir = File("${layout.buildDirectory.get()}/compose/jars")
        val jars = jarDir.listFiles()?.filter { it.name.endsWith(".jar") } ?: emptyList()
        val uberJar = jars.firstOrNull()
            ?: throw GradleException("No uber jar found in ${jarDir}. Run packageUberJarForCurrentOS first.")

        val jpackageExec = System.getProperty("java.home")?.let { home ->
            val bin = File(home, "bin/jpackage")
            if (bin.exists()) bin.absolutePath else null
        } ?: throw GradleException("jpackage not found. Ensure you're using JDK 14+.")

        val outputDir = File("${layout.buildDirectory.get()}/jpackage")
        outputDir.mkdirs()

        val os = System.getProperty("os.name").lowercase()
        val appName = "Mihon Desktop"
        val appVersion = "1.0.0"

        val args = mutableListOf(
            jpackageExec,
            "--input", jarDir.absolutePath,
            "--main-jar", uberJar.name,
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
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val output = process.inputStream.bufferedReader().readText()
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

    manifest {
        attributes(
            "Main-Class" to "mihon.desktop.app.MainKt",
            "Implementation-Title" to "Mihon Desktop",
            "Implementation-Version" to "1.0.0",
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
