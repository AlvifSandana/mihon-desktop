package mihon.desktop.loader.backup.tachibk

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.WireFormat

/**
 * Hand-written protobuf codec for the `.tachibk` backup subset (see
 * [TachibkModels] for the verified field numbers). No protoc/codegen — only
 * [CodedInputStream]/[CodedOutputStream] primitives.
 *
 * Encoding rules match Mihon's kotlinx.serialization.protobuf behavior:
 * fields equal to their default value (0, "", false, null, empty lists) are
 * omitted; unknown fields are never emitted. Decoding is lenient: fields we
 * don't know about (preferences, tracking, extension stores, …) are skipped
 * with standard wire-format semantics, and known fields arriving on an
 * unexpected wire type are treated as unknown rather than fatal.
 *
 * Malformed input (truncated varints, negative/oversized length prefixes,
 * invalid UTF-8) surfaces as [TachibkFormatException] with a clear message —
 * never an unchecked exception or OOM. Message payloads are length-prefixed
 * and bounded by their parent buffer, so the depth is naturally capped at the
 * schema's flat nesting (Backup → Manga → Chapter).
 */
object TachibkCodec {

    // ── Wire constants (verified against Mihon v0.20.4, see TachibkModels) ──

    private const val FIELD_BACKUP_MANGA = 1
    private const val FIELD_BACKUP_CATEGORIES = 2
    private const val FIELD_BACKUP_SOURCES = 101

    private const val FIELD_MANGA_SOURCE = 1
    private const val FIELD_MANGA_URL = 2
    private const val FIELD_MANGA_TITLE = 3
    private const val FIELD_MANGA_ARTIST = 4
    private const val FIELD_MANGA_AUTHOR = 5
    private const val FIELD_MANGA_STATUS = 8
    private const val FIELD_MANGA_THUMBNAIL_URL = 9
    private const val FIELD_MANGA_DATE_ADDED = 13
    private const val FIELD_MANGA_CHAPTERS = 16
    private const val FIELD_MANGA_CATEGORIES = 17
    private const val FIELD_MANGA_FAVORITE = 100
    private const val FIELD_MANGA_HISTORY = 104

    private const val FIELD_CHAPTER_URL = 1
    private const val FIELD_CHAPTER_NAME = 2
    private const val FIELD_CHAPTER_SCANLATOR = 3
    private const val FIELD_CHAPTER_READ = 4
    private const val FIELD_CHAPTER_BOOKMARK = 5
    private const val FIELD_CHAPTER_LAST_PAGE_READ = 6
    private const val FIELD_CHAPTER_DATE_FETCH = 7
    private const val FIELD_CHAPTER_CHAPTER_NUMBER = 9
    private const val FIELD_CHAPTER_SOURCE_ORDER = 10

    private const val FIELD_CATEGORY_NAME = 1
    private const val FIELD_CATEGORY_ORDER = 2
    private const val FIELD_CATEGORY_FLAGS = 100

    private const val FIELD_SOURCE_NAME = 1
    private const val FIELD_SOURCE_ID = 2

    private const val FIELD_HISTORY_URL = 1
    private const val FIELD_HISTORY_LAST_READ = 2
    private const val FIELD_HISTORY_READ_DURATION = 3

    /** WireFormat.FIXED32_SIZE is package-private in protobuf-java; the value is 4. */
    private const val FIXED32_SIZE = 4

    // ── Encode ──────────────────────────────────────────────────────────

    fun encode(backup: TachibkBackup): ByteArray {
        val mangaBytes = backup.backupManga.map { encodeManga(it) }
        val categoryBytes = backup.backupCategories.map { encodeCategory(it) }
        val sourceBytes = backup.backupSources.map { encodeSource(it) }

        var size = 0
        for (b in mangaBytes) size += messageFieldSize(FIELD_BACKUP_MANGA, b)
        for (b in categoryBytes) size += messageFieldSize(FIELD_BACKUP_CATEGORIES, b)
        for (b in sourceBytes) size += messageFieldSize(FIELD_BACKUP_SOURCES, b)

        val out = buf(size)
        for (b in mangaBytes) out.writeMessageField(FIELD_BACKUP_MANGA, b)
        for (b in categoryBytes) out.writeMessageField(FIELD_BACKUP_CATEGORIES, b)
        for (b in sourceBytes) out.writeMessageField(FIELD_BACKUP_SOURCES, b)
        return out.buffer()
    }

    internal fun encodeManga(m: TachibkManga): ByteArray {
        val chapterBytes = m.chapters.map { encodeChapter(it) }
        val historyBytes = m.history.map { encodeHistory(it) }
        val categoriesPacked = packedVarintSize(m.categories)

        var size = 0
        size += varintFieldSize(FIELD_MANGA_SOURCE, m.source)
        size += stringFieldSize(FIELD_MANGA_URL, m.url)
        size += stringFieldSize(FIELD_MANGA_TITLE, m.title)
        size += stringFieldSize(FIELD_MANGA_ARTIST, m.artist)
        size += stringFieldSize(FIELD_MANGA_AUTHOR, m.author)
        size += intFieldSize(FIELD_MANGA_STATUS, m.status)
        size += stringFieldSize(FIELD_MANGA_THUMBNAIL_URL, m.thumbnailUrl)
        size += varintFieldSize(FIELD_MANGA_DATE_ADDED, m.dateAdded)
        for (b in chapterBytes) size += messageFieldSize(FIELD_MANGA_CHAPTERS, b)
        if (m.categories.isNotEmpty()) {
            size += tagSize(FIELD_MANGA_CATEGORIES) +
                CodedOutputStream.computeUInt32SizeNoTag(categoriesPacked) + categoriesPacked
        }
        for (b in historyBytes) size += messageFieldSize(FIELD_MANGA_HISTORY, b)
        size += boolFieldSize(FIELD_MANGA_FAVORITE, m.favorite)

        val out = buf(size)
        out.writeVarintField(FIELD_MANGA_SOURCE, m.source)
        out.writeStringField(FIELD_MANGA_URL, m.url)
        out.writeStringField(FIELD_MANGA_TITLE, m.title)
        out.writeStringField(FIELD_MANGA_ARTIST, m.artist)
        out.writeStringField(FIELD_MANGA_AUTHOR, m.author)
        out.writeIntField(FIELD_MANGA_STATUS, m.status)
        out.writeStringField(FIELD_MANGA_THUMBNAIL_URL, m.thumbnailUrl)
        out.writeVarintField(FIELD_MANGA_DATE_ADDED, m.dateAdded)
        for (b in chapterBytes) out.writeMessageField(FIELD_MANGA_CHAPTERS, b)
        if (m.categories.isNotEmpty()) {
            out.writePackedVarintField(FIELD_MANGA_CATEGORIES, m.categories)
        }
        for (b in historyBytes) out.writeMessageField(FIELD_MANGA_HISTORY, b)
        out.writeBoolField(FIELD_MANGA_FAVORITE, m.favorite)
        return out.buffer()
    }

    internal fun encodeChapter(c: TachibkChapter): ByteArray {
        var size = 0
        size += stringFieldSize(FIELD_CHAPTER_URL, c.url)
        size += stringFieldSize(FIELD_CHAPTER_NAME, c.name)
        size += stringFieldSize(FIELD_CHAPTER_SCANLATOR, c.scanlator)
        size += boolFieldSize(FIELD_CHAPTER_READ, c.read)
        size += boolFieldSize(FIELD_CHAPTER_BOOKMARK, c.bookmark)
        size += varintFieldSize(FIELD_CHAPTER_LAST_PAGE_READ, c.lastPageRead)
        size += varintFieldSize(FIELD_CHAPTER_DATE_FETCH, c.dateFetch)
        size += floatFieldSize(FIELD_CHAPTER_CHAPTER_NUMBER, c.chapterNumber)
        size += varintFieldSize(FIELD_CHAPTER_SOURCE_ORDER, c.sourceOrder)

        val out = buf(size)
        out.writeStringField(FIELD_CHAPTER_URL, c.url)
        out.writeStringField(FIELD_CHAPTER_NAME, c.name)
        out.writeStringField(FIELD_CHAPTER_SCANLATOR, c.scanlator)
        out.writeBoolField(FIELD_CHAPTER_READ, c.read)
        out.writeBoolField(FIELD_CHAPTER_BOOKMARK, c.bookmark)
        out.writeVarintField(FIELD_CHAPTER_LAST_PAGE_READ, c.lastPageRead)
        out.writeVarintField(FIELD_CHAPTER_DATE_FETCH, c.dateFetch)
        out.writeFloatField(FIELD_CHAPTER_CHAPTER_NUMBER, c.chapterNumber)
        out.writeVarintField(FIELD_CHAPTER_SOURCE_ORDER, c.sourceOrder)
        return out.buffer()
    }

    internal fun encodeCategory(c: TachibkCategory): ByteArray {
        var size = 0
        size += stringFieldSize(FIELD_CATEGORY_NAME, c.name)
        size += varintFieldSize(FIELD_CATEGORY_ORDER, c.order)
        size += varintFieldSize(FIELD_CATEGORY_FLAGS, c.flags)

        val out = buf(size)
        out.writeStringField(FIELD_CATEGORY_NAME, c.name)
        out.writeVarintField(FIELD_CATEGORY_ORDER, c.order)
        out.writeVarintField(FIELD_CATEGORY_FLAGS, c.flags)
        return out.buffer()
    }

    internal fun encodeSource(s: TachibkSource): ByteArray {
        var size = 0
        size += stringFieldSize(FIELD_SOURCE_NAME, s.name)
        size += varintFieldSize(FIELD_SOURCE_ID, s.sourceId)

        val out = buf(size)
        out.writeStringField(FIELD_SOURCE_NAME, s.name)
        out.writeVarintField(FIELD_SOURCE_ID, s.sourceId)
        return out.buffer()
    }

    internal fun encodeHistory(h: TachibkHistory): ByteArray {
        var size = 0
        size += stringFieldSize(FIELD_HISTORY_URL, h.chapterUrl)
        size += varintFieldSize(FIELD_HISTORY_LAST_READ, h.lastRead)
        size += varintFieldSize(FIELD_HISTORY_READ_DURATION, h.readDuration)

        val out = buf(size)
        out.writeStringField(FIELD_HISTORY_URL, h.chapterUrl)
        out.writeVarintField(FIELD_HISTORY_LAST_READ, h.lastRead)
        out.writeVarintField(FIELD_HISTORY_READ_DURATION, h.readDuration)
        return out.buffer()
    }

    // ── Decode ──────────────────────────────────────────────────────────

    fun decode(bytes: ByteArray): TachibkBackup = try {
        decodeBackup(CodedInputStream.newInstance(bytes, 0, bytes.size))
    } catch (e: InvalidProtocolBufferException) {
        throw TachibkFormatException("Malformed .tachibk protobuf: ${e.message}", e)
    } catch (e: TachibkFormatException) {
        throw e
    }

    internal fun decodeBackup(input: CodedInputStream): TachibkBackup {
        val manga = ArrayList<TachibkManga>()
        val categories = ArrayList<TachibkCategory>()
        val sources = ArrayList<TachibkSource>()
        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            when (WireFormat.getTagFieldNumber(tag)) {
                FIELD_BACKUP_MANGA ->
                    if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                        manga += decodeManga(newInput(input.readBytes().toByteArray()))
                    } else {
                        input.skipField(tag)
                    }
                FIELD_BACKUP_CATEGORIES ->
                    if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                        categories += decodeCategory(newInput(input.readBytes().toByteArray()))
                    } else {
                        input.skipField(tag)
                    }
                FIELD_BACKUP_SOURCES ->
                    if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                        sources += decodeSource(newInput(input.readBytes().toByteArray()))
                    } else {
                        input.skipField(tag)
                    }
                else -> input.skipField(tag)
            }
        }
        return TachibkBackup(manga, categories, sources)
    }

    internal fun decodeManga(input: CodedInputStream): TachibkManga {
        var source = 0L
        var url = ""
        var title = ""
        var artist: String? = null
        var author: String? = null
        var status = 0
        var thumbnailUrl: String? = null
        var dateAdded = 0L
        val chapters = ArrayList<TachibkChapter>()
        val categories = ArrayList<Long>()
        val history = ArrayList<TachibkHistory>()
        var favorite = true
        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            val wireType = WireFormat.getTagWireType(tag)
            when (WireFormat.getTagFieldNumber(tag)) {
                FIELD_MANGA_SOURCE -> ifVarint(input, wireType, tag) { source = it.readInt64() }
                FIELD_MANGA_URL -> ifLengthDelimited(input, wireType, tag) { url = it.readString() }
                FIELD_MANGA_TITLE -> ifLengthDelimited(input, wireType, tag) { title = it.readString() }
                FIELD_MANGA_ARTIST -> ifLengthDelimited(input, wireType, tag) { artist = it.readString() }
                FIELD_MANGA_AUTHOR -> ifLengthDelimited(input, wireType, tag) { author = it.readString() }
                FIELD_MANGA_STATUS -> ifVarint(input, wireType, tag) { status = it.readInt32() }
                FIELD_MANGA_THUMBNAIL_URL -> ifLengthDelimited(input, wireType, tag) { thumbnailUrl = it.readString() }
                FIELD_MANGA_DATE_ADDED -> ifVarint(input, wireType, tag) { dateAdded = it.readInt64() }
                FIELD_MANGA_CHAPTERS ->
                    if (wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                        chapters += decodeChapter(newInput(input.readBytes().toByteArray()))
                    } else {
                        input.skipField(tag)
                    }
                FIELD_MANGA_CATEGORIES ->
                    when (wireType) {
                        // Packed (kotlinx/Mihon canonical form): one
                        // length-delimited blob of concatenated varints.
                        WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                            val packed = newInput(input.readBytes().toByteArray())
                            while (!packed.isAtEnd) categories += packed.readInt64()
                        }
                        // Unpacked (pre-proto3 / other writers): one element.
                        WireFormat.WIRETYPE_VARINT -> categories += input.readInt64()
                        else -> input.skipField(tag)
                    }
                FIELD_MANGA_FAVORITE -> ifVarint(input, wireType, tag) { favorite = it.readBool() }
                FIELD_MANGA_HISTORY ->
                    if (wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                        history += decodeHistory(newInput(input.readBytes().toByteArray()))
                    } else {
                        input.skipField(tag)
                    }
                else -> input.skipField(tag)
            }
        }
        return TachibkManga(
            source = source,
            url = url,
            title = title,
            artist = artist,
            author = author,
            status = status,
            thumbnailUrl = thumbnailUrl,
            dateAdded = dateAdded,
            chapters = chapters,
            categories = categories,
            history = history,
            favorite = favorite,
        )
    }

    internal fun decodeChapter(input: CodedInputStream): TachibkChapter {
        var url = ""
        var name = ""
        var scanlator: String? = null
        var read = false
        var bookmark = false
        var lastPageRead = 0L
        var dateFetch = 0L
        var chapterNumber = 0f
        var sourceOrder = 0L
        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            val wireType = WireFormat.getTagWireType(tag)
            when (WireFormat.getTagFieldNumber(tag)) {
                FIELD_CHAPTER_URL -> ifLengthDelimited(input, wireType, tag) { url = it.readString() }
                FIELD_CHAPTER_NAME -> ifLengthDelimited(input, wireType, tag) { name = it.readString() }
                FIELD_CHAPTER_SCANLATOR -> ifLengthDelimited(input, wireType, tag) { scanlator = it.readString() }
                FIELD_CHAPTER_READ -> ifVarint(input, wireType, tag) { read = it.readBool() }
                FIELD_CHAPTER_BOOKMARK -> ifVarint(input, wireType, tag) { bookmark = it.readBool() }
                FIELD_CHAPTER_LAST_PAGE_READ -> ifVarint(input, wireType, tag) { lastPageRead = it.readInt64() }
                FIELD_CHAPTER_DATE_FETCH -> ifVarint(input, wireType, tag) { dateFetch = it.readInt64() }
                FIELD_CHAPTER_CHAPTER_NUMBER -> ifFixed32(input, wireType, tag) { chapterNumber = it.readFloat() }
                FIELD_CHAPTER_SOURCE_ORDER -> ifVarint(input, wireType, tag) { sourceOrder = it.readInt64() }
                else -> input.skipField(tag)
            }
        }
        return TachibkChapter(
            url = url,
            name = name,
            scanlator = scanlator,
            read = read,
            bookmark = bookmark,
            lastPageRead = lastPageRead,
            dateFetch = dateFetch,
            chapterNumber = chapterNumber,
            sourceOrder = sourceOrder,
        )
    }

    internal fun decodeCategory(input: CodedInputStream): TachibkCategory {
        var name = ""
        var order = 0L
        var flags = 0L
        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            val wireType = WireFormat.getTagWireType(tag)
            when (WireFormat.getTagFieldNumber(tag)) {
                FIELD_CATEGORY_NAME -> ifLengthDelimited(input, wireType, tag) { name = it.readString() }
                FIELD_CATEGORY_ORDER -> ifVarint(input, wireType, tag) { order = it.readInt64() }
                FIELD_CATEGORY_FLAGS -> ifVarint(input, wireType, tag) { flags = it.readInt64() }
                else -> input.skipField(tag)
            }
        }
        return TachibkCategory(name = name, order = order, flags = flags)
    }

    internal fun decodeSource(input: CodedInputStream): TachibkSource {
        var name = ""
        var sourceId = 0L
        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            val wireType = WireFormat.getTagWireType(tag)
            when (WireFormat.getTagFieldNumber(tag)) {
                FIELD_SOURCE_NAME -> ifLengthDelimited(input, wireType, tag) { name = it.readString() }
                FIELD_SOURCE_ID -> ifVarint(input, wireType, tag) { sourceId = it.readInt64() }
                else -> input.skipField(tag)
            }
        }
        return TachibkSource(name = name, sourceId = sourceId)
    }

    internal fun decodeHistory(input: CodedInputStream): TachibkHistory {
        var url = ""
        var lastRead = 0L
        var readDuration = 0L
        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            val wireType = WireFormat.getTagWireType(tag)
            when (WireFormat.getTagFieldNumber(tag)) {
                FIELD_HISTORY_URL -> ifLengthDelimited(input, wireType, tag) { url = it.readString() }
                FIELD_HISTORY_LAST_READ -> ifVarint(input, wireType, tag) { lastRead = it.readInt64() }
                FIELD_HISTORY_READ_DURATION -> ifVarint(input, wireType, tag) { readDuration = it.readInt64() }
                else -> input.skipField(tag)
            }
        }
        return TachibkHistory(chapterUrl = url, lastRead = lastRead, readDuration = readDuration)
    }

    /** Runs [block] only for the expected varint wire type, otherwise skips the field. */
    private inline fun ifVarint(input: CodedInputStream, wireType: Int, tag: Int, block: (CodedInputStream) -> Unit) {
        if (wireType == WireFormat.WIRETYPE_VARINT) block(input) else input.skipField(tag)
    }

    private inline fun ifLengthDelimited(input: CodedInputStream, wireType: Int, tag: Int, block: (CodedInputStream) -> Unit) {
        if (wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) block(input) else input.skipField(tag)
    }

    private inline fun ifFixed32(input: CodedInputStream, wireType: Int, tag: Int, block: (CodedInputStream) -> Unit) {
        if (wireType == WireFormat.WIRETYPE_FIXED32) block(input) else input.skipField(tag)
    }

    private fun newInput(bytes: ByteArray): CodedInputStream = CodedInputStream.newInstance(bytes, 0, bytes.size)

    // ── Helpers: sizes ──────────────────────────────────────────────────

    private fun tagSize(field: Int) = CodedOutputStream.computeTagSize(field)

    private fun varintFieldSize(field: Int, value: Long) =
        if (value == 0L) 0 else tagSize(field) + CodedOutputStream.computeInt64SizeNoTag(value)

    private fun intFieldSize(field: Int, value: Int) =
        if (value == 0) 0 else tagSize(field) + CodedOutputStream.computeInt32SizeNoTag(value)

    private fun stringFieldSize(field: Int, value: String?) =
        if (value.isNullOrEmpty()) 0 else
            tagSize(field) + CodedOutputStream.computeStringSizeNoTag(value)

    private fun boolFieldSize(field: Int, value: Boolean) =
        if (!value) 0 else tagSize(field) + 1

    private fun floatFieldSize(field: Int, value: Float) =
        if (value == 0f) 0 else tagSize(field) + FIXED32_SIZE

    private fun messageFieldSize(field: Int, bytes: ByteArray) =
        tagSize(field) + CodedOutputStream.computeUInt32SizeNoTag(bytes.size) + bytes.size

    /** Payload size of [values] as a packed varint block (every element emitted, 0 included). */
    private fun packedVarintSize(values: List<Long>): Int =
        values.sumOf { CodedOutputStream.computeInt64SizeNoTag(it) }

    // ── Helpers: writer ─────────────────────────────────────────────────

    private class Buf(private val bytes: ByteArray) {
        private val stream = CodedOutputStream.newInstance(bytes)

        fun writeVarintField(field: Int, value: Long) {
            if (value == 0L) return // proto3: defaults are omitted (see varintFieldSize)
            stream.writeTag(field, WireFormat.WIRETYPE_VARINT)
            stream.writeInt64NoTag(value)
        }

        fun writeIntField(field: Int, value: Int) {
            if (value == 0) return // proto3: defaults are omitted (see intFieldSize)
            stream.writeTag(field, WireFormat.WIRETYPE_VARINT)
            stream.writeInt32NoTag(value)
        }

        fun writeStringField(field: Int, value: String?) {
            if (value.isNullOrEmpty()) return
            stream.writeTag(field, WireFormat.WIRETYPE_LENGTH_DELIMITED)
            stream.writeStringNoTag(value)
        }

        fun writeBoolField(field: Int, value: Boolean) {
            if (!value) return
            stream.writeTag(field, WireFormat.WIRETYPE_VARINT)
            stream.writeBoolNoTag(value)
        }

        fun writeFloatField(field: Int, value: Float) {
            if (value == 0f) return
            stream.writeTag(field, WireFormat.WIRETYPE_FIXED32)
            stream.writeFloatNoTag(value)
        }

        fun writeMessageField(field: Int, bytes: ByteArray) {
            stream.writeTag(field, WireFormat.WIRETYPE_LENGTH_DELIMITED)
            stream.writeUInt32NoTag(bytes.size)
            stream.writeRawBytes(bytes)
        }

        fun writePackedVarintField(field: Int, values: List<Long>) {
            if (values.isEmpty()) return
            var packedSize = 0
            for (v in values) packedSize += CodedOutputStream.computeInt64SizeNoTag(v)
            stream.writeTag(field, WireFormat.WIRETYPE_LENGTH_DELIMITED)
            stream.writeUInt32NoTag(packedSize)
            for (v in values) stream.writeInt64NoTag(v)
        }

        fun buffer(): ByteArray {
            check(stream.spaceLeft() == 0) { "Size pre-computation mismatch: ${stream.spaceLeft()} bytes left" }
            return bytes
        }
    }

    private fun buf(size: Int) = Buf(ByteArray(size))
}

/** Raised for any structurally invalid `.tachibk` payload (bad protobuf, bad gzip, JSON, oversize). */
class TachibkFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
