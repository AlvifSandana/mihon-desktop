package mihon.desktop.loader.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

/** Status mapping round-trips, both directions, for AniList and MAL. */
class TrackStatusMappingTest {

    @Test
    fun `anilist toRemote covers every normalized status`() {
        assertEquals("CURRENT", AniListStatus.toRemote(TrackStatus.READING))
        assertEquals("PLANNING", AniListStatus.toRemote(TrackStatus.PLAN_TO_READ))
        assertEquals("COMPLETED", AniListStatus.toRemote(TrackStatus.COMPLETED))
        assertEquals("DROPPED", AniListStatus.toRemote(TrackStatus.DROPPED))
        assertEquals("PAUSED", AniListStatus.toRemote(TrackStatus.ON_HOLD))
        assertEquals("REPEATING", AniListStatus.toRemote(TrackStatus.REPEATING))
    }

    @Test
    fun `anilist fromRemote maps every MediaListStatus`() {
        assertEquals(TrackStatus.READING, AniListStatus.fromRemote("CURRENT"))
        assertEquals(TrackStatus.PLAN_TO_READ, AniListStatus.fromRemote("PLANNING"))
        assertEquals(TrackStatus.COMPLETED, AniListStatus.fromRemote("COMPLETED"))
        assertEquals(TrackStatus.DROPPED, AniListStatus.fromRemote("DROPPED"))
        assertEquals(TrackStatus.ON_HOLD, AniListStatus.fromRemote("PAUSED"))
        assertEquals(TrackStatus.REPEATING, AniListStatus.fromRemote("REPEATING"))
        // Unknown remote value degrades to READING, never crashes.
        assertEquals(TrackStatus.READING, AniListStatus.fromRemote("SOMETHING_NEW"))
    }

    @Test
    fun `anilist round-trips every status`() {
        for (status in TrackStatus.entries) {
            assertEquals(status, AniListStatus.fromRemote(AniListStatus.toRemote(status)))
        }
    }

    @Test
    fun `mal toRemote maps statuses and folds REPEATING into reading`() {
        assertEquals("reading", MalStatus.toRemote(TrackStatus.READING))
        assertEquals("on_hold", MalStatus.toRemote(TrackStatus.ON_HOLD))
        assertEquals("completed", MalStatus.toRemote(TrackStatus.COMPLETED))
        assertEquals("dropped", MalStatus.toRemote(TrackStatus.DROPPED))
        assertEquals("plan_to_read", MalStatus.toRemote(TrackStatus.PLAN_TO_READ))
        // MAL expresses rereading as reading + is_rereading=true.
        assertEquals("reading", MalStatus.toRemote(TrackStatus.REPEATING))
    }

    @Test
    fun `mal fromRemote maps statuses and is_rereading`() {
        assertEquals(TrackStatus.READING, MalStatus.fromRemote("reading", isRereading = false))
        assertEquals(TrackStatus.ON_HOLD, MalStatus.fromRemote("on_hold", isRereading = false))
        assertEquals(TrackStatus.COMPLETED, MalStatus.fromRemote("completed", isRereading = false))
        assertEquals(TrackStatus.DROPPED, MalStatus.fromRemote("dropped", isRereading = false))
        assertEquals(TrackStatus.PLAN_TO_READ, MalStatus.fromRemote("plan_to_read", isRereading = false))
        assertEquals(TrackStatus.REPEATING, MalStatus.fromRemote("reading", isRereading = true))
        assertEquals(TrackStatus.REPEATING, MalStatus.fromRemote("completed", isRereading = true))
        // Unknown remote value degrades to READING.
        assertEquals(TrackStatus.READING, MalStatus.fromRemote("weird_status", isRereading = false))
    }

    @Test
    fun `mal round-trips statuses except REPEATING which needs the rereading flag`() {
        for (status in TrackStatus.entries - TrackStatus.REPEATING) {
            val remote = MalStatus.toRemote(status)
            assertEquals(status, MalStatus.fromRemote(remote, isRereading = false))
        }
    }
}
