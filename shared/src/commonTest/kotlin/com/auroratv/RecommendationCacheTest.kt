package com.auroratv

import com.auroratv.recommend.RecommendationService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * "Recommended For You" kept per profile in memory: Home opened again asks the server nothing,
 * Homes opened at the same time share one request, and only new watch activity loads it again.
 */
class RecommendationCacheTest {
    private val history = """{"Items":[
        {"Id":"m1","Name":"Tidewater","Type":"Movie","Genres":["Drama"],"UserData":{"Played":true,"PlayCount":1}},
        {"Id":"m2","Name":"Velvet Static","Type":"Movie","Genres":["Thriller"],"UserData":{"Played":true,"PlayCount":1}}
    ],"TotalRecordCount":2}"""

    @Test
    fun planIsKeptPerProfileAndLoadedOnceAtATime() = runTest {
        val server = FakeServer { json(history) }
        val a = api(server)
        val svc = RecommendationService(this, MemoryFileStore())
        assertNull(svc.peekPlan("p1", 0))

        // Two Homes asking at once: one load (the history, plus details of its titles), the same plan for both.
        val (x, y) = listOf(async { svc.planFor(a, "p1", 0, emptyList()) }, async { svc.planFor(a, "p1", 0, emptyList()) }).awaitAll()
        assertSame(x, y)
        val afterFirst = server.requests.size
        assertTrue(afterFirst in 1..2, "one load: $afterFirst request(s)")

        // Opened again with nothing watched since: from memory, no requests.
        assertSame(x, svc.planFor(a, "p1", 0, emptyList()))
        assertEquals(afterFirst, server.requests.size)
        assertTrue(assertNotNull(svc.peekPlan("p1", 0)).fresh)

        // Another profile has its own.
        assertNull(svc.peekPlan("p2", 0))

        // Something was watched: the old plan is still there to show (stale), and a new one is loaded.
        val stale = assertNotNull(svc.peekPlan("p1", 1))
        assertFalse(stale.fresh)
        assertSame(x, stale.plan)
        svc.planFor(a, "p1", 1, emptyList())
        assertEquals(afterFirst * 2, server.requests.size)

        // A removed profile forgets it.
        svc.forget("p1")
        assertNull(svc.peekPlan("p1", 1))
    }
}
