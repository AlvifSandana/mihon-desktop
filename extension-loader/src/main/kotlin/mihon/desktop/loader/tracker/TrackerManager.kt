package mihon.desktop.loader.tracker

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.loader.log.Logger
import mihon.desktop.loader.prefs.AppPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val TAG = "TrackerManager"

/**
 * Registry + sync engine for the enabled trackers.
 *
 * `shared()` builds the production wiring: trackers use the NetworkHelper
 * OkHttpClient (via [OkHttpExecutor]) and the shared token store. Requires
 * [mihon.desktop.loader.DesktopExtensionRuntime.bootstrap] to have run (it
 * registers NetworkHelper in Injekt) -- which the app does at startup, so
 * only call this from UI code, never from extension-loader tests.
 */
class TrackerManager(
    private val repository: TrackerRepository = TrackerRepository(),
    val trackers: List<Tracker> = defaultTrackers(),
    /** Seam for tests; production reads the shared incognito preference. */
    private val isIncognito: () -> Boolean = {
        AppPreferences.getBoolean(AppPreferences.KEY_INCOGNITO_MODE, false)
    },
) {
    fun byName(name: String): Tracker? = trackers.firstOrNull { it.name == name }

    fun loggedIn(): List<Tracker> = trackers.filter { it.isLoggedIn() }

    suspend fun isLoggedIn(name: String): Boolean = byName(name)?.isLoggedIn() == true

    // ── sync hooks ──────────────────────────────────────────────────────

    /**
     * Pushes reading progress to every tracker this manga is bound to. Called
     * from the reader's chapter-finished path and MangaDetail's mark-as-read.
     *
     * Fire-and-forget by design: failures are logged, never surfaced, and
     * never block the read state write that already happened. Progress is
     * monotonic -- a chapter number lower than what the tracker row last
     * recorded is not pushed (no regressing the remote on re-reads).
     * No-ops in incognito mode.
     */
    suspend fun pushChapterRead(sourceId: Long, mangaUrl: String, chapterNumber: Double) {
        if (chapterNumber < 0) return
        if (isIncognito()) return
        withContext(Dispatchers.IO) {
            val mangaId = repository.mangaIdFor(sourceId, mangaUrl) ?: return@withContext
            val entries = repository.forMangaId(mangaId)
            for (entry in entries) {
                val tracker = byName(entry.trackerName) ?: continue
                if (!tracker.isLoggedIn()) continue
                val current = entry.lastChapterRead ?: 0.0
                if (chapterNumber <= current) continue
                runCatching {
                    val updated = tracker.update(entry, TrackUpdate(lastChapterRead = chapterNumber))
                    repository.upsert(updated)
                }.onFailure {
                    Logger.w(TAG, "Push to ${entry.trackerName} failed for manga $mangaId: ${it.message}")
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: TrackerManager? = null

        fun shared(): TrackerManager {
            instance?.let { return it }
            synchronized(this) {
                instance ?: TrackerManager().also { instance = it }
            }
            return instance!!
        }

        /** Production wiring: both trackers on the NetworkHelper client. */
        fun defaultTrackers(): List<Tracker> {
            val client = Injekt.get<NetworkHelper>().client
            return listOf(
                AniListTracker(executor = OkHttpExecutor(client)),
                MyAnimeListTracker(
                    executor = OkHttpExecutor(client),
                    clientIdProvider = {
                        AppPreferences.getString(AppPreferences.KEY_TRACKER_MAL_CLIENT_ID, "")
                            .ifBlank { MyAnimeListTracker.DEFAULT_CLIENT_ID }
                    },
                ),
            )
        }
    }
}
