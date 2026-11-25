package com.auroratv

import com.auroratv.playback.PlayMethod
import com.auroratv.playback.PlaybackNegotiator
import com.auroratv.playback.PlaybackPreparer
import com.auroratv.playback.PlaybackPrewarmer
import com.auroratv.settings.Settings
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Preparing playback ahead of Play: what is fetched, in parallel, and when a prepared result is used. */
class PrepareTest {
    private val source = """{"Id":"ms1","Container":"mkv","MediaStreams":[
        {"Index":0,"Type":"Video","Codec":"hevc"},
        {"Index":1,"Type":"Audio","Codec":"aac","Language":"jpn","IsDefault":true},
        {"Index":2,"Type":"Subtitle","Codec":"ass","Language":"eng","IsDefault":true}],
        "MediaAttachments":[{"Index":3,"Codec":"ttf","FileName":"Typeset.ttf","MimeType":"application/x-truetype-font"}]"""
    private val item = """{"Id":"ep1","Name":"Episode 1","Type":"Episode","RunTimeTicks":14400000000,
        "UserData":{"PlaybackPositionTicks":3000000000},"MediaSources":[$source,"SupportsDirectPlay":true}]}"""

    private fun server(transcode: Boolean) = FakeServer { req ->
        val path = req.url.encodedPath
        when {
            path.endsWith("/PlaybackInfo") -> json(
                if (transcode) """{"MediaSources":[$source,"SupportsDirectPlay":false,"SupportsDirectStream":false,"TranscodingUrl":"/videos/ep1/master.m3u8?TranscodeReasons=VideoCodecNotSupported"}],"PlaySessionId":"ps"}"""
                else """{"MediaSources":[$source,"SupportsDirectPlay":true,"SupportsDirectStream":true}],"PlaySessionId":"ps"}"""
            )
            path.contains("/Attachments/") -> respond(ByteArray(2048) { 7 }, HttpStatusCode.OK, headersOf("Content-Type", "font/ttf"))
            path.endsWith("/Items/ep1") -> json(item)
            else -> json("{}", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun transcodeWithAssBringsItsFontsAndResumePoint() = runTest {
        val s = server(transcode = true)
        val p = PlaybackPreparer.prepare(api(s), PlaybackNegotiator(api(s), oldStick), Settings(), "ep1", startFromBeginning = false)
        assertEquals(PlayMethod.Transcode, p.plan.method)
        assertEquals(300_000L, p.startMs)
        assertEquals(listOf("Typeset.ttf"), p.fonts.map { it.name })
        assertEquals(2048, p.fonts.single().data.size)
    }

    @Test
    fun directPlayNeedsNoFonts() = runTest {
        val s = server(transcode = false)
        val p = PlaybackPreparer.prepare(api(s), PlaybackNegotiator(api(s), fireTv4k), Settings(), "ep1", startFromBeginning = true)
        assertEquals(PlayMethod.DirectPlay, p.plan.method)
        assertEquals(0L, p.startMs)
        assertTrue(p.fonts.isEmpty(), "MKV fonts reach the player inside the file")
    }

    @Test
    fun preparedPlaybackIsUsedOnceAndOnlyForTheSameSetup() = runTest {
        val s = server(transcode = false)
        val a = api(s)
        val warm = PlaybackPrewarmer(this)
        warm.prewarm(a, fireTv4k, Settings(), "ep1")
        val first = warm.take(a, fireTv4k, Settings(), "ep1")
        assertNotNull(first)
        assertNull(warm.take(a, fireTv4k, Settings(), "ep1"), "consumed")

        warm.prewarm(a, fireTv4k, Settings(), "ep1")
        assertNull(warm.take(a, fireTv4k, Settings(maxBitrateMbps = 8), "ep1"), "settings changed since")
        warm.prewarm(a, fireTv4k, Settings(), "ep1")
        assertNull(warm.take(a, oldStick, Settings(), "ep1"), "different capabilities (e.g. player engine switched)")
        warm.prewarm(a, fireTv4k, Settings(), "ep1")
        assertNull(warm.take(a, fireTv4k, Settings(), "other"), "different title")
        fun apiRequests() = s.requests.count { !it.url.encodedPath.contains("/Attachments/") }
        val requests = apiRequests()
        // Asking for another title left ep1's preparation alone (several are kept), and focusing it
        // again doesn't redo it.
        warm.prewarm(a, fireTv4k, Settings(), "ep1")
        warm.prewarm(a, fireTv4k, Settings(), "ep1")
        val again = warm.take(a, fireTv4k, Settings(), "ep1")
        assertNotNull(again)
        assertEquals(requests, apiRequests(), "nothing asked again")
        assertEquals("ep1", again.item.id)
    }

    /** A server with titles t1…t9 (any id): Direct Play, or a transcode. */
    private fun library(transcode: Boolean) = FakeServer { req ->
        val path = req.url.encodedPath
        val id = Regex("/Items/([^/]+)").find(path)?.groupValues?.get(1) ?: "x"
        val src = """{"Id":"ms-$id","Container":"mkv","MediaStreams":[{"Index":0,"Type":"Video","Codec":"hevc"},{"Index":1,"Type":"Audio","Codec":"aac","Language":"eng"}]"""
        when {
            path.endsWith("/PlaybackInfo") -> json(
                if (transcode) """{"MediaSources":[$src,"SupportsDirectPlay":false,"SupportsDirectStream":false,"TranscodingUrl":"/videos/$id/master.m3u8?TranscodeReasons=VideoCodecNotSupported"}],"PlaySessionId":"ps"}"""
                else """{"MediaSources":[$src,"SupportsDirectPlay":true,"SupportsDirectStream":true}],"PlaySessionId":"ps"}"""
            )
            path.startsWith("/Items/") -> json("""{"Id":"$id","Name":"$id","Type":"Movie","RunTimeTicks":60000000000,"UserData":{"PlaybackPositionTicks":600000000},"MediaSources":[$src,"SupportsDirectPlay":true}]}""")
            else -> json("{}", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun focusComingBackToACardReusesWhatWasPrepared() = runTest {
        val s = library(transcode = false)
        val a = api(s)
        val warm = PlaybackPrewarmer(this)
        // Continue Watching card, then a detail page for another title, then the card again.
        warm.prewarm(a, fireTv4k, Settings(), "t1")
        warm.prewarm(a, fireTv4k, Settings(), "t2")
        testScheduler.advanceUntilIdle()
        val before = s.requests.size
        warm.prewarm(a, fireTv4k, Settings(), "t1")
        testScheduler.advanceUntilIdle()
        assertEquals(before, s.requests.size, "t1 was still prepared: nothing asked again")
        assertNotNull(warm.take(a, fireTv4k, Settings(), "t1"))
        assertNotNull(warm.take(a, fireTv4k, Settings(), "t2"), "and t2 too")
    }

    @Test
    fun onlyTheLastFourTitlesAreKept() = runTest {
        val s = library(transcode = false)
        val a = api(s)
        val warm = PlaybackPrewarmer(this)
        for (t in listOf("t1", "t2", "t3", "t4", "t5")) warm.prewarm(a, fireTv4k, Settings(), t)
        testScheduler.advanceUntilIdle()
        assertNull(warm.take(a, fireTv4k, Settings(), "t1"), "the oldest made room")
        assertNotNull(warm.take(a, fireTv4k, Settings(), "t5"))
    }

    @Test
    fun preparingAheadAsksHowToPlayOnlyOnce() = runTest {
        val s = library(transcode = true)
        val a = api(s)
        val warm = PlaybackPrewarmer(this)
        warm.prewarm(a, oldStick, Settings(), "t1")
        val p = assertNotNull(warm.take(a, oldStick, Settings(), "t1"))
        assertEquals(PlayMethod.Transcode, p.plan.method)
        assertEquals(1, s.playbackInfoCalls(), "no guess made before the item arrived: nobody is waiting")
    }

    private fun FakeServer.playbackInfoCalls() = requests.count { it.url.encodedPath.endsWith("/PlaybackInfo") }

    @Test
    fun resumingDirectPlayNegotiatesWhileTheItemLoads() = runTest {
        val s = server(transcode = false)
        val p = PlaybackPreparer.prepare(api(s), PlaybackNegotiator(api(s), fireTv4k), Settings(), "ep1", startFromBeginning = false)
        assertEquals(PlayMethod.DirectPlay, p.plan.method)
        assertEquals(300_000L, p.startMs, "resume point still comes from the item")
        assertEquals(1, s.playbackInfoCalls(), "the early answer is used: Direct Play doesn't depend on the start point")
        assertEquals(p.audioIndex, p.plan.audioIndex, "the player still gets the chosen tracks")
        assertEquals(p.subtitleIndex, p.plan.subtitleIndex)
    }

    @Test
    fun aTranscodeIsNegotiatedAgainForTheResumePoint() = runTest {
        val s = server(transcode = true)
        val p = PlaybackPreparer.prepare(api(s), PlaybackNegotiator(api(s), oldStick), Settings(), "ep1", startFromBeginning = false)
        assertEquals(PlayMethod.Transcode, p.plan.method)
        assertEquals(2, s.playbackInfoCalls(), "the server must start the transcode at the resume point")
    }

    @Test
    fun originalPathAsksOneAfterTheOther() = runTest {
        val before = com.auroratv.playback.ResumeTuning.level
        try {
            com.auroratv.playback.ResumeTuning.level = 0
            val s = server(transcode = false)
            PlaybackPreparer.prepare(api(s), PlaybackNegotiator(api(s), fireTv4k), Settings(), "ep1", startFromBeginning = false)
            assertEquals(1, s.playbackInfoCalls())
        } finally {
            com.auroratv.playback.ResumeTuning.level = before
        }
    }
}
