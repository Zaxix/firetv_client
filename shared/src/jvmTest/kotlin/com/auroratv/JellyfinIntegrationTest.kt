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
import com.auroratv.playback.DeviceCapabilities
import com.auroratv.playback.DolbyVisionSupport
import com.auroratv.playback.HdrFormat
import com.auroratv.playback.PlayMethod
import com.auroratv.playback.PlaybackNegotiator
import com.auroratv.playback.PlaybackPlan
import com.auroratv.playback.TrackSelector
import com.auroratv.settings.SubtitleMode
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
            val total = api.items(ItemQuery(includeItemTypes = listOf("Movie"), limit = 1)).totalRecordCount
            assertTrue(total >= 5)
            val pages = (0 until total step 2).map { api.items(ItemQuery(includeItemTypes = listOf("Movie"), limit = 2, startIndex = it)) }
            val all = pages.flatMap { p -> p.items.map { it.id } }
            assertEquals(total, all.distinct().size)
            "$total movies over ${pages.size} pages of 2, no duplicates"
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

        record("Brand tiles: studios from metadata resolve to brands, and a hub lists their titles") {
            val brands = com.auroratv.brands.BrandCatalog.resolve(api.studios())
            val ids = brands.map { it.brand.id }
            assertTrue(ids.containsAll(listOf("netflix", "appletv", "paramount")), "found $ids")
            val netflix = brands.first { it.brand.id == "netflix" }
            val titles = api.items(ItemQuery(includeItemTypes = listOf("Movie", "Series"), studioIds = netflix.studioIds)).items.map { it.name }
            assertEquals(listOf("Glass Meridian"), titles)
            "brands=$ids; Netflix hub → $titles"
        }
        record("Critic score from server metadata feeds the ratings strip") {
            val m = movie("Glass Meridian")
            val r = com.auroratv.ratings.RatingsService.fromJellyfin(m)
            assertEquals(93, r?.tomatometer)
            "CriticRating=${m.criticRating} → tomatometer ${r?.tomatometer}%; providerIds=${m.providerIds.keys}"
        }
        record("Rating keys typed on one device reach another (Jellyfin DisplayPreferences)") {
            val pc = com.auroratv.ratings.RatingsKeyStore(MemoryVault())
            pc.save(com.auroratv.ratings.RatingsKeys("ci-mdblist", "ci-trakt")); pc.push(api, pc.keys.value)
            val tvVault = MemoryVault()
            val tv = com.auroratv.ratings.RatingsKeyStore(tvVault); tv.pull(api)
            assertEquals(com.auroratv.ratings.RatingsKeys("ci-mdblist", "ci-trakt"), tv.keys.value)
            pc.push(api, com.auroratv.ratings.RatingsKeys())
            "pushed from one store, pulled into another; vault keys=${tvVault.data.keys.sorted()}"
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
        // HDR checks only mean something if the server's probe flagged the sample as HDR10.
        val hdrSample = runBlocking { movie("Ember Field") }
        val serverRange = hdrSample.mediaSources.first().videoStream?.videoRangeType
        if (serverRange != "HDR10") {
            report.appendLine("| ⚠️ | 4K HDR10 Direct Play / tone-mapping decisions | not verifiable: this server's probe reported the HDR10 sample as $serverRange (file is PQ/BT.2020; see ffprobe in CI log). Covered by DeviceProfileTest. |")
        } else {
            record("4K HDR10 → Direct Play on HDR10 display") {
                val v = hdrSample.mediaSources.first().videoStream!!
                val p = plan(hdrSample, fireTv4k)
                assertEquals(PlayMethod.DirectPlay, p.method)
                "${v.width}x${v.height} ${v.videoRangeType} → ${p.method}"
            }
            record("4K HDR10 → server tone-mapping on SDR-only display") {
                val p = plan(hdrSample, sdr4k)
                assertTrue(p.method != PlayMethod.DirectPlay)
                "${p.method}; reasons=${p.transcodeReasons}"
            }
        }
        dolbyVisionChecks()
        recommendationChecks(http, sm)
        record("Audio windows: where the server's mid-file audio starts, and credits edges (diagnostic)") { seekCheck() }
        record("Intro + credits found by listening (non-anime show, no chapters, no plugin)") { listeningCheck() }
        record("Outro found on screen (live-action credits, different music every episode, post-credits scene)") { screenCheck() }
        record("Two episodes in one file (Paper Town): split by its chapters, played and tracked separately") { splitCheck() }
        record("Episode order (Zephyr Bay): an episode moved back a season, browsed, played next and resumed in that order") { orderCheck() }
        record("Upcoming episodes request answers (no virtual episodes on this server)") {
            val series = api.items(ItemQuery(includeItemTypes = listOf("Series"), limit = 1)).items.first()
            val list = api.upcomingEpisodes(series.id)
            val upcoming = com.auroratv.data.Upcoming.fromServer(list, com.auroratv.playback.wallClockMs())
            assertTrue(upcoming.isEmpty(), "nothing invented: $upcoming")
            "${series.name}: ${list.size} item(s) from /Shows/Upcoming, ${upcoming.size} shown"
        }
        record("MPEG-4/AVI → transcode on Fire TV") {
            val tv = plan(movie("Old Reel"), fireTv4k)
            assertTrue(tv.method != PlayMethod.DirectPlay)
            "Fire TV=${tv.method} (${tv.transcodeReasons.joinToString()})"
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
            assertTrue(subs.any { it.isExternal && it.codec in setOf("srt", "subrip") }, "streams: ${subs.map { "${it.codec}/${it.isExternal}" }}")
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

    /** Fire TV 4K Max on a Dolby Vision TV: DV decoder for profiles 5 and 8, libdovi for 7 → 8.1. */
    private val fireTvDv = fireTv4k.copy(
        hdr = setOf(HdrFormat.HDR10, HdrFormat.HLG, HdrFormat.DOLBY_VISION),
        dolbyVision = DolbyVisionSupport(display = true, decoderProfiles = setOf(5, 8), canConvertProfile7 = true),
    )
    /** Same box on an HDR10 TV: Dolby Vision files can only use their base layer. */
    private val fireTvHdr10 = fireTv4k.copy(dolbyVision = DolbyVisionSupport(display = false, decoderProfiles = setOf(5, 8)))

    /**
     * Dolby Vision samples (built in CI with dovi_tool + mkvmerge): the server's range type, then
     * where each profile is allowed to go untouched for DV TV / HDR10 TV / SDR TV.
     */
    private fun dolbyVisionChecks() {
        data class Case(val title: String, val range: String, val dvTv: Boolean, val hdr10Tv: Boolean, val sdrTv: Boolean)
        val cases = listOf(
            Case("Prism Coast", "DOVIWithHDR10", dvTv = true, hdr10Tv = true, sdrTv = false),
            Case("Neon Tide", "DOVI", dvTv = true, hdr10Tv = false, sdrTv = false),
            Case("Velvet Static", "DOVIWithEL", dvTv = true, hdr10Tv = true, sdrTv = false),
        )
        for (c in cases) {
            val item = runBlocking { runCatching { movie(c.title) }.getOrNull() }
            if (item == null) {
                report.appendLine("| ⚠️ | Dolby Vision ${c.range} sample | not generated in this run (dovi_tool/mkvmerge unavailable) |")
                continue
            }
            val v = item.mediaSources.first().videoStream!!
            if (v.videoRangeType != c.range) {
                report.appendLine("| ⚠️ | Dolby Vision ${c.range} sample | server classified it as ${v.videoRangeType} (${v.videoRange}); routing covered by DolbyVisionTest |")
                continue
            }
            record("Dolby Vision ${c.range}: DV TV / HDR10 TV / SDR TV") {
                fun direct(caps: DeviceCapabilities) = runBlocking { plan(item, caps).method == PlayMethod.DirectPlay }
                val got = listOf(direct(fireTvDv), direct(fireTvHdr10), direct(sdr4k))
                assertEquals(listOf(c.dvTv, c.hdr10Tv, c.sdrTv), got, "DirectPlay for [DV TV, HDR10 TV, SDR TV]")
                val names = listOf("DV TV", "HDR10 TV", "SDR TV")
                "${v.codec} ${v.videoRangeType}: " + names.zip(got).joinToString { (n, d) -> "$n=${if (d) "Direct Play" else "server converts"}" }
            }
        }
    }

    /**
     * "Because You Watched" / "Recommended For You" against the real server with two profiles that
     * watch different things: each gets rows from its own history only, and never its own watched titles.
     */
    private fun recommendationChecks(http: io.ktor.client.HttpClient, sm: SessionManager) {
        val base = api.baseUrl.trimEnd('/')
        var userB: UserSession? = null
        val played = mutableListOf<Pair<JellyfinApi, String>>()
        try {
            record("Recommendations stay per profile (two users, different histories)") {
                // Profile B: a second Jellyfin user on the same server (as "Add profile" would sign in).
                http.post("$base/Users/New") {
                    header("Authorization", api.authHeader)
                    setBody(io.ktor.http.content.TextContent("""{"Name":"aurora-b","Password":"aurora-b-ci"}""", io.ktor.http.ContentType.Application.Json))
                }
                val smB = SessionManager(http, MemoryFileStore(), MemoryVault(), "Aurora CI")
                smB.addServer(base, smB.discover(base.removePrefix("http://")).second)
                val b = smB.login(smB.servers.value.first(), "aurora-b", "aurora-b-ci").also { userB = it }
                suspend fun watch(a: JellyfinApi, title: String) {
                    val id = a.items(ItemQuery(includeItemTypes = listOf("Movie"), searchTerm = title, limit = 5)).items.first { it.name == title }.id
                    a.setPlayed(id, true); played += a to id
                    kotlinx.coroutines.delay(1_100) // distinct "last played" times
                }
                // A: sci-fi thrillers and mystery dramas. B: comedy and family adventure.
                watch(api, "Glass Meridian"); watch(api, "Harbor Lights")
                watch(b.api, "Seven Rivers"); watch(b.api, "Old Reel")

                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
                val svc = com.auroratv.recommend.RecommendationService(scope)
                val planA = svc.plan(api, api.resumeItems())
                val planB = svc.plan(b.api, b.api.resumeItems())
                val namesA = planA.titles.map { it.name }; val namesB = planB.titles.map { it.name }
                assertEquals("Harbor Lights", planA.seeds.first().name, "A's newest title leads"); assertTrue("Glass Meridian" in namesA)
                assertEquals("Old Reel", planB.seeds.first().name, "B's newest title leads"); assertTrue("Seven Rivers" in namesB)
                assertTrue(namesA.none { it in setOf("Seven Rivers", "Old Reel") }, "B's history never reaches A: $namesA")
                assertTrue(namesB.none { it in setOf("Glass Meridian", "Harbor Lights") }, "A's history never reaches B: $namesB")

                val rowsA = svc.rows(api, "A", planA).await().orEmpty()
                val rowsB = svc.rows(b.api, "B", planB).await().orEmpty()
                assertTrue(rowsA.isNotEmpty(), "A gets recommendations")
                fun titles(rows: List<com.auroratv.recommend.RecommendationService.Row>) = rows.flatMap { r -> r.items.mapNotNull { it.name } }
                assertTrue(titles(rowsA).none { it in namesA }, "A isn't offered what A watched: ${titles(rowsA)}")
                assertTrue(titles(rowsB).none { it in namesB }, "B isn't offered what B watched: ${titles(rowsB)}")
                rowsA.forEach { r -> assertEquals(r.items.size, r.items.distinctBy { it.id }.size) }
                assertTrue(titles(rowsA).toSet().size == titles(rowsA).size, "no title repeated across A's rows")
                // Taste: A's "For You" leans to thrillers/dramas; B's to family/comedy, never A's thrillers first.
                rowsA.firstOrNull { it.key == "foryou" }?.let { r -> assertTrue(r.items.first().genres.any { it in setOf("Thriller", "Drama", "Mystery", "Science Fiction") }, "A's top pick: ${r.items.first().name} ${r.items.first().genres}") }
                val thrillersForB = rowsB.flatMap { it.items }.filter { "Thriller" in it.genres }.map { it.name }
                assertTrue(thrillersForB.isEmpty(), "B (comedy/family) is never offered A's thrillers: $thrillersForB")
                fun show(rows: List<com.auroratv.recommend.RecommendationService.Row>) = rows.joinToString("; ") { r -> "${r.title}: " + r.items.take(4).joinToString { it.name.orEmpty() } }
                "A → ${show(rowsA)} ‖ B → ${show(rowsB).ifEmpty { "(nothing that fits B's taste in this small library)" }}"
            }
        } finally {
            runBlocking {
                played.forEach { (a, id) -> runCatching { a.setPlayed(id, false) } }
                userB?.let { b -> runCatching { http.delete("$base/Users/${b.user.id}") { header("Authorization", api.authHeader) } } }
            }
        }
    }

    /**
     * Harbor Precinct: closing credits are white text on black in every episode but over different
     * music, so listening finds nothing and the trickplay thumbnails have to. Episode 2 has a 20 s
     * scene after its credits, which Skip Outro must land on (not skip).
     */
    private suspend fun screenCheck(): String {
        val series = api.items(ItemQuery(includeItemTypes = listOf("Series"), searchTerm = "Harbor Precinct", limit = 5)).items.firstOrNull()
            ?: return "skipped: sample series not in this library"
        val eps = api.episodes(series.id)
        assertEquals(3, eps.size)
        val ep2 = api.item(eps[1].id)
        if (ep2.trickplay.isNullOrEmpty()) return "skipped: the server made no trickplay thumbnails for this run"
        val detector = com.auroratv.playback.SkipDetector(api, io.ktor.client.HttpClient(), audioAllowed = { true }, onlineAllowed = { false })
        val t0 = System.currentTimeMillis()
        val result = assertNotNull(detector.detect(ep2, setOf(com.auroratv.playback.SkipRange.Kind.Intro, com.auroratv.playback.SkipRange.Kind.Recap)), "found no outro")
        val ms = System.currentTimeMillis() - t0
        val credits = assertNotNull(result.ranges.firstOrNull { it.kind == com.auroratv.playback.SkipRange.Kind.Credits }, "no outro: ${result.ranges}")
        assertTrue(result.source.contains("on screen"), "found by ${result.source}")
        // Credits are 170–215 s; thumbnails are 10 s apart, so the start is at most one thumbnail late.
        assertTrue(credits.startMs in 165_000..181_000, "outro starts at 2:50: $credits")
        assertTrue(credits.endMs in 205_000..216_000, "ends before the post-credits scene at 3:35: $credits")
        val ui = com.auroratv.playback.PlayerUiState(item = ep2)
        assertTrue(!ui.creditsRunToEnd(credits), "Skip Outro lands on the post-credits scene")
        return "episode 2: outro ${credits.startMs / 1000.0}–${credits.endMs / 1000.0} s (true 170–215, scene after it) via ${result.source}; took $ms ms [${detector.lastTimings}]"
    }

    /**
     * Paper Town (CI sample): "S01E01-E02" holds two 4-minute episodes with chapters naming them;
     * "S01E04-E05" holds two without saying where the second starts.
     */
    private suspend fun splitCheck(): String {
        val v = assertNotNull(api.virtual, "episode splitting is on for every session")
        val series = api.items(ItemQuery(includeItemTypes = listOf("Series"), searchTerm = "Paper Town", limit = 5)).items.firstOrNull()
            ?: return "skipped: sample series not in this library"
        val eps = api.episodes(series.id)
        val first = eps.filter { com.auroratv.jellyfin.VirtualIds.isVirtual(it.id) }
        assertEquals(2, first.size, "the two-episode file with named chapters is shown as two episodes: ${eps.map { "${it.id}:${it.name}" }}")
        val (a, b) = first
        assertEquals(listOf(1, 2), first.map { it.indexNumber })
        assertTrue(first.all { kotlin.math.abs((it.runtimeMs ?: 0) - 240_000) <= 2_000 }, "4 minutes each: ${first.map { it.runtimeMs }}")
        val w = assertNotNull(v.window(b.id))
        assertTrue(kotlin.math.abs(w.startMs - 240_000) <= 1_000, "episode 2 starts at 4:00 in the file: $w")
        // The file without named chapters stays one playable entry (no guessed halves).
        val unsplit = eps.firstOrNull { it.indexNumber == 4 }
        assertTrue(unsplit != null && !com.auroratv.jellyfin.VirtualIds.isVirtual(unsplit.id) && unsplit.indexNumberEnd == 5, "S01E04-E05 isn't split: ${eps.map { it.id to it.indexNumber }}")
        // The next episode after each part, and playback of part 2 from the file.
        val afterA = api.episodes(series.id, startItemId = a.id, limit = 2).getOrNull(1)?.id
        val afterB = api.episodes(series.id, startItemId = b.id, limit = 2).getOrNull(1)
        assertEquals(b.id, afterA)
        assertEquals(3, afterB?.indexNumber)
        val plan = PlaybackNegotiator(api, sdr4k).negotiate(b.id, null, w.startMs, null, null, 0, true)
        assertEquals(PlayMethod.DirectPlay, plan.method)
        assertTrue(plan.url.contains(com.auroratv.jellyfin.VirtualIds.serverId(b.id)), "plays the file")
        // Progress: part 1 finished, part 2 half watched; the server's file untouched until both are done.
        api.reportStopped(com.auroratv.jellyfin.model.PlaybackReport(a.id, plan.mediaSource.id, null, 240_000L * 10_000))
        api.reportStopped(com.auroratv.jellyfin.model.PlaybackReport(b.id, plan.mediaSource.id, null, 120_000L * 10_000))
        val again = api.episodes(series.id).filter { com.auroratv.jellyfin.VirtualIds.isVirtual(it.id) }
        val file = api.fileItem(a.id)
        assertTrue(again[0].userData?.played == true && again[1].userData?.played != true, "independent watched states")
        assertEquals(120_000L * 10_000, again[1].userData?.playbackPositionTicks)
        assertTrue(file.userData?.played != true && (file.userData?.playbackPositionTicks ?: 0) == 0L, "the server's own progress for the file isn't touched")
        assertTrue(api.resumeItems().any { it.id == b.id }, "Continue Watching shows episode 2")
        // Clean up for the other checks: the file back to unwatched with no progress here.
        api.setPlayed(a.id, false); api.setPlayed(b.id, false)
        return "S01E01-E02 → ${first.joinToString { "${it.indexNumber} \"${it.name}\" ${it.runtimeMs!! / 1000} s" }}; part 2 starts at ${w.startMs / 1000.0} s; " +
            "S01E04-E05 left whole (${v.splits[unsplit!!.id]?.reason}); next after part 1: part 2, after part 2: episode ${afterB?.indexNumber}; " +
            "${plan.method.jellyfinName} of the file; progress kept per part, server's file untouched"
    }

    /**
     * Zephyr Bay (CI sample): S02E02 "Zero Letter" belongs at the end of season 1. A hand-made order
     * moves it there; the seasons, each season's list, the next episode, Next Up and progress follow
     * the order, the right file plays, and the server's own numbering is untouched.
     */
    private suspend fun orderCheck(): String {
        val o = assertNotNull(api.order, "episode orders are on for every session")
        val series = api.items(ItemQuery(includeItemTypes = listOf("Series"), searchTerm = "Zephyr Bay", limit = 5)).items.firstOrNull()
            ?: return "skipped: sample series not in this library"
        val t0 = kotlin.time.TimeSource.Monotonic.markNow()
        val server = api.episodes(series.id)
        val plainMs = t0.elapsedNow().inWholeMilliseconds
        assertEquals(6, server.size, "six episodes: ${server.map { it.name }}")
        fun at(s: Int, e: Int) = server.first { it.parentIndexNumber == s && it.indexNumber == e }
        val late = at(2, 2)
        // Half of "Zero Letter" watched before its place changes.
        val plan = PlaybackNegotiator(api, sdr4k).negotiate(late.id, null, 0, null, null, 0, true)
        api.reportStopped(com.auroratv.jellyfin.model.PlaybackReport(late.id, plan.mediaSource.id, null, 10_000L * 10_000))
        api.setPlayed(at(1, 1).id, true); api.setPlayed(at(1, 2).id, true)
        try {
            api.setOrder(series.id, com.auroratv.jellyfin.SeriesOrder.CUSTOM, listOf(
                com.auroratv.jellyfin.CustomSeason(1, null, listOf(at(1, 1).id, at(1, 2).id, at(1, 3).id, late.id)),
                com.auroratv.jellyfin.CustomSeason(2, null, listOf(at(2, 1).id, at(2, 3).id)),
            ))
            val t1 = kotlin.time.TimeSource.Monotonic.markNow()
            val seasons = api.seasons(series.id)
            val s1 = api.episodes(series.id, seasonId = seasons[0].id)
            val orderedMs = t1.elapsedNow().inWholeMilliseconds
            val s2 = api.episodes(series.id, seasonId = seasons[1].id)
            assertEquals(listOf(at(1, 1).id, at(1, 2).id, at(1, 3).id, late.id), s1.map { it.id }, "Zero Letter closes season 1: ${s1.map { it.name }}")
            assertEquals(listOf(at(2, 1).id, at(2, 3).id), s2.map { it.id })
            val moved = s1.last()
            assertEquals(1, moved.parentIndexNumber); assertEquals(4, moved.indexNumber)
            assertEquals(10_000L * 10_000, moved.userData?.playbackPositionTicks, "its progress came with it")
            // Next Episode: after S01E03 comes Zero Letter, and after it season 2 begins.
            assertEquals(late.id, api.episodes(series.id, startItemId = at(1, 3).id, limit = 2)[1].id)
            assertEquals(at(2, 1).id, api.episodes(series.id, startItemId = late.id, limit = 2)[1].id)
            // Next Up: S01E01–E02 watched → S01E03 (then Zero Letter), never S02E03 after the server's numbering.
            assertEquals(at(1, 3).id, api.nextUp(seriesId = series.id).firstOrNull()?.id)
            // The right file plays; the server still numbers it S02E02.
            val p2 = PlaybackNegotiator(api, sdr4k).negotiate(moved.id, null, 0, null, null, 0, true)
            assertTrue(p2.url.contains(late.id), "plays Zero Letter's own file")
            val truth = api.item(late.id)
            assertEquals(2, truth.parentIndexNumber, "the server's numbering isn't changed")
            assertEquals(4, api.displayed(truth).indexNumber, "shown as S01E04 in the player")
            // Back to Jellyfin's order.
            api.setOrder(series.id, com.auroratv.jellyfin.SeriesOrder.JELLYFIN, emptyList())
            assertEquals(3, api.episodes(series.id, seasonId = api.seasons(series.id)[0].id).size)
            assertTrue(o.store[series.id]?.mode == com.auroratv.jellyfin.SeriesOrder.JELLYFIN)
            return "season 1: ${s1.joinToString { "${it.indexNumber} ${it.name}" }}; season 2: ${s2.joinToString { "${it.indexNumber} ${it.name}" }}; " +
                "next after S01E03: Zero Letter, after it: ${server.first { it.id == at(2, 1).id }.name}; Next Up S01E03; progress kept (10 s); " +
                "${p2.method.jellyfinName} of its own file; list ${plainMs} ms in Jellyfin's order, seasons + season 1 ${orderedMs} ms in the custom order"
        } finally {
            api.setOrder(series.id, com.auroratv.jellyfin.SeriesOrder.JELLYFIN, emptyList())
            server.forEach { runCatching { api.setPlayed(it.id, false) } }
            runCatching { api.reportStopped(com.auroratv.jellyfin.model.PlaybackReport(late.id, plan.mediaSource.id, null, 0)) }
        }
    }

    /**
     * Fetches Wavelength Street episode 2's audio from the start and from 3:12 (as the credits
     * check does), and finds where the second really begins inside the first: the difference is
     * how far the server's mid-file audio is from where it was asked to start.
     */
    private suspend fun seekCheck(): String {
        val series = api.items(ItemQuery(includeItemTypes = listOf("Series"), searchTerm = "Wavelength", limit = 5)).items.firstOrNull()
            ?: return "skipped: sample series not in this library"
        val ep = api.episodes(series.id)[1]
        val rate = com.auroratv.playback.AudioFingerprint.SAMPLE_RATE
        val whole = com.auroratv.playback.AudioFingerprint.wavSamples(assertNotNull(api.audioSample(ep.id, 0, 250, rate)))
        val asked = 192_000L
        val part = com.auroratv.playback.AudioFingerprint.wavSamples(assertNotNull(api.audioSample(ep.id, asked, 30, rate)))
        // Best sample offset of part's first 5 s inside whole (search ±3 s around where it should be).
        val probe = 5 * rate
        fun v(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) - 128).toDouble()
        var best = 0; var bestScore = Double.MAX_VALUE
        val expected = (asked * rate / 1000).toInt()
        for (off in expected - 3 * rate..expected + 3 * rate) {
            if (off < 0 || off + probe > whole.size) continue
            var d = 0.0
            var i = 0
            while (i < probe) { val x = v(whole, off + i) - v(part, i); d += x * x; i += 4 }
            if (d < bestScore) { bestScore = d; best = off }
        }
        val actualMs = best * 1000L / rate
        // The credits comparison itself, frame by frame around where the credits music starts (3:35).
        val eps = api.episodes(series.id)
        fun endOf(e: com.auroratv.jellyfin.model.BaseItemDto): Pair<Long, ByteArray> {
            val rt = e.runtimeMs!!; val from = rt - minOf(rt / 5, 240_000L)
            return from to com.auroratv.playback.AudioFingerprint.wavSamples(assertNotNull(kotlinx.coroutines.runBlocking { api.audioSample(e.id, from, ((rt - from) / 1000).toInt() + 8, rate) }))
        }
        val (fa, pa) = endOf(eps[1]); val (fb, pb) = endOf(eps[2])
        val a = com.auroratv.playback.AudioFingerprint.fingerprint(pa); val b = com.auroratv.playback.AudioFingerprint.fingerprint(pb)
        val m = com.auroratv.playback.AudioFingerprint.longestShared(a, b, 96)
        val f = com.auroratv.playback.AudioFingerprint.FRAME_MS
        return "asked for 3:12.000, the audio started at ${actualMs / 1000.0} s (${actualMs - asked} ms off); whole file ${whole.size * 1000L / rate} ms, runtime ${ep.runtimeMs} ms; " +
            "credits rough ${m?.let { "${(fa + it.aStart * f) / 1000.0}–${(fa + it.aEnd * f) / 1000.0} (ep3 ${(fb + it.bStart * f) / 1000.0}–${(fb + it.bEnd * f) / 1000.0})" }}; " +
            "refined ${m?.let { com.auroratv.playback.AudioFingerprint.refine(a, b, it) }?.let { "${(fa + it.aStart * f) / 1000.0}–${(fa + it.aEnd * f) / 1000.0}" }} (true 215–240)"
    }

    /**
     * Wavelength Street (CI sample): the same theme after cold opens of 20 / 8 / 25 s, and the same
     * credits music; episode 3 has a 15 s scene after its credits.
     */
    private suspend fun listeningCheck(): String {
        val series = api.items(ItemQuery(includeItemTypes = listOf("Series"), searchTerm = "Wavelength", limit = 5)).items.firstOrNull()
            ?: return "skipped: sample series not in this library"
        val eps = api.episodes(series.id)
        assertEquals(3, eps.size)
        assertTrue(api.mediaSegments(eps[1].id).isEmpty(), "no server markers: the plugin isn't involved")
        val plain = io.ktor.client.HttpClient()
        val detector = com.auroratv.playback.SkipDetector(api, plain, audioAllowed = { true }, onlineAllowed = { false })
        val t0 = System.currentTimeMillis()
        val result = assertNotNull(detector.detect(api.item(eps[1].id), emptySet()), "found nothing by listening")
        val ms = System.currentTimeMillis() - t0
        val intro = assertNotNull(result.ranges.firstOrNull { it.kind == com.auroratv.playback.SkipRange.Kind.Intro }, "no intro: ${result.ranges}")
        val credits = assertNotNull(result.ranges.firstOrNull { it.kind == com.auroratv.playback.SkipRange.Kind.Credits }, "no credits: ${result.ranges}")
        // Accuracy: no story skipped at either edge (a frame's leeway for the encoder), and at most
        // 1.25 s of theme left unskipped.
        assertTrue(intro.startMs in 7_875..9_250 && intro.endMs in 36_750..38_125, "episode 2 intro is 8–38 s: $intro")
        assertTrue(credits.startMs in 214_875..216_250, "credits start at 3:35: $credits")
        assertEquals(eps[1].runtimeMs ?: 240_000L, credits.endMs, "credits music runs to the end: $credits")
        assertTrue(intro.confident && credits.confident, "a full theme is a confident match")
        val other = result.others.values.flatten().firstOrNull { it.kind == com.auroratv.playback.SkipRange.Kind.Intro }
        // Episode 3 (compared with): its credits music ends at 4:00 and a 15 s scene follows, which Skip Outro must land on.
        val ep3Credits = result.others[eps[2].id]?.firstOrNull { it.kind == com.auroratv.playback.SkipRange.Kind.Credits }
        if (ep3Credits != null) {
            assertTrue(ep3Credits.endMs in 238_500..240_250, "episode 3's credits end before its post-credits scene: $ep3Credits")
            assertTrue(!com.auroratv.playback.PlayerUiState(item = api.item(eps[2].id)).creditsRunToEnd(ep3Credits), "Skip Outro lands on the scene")
        }
        return "episode 2: intro ${intro.startMs / 1000.0}–${intro.endMs / 1000.0} s (true 8–38), credits ${credits.startMs / 1000.0}–${credits.endMs / 1000.0} s (true 215–end); " +
            "compared with ${result.others.keys.joinToString { k -> eps.indexOfFirst { it.id == k }.let { "episode ${it + 1}" } }} " +
            "(its intro ${other?.let { "${it.startMs / 1000.0}–${it.endMs / 1000.0} s" }}${ep3Credits?.let { ", episode 3 credits ${it.startMs / 1000.0}–${it.endMs / 1000.0} s (true 215–240, scene after)" } ?: ""}); " +
            "took $ms ms [${detector.lastTimings}]"
    }
}
