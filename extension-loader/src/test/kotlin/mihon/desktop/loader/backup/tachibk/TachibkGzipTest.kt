package mihon.desktop.loader.backup.tachibk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.zip.GZIPOutputStream

/**
 * GZIP detection, bounded decompression and malformed-input handling for
 * `.tachibk` files. The cap test guards against gzip bombs: a few KB of
 * compressed input must never balloon into a huge allocation.
 */
class TachibkGzipTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `gzip and gunzip round trip`() {
        val payload = TachibkCodec.encode(
            TachibkBackup(backupManga = listOf(TachibkManga(source = 1, url = "u", title = "T"))),
        )
        val compressed = TachibkGzip.gzip(payload)
        assertTrue(compressed.size >= 2)
        assertEquals(0x1f, compressed[0].toInt() and 0xff)
        assertEquals(0x8b, compressed[1].toInt() and 0xff)
        assertArrayEquals(payload, TachibkGzip.gunzip(ByteArrayInputStream(compressed)))
    }

    @Test
    fun `readBackupPayload decompresses gzipped backups`() {
        val payload = TachibkCodec.encode(TachibkBackup(backupManga = listOf(TachibkManga(source = 1, url = "u"))))
        val file = folder.newFile("backup.tachibk")
        file.writeBytes(TachibkGzip.gzip(payload))
        assertArrayEquals(payload, TachibkGzip.readBackupPayload(file))
    }

    @Test
    fun `readBackupPayload accepts plain protobuf`() {
        val payload = TachibkCodec.encode(TachibkBackup(backupManga = listOf(TachibkManga(source = 1, url = "u"))))
        val file = folder.newFile("backup.tachibk")
        file.writeBytes(payload)
        assertArrayEquals(payload, TachibkGzip.readBackupPayload(file))
    }

    @Test
    fun `gzip bomb beyond cap throws clear exception without oom`() {
        // 512 KB of incompressible-ish zeros -> a few KB compressed; cap is 64 KB.
        val bomb = TachibkGzip.gzip(ByteArray(512 * 1024))
        try {
            TachibkGzip.gunzip(ByteArrayInputStream(bomb), maxDecompressedBytes = 64 * 1024)
            fail("expected BackupSizeExceededException")
        } catch (e: TachibkGzip.BackupSizeExceededException) {
            assertTrue(e.message!!.contains("exceeds"))
        }
    }

    @Test
    fun `oversize plain file is rejected without reading`() {
        // Use a small cap with a small-but-over-cap file: the length check
        // must fire before any parsing.
        val file = folder.newFile("big.tachibk")
        file.writeBytes(ByteArray(256))
        try {
            TachibkGzip.readBackupPayload(file, maxDecompressedBytes = 128)
            fail("expected BackupSizeExceededException")
        } catch (e: TachibkGzip.BackupSizeExceededException) {
            assertTrue(e.message!!.contains("beyond"))
        }
    }

    @Test
    fun `truncated gzip stream throws clear error`() {
        val payload = TachibkGzip.gzip(TachibkCodec.encode(TachibkBackup()))
        val truncated = payload.copyOf(payload.size - 5)
        try {
            TachibkGzip.gunzip(ByteArrayInputStream(truncated))
            fail("expected TachibkFormatException")
        } catch (e: TachibkFormatException) {
            assertTrue(e.message!!.contains("Invalid gzip"))
        }
    }

    @Test
    fun `random non-gzip bytes produce a clear parse error`() {
        // Deterministic pseudo-random bytes that do NOT start with the gzip
        // magic: detection passes them through as plain protobuf, and the
        // protobuf parser must reject them with a clear message.
        val bytes = ByteArray(512) { ((it * 31 + 7) and 0xff).toByte() }
        check(bytes[0].toInt() and 0xff != 0x1f)
        val file = folder.newFile("random.tachibk")
        file.writeBytes(bytes)
        try {
            TachibkCodec.decode(TachibkGzip.readBackupPayload(file))
            fail("expected TachibkFormatException")
        } catch (e: TachibkFormatException) {
            assertTrue(e.message!!.contains("Malformed"))
        }
    }

    @Test
    fun `json backup is rejected with a specific message`() {
        val file = folder.newFile("backup.json")
        file.writeText("""{"libraryManga": []}""")
        try {
            TachibkGzip.readBackupPayload(file)
            fail("expected TachibkFormatException")
        } catch (e: TachibkFormatException) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }
}
