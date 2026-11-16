package com.auroratv

import com.auroratv.jellyfin.model.MediaAttachment
import com.auroratv.jellyfin.model.MediaSourceInfo
import com.auroratv.playback.MediaHeader
import com.auroratv.playback.PlayMethod
import com.auroratv.playback.PlaybackPlan
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Finding (and keeping) the start of font-heavy MKVs. */
class MediaHeaderTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** EBML header, Segment (unknown size), Void, Attachments (256 bytes of "fonts"), then the first Cluster. */
    private val mkv: ByteArray = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x84, 1, 2, 3, 4) +
        bytes(0x18, 0x53, 0x80, 0x67, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF) +
        bytes(0xEC, 0x82, 0, 0) +
        bytes(0x19, 0x41, 0xA4, 0x69, 0x41, 0x00) + ByteArray(256) { 7 } +
        bytes(0x1F, 0x43, 0xB6, 0x75, 0x81, 0) + ByteArray(64)

    @Test
    fun walksTheTopLevelElementsToTheFirstCluster() = runTest {
        val reads = mutableListOf<Long>()
        val offset = MediaHeader.firstClusterOffset { pos, len ->
            reads += pos
            if (pos >= mkv.size) null else mkv.copyOfRange(pos.toInt(), minOf(mkv.size, pos.toInt() + len))
        }
        assertEquals(287L, offset, "after the 256 bytes of attachments, without reading them")
        assertEquals(listOf(0L, 9L, 21L, 25L, 287L), reads, "only element headers are fetched")
    }

    @Test
    fun notMatroskaIsLeftAlone() = runTest {
        assertNull(MediaHeader.firstClusterOffset { _, len -> ByteArray(len) { 0x30 } })
    }

    private fun plan(method: PlayMethod, container: String, attachments: Int) = PlaybackPlan(
        mediaSource = MediaSourceInfo(id = "ms1", container = container, size = 1_500_000_000, eTag = "abc",
            mediaAttachments = List(attachments) { MediaAttachment(index = it, fileName = "f$it.ttf") }),
        playSessionId = "ps", method = method, url = "http://x", headers = emptyMap(), audioIndex = null, subtitleIndex = null, transcodeReasons = emptyList(),
    )

    @Test
    fun onlyDirectPlayMkvsWithAttachmentsAreKept() {
        assertNotNull(MediaHeader.cacheKey(plan(PlayMethod.DirectPlay, "mkv", 12)))
        assertEquals("hdr:ms1:1500000000:abc", MediaHeader.cacheKey(plan(PlayMethod.DirectPlay, "mkv", 12)), "names the exact file version")
        assertNull(MediaHeader.cacheKey(plan(PlayMethod.DirectPlay, "mkv", 0)), "no fonts: nothing big ahead of the video")
        assertNull(MediaHeader.cacheKey(plan(PlayMethod.Transcode, "mkv", 12)), "transcodes stream from the server's start point")
        assertNull(MediaHeader.cacheKey(plan(PlayMethod.DirectPlay, "mp4", 3)))
    }
}
