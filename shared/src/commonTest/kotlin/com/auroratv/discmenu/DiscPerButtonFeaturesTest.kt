package com.auroratv.discmenu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The features a disc with a separate transition per button, a timed music cut and a spoken-language page needs. */
private const val PER_BUTTON_PACK = """
{
 "schema": 2, "id": "perbutton", "title": "Per button",
 "canvas": {"w": 640, "h": 360},
 "video": {
  "loop": {"file": "l.mp4", "durationMs": 27000, "entryMs": 0, "fps": 25.0},
  "transition": {"file": "t_scenes.mp4", "durationMs": 4560, "pageCutMs": 4560, "audioDelayMs": 0},
  "transitions": {"lang": {"file": "t_lang.mp4", "durationMs": 4600, "pageCutMs": 4600, "audioDelayMs": 0}},
  "intro": {"file": "i.mp4", "durationMs": 36400, "crossfadeMs": 0},
  "letter": {"file": "le.mp4", "durationMs": 6200}
 },
 "music": {"file": "m.opus", "startLagAfterVideoEntryMs": 400, "startsInIntroAtMs": 31861},
 "sparkles": {"index": "s.json", "data": "s.bin"},
 "timing": {"activationToTransitionMs": {"scene_selections": 1400, "languages": 1040}, "subActivationToCutMs": 560,
            "playMovie": {"activationToDissolveMs": 3000}, "musicStopBeforeTransitionMs": 700, "pageActivationShowsHighlight": false},
 "main": {"hotspotPadPx": 4, "buttons": [
   {"id": "play_movie", "label": "Play Movie", "rect": [133, 302, 198, 322], "action": "play_movie", "default": true},
   {"id": "scene_selections", "label": "Scene Selections", "rect": [213, 302, 314, 322], "action": "goto:ss1"},
   {"id": "theatrical_trailer", "label": "Theatrical Trailer", "rect": [331, 302, 430, 322], "action": "gate:Theatrical Trailer isn't part of this menu yet"},
   {"id": "languages", "label": "Languages", "rect": [447, 302, 508, 322], "action": "goto:lang"}
 ]},
 "pages": {
  "ss1": {"title": "S1", "plate": "s1.webp", "default": "tab_1", "hotspots": [
     {"id": "tab_1", "rect": [160, 303, 205, 326], "action": "stay"},
     {"id": "main_menu", "rect": [400, 303, 508, 326], "action": "goto:main", "cutDelayMs": 1120}]},
  "lang": {"title": "L", "plate": "l.webp", "default": "main_menu", "hotspots": [
     {"id": "main_menu", "rect": [128, 303, 246, 326], "action": "goto:main", "cutDelayMs": 1120},
     {"id": "audio_es", "rect": [333, 132, 428, 156], "action": "audio:es"},
     {"id": "sub_fr", "rect": [333, 247, 408, 264], "action": "subtitle:fr"}]}
 }
}
"""

class DiscPerButtonFeaturesTest {
    private fun pack(transform: (String) -> String = { it }) = DiscMenuPackDef.parse(transform(PER_BUTTON_PACK))
    private fun logic(p: DiscMenuPackDef = pack()) = MenuLogic(p)
    private fun MenuLogic.at(t: Long, key: MenuKey) = onKey(key, t)
    private fun noIntro() = pack { it.replace(Regex("\"intro\": \\{[^}]*\\},"), "") }

    @Test
    fun eachButtonUsesItsOwnTransitionVideoAndItsOwnLength() {
        val p = pack()
        assertEquals("t_lang.mp4", p.video.transitionFor("lang").file)
        assertEquals("t_scenes.mp4", p.video.transitionFor("ss1").file)
        assertEquals(listOf("t_scenes.mp4", "t_lang.mp4"), p.video.allTransitions.map { it.file })

        val m = logic(noIntro())
        m.start(0); m.drainEffects()
        m.at(10, MenuKey.Right); m.at(20, MenuKey.Right); m.at(25, MenuKey.Right)
        assertEquals("languages", (m.state as MenuState.Main).selected)
        m.at(30, MenuKey.Select)
        assertTrue(MenuEffect.PrepareTransition("lang") in m.drainEffects())
        m.update(30 + 1040)
        assertEquals(listOf(MenuEffect.StopMainVideo, MenuEffect.StartTransition("lang")), m.drainEffects().filterNot { it == MenuEffect.StopMusic })
        m.update(30 + 1040 + 4599)
        assertIs<MenuState.Transition>(m.state)
        m.update(30 + 1040 + 4600)
        assertEquals("lang", assertIs<MenuState.Page>(m.state).id)
    }

    @Test
    fun aMainButtonWhoseActionWasNeverShownGivesANoticeAndStartsNothing() {
        val m = logic(noIntro())
        m.start(0); m.drainEffects()
        m.at(10, MenuKey.Right); m.at(20, MenuKey.Right)
        assertEquals("theatrical_trailer", (m.state as MenuState.Main).selected)
        m.at(30, MenuKey.Select)
        assertEquals(listOf(MenuEffect.Gate("Theatrical Trailer isn't part of this menu yet")), m.drainEffects())
        m.update(5_000)
        assertEquals(emptyList(), m.drainEffects())
        assertEquals(null, (m.state as MenuState.Main).activation)
    }

    @Test
    fun theMusicKeepsPlayingUntilJustBeforeTheTransitionStarts() {
        val m = logic(noIntro())
        m.start(0); m.drainEffects()
        m.at(10, MenuKey.Right)
        assertEquals("scene_selections", (m.state as MenuState.Main).selected)
        m.at(1_000, MenuKey.Select)
        // activation delay 1400, the music is cut 700 ms before the transition: at 1_700
        assertEquals(listOf(MenuEffect.PrepareTransition("ss1")), m.drainEffects())
        m.update(1_699)
        assertEquals(emptyList(), m.drainEffects())
        m.update(1_700)
        assertEquals(listOf(MenuEffect.StopMusic), m.drainEffects())
        m.update(1_800)
        assertEquals(emptyList(), m.drainEffects()) // cut once
        m.update(2_400)
        assertIs<MenuState.Transition>(m.state)
    }

    @Test
    fun aTransitionShorterThanTheMusicLeadCutsTheMusicAtOnce() {
        val short = pack { it.replace(Regex("\"intro\": \\{[^}]*\\},"), "").replace("\"languages\": 1040", "\"languages\": 300") }
        val m = MenuLogic(short)
        m.start(0); m.drainEffects()
        m.at(10, MenuKey.Right); m.at(20, MenuKey.Right); m.at(25, MenuKey.Right)
        m.at(30, MenuKey.Select)
        assertEquals(listOf(MenuEffect.StopMusic, MenuEffect.PrepareTransition("lang")), m.drainEffects())
    }

    @Test
    fun playMovieCutsTheMusicBeforeTheClipAndThenPlaysTheClipThenTheFilm() {
        val m = logic(noIntro())
        m.start(0); m.drainEffects()
        m.at(500, MenuKey.Select) // Play Movie is the default button
        assertEquals(emptyList(), m.drainEffects())
        m.update(500 + 2_299)
        assertEquals(emptyList(), m.drainEffects())
        m.update(500 + 2_300)
        assertEquals(listOf(MenuEffect.StopMusic), m.drainEffects())
        m.update(500 + 3_000)
        assertIs<MenuState.Letter>(m.state)
        assertTrue(MenuEffect.StartLetter in m.drainEffects())
        m.update(500 + 3_000 + 6_199)
        assertIs<MenuState.Letter>(m.state)
        m.update(500 + 3_000 + 6_200)
        assertEquals(listOf(MenuEffect.PlayMovie), m.drainEffects())
    }

    @Test
    fun theIntroHandsOverToTheMusicWhereTheIntroSoundLeftOff() {
        val m = logic()
        m.start(0); m.drainEffects()
        m.introStarted(100)
        m.update(100 + 36_399)
        assertIs<MenuState.Intro>(m.state)
        m.update(100 + 36_400)
        assertIs<MenuState.Main>(m.state)
        val e = m.drainEffects()
        // loop from its beginning, the music on from 36 400 - 31 861 = 4 539 ms into its file, no lag
        assertTrue(MenuEffect.StartMainVideo(0) in e, e.toString())
        assertTrue(MenuEffect.StartMusic(0, 4_539) in e, e.toString())
        assertTrue(MenuEffect.StopIntro in e)
    }

    @Test
    fun comingBackFromAPageStartsTheMusicOverWithTheMeasuredLag() {
        val m = logic(noIntro())
        m.start(0); m.drainEffects()
        m.at(10, MenuKey.Right)
        m.at(20, MenuKey.Select)
        m.update(20 + 1_400); m.update(20 + 1_400 + 4_560)
        m.drainEffects()
        val p = assertIs<MenuState.Page>(m.state)
        assertEquals("ss1", p.id)
        // Main Menu link: the page-to-main cut takes its own, longer delay
        m.at(10_000, MenuKey.Right)
        assertEquals("main_menu", (m.state as MenuState.Page).selected)
        m.at(10_100, MenuKey.Select)
        m.update(10_100 + 1_119)
        assertIs<MenuState.Page>(m.state)
        m.update(10_100 + 1_120)
        assertIs<MenuState.Main>(m.state)
        assertTrue(MenuEffect.StartMusic(400, 0) in m.drainEffects())
    }

    @Test
    fun aPageButtonWithoutItsOwnDelayUsesThePagesDelay() {
        val m = logic(noIntro())
        m.start(0); m.drainEffects()
        m.at(10, MenuKey.Right); m.at(20, MenuKey.Select)
        m.update(20 + 1_400); m.update(20 + 1_400 + 4_560); m.drainEffects()
        m.at(9_000, MenuKey.Select) // tab_1 is the default: "stay"
        m.update(9_000 + 559)
        assertTrue((m.state as MenuState.Page).activation != null)
        m.update(9_000 + 560)
        assertNull((m.state as MenuState.Page).activation)
    }

    @Test
    fun spokenLanguageIsAnAction() {
        val m = logic(noIntro())
        m.start(0); m.drainEffects()
        m.at(10, MenuKey.Right); m.at(20, MenuKey.Right); m.at(25, MenuKey.Right) // languages is the last button
        m.at(30, MenuKey.Select)
        m.update(30 + 1_040); m.update(30 + 1_040 + 4_600); m.drainEffects()
        assertEquals("lang", (m.state as MenuState.Page).id)
        // go to the Spanish audio row by pointing at it, then press Select
        m.click(380f, 140f, 5_000)
        m.update(5_000 + 560)
        assertEquals(listOf(MenuEffect.SelectAudio("es")), m.drainEffects())
        m.click(370f, 255f, 8_000)
        m.update(8_000 + 560)
        assertEquals(listOf(MenuEffect.SelectSubtitle("fr")), m.drainEffects())
    }
}
