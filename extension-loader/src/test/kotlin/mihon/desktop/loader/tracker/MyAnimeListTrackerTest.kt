package mihon.desktop.loader.tracker

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MyAnimeListTrackerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var auth: TrackerAuthStore
    private lateinit var executor: FakeExecutor
    private lateinit var tracker: MyAnimeListTracker

    @Before
    fun setUp() {
        auth = TrackerAuthStore(File(folder.root, "auth.properties"))
        executor = FakeExecutor()
        tracker = MyAnimeListTracker(executor, auth)
    }

    private fun login() {
        auth.put(MyAnimeListTracker.KEY_ACCESS_TOKEN, "mal-token-1")
        auth.put(MyAnimeListTracker.KEY_REFRESH_TOKEN, "mal-refresh-1")
    }

    // ── parsers (sample JSON, no network) ───────────────────────────────

    @Test
    fun `parseSearchResponse maps nodes and drops novels`() {
        val hits = MyAnimeListTracker.parseSearchResponse(
            """
            {"data":[
                {"node":{"id":2,"title":"Berserk","synopsis":"Guts, a man...",
                 "num_chapters":374,"mean":9.44,"status":"publishing","media_type":"manga",
                 "main_picture":{"large":"https://cdn.myanimelist.net/large.jpg","medium":"m.jpg"}}},
                {"node":{"id":123,"title":"Some Light Novel","num_chapters":10,"media_type":"light_novel"}},
                {"node":{"id":1,"title":"Monster","num_chapters":162,"mean":9.1,"status":"finished",
                 "media_type":"manga"}}
            ]}
            """.trimIndent(),
        )
        // The light novel is filtered like upstream Mihon.
        assertEquals(listOf("Berserk", "Monster"), hits.map { it.title })
        val berserk = hits[0]
        assertEquals("2", berserk.remoteId)
        assertEquals("Guts, a man...", berserk.summary)
        assertEquals(374L, berserk.totalChapters)
        assertEquals(9.44, berserk.score!!, 0.001)
        assertEquals("publishing", berserk.publishingStatus)
        assertEquals("https://cdn.myanimelist.net/large.jpg", berserk.coverUrl)
        assertEquals("https://myanimelist.net/manga/2", berserk.trackingUrl)
    }

    @Test
    fun `parseOAuthResponse reads tokens`() {
        val oauth = MyAnimeListTracker.parseOAuthResponse(
            """{"token_type":"Bearer","expires_in":2678400,
                "access_token":"abc","refresh_token":"def"}""",
        )
        assertEquals("abc", oauth.accessToken)
        assertEquals("def", oauth.refreshToken)
        assertEquals(2678400L, oauth.expiresIn)
    }

    @Test
    fun `parseListStatus reads the update response shape`() {
        val status = MyAnimeListTracker.parseListStatus(
            """{"status":"on_hold","score":7,"num_chapters_read":42,"is_rereading":false,
                "updated_at":"2026-01-01T00:00:00+00:00"}""",
        )
        assertEquals("on_hold", status.status)
        assertEquals(7.0, status.score, 0.0)
        assertEquals(42, status.numChaptersRead)
        assertFalse(status.isRereading)
    }

    @Test
    fun `buildUpdateForm encodes status score and chapters`() {
        val form = MyAnimeListTracker.buildUpdateForm(
            status = TrackStatus.COMPLETED,
            score = 9.0,
            lastChapterRead = 374.0,
        )
        val body = form.bodyText()
        assertTrue(body.contains("status=completed"))
        assertTrue(body.contains("score=9"))
        assertTrue(body.contains("num_chapters_read=374"))
        assertTrue(body.contains("is_rereading=false"))
    }

    @Test
    fun `buildUpdateForm marks rereading for REPEATING`() {
        val body = MyAnimeListTracker.buildUpdateForm(TrackStatus.REPEATING, 8.0, 5.0).bodyText()
        assertTrue(body.contains("status=reading"))
        assertTrue(body.contains("is_rereading=true"))
    }

    // ── HTTP layer via the executor seam ────────────────────────────────

    @Test
    fun `search hits the manga endpoint with query and fields`() = runBlocking {
        login()
        executor.enqueue(200, """{"data":[]}""")
        tracker.search("berserk")

        val request = executor.requests.single()
        assertEquals(
            "https://api.myanimelist.net/v2/manga?q=berserk&nsfw=true&limit=20&" +
                "fields=id%2Ctitle%2Csynopsis%2Cnum_chapters%2Cmean%2Cmain_picture%2Cstatus%2Cmedia_type",
            request.url.toString(),
        )
        assertEquals("Bearer mal-token-1", request.header("Authorization"))
        assertEquals(MyAnimeListTracker.DEFAULT_CLIENT_ID, request.header("X-MAL-CLIENT-ID"))
    }

    @Test
    fun `update PUTs the list status form and merges the response`() = runBlocking {
        login()
        executor.enqueue(
            200,
            """{"status":"completed","score":9,"num_chapters_read":374,"is_rereading":false}""",
        )
        val result = tracker.update(
            TrackEntry(mangaId = 1, trackerName = "myanimelist", remoteId = "2", title = "Berserk"),
            TrackUpdate(status = TrackStatus.COMPLETED, score = 9.0, lastChapterRead = 374.0),
        )

        assertEquals(TrackStatus.COMPLETED, result.status)
        assertEquals(9.0, result.score!!, 0.0)
        assertEquals(374.0, result.lastChapterRead!!, 0.0)

        val request = executor.requests.single()
        assertEquals("https://api.myanimelist.net/v2/manga/2/my_list_status", request.url.toString())
        assertEquals("PUT", request.method)
        val body = request.bodyText()
        assertTrue(body.contains("status=completed"))
        assertTrue(body.contains("score=9"))
        assertTrue(body.contains("num_chapters_read=374"))
    }

    @Test
    fun `refresh reads my_list_status and maps is_rereading`() = runBlocking {
        login()
        executor.enqueue(
            200,
            """{"id":2,"title":"Berserk","num_chapters":374,
                "my_list_status":{"status":"reading","score":8,"num_chapters_read":120,"is_rereading":true}}""",
        )
        val refreshed = tracker.refresh(
            TrackEntry(mangaId = 1, trackerName = "myanimelist", remoteId = "2", title = "Berserk"),
        )!!

        assertEquals(TrackStatus.REPEATING, refreshed.status)
        assertEquals(8.0, refreshed.score!!, 0.0)
        assertEquals(120.0, refreshed.lastChapterRead!!, 0.0)
        // Request asked for the list-status fields.
        assertTrue(executor.requests.single().url.toString().contains("my_list_status"))
    }

    @Test
    fun `refresh returns null when the manga is not on the list`() = runBlocking {
        login()
        executor.enqueue(200, """{"id":2,"title":"Berserk","num_chapters":374}""")
        assertNull(
            tracker.refresh(TrackEntry(mangaId = 1, trackerName = "myanimelist", remoteId = "2", title = "B")),
        )
    }

    @Test
    fun `unbind DELETEs the list status`() = runBlocking {
        login()
        executor.enqueue(200, """{"deleted":true}""")
        tracker.unbind(TrackEntry(mangaId = 1, trackerName = "myanimelist", remoteId = "2", title = "B"))

        val request = executor.requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("https://api.myanimelist.net/v2/manga/2/my_list_status", request.url.toString())
    }

    // ── OAuth / PKCE flow ───────────────────────────────────────────────

    @Test
    fun `prepareLogin builds a PKCE authorize url and login exchanges the code`() = runBlocking {
        val loginRequest = tracker.prepareLogin()
        assertTrue(loginRequest.url.startsWith("https://myanimelist.net/v1/oauth2/authorize?"))
        assertTrue(loginRequest.url.contains("response_type=code"))
        assertTrue(loginRequest.url.contains("client_id=${MyAnimeListTracker.DEFAULT_CLIENT_ID}"))
        assertTrue(loginRequest.url.contains("code_challenge="))
        // Without the S256 declaration, RFC 7636 defaults the challenge
        // method to "plain" and the token exchange would fail.
        assertTrue(loginRequest.url.contains("code_challenge_method=S256"))
        assertEquals(CredentialStyle.AUTH_CODE, loginRequest.credentialStyle)

        executor.enqueue(200, """{"token_type":"Bearer","expires_in":2678400,
            "access_token":"mal-access","refresh_token":"mal-refresh"}""")
        executor.enqueue(200, """{"id":1,"name":"mal-user","location":""}""")
        tracker.login("https://example.com/callback?code=auth-code-xyz")

        val exchange = executor.requests[0]
        assertEquals("https://myanimelist.net/v1/oauth2/token", exchange.url.toString())
        val form = exchange.body as okhttp3.FormBody
        fun formValue(name: String): String =
            (0 until form.size).first { form.name(it) == name }.let { form.value(it) }
        assertEquals("authorization_code", formValue("grant_type"))
        assertEquals("auth-code-xyz", formValue("code"))
        assertEquals(MyAnimeListTracker.DEFAULT_CLIENT_ID, formValue("client_id"))
        // The verifier matches the challenge from the authorize URL.
        val challenge = loginRequest.url.substringAfter("code_challenge=").substringBefore('&')
        assertEquals(Pkce.codeChallenge(formValue("code_verifier")), challenge)

        assertEquals("mal-access", auth.get(MyAnimeListTracker.KEY_ACCESS_TOKEN))
        assertEquals("mal-refresh", auth.get(MyAnimeListTracker.KEY_REFRESH_TOKEN))
        assertEquals("mal-user", auth.get(MyAnimeListTracker.KEY_USERNAME))
    }

    @Test
    fun `a raw code paste works too`() = runBlocking {
        tracker.prepareLogin()
        executor.enqueue(200, """{"access_token":"t","refresh_token":"r","expires_in":100}""")
        tracker.login("  raw-code-42 ")
        // (the follow-up /users/@me probe records a second request; it is
        // best-effort and may fail against the exhausted fake)
        assertTrue(executor.requests.first().bodyText().contains("code=raw-code-42"))
    }

    @Test
    fun `a failed exchange keeps the verifier so the same dialog can retry`() = runBlocking {
        val loginRequest = tracker.prepareLogin()

        // First attempt: MAL rejects the code (expired / already used).
        executor.enqueue(400, """{"error":"invalid_grant","message":"invalid_grant"}""")
        runCatching { tracker.login("https://example.com/callback?code=stale") }
        assertNull(auth.get(MyAnimeListTracker.KEY_ACCESS_TOKEN))

        // Retry from the SAME dialog (same challenge) with a fresh code works.
        executor.enqueue(200, """{"token_type":"Bearer","expires_in":100,"access_token":"t2","refresh_token":"r2"}""")
        executor.enqueue(200, """{"id":1,"name":"u","location":""}""")
        tracker.login("https://example.com/callback?code=fresh")
        assertEquals("t2", auth.get(MyAnimeListTracker.KEY_ACCESS_TOKEN))

        // Both exchanges used the same verifier, bound to the dialog's challenge.
        fun verifierOf(request: okhttp3.Request): String {
            val form = request.body as okhttp3.FormBody
            return (0 until form.size).first { form.name(it) == "code_verifier" }.let { form.value(it) }
        }
        val exchanges = executor.requests.filter { it.url.toString() == "https://myanimelist.net/v1/oauth2/token" }
        assertEquals(2, exchanges.size)
        assertEquals(verifierOf(exchanges[0]), verifierOf(exchanges[1]))
        val challenge = loginRequest.url.substringAfter("code_challenge=").substringBefore('&')
        assertEquals(Pkce.codeChallenge(verifierOf(exchanges[0])), challenge)
    }

    @Test
    fun `401 triggers one token refresh and a retry with the new token`() = runBlocking {
        login()
        // First API call: expired token.
        executor.enqueue(401, """{"message":"expired"}""")
        // Refresh exchange.
        executor.enqueue(200, """{"access_token":"mal-token-2","refresh_token":"mal-refresh-2","expires_in":100}""")
        // Retried call succeeds.
        executor.enqueue(200, """{"status":"reading","score":0,"num_chapters_read":5,"is_rereading":false}""")

        val result = tracker.update(
            TrackEntry(mangaId = 1, trackerName = "myanimelist", remoteId = "2", title = "B"),
            TrackUpdate(lastChapterRead = 5.0),
        )
        assertEquals(5.0, result.lastChapterRead!!, 0.0)

        assertEquals(3, executor.requests.size)
        val (first, refresh, retry) = executor.requests
        assertEquals("Bearer mal-token-1", first.header("Authorization"))
        assertEquals("https://myanimelist.net/v1/oauth2/token", refresh.url.toString())
        assertTrue(refresh.bodyText().contains("grant_type=refresh_token"))
        assertTrue(refresh.bodyText().contains("refresh_token=mal-refresh-1"))
        assertEquals("Bearer mal-token-2", retry.header("Authorization"))
        // The refreshed tokens are persisted.
        assertEquals("mal-token-2", auth.get(MyAnimeListTracker.KEY_ACCESS_TOKEN))
        assertEquals("mal-refresh-2", auth.get(MyAnimeListTracker.KEY_REFRESH_TOKEN))
    }

    @Test
    fun `simultaneous 401s are served by exactly one token refresh`() = runBlocking {
        login()
        // Gate the fake so the refresh exchange cannot run until BOTH
        // callers have been served their 401: both coroutines enter
        // refreshTokens and contend on refreshMutex, and the loser must
        // skip its own exchange via the double-check. The short hold after
        // the gate lets the loser read the still-old refresh token before
        // the winner rotates it (that read is what the double-check
        // compares against).
        val bothGot401 = CountDownLatch(2)
        val serveLock = Any() // the underlying FakeExecutor is not thread-safe
        val gated = object : HttpExecutor {
            override fun execute(request: Request): HttpResult {
                val refreshGrant =
                    request.url.toString() == "https://myanimelist.net/v1/oauth2/token" &&
                        request.bodyText().contains("grant_type=refresh_token")
                if (refreshGrant) {
                    check(bothGot401.await(10, TimeUnit.SECONDS)) { "both callers never hit their 401" }
                    Thread.sleep(200)
                }
                return synchronized(serveLock) {
                    val result = executor.execute(request)
                    if (!refreshGrant && result.code == 401) bothGot401.countDown()
                    result
                }
            }
        }
        val racer = MyAnimeListTracker(gated, auth)

        // Two stale-token calls, one refresh exchange, two retries.
        executor.enqueue(401, """{"message":"expired"}""")
        executor.enqueue(401, """{"message":"expired"}""")
        executor.enqueue(200, """{"access_token":"mal-token-2","refresh_token":"mal-refresh-2","expires_in":100}""")
        executor.enqueue(200, """{"status":"reading","score":0,"num_chapters_read":5,"is_rereading":false}""")
        executor.enqueue(200, """{"status":"reading","score":0,"num_chapters_read":6,"is_rereading":false}""")

        fun entry() = TrackEntry(mangaId = 1, trackerName = "myanimelist", remoteId = "2", title = "Berserk")
        val first = async { racer.update(entry(), TrackUpdate(lastChapterRead = 5.0)) }
        val second = async { racer.update(entry(), TrackUpdate(lastChapterRead = 6.0)) }
        val results = listOf(first.await(), second.await())

        // Both callers succeeded (retry order between them is not deterministic).
        assertEquals(setOf(5.0, 6.0), results.map { it.lastChapterRead }.toSet())
        // Exactly one refresh-token grant hit the token endpoint.
        val refreshes = executor.requests.filter {
            it.url.toString() == "https://myanimelist.net/v1/oauth2/token" &&
                it.bodyText().contains("grant_type=refresh_token")
        }
        assertEquals(1, refreshes.size)
        // Two stale calls + one refresh + two retries, in that order.
        assertEquals(5, executor.requests.size)
        assertTrue(executor.requests.take(2).all { it.header("Authorization") == "Bearer mal-token-1" })
        assertTrue(executor.requests.drop(3).all { it.header("Authorization") == "Bearer mal-token-2" })
        // The rotated pair was persisted exactly once.
        assertEquals("mal-token-2", auth.get(MyAnimeListTracker.KEY_ACCESS_TOKEN))
        assertEquals("mal-refresh-2", auth.get(MyAnimeListTracker.KEY_REFRESH_TOKEN))
    }

    @Test
    fun `non-401 failures throw TrackerException`() = runBlocking {
        login()
        executor.enqueue(400, """{"message":"Invalid content","error":"invalid_content"}""")
        try {
            tracker.update(
                TrackEntry(mangaId = 1, trackerName = "myanimelist", remoteId = "2", title = "B"),
                TrackUpdate(lastChapterRead = 1.0),
            )
            org.junit.Assert.fail("expected TrackerException")
        } catch (e: TrackerException) {
            assertTrue(e.message!!.contains("HTTP 400"))
        }
    }

    @Test
    fun `client id override is used for authorize url, exchange and api calls`() = runBlocking {
        val custom = MyAnimeListTracker(executor, auth, clientIdProvider = { "my-own-client" })
        val loginRequest = custom.prepareLogin()
        assertTrue(loginRequest.url.contains("client_id=my-own-client"))

        auth.put(MyAnimeListTracker.KEY_ACCESS_TOKEN, "tok")
        executor.enqueue(200, """{"data":[]}""")
        custom.search("x")
        assertEquals("my-own-client", executor.requests.single().header("X-MAL-CLIENT-ID"))
    }

    @Test
    fun `logout drops only mal keys`() {
        login()
        auth.put(AniListTracker.KEY_TOKEN, "keep-me")
        tracker.logout()

        assertNull(auth.get(MyAnimeListTracker.KEY_ACCESS_TOKEN))
        assertNull(auth.get(MyAnimeListTracker.KEY_REFRESH_TOKEN))
        assertEquals("keep-me", auth.get(AniListTracker.KEY_TOKEN))
    }
}
