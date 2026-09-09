package mihon.desktop.loader.tracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AniList tracker (https://graphql.anilist.co).
 *
 * ## Login
 * AniList's implicit OAuth grant: the user opens
 * `https://anilist.co/api/v2/oauth/authorize?client_id=<ID>&response_type=token`,
 * authorizes, and the browser lands on a redirect page whose URL fragment
 * contains the access token. Desktop has no redirect receiver, so the user
 * copies the token and pastes it into the login dialog; [login] verifies it
 * with a `Viewer` query before storing.
 *
 * The client id is Mihon's registered AniList OAuth client (public in
 * upstream source: AnilistApi.CLIENT_ID).
 *
 * ## Scores
 * The DB stores normalized 0-10 scores; AniList's `scoreRaw` is POINT_100,
 * so pushes multiply by 10 and pulls divide by 10.
 */
class AniListTracker(
    private val executor: HttpExecutor,
    private val auth: TrackerAuthStore = TrackerAuthStore.shared,
) : Tracker {
    override val id: Long = ANILIST_TRACKER_ID
    override val name: String = "anilist"

    override fun isLoggedIn(): Boolean = auth.get(KEY_TOKEN) != null

    override fun username(): String? = auth.get(KEY_USERNAME)

    override fun logout() {
        auth.clearPrefix("anilist.")
    }

    override fun prepareLogin(): LoginRequest = LoginRequest(
        url = authorizeUrl(),
        credentialStyle = CredentialStyle.ACCESS_TOKEN,
        instructions = "1. Open the AniList authorize page and log in.\n" +
            "2. Approve the permission request.\n" +
            "3. The browser redirects to a URL ending in \"#access_token=...\".\n" +
            "4. Copy the access token value and paste it below.",
    )

    override suspend fun login(credential: String) = withContext(Dispatchers.IO) {
        val token = credential.trim().substringAfter("access_token=").substringBefore('&').trim()
        require(token.isNotEmpty()) { "Paste the access token from the redirect URL (after \"access_token=\")" }
        val (userId, username) = parseViewerResponse(
            post(buildPayload(VIEWER_QUERY, buildJsonObject { }), token).second,
        )
        auth.put(KEY_TOKEN, token)
        auth.put(KEY_USER_ID, userId.toString())
        auth.put(KEY_USERNAME, username)
        Unit
    }

    override suspend fun search(query: String): List<RemoteManga> = withContext(Dispatchers.IO) {
        // Search works unauthenticated on AniList, but the tracker is only
        // reachable through the logged-in UI -- send the token like every
        // other call so behavior is uniform.
        val payload = buildPayload(SEARCH_QUERY, buildJsonObject { put("q", query) })
        parseSearchResponse(post(payload, requireToken()).second)
    }

    override suspend fun bind(track: TrackEntry, seed: TrackUpdate): TrackEntry {
        val merged = merge(track, seed)
        val saved = saveRemote(merged)
        return saved
    }

    override suspend fun update(track: TrackEntry, changes: TrackUpdate): TrackEntry =
        saveRemote(merge(track, changes))

    override suspend fun refresh(track: TrackEntry): TrackEntry? = withContext(Dispatchers.IO) {
        val token = requireToken()
        val userId = auth.get(KEY_USER_ID)?.toIntOrNull()
            ?: error("AniList login is missing the user id -- log in again")
        val payload = buildPayload(
            MEDIA_LIST_QUERY,
            buildJsonObject {
                put("userId", userId)
                put("mediaId", track.remoteId.toInt())
            },
        )
        val entry = parseMediaListResponse(post(payload, token).second) ?: return@withContext null
        track.copy(
            status = AniListStatus.fromRemote(entry.status),
            score = entry.scoreRaw.takeIf { it > 0 }?.let { it / 10.0 },
            lastChapterRead = entry.progress.toDouble(),
        )
    }

    override suspend fun unbind(track: TrackEntry) = withContext(Dispatchers.IO) {
        val token = requireToken()
        // DeleteMediaListEntry needs the remote list-entry id, which isn't
        // persisted -- resolve it first, tolerating "not on the list".
        val userId = auth.get(KEY_USER_ID)?.toIntOrNull()
        if (userId != null) {
            val payload = buildPayload(
                MEDIA_LIST_QUERY,
                buildJsonObject {
                    put("userId", userId)
                    put("mediaId", track.remoteId.toInt())
                },
            )
            val entry = parseMediaListResponse(post(payload, token).second)
            if (entry != null) {
                post(
                    buildPayload(
                        DELETE_MUTATION,
                        buildJsonObject { put("id", entry.listId) },
                    ),
                    token,
                )
            }
        }
    }

    // ── internals ───────────────────────────────────────────────────────

    private fun merge(track: TrackEntry, changes: TrackUpdate): TrackEntry = track.copy(
        status = changes.status ?: track.status,
        score = changes.score ?: track.score,
        lastChapterRead = changes.lastChapterRead ?: track.lastChapterRead,
    )

    /** SaveMediaListEntry upserts by mediaId, creating the list entry on bind. */
    private suspend fun saveRemote(track: TrackEntry): TrackEntry = withContext(Dispatchers.IO) {
        val token = requireToken()
        val payload = buildPayload(
            SAVE_MUTATION,
            buildJsonObject {
                put("mediaId", track.remoteId.toInt())
                put("status", AniListStatus.toRemote(track.status))
                put("progress", (track.lastChapterRead ?: 0.0).toInt())
                put("scoreRaw", ((track.score ?: 0.0) * 10).toInt())
            },
        )
        val entry = parseSaveResponse(post(payload, token).second)
        track.copy(
            status = AniListStatus.fromRemote(entry.status),
            score = entry.scoreRaw.takeIf { it > 0 }?.let { it / 10.0 },
            lastChapterRead = entry.progress.toDouble(),
        )
    }

    private fun requireToken(): String =
        auth.get(KEY_TOKEN) ?: error("Not logged in to AniList")

    /** POSTs a GraphQL payload; returns (http result, body). Throws on transport error or GraphQL "errors". */
    private suspend fun post(payload: String, token: String? = null): Pair<HttpResult, String> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(API_URL)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            val result = executor.execute(request)
            check(result.isSuccessful) { "AniList request failed: HTTP ${result.code}: ${result.body.take(200)}" }
            // GraphQL answers 200 with an "errors" array on failure -- surface it.
            val json = Json.parseToJsonElement(result.body).jsonObject
            json["errors"]?.let { errors ->
                val message = errors.jsonArray.firstOrNull()
                    ?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "unknown GraphQL error"
                throw TrackerException("AniList: $message")
            }
            result to result.body
        }

    companion object {
        const val ANILIST_TRACKER_ID = 1L

        /** Mihon's registered AniList OAuth client (public in upstream source). */
        const val CLIENT_ID = "16329"
        const val API_URL = "https://graphql.anilist.co/"

        const val KEY_TOKEN = "anilist.token"
        const val KEY_USER_ID = "anilist.userId"
        const val KEY_USERNAME = "anilist.username"

        fun authorizeUrl(): String =
            "https://anilist.co/api/v2/oauth/authorize?client_id=$CLIENT_ID&response_type=token"

        fun buildPayload(query: String, variables: JsonObject): String = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString()

        // ── GraphQL documents ───────────────────────────────────────────
        // Kotlin raw strings still interpolate '$', and GraphQL variable
        // references need a literal '$' -- hence the ${'$'} dance below.

        val VIEWER_QUERY = """
            query { Viewer { id name } }
        """.trimIndent()

        val SEARCH_QUERY = """
            query (${D}q: String) {
                Page(perPage: 20) {
                    media(search: ${D}q, type: MANGA, format_not_in: [NOVEL]) {
                        id
                        title { userPreferred }
                        description(asHtml: false)
                        chapters
                        averageScore
                        status
                        coverImage { large }
                    }
                }
            }
        """.trimIndent()

        val MEDIA_LIST_QUERY = """
            query (${D}userId: Int!, ${D}mediaId: Int!) {
                MediaList(userId: ${D}userId, mediaId: ${D}mediaId) {
                    id
                    status
                    scoreRaw: score(format: POINT_100)
                    progress
                }
            }
        """.trimIndent()

        val SAVE_MUTATION = """
            mutation (
                ${D}mediaId: Int, ${D}status: MediaListStatus, ${D}progress: Int, ${D}scoreRaw: Int
            ) {
                SaveMediaListEntry(
                    mediaId: ${D}mediaId, status: ${D}status, progress: ${D}progress, scoreRaw: ${D}scoreRaw
                ) { id status scoreRaw: score(format: POINT_100) progress }
            }
        """.trimIndent()

        val DELETE_MUTATION = """
            mutation (${D}id: Int) { DeleteMediaListEntry(id: ${D}id) { deleted } }
        """.trimIndent()

        /** Literal '$' for GraphQL variable references inside raw strings. */
        private const val D = "$"

        // ── response parsers (pure; unit-tested against sample JSON) ────

        data class MediaListEntryData(
            val listId: Long,
            val status: String,
            val scoreRaw: Int,
            val progress: Int,
        )

        /** JsonNull-safe string read ("null" never leaks into titles/urls). */
        private fun kotlinx.serialization.json.JsonElement?.str(): String? =
            (this as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

        /** JsonNull-safe object read. */
        private fun kotlinx.serialization.json.JsonElement?.obj(): kotlinx.serialization.json.JsonObject? =
            this as? kotlinx.serialization.json.JsonObject

        fun parseViewerResponse(body: String): Pair<Int, String> {
            val viewer = Json.parseToJsonElement(body).jsonObject["data"]!!
                .obj()!!["Viewer"].obj()!!
            return viewer["id"]!!.jsonPrimitive.int to (viewer["name"].str() ?: "")
        }

        fun parseSearchResponse(body: String): List<RemoteManga> {
            val media = Json.parseToJsonElement(body).jsonObject["data"]!!
                .obj()!!["Page"].obj()!!["media"]!!.jsonArray
            return media.map { el ->
                val m = el.jsonObject
                val id = m["id"]!!.jsonPrimitive.int
                RemoteManga(
                    remoteId = id.toString(),
                    title = m["title"].obj()?.get("userPreferred").str() ?: "",
                    summary = m["description"].str() ?: "",
                    totalChapters = m["chapters"]?.jsonPrimitive?.longOrNull ?: 0,
                    score = m["averageScore"]?.jsonPrimitive?.intOrNull?.toDouble(),
                    publishingStatus = m["status"].str() ?: "",
                    coverUrl = m["coverImage"].obj()?.get("large").str(),
                    trackingUrl = "https://anilist.co/manga/$id",
                )
            }
        }

        fun parseMediaListResponse(body: String): MediaListEntryData? {
            val list = Json.parseToJsonElement(body).jsonObject["data"]!!
                .obj()!!["MediaList"].obj() ?: return null
            return MediaListEntryData(
                listId = list["id"]!!.jsonPrimitive.long,
                status = list["status"].str() ?: "CURRENT",
                scoreRaw = list["scoreRaw"]?.jsonPrimitive?.intOrNull ?: 0,
                progress = list["progress"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }

        fun parseSaveResponse(body: String): MediaListEntryData {
            val entry = Json.parseToJsonElement(body).jsonObject["data"]!!
                .obj()!!["SaveMediaListEntry"].obj()!!
            return MediaListEntryData(
                listId = entry["id"]!!.jsonPrimitive.long,
                status = entry["status"].str() ?: "CURRENT",
                scoreRaw = entry["scoreRaw"]?.jsonPrimitive?.intOrNull ?: 0,
                progress = entry["progress"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
    }
}

/** AniList MediaListStatus <-> [TrackStatus] (both directions). */
object AniListStatus {
    fun toRemote(status: TrackStatus): String = when (status) {
        TrackStatus.READING -> "CURRENT"
        TrackStatus.PLAN_TO_READ -> "PLANNING"
        TrackStatus.COMPLETED -> "COMPLETED"
        TrackStatus.DROPPED -> "DROPPED"
        TrackStatus.ON_HOLD -> "PAUSED"
        TrackStatus.REPEATING -> "REPEATING"
    }

    fun fromRemote(status: String): TrackStatus = when (status) {
        "CURRENT" -> TrackStatus.READING
        "PLANNING" -> TrackStatus.PLAN_TO_READ
        "COMPLETED" -> TrackStatus.COMPLETED
        "DROPPED" -> TrackStatus.DROPPED
        "PAUSED" -> TrackStatus.ON_HOLD
        "REPEATING" -> TrackStatus.REPEATING
        else -> TrackStatus.READING
    }
}
