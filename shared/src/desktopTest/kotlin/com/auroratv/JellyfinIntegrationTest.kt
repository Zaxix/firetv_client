package com.auroratv

import com.auroratv.core.AppError
import com.auroratv.data.SessionManager
import com.auroratv.data.SessionState
import com.auroratv.data.UserSession
import com.auroratv.jellyfin.ItemQuery
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.createJellyfinHttpClient
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.PlaybackReport
import com.auroratv.jellyfin.model.msToTicks
import com.auroratv.playback.Codecs
import com.auroratv.playback.DeviceCapabilities
import com.auroratv.playback.PlayMethod
import com.auroratv.playback.PlaybackNegotiator
import com.auroratv.playback.PlaybackPlan
import com.auroratv.playback.TrackSelector
import com.auroratv.playback.VideoCodecSupport
import com.auroratv.settings.SubtitleMode
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end tests against a real Jellyfin server (CI starts one in Docker with generated media).
 * Skipped unless AURORA_IT_URL is set. Writes a Markdown report used in docs/TEST_RESULTS.md.
 */
class JellyfinIntegrationTest {
    private val url = System.getenv("AURORA_IT_URL")
    private val report = StringBuilder()
    private val failures = mutableListOf<String>()

    /** Runs one check, records it in the report and keeps going so the report shows every result. */
    private fun record(name: String, block: suspend () -> String) {
        if (url == null) return
        val result = runCatching { runBlocking { block() } }
        val line = result.fold({ "| ✅ | $name | $it |" }, { "| ❌ | $name | ${it::class.simpleName}: ${it.message?.take(200)} |" })
        report.appendLine(line)
        println(line)
        result.exceptionOrNull()?.let { failures += name; it.printStackTrace() }
    }

    private lateinit var session: UserSession
    private val api: JellyfinApi get() = session.api

    private suspend fun movie(name: String): BaseItemDto =
        api.items(ItemQuery(includeItemTypes = listOf("Movie"), searchTerm = name, limit = 5)).items.firstOrNull { it.name == name }
            ?.let { api.item(it.id) } ?: fail("Movie '$name' not found")

    private suspend fun plan(item: BaseItemDto, caps: DeviceCapabilities, audio: Int? = null, sub: Int? = null): PlaybackPlan =
        PlaybackNegotiator(api, caps).negotiate(item.id, item.mediaSources.first().id, 0, audio, sub, 0, true)

    private val sdr4k = fireTv4k.copy(hdr = emptySet())
    private val noLibass = oldStick.copy(subtitleEmbedded = setOf("srt", "subrip"), subtitleExternal = setOf("srt", "subrip", "vtt"), libassRendering = false)
    private val mpvDesktop = fireTv4k.copy(
        video = listOf(Codecs.H264, Codecs.HEVC, Codecs.VP9, Codecs.AV1, Codecs.MPEG2, Codecs.MPEG4).map { VideoCodecSupport(it, true, 7680, 4320, 12) },
        canToneMapHdr = true,
        audioDecode = setOf("aac", "mp3", "ac3", "eac3", "dts", "truehd", "flac", "opus"),
        containers = setOf("mkv", "mp4", "avi", "ts", "mov", "webm"),
    )

    @Test
    fun realServer() {
        if (url == null) { println("AURORA_IT_URL not set; skipping integration tests"); return }
        report.appendLine("| | Check | Result |").appendLine("|---|---|---|")
        try {
            run()
        } finally {
            File(System.getenv("AURORA_IT_REPORT") ?: "build/integration-report.md").apply { parentFile?.mkdirs() }.writeText(report.toString())
        }
        if (failures.isNotEmpty()) fail("Failed checks: ${failures.joinToString()}")
    }

    private fun run() {
        val http = createJellyfinHttpClient()
        val files = MemoryFileStore(); val vault = MemoryVault()
        val sm = SessionManager(http, files, vault, "Aurora CI")

        record("Server discovery from bare host") {
            val (base, info) = sm.discover(url!!.removePrefix("http://"))
            sm.addServer(base, info)
            "found Jellyfin ${info.version} at $base"
        }
        record("Invalid credentials rejected") {
            assertFailsWith<AppError.InvalidCredentials> { sm.login(sm.servers.value.first(), "aurora", "wrong-password") }
            "InvalidCredentials"
        }
        record("Authentication + secure token storage") {
            session = sm.login(sm.servers.value.first(), System.getenv("AURORA_IT_USER") ?: "aurora", System.getenv("AURORA_IT_PASSWORD") ?: "aurora-ci")
            assertTrue(vault.data.keys.any { it.startsWith("token:") })
            assertTrue(files.data.values.none { it.contains(api.accessToken!!) })
            "signed in as ${session.user.name}; token only in vault"
        }
        record("Session restore after relaunch") {
            val sm2 = SessionManager(http, files, vault, "Aurora CI"); sm2.restore()
            assertTrue(sm2.state.value is SessionState.Ready); "restored"
        }
        record("Expired/invalid token detected") {
            val bad = JellyfinApi(http, api.baseUrl, sm.identity, "not-a-token", api.userId)
            assertFailsWith<AppError.SessionExpired> { bad.userViews() }; "SessionExpired"
        }
        record("Libraries") {
            val views = api.userViews().map { "${it.name}(${it.collectionType})" }
            assertTrue(views.any { it.contains("movies") } && views.any { it.contains("tvshows") }); views.joinToString()
        }
        record("Library pagination") {
            val p1 = api.items(ItemQuery(includeItemTypes = listOf("Movie"), limit = 2, startIndex = 0))
            val p2 = api.items(ItemQuery(includeItemTypes = listOf("Movie"), limit = 2, startIndex = 2))
            val p3 = api.items(ItemQuery(includeItemTypes = listOf("Movie"), limit = 2, startIndex = 4))
            val all = (p1.items + p2.items + p3.items).map { it.id }
            assertEquals(5, p1.totalRecordCount); assertEquals(5, all.distinct().size)
            "5 movies over 3 pages of 2, no duplicates"
        }
        record("Search") {
            val r = api.items(ItemQuery(searchTerm = "meridian", includeItemTypes = listOf("Movie", "Series", "Episode")))
            assertEquals("Glass Meridian", r.items.firstOrNull()?.name); "'meridian' → Glass Meridian"
        }
        record("Metadata + artwork from server") {
            val m = movie("Glass Meridian")
            assertNotNull(m.overview); assertTrue(m.genres.isNotEmpty())
            assertTrue("Primary" in m.imageTags && m.backdropImageTags.isNotEmpty())
            val poster = api.images.poster(m, 300)!!
            val bytes = http.get(poster.url).let { it.status.value to it.headers["Content-Type"] }
            assertEquals(200, bytes.first)
            "overview, ${m.genres.size} genres, poster ${bytes.second}, blurhash=${poster.blurHash != null}"
        }

        // ---- Playback negotiation ----
        record("HEVC Main10 + ASS → Direct Play on HEVC-capable Fire TV profile") {
            val m = movie("Glass Meridian")
            val v = m.mediaSources.first().videoStream!!
            val sub = m.mediaSources.first().subtitleStreams.first()
            val p = plan(m, fireTv4k, sub = sub.index)
            assertEquals(PlayMethod.DirectPlay, p.method)
            assertEquals("Embed", p.subtitleStreams.first().deliveryMethod)
            "${v.codec} ${v.profile} ${v.bitDepth}-bit → ${p.method}; ASS delivery=Embed"
        }
        record("HEVC Main10 → transcode on H.264-only device (fallback)") {
            val m = movie("Glass Meridian")
            val p = plan(m, oldStick)
            assertTrue(p.method != PlayMethod.DirectPlay); assertTrue(p.url.contains(".m3u8"))
            "${p.method}; reasons=${p.transcodeReasons}"
        }
        record("ASS kept as styled ASS (External) when transcoding with libass") {
            val m = movie("Glass Meridian")
            val sub = m.mediaSources.first().subtitleStreams.first()
            val p = plan(m, oldStick, sub = sub.index)
            val s = p.subtitleStreams.first { it.index == sub.index }
            assertEquals("External", s.deliveryMethod); assertTrue(s.deliveryUrl!!.contains(".ass", ignoreCase = true))
            val body = http.get(api.absolute(s.deliveryUrl!!)) { header("X-Emby-Token", api.accessToken) }.bodyAsText()
            assertTrue(body.contains("[V4+ Styles]") && body.contains("\\k"), "styling + karaoke tags preserved")
            "delivered as ${s.deliveryUrl!!.substringAfterLast('/').substringBefore('?')} with styles + \\k tags intact"
        }
        record("ASS burned in by server when the device has no ASS renderer") {
            val m = movie("Glass Meridian")
            val sub = m.mediaSources.first().subtitleStreams.first()
            val p = plan(m, noLibass, sub = sub.index)
            assertEquals("Encode", p.subtitleStreams.first { it.index == sub.index }.deliveryMethod)
            "${p.method}; ASS delivery=Encode (burn-in, never flattened to SRT)"
        }
        record("4K HDR10 → Direct Play on HDR10 display") {
            val m = movie("Ember Field")
            val v = m.mediaSources.first().videoStream!!
            assertEquals("HDR10", v.videoRangeType, "server must detect the sample as HDR10 (range=${v.videoRange})")
            val p = plan(m, fireTv4k)
            assertEquals(PlayMethod.DirectPlay, p.method)
            "${v.width}x${v.height} ${v.videoRangeType} → ${p.method}"
        }
        record("4K HDR10 → server tone-mapping on SDR-only display") {
            val p = plan(movie("Ember Field"), sdr4k)
            assertTrue(p.method != PlayMethod.DirectPlay)
            "${p.method}; reasons=${p.transcodeReasons}"
        }
        record("4K HDR10 → Direct Play on Windows/mpv (tone-maps locally)") {
            assertEquals(PlayMethod.DirectPlay, plan(movie("Ember Field"), mpvDesktop).method); "DirectPlay"
        }
        record("MPEG-4/AVI → transcode on Fire TV, Direct Play on mpv") {
            val m = movie("Old Reel")
            val tv = plan(m, fireTv4k); val pc = plan(m, mpvDesktop)
            assertTrue(tv.method != PlayMethod.DirectPlay); assertEquals(PlayMethod.DirectPlay, pc.method)
            "Fire TV=${tv.method} (${tv.transcodeReasons.joinToString()}), Windows=${pc.method}"
        }
        record("Multi-track MKV: audio/subtitle selection") {
            val m = movie("Seven Rivers")
            val streams = m.mediaSources.first().mediaStreams
            val audio = streams.filter { it.isAudio }; val subs = streams.filter { it.isSubtitle }
            assertEquals(3, audio.size); assertEquals(3, subs.size)
            val jpn = TrackSelector.pickAudio(streams, "ja", null)
            assertEquals("jpn", streams.first { it.index == jpn }.language)
            val forced = TrackSelector.pickSubtitle(streams, SubtitleMode.OnlyForced, "eng", "eng", null)
            assertTrue(streams.first { it.index == forced }.isForced)
            val smart = TrackSelector.pickSubtitle(streams, SubtitleMode.Smart, "eng", "jpn", null)
            assertEquals("English Full", streams.first { it.index == smart }.title)
            val p = plan(m, fireTv4k, audio = jpn, sub = smart)
            assertEquals(PlayMethod.DirectPlay, p.method)
            "3 audio (${audio.joinToString { it.codec.orEmpty() }}), 3 subs; ja→jpn, forced→${streams.first { it.index == forced }.title}, smart→English Full; ${p.method}"
        }
        record("External SRT sidecar detected") {
            val subs = movie("Harbor Lights").mediaSources.first().subtitleStreams
            assertTrue(subs.any { it.isExternal && it.codec == "srt" })
            "${subs.size} subtitle streams, external=${subs.count { it.isExternal }}"
        }
        record("Direct stream authenticates via header (no token in URL)") {
            val m = movie("Harbor Lights")
            val p = plan(m, fireTv4k)
            assertTrue(!p.url.contains(api.accessToken!!))
            val r = http.get(p.url) { p.headers.forEach { (k, v) -> header(k, v) }; header("Range", "bytes=0-4095") }
            assertTrue(r.status.value == 206 || r.status.value == 200)
            "HTTP ${r.status.value}, ${r.headers["Content-Length"]} bytes"
        }
        record("Transcode playlist reachable") {
            val p = plan(movie("Old Reel"), fireTv4k)
            val body = http.get(p.url) { p.headers.forEach { (k, v) -> header(k, v) } }.bodyAsText()
            assertTrue(body.startsWith("#EXTM3U")); p.playSessionId?.let { api.stopTranscoding(it) }
            "#EXTM3U master playlist"
        }

        // ---- Progress sync ----
        record("Resume position + watched state sync") {
            val m = movie("Harbor Lights")
            val p = plan(m, fireTv4k)
            val base = PlaybackReport(itemId = m.id, mediaSourceId = p.mediaSource.id, playSessionId = p.playSessionId, playMethod = p.method.jellyfinName)
            api.reportStart(base)
            api.reportProgress(base.copy(positionTicks = 12_000L.msToTicks(), isPaused = true))
            api.reportStopped(base.copy(positionTicks = 12_000L.msToTicks()))
            val resumeMs = api.item(m.id).resumePositionMs
            assertTrue(resumeMs in 11_000..13_000, "resume=$resumeMs")
            assertTrue(api.resumeItems().any { it.id == m.id })
            api.setPlayed(m.id, true); assertTrue(api.item(m.id).userData!!.played)
            api.setPlayed(m.id, false); assertTrue(!api.item(m.id).userData!!.played)
            "resume at ${resumeMs / 1000}s, in Continue Watching; watched toggled on/off"
        }
        record("Favorites") {
            val m = movie("Seven Rivers")
            api.setFavorite(m.id, true)
            assertTrue(api.items(ItemQuery(isFavorite = true, includeItemTypes = listOf("Movie"))).items.any { it.id == m.id })
            api.setFavorite(m.id, false)
            assertTrue(api.items(ItemQuery(isFavorite = true, includeItemTypes = listOf("Movie"))).items.none { it.id == m.id })
            "added + removed"
        }
        record("Seasons, episodes and Next Up") {
            val series = api.items(ItemQuery(includeItemTypes = listOf("Series"))).items.first()
            val seasons = api.seasons(series.id); val eps = api.episodes(series.id)
            assertEquals(3, eps.size)
            api.setPlayed(eps[0].id, true)
            val next = api.nextUp(seriesId = series.id).firstOrNull()
            assertEquals(eps[1].id, next?.id)
            val after = api.episodes(series.id, startItemId = eps[1].id, limit = 2).firstOrNull { it.id != eps[1].id }
            assertEquals(eps[2].id, after?.id)
            api.setPlayed(eps[0].id, false)
            "${series.name}: ${seasons.size} season, ${eps.size} episodes; Next Up after E1 = ${next?.episodeLabel}; following = ${after?.episodeLabel}"
        }
        record("Media segments (Skip Intro) endpoint") {
            val ep = api.items(ItemQuery(includeItemTypes = listOf("Episode"), limit = 1)).items.first()
            "${api.mediaSegments(ep.id).size} segments (no intro-detection plugin on CI server)"
        }
    }
}
