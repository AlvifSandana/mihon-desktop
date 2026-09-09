package mihon.desktop.loader.backup.tachibk

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * GZIP/plain detection and bounded decompression for `.tachibk` payloads.
 *
 * A `.tachibk` written by Android Mihon is a GZIP stream of the `Backup`
 * protobuf message; the gzip magic bytes (0x1f 0x8b) are sniffed like Mihon's
 * own `BackupDecoder` (which also rejects JSON backups with a specific
 * error). Because the input is untrusted, decompression is capped: once more
 * than [maxDecompressedBytes] have been produced, the stream is aborted with
 * [BackupSizeExceededException] instead of exhausting memory (gzip bombs).
 */
object TachibkGzip {

    /** Hard cap on the decompressed backup payload (256 MB). */
    const val MAX_DECOMPRESSED_BYTES: Long = 256L * 1024 * 1024

    private const val GZIP_MAGIC_HI = 0x1f
    private const val GZIP_MAGIC_LO = 0x8b
    private const val CHUNK = 64 * 1024

    /** Raised when the decompressed payload exceeds the configured cap. */
    class BackupSizeExceededException(message: String) : IOException(message)

    /**
     * Reads a whole (small) file into memory, then returns its protobuf
     * payload: gunzipped if it starts with the gzip magic bytes, raw
     * otherwise. Throws [TachibkFormatException] for JSON backups, truncated
     * gzip streams, and payloads beyond [maxDecompressedBytes].
     */
    fun readBackupPayload(file: File, maxDecompressedBytes: Long = MAX_DECOMPRESSED_BYTES): ByteArray {
        // Compressed side is bounded by the file itself; a plain-protobuf
        // backup larger than the cap is rejected without reading it.
        if (file.length() > maxDecompressedBytes) {
            throw BackupSizeExceededException(
                "Backup file is ${file.length()} bytes, beyond the $maxDecompressedBytes byte limit",
            )
        }
        val raw = file.readBytes()
        return if (raw.size >= 2 && raw[0].toInt() and 0xff == GZIP_MAGIC_HI && raw[1].toInt() and 0xff == GZIP_MAGIC_LO) {
            gunzip(raw.inputStream(), maxDecompressedBytes)
        } else {
            if (raw.isNotEmpty() && raw[0] == '{'.code.toByte()) {
                throw TachibkFormatException(
                    "This is a JSON backup, not a Mihon .tachibk protobuf backup",
                )
            }
            raw
        }
    }

    /**
     * Decompresses a gzip stream fully into memory, aborting with
     * [BackupSizeExceededException] as soon as the output exceeds
     * [maxDecompressedBytes] — the input stream is closed and only O(chunk)
     * extra memory is used, so a gzip bomb cannot OOM the process.
     */
    fun gunzip(input: InputStream, maxDecompressedBytes: Long = MAX_DECOMPRESSED_BYTES): ByteArray {
        try {
            GZIPInputStream(input, CHUNK).use { gz ->
                val out = ByteArrayOutputStream(CHUNK)
                val chunk = ByteArray(CHUNK)
                var total = 0L
                while (true) {
                    val read = gz.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > maxDecompressedBytes) {
                        throw BackupSizeExceededException(
                            "Decompressed backup exceeds $maxDecompressedBytes bytes (gzip bomb?)",
                        )
                    }
                    out.write(chunk, 0, read)
                }
                return out.toByteArray()
            }
        } catch (e: BackupSizeExceededException) {
            throw e
        } catch (e: IOException) {
            throw TachibkFormatException("Invalid gzip backup stream: ${e.message}", e)
        }
    }

    /** GZIP-compresses [bytes] (the `.tachibk` on-disk format). */
    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 16)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
