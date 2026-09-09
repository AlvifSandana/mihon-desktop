package mihon.desktop.loader.tracker

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.RequestBody
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Reads a request (form/JSON) body back as text for assertions. */
fun RequestBody.bodyText(): String = Buffer().apply { writeTo(this) }.readUtf8()

fun Request.bodyText(): String = body!!.bodyText()

/** Scriptable [HttpExecutor]: records every request, serves queued responses. */
class FakeExecutor : HttpExecutor {
    data class Served(val code: Int, val body: String)

    val requests = mutableListOf<Request>()
    private val responses = ArrayDeque<Served>()

    fun enqueue(code: Int, body: String) {
        responses.addLast(Served(code, body))
    }

    override fun execute(request: Request): HttpResult {
        requests.add(request)
        val next = responses.removeFirstOrNull() ?: error("No canned response for ${request.url}")
        return HttpResult(code = next.code, body = next.body, url = request.url.toString())
    }
}

class AniListTrackerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var auth: TrackerAuthStore
    private lateinit var executor: FakeExecutor
    private lateinit var tracker: AniListTracker

    @Before
    fun setUp() {
        auth = TrackerAuthStore(File(folder.root, "auth.properties"))
        executor = FakeExecutor()
        tracker = AniListTracker(executor, auth)
    }

    private fun login() {
        auth.put(AniListTracker.KEY_TOKEN, "test-token")
        auth.put(AniListTracker.KEY_USER_ID, "12345")
    }

    // ── GraphQL payload builder ─────────────────────────────────────────

    @Test
    fun `buildPayload embeds query and variables`() {
        val payload = AniListTracker.buildPayload(
            "query { Viewer { id } }",
            kotlinx.serialization.json.buildJsonObject { put("q", "berserk") },
        )
        val json = Json.parseToJsonElement(payload).jsonObject
        assertEquals("query { Viewer { id } }", json["query"]!!.jsonPrimitiveStr())
        assertEquals("berserk", json["variables"]!!.jsonObject["q"]!!.jsonPrimitiveStr())
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveStr(): String =
        (this as kotlinx.serialization.json.JsonPrimitive).content

    // ── response parsers (sample JSON, no network) ──────────────────────

    @Test
    fun `parseViewerResponse extracts id and name`() {
        val (id, name) = AniListTracker.parseViewerResponse(
            """{"data":{"Viewer":{"id":12345,"name":"mihon-user"}}}""",
        )
        assertEquals(12345, id)
        assertEquals("mihon-user", name)
    }

    @Test
    fun `parseSearchResponse maps fields and tolerates nulls`() {
        val hits = AniListTracker.parseSearchResponse(
            """
            {"data":{"Page":{"media":[
                {"id":30002,"title":{"userPreferred":"GANTZ"},"description":"<b>Gantz</b>...",
                 "chapters":393,"averageScore":78,"status":"FINISHED",
                 "coverImage":{"large":"https://s4.anilist.co/file/large.png"}},
                {"id":30656,"title":{"userPreferred":"Berserk"},"description":null,
                 "chapters":null,"averageScore":null,"status":null,"coverImage":null}
            ]}}}
            """.trimIndent(),
        )
        assertEquals(2, hits.size)

        val gantz = hits[0]
        assertEquals("30002", gantz.remoteId)
        assertEquals("GANTZ", gantz.title)
        assertEquals("<b>Gantz</b>...", gantz.summary)
        assertEquals(393L, gantz.totalChapters)
        assertEquals(78.0, gantz.score!!, 0.0)
        assertEquals("FINISHED", gantz.publishingStatus)
        assertEquals("https://s4.anilist.co/file/large.png", gantz.coverUrl)
        assertEquals("https://anilist.co/manga/30002", gantz.trackingUrl)

        val berserk = hits[1]
        assertEquals(0L, berserk.totalChapters)
        assertNull(berserk.score)
        assertNull(berserk.coverUrl)
        assertEquals("", berserk.summary)
    }

    @Test
    fun `parseMediaListResponse returns null when not on the list`() {
        assertNull(AniListTracker.parseMediaListResponse("""{"data":{"MediaList":null}}"""))

        val entry = AniListTracker.parseMediaListResponse(
            """{"data":{"MediaList":{"id":987654,"status":"PAUSED","scoreRaw":80,"progress":120}}}""",
        )!!
        assertEquals(987654L, entry.listId)
        assertEquals("PAUSED", entry.status)
        assertEquals(80, entry.scoreRaw)
        assertEquals(120, entry.progress)
    }

    @Test
    fun `parseSaveResponse reads the created or updated entry`() {
        val entry = AniListTracker.parseSaveResponse(
            """{"data":{"SaveMediaListEntry":{"id":111222,"status":"CURRENT","scoreRaw":90,"progress":13}}}""",
        )!!
        assertEquals(111222L, entry.listId)
        assertEquals("CURRENT", entry.status)
        assertEquals(90, entry.scoreRaw)
        assertEquals(13, entry.progress)
    }

    // ── HTTP layer via the executor seam ────────────────────────────────

    @Test
    fun `search posts the query and parses the response`() = runBlocking {
        login()
        executor.enqueue(
            200,
            """{"data":{"Page":{"media":[{"id":30002,"title":{"userPreferred":"GANTZ"},"chapters":393,
                "averageScore":78,"status":"FINISHED","coverImage":{"large":"u"}}]}}}""",
        )
        val hits = tracker.search("gantz")

        assertEquals(1, hits.size)
        assertEquals("GANTZ", hits[0].title)

        val request = executor.requests.single()
        assertEquals("https://graphql.anilist.co/", request.url.toString())
        assertEquals("Bearer test-token", request.header("Authorization"))
        assertTrue(request.bodyText().contains("\"q\""))
        assertTrue(request.bodyText().contains("gantz"))
        assertTrue(request.bodyText().contains("Page(perPage: 20)"))
    }

    @Test
    fun `update pushes mediaId status progress and scoreRaw`() = runBlocking {
        login()
        executor.enqueue(
            200,
            """{"data":{"SaveMediaListEntry":{"id":1,"status":"PAUSED","scoreRaw":80,"progress":12}}}""",
        )
        val result = tracker.update(
            TrackEntry(mangaId = 1, trackerName = "anilist", remoteId = "30002", title = "GANTZ"),
            TrackUpdate(status = TrackStatus.ON_HOLD, score = 8.0, lastChapterRead = 12.0),
        )

        // scoreRaw 80 pulls back as normalized 8.0.
        assertEquals(TrackStatus.ON_HOLD, result.status)
        assertEquals(8.0, result.score!!, 0.0)
        assertEquals(12.0, result.lastChapterRead!!, 0.0)

        val body = Json.parseToJsonElement(executor.requests.single().bodyText()).jsonObject
        val vars = body["variables"]!!.jsonObject
        assertEquals(30002, (vars["mediaId"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals("PAUSED", (vars["status"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(12, (vars["progress"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals(80, (vars["scoreRaw"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        // The mutation itself is the SaveMediaListEntry upsert.
        assertTrue(body["query"]!!.jsonPrimitiveStr().contains("SaveMediaListEntry"))
    }

    @Test
    fun `refresh reads the MediaList entry and maps status score and progress`() = runBlocking {
        login()
        executor.enqueue(
            200,
            """{"data":{"MediaList":{"id":987654,"status":"PAUSED","scoreRaw":80,"progress":120}}}""",
        )
        val refreshed = tracker.refresh(
            TrackEntry(mangaId = 1, trackerName = "anilist", remoteId = "30002", title = "GANTZ"),
        )!!

        // scoreRaw 80 pulls back as normalized 8.0.
        assertEquals(TrackStatus.ON_HOLD, refreshed.status)
        assertEquals(8.0, refreshed.score!!, 0.0)
        assertEquals(120.0, refreshed.lastChapterRead!!, 0.0)

        // The query targeted this user's entry for this media id.
        val request = executor.requests.single()
        assertEquals("Bearer test-token", request.header("Authorization"))
        val body = Json.parseToJsonElement(request.bodyText()).jsonObject
        assertTrue(body["query"]!!.jsonPrimitiveStr().contains("MediaList"))
        val vars = body["variables"]!!.jsonObject
        assertEquals(12345, (vars["userId"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals(30002, (vars["mediaId"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
    }

    @Test
    fun `refresh returns null when the manga is not on the list`() = runBlocking {
        login()
        executor.enqueue(200, """{"data":{"MediaList":null}}""")
        assertNull(
            tracker.refresh(TrackEntry(mangaId = 1, trackerName = "anilist", remoteId = "30002", title = "G")),
        )
    }

    @Test
    fun `graphql errors array surfaces as TrackerException`() = runBlocking {
        login()
        executor.enqueue(200, """{"errors":[{"message":"Unauthorized"}],"data":null}""")
        try {
            tracker.search("x")
            fail("expected TrackerException")
        } catch (e: TrackerException) {
            assertEquals("AniList: Unauthorized", e.message)
        }
    }

    @Test
    fun `login verifies token and stores user`() = runBlocking {
        executor.enqueue(200, """{"data":{"Viewer":{"id":12345,"name":"mihon-user"}}}""")
        tracker.login("pasted-access-token-abc")

        assertEquals("pasted-access-token-abc", auth.get(AniListTracker.KEY_TOKEN))
        assertEquals("12345", auth.get(AniListTracker.KEY_USER_ID))
        assertEquals("mihon-user", auth.get(AniListTracker.KEY_USERNAME))
        assertEquals("Bearer pasted-access-token-abc", executor.requests.single().header("Authorization"))
    }

    @Test
    fun `login extracts token from a pasted redirect url`() = runBlocking {
        executor.enqueue(200, """{"data":{"Viewer":{"id":1,"name":"u"}}}""")
        tracker.login("https://anilist.co/redirect#access_token=tok-123&token_type=Bearer")
        assertEquals("tok-123", auth.get(AniListTracker.KEY_TOKEN))
    }

    @Test
    fun `logout drops only anilist keys`() {
        login()
        auth.put("mal.accessToken", "keep-me")
        tracker.logout()

        assertNull(auth.get(AniListTracker.KEY_TOKEN))
        assertEquals("keep-me", auth.get("mal.accessToken"))
    }

    @Test
    fun `prepareLogin uses mihon client id and token response type`() {
        val login = tracker.prepareLogin()
        assertEquals(
            "https://anilist.co/api/v2/oauth/authorize?client_id=16329&response_type=token",
            login.url,
        )
        assertEquals(CredentialStyle.ACCESS_TOKEN, login.credentialStyle)
    }
}
