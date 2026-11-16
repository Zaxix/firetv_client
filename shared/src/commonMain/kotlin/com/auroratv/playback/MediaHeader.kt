package com.auroratv.playback

/**
 * The start of an MKV file (everything before the first video cluster) for files whose start is
 * big: fansub/anime releases put their fonts there, often 10–40 MB, and every start or resume
 * has to read all of it before a single frame shows. That part is kept on the device (see the
 * Android MediaHeaderCache) and fetched ahead while the details page or a Continue Watching card
 * is open, so resuming only downloads the video around the resume point.
 */
object MediaHeader {
    /** At most this much of a file's start is kept. */
    const val MAX_BYTES = 48L * 1024 * 1024

    /**
     * Cache key for a Direct Play MKV with attachments (null otherwise: nothing worth keeping). It
     * names the exact file version (id + size + ETag), so a replaced file never reuses old bytes.
     */
    fun cacheKey(plan: PlaybackPlan): String? {
        if (plan.method != PlayMethod.DirectPlay || !ResumeTuning.headerCache) return null
        val src = plan.mediaSource
        if ((src.container ?: "").lowercase().split(',').none { it == "mkv" || it == "webm" || it == "matroska" }) return null
        if (src.mediaAttachments.isEmpty()) return null
        return "hdr:${src.id}:${src.size ?: 0}:${src.eTag.orEmpty()}"
    }

    private const val SEGMENT = 0x18538067L
    private const val CLUSTER = 0x1F43B675L

    /**
     * Where the first Cluster starts (the end of the part worth keeping), found by walking the
     * file's top-level element headers through [read] (a few small range reads), or null if the
     * file doesn't look like Matroska.
     */
    suspend fun firstClusterOffset(read: suspend (position: Long, length: Int) -> ByteArray?): Long? {
        var buf = read(0, 64) ?: return null
        var pos = 0L
        // EBML header element, then the Segment.
        val ebml = element(buf, 0) ?: return null
        pos = ebml.dataStart + ebml.size
        buf = read(pos, 16) ?: return null
        val segment = element(buf, 0) ?: return null
        if (segment.id != SEGMENT) return null
        pos += segment.dataStart // first child of the Segment
        repeat(64) {
            val head = read(pos, 16) ?: return null
            val e = element(head, 0) ?: return null
            if (e.id == CLUSTER) return pos
            if (e.size < 0) return null // unknown size before any cluster: give up
            pos += e.dataStart + e.size
        }
        return null
    }

    private class Element(val id: Long, val size: Long, val dataStart: Int)

    /** An EBML element header at [at]: id (with its marker bits), data size (-1 = unknown), header length. */
    private fun element(b: ByteArray, at: Int): Element? {
        val (id, idLen) = vint(b, at, keepMarker = true) ?: return null
        val (size, sizeLen) = vint(b, at + idLen, keepMarker = false) ?: return null
        return Element(id, size, idLen + sizeLen)
    }

    private fun vint(b: ByteArray, at: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (at >= b.size) return null
        val first = b[at].toInt() and 0xFF
        if (first == 0) return null
        var len = 1
        while (len <= 8 && first and (0x80 shr (len - 1)) == 0) len++
        if (len > 8 || at + len > b.size) return null
        var value = (if (keepMarker) first else first and (0xFF shr len)).toLong()
        var allOnes = !keepMarker && value == ((1L shl (8 - len)) - 1)
        for (i in 1 until len) {
            val v = b[at + i].toInt() and 0xFF
            if (v != 0xFF) allOnes = false
            value = (value shl 8) or v.toLong()
        }
        return (if (allOnes) -1L else value) to len
    }
}
