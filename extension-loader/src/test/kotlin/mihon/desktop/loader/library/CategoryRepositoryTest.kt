package mihon.desktop.loader.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises [CategoryRepository] against a throwaway database: CRUD, reorder,
 * manga<->category mapping set/replace, delete-with-members, and counts.
 */
class CategoryRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: MihonDesktopDatabase
    private lateinit var repo: CategoryRepository
    private lateinit var library: LibraryRepository

    @Before
    fun setUp() {
        db = LibraryDatabase.openDatabase(folder.newFile("library.db"))
        repo = CategoryRepository(db)
        library = LibraryRepository(db)
    }

    /** Adds a library manga and returns its row id. */
    private fun addManga(url: String, sourceId: Long = 1L): Long {
        runBlocking {
            library.add(
                sourceId = sourceId,
                packageName = "pkg",
                jarFileName = "pkg-v1.jar",
                extensionName = "Ext",
                mangaUrl = url,
                title = "Manga $url",
                thumbnailUrl = null,
                author = null,
            )
        }
        return db.libraryMangaQueries.selectOne(sourceId, url).executeAsOne().id
    }

    @Test
    fun `create assigns ids and sortOrder in creation order`() = runBlocking {
        val a = repo.create("Reading")
        val b = repo.create("Plan to read")
        val c = repo.create("Dropped")

        assertNotNull(a); assertNotNull(b); assertNotNull(c)

        val names = repo.all().map { it.name }
        assertEquals(listOf("Reading", "Plan to read", "Dropped"), names)
        assertEquals(listOf(1L, 2L, 3L), repo.all().map { it.sortOrder })
    }

    @Test
    fun `create rejects blank and duplicate names`() = runBlocking {
        assertNotNull(repo.create("Reading"))
        assertNull(repo.create("  ")) // blank after trim
        assertNull(repo.create("Reading")) // exact duplicate
        assertNull(repo.create(" Reading ")) // duplicate after trim
        // The failed creates must not have consumed sort orders or rows
        assertEquals(1, repo.all().size)
    }

    @Test
    fun `rename updates the name and keeps order`() = runBlocking {
        val id = repo.create("Reading")!!

        assertTrue(repo.rename(id, "Currently reading"))
        assertEquals("Currently reading", repo.all().single().name)

        // Blank or duplicate (other id) names are rejected
        assertFalse(repo.rename(id, "  "))
        repo.create("Other")
        assertFalse(repo.rename(id, "Other"))
        // Renaming to your own name is a no-op, not a conflict
        assertTrue(repo.rename(id, "Currently reading"))
    }

    @Test
    fun `reorder rewrites sortOrder to match the given order`() = runBlocking {
        val a = repo.create("A")!!
        val b = repo.create("B")!!
        val c = repo.create("C")!!

        repo.reorder(listOf(c, a, b))

        assertEquals(listOf("C", "A", "B"), repo.all().map { it.name })
        assertEquals(listOf(0L, 1L, 2L), repo.all().map { it.sortOrder })
    }

    @Test
    fun `setMangaCategories replaces the previous set`() = runBlocking {
        addManga("m1")
        val cat1 = repo.create("Reading")!!
        val cat2 = repo.create("Dropped")!!

        repo.setMangaCategories(1L, "m1", listOf(cat1))
        assertEquals(listOf("Reading"), repo.categoriesForManga(1L, "m1").map { it.name })

        // Replace: cat1 out, cat2 in (not a merge)
        repo.setMangaCategories(1L, "m1", listOf(cat2))
        assertEquals(listOf("Dropped"), repo.categoriesForManga(1L, "m1").map { it.name })

        // Replace with empty clears the mapping
        repo.setMangaCategories(1L, "m1", emptyList())
        assertTrue(repo.categoriesForManga(1L, "m1").isEmpty())

        // Unknown ids are ignored, duplicates collapse
        repo.setMangaCategories(1L, "m1", listOf(cat1, cat1, 999L))
        assertEquals(listOf("Reading"), repo.categoriesForManga(1L, "m1").map { it.name })
    }

    @Test
    fun `one manga can be in multiple categories`() = runBlocking {
        addManga("m1")
        val cat1 = repo.create("Reading")!!
        val cat2 = repo.create("Ongoing")!!

        repo.setMangaCategories(1L, "m1", listOf(cat1, cat2))

        val names = repo.categoriesForManga(1L, "m1").map { it.name }
        assertEquals(listOf("Reading", "Ongoing"), names) // category order, not insert order
    }

    @Test
    fun `mangaIdsForCategory and mangaIdsWithAnyCategory`() = runBlocking {
        val m1 = addManga("m1")
        val m2 = addManga("m2")
        val m3 = addManga("m3") // uncategorized
        val cat1 = repo.create("Reading")!!
        val cat2 = repo.create("Dropped")!!

        repo.setMangaCategories(1L, "m1", listOf(cat1, cat2))
        repo.setMangaCategories(1L, "m2", listOf(cat1))

        // No ORDER BY on these queries -- compare as sorted sets
        assertEquals(listOf(m1, m2), repo.mangaIdsForCategory(cat1).sorted())
        assertEquals(listOf(m1), repo.mangaIdsForCategory(cat2))
        // "Default" complement = every library id minus categorized ones
        val allIds = db.libraryMangaQueries.selectAll().executeAsList().map { it.id }.toSet()
        val default = allIds - repo.mangaIdsWithAnyCategory()
        assertEquals(setOf(m3), default)
    }

    @Test
    fun `setMangaCategories with only bogus ids inserts no mappings`() = runBlocking {
        val m1 = addManga("m1")

        // FK enforcement is off (pragma), so this must be guarded in the repo
        repo.setMangaCategories(1L, "m1", listOf(999L, 1000L))

        // Assert actual mapping-table emptiness (mangaIdsByCategory reads the
        // raw mangaCategory table, no JOIN that could hide dangling rows), not
        // just absence from join-based results.
        assertTrue(m1 !in repo.mangaIdsWithAnyCategory())
        assertTrue(repo.mangaIdsByCategory().isEmpty())
        assertTrue(repo.mangaIdsForCategory(999L).isEmpty())
        assertEquals(0L, db.categoryQueries.selectAllWithCounts().executeAsList().sumOf { it.mangaCount })
    }

    @Test
    fun `setMangaCategories is a no-op for manga not in the library`() = runBlocking {
        val cat = repo.create("Reading")!!
        repo.setMangaCategories(1L, "unknown", listOf(cat))
        assertTrue(repo.mangaIdsForCategory(cat).isEmpty())
        assertTrue(repo.categoriesForManga(1L, "unknown").isEmpty())
    }

    @Test
    fun `delete removes category and mappings but keeps manga`() = runBlocking {
        val m1 = addManga("m1")
        val cat1 = repo.create("Reading")!!
        val cat2 = repo.create("Keep")!!
        repo.setMangaCategories(1L, "m1", listOf(cat1, cat2))

        repo.delete(cat1)

        // Category gone, its mappings gone...
        assertEquals(listOf("Keep"), repo.all().map { it.name })
        assertEquals(listOf("Keep"), repo.categoriesForManga(1L, "m1").map { it.name })
        assertTrue(repo.mangaIdsForCategory(cat1).isEmpty())
        // ...but the manga is still in the library
        assertEquals(1, db.libraryMangaQueries.selectAll().executeAsList().size)
        assertEquals(m1, db.libraryMangaQueries.selectOne(1L, "m1").executeAsOne().id)
    }

    @Test
    fun `allWithCounts counts mapped manga per category`() = runBlocking {
        addManga("m1")
        addManga("m2")
        addManga("m3")
        val reading = repo.create("Reading")!!
        val empty = repo.create("Empty")!!

        repo.setMangaCategories(1L, "m1", listOf(reading))
        repo.setMangaCategories(1L, "m2", listOf(reading))

        val counts = repo.allWithCounts().associate { it.name to it.mangaCount }
        assertEquals(2L, counts["Reading"])
        assertEquals(0L, counts["Empty"])
    }

    @Test
    fun `removing manga from library purges its mappings`() = runBlocking {
        addManga("m1")
        val cat = repo.create("Reading")!!
        repo.setMangaCategories(1L, "m1", listOf(cat))
        assertEquals(1, repo.mangaIdsForCategory(cat).size)

        runBlocking { library.remove(1L, "m1") }

        assertTrue(repo.mangaIdsForCategory(cat).isEmpty())
        assertTrue(repo.mangaIdsWithAnyCategory().isEmpty())
    }

    @Test
    fun `re-adding a manga keeps its id and category mappings`() = runBlocking {
        addManga("m1")
        val cat = repo.create("Reading")!!
        repo.setMangaCategories(1L, "m1", listOf(cat))

        // Re-add (e.g. metadata refresh while in library): must not replace the row
        runBlocking {
            library.add(
                sourceId = 1L,
                packageName = "pkg",
                jarFileName = "pkg-v2.jar", // refreshed jar
                extensionName = "Ext",
                mangaUrl = "m1",
                title = "Manga m1",
                thumbnailUrl = null,
                author = null,
            )
        }

        // Same row (id + addedAt preserved), updated jar file, mappings intact
        val row = db.libraryMangaQueries.selectOne(1L, "m1").executeAsOne()
        val original = db.libraryMangaQueries.selectAll().executeAsList().single()
        assertEquals(row.id, original.id)
        assertEquals("pkg-v2.jar", row.jarFileName)
        assertEquals(listOf("Reading"), repo.categoriesForManga(1L, "m1").map { it.name })
    }
}
