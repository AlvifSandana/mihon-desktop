package mihon.desktop.loader

import mihon.desktop.loader.log.Logger
import java.io.File
import java.security.MessageDigest

private const val TAG = "JarIntegrity"

/**
 * Tamper-evidence for cached extension jars.
 *
 * [ExtensionDownloader][mihon.desktop.loader.catalog.ExtensionDownloader] verifies each
 * download against keiyoushi's `release-assets.json` sha256 manifest (when reachable),
 * then writes a `<jar>.sha256` sidecar recording the hash of the file as served.
 * [ExtensionLoader] re-checks the jar against that sidecar before loading it, so a jar
 * that was replaced or modified on disk after download is rejected instead of silently
 * executed.
 *
 * Limitations (documented trade-offs, not guarantees against a same-user attacker):
 * - jars installed before sidecars existed load without a check (logged);
 * - the sidecar itself is writable by the same user, so an attacker with write access
 *   to the cache dir can re-forge it. Full fix would require signing or a
 *   read-only trust store.
 */
internal object JarIntegrity {

    fun sidecarFor(jarFile: File): File = File(jarFile.parentFile, jarFile.name + ".sha256")

    /** Records the current on-disk hash of [jarFile] in its sidecar (atomic write). */
    fun writeSidecar(jarFile: File) {
        val sidecar = sidecarFor(jarFile)
        val tmp = File(sidecar.parentFile, sidecar.name + ".tmp")
        try {
            tmp.writeText(sha256Of(jarFile))
            check(tmp.renameTo(sidecar)) { "Failed to move $tmp to $sidecar" }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    fun removeSidecar(jarFile: File) {
        sidecarFor(jarFile).delete()
    }

    /**
     * Throws [SecurityException] if [jarFile] no longer matches its sidecar hash.
     * No-op when no sidecar exists (legacy install) -- a warning is logged.
     */
    fun verifyAgainstSidecar(jarFile: File) {
        val sidecar = sidecarFor(jarFile)
        if (!sidecar.exists()) {
            Logger.w(TAG, "No integrity sidecar for ${jarFile.name}; loading unverified")
            return
        }
        check(jarFile.exists()) {
            "Integrity sidecar exists for ${jarFile.name} but the jar itself is missing"
        }
        val expected = sidecar.readText().trim()
        val actual = sha256Of(jarFile)
        check(actual == expected) {
            "${jarFile.name} failed sidecar sha256 verification " +
                "(expected $expected, got $actual) -- the file changed after it was " +
                "downloaded. Refusing to load it."
        }
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
