package com.auroratv.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Finds the stretch of sound two episodes share (the theme song, the end-credits music), the way
 * the Intro Skipper plugin does, but on the device and without any plugin: no metadata or online
 * service, just the audio the server already has.
 *
 * Audio arrives as 8-bit mono at [SAMPLE_RATE] (≈4 KB a second). Every [HOP] samples (1/8 s) a
 * 1/4-second slice is turned into a 15-bit code: whether each of 16 frequency bands (100 Hz–1.9 kHz)
 * is louder than the next. That shape survives re-encoding, volume differences and slices that
 * don't line up exactly between episodes (codes built from changes over time didn't). Matching
 * codes vote on how the episodes line up; at the best alignments the longest stretch where most
 * codes agree (within 4 bits) is the shared part.
 */
object AudioFingerprint {
    const val SAMPLE_RATE = 4000
    private const val WINDOW = 1024
    const val HOP = 500
    const val FRAME_MS = HOP * 1000L / SAMPLE_RATE // 125 ms
    private const val BANDS = 16
    private const val SILENT = -1

    /** A shared stretch: frame ranges (inclusive start, exclusive end) in each of the two inputs. */
    data class Match(val aStart: Int, val aEnd: Int, val bStart: Int, val bEnd: Int) {
        val frames get() = aEnd - aStart
    }

    /** One code per hop; [SILENT] where there's next to no sound (silence must never "match"). */
    fun fingerprint(pcmU8: ByteArray): IntArray {
        if (pcmU8.size < WINDOW) return IntArray(0)
        val frames = (pcmU8.size - WINDOW) / HOP + 1
        val codes = IntArray(frames)
        val re = DoubleArray(WINDOW); val im = DoubleArray(WINDOW)
        val hann = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * it / (WINDOW - 1)) }
        val edges = bandEdges()
        for (f in 0 until frames) {
            val off = f * HOP
            var power = 0.0
            for (i in 0 until WINDOW) {
                val s = ((pcmU8[off + i].toInt() and 0xFF) - 128) / 128.0
                power += s * s
                re[i] = s * hann[i]; im[i] = 0.0
            }
            fft(re, im)
            val e = DoubleArray(BANDS) { b ->
                var sum = 1e-9
                for (k in edges[b] until edges[b + 1]) sum += re[k] * re[k] + im[k] * im[k]
                ln(sum)
            }
            codes[f] = if (power / WINDOW < 1e-4) SILENT else {
                var code = 0
                for (b in 0 until BANDS - 1) if (e[b] > e[b + 1]) code = code or (1 shl b)
                code
            }
        }
        return codes
    }

    /**
     * The longest stretch [a] and [b] share, at least [minFrames] long, or null. Gaps of up to a
     * second inside it are tolerated (a sound effect over the theme, an encoder difference).
     */
    fun longestShared(a: IntArray, b: IntArray, minFrames: Int): Match? {
        if (a.isEmpty() || b.isEmpty()) return null
        // Exact code matches vote for an alignment (offset = position in a − position in b).
        val where = HashMap<Int, MutableList<Int>>()
        b.forEachIndexed { j, c -> if (c != SILENT) where.getOrPut(c) { mutableListOf() }.add(j) }
        val votes = HashMap<Int, Int>()
        a.forEachIndexed { i, c ->
            if (c == SILENT) return@forEachIndexed
            where[c]?.let { js -> if (js.size <= 64) js.forEach { j -> votes[i - j] = (votes[i - j] ?: 0) + 1 } }
        }
        val offsets = votes.entries.sortedByDescending { it.value }.take(6)
            .flatMap { (d, _) -> listOf(d - 1, d, d + 1) }.distinct()
        var best: Match? = null
        for (d in offsets) {
            val m = runAt(a, b, d) ?: continue
            if (m.frames >= minFrames && (best == null || m.frames > best.frames)) best = m
        }
        return best
    }

    /** Longest well-matching run when a[i] lines up with b[i − d]. */
    private fun runAt(a: IntArray, b: IntArray, d: Int): Match? {
        val from = maxOf(0, d); val to = minOf(a.size, b.size + d)
        if (to - from <= 0) return null
        val n = to - from
        val good = BooleanArray(n) { k ->
            val i = from + k; val x = a[i]; val y = b[i - d]
            x != SILENT && y != SILENT && (x xor y).countOneBits() <= 4
        }
        // Smooth over ±1 s (17 frames): a stretch counts when at least 8 of them agree. Unrelated
        // audio rarely manages a second of that; a shared theme holds it throughout.
        val smooth = BooleanArray(n)
        var window = 0
        for (k in 0 until n + 8) {
            if (k < n && good[k]) window++
            if (k - 17 >= 0 && good[k - 17]) window--
            val centre = k - 8
            if (centre in 0 until n) smooth[centre] = window >= 8
        }
        var bestStart = -1; var bestLen = 0; var start = -1
        for (k in 0..n) {
            val on = k < n && smooth[k]
            if (on && start < 0) start = k
            if (!on && start >= 0) {
                if (k - start > bestLen) { bestLen = k - start; bestStart = start }
                start = -1
            }
        }
        if (bestStart < 0) return null
        val aStart = from + bestStart
        return Match(aStart, aStart + bestLen, aStart - d, aStart - d + bestLen)
    }

    private fun bandEdges(): IntArray {
        // Log-spaced 100 Hz … 1.9 kHz, as FFT bin indices.
        val lo = 100.0; val hi = 1900.0
        return IntArray(BANDS + 1) { i ->
            val hz = lo * kotlin.math.exp(ln(hi / lo) * i / BANDS)
            (hz * WINDOW / SAMPLE_RATE).toInt().coerceIn(1, WINDOW / 2)
        }
    }

    /** In-place iterative radix-2 FFT (size must be a power of two). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** The 8-bit samples of a WAV stream (or the bytes as they are when there's no header). */
    /** Codes that agree closely (at most 2 of 15 bits differ): stricter than inside a run, for its edges. */
    private fun close(x: Int, y: Int) = x != SILENT && y != SILENT && (x xor y).countOneBits() <= 2

    /**
     * The edges of [m], placed so no programme sound is inside: the run's ±1 s smoothing lets it
     * start or end where stray codes happened to agree (story sound can resemble the theme for a
     * frame or two). The edge is the first (last) frame that agrees closely, with one of the next two
     * agreeing too (a lone agreeing frame is chance; slices half a frame out of step agree every other
     * frame) and at least 4 of the 10 frames inward; then the start moves in by a frame (a quarter-second slice
     * straddling the edge can still agree) and the end by [END_MARGIN] frames (the last frames of a
     * theme blur into what follows). Tuned on synthetic episodes so that no edge
     * reached into the programme (and on CI's real server decode, where the frame before the credits
     * music agreed by chance), leaving at most ~1.4 s of theme unskipped at an edge. Searched within [slack]
     * frames of the rough edges; null when no such edge is found at one of them.
     */
    fun refine(a: IntArray, b: IntArray, m: Match, slack: Int = 16): Match? {
        val d = m.aStart - m.bStart
        fun ok(i: Int) = i in a.indices && (i - d) in b.indices && close(a[i], b[i - d])
        // A lone agreeing frame isn't an edge: the next (previous) frame or the one after must agree too.
        val start = ((m.aStart - slack..m.aStart + slack).firstOrNull { i ->
            ok(i) && (ok(i + 1) || ok(i + 2)) && (0 until EDGE_WINDOW).count { ok(i + it) } >= EDGE_NEED
        } ?: return null) + START_MARGIN
        val end = ((m.aEnd + slack downTo m.aEnd - slack).firstOrNull { k ->
            ok(k - 1) && (ok(k - 2) || ok(k - 3)) && (1..EDGE_WINDOW).count { ok(k - it) } >= EDGE_NEED
        } ?: return null) - END_MARGIN
        if (end - start < m.frames / 2) return null
        return Match(start, end, start - d, end - d)
    }

    /** Without exact edges: [m] pulled in by [frames] at both ends, past where the smoothing can reach. */
    fun shrink(m: Match, frames: Int = 5): Match =
        if (m.frames <= frames * 4) m else Match(m.aStart + frames, m.aEnd - frames, m.bStart + frames, m.bEnd - frames)

    private const val EDGE_WINDOW = 10
    private const val EDGE_NEED = 4
    private const val END_MARGIN = 3
    private const val START_MARGIN = 1

    /** Share of [m]'s frames whose codes agree: near 1 for a shared theme, low for a weak match. */
    fun agreement(a: IntArray, b: IntArray, m: Match): Double {
        if (m.frames <= 0) return 0.0
        val d = m.aStart - m.bStart
        val good = (m.aStart until m.aEnd).count { i ->
            i in a.indices && (i - d) in b.indices && a[i] != SILENT && b[i - d] != SILENT && (a[i] xor b[i - d]).countOneBits() <= 4
        }
        return good.toDouble() / m.frames
    }

    /** RMS loudness (0–1) of 8-bit samples [from, to). */
    fun loudness(pcm: ByteArray, from: Int, to: Int): Double {
        val a = from.coerceIn(0, pcm.size); val b = to.coerceIn(a, pcm.size)
        if (b <= a) return 0.0
        var sum = 0.0
        for (i in a until b) { val v = ((pcm[i].toInt() and 0xFF) - 128) / 128.0; sum += v * v }
        return kotlin.math.sqrt(sum / (b - a))
    }

    /**
     * The middle of a near-silent gap (≥ 100 ms) between [fromMs] and [toMs] of [pcm] (offsets in
     * ms from its start), or null. Themes and credits usually begin and end at such a gap, so an
     * edge moved into it lands between the music and the story.
     */
    fun silenceBetween(pcm: ByteArray, fromMs: Long, toMs: Long): Long? {
        val block = SAMPLE_RATE / 20 // 50 ms
        val first = (fromMs * SAMPLE_RATE / 1000 / block).toInt().coerceAtLeast(0)
        val last = (toMs * SAMPLE_RATE / 1000 / block).toInt().coerceAtMost(pcm.size / block - 1)
        var best: IntRange? = null; var run = -1
        for (k in first..last + 1) {
            val quiet = k <= last && loudness(pcm, k * block, (k + 1) * block) < QUIET
            if (quiet && run < 0) run = k
            if (!quiet && run >= 0) {
                if (k - run >= 2 && (best == null || k - run > best.count())) best = run until k
                run = -1
            }
        }
        val gap = best ?: return null
        return ((gap.first + gap.last + 1) * block / 2) * 1000L / SAMPLE_RATE
    }

    /** Quieter than this (RMS) is silence: well under speech or music, above the noise of re-encoding. */
    const val QUIET = 0.015

    fun wavSamples(bytes: ByteArray): ByteArray {
        val limit = minOf(bytes.size - 8, 512)
        for (i in 12 until limit) {
            if (bytes[i] == 'd'.code.toByte() && bytes[i + 1] == 'a'.code.toByte() && bytes[i + 2] == 't'.code.toByte() && bytes[i + 3] == 'a'.code.toByte()) {
                return bytes.copyOfRange(i + 8, bytes.size)
            }
        }
        return bytes
    }
}
