package mihon.desktop.loader.backup.tachibk

import com.google.protobuf.CodedInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip and wire-format tests for the hand-written `.tachibk` codec.
 *
 * The wire-format pins (raw byte expectations like `0x08` for BackupManga
 * field 1) guard against accidental field renumbering — the silent killer
 * for Android Mihon interop.
 */
class TachibkCodecTest {

    private fun fullBackup(): TachibkBackup = TachibkBackup(
        backupManga = listOf(
            TachibkManga(
                source = 2499283573025,
                url = "manga/solo-leveling",
                title = "Solo Leveling",
                artist = "DUBU",
                author = "Chugong",
                status = 2,
                thumbnailUrl = "https://example.com/cover.jpg",
                dateAdded = 1700000000000L,
                favorite = true,
                chapters = listOf(
                    TachibkChapter(
                        url = "chapter/1",
                        name = "Chapter 1",
                        scanlator = "MangaDex",
                        read = true,
                        bookmark = true,
                        lastPageRead = 12,
                        dateFetch = 1700001000000L,
                        chapterNumber = 1.5f,
                        sourceOrder = 3,
                    ),
                    TachibkChapter(
                        url = "chapter/2",
                        name = "Chapter 2",
                        read = false,
                        lastPageRead = 0,
                    ),
                ),
                categories = listOf(0L, 2L),
                history = listOf(
                    TachibkHistory(chapterUrl = "chapter/1", lastRead = 1700002000000L, readDuration = 300),
                ),
            ),
            TachibkManga(
                source = 1234L,
                url = "manga/b",
                title = "B",
            ),
        ),
        backupCategories = listOf(
            TachibkCategory(name = "Reading", order = 0, flags = 0),
            TachibkCategory(name = "Plan to read", order = 2),
        ),
        backupSources = listOf(
            TachibkSource(name = "MangaDex", sourceId = 2499283573025),
        ),
    )

    @Test
    fun `round-trips a full backup`() {
        val backup = fullBackup()
        val decoded = TachibkCodec.decode(TachibkCodec.encode(backup))
        assertEquals(backup, decoded)
    }

    @Test
    fun `round-trips a manga with all fields`() {
        val manga = fullBackup().backupManga[0]
        val decoded = TachibkCodec.decodeManga(CodedInputStream.newInstance(TachibkCodec.encodeManga(manga)))
        assertEquals(manga, decoded)
    }

    @Test
    fun `round-trips a chapter with all fields`() {
        val chapter = fullBackup().backupManga[0].chapters[0]
        val decoded = TachibkCodec.decodeChapter(CodedInputStream.newInstance(TachibkCodec.encodeChapter(chapter)))
        assertEquals(chapter, decoded)
    }

    @Test
    fun `round-trips category, source and history`() {
        val category = TachibkCategory(name = "Reading", order = 4, flags = 8)
        assertEquals(
            category,
            TachibkCodec.decodeCategory(CodedInputStream.newInstance(TachibkCodec.encodeCategory(category))),
        )
        val source = TachibkSource(name = "MangaDex", sourceId = 123456789L)
        assertEquals(
            source,
            TachibkCodec.decodeSource(CodedInputStream.newInstance(TachibkCodec.encodeSource(source))),
        )
        val history = TachibkHistory(chapterUrl = "c/1", lastRead = 1700000000000L, readDuration = 60)
        assertEquals(
            history,
            TachibkCodec.decodeHistory(CodedInputStream.newInstance(TachibkCodec.encodeHistory(history))),
        )
    }

    @Test
    fun `round-trips an empty backup`() {
        val backup = TachibkBackup()
        val decoded = TachibkCodec.decode(TachibkCodec.encode(backup))
        assertEquals(backup, decoded)
        assertEquals(emptyList<TachibkManga>(), decoded.backupManga)
        assertEquals(emptyList<TachibkCategory>(), decoded.backupCategories)
        assertEquals(emptyList<TachibkSource>(), decoded.backupSources)
    }

    @Test
    fun `round-trips a manga with empty lists`() {
        val manga = TachibkManga(source = 1, url = "u", title = "t")
        val decoded = TachibkCodec.decodeManga(CodedInputStream.newInstance(TachibkCodec.encodeManga(manga)))
        assertEquals(emptyList<TachibkChapter>(), decoded.chapters)
        assertEquals(emptyList<Long>(), decoded.categories)
        assertEquals(emptyList<TachibkHistory>(), decoded.history)
    }

    @Test
    fun `all-default message encodes to zero bytes`() {
        // proto3 semantics: a message whose fields are all default values is
        // the empty byte string (matches kotlinx.serialization.protobuf).
        assertEquals(0, TachibkCodec.encode(TachibkBackup()).size)
        assertEquals(0, TachibkCodec.encodeChapter(TachibkChapter(url = "", name = "")).size)
        // BackupManga always emits favorite=true (its default is true, i.e.
        // non-zero on the wire), so only favorite=false drops to zero bytes
        // when everything else is default.
        assertEquals(
            0,
            TachibkCodec.encodeManga(TachibkManga(source = 0, url = "", title = "", favorite = false)).size,
        )
    }

    // ── Wire-format pins (field-number guards) ──────────────────────────

    @Test
    fun `pins Backup top-level field numbers`() {
        val bytes = TachibkCodec.encode(fullBackup())
        // First field is backupManga (field 1, length-delimited): tag = (1 << 3) | 2 = 0x0a.
        assertEquals(0x0a, bytes[0].toInt() and 0xff)
    }

    @Test
    fun `pins BackupManga field numbers`() {
        val manga = TachibkManga(source = 7, url = "u", title = "T", author = "A")
        val bytes = TachibkCodec.encodeManga(manga)
        // field 1 source varint: tag = (1 << 3) | 0 = 0x08, then value 7
        assertEquals(0x08, bytes[0].toInt() and 0xff)
        assertEquals(7, bytes[1].toInt())
        // field 2 url: tag = (2 << 3) | 2 = 0x12
        assertEquals(0x12, bytes[2].toInt() and 0xff)
    }

    @Test
    fun `pins BackupChapter field numbers`() {
        // The critical interop pin: Mihon v0.14+ encodes lastPageRead as
        // field 6 (no lastReadAt exists in any Mihon release; field 6/7
        // lastReadAt/lastPageRead is the dead pre-Mihon 1.x numbering).
        val bytes = TachibkCodec.encodeChapter(
            TachibkChapter(url = "c", name = "n", lastPageRead = 9, read = true),
        )
        // url tag 0x0a, name tag 0x12, read (field 4) tag = 0x20 value 1,
        // lastPageRead (field 6) tag = (6 << 3) = 0x30 value 9
        val asList = bytes.toList()
        assertTrue("field 4 tag missing", asList.contains(0x20))
        assertTrue("field 6 tag missing", asList.contains(0x30))
        val idx6 = asList.indexOf(0x30)
        assertEquals(9, bytes[idx6 + 1].toInt())
    }

    // ── Unknown-field skipping ──────────────────────────────────────────

    @Test
    fun `skips unknown varint and length-delimited fields at top level`() {
        val valid = TachibkCodec.encode(
            TachibkBackup(backupSources = listOf(TachibkSource(name = "S", sourceId = 7))),
        )
        // Unknown varint field 99 (tag bytes 0x98 0x1c, value 5) and unknown
        // length-delimited field 200 (tag 0xd2 0x0c, len 3, junk payload).
        val prefix = byteArrayOf(
            0x98.toByte(), 0x1c, 5,
            0xd2.toByte(), 0x0c, 3, 1, 2, 3,
        )
        val decoded = TachibkCodec.decode(prefix + valid)
        assertEquals(1, decoded.backupSources.size)
        assertEquals(7L, decoded.backupSources[0].sourceId)
        assertEquals("S", decoded.backupSources[0].name)
        assertEquals(0, decoded.backupManga.size)
    }

    @Test
    fun `skips unknown fields nested inside BackupManga`() {
        val valid = TachibkCodec.encodeManga(TachibkManga(source = 1, url = "u", title = "T"))
        // Unknown varint field 102 inside the manga (tag 0x90 0x06, value 42).
        val prefix = byteArrayOf(0x90.toByte(), 0x06, 42)
        val backupBytes = prefix + valid
        // Wrap as a top-level backupManga entry: tag 0x0a + varint length.
        val wrapped = byteArrayOf(0x0a) + varint(backupBytes.size) + backupBytes
        val decoded = TachibkCodec.decode(wrapped)
        assertEquals(1, decoded.backupManga.size)
        assertEquals("u", decoded.backupManga[0].url)
        assertEquals("T", decoded.backupManga[0].title)
    }

    @Test
    fun `known field on unexpected wire type is skipped not fatal`() {
        // BackupManga.url (field 2) arriving as a varint (fixed64 wire type
        // would also apply); parser must skip, not crash.
        val valid = TachibkCodec.encodeManga(TachibkManga(source = 1, url = "u"))
        val prefix = byteArrayOf(
            0x10, // tag = (2 << 3) | 0 = 0x10: url as varint
            5,
        )
        val backupBytes = prefix + valid
        val wrapped = byteArrayOf(0x0a) + varint(backupBytes.size) + backupBytes
        val decoded = TachibkCodec.decode(wrapped)
        assertEquals(1, decoded.backupManga.size)
        assertEquals("u", decoded.backupManga[0].url)
    }

    // ── Malformed input ─────────────────────────────────────────────────

    @Test
    fun `truncated varint throws clear error`() {
        // Field 1 varint with continuation bit set but no continuation bytes.
        val bytes = byteArrayOf(0x08, 0x80.toByte())
        assertMalformed(bytes)
    }

    @Test
    fun `oversized length prefix throws clear error`() {
        // Field 1 (backupManga), length-delimited, claiming a 2^28-byte
        // payload the buffer doesn't have.
        val bytes = byteArrayOf(0x0a, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x08)
        assertMalformed(bytes)
    }

    @Test
    fun `truncated top-level message throws clear error`() {
        val bytes = TachibkCodec.encode(TachibkBackup(backupManga = listOf(TachibkManga(source = 1, url = "u"))))
        assertMalformed(bytes.copyOf(bytes.size - 1))
    }

    @Test
    fun `truncated nested chapter throws clear error`() {
        val manga = TachibkCodec.encodeManga(
            TachibkManga(source = 1, url = "u", chapters = listOf(TachibkChapter(url = "c", name = "n", read = true))),
        )
        assertMalformed(manga.copyOf(manga.size - 1))
    }

    @Test
    fun `invalid utf8 string decodes lossily instead of crashing`() {
        // protobuf-java's readString is lenient on nested payloads: invalid
        // UTF-8 becomes the replacement char rather than throwing. That's the
        // safe direction for untrusted input (no crash, degraded field) —
        // structural corruption is still caught by the other malformed tests.
        val mangaBytes = byteArrayOf(0x12, 1, 0xff.toByte()) // url = one invalid byte
        val wrapped = byteArrayOf(0x0a) + varint(mangaBytes.size) + mangaBytes
        val decoded = TachibkCodec.decode(wrapped)
        assertEquals(1, decoded.backupManga.size)
        assertEquals("\ufffd", decoded.backupManga[0].url)
    }

    // ── Repeated categories: packed + unpacked interop ──────────────────

    @Test
    fun `categories round-trip packed including zero elements`() {
        val manga = TachibkManga(source = 1, url = "u", categories = listOf(0L, 2L))
        val decoded = TachibkCodec.decodeManga(CodedInputStream.newInstance(TachibkCodec.encodeManga(manga)))
        // proto3 repeated semantics: elements are always emitted, 0 included.
        assertEquals(listOf(0L, 2L), decoded.categories)
    }

    @Test
    fun `categories accept unpacked varint encoding`() {
        // Hand-crafted unpacked form (tag per element, the pre-proto3 style):
        // field 17 varint values 0 and 2. Field 17 tag = (17 << 3) | 0 = 136,
        // a two-byte varint (0x88 0x01).
        val tag17 = byteArrayOf(0x88.toByte(), 0x01)
        val mangaBytes = tag17 + byteArrayOf(0) + tag17 + byteArrayOf(2)
        val wrapped = byteArrayOf(0x0a) + varint(mangaBytes.size) + mangaBytes
        val decoded = TachibkCodec.decode(wrapped)
        assertEquals(listOf(0L, 2L), decoded.backupManga[0].categories)
    }

    @Test
    fun `categories packed payload is decoded element-wise`() {
        // Packed: field 17, length-delimited, payload = two varints (0, 2).
        val packed = byteArrayOf(0, 2)
        val mangaBytes = tagBytes(17, 2) + varint(packed.size) + packed
        val wrapped = byteArrayOf(0x0a) + varint(mangaBytes.size) + mangaBytes
        val decoded = TachibkCodec.decode(wrapped)
        assertEquals(listOf(0L, 2L), decoded.backupManga[0].categories)
    }

    /** Tag bytes for a field with the given wire type. */
    private fun tagBytes(field: Int, wireType: Int): ByteArray {
        val tag = (field shl 3) or wireType
        return varint(tag)
    }

    private fun assertMalformed(bytes: ByteArray) {
        try {
            TachibkCodec.decode(bytes)
            throw AssertionError("expected TachibkFormatException for ${bytes.size} bytes")
        } catch (e: TachibkFormatException) {
            assertTrue(e.message!!.contains("Malformed"))
        }
    }

    private fun varint(value: Int): ByteArray {
        val out = ArrayList<Byte>(5)
        var v = value
        while (true) {
            if (v and 0x7f.inv() == 0) {
                out.add(v.toByte())
                return out.toByteArray()
            }
            out.add(((v and 0x7f) or 0x80).toByte())
            v = v ushr 7
        }
    }
}
