package com.auroratv

import com.auroratv.playback.CreditsVision
import com.auroratv.playback.CreditsVision.Look
import com.auroratv.playback.DecodedPixels
import com.auroratv.playback.SkipRange
import com.auroratv.playback.PlayerUiState
import com.auroratv.jellyfin.model.BaseItemDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** End-credits recognition from trickplay thumbnails, and where Skip Outro lands. */
class CreditsVisionTest {
    private val w = 160; private val h = 90
    private fun frame(fill: Int, paint: (IntArray) -> Unit = {}) = DecodedPixels(w, h, IntArray(w * h) { fill }.also(paint))
    private val black = 0xFF000000.toInt(); private val white = 0xFFF0F0F0.toInt()

    /** White text: short 1-px strokes in rows, like lines of names. */
    private fun credits() = frame(black) { px ->
        for (line in 0 until 6) for (x in 40 until 120) if (x % 3 == 0) {
            val y = 10 + line * 13
            px[y * w + x] = white; px[(y + 1) * w + x] = white
        }
    }

    @Test
    fun framesAreJudgedByTheirLook() {
        assertEquals(Look.Credits, CreditsVision.look(credits(), 0, 0, w, h))
        assertEquals(Look.Black, CreditsVision.look(frame(black), 0, 0, w, h))
        // A night scene: nearly all dark, with a bright moon (a blob, not thin strokes).
        val night = frame(0xFF06080C.toInt()) { px -> for (y in 10 until 24) for (x in 120 until 134) px[y * w + x] = white }
        assertEquals(Look.Scene, CreditsVision.look(night, 0, 0, w, h))
        // Coloured text on black (a title in red): not the usual credits look, so never Credits
        // (a little dark red on black is just Black, which alone never makes a credits stretch).
        val red = frame(black) { px -> for (line in 0 until 6) for (x in 40 until 120) if (x % 3 == 0) px[(10 + line * 13) * w + x] = 0xFFFF2020.toInt() }
        assertTrue(CreditsVision.look(red, 0, 0, w, h) != Look.Credits)
        // Bright coloured (saturated) text: rejected as colour.
        val yellow = frame(black) { px -> for (line in 0 until 6) for (x in 40 until 120) if (x % 3 == 0) { val y = 10 + line * 13; px[y * w + x] = 0xFFFFE000.toInt(); px[(y + 1) * w + x] = 0xFFFFE000.toInt() } }
        assertEquals(Look.Scene, CreditsVision.look(yellow, 0, 0, w, h))
        assertEquals(Look.Scene, CreditsVision.look(frame(0xFF4080C0.toInt()), 0, 0, w, h))
    }

    private val S = Look.Scene; private val C = Look.Credits; private val B = Look.Black

    @Test
    fun creditsRunningToTheEnd() {
        // 40-minute episode, thumbnails every 10 s from 34:00 (index 204).
        val runtime = 2_400_000L
        val looks = List(30) { S } + listOf(B, C, C, C, C, C, C, B, C, C) + listOf(C) // to the last thumbnail
        val run = assertNotNull(CreditsVision.creditsRun(looks, 200, 10_000, runtime))
        assertEquals(2_300_000, run.startMs)
        assertTrue(run.reachesEnd); assertEquals(runtime, run.endMs)
    }

    @Test
    fun aPostCreditsSceneIsNeverSkipped() {
        val runtime = 2_400_000L
        val looks = List(20) { S } + List(8) { C } + List(12) { S }
        val run = assertNotNull(CreditsVision.creditsRun(looks, 200, 10_000, runtime))
        assertFalse(run.reachesEnd)
        // The last credits thumbnail is at 2 270 s; the scene starts somewhere in the next 10 s.
        assertEquals(2_271_000, run.endMs, "ends a second after the last credits frame, before the scene")
        val ui = PlayerUiState(item = BaseItemDto(id = "e", type = "Episode", runTimeTicks = runtime * 10_000))
        assertFalse(ui.creditsRunToEnd(SkipRange(SkipRange.Kind.Credits, run.startMs, run.endMs)), "Skip Outro, not Next Episode")
        assertTrue(ui.creditsRunToEnd(SkipRange(SkipRange.Kind.Credits, 2_300_000, runtime - 5_000)))
    }

    @Test
    fun weakOrMisplacedStretchesAreNotCredits() {
        val runtime = 2_400_000L
        assertNull(CreditsVision.creditsRun(List(37) { S } + listOf(C, C, S), 200, 10_000, runtime), "too short (20 s)")
        assertNull(CreditsVision.creditsRun(List(30) { S } + List(10) { B }, 200, 10_000, runtime), "only black: a fade, not credits")
        // A dark stretch in the middle of the episode is story, not credits.
        assertNull(CreditsVision.creditsRun(List(10) { C } + List(60) { S }, 60, 10_000, runtime))
    }

    @Test
    fun neighboursMustAgree() {
        val a = CreditsVision.Run(2_300_000, 2_400_000, true)
        assertTrue(CreditsVision.agree(a, CreditsVision.Run(2_290_000, 2_380_000, true)))
        assertFalse(CreditsVision.agree(a, CreditsVision.Run(2_350_000, 2_390_000, true)), "100 s vs 40 s")
    }
}
