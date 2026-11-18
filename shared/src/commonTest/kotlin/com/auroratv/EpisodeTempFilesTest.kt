package com.auroratv

import com.auroratv.playback.DisposableCache
import com.auroratv.playback.EpisodeTempFiles
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Saved file starts: deleted when finished, kept 5 days when left unfinished, never while in use. */
class EpisodeTempFilesTest {
    private class FakeCache(vararg keys: String) : DisposableCache {
        val held = keys.toMutableSet()
        val removed = mutableListOf<String>()
        override fun keys() = held.toSet()
        override fun remove(key: String) { held -= key; removed += key }
    }

    private val day = 24L * 60 * 60 * 1000
    private var clock = 1_000_000L

    private fun TestScope.ledger(files: MemoryFileStore, cache: FakeCache) =
        EpisodeTempFiles(files, cache, this, now = { clock }, serial = StandardTestDispatcher(testScheduler))

    @Test
    fun finishedEpisodeIsCleanedUpAtOnce() = runTest {
        val cache = FakeCache("hdr:a"); val t = ledger(MemoryFileStore(), cache)
        t.started("hdr:a", "ep1", "p1"); advanceUntilIdle()
        t.stopped("hdr:a", "p1", finished = true); advanceUntilIdle()
        assertEquals(listOf("hdr:a"), cache.removed)
    }

    @Test
    fun unfinishedEpisodeIsKeptFiveDaysAndTheClockRestartsOnResume() = runTest {
        val files = MemoryFileStore(); val cache = FakeCache("hdr:a")
        val t = ledger(files, cache)
        t.started("hdr:a", "ep1", "p1"); t.stopped("hdr:a", "p1", finished = false); advanceUntilIdle()
        clock += 4 * day
        // Resumed on day 4 and left again: another 5 days from now.
        t.started("hdr:a", "ep1", "p1"); t.stopped("hdr:a", "p1", finished = false); advanceUntilIdle()
        clock += 4 * day
        ledger(files, cache).sweep(); advanceUntilIdle() // a later app start
        assertTrue(cache.removed.isEmpty(), "8 days after the first watch, but only 4 after the last")
        clock += 2 * day
        ledger(files, cache).sweep(); advanceUntilIdle()
        assertEquals(listOf("hdr:a"), cache.removed, "not resumed for 6 days: deleted")
    }

    @Test
    fun otherProfilesAndPlaybackProtectIt() = runTest {
        val cache = FakeCache("hdr:a"); val t = ledger(MemoryFileStore(), cache)
        t.fetchedAhead("hdr:a", "ep1", "p2") // someone else is part-way through it
        t.started("hdr:a", "ep1", "p1"); t.stopped("hdr:a", "p1", finished = true); advanceUntilIdle()
        assertTrue(cache.removed.isEmpty(), "profile 2 still holds it")
        assertEquals(mapOf("hdr:a" to setOf("p2")), t.snapshot())
        // In use: the sweep never deletes it, even with every hold expired.
        t.started("hdr:a", "ep1", "p1"); advanceUntilIdle()
        clock += 30 * day
        t.sweep(); advanceUntilIdle()
        assertTrue(cache.removed.isEmpty(), "playing right now")
        t.stopped("hdr:a", "p1", finished = true); advanceUntilIdle()
        assertEquals(listOf("hdr:a"), cache.removed)
    }

    @Test
    fun unknownFilesAreAdoptedAndMissingOnesForgotten() = runTest {
        val files = MemoryFileStore(); val cache = FakeCache("hdr:old")
        val t = ledger(files, cache)
        t.sweep(); advanceUntilIdle()
        assertTrue(cache.removed.isEmpty(), "found without a record (older version or a crash): kept 5 more days")
        cache.held -= "hdr:old" // deleted behind our back (Android cleared the cache)
        t.sweep(); advanceUntilIdle()
        assertTrue(t.snapshot().isEmpty())
        assertFalse(files.read("temp-files.json") != null, "nothing left to remember")
    }

    @Test
    fun removingAProfileReleasesItsHolds() = runTest {
        val cache = FakeCache("hdr:a"); val t = ledger(MemoryFileStore(), cache)
        t.fetchedAhead("hdr:a", "ep1", "p1"); advanceUntilIdle()
        t.profileRemoved("p1"); advanceUntilIdle()
        assertEquals(listOf("hdr:a"), cache.removed)
    }
}
