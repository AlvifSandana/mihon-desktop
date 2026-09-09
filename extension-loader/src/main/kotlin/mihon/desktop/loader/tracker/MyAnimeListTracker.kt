package mihon.desktop.loader.tracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mihon.desktop.loader.log.Logger
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

private const val TAG = "MyAnimeListTracker"

/**
 * MyAnimeList tracker (API v2, https://api.myanimelist.net/v2).
 *
 * ## Login (OAuth2 + PKCE)
 * MAL only offers authorization-code-with-PKCE. Desktop has no redirect
 * receiver, so the flow is: [prepareLogin] builds the authorize URL with a
 * fresh `code_challenge` (the matching verifier is kept in memory), the user
 * authorizes in the browser, MAL redirects to a placeholder page, and the
 * user pastes that redirect URL (or just the `code` param) into the login
 * dialog. [login] exchanges the code for access/refresh tokens.
 *
 * The default client id is Mihon's registered MAL OAuth client (public in
 * upstream source: MyAnimeListApi.CLIENT_ID); it can be overridden with a
 * user-set preference (see TrackerManager) for anyone who wants their own.
 *
 * ## Auth
 * Every API call sends `Authorization: Bearer <access_token>` plus
 * `X-MAL-CLIENT-ID`. A 401 triggers one refresh-token exchange and a single
 * retry before giving up.
 *
 * ## Scores
 * MAL scores are 0-10 point -- identical to the normalized DB scale.
 */
class MyAnimeListTracker(
    private val executor: HttpExecutor,
    private val auth: TrackerAuthStore = TrackerAuthStore.shared,
    /** Client id used for both the OAuth exchange and API calls. */
    private val clientIdProvider: () -> String = { DEFAULT_CLIENT_ID },
) : Tracker {
    override val id: Long = MAL_TRACKER_ID
    override val name: String = "myanimelist"

    /** Verifier of the login attempt in flight; bound to the challenge in the URL [prepareLogin] returned. */
    @Volatile
    private var pendingCodeVerifier: String? = null

    private val clientId: String get() = clientIdProvider()

    override fun isLoggedIn(): Boolean = auth.get(KEY_ACCESS_TOKEN) != null

    override fun username(): String? = auth.get(KEY_USERNAME)

    override fun logout() {
        auth.clearPrefix("mal.")
    }

    override fun prepareLogin(): LoginRequest {
        val verifier = Pkce.generateVerifier()
        pendingCodeVerifier = verifier
        return LoginRequest(
            url = authorizeUrl(Pkce.codeChallenge(verifier), clientId),
            credentialStyle = CredentialStyle.AUTH_CODE,
            instructions = "1. Open the MyAnimeList authorize page and log in.\n" +
                "2. Approve the permission request.\n" +
                "3. The browser redirects to a page that fails to load -- that is expected.\n" +
                "4. Copy the full address of that page (it contains \"code=...\") and paste it below.",
        )
    }

    override suspend fun login(credential: String) = withContext(Dispatchers.IO) {
        val code = extractAuthCode(credential)
        require(code.isNotEmpty()) { "Paste the redirect URL (containing \"code=...\") or the code itself" }
        val verifier = pendingCodeVerifier
        requireNotNull(verifier) { "No login attempt in progress -- restart the login dialog" }

        val tokens = exchangeTokens(code, verifier)
        // Consume the verifier only on success: a failed exchange (bad or
        // expired code) keeps it, so the user can re-authorize with the same
        // dialog URL and paste a fresh code without reopening the dialog.
        pendingCodeVerifier = null
        storeTokens(tokens)
        // Best-effort username fetch for the Settings display.
        runCatching {
            val me = Json.parseToJsonElement(
                api(Request.Builder().url("$API_URL/users/@me").get().build()).body,
            ).jsonObject
            auth.put(KEY_USERNAME, me["name"]?.jsonPrimitive?.content ?: "")
        }.onFailure { Logger.w(TAG, "MAL /users/@me failed: ${it.message}") }
        Unit
    }

    override suspend fun search(query: String): List<RemoteManga> = withContext(Dispatchers.IO) {
        // MAL rejects queries over 64 chars with a 400.
        val url = "$API_URL/manga".toHttpUrl().newBuilder().apply {
            addQueryParameter("q", query.take(64))
            addQueryParameter("nsfw", "true")
            addQueryParameter("limit", "20")
            addQueryParameter("fields", SEARCH_FIELDS)
        }.build()
        parseSearchResponse(api(Request.Builder().url(url).get().build()).body)
    }

    override suspend fun bind(track: TrackEntry, seed: TrackUpdate): TrackEntry =
        update(track, seed)

    override suspend fun update(track: TrackEntry, changes: TrackUpdate): TrackEntry {
        val merged = track.copy(
            status = changes.status ?: track.status,
            score = changes.score ?: track.score,
            lastChapterRead = changes.lastChapterRead ?: track.lastChapterRead,
        )
        return withContext(Dispatchers.IO) {
            val body = buildUpdateForm(merged.status, merged.score, merged.lastChapterRead)
            val request = Request.Builder()
                .url(mangaListStatusUrl(track.remoteId))
                .put(body)
                .build()
            val status = parseListStatus(api(request).body)
            applyRemote(merged, status)
        }
    }

    override suspend fun refresh(track: TrackEntry): TrackEntry? = withContext(Dispatchers.IO) {
        val url = "$API_URL/manga/${track.remoteId}".toHttpUrl().newBuilder().apply {
            addQueryParameter("fields", "num_chapters,my_list_status{status,score,num_chapters_read,is_rereading}")
        }.build()
        val response = Json.parseToJsonElement(api(Request.Builder().url(url).get().build()).body).jsonObject
        val listStatus = response["my_list_status"]?.jsonObject ?: return@withContext null
        applyRemote(track, parseListStatusObject(listStatus))
    }

    override suspend fun unbind(track: TrackEntry) = withContext(Dispatchers.IO) {
        api(
            Request.Builder().url(mangaListStatusUrl(track.remoteId)).delete().build(),
        )
        Unit
    }

    // ── internals ───────────────────────────────────────────────────────

    private fun applyRemote(track: TrackEntry, status: MalListStatus): TrackEntry = track.copy(
        status = MalStatus.fromRemote(status.status, status.isRereading),
        score = status.score.takeIf { it > 0 },
        lastChapterRead = status.numChaptersRead.toDouble(),
    )

    /**
     * Executes a request with auth; on 401 refreshes the access token once
     * and retries. Throws [TrackerException] on other non-2xx responses.
     */
    private suspend fun api(request: Request, isRetry: Boolean = false): HttpResult {
        val token = auth.get(KEY_ACCESS_TOKEN)
            ?: error("Not logged in to MyAnimeList")
        val authed = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .header("X-MAL-CLIENT-ID", clientId)
            .build()
        val result = executor.execute(authed)
        if (result.code == 401 && !isRetry) {
            refreshTokens()
            return api(request, isRetry = true)
        }
        if (!result.isSuccessful) {
            throw TrackerException("MyAnimeList request failed: HTTP ${result.code}: ${result.body.take(200)}")
        }
        return result
    }

    private fun extractAuthCode(paste: String): String {
        val trimmed = paste.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.contains("code=")) return trimmed // raw code paste
        // Full redirect URL: parse it properly (query values may be encoded).
        trimmed.toHttpUrlOrNull()?.queryParameter("code")?.let { if (it.isNotEmpty()) return it }
        // Fragment-style or partial paste: extract textually.
        return trimmed.substringAfter("code=").substringBefore('&').substringBefore('#').trim()
    }

    private fun exchangeTokens(code: String, verifier: String): MalOAuth {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("grant_type", "authorization_code")
            .build()
        val request = Request.Builder().url("$OAUTH_URL/token").post(form).build()
        val result = executor.execute(request)
        check(result.isSuccessful) {
            "MyAnimeList token exchange failed: HTTP ${result.code}: ${result.body.take(200)}"
        }
        return parseOAuthResponse(result.body)
    }

    /** Serializes 401-triggered refreshes; see [refreshTokens]. */
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Refresh-token grant; also used by [api] on 401.
     *
     * Concurrent 401s (e.g. reader + detail screen pushing at once) would
     * each exchange the same refresh token; MAL rotates it, so the loser can
     * clobber the newer token or fail spuriously. The mutex serializes the
     * exchanges, and the double-check skips the exchange entirely when the
     * token was already rotated while this caller waited on the lock.
     */
    private suspend fun refreshTokens() {
        val refreshToken = auth.get(KEY_REFRESH_TOKEN)
            ?: throw TrackerException("MyAnimeList session expired and no refresh token is stored -- log in again")
        refreshMutex.lock()
        try {
            if (auth.get(KEY_REFRESH_TOKEN) != refreshToken) return // already refreshed
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("refresh_token", refreshToken)
                .add("grant_type", "refresh_token")
                .build()
            val request = Request.Builder().url("$OAUTH_URL/token").post(form).build()
            val result = executor.execute(request)
            check(result.isSuccessful) {
                "MyAnimeList token refresh failed: HTTP ${result.code}: ${result.body.take(200)}"
            }
            storeTokens(parseOAuthResponse(result.body))
        } finally {
            refreshMutex.unlock()
        }
    }

    private fun storeTokens(tokens: MalOAuth) {
        auth.put(KEY_ACCESS_TOKEN, tokens.accessToken)
        tokens.refreshToken?.let { auth.put(KEY_REFRESH_TOKEN, it) }
        auth.put(KEY_EXPIRES_AT, (System.currentTimeMillis() + tokens.expiresIn * 1000).toString())
    }

    companion object {
        const val MAL_TRACKER_ID = 2L

        /** Mihon's registered MAL OAuth client id (public in upstream source). */
        const val DEFAULT_CLIENT_ID = "c46c9e24640a64dad5be5ca7a1a53a0f"

        const val OAUTH_URL = "https://myanimelist.net/v1/oauth2"
        const val API_URL = "https://api.myanimelist.net/v2"

        const val SEARCH_FIELDS =
            "id,title,synopsis,num_chapters,mean,main_picture,status,media_type"

        const val KEY_ACCESS_TOKEN = "mal.accessToken"
        const val KEY_REFRESH_TOKEN = "mal.refreshToken"
        const val KEY_EXPIRES_AT = "mal.expiresAt"
        const val KEY_USERNAME = "mal.username"

        /**
         * PKCE S256: the challenge method MUST be declared -- RFC 7636
         * defaults to "plain", and MAL would then expect the raw verifier
         * as the challenge, failing the token exchange.
         */
        fun authorizeUrl(codeChallenge: String, clientId: String = DEFAULT_CLIENT_ID): String =
            "$OAUTH_URL/authorize?response_type=code&client_id=$clientId" +
                "&code_challenge=$codeChallenge&code_challenge_method=S256"

        fun mangaListStatusUrl(remoteId: String): String {
            // remoteId comes from the local DB; a corrupted or foreign value
            // must not steer the URL off the intended path segment.
            require(remoteId.toLongOrNull() != null) { "Invalid MAL manga id" }
            return "$API_URL/manga/$remoteId/my_list_status"
        }

        // ── request builders / response parsers (pure; unit-tested) ─────

        data class MalOAuth(
            val accessToken: String,
            val refreshToken: String?,
            val expiresIn: Long,
        )

        data class MalListStatus(
            val status: String,
            val score: Double,
            val numChaptersRead: Int,
            val isRereading: Boolean,
        )

        fun buildUpdateForm(status: TrackStatus, score: Double?, lastChapterRead: Double?): FormBody =
            FormBody.Builder().apply {
                add("status", MalStatus.toRemote(status))
                add("is_rereading", (status == TrackStatus.REPEATING).toString())
                add("score", (score ?: 0.0).toInt().toString())
                add("num_chapters_read", (lastChapterRead ?: 0.0).toInt().toString())
            }.build()

        fun parseOAuthResponse(body: String): MalOAuth {
            val json = Json.parseToJsonElement(body).jsonObject
            return MalOAuth(
                accessToken = json["access_token"]!!.jsonPrimitive.content,
                refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull,
                expiresIn = json["expires_in"]?.jsonPrimitive?.longOrNull ?: 2678400L,
            )
        }

        fun parseSearchResponse(body: String): List<RemoteManga> {
            val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
            return data.mapNotNull { el ->
                val node = el.jsonObject["node"]?.jsonObject ?: return@mapNotNull null
                // MAL returns unapproved/novel entries in search that cannot
                // be tracked; drop novels like upstream Mihon does.
                val mediaType = node["media_type"].str() ?: ""
                if (mediaType.contains("novel")) return@mapNotNull null
                val id = node["id"]!!.jsonPrimitive.longOrNull ?: return@mapNotNull null
                RemoteManga(
                    remoteId = id.toString(),
                    title = node["title"]!!.jsonPrimitive.content,
                    summary = node["synopsis"].str() ?: "",
                    totalChapters = node["num_chapters"]?.jsonPrimitive?.longOrNull ?: 0,
                    score = node["mean"]?.jsonPrimitive?.doubleOrNull,
                    publishingStatus = node["status"].str()?.replace('_', ' ') ?: "",
                    coverUrl = node["main_picture"]?.jsonObject?.get("large").str(),
                    trackingUrl = "https://myanimelist.net/manga/$id",
                )
            }
        }

        fun parseListStatus(body: String): MalListStatus =
            parseListStatusObject(Json.parseToJsonElement(body).jsonObject)

        private fun parseListStatusObject(json: kotlinx.serialization.json.JsonObject): MalListStatus =
            MalListStatus(
                status = json["status"].str() ?: "reading",
                score = json["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                numChaptersRead = json["num_chapters_read"]?.jsonPrimitive?.intOrNull ?: 0,
                isRereading = json["is_rereading"].str()?.toBooleanStrict() ?: false,
            )

        /** JsonNull-safe string read. */
        private fun kotlinx.serialization.json.JsonElement?.str(): String? =
            (this as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.contentOrNull
    }
}

/** MAL list status strings <-> [TrackStatus]. MAL has no REPEATING status -- it is a separate `is_rereading` flag. */
object MalStatus {
    fun toRemote(status: TrackStatus): String = when (status) {
        TrackStatus.READING -> "reading"
        TrackStatus.ON_HOLD -> "on_hold"
        TrackStatus.COMPLETED -> "completed"
        TrackStatus.DROPPED -> "dropped"
        TrackStatus.PLAN_TO_READ -> "plan_to_read"
        // MAL expresses rereading as reading + is_rereading=true.
        TrackStatus.REPEATING -> "reading"
    }

    fun fromRemote(status: String, isRereading: Boolean): TrackStatus = when {
        isRereading -> TrackStatus.REPEATING
        status == "reading" -> TrackStatus.READING
        status == "on_hold" -> TrackStatus.ON_HOLD
        status == "completed" -> TrackStatus.COMPLETED
        status == "dropped" -> TrackStatus.DROPPED
        status == "plan_to_read" -> TrackStatus.PLAN_TO_READ
        else -> TrackStatus.READING
    }
}
