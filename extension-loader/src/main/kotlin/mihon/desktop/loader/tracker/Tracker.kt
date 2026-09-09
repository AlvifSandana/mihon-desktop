package mihon.desktop.loader.tracker

/**
 * Normalized tracking status across all trackers. Persisted in the `tracker`
 * table as its ordinal; each tracker maps it to/from its own remote values
 * (see [AniListStatus] / [MalStatus]).
 */
enum class TrackStatus {
    READING,
    COMPLETED,
    ON_HOLD,
    DROPPED,
    PLAN_TO_READ,
    REPEATING,
    ;

    companion object {
        private val byOrdinal = entries.toList()

        /** Unknown ordinals (e.g. a row written by a future version) degrade to READING, never crash. */
        fun fromInt(value: Int): TrackStatus = byOrdinal.getOrNull(value) ?: TrackStatus.READING
    }
}

/**
 * A search hit on a remote tracker, normalized. `remoteId` is kept as String
 * (AniList media ids and MAL manga ids are numeric, but String is the lowest
 * common denominator and what the `tracker` table stores).
 */
data class RemoteManga(
    val remoteId: String,
    val title: String,
    val summary: String = "",
    val totalChapters: Long = 0,
    /** Remote-native average score, display-only. */
    val score: Double? = null,
    val coverUrl: String? = null,
    val trackingUrl: String = "",
    val publishingStatus: String = "",
)

/**
 * Fields to push to the remote tracker. `null` = leave unchanged.
 * Score is normalized 0-10 (each tracker converts to its own scale).
 */
data class TrackUpdate(
    val status: TrackStatus? = null,
    val score: Double? = null,
    val lastChapterRead: Double? = null,
)

/** Domain view of one `tracker` table row. */
data class TrackEntry(
    val id: Long = 0,
    val mangaId: Long = 0,
    /** Matches [Tracker.name] ("anilist" / "myanimelist"). */
    val trackerName: String,
    val remoteId: String,
    val title: String,
    val status: TrackStatus = TrackStatus.READING,
    /** Normalized 0-10; null = unscored. */
    val score: Double? = null,
    val lastChapterRead: Double? = null,
    val updatedAt: Long = 0,
)

/**
 * How the login dialog should label and parse the pasted credential:
 * AniList's implicit grant yields a raw access token, MAL's PKCE flow yields
 * an auth code (usually inside the full redirect URL).
 */
enum class CredentialStyle { ACCESS_TOKEN, AUTH_CODE }

/** One login attempt's authorize URL + UI hints, from [Tracker.prepareLogin]. */
data class LoginRequest(
    val url: String,
    val credentialStyle: CredentialStyle,
    val instructions: String,
)

/**
 * A remote tracking service (AniList, MyAnimeList, ...). Implementations are
 * stateless across restarts beyond the token stored in [TrackerAuthStore].
 *
 * All network methods are `suspend` and must be called off the UI thread.
 */
interface Tracker {
    val id: Long
    /** Stable identifier persisted in `tracker.trackerName`. */
    val name: String

    fun isLoggedIn(): Boolean

    /** Stored display name of the logged-in user, when known. */
    fun username(): String?

    /** Drops the stored token; bound entries stay in the DB untouched. */
    fun logout()

    /**
     * Builds the authorize URL for a fresh login. MAL generates (and keeps in
     * memory) a PKCE `code_verifier` bound to the returned URL's challenge --
     * call this once per login attempt, then pass the user's paste to [login].
     */
    fun prepareLogin(): LoginRequest

    /**
     * Completes login with the credential pasted by the user (token, auth
     * code, or full redirect URL -- implementations extract what they need).
     * Throws on invalid/expired credentials.
     */
    suspend fun login(credential: String)

    suspend fun search(query: String): List<RemoteManga>

    /**
     * Links a local manga to a remote one: creates the remote list entry if
     * the manga isn't on it yet, seeding it with [seed]. Returns the entry
     * with remote state applied.
     */
    suspend fun bind(track: TrackEntry, seed: TrackUpdate): TrackEntry

    /** Pushes [changes]; returns the entry with the changes merged in. */
    suspend fun update(track: TrackEntry, changes: TrackUpdate): TrackEntry

    /**
     * Fetches the remote state for a bound entry. Null when the manga is no
     * longer on the remote list.
     */
    suspend fun refresh(track: TrackEntry): TrackEntry?

    /** Removes the remote list entry. Best-effort; local row is deleted by the caller either way. */
    suspend fun unbind(track: TrackEntry)
}
