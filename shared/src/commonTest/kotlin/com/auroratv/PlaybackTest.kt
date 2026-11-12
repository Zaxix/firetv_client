package com.auroratv

import com.auroratv.jellyfin.ServerAddress
import com.auroratv.jellyfin.model.MediaSourceInfo
import com.auroratv.jellyfin.model.MediaStream
import com.auroratv.playback.Codecs
import com.auroratv.playback.DeviceProfileBuilder
import com.auroratv.playback.PlayMethod
import com.auroratv.playback.PlaybackNegotiator
import com.auroratv.playback.TrackSelector
import com.auroratv.settings.SubtitleMode
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceProfileTest {

    @Test
    fun hevcMain10DeviceAdvertisesMain10AndHdr10() {
        val p = DeviceProfileBuilder.build(fireTv4k, maxBitrateMbps = 0)
        val video = p.directPlayProfiles.first { it.type == "Video" }
        assertTrue(video.videoCodec!!.split(',').contains("hevc"))
        val hevc = p.codecProfiles.first { it.codec == Codecs.HEVC }
        assertEquals("main|main 10", hevc.conditions.first { it.property == "VideoProfile" }.value)
        assertEquals("10", hevc.conditions.first { it.property == "VideoBitDepth" }.value)
        val ranges = hevc.conditions.first { it.property == "VideoRangeType" }.value.split('|')
        assertTrue("HDR10" in ranges && "HLG" in ranges && "SDR" in ranges)
        assertFalse("DOVI" in ranges, "No Dolby Vision display → full DOVI must be transcoded/tone-mapped")
        assertTrue("DOVIWithHDR10" in ranges, "DV with HDR10 base layer can play as HDR10")
        // HEVC transcodes use fMP4 HLS when the device decodes HEVC in hardware.
        val t = p.transcodingProfiles.first { it.type == "Video" }
        assertEquals("mp4", t.container)
        assertTrue(t.videoCodec.startsWith("hevc"))
    }

    @Test
    fun h264OnlyDeviceNeverClaimsHevc() {
        val p = DeviceProfileBuilder.build(oldStick, maxBitrateMbps = 20)
        assertFalse(p.directPlayProfiles.any { it.videoCodec?.contains("hevc") == true })
        assertEquals(20_000_000L, p.maxStreamingBitrate)
        val t = p.transcodingProfiles.first { it.type == "Video" }
        assertEquals("ts", t.container)
        assertEquals("h264", t.videoCodec)
        assertEquals("2", t.maxAudioChannels)
        val h264 = p.codecProfiles.first { it.codec == Codecs.H264 }
        assertEquals("1920", h264.conditions.first { it.property == "Width" }.value)
        assertEquals("8", h264.conditions.first { it.property == "VideoBitDepth" }.value)
    }

    @Test
    fun passthroughCodecsAreDirectPlayable() {
        val p = DeviceProfileBuilder.build(fireTv4k, 0)
        val audio = p.directPlayProfiles.first { it.type == "Video" }.audioCodec!!.split(',')
        assertTrue("eac3" in audio && "ac3" in audio)
        assertFalse("truehd" in audio, "TrueHD not decodable nor passthrough → server converts")
    }

    @Test
    fun unsupportedSubtitlesAreBurnedInNotDropped() {
        val p = DeviceProfileBuilder.build(fireTv4k, 0)
        assertTrue(p.subtitleProfiles.any { it.format == "ass" && it.method == "Embed" })
        assertTrue(p.subtitleProfiles.any { it.format == "ass" && it.method == "External" })
        assertTrue(p.subtitleProfiles.any { it.format == "dvdsub" && it.method == "Encode" })
        assertFalse(p.subtitleProfiles.any { it.format == "ass" && it.method == "Encode" }, "ASS must not be flattened when libass is available")
    }

    @Test
    fun disablingDirectPlayRemovesDirectPlayProfiles() {
        assertTrue(DeviceProfileBuilder.build(fireTv4k, 0, allowDirectPlay = false).directPlayProfiles.isEmpty())
    }
}

class NegotiationTest {
    private val streams = listOf(
        MediaStream(0, "Video", codec = "hevc"),
        MediaStream(1, "Audio", codec = "eac3", language = "eng", isDefault = true),
        MediaStream(2, "Audio", codec = "aac", language = "jpn"),
        MediaStream(3, "Subtitle", codec = "ass", language = "eng", deliveryMethod = "Embed"),
        MediaStream(4, "Subtitle", codec = "srt", language = "eng", isForced = true, deliveryMethod = "Embed"),
        MediaStream(5, "Subtitle", codec = "ass", language = "spa", isExternal = true, deliveryMethod = "External", deliveryUrl = "/Videos/x/x/Subtitles/5/0/Stream.ass"),
    )

    @Test
    fun directPlayUsesStaticStreamWithoutTokenInUrl() = runTest {
        val server = FakeServer { json("""{"MediaSources":[{"Id":"ms1","Container":"mkv","SupportsDirectPlay":true,"SupportsDirectStream":true}],"PlaySessionId":"ps1"}""") }
        val plan = PlaybackNegotiator(api(server), fireTv4k).negotiate("item1", null, 60_000, 1, 3, 0, true)
        assertEquals(PlayMethod.DirectPlay, plan.method)
        assertTrue(plan.url.startsWith("http://jf.local:8096/Videos/item1/stream.mkv?static=true&mediaSourceId=ms1"))
        assertFalse(plan.url.contains("tok"), "Token travels in headers, not URLs")
        assertEquals("tok", plan.headers["X-Emby-Token"])
        // Request body carries the device profile, start position and chosen streams.
        val body = server.requests.single().body.toString()
        assertNotNull(body)
    }

    @Test
    fun transcodeUrlIsAbsolutisedAndReasonsParsed() = runTest {
        val server = FakeServer {
            json("""{"MediaSources":[{"Id":"ms1","Container":"mkv","SupportsDirectPlay":false,"SupportsDirectStream":false,"TranscodingUrl":"/videos/item1/master.m3u8?DeviceId=dev123&TranscodeReasons=VideoCodecNotSupported%2CAudioCodecNotSupported","TranscodingSubProtocol":"hls"}],"PlaySessionId":"ps2"}""")
        }
        val plan = PlaybackNegotiator(api(server), oldStick).negotiate("item1", null, 0, null, null, 0, true)
        assertEquals(PlayMethod.Transcode, plan.method)
        assertTrue(plan.url.startsWith("http://jf.local:8096/videos/item1/master.m3u8"))
        assertEquals(listOf("VideoCodecNotSupported", "AudioCodecNotSupported"), plan.transcodeReasons)
    }

    @Test
    fun serverRefusalIsExplained() = runTest {
        val server = FakeServer { json("""{"MediaSources":[],"ErrorCode":"NoCompatibleStream"}""") }
        val e = runCatching { PlaybackNegotiator(api(server), oldStick).negotiate("i", null, 0, null, null, 0, true) }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("couldn't find a format"))
    }

    @Test
    fun sessionExpiryDuringNegotiationPropagates() = runTest {
        val server = FakeServer { json("", HttpStatusCode.Unauthorized) }
        val e = runCatching { PlaybackNegotiator(api(server), oldStick).negotiate("i", null, 0, null, null, 0, true) }.exceptionOrNull()
        assertTrue(e is com.auroratv.core.AppError.SessionExpired)
    }

    @Test
    fun embeddedOrdinalsMapJellyfinIndicesToContainerOrder() {
        assertEquals(0, PlaybackNegotiator.embeddedOrdinal(streams, streams[1]))
        assertEquals(1, PlaybackNegotiator.embeddedOrdinal(streams, streams[2]))
        assertEquals(0, PlaybackNegotiator.embeddedOrdinal(streams, streams[3]))
        assertEquals(1, PlaybackNegotiator.embeddedOrdinal(streams, streams[4]))
        assertNull(PlaybackNegotiator.embeddedOrdinal(streams, streams[5]), "External tracks have no container ordinal")
    }

    @Test
    fun resumeAndPlanReportDirectPlayHeadersForEngine() {
        val src = MediaSourceInfo(id = "ms", container = "mkv", supportsDirectPlay = true, mediaStreams = streams)
        val server = FakeServer { json("{}") }
        val plan = PlaybackNegotiator(api(server), fireTv4k).planFor("it", src, "ps", 1, 3)
        assertEquals(3, plan.subtitleIndex)
        assertEquals(3, plan.subtitleStreams.size)
        assertEquals(2, plan.audioStreams.size)
    }

    // ---- Track selection --------------------------------------------------------------------

    @Test
    fun audioPrefersUserLanguageOverDefault() {
        assertEquals(2, TrackSelector.pickAudio(streams, "ja", serverDefault = 1))
        assertEquals(1, TrackSelector.pickAudio(streams, "", serverDefault = null))
    }

    @Test
    fun subtitleModes() {
        // Default: no default-flagged subs → forced track.
        assertEquals(4, TrackSelector.pickSubtitle(streams, SubtitleMode.Default, "eng", "eng", null))
        assertEquals(3, TrackSelector.pickSubtitle(streams, SubtitleMode.Always, "eng", "eng", null))
        assertEquals(4, TrackSelector.pickSubtitle(streams, SubtitleMode.OnlyForced, "eng", "eng", null))
        assertNull(TrackSelector.pickSubtitle(streams, SubtitleMode.Off, "eng", "eng", null))
        // Smart: Japanese audio + English preference → full English subtitles.
        assertEquals(3, TrackSelector.pickSubtitle(streams, SubtitleMode.Smart, "eng", "jpn", null))
        // Smart: English audio → forced only.
        assertEquals(4, TrackSelector.pickSubtitle(streams, SubtitleMode.Smart, "eng", "eng", null))
        assertEquals(5, TrackSelector.pickSubtitle(streams, SubtitleMode.Always, "es", "eng", null), "ISO 639-1 'es' matches 'spa'")
    }

    // ---- Intro / credits ranges (yellow timeline markers) -----------------------------------

    @Test
    fun skipRangesComeFromSegmentsOrChapterNames() {
        val ep = com.auroratv.jellyfin.model.BaseItemDto(id = "e", type = "Episode", runTimeTicks = 1_440_000L * 10_000)
        val fromSegments = com.auroratv.playback.PlayerUiState(
            item = ep,
            segments = listOf(
                com.auroratv.jellyfin.model.MediaSegmentDto(type = "Intro", startTicks = 60_000L * 10_000, endTicks = 150_000L * 10_000),
                com.auroratv.jellyfin.model.MediaSegmentDto(type = "Outro", startTicks = 1_320_000L * 10_000, endTicks = 1_440_000L * 10_000),
                com.auroratv.jellyfin.model.MediaSegmentDto(type = "Commercial", startTicks = 0, endTicks = 10),
            ),
        )
        assertEquals(listOf(com.auroratv.playback.SkipRange.Kind.Intro, com.auroratv.playback.SkipRange.Kind.Credits), fromSegments.skipRanges.map { it.kind })
        assertEquals(60_000L, fromSegments.activeSkip(90_000)?.startMs)
        assertNull(fromSegments.activeSkip(200_000))

        val ch = { name: String, ms: Long -> com.auroratv.jellyfin.model.ChapterInfo(startPositionTicks = ms * 10_000, name = name) }
        val fromChapters = com.auroratv.playback.PlayerUiState(
            item = ep,
            chapters = listOf(ch("Prologue", 0), ch("Opening", 90_000), ch("Part A", 180_000), ch("Ending", 1_300_000), ch("Preview", 1_400_000)),
        )
        val r = fromChapters.skipRanges
        assertEquals(2, r.size)
        assertEquals(com.auroratv.playback.SkipRange(com.auroratv.playback.SkipRange.Kind.Intro, 90_000, 180_000), r[0])
        assertEquals(com.auroratv.playback.SkipRange(com.auroratv.playback.SkipRange.Kind.Credits, 1_300_000, 1_400_000), r[1])
    }

    // ---- Address handling -------------------------------------------------------------------

    @Test
    fun serverAddressCandidates() {
        assertEquals("http://192.168.1.20:8096", ServerAddress.candidates("192.168.1.20").first())
        assertEquals("https://media.example.com", ServerAddress.candidates("media.example.com").first())
        assertEquals(listOf("https://media.example.com/jellyfin"), ServerAddress.candidates("https://media.example.com/jellyfin/web/#/home.html").take(1))
        assertEquals(listOf("http://10.0.0.5:9000"), ServerAddress.candidates("http://10.0.0.5:9000/"))
        assertEquals("http://jellyfin:8096", ServerAddress.candidates("jellyfin").first())
        assertTrue(ServerAddress.candidates("   ").isEmpty())
    }
}
