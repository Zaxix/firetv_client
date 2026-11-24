package com.auroratv.discmenu

import com.auroratv.core.AuroraJson
import kotlinx.serialization.Serializable

/**
 * Many small files in one file, so a pack with dozens of tiny images costs one download instead of dozens.
 * A bundle is `DMB1`, the header length (4 bytes, big endian), a JSON header listing each member's path, offset
 * (from the end of the header) and length, then the members' bytes back to back. Members are read by the same
 * relative path they would have as separate files, so `pack.json` doesn't change.
 */
object AssetBundle {
    const val EXTENSION = ".bundle"
    const val PREFIX_BYTES = 8
    private const val MAX_HEADER_BYTES = 1 shl 20
    private val MAGIC = byteArrayOf('D'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())

    class Entry(val path: String, val offset: Long, val length: Int)

    @Serializable
    private data class Header(val files: List<Member>)

    @Serializable
    private data class Member(val p: String, val o: Long, val l: Int)

    /** The header's length from the first [PREFIX_BYTES] bytes of a file, or null when it isn't a bundle. */
    fun headerLength(prefix: ByteArray): Int? {
        if (prefix.size < PREFIX_BYTES || !(0 until 4).all { prefix[it] == MAGIC[it] }) return null
        val n = ((prefix[4].toInt() and 0xFF) shl 24) or ((prefix[5].toInt() and 0xFF) shl 16) or
            ((prefix[6].toInt() and 0xFF) shl 8) or (prefix[7].toInt() and 0xFF)
        return n.takeIf { it in 2..MAX_HEADER_BYTES }
    }

    /**
     * The members with absolute offsets into the file, or null when the header is damaged or a member lies outside a
     * file of [fileSize] bytes, or a path is unsafe.
     */
    fun parseEntries(header: ByteArray, fileSize: Long): List<Entry>? = runCatching {
        val h = AuroraJson.decodeFromString(Header.serializer(), header.decodeToString())
        val base = PREFIX_BYTES + header.size.toLong()
        val seen = HashSet<String>()
        h.files.map { m ->
            require(PackManifestRules.isSafePath(m.p) && seen.add(m.p))
            require(m.o >= 0 && m.l >= 0 && base + m.o + m.l <= fileSize)
            Entry(m.p, base + m.o, m.l)
        }
    }.getOrNull()

    /** The paths a bundle holds, for the manifest. */
    fun paths(header: ByteArray): List<String>? = parseEntries(header, Long.MAX_VALUE)?.map { it.path }

    fun build(members: List<Pair<String, ByteArray>>): ByteArray {
        var off = 0L
        val header = AuroraJson.encodeToString(Header.serializer(), Header(members.map { (p, b) -> Member(p, off, b.size).also { off += b.size } })).encodeToByteArray()
        val out = ByteArray(PREFIX_BYTES + header.size + off.toInt())
        MAGIC.copyInto(out)
        out[4] = (header.size ushr 24).toByte(); out[5] = (header.size ushr 16).toByte(); out[6] = (header.size ushr 8).toByte(); out[7] = header.size.toByte()
        header.copyInto(out, PREFIX_BYTES)
        var at = PREFIX_BYTES + header.size
        for ((_, b) in members) { b.copyInto(out, at); at += b.size }
        return out
    }
}
