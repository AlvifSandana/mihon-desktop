package mihon.desktop.loader

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
 * Results are cached per (jar path, last-modified, length): every screen entry and each
 * hourly scheduler run used to re-parse the manifest and spin up a fresh [URLClassLoader]
 * -- leaking file descriptors and metaspace over long sessions. Cache hits return the same
 * [Source] instances, so per-source in-memory state (clients, QuickJS engines) is shared
 * instead of duplicated per caller.
 *
 * Before calling [load], the caller must have registered the Injekt singletons the
 * `:source-api`/`:platform-compat` classes expect -- see [DesktopExtensionRuntime.bootstrap].
 */
object ExtensionLoader {
    private val extensionCacheDir = File(System.getProperty("user.home"), ".mihon-desktop/extension-cache")

    /**
     * Strict allowlist: one plain file name ending in .jar. The first character
     * must not be a dot, so hidden files and `..` are rejected.
     */
    private val cachedJarNamePattern = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.jar")

    /** How long an evicted class loader stays open for live screens to finish lazy loads. */
    private val evictedLoaderGracePeriod = TimeUnit.MINUTES.toMillis(10)

    private class CacheEntry(
        val lastModified: Long,
        val length: Long,
        val classLoader: URLClassLoader,
        val extension: LoadedExtension,
    )

    private class EvictedLoader(val path: String, val classLoader: URLClassLoader, val evictedAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // Guarded by @Synchronized on load/removeCachedJar.
    private val evictedLoaders = mutableListOf<EvictedLoader>()

    /**
     * Loads an extension jar from the local cache directory (`~/.mihon-desktop/extension-cache`)
     * by file name. The name comes from persisted DB rows, so it is validated against a
     * strict allowlist (single path segment, no hidden files, no Windows drive-relative
     * tricks) and the resolved path is additionally checked against the canonical cache
     * dir to reject symlinks pointing elsewhere: a tampered `jarFileName` value must
     * never be able to load a file outside the cache dir.
     */
    fun loadCached(jarFileName: String): LoadedExtension {
        require(isValidCachedJarName(jarFileName)) {
            "Invalid extension jar file name: '$jarFileName'"
        }
        val jarFile = File(extensionCacheDir, jarFileName)
        val canonicalDir = extensionCacheDir.canonicalPath + File.separator
        require(jarFile.canonicalPath.startsWith(canonicalDir)) {
            "Extension jar resolves outside the cache dir: '$jarFileName'"
        }
        return load(jarFile)
    }

    /** Whether [name] is an acceptable single-segment cache jar file name. */
    internal fun isValidCachedJarName(name: String): Boolean = name.matches(cachedJarNamePattern)

    /**
     * Drops the cached entry for [jarFile], closes its class loader IMMEDIATELY
     * (and any previously deferred close for the same jar), and removes its
     * integrity sidecar. Call before deleting/uninstalling a jar: on Windows the
     * open file handle makes delete() silently fail while a loader holds the
     * file, and a stale sidecar would block a manually re-placed jar.
     *
     * Unlike the deferred close used for silent evictions (jar replaced during
     * an update), this is an explicit user uninstall -- open screens referencing
     * the extension are expected to stop working, which is preferable to keeping
     * the file locked.
     */
    @Synchronized
    fun removeCachedJar(jarFile: File) {
        val path = jarFile.absolutePath
        cache.remove(path)?.let { entry ->
            runCatching { entry.classLoader.close() }
        }
        evictedLoaders.removeAll { it.path == path }
        JarIntegrity.removeSidecar(jarFile)
        closeExpiredEvictedLoaders()
    }

    /**
     * Loads an extension jar, reusing the cached result when the file hasn't changed.
     * Synchronized: jar parsing + class loading is heavy, and concurrent callers
     * (screens, scheduler) otherwise race to create duplicate class loaders.
     */
    @Synchronized
    fun load(jarFile: File): LoadedExtension {
        val key = jarFile.absolutePath
        val lastModified = jarFile.lastModified()
        val length = jarFile.length()

        cache[key]?.let { entry ->
            if (entry.lastModified == lastModified && entry.length == length) {
                return entry.extension
            }
            // Jar was replaced (extension update): evict the stale entry.
            evict(key)
        }

        // The jar changed on disk since the downloader served it (or is a fresh
        // file): re-check its hash against the download-time sidecar before
        // executing any of its code. Cache hits skip this -- an unchanged
        // lastModified + length means the content is what we already verified.
        JarIntegrity.verifyAgainstSidecar(jarFile)

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

        val extension = LoadedExtension(metadata, sources)
        cache[key] = CacheEntry(lastModified, length, classLoader, extension)
        closeExpiredEvictedLoaders()
        return extension
    }

    /**
     * Moves [key]'s entry to the deferred-close list. Closing immediately would break
     * [Source] instances still held by open screens: their classes stay valid, but the
     * FIRST lazy load of an as-yet-unloaded class through the closed loader would throw
     * NoClassDefFoundError. The grace period gives screens time to finish those loads;
     * after it, close() releases the jar file handle.
     */
    private fun evict(key: String) {
        cache.remove(key)?.let { entry ->
            evictedLoaders.add(EvictedLoader(key, entry.classLoader, System.currentTimeMillis()))
        }
    }

    private fun closeExpiredEvictedLoaders() {
        val cutoff = System.currentTimeMillis() - evictedLoaderGracePeriod
        val iterator = evictedLoaders.iterator()
        while (iterator.hasNext()) {
            val evicted = iterator.next()
            if (evicted.evictedAt < cutoff) {
                runCatching { evicted.classLoader.close() }
                iterator.remove()
            }
        }
    }
}
