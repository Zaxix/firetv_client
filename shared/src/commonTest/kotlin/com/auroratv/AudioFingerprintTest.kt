package com.auroratv

import com.auroratv.playback.AudioFingerprint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Finding the theme two episodes share, from their sound alone. */
class AudioFingerprintTest {
    private val rate = AudioFingerprint.SAMPLE_RATE

    /** A made-up theme: a fixed sequence of two-note chords, a new one every quarter second. */
    private fun theme(seconds: Double, volume: Double): DoubleArray {
        val notes = Random(7).let { r -> List(400) { 180.0 + r.nextInt(30) * 45.0 to 260.0 + r.nextInt(20) * 60.0 } }
        return DoubleArray((seconds * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            val (f1, f2) = notes[(t * 4).toInt() % notes.size]
            volume * (0.5 * sin(2 * PI * f1 * t) + 0.35 * sin(2 * PI * f2 * t))
        }
    }

    /** Programme sound that differs every episode (dialogue-ish: noise with a wandering tone). */
    private fun programme(seconds: Double, seed: Int): DoubleArray {
        val r = Random(seed)
        var f = 300.0
        return DoubleArray((seconds * rate).toInt()) { i ->
            if (i % 800 == 0) f = 150.0 + r.nextDouble() * 900
            0.25 * (r.nextDouble() * 2 - 1) + 0.3 * sin(2 * PI * f * i / rate)
        }
    }

    private fun u8(vararg parts: DoubleArray): ByteArray {
        val all = parts.flatMap { it.asList() }
        return ByteArray(all.size) { (128 + (all[it].coerceIn(-1.0, 1.0) * 120)).toInt().toByte() }
    }

    @Test
    fun findsTheSharedThemeAfterDifferentColdOpens() {
        // A: 40 s cold open, theme, 50 s of story. B: 15.07 s cold open (not on a frame boundary),
        // the same theme 30% quieter, 70 s of different story.
        val a = AudioFingerprint.fingerprint(u8(programme(40.0, 1), theme(30.0, 1.0), programme(50.0, 2)))
        val b = AudioFingerprint.fingerprint(u8(programme(15.07, 3), theme(30.0, 0.7), programme(70.0, 4)))
        val m = assertNotNull(AudioFingerprint.longestShared(a, b, minFrames = 96))
        val ms = AudioFingerprint.FRAME_MS
        assertTrue(abs(m.aStart * ms - 40_000) <= 1_500 && abs(m.aEnd * ms - 70_000) <= 1_500, "A: ${m.aStart * ms}-${m.aEnd * ms} ms")
        assertTrue(abs(m.bStart * ms - 15_070) <= 1_500 && abs(m.bEnd * ms - 45_070) <= 1_500, "B: ${m.bStart * ms}-${m.bEnd * ms} ms")
    }

    @Test
    fun refinedEdgesNeverReachIntoTheStory() {
        val a = AudioFingerprint.fingerprint(u8(programme(40.0, 1), theme(30.0, 1.0), programme(50.0, 2)))
        val b = AudioFingerprint.fingerprint(u8(programme(15.07, 3), theme(30.0, 0.7), programme(70.0, 4)))
        val m = assertNotNull(AudioFingerprint.refine(a, b, assertNotNull(AudioFingerprint.longestShared(a, b, minFrames = 96))))
        val ms = AudioFingerprint.FRAME_MS
        // The theme is 40–70 s in A: neither edge reaches into the story, and at most a second of theme is left out.
        assertTrue(m.aStart * ms in 40_000..41_000 && m.aEnd * ms in 69_000..70_000, "A: ${m.aStart * ms}-${m.aEnd * ms} ms")
        assertTrue(AudioFingerprint.agreement(a, b, m) >= 0.5, "a shared theme agrees throughout")
    }

    @Test
    fun aSilentGapIsFoundBetweenMusicAndStory() {
        val gap = DoubleArray(rate * 3 / 10) // 300 ms of silence at 10.0–10.3 s
        val pcm = u8(programme(10.0, 5), gap, theme(10.0, 1.0))
        val mid = assertNotNull(AudioFingerprint.silenceBetween(pcm, 9_500, 11_000))
        assertTrue(mid in 10_050..10_250, "gap middle: $mid ms")
        assertNull(AudioFingerprint.silenceBetween(u8(programme(10.0, 6)), 0, 10_000), "no silence in story")
        assertTrue(AudioFingerprint.loudness(pcm, 0, rate) > 0.1 && AudioFingerprint.loudness(pcm, rate * 10 + 100, rate * 10 + 900) < AudioFingerprint.QUIET)
    }

    @Test
    fun differentEpisodesWithoutAThemeShareNothing() {
        val a = AudioFingerprint.fingerprint(u8(programme(90.0, 11)))
        val b = AudioFingerprint.fingerprint(u8(programme(90.0, 12)))
        assertNull(AudioFingerprint.longestShared(a, b, minFrames = 96))
    }

    @Test
    fun silenceIsNeverTakenForAMatch() {
        val quiet = DoubleArray(60 * rate) // a minute of silence in both
        val a = AudioFingerprint.fingerprint(u8(programme(20.0, 21), quiet, programme(20.0, 22)))
        val b = AudioFingerprint.fingerprint(u8(programme(20.0, 23), quiet, programme(20.0, 24)))
        assertNull(AudioFingerprint.longestShared(a, b, minFrames = 96))
    }

    @Test
    fun wavHeaderIsSkipped() {
        val header = "RIFF\u0000\u0000\u0000\u0000WAVEfmt ".encodeToByteArray() + ByteArray(16) + "data".encodeToByteArray() + ByteArray(4)
        val samples = ByteArray(10) { (100 + it).toByte() }
        assertTrue(AudioFingerprint.wavSamples(header + samples).contentEquals(samples))
    }
}
