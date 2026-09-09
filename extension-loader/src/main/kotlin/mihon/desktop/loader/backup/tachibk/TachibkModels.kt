package mihon.desktop.loader.backup.tachibk

/**
 * In-memory mirror of the subset of Mihon's `.tachibk` backup protobuf schema
 * that mihon-desktop reads and writes.
 *
 * Field numbers verified against the latest stable Mihon release (v0.20.4,
 * 2026-08-05), which encodes the schema with kotlinx.serialization
 * `@ProtoNumber` annotations in:
 *
 *   https://raw.githubusercontent.com/mihonapp/mihon/v0.20.4/app/src/main/java/eu/kanade/tachiyomi/data/backup/models/Backup.kt
 *   .../models/BackupManga.kt, BackupChapter.kt, BackupCategory.kt,
 *   BackupSource.kt, BackupHistory.kt
 *
 * Notable corrections vs. common assumptions about the older Tachiyomi
 * `.proto` era (all Mihon releases — v0.14.x through v0.20.x — agree on these):
 * - `BackupChapter` has NO `lastReadAt` field: `lastPageRead` IS field 6.
 * - `BackupManga.status` is field 8 (field 13 is `dateAdded`).
 * - `BackupCategory.flags` is field 100 (field 3 is `id`).
 * - `Backup` has no "extensions" field (101=sources, 104=preferences,
 *   105=sourcePreferences, 106=extensionStores).
 * - `BackupManga.categories` holds category **order** values (not indices);
 *   the restorer resolves them via `backupCategories.associateBy { it.order }`.
 *
 * Time fields (`dateAdded`, `dateFetch`, `BackupHistory.lastRead`) are epoch
 * milliseconds, matching Mihon's `Date`/`System.currentTimeMillis()` usage.
 *
 * Unknown fields in real backups (preferences, tracking, extension stores,
 * viewer flags, …) are skipped on decode and never emitted on encode.
 */
data class TachibkBackup(
    val backupManga: List<TachibkManga> = emptyList(),
    val backupCategories: List<TachibkCategory> = emptyList(),
    val backupSources: List<TachibkSource> = emptyList(),
)

data class TachibkManga(
    val source: Long,
    val url: String,
    val title: String = "",
    val artist: String? = null,
    val author: String? = null,
    val status: Int = 0,
    val thumbnailUrl: String? = null,
    /** Epoch ms the manga was added to the library (0 = absent). */
    val dateAdded: Long = 0,
    val chapters: List<TachibkChapter> = emptyList(),
    /** Category `order` values (resolved against [TachibkBackup.backupCategories]). */
    val categories: List<Long> = emptyList(),
    /** Per-chapter read timestamps, epoch ms. */
    val history: List<TachibkHistory> = emptyList(),
    /** Always true in library backups; emitted so old readers see the manga as favorited. */
    val favorite: Boolean = true,
)

data class TachibkChapter(
    val url: String,
    val name: String,
    val scanlator: String? = null,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    /** Page index the reader left off at. */
    val lastPageRead: Long = 0,
    /** Epoch ms the chapter list entry was last fetched (0 = absent). */
    val dateFetch: Long = 0,
    val chapterNumber: Float = 0f,
    val sourceOrder: Long = 0,
)

data class TachibkCategory(
    val name: String,
    val order: Long = 0,
    val flags: Long = 0,
)

data class TachibkSource(
    val name: String,
    val sourceId: Long,
)

data class TachibkHistory(
    val chapterUrl: String,
    /** Epoch ms the chapter was last read. */
    val lastRead: Long = 0,
    val readDuration: Long = 0,
)
