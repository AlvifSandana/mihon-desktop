package mihon.desktop.loader

import java.io.File
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Metadata read out of a keiyoushi/Mihon extension's AndroidManifest.xml.
 *
 * The `.jar` variant keiyoushi publishes alongside every `.apk` keeps this file as
 * plain-text XML (not compiled AXML), so it can be parsed with a stock XML parser --
 * no Android SDK / aapt / PackageManager involved.
 */
data class ExtensionMetadata(
    val packageName: String,
    val versionCode: Long?,
    val versionName: String?,
    val name: String?,
    val sourceClass: String,
    val sourceFactory: String?,
    val isNsfw: Boolean,
    val extensionLibVersion: String?,
)

private const val META_NAME = "tachiyomix.name"
private const val META_SOURCE_CLASS = "tachiyomi.extension.class"
private const val META_SOURCE_FACTORY = "tachiyomi.extension.factory"
private const val META_NSFW = "tachiyomi.extension.nsfw"
private const val META_EXTENSION_LIB = "tachiyomix.extensionLib"

object ExtensionMetadataReader {
    fun read(jarFile: File): ExtensionMetadata {
        JarFile(jarFile).use { jar ->
            val entry = jar.getJarEntry("AndroidManifest.xml")
                ?: error("No AndroidManifest.xml in $jarFile -- is this a keiyoushi/Mihon extension jar?")

            val doc = jar.getInputStream(entry).use { input ->
                // XXE hardening: the manifest comes from a downloaded jar, so a
                // hostile one must not be able to pull in local files or remote
                // entities via DOCTYPE/ENTITY. Real extension manifests never
                // use a DTD, so doctypes are rejected outright.
                val factory = DocumentBuilderFactory.newInstance()
                runCatching {
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
                    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
                factory.isXIncludeAware = false
                factory.isExpandEntityReferences = false
                factory.newDocumentBuilder().parse(input)
            }

            val packageName = doc.documentElement.getAttribute("package")
            val versionCode = doc.documentElement.getAttribute("android:versionCode")
                .toLongOrNull()
            val versionName = doc.documentElement.getAttribute("android:versionName").ifBlank { null }

            val metaData = mutableMapOf<String, String>()
            val metaDataNodes = doc.getElementsByTagName("meta-data")
            for (i in 0 until metaDataNodes.length) {
                val attrs = metaDataNodes.item(i).attributes
                val name = attrs.getNamedItem("android:name")?.nodeValue ?: continue
                val value = attrs.getNamedItem("android:value")?.nodeValue ?: continue
                metaData[name] = value
            }

            val sourceClass = metaData[META_SOURCE_CLASS]
                ?: error("Missing $META_SOURCE_CLASS metadata in $jarFile")

            return ExtensionMetadata(
                packageName = packageName,
                versionCode = versionCode,
                versionName = versionName,
                name = metaData[META_NAME],
                sourceClass = sourceClass,
                sourceFactory = metaData[META_SOURCE_FACTORY],
                isNsfw = metaData[META_NSFW] == "1",
                extensionLibVersion = metaData[META_EXTENSION_LIB],
            )
        }
    }
}
