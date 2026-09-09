package mihon.desktop.loader.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * User-defined categories for library manga. A manga can belong to any number of
 * categories; manga in the library with no category show up under "Default" in
 * the library filter and under "All" regardless.
 *
 * Deleting a category only removes its [MangaCategory] mappings -- the manga
 * themselves stay in the library.
 */
class CategoryRepository(private val database: MihonDesktopDatabase = LibraryDatabase.get()) {
    private val categoryQueries = database.categoryQueries
    private val mangaCategoryQueries = database.mangaCategoryQueries
    private val libraryQueries = database.libraryMangaQueries

    /** All categories in user-defined order. */
    suspend fun all(): List<Category> = withContext(Dispatchers.IO) {
        categoryQueries.selectAll().executeAsList()
    }

    /** All categories in order, each with the number of manga mapped to it. */
    suspend fun allWithCounts(): List<SelectAllWithCounts> = withContext(Dispatchers.IO) {
        categoryQueries.selectAllWithCounts().executeAsList()
    }

    /**
     * Creates a category appended at the end of the sort order.
     *
     * @return the new category's id, or null if [name] is blank or a category
     *   with that name already exists (names are unique).
     */
    suspend fun create(name: String): Long? = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext null
        if (categoryQueries.selectByName(trimmed).executeAsOneOrNull() != null) return@withContext null
        val sortOrder = categoryQueries.maxSortOrder().executeAsOne() + 1
        // The existence check above can race a concurrent create; the
        // UNIQUE(name) constraint is the real guard, so a violation here just
        // means "duplicate" -- never a crash.
        runCatching {
            categoryQueries.insert(trimmed, sortOrder)
        }.getOrNull() ?: return@withContext null
        categoryQueries.selectByName(trimmed).executeAsOneOrNull()?.id
    }

    /**
     * Renames a category.
     *
     * @return false if [newName] is blank or another category already uses it.
     */
    suspend fun rename(id: Long, newName: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@withContext false
        val clash = categoryQueries.selectByName(trimmed).executeAsOneOrNull()
        if (clash != null && clash.id != id) return@withContext false
        // Same TOCTOU guard as create(): a UNIQUE(name) violation from a
        // concurrent rename/create means the name was taken in the meantime.
        runCatching { categoryQueries.rename(trimmed, id) }.isSuccess
    }

    /**
     * Deletes a category and its manga mappings. Library manga are untouched.
     */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        database.transaction {
            mangaCategoryQueries.deleteForCategory(id)
            categoryQueries.delete(id)
        }
    }

    /**
     * Persists a full ordering: [orderedIds] is the complete list of category
     * ids in the desired order; sortOrder is rewritten to match (0, 1, 2, ...).
     */
    suspend fun reorder(orderedIds: List<Long>) = withContext(Dispatchers.IO) {
        database.transaction {
            orderedIds.forEachIndexed { index, id ->
                categoryQueries.updateSortOrder(index.toLong(), id)
            }
        }
    }

    /** The categories a library manga belongs to, in category order. */
    suspend fun categoriesForManga(sourceId: Long, mangaUrl: String): List<Category> =
        withContext(Dispatchers.IO) {
            val manga = libraryQueries.selectOne(sourceId, mangaUrl).executeAsOneOrNull()
                ?: return@withContext emptyList()
            mangaCategoryQueries.selectCategoriesForManga(manga.id).executeAsList()
        }

    /**
     * Replaces the set of categories a library manga belongs with [categoryIds].
     * Unknown category ids (e.g. a category deleted concurrently) are skipped
     * rather than inserted as dangling rows -- foreign keys are only enforced
     * when the connection has the pragma on. No-op when the manga is not in
     * the library.
     */
    suspend fun setMangaCategories(sourceId: Long, mangaUrl: String, categoryIds: Collection<Long>) =
        withContext(Dispatchers.IO) {
            database.transaction {
                val manga = libraryQueries.selectOne(sourceId, mangaUrl).executeAsOneOrNull()
                    ?: return@transaction
                mangaCategoryQueries.deleteForManga(manga.id)
                for (categoryId in categoryIds.distinct()) {
                    if (categoryQueries.selectById(categoryId).executeAsOneOrNull() != null) {
                        mangaCategoryQueries.insertOrIgnore(manga.id, categoryId)
                    }
                }
            }
        }

    /** Library-manga row ids mapped to [categoryId]. */
    suspend fun mangaIdsForCategory(categoryId: Long): List<Long> = withContext(Dispatchers.IO) {
        mangaCategoryQueries.selectMangaIdsForCategory(categoryId).executeAsList()
    }

    /** Library-manga row ids that belong to at least one category (the complement is "Default"). */
    suspend fun mangaIdsWithAnyCategory(): Set<Long> = withContext(Dispatchers.IO) {
        mangaCategoryQueries.selectMangaIdsWithAnyCategory().executeAsList().toSet()
    }

    /**
     * All (categoryId -> library-manga row ids) mappings in one query, for
     * building per-category filter sets without N+1 round-trips.
     */
    suspend fun mangaIdsByCategory(): Map<Long, Set<Long>> = withContext(Dispatchers.IO) {
        mangaCategoryQueries.selectMangaIdsByCategory().executeAsList()
            .groupBy({ it.categoryId }, { it.mangaId })
            .mapValues { (_, ids) -> ids.toSet() }
    }
}
