package com.auroratv.playback

/**
 * In-place edits on one HEVC access unit in Annex B form (start-code delimited NAL units, which
 * is what Media3's extractors hand the decoder). Works on a heap [ByteArray] because walking a
 * heap array is far cheaper than per-byte calls on a direct buffer; callers copy the access unit
 * in once and write it back only if something changed.
 */
object HevcBitstream {
    const val NAL_PREFIX_SEI = 39
    /** Dolby Vision RPU (unspecified type used by Dolby). */
    const val NAL_DOVI_RPU = 62
    /** Dolby Vision enhancement layer (profile 7). */
    const val NAL_DOVI_EL = 63
    private const val MAX_VCL_TYPE = 31

    private const val SEI_USER_DATA_REGISTERED_T35 = 4
    /** A payload type H.265 doesn't define: decoders must skip SEI messages carrying it (D.3.1). */
    const val SEI_MASKED_PAYLOAD_TYPE = 254
    /** ITU-T T.35 header of an HDR10+ (ST 2094-40) message: USA, Samsung, application 1. */
    private val HDR10_PLUS_T35 = intArrayOf(0xB5, 0x00, 0x3C, 0x00, 0x01)

    fun nalType(header: Byte): Int = (header.toInt() shr 1) and 0x3F

    fun startsWithStartCode(a: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 4) return false
        if (a[offset].toInt() != 0 || a[offset + 1].toInt() != 0) return false
        return a[offset + 2].toInt() == 1 || (a[offset + 2].toInt() == 0 && a[offset + 3].toInt() == 1)
    }

    /** Length of the start code at [at] (caller knows one is there): 3 or 4 bytes. */
    private fun startCodeLength(a: ByteArray, at: Int): Int = if (a[at + 2].toInt() == 1) 3 else 4

    /**
     * Index of the next start code at or after [from], or [limit]. A zero byte directly before
     * `00 00 01` belongs to the start code (NAL units never end in 0x00). Skips ahead three bytes
     * whenever the third byte rules a start code out, which makes the scan several times faster
     * than a byte-by-byte walk on slice data.
     */
    fun nextStartCode(a: ByteArray, from: Int, limit: Int): Int {
        var i = from
        while (i + 2 < limit) {
            val b2 = a[i + 2].toInt()
            when {
                b2 > 1 -> i += 3
                b2 == 1 && a[i + 1].toInt() == 0 && a[i].toInt() == 0 ->
                    return if (i > from && a[i - 1].toInt() == 0) i - 1 else i
                else -> i++
            }
        }
        return limit
    }

    /** Calls [block] with (unit start, NAL header index, unit end) for each NAL unit. */
    inline fun forEachNal(a: ByteArray, length: Int, block: (start: Int, header: Int, end: Int) -> Unit) {
        if (!startsWithStartCode(a, 0, length)) return
        var unit = 0
        while (unit < length) {
            val header = unit + (if (a[unit + 2].toInt() == 1) 3 else 4)
            if (header >= length) break
            val end = nextStartCode(a, header, length)
            block(unit, header, end)
            unit = end
        }
    }

    fun containsNalType(a: ByteArray, length: Int, type: Int): Boolean {
        forEachNal(a, length) { _, header, _ -> if (nalType(a[header]) == type) return true }
        return false
    }

    /** Removes every NAL unit of [type], compacting in place. Returns the new length. */
    fun removeNalType(a: ByteArray, length: Int, type: Int): Int {
        var write = 0
        var removed = false
        forEachNal(a, length) { start, header, end ->
            if (nalType(a[header]) == type) {
                removed = true
            } else {
                if (removed && write != start) a.copyInto(a, write, start, end)
                write += end - start
            }
        }
        return if (removed) write else length
    }

    /**
     * Masks every HDR10+ SEI message in the prefix SEI NAL units of the access unit by overwriting
     * its payload type byte with [SEI_MASKED_PAYLOAD_TYPE]. The access unit keeps its size and no
     * emulation-prevention bytes change. Stops at the first slice, since prefix SEI precedes it.
     * Returns the number of messages masked.
     */
    fun maskHdr10PlusSei(a: ByteArray, length: Int): Int {
        var masked = 0
        forEachNal(a, length) { _, header, end ->
            val type = nalType(a[header])
            if (type <= MAX_VCL_TYPE) return masked
            if (type == NAL_PREFIX_SEI) masked += maskInSei(a, header + 2, end)
        }
        return masked
    }

    /** Reads RBSP bytes (skipping emulation-prevention 0x03) while tracking raw positions. */
    private class RbspReader(val a: ByteArray, var pos: Int, val end: Int) {
        private var zeros = 0
        var lastRaw = -1
        fun hasMore() = pos < end
        fun read(): Int {
            if (zeros >= 2 && pos < end && a[pos].toInt() == 3) { pos++; zeros = 0 }
            if (pos >= end) return -1
            val b = a[pos].toInt() and 0xFF
            lastRaw = pos
            pos++
            zeros = if (b == 0) zeros + 1 else 0
            return b
        }
        /** True while more than the RBSP trailing bits remain (H.265 7.2 more_rbsp_data). */
        fun moreRbspData(): Boolean {
            var last = end - 1
            while (last >= pos && a[last].toInt() == 0) last--
            return last > pos || (last == pos && a[last].toInt() and 0xFF != 0x80)
        }
    }

    private fun maskInSei(a: ByteArray, from: Int, end: Int): Int {
        val r = RbspReader(a, from, end)
        var masked = 0
        while (r.hasMore() && r.moreRbspData()) {
            var payloadType = 0
            var typeByte: Int
            var typeBytePos: Int
            var typeBytes = 0
            do { typeByte = r.read(); if (typeByte < 0) return masked; typeBytePos = r.lastRaw; payloadType += typeByte; typeBytes++ } while (typeByte == 0xFF)
            var payloadSize = 0
            var sizeByte: Int
            do { sizeByte = r.read(); if (sizeByte < 0) return masked; payloadSize += sizeByte } while (sizeByte == 0xFF)
            var consumed = 0
            if (payloadType == SEI_USER_DATA_REGISTERED_T35 && typeBytes == 1 && payloadSize >= HDR10_PLUS_T35.size) {
                var matches = true
                for (expected in HDR10_PLUS_T35) {
                    val b = r.read(); consumed++
                    if (b != expected) { matches = false; break }
                }
                if (matches) { a[typeBytePos] = SEI_MASKED_PAYLOAD_TYPE.toByte(); masked++ }
            }
            while (consumed < payloadSize) { if (r.read() < 0) return masked; consumed++ }
        }
        return masked
    }
}
