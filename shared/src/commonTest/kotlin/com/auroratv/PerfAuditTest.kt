package com.auroratv

import com.auroratv.jellyfin.TimedCache
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Performance audit (Nov 2026): the caches it added stay bounded and never serve stale watched state. */
class PerfAuditTest {
    @Test
    fun timedCacheIsBounded() {
        val c = TimedCache<Int, String>(capacity = 3, ttl = kotlin.time.Duration.parse("1h"))
        (1..5).forEach { c.put(it, "v$it") }
        assertEquals(3, c.size)
        assertNull(c.get(1), "the oldest entries go first")
        assertEquals("v5", c.get(5))
        c.clear()
        assertNull(c.get(5))
    }

    @Test
    fun timedCacheExpires() {
        val c = TimedCache<Int, String>(capacity = 3, ttl = kotlin.time.Duration.ZERO)
        c.put(1, "v")
        assertNull(c.get(1))
    }

    @Test
    fun moreLikeThisIsAskedOnceUntilSomethingIsWatched() = runTest {
        val fake = FakeServer { req ->
            when {
                req.url.encodedPath.endsWith("/Similar") -> json("""{"Items":[{"Id":"s1","Name":"Alike","Type":"Movie"}]}""")
                else -> json("{}")
            }
        }
        val a = api(fake)
        assertEquals("s1", a.similar("m1").single().id)
        a.similar("m1")
        assertEquals(1, fake.requests.count { it.url.encodedPath.endsWith("/Similar") }, "a reopened page reuses the answer")
        a.similar("m2")
        assertEquals(2, fake.requests.count { it.url.encodedPath.endsWith("/Similar") }, "another title asks for its own")
        a.setPlayed("s1", true)
        a.similar("m1")
        assertEquals(3, fake.requests.count { it.url.encodedPath.endsWith("/Similar") }, "after watching something, asked again (watched marks)")
    }
}
