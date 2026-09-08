package mihon.desktop.loader

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File
import java.net.URLClassLoader

/**
 * A loaded extension jar: its metadata plus the [Source] instance(s) it produced.
 */
data class LoadedExtension(
    val metadata: ExtensionMetadata,
    val sources: List<Source>,
)

/**
 * Loads a keiyoushi/Mihon extension `.jar` (the JVM-native artifact keiyoushi publishes
 * alongside the Android `.apk` -- see docs/RESEARCH.md) directly on a plain JVM.
 *
 * This mirrors Mihon's own `eu.kanade.tachiyomi.extension.util.ExtensionLoader`, minus the
 * Android PackageManager/DexClassLoader plumbing: metadata comes from parsing the manifest
 * as plain XML (see [ExtensionMetadataReader]), and classes load through a normal JVM
 * [URLClassLoader] since the jar contains standard `.class` bytecode, not DEX.
 *
 * Before calling [load], the caller must have registered the Injekt singletons the
 * `:source-api`/`:platform-compat` classes expect -- see [DesktopExtensionRuntime.bootstrap].
 */
object ExtensionLoader {
    private val extensionCacheDir = File(System.getProperty("user.home"), ".mihon-desktop/extension-cache")

    /** Strict allowlist: one plain file name ending in .jar, nothing else. */
    private val cachedJarNamePattern = Regex("[A-Za-z0-9._-]+\\.jar")

    /**
     * Loads an extension jar from the local cache directory (`~/.mihon-desktop/extension-cache`)
     * by file name. The name comes from persisted DB rows, so it is validated against a
     * strict allowlist (single path segment, no hidden files, no Windows drive-relative
     * tricks): a tampered `jarFileName` value must never be able to point outside the
     * cache dir.
     */
    fun loadCached(jarFileName: String): LoadedExtension {
        require(jarFileName.matches(cachedJarNamePattern)) {
            "Invalid extension jar file name: '$jarFileName'"
        }
        return load(File(extensionCacheDir, jarFileName))
    }

    fun load(jarFile: File): LoadedExtension {
        val metadata = ExtensionMetadataReader.read(jarFile)
        val classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()), Thread.currentThread().contextClassLoader)

        val sources = metadata.sourceClass.split(";").map { it.trim() }.flatMap { rawClassName ->
            val className = if (rawClassName.startsWith(".")) {
                metadata.packageName + rawClassName
            } else {
                rawClassName
            }
            when (val instance = Class.forName(className, true, classLoader).getDeclaredConstructor().newInstance()) {
                is Source -> listOf(instance)
                is SourceFactory -> instance.createSources()
                else -> error("Unknown source class type for $className: ${instance.javaClass}")
            }
        }

        return LoadedExtension(metadata, sources)
    }
}
