package com.auroratv

import com.auroratv.playback.Codecs
import com.auroratv.playback.DeviceProfileBuilder
import com.auroratv.playback.DolbyVisionCodecs
import com.auroratv.playback.DolbyVisionSupport
import com.auroratv.playback.DoviRoute
import com.auroratv.playback.HdrFormat
import com.auroratv.playback.HevcBitstream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Typical devices: what each Dolby Vision flavour may be sent as, and how the player routes it. */
class DolbyVisionTest {
    /** Fire TV 4K Max on a Dolby Vision TV: decoder declares 5 and 8, libdovi available. */
    private val fireTvDv = fireTv4k.copy(
        hdr = setOf(HdrFormat.HDR10, HdrFormat.HLG, HdrFormat.DOLBY_VISION),
        dolbyVision = DolbyVisionSupport(display = true, decoderProfiles = setOf(5, 8), canConvertProfile7 = true, masksHdr10Plus = true),
    )
    /** Same box without libdovi for its ABI. */
    private val fireTvDvNoConvert = fireTvDv.copy(dolbyVision = fireTvDv.dolbyVision.copy(canConvertProfile7 = false))
    /** Shield: profile 7 decoded natively. */
    private val shield = fireTvDv.copy(dolbyVision = DolbyVisionSupport(display = true, decoderProfiles = setOf(5, 7, 8)))
    /** DV decoder present but the TV only does HDR10 (fireTv4k fixture has HDR10 + HLG). */
    private val hdr10Tv = fireTv4k.copy(dolbyVision = DolbyVisionSupport(display = false, decoderProfiles = setOf(5, 8)))
    private val sdrTv = fireTv4k.copy(hdr = emptySet())

    private fun ranges(caps: com.auroratv.playback.DeviceCapabilities, codec: String = Codecs.HEVC) =
        DeviceProfileBuilder.build(caps, 0).codecProfiles.first { it.codec == codec }.conditions.first { it.property == "VideoRangeType" }.value.split('|').toSet()

    @Test
    fun dolbyVisionTvGetsEveryProfileItCanShow() {
        val r = ranges(fireTvDv)
        for (t in listOf("DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithEL", "DOVIWithHDR10Plus", "DOVIWithELHDR10Plus")) assertTrue(t in r, t)
        assertFalse("DOVIInvalid" in r, "invalid signalling is left to the server")
        assertEquals(DoviRoute.Native, fireTvDv.dolbyVision.routeFor(8))
        assertEquals(DoviRoute.Native, fireTvDv.dolbyVision.routeFor(5))
        assertEquals(DoviRoute.ConvertToProfile8, fireTvDv.dolbyVision.routeFor(7))
    }

    @Test
    fun profile7WithoutConversionStillDirectPlaysAsHdr10BaseLayer() {
        assertEquals(DoviRoute.BaseLayer, fireTvDvNoConvert.dolbyVision.routeFor(7))
        assertTrue("DOVIWithEL" in ranges(fireTvDvNoConvert))
        assertEquals(DoviRoute.Native, shield.dolbyVision.routeFor(7))
    }

    @Test
    fun hdr10OnlyTvPlaysBaseLayersAndLeavesProfile5ToTheServer() {
        val r = ranges(hdr10Tv)
        assertFalse("DOVI" in r, "profile 5 has no base layer")
        assertTrue("DOVIWithHDR10" in r && "DOVIWithEL" in r && "DOVIWithHLG" in r)
        assertEquals(DoviRoute.BaseLayer, hdr10Tv.dolbyVision.routeFor(8))
        assertEquals(DoviRoute.BaseLayer, hdr10Tv.dolbyVision.routeFor(7))
        assertEquals(DoviRoute.Unsupported, hdr10Tv.dolbyVision.routeFor(5))
    }

    @Test
    fun sdrTvOnlyGetsSdrCompatibleDolbyVision() {
        val r = ranges(sdrTv)
        assertEquals(setOf("SDR", "DOVIWithSDR"), r)
    }

    @Test
    fun av1Profile10NeedsTheDvDecoderForTheNonCompatibleVariant() {
        assertFalse("DOVI" in ranges(fireTvDv, Codecs.AV1), "decoder doesn't declare profile 10")
        assertTrue("DOVIWithHDR10" in ranges(fireTvDv, Codecs.AV1))
        val av1Dv = fireTvDv.copy(dolbyVision = fireTvDv.dolbyVision.copy(decoderProfiles = setOf(8, 10)))
        assertTrue("DOVI" in ranges(av1Dv, Codecs.AV1))
        assertFalse(ranges(fireTvDv, Codecs.VP9).any { it.startsWith("DOVI") }, "VP9 has no Dolby Vision")
    }

    @Test
    fun codecStrings() {
        assertEquals(7, DolbyVisionCodecs.profile("dvhe.07.06"))
        assertEquals(8, DolbyVisionCodecs.profile("dvh1.08.09"))
        assertEquals(10, DolbyVisionCodecs.profile("dav1.10.06"))
        assertNull(DolbyVisionCodecs.profile("hvc1.2.4.L153.B0"))
        assertNull(DolbyVisionCodecs.profile(null))
        assertEquals("dvhe.08.06", DolbyVisionCodecs.withProfile("dvhe.07.06", 8))
        assertEquals(Codecs.AV1, DolbyVisionCodecs.baseCodec("dav1.10.01"))
        assertEquals(Codecs.HEVC, DolbyVisionCodecs.baseCodec("dvh1.08.06"))
    }

    // ---- Bitstream editing ----

    private fun nal(type: Int, vararg payload: Int, fourByte: Boolean = true): ByteArray {
        val sc = if (fourByte) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)
        return sc + byteArrayOf((type shl 1).toByte(), 1) + payload.map { it.toByte() }.toByteArray()
    }

    private val hdr10PlusPayload = intArrayOf(0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04, 0x01, 0x40)
    private fun sei(vararg messages: IntArray): ByteArray {
        val body = messages.flatMap { m -> listOf(4, m.size) + m.toList() } + 0x80
        return nal(HevcBitstream.NAL_PREFIX_SEI, *body.toIntArray())
    }

    @Test
    fun removesEnhancementLayerAndKeepsTheRest() {
        val au = nal(35, 0x50) + nal(39, 0x05, 0x01, 0xAA, 0x80) + nal(1, 0x11, 0x22, 0x33) + nal(63, 0x01, 0x02, 0x03, 0x04) + nal(62, 0x09, 0x08, fourByte = false) + nal(63, 0x05)
        val expected = nal(35, 0x50) + nal(39, 0x05, 0x01, 0xAA, 0x80) + nal(1, 0x11, 0x22, 0x33) + nal(62, 0x09, 0x08, fourByte = false)
        val buf = au.copyOf(au.size + 16)
        val n = HevcBitstream.removeNalType(buf, au.size, HevcBitstream.NAL_DOVI_EL)
        assertContentEquals(expected, buf.copyOf(n))
        assertTrue(HevcBitstream.containsNalType(buf, n, HevcBitstream.NAL_DOVI_RPU))
        assertFalse(HevcBitstream.containsNalType(buf, n, HevcBitstream.NAL_DOVI_EL))
        assertEquals(n, HevcBitstream.removeNalType(buf, n, HevcBitstream.NAL_DOVI_EL), "nothing left to remove")
    }

    @Test
    fun startCodeScanHandlesZeroRunsInsidePayloads() {
        // 00 00 03 is emulation prevention, not a start code; payload bytes > 1 exercise the skip-ahead.
        val au = nal(1, 0x00, 0x00, 0x03, 0x01, 0x00, 0x00, 0x03, 0x00, 0x07, 0xFF) + nal(63, 0x10)
        val n = HevcBitstream.removeNalType(au, au.size, HevcBitstream.NAL_DOVI_EL)
        assertContentEquals(nal(1, 0x00, 0x00, 0x03, 0x01, 0x00, 0x00, 0x03, 0x00, 0x07, 0xFF), au.copyOf(n))
    }

    @Test
    fun masksOnlyHdr10PlusSeiMessages() {
        val cc = intArrayOf(0xB5, 0x00, 0x31, 0x47, 0x41, 0x39, 0x34) // A/53 captions: must stay
        val au = sei(cc, hdr10PlusPayload) + nal(1, 0x44, 0x55) + sei(hdr10PlusPayload)
        val before = au.copyOf()
        assertEquals(1, HevcBitstream.maskHdr10PlusSei(au, au.size), "suffix-of-slice SEI is after the first VCL unit and left alone")
        val diff = au.indices.filter { au[it] != before[it] }
        assertEquals(1, diff.size, "exactly one byte changes")
        assertEquals(HevcBitstream.SEI_MASKED_PAYLOAD_TYPE.toByte(), au[diff.single()])
        assertEquals(0, HevcBitstream.maskHdr10PlusSei(au, au.size), "already masked")
    }

    @Test
    fun seiWalkSkipsEmulationPreventionBytes() {
        // A first message whose payload contains 00 00 03 xx; the HDR10+ message after it must still be found.
        val first = intArrayOf(0x00, 0x00, 0x03, 0x01, 0x22) // raw bytes: 4 RBSP bytes + one EP byte
        val body = listOf(5, 4) + first.toList() + listOf(4, hdr10PlusPayload.size) + hdr10PlusPayload.toList() + 0x80
        val au = nal(39, *body.toIntArray()) + nal(19, 0x01)
        assertEquals(1, HevcBitstream.maskHdr10PlusSei(au, au.size))
    }

    @Test
    fun nonAnnexBDataIsLeftAlone() {
        val data = byteArrayOf(0, 0, 0, 12, 0x40, 0x01, 0x0C, 0x01)
        assertEquals(data.size, HevcBitstream.removeNalType(data, data.size, 63))
        assertEquals(0, HevcBitstream.maskHdr10PlusSei(data, data.size))
    }
}
