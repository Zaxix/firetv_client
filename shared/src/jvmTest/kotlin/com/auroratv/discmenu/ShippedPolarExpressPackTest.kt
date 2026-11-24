package com.auroratv.discmenu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checks the measured polar-express-wb-2005 pack's configuration and runs its timings through the state machine (its media are not in the repository). */
class ShippedPolarExpressPackTest {
    private val pack: DiscMenuPackDef by lazy {
        DiscMenuPackDef.parse(checkNotNull(javaClass.classLoader.getResource("discmenu/polar-express-wb-2005.pack.json")) { "fixture missing" }.readText())
    }
    private val manifest: PackManifest by lazy {
        PackManifest.parse(javaClass.getResourceAsStream("/discmenu/polar-express-wb-2005.manifest.json")!!.readBytes().decodeToString())
    }

    private val knownAudio = setOf("en", "es", "fr")
    private val knownSubtitles = setOf("en", "es", "fr", "off")

    @Test
    fun everyActionTargetExistsEveryRectIsInsideTheCanvasAndEveryLanguageCodeIsKnown() {
        val problems = mutableListOf<String>()
        fun checkAction(where: String, a: String) {
            when {
                a.startsWith("goto:") -> a.removePrefix("goto:").let { t -> if (t != "main" && t !in pack.pages) problems += "$where -> unknown page $t" }
                a.startsWith("audio:") -> if (a.removePrefix("audio:") !in knownAudio) problems += "$where -> unknown audio code $a"
                a.startsWith("subtitle:") -> if (a.removePrefix("subtitle:") !in knownSubtitles) problems += "$where -> unknown subtitle code $a"
                a.startsWith("chapter:") || a.startsWith("gate:") || a == "stay" || a == "play" || a == "play_movie" -> Unit
                else -> problems += "$where has an action the engine does not know: $a"
            }
        }
        assertEquals(640, pack.canvas.w); assertEquals(360, pack.canvas.h)
        pack.main.buttons.forEach { b ->
            val r = b.rect.toRect(b.id)
            if (r.right > pack.canvas.w || r.bottom > pack.canvas.h) problems += "main.${b.id} out of canvas"
            checkAction("main.${b.id}", b.action)
        }
        for ((pid, p) in pack.pages) {
            assertTrue(p.hotspots.any { it.id == p.default }, "$pid default '${p.default}' missing")
            p.backTo?.let { assertNotNull(pack.pages[it], "$pid backTo") }
            for (h in p.hotspots) {
                val r = h.rect.toRect("$pid.${h.id}")
                if (r.width <= 0 || r.height <= 0 || r.right > pack.canvas.w || r.bottom > pack.canvas.h) problems += "$pid.${h.id} rect out of canvas: $r"
                h.spriteRect?.toRect("$pid.${h.id}.sprite")?.let { s -> if (s.right > pack.canvas.w || s.bottom > pack.canvas.h) problems += "$pid.${h.id} sprite out of canvas" }
                if ((h.selected == null) != (h.spriteRect == null)) problems += "$pid.${h.id} has a sprite file without its place (or the reverse)"
                checkAction("$pid.${h.id}", h.action)
            }
        }
        for ((name, seq) in pack.sequences) seq.forEach { if (it !in pack.pages) problems += "sequence $name has unknown page $it" }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun everyHotspotOnEveryPageIsReachableFromItsDefaultWithTheArrowKeys() {
        val unreachable = mutableListOf<String>()
        for ((pid, p) in pack.pages) {
            val items = p.hotspots.map { NavItem(it.id, it.rect.toRect()) }
            val seen = mutableSetOf(p.default)
            val queue = ArrayDeque(listOf(p.default))
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                for (d in Dir.entries) MenuGeometry.neighbour(cur, items, d)?.let { if (seen.add(it)) queue += it }
            }
            val missing = items.map { it.id }.toSet() - seen
            if (missing.isNotEmpty()) unreachable += "$pid: $missing"
        }
        assertEquals(emptyList(), unreachable)
    }

    @Test
    fun theManifestListsEveryFileThePackNeedsAndStagesThemByWhenTheMenuUsesThem() {
        val listed = ManifestAssets(manifest)
        assertEquals(emptyList(), PackCheck.missingRequired(pack, listed))
        assertEquals(emptyList(), PackCheck.withoutMissingOptional(pack, listed).second)
        fun stageOf(path: String) = manifest.files.single { it.path == path }.stage
        assertEquals(setOf("pack.json", pack.video.intro!!.file), manifest.files.filter { it.stage == 0 }.map { it.path }.toSet())
        assertEquals(setOf(pack.video.loop.file, pack.music.file, pack.sparkles.index, pack.sparkles.data), manifest.files.filter { it.stage == 1 }.map { it.path }.toSet())
        assertEquals(3, stageOf(pack.video.letter!!.file))
        pack.video.allTransitions.forEach { assertEquals(2, stageOf(it.file), it.file) }
        assertTrue(pack.pages.values.all { stageOf(it.plate) == 2 })
        assertEquals(emptyList(), manifest.problems())
    }

    @Test
    fun theWholeDiscIsSmall() {
        assertTrue(manifest.files.sumOf { it.size } < 3_000_000L, "the pack should stay under 3 MB, is ${manifest.files.sumOf { it.size }}")
    }

    @Test
    fun theSceneThumbnailsCoverTwentyFourChaptersExactlyAndEachPageHasItsTab() {
        val numbers = pack.pages.filterKeys { it.startsWith("ss") }.values.flatMap { it.hotspots }.map { it.action }
            .filter { it.startsWith("chapter:") }.map { it.removePrefix("chapter:").toInt() }.toList()
        assertEquals((1..24).toList(), numbers.sorted())
        assertEquals(listOf("tab_1_6", "tab_7_12", "tab_13_18", "tab_19_24"), listOf("ss1", "ss2", "ss3", "ss4").map { pack.pages.getValue(it).default })
        assertEquals(listOf("ss1", "ss2", "ss3", "ss4"), pack.sequences.getValue("scenes"))
    }

    @Test
    fun thePageButtonsPutTheMeasuredDelaysInThePack() {
        assertEquals(560L, pack.timing.subActivationToCutMs)
        for (id in listOf("ss1", "ss2", "ss3", "ss4")) assertEquals(1120L, pack.pages.getValue(id).hotspots.single { it.id == "main_menu" }.cutDelayMs)
        assertEquals(1120L, pack.pages.getValue("legal").hotspots.single { it.id == "main_menu" }.cutDelayMs)
        assertNull(pack.pages.getValue("lang").hotspots.single { it.id == "next" }.cutDelayMs)
        assertEquals(false, pack.timing.pageActivationShowsHighlight)
    }

    @Test
    fun theMainMenuWalksLeftToRight() {
        val items = pack.main.buttons.map { NavItem(it.id, it.rect.toRect().padded(pack.main.hotspotPadPx)) }
        var cur = "play_movie"
        val order = mutableListOf(cur)
        repeat(3) { cur = MenuGeometry.neighbour(cur, items, Dir.Right)!!; order += cur }
        assertEquals(listOf("play_movie", "scene_selections", "theatrical_trailer", "languages"), order)
        assertNull(MenuGeometry.neighbour("languages", items, Dir.Right))
    }

    @Test
    fun theDiscRunsThroughTheStateMachineWithTheMeasuredTimes() {
        val m = MenuLogic(pack)
        m.start(0); m.drainEffects()
        m.introStarted(0)
        // the intro hands over to the loop at its end; the music goes on from where the intro's sound left off
        m.update(36_400)
        assertIs<MenuState.Main>(m.state)
        val handOff = m.drainEffects()
        assertTrue(MenuEffect.StartMainVideo(0) in handOff, handOff.toString())
        assertTrue(MenuEffect.StartMusic(0, 4_539) in handOff, handOff.toString())

        // Scene Selections: music cut 700 ms before the clip, clip after 2400 ms, the page 4680 ms later
        val t0 = 40_000L
        m.onKey(MenuKey.Right, t0)
        m.onKey(MenuKey.Select, t0 + 10)
        assertEquals(listOf(MenuEffect.PrepareTransition("ss1")), m.drainEffects())
        m.update(t0 + 10 + 1_699); assertEquals(emptyList(), m.drainEffects())
        m.update(t0 + 10 + 1_700); assertEquals(listOf(MenuEffect.StopMusic), m.drainEffects())
        m.update(t0 + 10 + 2_400); assertEquals(listOf(MenuEffect.StopMainVideo, MenuEffect.StartTransition("ss1")), m.drainEffects())
        m.update(t0 + 10 + 2_400 + 4_680)
        assertEquals("ss1", assertIs<MenuState.Page>(m.state).id)

        // Main Menu on the scene page: 1120 ms to the cut, then the loop from its start and the music 400 ms behind
        val t1 = t0 + 20_000
        var guard = 0
        while ((m.state as MenuState.Page).selected != "main_menu" && guard++ < 10) m.onKey(MenuKey.Right, t1)
        assertEquals("main_menu", (m.state as MenuState.Page).selected)
        m.onKey(MenuKey.Select, t1 + 10)
        m.update(t1 + 10 + 1_119); assertIs<MenuState.Page>(m.state)
        m.update(t1 + 10 + 1_120)
        assertIs<MenuState.Main>(m.state)
        assertTrue(MenuEffect.StartMainVideo(0) in m.drainEffects().also { assertTrue(MenuEffect.StartMusic(400, 0) in it, it.toString()) })
    }

    @Test
    fun languagesAndTheSpokenTrackBothGoThroughTheirOwnTransition() {
        val m = MenuLogic(pack)
        m.start(0, withIntro = false); m.drainEffects()
        repeat(3) { m.onKey(MenuKey.Right, 10) }
        assertEquals("languages", (m.state as MenuState.Main).selected)
        m.onKey(MenuKey.Select, 20)
        assertEquals(listOf(MenuEffect.PrepareTransition("lang")), m.drainEffects())
        m.update(20 + 2_120 - 700); m.drainEffects()
        m.update(20 + 2_120)
        assertEquals(listOf(MenuEffect.StopMainVideo, MenuEffect.StartTransition("lang")), m.drainEffects())
        m.update(20 + 2_120 + 4_719); assertIs<MenuState.Transition>(m.state)
        m.update(20 + 2_120 + 4_720)
        assertEquals("lang", assertIs<MenuState.Page>(m.state).id)
        m.click(380f, 140f, 20_000) // Español 5.1
        m.update(20_000 + 560)
        assertEquals(listOf(MenuEffect.SelectAudio("es")), m.drainEffects())
        m.click(370f, 258f, 21_000) // Français subtitle
        m.update(21_000 + 560)
        assertEquals(listOf(MenuEffect.SelectSubtitle("fr")), m.drainEffects())
    }

    @Test
    fun playMovieRunsItsClipThenPlaysTheFilmAndTheTrailerIsGated() {
        val m = MenuLogic(pack)
        m.start(0, withIntro = false); m.drainEffects()
        m.onKey(MenuKey.Select, 1_000)
        m.update(1_000 + 2_299); assertEquals(emptyList(), m.drainEffects())
        m.update(1_000 + 2_300); assertEquals(listOf(MenuEffect.StopMusic), m.drainEffects())
        m.update(1_000 + 3_000)
        assertIs<MenuState.Letter>(m.state)
        assertTrue(MenuEffect.StartLetter in m.drainEffects())
        m.update(1_000 + 3_000 + 5_199); assertIs<MenuState.Letter>(m.state)
        m.update(1_000 + 3_000 + 5_200)
        assertEquals(listOf(MenuEffect.PlayMovie), m.drainEffects())
        assertNull(pack.timing.playMovie?.letterHoldBeforePlaybackMs) // UNKNOWN stays unknown

        val g = MenuLogic(pack)
        g.start(0, withIntro = false); g.drainEffects()
        repeat(2) { g.onKey(MenuKey.Right, 10) }
        g.onKey(MenuKey.Select, 20)
        assertTrue(g.drainEffects().single() is MenuEffect.Gate)
    }
}
