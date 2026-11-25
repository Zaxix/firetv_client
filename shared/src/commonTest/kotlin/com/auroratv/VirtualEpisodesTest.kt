package com.auroratv

import com.auroratv.jellyfin.EpisodeSplit
import com.auroratv.jellyfin.SplitPart
import com.auroratv.jellyfin.SplitStore
import com.auroratv.jellyfin.VirtualEpisodes
import com.auroratv.jellyfin.VirtualIds
import com.auroratv.jellyfin.Window
import com.auroratv.jellyfin.model.ChapterInfo
import com.auroratv.jellyfin.model.PlaybackReport
import com.auroratv.playback.ClippedEngine
import com.auroratv.playback.EngineSource
import com.auroratv.playback.EngineState
import com.auroratv.playback.PlaybackEngine
import com.auroratv.playback.PlayerController
import com.auroratv.playback.SkipRange
import com.auroratv.playback.SubtitleTrackRef
import com.auroratv.profiles.LocalWatchStore
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Virtual Episode Splitting: two (or more) episodes in one file shown, played and tracked as
 * separate episodes, whether the server lists the file once or once per episode.
 */
class VirtualEpisodesTest {
    private val min = 60_000L
    private fun ticks(ms: Long) = ms * 10_000

    // A 22-minute file holding episodes 1 and 2 (the server lists it once, as 1–2).
    private val combined = """{"Id":"f1","Name":"The Lost Kite / Rain Day","Type":"Episode","SeriesId":"s1","SeasonId":"se1","SeriesName":"Paper Town",
        "IndexNumber":1,"IndexNumberEnd":2,"ParentIndexNumber":1,"RunTimeTicks":${ticks(22 * min)},"Overview":"Pip loses a kite.","Path":"/tv/Paper Town/S01E01-E02.mkv",
        "ImageTags":{"Primary":"p1"},"UserData":{"Played":false,"PlaybackPositionTicks":0}}"""
    private val ep3 = """{"Id":"e3","Name":"Snow","Type":"Episode","SeriesId":"s1","SeasonId":"se1","IndexNumber":3,"ParentIndexNumber":1,"RunTimeTicks":${ticks(11 * min)},"Path":"/tv/Paper Town/S01E03.mkv"}"""
    private fun chapters(named: Boolean) = """{"Items":[{"Id":"f1","Type":"Episode","RunTimeTicks":${ticks(22 * min)},"Path":"/tv/Paper Town/S01E01-E02.mkv","Chapters":[
        {"StartPositionTicks":0,"Name":"${if (named) "Episode 1" else "Chapter 1"}"},
        {"StartPositionTicks":${ticks(5 * min)},"Name":"Chapter 2"},
        {"StartPositionTicks":${ticks(10 * min + 45_000)},"Name":"${if (named) "Episode 2" else "Chapter 3"}","ImageTag":"c2"}]}]}"""

    private class Setup(val server: FakeServer, val files: MemoryFileStore, val store: SplitStore, val v: VirtualEpisodes) {
        val api = api(server).also { it.virtual = v }
    }

    private fun setup(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
                      files: MemoryFileStore = MemoryFileStore(), profile: String = "p1", now: () -> Long = { 1_000L }): Setup {
        val server = FakeServer(handler)
        val store = SplitStore(files, "srv")
        return Setup(server, files, store, VirtualEpisodes(store, LocalWatchStore(files, profile, now, base = "split-watch")))
    }

    private fun seasonHandler(named: Boolean): suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData = { req ->
        val path = req.url.encodedPath
        when {
            path.endsWith("/Episodes") -> json("""{"Items":[$combined,$ep3]}""")
            path == "/Items" && req.url.parameters["fields"]?.contains("Chapters") == true -> json(chapters(named))
            path == "/Items" -> json("""{"Items":[$combined]}""")
            path == "/Shows/NextUp" -> json("""{"Items":[$combined]}""")
            path == "/UserItems/Resume" -> json("""{"Items":[]}""")
            else -> json("{}")
        }
    }

    @Test
    fun fileNamesTitlesAndChapterNamesAreReadCarefully() {
        assertEquals(2, VirtualEpisodes.fileNameCount("/tv/Show/S01E01-E02.mkv"))
        assertEquals(2, VirtualEpisodes.fileNameCount("Show - S01E01E02 - Two.mkv"))
        assertEquals(3, VirtualEpisodes.fileNameCount("Show S02E05-07.mkv"))
        assertNull(VirtualEpisodes.fileNameCount("Show S01E01 - 1080p.mkv"), "a resolution isn't an episode")
        assertNull(VirtualEpisodes.fileNameCount("Show S01E01.mkv"))
        assertEquals(listOf("A", "B"), VirtualEpisodes.splitNames("A / B", 2))
        assertNull(VirtualEpisodes.splitNames("Just One Title", 2))
        val ch = listOf(ChapterInfo(0, "Opening"), ChapterInfo(ticks(11 * min), "Episode 2"))
        assertEquals(listOf(11 * min), VirtualEpisodes.boundariesFromChapters(ch, 2, listOf(1, 2), listOf(null, null), 22 * min))
        // By the episode's own number, or its title.
        assertEquals(listOf(11 * min), VirtualEpisodes.boundariesFromChapters(listOf(ChapterInfo(0, "x"), ChapterInfo(ticks(11 * min), "Ep. 14")), 2, listOf(13, 14), listOf(null, null), 22 * min))
        assertEquals(listOf(11 * min), VirtualEpisodes.boundariesFromChapters(listOf(ChapterInfo(0, "x"), ChapterInfo(ticks(11 * min), "Rain Day")), 2, listOf(1, 2), listOf("Kite", "Rain Day"), 22 * min))
        // "Part B" of one cartoon isn't an episode; nothing named: no split; a 1-minute "episode": no split.
        assertNull(VirtualEpisodes.boundariesFromChapters(listOf(ChapterInfo(0, "Part A"), ChapterInfo(ticks(11 * min), "Part B")), 2, listOf(1, 2), listOf(null, null), 22 * min))
        assertNull(VirtualEpisodes.boundariesFromChapters(listOf(ChapterInfo(0, "Chapter 1"), ChapterInfo(ticks(11 * min), "Chapter 2")), 2, listOf(1, 2), listOf(null, null), 22 * min))
        assertNull(VirtualEpisodes.boundariesFromChapters(listOf(ChapterInfo(0, "x"), ChapterInfo(ticks(21 * min), "Episode 2")), 2, listOf(1, 2), listOf(null, null), 22 * min))
        // Exactly one chapter per episode is only a suggestion for the editor.
        assertEquals(listOf(11 * min), VirtualEpisodes.suggestFromChapters(listOf(ChapterInfo(0, "Chapter 1"), ChapterInfo(ticks(11 * min), "Chapter 2")), 2, 22 * min))
    }

    @Test
    fun oneServerEntryWithChaptersNamingTheEpisodesBecomesTwoEpisodes() = runTest {
        val s = setup(seasonHandler(named = true))
        val eps = s.api.episodes("s1", seasonId = "se1")
        assertEquals(listOf(VirtualIds.of("f1", 0), VirtualIds.of("f1", 1), "e3"), eps.map { it.id }, "no duplicate cards: the file is replaced by its episodes")
        assertEquals(listOf(1, 2, 3), eps.map { it.indexNumber })
        assertEquals(listOf("The Lost Kite", "Rain Day"), eps.take(2).map { it.name })
        assertEquals(listOf(10 * min + 45_000, 22 * min - (10 * min + 45_000)), eps.take(2).map { it.runtimeMs })
        assertEquals("Pip loses a kite.", eps[0].overview)
        assertNull(eps[1].overview, "no description invented for the second episode")
        assertEquals("c2", eps[1].imageTags[VirtualIds.CHAPTER_TAG], "its own thumbnail: the chapter image where it starts")
        assertEquals(Window(10 * min + 45_000, 22 * min), s.v.window(VirtualIds.of("f1", 1)))
        // Looked at once: listing the season again asks for no chapters.
        val chapterAsks = s.server.requests.count { it.url.parameters["fields"]?.contains("Chapters") == true }
        s.api.episodes("s1", seasonId = "se1")
        assertEquals(chapterAsks, s.server.requests.count { it.url.parameters["fields"]?.contains("Chapters") == true })
        // Images and playback go to the file.
        assertTrue(s.api.images.landscape(eps[0], 400)!!.url.contains("/Items/f1/Images/Primary"))
        assertTrue(s.api.images.landscape(eps[1], 400)!!.url.contains("/Items/f1/Images/Chapter/2"))
    }

    @Test
    fun withoutReliableBoundariesNothingIsSplit() = runTest {
        val s = setup(seasonHandler(named = false))
        val eps = s.api.episodes("s1", seasonId = "se1")
        assertEquals(listOf("f1", "e3"), eps.map { it.id }, "no halves guessed: the file stays one playable entry")
        val decision = s.store["f1"]!!
        assertFalse(decision.isSplit)
        assertEquals(2, decision.count)
    }

    @Test
    fun progressIsPerEpisodeAndTheServerOnlyLearnsWhenTheFileIsDone() = runTest {
        val s = setup(seasonHandler(named = true))
        val eps = s.api.episodes("s1", seasonId = "se1")
        val (a, b) = eps
        // Episode 1 watched to 95 % of its own length.
        s.api.reportStart(PlaybackReport(a.id, "f1", null, 0))
        s.api.reportStopped(PlaybackReport(a.id, "f1", null, ticks(a.runtimeMs!! * 95 / 100)))
        // Episode 2 started and left at 4 minutes.
        s.api.reportStopped(PlaybackReport(b.id, "f1", null, ticks(4 * min)))
        val after = s.api.episodes("s1", seasonId = "se1")
        assertTrue(after[0].userData!!.played)
        assertFalse(after[1].userData!!.played, "finishing one doesn't finish the other")
        assertEquals(ticks(4 * min), after[1].userData!!.playbackPositionTicks)
        // Nothing about positions went to the server, and the file isn't marked watched yet.
        assertTrue(s.server.requests.none { it.url.encodedPath.startsWith("/Sessions/Playing") })
        assertTrue(s.server.requests.none { it.url.encodedPath.startsWith("/UserPlayedItems") })
        // Episode 2 finished: now the whole file is, and the server is told.
        s.api.reportStopped(PlaybackReport(b.id, "f1", null, ticks(b.runtimeMs!!)))
        assertTrue(s.server.requests.any { it.method == HttpMethod.Post && it.url.encodedPath == "/UserPlayedItems/f1" })
    }

    @Test
    fun eachProfileHasItsOwnProgress() = runTest {
        val files = MemoryFileStore()
        val one = setup(seasonHandler(named = true), files, "p1")
        val two = Setup(one.server, files, one.store, VirtualEpisodes(one.store, LocalWatchStore(files, "p2", { 1_000L }, base = "split-watch")))
        val a = one.api.episodes("s1", seasonId = "se1")[1]
        one.api.reportStopped(PlaybackReport(a.id, "f1", null, ticks(5 * min)))
        assertEquals(ticks(5 * min), one.api.episodes("s1", seasonId = "se1")[1].userData!!.playbackPositionTicks)
        assertEquals(0L, two.api.episodes("s1", seasonId = "se1")[1].userData!!.playbackPositionTicks)
    }

    @Test
    fun nextUpContinueWatchingAndTheNextEpisodeFollowTheParts() = runTest {
        var clock = 1_000L
        val s = setup(seasonHandler(named = true), now = { clock })
        s.api.episodes("s1", seasonId = "se1")
        // Next Up: the file's first episode not watched.
        assertEquals(VirtualIds.of("f1", 0), s.api.nextUp().single().id)
        // The episode after part 1 is part 2, then episode 3.
        assertEquals(VirtualIds.of("f1", 1), s.api.episodes("s1", startItemId = VirtualIds.of("f1", 0), limit = 2)[1].id)
        assertEquals("e3", s.api.episodes("s1", startItemId = VirtualIds.of("f1", 1), limit = 2)[1].id)
        // Part 1 done, part 2 half watched: Continue Watching shows part 2 (not the file), Next Up leaves it to it.
        s.api.reportStopped(PlaybackReport(VirtualIds.of("f1", 0), "f1", null, ticks(11 * min)))
        clock = 2_000L
        s.api.reportStopped(PlaybackReport(VirtualIds.of("f1", 1), "f1", null, ticks(5 * min)))
        val resume = s.api.resumeItems()
        assertEquals(listOf(VirtualIds.of("f1", 1)), resume.map { it.id })
        assertEquals(ticks(5 * min), resume.single().userData!!.playbackPositionTicks)
        assertTrue(s.api.nextUp().isEmpty())
        // The series' own Play button resumes it.
        assertEquals(VirtualIds.of("f1", 1), s.api.nextUp(limit = 1, seriesId = "s1").single().id)
    }

    @Test
    fun severalServerEntriesForOneFilePlayTheirOwnParts() = runTest {
        val a = """{"Id":"a","Name":"Kite","Type":"Episode","SeriesId":"s1","IndexNumber":1,"ParentIndexNumber":1,"RunTimeTicks":${ticks(22 * min)},"Path":"/tv/x.mkv"}"""
        val b = """{"Id":"b","Name":"Rain Day","Type":"Episode","SeriesId":"s1","IndexNumber":2,"ParentIndexNumber":1,"RunTimeTicks":${ticks(22 * min)},"Path":"/tv/x.mkv"}"""
        val withChapters = """{"Items":[${a.dropLast(1)},"Chapters":[{"StartPositionTicks":0,"Name":"Kite"},{"StartPositionTicks":${ticks(11 * min)},"Name":"Rain Day"}]},${b.dropLast(1)},"Chapters":[]}]}"""
        val s = setup({ req ->
            when {
                req.url.encodedPath.endsWith("/Episodes") -> json("""{"Items":[$a,$b]}""")
                req.url.encodedPath == "/Items" -> json(withChapters)
                else -> json("{}")
            }
        })
        val eps = s.api.episodes("s1")
        assertEquals(listOf("a", "b"), eps.map { it.id }, "the server's own entries, no extra cards")
        assertEquals(listOf(11 * min, 11 * min), eps.map { it.runtimeMs })
        assertEquals(Window(11 * min, 22 * min), s.v.window("b"))
        // Finishing b marks b (not a) watched on the server.
        s.api.reportStopped(PlaybackReport("b", "b", null, ticks(11 * min)))
        assertTrue(s.server.requests.any { it.method == HttpMethod.Post && it.url.encodedPath == "/UserPlayedItems/b" })
        assertTrue(s.server.requests.none { it.url.encodedPath == "/UserPlayedItems/a" })
    }

    @Test
    fun manualSplitsSurviveARestartAndNeverFollowAReplacedFile() = runTest {
        val files = MemoryFileStore()
        SplitStore(files, "srv").put("f9", EpisodeSplit(ticks(22 * min), listOf(SplitPart(0, 10 * min, 5, "Five"), SplitPart(10 * min, 22 * min, 6, "Six")), manual = true, count = 2))
        val again = SplitStore(files, "srv")
        val v = VirtualEpisodes(again, LocalWatchStore(files, "p1", base = "split-watch"))
        val file = com.auroratv.jellyfin.model.BaseItemDto(id = "f9", type = "Episode", runTimeTicks = ticks(22 * min), indexNumber = 5)
        assertEquals(listOf("Five", "Six"), v.expand(listOf(file)).map { it.name })
        assertEquals(listOf(5, 6), v.expand(listOf(file)).map { it.indexNumber })
        // A server name that's only the file name names neither episode.
        val named = VirtualEpisodes(again, LocalWatchStore(files, "p1", base = "split-watch"))
        SplitStore(files, "srv")
        again.put("f8", EpisodeSplit(ticks(22 * min), listOf(SplitPart(0, 10 * min), SplitPart(10 * min, 22 * min)), count = 2))
        assertEquals(listOf<String?>(null, null), named.expand(listOf(file.copy(id = "f8", name = "Paper Town - S01E01-E02"))).map { it.name })
        // Same id, different file (another length): left alone.
        val replaced = file.copy(runTimeTicks = ticks(24 * min))
        assertEquals(listOf("f9"), v.expand(listOf(replaced)).map { it.id })
    }

    @Test
    fun playerTimelineIsTheEpisodesOwn() {
        val raw = FakeEngine()
        val e = ClippedEngine(raw)
        e.setWindow(Window(11 * min, 22 * min))
        e.load(EngineSource("u", emptyMap(), 2 * min, null, null, emptyList(), false, "t"))
        assertEquals(13 * min, raw.loaded!!.startPositionMs, "resume 2:00 into episode 2 is 13:00 in the file")
        assertEquals(22 * min, raw.loaded!!.endPositionMs, "the engine stops at the episode's end")
        raw.state.value = EngineState(positionMs = 16 * min, durationMs = 22 * min, isPlaying = true, isBuffering = false)
        assertEquals(5 * min, e.state.value.positionMs, "the timeline starts at 0:00")
        assertEquals(11 * min, e.state.value.durationMs, "an 11-minute episode")
        e.seekTo(5 * min); assertEquals(16 * min, raw.lastSeek)
        e.seekTo(-30_000); assertEquals(11 * min, raw.lastSeek, "can't seek before the episode")
        e.seekTo(60 * min); assertEquals(22 * min - 500, raw.lastSeek, "or past it")
        // Past the end (an engine that didn't stop): stopped and reported as ended.
        raw.state.value = raw.state.value.copy(positionMs = 22 * min + 400)
        assertTrue(e.checkEnd())
        assertTrue(e.state.value.isEnded)
        assertFalse(e.state.value.isPlaying)
        // A whole file is passed through untouched.
        e.setWindow(null)
        raw.state.value = EngineState(positionMs = 16 * min, durationMs = 22 * min)
        assertEquals(16 * min, e.state.value.positionMs)
    }

    @Test
    fun skipMarkersStayInsideTheEpisode() {
        val w = Window(11 * min, 22 * min)
        val ranges = listOf(
            SkipRange(SkipRange.Kind.Credits, 10 * min, 11 * min + 20_000), // episode 1's credits run 20 s into episode 2: cut
            SkipRange(SkipRange.Kind.Intro, 11 * min + 30_000, 12 * min + 30_000),
            SkipRange(SkipRange.Kind.Credits, 21 * min, 23 * min),
        )
        val clipped = PlayerController.clipRanges(ranges, w)
        assertEquals(listOf(SkipRange(SkipRange.Kind.Credits, 0, 20_000), SkipRange(SkipRange.Kind.Intro, 30_000, 90_000), SkipRange(SkipRange.Kind.Credits, 10 * min, 11 * min)), clipped)
        assertTrue(clipped.all { it.startMs >= 0 && it.endMs <= w.lengthMs }, "never into another episode")
    }

    private class FakeEngine : PlaybackEngine {
        override val name = "fake"
        override val state = MutableStateFlow(EngineState())
        var loaded: EngineSource? = null
        var lastSeek: Long? = null
        override fun load(source: EngineSource) { loaded = source }
        override fun play() {}
        override fun pause() { state.value = state.value.copy(isPlaying = false) }
        override fun seekTo(positionMs: Long) { lastSeek = positionMs }
        override fun selectAudio(ordinal: Int) {}
        override fun selectSubtitle(ref: SubtitleTrackRef?) {}
        override fun setSubtitleDelay(delayMs: Long) {}
        override fun setSubtitleScale(scale: Float) {}
        override fun stats(): List<Pair<String, String>> = emptyList()
        override fun release() {}
    }
}
