package com.auroratv.discmenu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val MINI_PACK = """
{
 "schema": 2, "id": "mini", "title": "Mini",
 "video": {
  "loop": {"file": "l.mp4", "durationMs": 20000, "entryMs": 15000, "fps": 40.0},
  "transition": {"file": "t.mp4", "durationMs": 7000, "pageCutMs": 8000, "audioDelayMs": 100},
  "intro": {"file": "i.mp4", "durationMs": 40000, "clipDurationMs": 40400, "crossfadeMs": 400},
  "letter": {"file": "le.mp4", "durationMs": 2500}
 },
 "music": {"file": "m.flac", "startLagAfterVideoEntryMs": 50, "fadeOutOnPlayMovie": {"startAfterDissolveMs": 1000, "durationMs": 2000}},
 "sparkles": {"index": "s.json", "data": "s.bin"},
 "timing": {"activationToTransitionMs": {"special_features": 500, "scene_selections": 700, "languages": 650, "play_movie": null}, "subActivationToCutMs": 250,
            "playMovie": {"activationToDissolveMs": 0}},
 "main": {"hotspotPadPx": 10, "buttons": [
   {"id": "play_movie", "label": "PLAY MOVIE", "rect": [400, 100, 700, 160], "action": "play_movie", "default": true},
   {"id": "special_features", "label": "SPECIAL FEATURES", "rect": [400, 200, 700, 260], "action": "goto:sf"},
   {"id": "scene_selections", "label": "SCENE SELECTIONS", "rect": [400, 300, 700, 360], "action": "goto:ss1"},
   {"id": "languages", "label": "LANGUAGES", "rect": [400, 400, 700, 460], "action": "goto:lang"}
 ]},
 "sequences": {"scenes": ["ss1", "ss2"]},
 "pages": {
  "sf": {"title": "SF", "plate": "sf.webp", "default": "cast", "backTo": null, "hotspots": [
     {"id": "cast", "rect": [500, 300, 700, 350], "action": "goto:cast1"},
     {"id": "trailer", "rect": [500, 400, 700, 450], "action": "gate:Trailer not observed"},
     {"id": "main_menu", "rect": [500, 800, 700, 850], "action": "goto:main"}]},
  "cast1": {"title": "Cast", "plate": "c.webp", "default": "special_features", "backTo": "sf", "hotspots": [
     {"id": "special_features", "rect": [500, 800, 700, 850], "action": "goto:sf"}]},
  "ss1": {"title": "S1", "plate": "s1.webp", "default": "link_2", "entryDefaultFrom": {"ss2": "link_2"}, "hotspots": [
     {"id": "tag_1", "rect": [600, 100, 700, 160], "action": "chapter:1"},
     {"id": "tag_2", "rect": [800, 100, 900, 160], "action": "chapter:2"},
     {"id": "tag_3", "rect": [600, 300, 700, 360], "action": "chapter:3"},
     {"id": "link_2", "rect": [100, 700, 200, 750], "action": "goto:ss2"},
     {"id": "start", "rect": [300, 700, 400, 750], "action": "play"}]},
  "ss2": {"title": "S2", "plate": "s2.webp", "default": "link_1", "hotspots": [
     {"id": "link_1", "rect": [100, 600, 200, 650], "action": "goto:ss1"},
     {"id": "end_credits", "rect": [300, 600, 400, 650], "action": "chapter:last"}]},
  "lang": {"title": "L", "plate": "l.webp", "default": "main_menu", "hotspots": [
     {"id": "main_menu", "rect": [500, 800, 700, 850], "action": "goto:main"},
     {"id": "sub_en", "rect": [500, 500, 700, 550], "action": "subtitle:en"}]}
 }
}
"""

class MenuLogicTest {
    private fun pack(transform: (String) -> String = { it }) = DiscMenuPackDef.parse(transform(MINI_PACK))
    private fun withoutIntro() = pack { it.replace(Regex("\"intro\": \\{[^}]*\\},"), "") }
    private fun logic(p: DiscMenuPackDef = withoutIntro(), available: (String) -> Boolean = { true }) = MenuLogic(p, available)

    private fun MenuLogic.at(t: Long, key: MenuKey): Boolean = onKey(key, t)

    /** Starts without intro, presses Down [downs] times and Select, then lets the page appear. */
    private fun MenuLogic.openMainButton(downs: Int, t0: Long = 0): Long {
        start(t0)
        repeat(downs) { at(t0 + 10, MenuKey.Down) }
        at(t0 + 20, MenuKey.Select)
        val delay = when (downs) { 1 -> 500L; 2 -> 700L; else -> 650L }
        update(t0 + 20 + delay)
        update(t0 + 20 + delay + 8000)
        return t0 + 20 + delay + 8000
    }

    @Test
    fun withoutAnIntroTheDiscOpensOnTheMenuWithMusicAndLoop() {
        val m = logic()
        m.start(1000)
        assertEquals(listOf(MenuEffect.StopMusic, MenuEffect.StartMainVideo(15000), MenuEffect.StartMusic(50)), m.drainEffects())
        val s = assertIs<MenuState.Main>(m.state)
        assertEquals("play_movie", s.selected)
    }

    @Test
    fun introHandsOffToTheLoopWithACrossfadeCentredOnItsEnd() {
        val m = logic(pack())
        m.start(0)
        assertEquals(listOf(MenuEffect.StartIntro), m.drainEffects())
        m.update(300); m.introStarted(300) // the first picture arrives 300 ms after the request
        m.update(39_000)
        assertIs<MenuState.Intro>(m.state)
        m.update(300 + 39_800) // durationMs 40000 minus half of 400 ms crossfade, counted from the first picture
        val s = assertIs<MenuState.Main>(m.state)
        assertEquals(300 + 40_200L, s.introFadeUntil)
        // the loop starts 200 ms early at the earlier phase; the music is delayed to land on the hand-off point
        assertEquals(listOf(MenuEffect.StopMusic, MenuEffect.StartMainVideo(14_800), MenuEffect.StartMusic(250)), m.drainEffects())
        m.update(300 + 40_199)
        assertTrue(m.drainEffects().isEmpty())
        m.update(300 + 40_200)
        assertEquals(listOf(MenuEffect.StopIntro), m.drainEffects())
        assertEquals(null, (m.state as MenuState.Main).introFadeUntil)
    }

    @Test
    fun anIntroThatNeverShowsAPictureFallsThroughToTheMenu() {
        val m = logic(pack())
        m.start(0); m.drainEffects()
        m.update(5_999)
        assertIs<MenuState.Intro>(m.state)
        m.update(6_001)
        assertIs<MenuState.Main>(m.state)
        assertTrue(MenuEffect.StopIntro in m.drainEffects())
    }

    @Test
    fun returningFromPlaybackOpensTheMenuWithoutTheIntro() {
        val m = logic(pack())
        m.start(0, withIntro = false)
        assertIs<MenuState.Main>(m.state)
    }

    @Test
    fun selectSkipsTheIntroStraightToTheMenu() {
        val m = logic(pack())
        m.start(0); m.drainEffects()
        m.at(5_000, MenuKey.Select)
        assertEquals(listOf(MenuEffect.StopIntro, MenuEffect.StopMusic, MenuEffect.StartMainVideo(15_000), MenuEffect.StartMusic(50)), m.drainEffects())
        assertIs<MenuState.Main>(m.state)
    }

    @Test
    fun mainButtonCutsMusicWaitsTheMeasuredDelayThenRunsTheTransitionAndLandsOnThePage() {
        val m = logic()
        m.start(0); m.drainEffects()
        m.at(10, MenuKey.Down)
        assertEquals("special_features", (m.state as MenuState.Main).selected)
        m.at(20, MenuKey.Select)
        assertEquals(listOf(MenuEffect.StopMusic, MenuEffect.PrepareTransition), m.drainEffects())
        m.at(30, MenuKey.Down) // ignored while the activation plays
        assertEquals("special_features", (m.state as MenuState.Main).selected)
        m.update(519)
        assertIs<MenuState.Main>(m.state)
        m.update(520)
        assertIs<MenuState.Transition>(m.state)
        assertEquals(listOf(MenuEffect.StopMainVideo, MenuEffect.StartTransition), m.drainEffects())
        m.update(520 + 7_999)
        assertIs<MenuState.Transition>(m.state)
        m.update(520 + 8_000)
        val p = assertIs<MenuState.Page>(m.state)
        assertEquals("sf", p.id)
        assertEquals("cast", p.selected)
        assertEquals(listOf(MenuEffect.StopTransition, MenuEffect.StopMainVideo), m.drainEffects())
    }

    @Test
    fun selectDuringTheTransitionSkipsToThePage() {
        val m = logic()
        m.start(0); m.drainEffects()
        m.at(1, MenuKey.Down); m.at(2, MenuKey.Select); m.update(600); m.drainEffects()
        assertIs<MenuState.Transition>(m.state)
        m.at(1_000, MenuKey.Select)
        assertEquals("sf", (m.state as MenuState.Page).id)
        assertTrue(MenuEffect.StopTransition in m.drainEffects())
    }

    @Test
    fun pageActivationWaitsThenGoesToMainWithTheEntryPhaseAndRestartsMusic() {
        val m = logic()
        val t = m.openMainButton(downs = 1)
        m.drainEffects()
        m.at(t + 1, MenuKey.Down) // cast -> trailer
        m.at(t + 2, MenuKey.Down) // trailer -> main_menu
        assertEquals("main_menu", (m.state as MenuState.Page).selected)
        m.at(t + 3, MenuKey.Select)
        m.update(t + 3 + 249)
        assertIs<MenuState.Page>(m.state)
        m.update(t + 3 + 250)
        assertIs<MenuState.Main>(m.state)
        assertEquals(listOf(MenuEffect.StopMusic, MenuEffect.StartMainVideo(15_000), MenuEffect.StartMusic(50)), m.drainEffects())
    }

    @Test
    fun anActionTheReferenceNeverShowedGatesVisiblyInsteadOfDoingNothing() {
        val m = logic()
        val t = m.openMainButton(downs = 1)
        m.drainEffects()
        m.at(t + 1, MenuKey.Down)
        m.at(t + 2, MenuKey.Select)
        m.update(t + 2 + 250)
        assertEquals(listOf(MenuEffect.Gate("Trailer not observed")), m.drainEffects())
        assertEquals("trailer", (m.state as MenuState.Page).selected)
        assertEquals(null, (m.state as MenuState.Page).activation)
    }

    @Test
    fun chapterAndPlayActionsReachTheHostAfterTheSubmenuDelay() {
        val m = logic()
        val t = m.openMainButton(downs = 2)
        m.drainEffects()
        assertEquals("link_2", (m.state as MenuState.Page).selected)
        m.at(t + 1, MenuKey.Up)
        // from link_2 (bottom-left) up is tag_3
        assertEquals("tag_3", (m.state as MenuState.Page).selected)
        m.at(t + 2, MenuKey.Select); m.update(t + 2 + 250)
        assertEquals(listOf(MenuEffect.PlayChapter(3)), m.drainEffects())
        m.at(t + 3, MenuKey.Down)
        assertEquals("start", (m.state as MenuState.Page).selected)
        m.at(t + 5, MenuKey.Select); m.update(t + 5 + 250)
        assertEquals(listOf(MenuEffect.PlayMovie), m.drainEffects())
    }

    @Test
    fun chaptersTheServerDoesNotHaveAreSkippedByNavigationAndRefusedByClicks() {
        val m = logic(available = { it != "chapter:3" })
        val t = m.openMainButton(downs = 2)
        m.drainEffects()
        m.at(t + 1, MenuKey.Up)
        assertEquals("tag_1", (m.state as MenuState.Page).selected) // tag_3 is skipped; the next chapter tag up the page is taken instead
        m.click(650f, 330f, t + 2) // where tag_3 is
        assertEquals("tag_1", (m.state as MenuState.Page).selected)
        assertEquals(null, (m.state as MenuState.Page).activation)
    }

    @Test
    fun lastChapterAndSubtitleActions() {
        val m = logic()
        var t = m.openMainButton(downs = 2)
        m.drainEffects()
        m.at(t + 1, MenuKey.NextPage)
        assertEquals("ss2", (m.state as MenuState.Page).id)
        assertEquals("link_1", (m.state as MenuState.Page).selected)
        m.drainEffects()
        m.at(t + 2, MenuKey.Right)
        m.at(t + 3, MenuKey.Select); m.update(t + 3 + 250)
        assertEquals(listOf(MenuEffect.PlayLastChapter), m.drainEffects())
        m.at(t + 4, MenuKey.PreviousPage)
        assertEquals("ss1", (m.state as MenuState.Page).id)
        assertEquals("link_2", (m.state as MenuState.Page).selected)
        // Languages page: a subtitle choice stays on the page
        val m2 = logic()
        t = m2.openMainButton(downs = 3)
        m2.drainEffects()
        m2.at(t + 1, MenuKey.Up)
        assertEquals("sub_en", (m2.state as MenuState.Page).selected)
        m2.at(t + 2, MenuKey.Select); m2.update(t + 2 + 250)
        assertEquals(listOf(MenuEffect.SelectSubtitle("en")), m2.drainEffects())
        assertEquals("lang", (m2.state as MenuState.Page).id)
    }

    @Test
    fun nextPageStopsAtTheEndOfASequence() {
        val m = logic()
        val t = m.openMainButton(downs = 2)
        m.at(t + 1, MenuKey.NextPage); m.at(t + 2, MenuKey.NextPage)
        assertEquals("ss2", (m.state as MenuState.Page).id)
        m.at(t + 3, MenuKey.PreviousPage); m.at(t + 4, MenuKey.PreviousPage)
        assertEquals("ss1", (m.state as MenuState.Page).id)
    }

    @Test
    fun backGoesUpOneLevelAndLeavesTheMenuFromTheMainMenu() {
        val m = logic()
        var t = m.openMainButton(downs = 1)
        m.at(t + 1, MenuKey.Select); m.update(t + 1 + 250)
        assertEquals("cast1", (m.state as MenuState.Page).id)
        assertTrue(m.at(t + 2_000, MenuKey.Back))
        assertEquals("sf", (m.state as MenuState.Page).id)
        assertTrue(m.at(t + 2_001, MenuKey.Back))
        assertIs<MenuState.Main>(m.state)
        assertFalse(m.at(t + 2_002, MenuKey.Back))
    }

    @Test
    fun playMovieDissolvesIntoTheLetterThenRequestsPlayback() {
        val m = logic()
        m.start(0); m.drainEffects()
        m.at(10, MenuKey.Select)
        assertIs<MenuState.Letter>(m.state)
        assertEquals(listOf(MenuEffect.StartLetter, MenuEffect.FadeOutMusic(1000, 2000)), m.drainEffects())
        m.update(10 + 2_499)
        assertTrue(m.drainEffects().isEmpty())
        m.update(10 + 2_500)
        assertEquals(listOf(MenuEffect.PlayMovie), m.drainEffects())
        m.update(10 + 9_000) // no second request
        assertTrue(m.drainEffects().isEmpty())
    }

    @Test
    fun backDuringTheLetterReturnsToTheMenuBeforePlaybackStarts() {
        val m = logic()
        m.start(0); m.drainEffects()
        m.at(10, MenuKey.Select); m.drainEffects()
        m.at(500, MenuKey.Back)
        assertIs<MenuState.Main>(m.state)
        assertTrue(MenuEffect.StopLetter in m.drainEffects())
    }

    @Test
    fun withoutALetterPlayMovieHandsOverImmediately() {
        val m = logic(pack { it.replace(Regex("\"intro\": \\{[^}]*\\},"), "").replace(Regex(",\\s*\"letter\": \\{[^}]*\\}"), "") })
        m.start(0); m.drainEffects()
        m.at(10, MenuKey.Select)
        assertEquals(listOf(MenuEffect.PlayMovie), m.drainEffects())
    }

    @Test
    fun mouseHoverAndClickUseCanvasCoordinatesWithThePad() {
        val m = logic()
        m.start(0)
        m.hover(500f, 235f, 5) // inside special_features' padded rect
        assertEquals("special_features", (m.state as MenuState.Main).selected)
        m.hover(500f, 185f, 6) // gap between buttons (pad is 10 px) -> unchanged
        assertEquals("special_features", (m.state as MenuState.Main).selected)
        m.click(500f, 335f, 7)
        assertEquals("scene_selections", (m.state as MenuState.Main).activation?.buttonId)
    }

    @Test
    fun shutdownStopsEverythingAndIgnoresLaterInput() {
        val m = logic()
        m.start(0); m.drainEffects()
        m.shutdown()
        assertTrue(MenuEffect.StopMusic in m.drainEffects())
        assertTrue(m.at(5, MenuKey.Select))
        assertTrue(m.drainEffects().isEmpty())
    }

    @Test
    fun geometryLetterboxesWithoutStretchingAndHitsMapBack() {
        val c = PackCanvas(1920, 1080)
        val f = MenuGeometry.fit(2560f, 1080f, c)
        assertEquals(1f, f.scale)
        assertEquals(320f, f.offsetX)
        assertEquals(null, MenuGeometry.windowToCanvas(100f, 500f, 2560f, 1080f, c))
        assertEquals(960f to 540f, MenuGeometry.windowToCanvas(1280f, 540f, 2560f, 1080f, c))
        assertEquals(0, MenuGeometry.loopIndex(0, 40.0, 10))
        assertEquals(1, MenuGeometry.loopIndex(25, 40.0, 10))
        assertEquals(0, MenuGeometry.loopIndex(250, 40.0, 10))
        assertEquals(9, MenuGeometry.holdIndex(10_000, 40.0, 10))
    }
}

class CreditsLayoutTest {
    private val def = CreditsDef(
        textLeftPx = 530, firstLineCenterYPx = 402, lineHeightPx = 50, fontSizePx = 38, maxWidthPx = 590, color = "#74594A",
        pages = linkedMapOf(
            "cast1" to CreditsPageDef("cast", 2, "c1.webp"),
            "cast2" to CreditsPageDef("cast", 2, "c2.webp"),
            "crew" to CreditsPageDef("crew", 3, "cr.webp"),
        ),
    )

    private fun p(name: String, type: String, role: String? = null) = com.auroratv.jellyfin.model.PersonDto(name = name, type = type, role = role)

    @Test
    fun castFillsPagesInBillingOrderAndCrewIsGroupedByJob() {
        val people = listOf(
            p("A One", "Actor", "Harry"), p("B Two", "Actor", "Ron"), p("C Three", "Actor", "Hermione"), p("D Four", "Actor"),
            p("Chris C", "Director"), p("Steve K", "Writer"), p("David H", "Producer"), p("Someone", "Producer"),
        )
        val lines = CreditsLayout.build(people, def)
        assertEquals(listOf("A One....Harry", "B Two....Ron"), lines["cast1"])
        assertEquals(listOf("C Three....Hermione", "D Four"), lines["cast2"])
        assertEquals(listOf("Directed by Chris C", "Written by Steve K", "Produced by David H, Someone"), lines["crew"])
    }

    @Test
    fun aPageTheServerHasNothingForKeepsItsBakedPlate() {
        assertEquals(emptyMap(), CreditsLayout.build(emptyList(), def))
        val onlyCrew = CreditsLayout.build(listOf(p("Chris C", "Director")), def)
        assertEquals(setOf("crew"), onlyCrew.keys)
    }

    @Test
    fun moreCastThanThePagesHoldIsCutAtTheLastPage() {
        val many = (1..9).map { p("Actor $it", "Actor") }
        val lines = CreditsLayout.build(many, def)
        assertEquals(2, lines["cast1"]!!.size)
        assertEquals(2, lines["cast2"]!!.size)
    }
}
