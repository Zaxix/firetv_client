package com.auroratv.playback

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.TrickplayInfo

/** Decoded image pixels (ARGB), downsampled by an integer factor while decoding. */
class DecodedPixels(val width: Int, val height: Int, val argb: IntArray)

/** Decodes a JPEG/PNG at 1/[sampleSize] of its size (Android: BitmapFactory inSampleSize). */
expect fun decodeImagePixels(bytes: ByteArray, sampleSize: Int): DecodedPixels?

/**
 * Recognises end credits in Jellyfin's trickplay thumbnails (one small frame every ~10 s, which the
 * server already made for scrub previews): closing credits are almost always light, thin, colourless
 * text on black. Each thumbnail is judged on a few cheap pixel statistics:
 *
 * - **Credits**: mostly near-black, a little bright *and colourless* detail, few mid-tones, and the
 *   bright detail is thin (many dark↔bright changes along a row per bright pixel, as text strokes
 *   have; a lamp or the moon in a night scene is a blob with few);
 * - **Black**: practically all black (fades, the end card's gaps);
 * - **Scene**: anything else.
 *
 * A credits stretch is a run of Credits/Black thumbnails (at most single-frame interruptions, e.g.
 * a coloured company logo) that holds at least three Credits frames, lasts 30 s or more, starts in
 * the last 30% of the episode and ends within 4 minutes of the end. It only counts when a
 * neighbouring episode has a stretch of similar length too ([agree]); a dark ending in one episode
 * alone is never taken for credits.
 */
object CreditsVision {
    enum class Look { Credits, Black, Scene }

    /** A credits stretch: [reachesEnd] when it runs to the end of the file (nothing after it). */
    data class Run(val startMs: Long, val endMs: Long, val reachesEnd: Boolean) {
        val lengthMs get() = endMs - startMs
    }

    private const val TAIL_MS = 10 * 60_000L
    private const val MIN_RUN_MS = 30_000L
    private const val MAX_AFTER_MS = 4 * 60_000L

    /** The thumbnail set to use: ~320 px wide is plenty (and small); none below 160 px. */
    fun pickTrickplay(item: BaseItemDto): Pair<String, TrickplayInfo>? {
        val sets = item.trickplay ?: return null
        val sourceId = item.mediaSources.firstOrNull()?.id?.takeIf { it in sets } ?: sets.keys.firstOrNull() ?: return null
        val infos = sets[sourceId]?.values?.filter { it.interval > 0 && it.tileWidth > 0 && it.tileHeight > 0 && it.width >= 160 && it.thumbnailCount > 0 }.orEmpty()
        val pick = infos.filter { it.width >= 320 }.minByOrNull { it.width } ?: infos.maxByOrNull { it.width } ?: return null
        return sourceId to pick
    }

    /** Thumbnail indexes covering the end of the episode (the last quarter, at most 10 minutes). */
    fun tailTiles(info: TrickplayInfo, runtimeMs: Long): List<Int>? {
        if (runtimeMs <= 0) return null
        val last = minOf(info.thumbnailCount - 1, (runtimeMs / info.interval).toInt())
        val first = maxOf(0, ((runtimeMs - minOf(runtimeMs / 4, TAIL_MS)) / info.interval).toInt())
        return if (last - first < 3) null else (first..last).toList()
    }

    /** How the thumbnail at ([x0], [y0]), [w]×[h] pixels of [px], looks. */
    fun look(px: DecodedPixels, x0: Int, y0: Int, w: Int, h: Int): Look {
        val xEnd = minOf(px.width, x0 + w); val yEnd = minOf(px.height, y0 + h)
        if (xEnd - x0 < 8 || yEnd - y0 < 8) return Look.Scene
        var n = 0; var dark = 0; var bright = 0; var mid = 0; var brightChroma = 0L; var edges = 0
        for (y in y0 until yEnd) {
            var prevBright = false; var prevDark = true
            for (x in x0 until xEnd) {
                val c = px.argb[y * px.width + x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val luma = (299 * r + 587 * g + 114 * b) / 1000
                n++
                val isBright = luma > BRIGHT
                when {
                    luma < DARK -> dark++
                    isBright -> { bright++; brightChroma += maxOf(r, g, b) - minOf(r, g, b) }
                    else -> mid++
                }
                val isDarkish = luma < EDGE_DARK
                if (x > x0 && ((isBright && prevDark) || (isDarkish && prevBright))) edges++
                prevBright = isBright; prevDark = isDarkish
            }
        }
        if (n == 0) return Look.Scene
        val fDark = dark.toDouble() / n; val fBright = bright.toDouble() / n; val fMid = mid.toDouble() / n
        if (fDark >= 0.98 && fBright < 0.002) return Look.Black
        if (fDark < 0.78 || fMid > 0.18 || fBright < 0.004 || fBright > 0.2) return Look.Scene
        if (brightChroma.toDouble() / bright > 60) return Look.Scene // coloured: a scene, not credits
        if (edges.toDouble() / bright < 0.5) return Look.Scene // blobs (lights), not thin strokes
        return Look.Credits
    }

    /**
     * The credits stretch among [looks] (thumbnail [firstIndex] onwards, one every [intervalMs]),
     * or null. Times are conservative: it starts at the first credits frame (never before) and,
     * when something follows it (a post-credits scene, a preview), ends one second after the last
     * credits frame, so a skip never jumps over the start of what follows.
     */
    fun creditsRun(looks: List<Look>, firstIndex: Int, intervalMs: Long, runtimeMs: Long): Run? {
        if (looks.isEmpty() || intervalMs <= 0) return null
        // Single Scene frames between two Credits frames don't break a stretch (a logo, a still).
        val l = looks.toMutableList()
        for (i in 1 until l.size - 1) if (l[i] == Look.Scene && l[i - 1] == Look.Credits && l[i + 1] == Look.Credits) l[i] = Look.Credits
        var best: IntRange? = null
        var i = 0
        while (i < l.size) {
            if (l[i] == Look.Scene) { i++; continue }
            var j = i
            while (j + 1 < l.size && l[j + 1] != Look.Scene) j++
            val credits = (i..j).count { l[it] == Look.Credits }
            val len = j - i + 1
            if (credits >= 3 && credits * 2 >= len && (best == null || len >= best.count())) best = i..j
            i = j + 1
        }
        val run = best ?: return null
        val startMs = (firstIndex + run.first) * intervalMs
        val lastTileMs = (firstIndex + run.last) * intervalMs
        val reachesEnd = run.last == l.lastIndex && runtimeMs - lastTileMs <= intervalMs * 2
        val endMs = if (reachesEnd) runtimeMs else lastTileMs + 1_000
        if (endMs - startMs < MIN_RUN_MS) return null
        if (startMs < runtimeMs * 7 / 10) return null
        if (runtimeMs - endMs > MAX_AFTER_MS) return null
        return Run(startMs, endMs, reachesEnd)
    }

    /** Two episodes' stretches look like the same closing credits: similar length. */
    fun agree(a: Run, b: Run): Boolean {
        val shorter = minOf(a.lengthMs, b.lengthMs).toDouble(); val longer = maxOf(a.lengthMs, b.lengthMs).toDouble()
        return shorter / longer >= 0.6 || longer - shorter <= 20_000
    }

    private const val DARK = 40
    private const val BRIGHT = 140
    private const val EDGE_DARK = 80
}
