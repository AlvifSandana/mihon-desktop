package mihon.desktop.loader.backup.tachibk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.desktop.loader.library.LibraryDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Interop proof against kotlinx.serialization.protobuf — the exact library
 * Android Mihon uses to read/write `.tachibk` (its backup models are
 * @ProtoNumber-annotated data classes). If our hand-written codec's output
 * parses identically through kotlinx (and vice versa), the wire format is
 * Mihon-compatible: same tags, same packed encoding for repeated ints, same
 * default-omission behavior.
 *
 * The mirror classes below replicate the Mihon v0.20.4 backup models for the
 * fields in our subset (see TachibkModels for upstream links). Fields Mihon
 * declares without a default (backupManga, manga source/url, chapter
 * url/name, history url/lastRead) are mirrored without one too: kotlinx
 * omits default-equal values when encoding AND requires non-defaulted fields
 * to be present when decoding, so these mirrors fail the test exactly where
 * an absent field would fail Mihon's restore.
 */
class TachibkKotlinxInteropTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Serializable
    private data class KBackup(
        @ProtoNumber(1) val backupManga: List<KManga>,
        @ProtoNumber(2) val backupCategories: List<KCategory> = emptyList(),
        @ProtoNumber(101) val backupSources: List<KSource> = emptyList(),
    )

    @Serializable
    private data class KManga(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(3) val title: String = "",
        @ProtoNumber(4) val artist: String? = null,
        @ProtoNumber(5) val author: String? = null,
        @ProtoNumber(8) val status: Int = 0,
        @ProtoNumber(9) val thumbnailUrl: String? = null,
        @ProtoNumber(13) val dateAdded: Long = 0,
        @ProtoNumber(16) val chapters: List<KChapter> = emptyList(),
        @ProtoNumber(17) val categories: List<Long> = emptyList(),
        @ProtoNumber(100) val favorite: Boolean = true,
        @ProtoNumber(104) val history: List<KHistory> = emptyList(),
    )

    @Serializable
    private data class KChapter(
        @ProtoNumber(1) val url: String,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val scanlator: String? = null,
        @ProtoNumber(4) val read: Boolean = false,
        @ProtoNumber(5) val bookmark: Boolean = false,
        @ProtoNumber(6) val lastPageRead: Long = 0,
        @ProtoNumber(7) val dateFetch: Long = 0,
        @ProtoNumber(9) val chapterNumber: Float = 0f,
        @ProtoNumber(10) val sourceOrder: Long = 0,
    )

    @Serializable
    private data class KCategory(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val order: Long = 0,
        @ProtoNumber(100) val flags: Long = 0,
    )

    @Serializable
    private data class KSource(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val sourceId: Long = 0,
    )

    @Serializable
    private data class KHistory(
        @ProtoNumber(1) val url: String,
        @ProtoNumber(2) val lastRead: Long,
        @ProtoNumber(3) val readDuration: Long = 0,
    )

    private val protobuf = ProtoBuf

    private val backup = TachibkBackup(
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
                    TachibkChapter(url = "chapter/2", name = "Chapter 2"),
                ),
                categories = listOf(0L, 2L),
                history = listOf(
                    TachibkHistory(chapterUrl = "chapter/1", lastRead = 1700002000000L, readDuration = 300),
                ),
            ),
        ),
        backupCategories = listOf(
            TachibkCategory(name = "Reading", order = 0),
            TachibkCategory(name = "Plan to read", order = 2),
        ),
        backupSources = listOf(TachibkSource(name = "MangaDex", sourceId = 2499283573025)),
    )

    @Test
    fun `our encoding parses identically through kotlinx protobuf`() {
        val bytes = TachibkCodec.encode(backup)
        val parsed = protobuf.decodeFromByteArray(KBackup.serializer(), bytes)

        assertEquals(1, parsed.backupManga.size)
        val manga = parsed.backupManga[0]
        assertEquals(2499283573025L, manga.source)
        assertEquals("manga/solo-leveling", manga.url)
        assertEquals("Solo Leveling", manga.title)
        assertEquals("DUBU", manga.artist)
        assertEquals("Chugong", manga.author)
        assertEquals(2, manga.status)
        assertEquals("https://example.com/cover.jpg", manga.thumbnailUrl)
        assertEquals(1700000000000L, manga.dateAdded)
        assertEquals(true, manga.favorite)
        assertEquals(listOf(0L, 2L), manga.categories)

        assertEquals(2, manga.chapters.size)
        val ch = manga.chapters[0]
        assertEquals("chapter/1", ch.url)
        assertEquals("Chapter 1", ch.name)
        assertEquals("MangaDex", ch.scanlator)
        assertEquals(true, ch.read)
        assertEquals(true, ch.bookmark)
        assertEquals(12L, ch.lastPageRead)
        assertEquals(1700001000000L, ch.dateFetch)
        assertEquals(1.5f, ch.chapterNumber)
        assertEquals(3L, ch.sourceOrder)
        assertEquals(false, manga.chapters[1].read)

        assertEquals(1, manga.history.size)
        assertEquals("chapter/1", manga.history[0].url)
        assertEquals(1700002000000L, manga.history[0].lastRead)
        assertEquals(300L, manga.history[0].readDuration)

        assertEquals(2, parsed.backupCategories.size)
        assertEquals("Reading", parsed.backupCategories[0].name)
        assertEquals(0L, parsed.backupCategories[0].order)
        assertEquals("Plan to read", parsed.backupCategories[1].name)
        assertEquals(2L, parsed.backupCategories[1].order)

        assertEquals(1, parsed.backupSources.size)
        assertEquals("MangaDex", parsed.backupSources[0].name)
        assertEquals(2499283573025L, parsed.backupSources[0].sourceId)
    }

    @Test
    fun `kotlinx encoding parses identically through our codec`() {
        val kBackup = KBackup(
            backupManga = listOf(
                KManga(
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
                        KChapter(
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
                    ),
                    categories = listOf(0L, 2L),
                    history = listOf(KHistory(url = "chapter/1", lastRead = 1700002000000L, readDuration = 300)),
                ),
            ),
            backupCategories = listOf(
                KCategory(name = "Reading", order = 0),
                KCategory(name = "Plan to read", order = 2),
            ),
            backupSources = listOf(KSource(name = "MangaDex", sourceId = 2499283573025)),
        )
        val bytes = protobuf.encodeToByteArray(KBackup.serializer(), kBackup)
        val parsed = TachibkCodec.decode(bytes)

        val manga = parsed.backupManga.single()
        assertEquals(2499283573025L, manga.source)
        assertEquals("manga/solo-leveling", manga.url)
        assertEquals("Solo Leveling", manga.title)
        assertEquals("DUBU", manga.artist)
        assertEquals("Chugong", manga.author)
        assertEquals(2, manga.status)
        assertEquals("https://example.com/cover.jpg", manga.thumbnailUrl)
        assertEquals(1700000000000L, manga.dateAdded)
        assertEquals(true, manga.favorite)
        assertEquals(listOf(0L, 2L), manga.categories)

        val ch = manga.chapters.single()
        assertEquals("chapter/1", ch.url)
        assertEquals("Chapter 1", ch.name)
        assertEquals("MangaDex", ch.scanlator)
        assertEquals(true, ch.read)
        assertEquals(true, ch.bookmark)
        assertEquals(12L, ch.lastPageRead)
        assertEquals(1700001000000L, ch.dateFetch)
        assertEquals(1.5f, ch.chapterNumber)
        assertEquals(3L, ch.sourceOrder)

        val history = manga.history.single()
        assertEquals("chapter/1", history.chapterUrl)
        assertEquals(1700002000000L, history.lastRead)
        assertEquals(300L, history.readDuration)

        assertEquals(listOf("Reading", "Plan to read"), parsed.backupCategories.map { it.name })
        assertEquals(listOf(0L, 2L), parsed.backupCategories.map { it.order })
        assertEquals(2499283573025L, parsed.backupSources.single().sourceId)
    }

    @Test
    fun `unknown kotlinx fields (preferences) are skipped by our decoder`() {
        // Mihon's real Backup writes preferences (field 104) and source
        // preferences (105) — our decoder must skip them cleanly.
        @Serializable
        data class KPref(
            @ProtoNumber(1) val key: String = "",
            @ProtoNumber(2) val value: String = "",
        )

        @Serializable
        data class KBackupWithPrefs(
            @ProtoNumber(1) val backupManga: List<KManga> = emptyList(),
            @ProtoNumber(104) val backupPreferences: List<KPref> = emptyList(),
            @ProtoNumber(105) val backupSourcePreferences: List<KPref> = emptyList(),
        )
        val kBackup = KBackupWithPrefs(
            backupManga = listOf(KManga(source = 5, url = "u", title = "T")),
            backupPreferences = listOf(KPref(key = "pref", value = "1")),
            backupSourcePreferences = listOf(KPref(key = "spref", value = "2")),
        )
        val bytes = protobuf.encodeToByteArray(KBackupWithPrefs.serializer(), kBackup)
        val parsed = TachibkCodec.decode(bytes)
        assertEquals(1, parsed.backupManga.size)
        assertEquals("u", parsed.backupManga[0].url)
    }

    @Test
    fun `export with a readAt=0 read chapter still decodes through strict kotlinx models`() = runBlocking {
        // A legacy readChapters row with readAt = 0 must not produce a
        // TachibkHistory.lastRead of 0: proto3 default omission drops the
        // field, and Mihon's BackupHistory has no default for lastRead —
        // Android restore errors out per-manga. The manager must stamp the
        // export time instead, so the strict mirrors below decode cleanly.
        val db = LibraryDatabase.openDatabase(folder.newFile("library.db"))
        db.libraryMangaQueries.insertOrReplace(
            sourceId = 1000L,
            packageName = "pkg",
            jarFileName = "ext-v1.jar",
            extensionName = "Ext",
            mangaUrl = "manga/a",
            title = "Manga A",
            thumbnailUrl = null,
            author = null,
            addedAt = 123L,
        )
        db.readChapterQueries.insertOrReplace(
            sourceId = 1000L,
            mangaUrl = "manga/a",
            chapterUrl = "c/1",
            chapterName = "Ch 1",
            readAt = 0L,
        )
        val manager = TachibkManager(
            database = db,
            installedSourcesProvider = { emptyMap() },
            onBackupActivity = {},
        )

        val file = folder.newFile("export.tachibk")
        manager.export(file)

        // Strict KBackup (backupManga required, KHistory.lastRead required):
        // a missing field throws MissingFieldException — the assert is that
        // decoding does not throw at all.
        val parsed = protobuf.decodeFromByteArray(
            KBackup.serializer(),
            TachibkGzip.readBackupPayload(file),
        )
        val history = parsed.backupManga.single().history.single()
        assertEquals("c/1", history.url)
        assertTrue("lastRead must be stamped, not 0", history.lastRead > 0)
    }
}
