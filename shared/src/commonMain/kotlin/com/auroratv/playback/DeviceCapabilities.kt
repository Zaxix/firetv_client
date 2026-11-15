package com.auroratv.playback

/** Jellyfin codec names. */
object Codecs {
    const val H264 = "h264"
    const val HEVC = "hevc"
    const val VP9 = "vp9"
    const val AV1 = "av1"
    const val MPEG2 = "mpeg2video"
    const val VC1 = "vc1"
    const val MPEG4 = "mpeg4"
}

enum class HdrFormat(val jellyfinRangeTypes: List<String>) {
    HDR10(listOf("HDR10")),
    HDR10_PLUS(listOf("HDR10Plus")),
    HLG(listOf("HLG")),
    DOLBY_VISION(listOf("DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR")),
}

data class VideoCodecSupport(
    val codec: String,
    val hardware: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    /** Highest supported bit depth (8 or 10). */
    val maxBitDepth: Int,
    /** Jellyfin profile names the decoder accepts, e.g. "main", "main 10". Empty = no restriction. */
    val profiles: List<String> = emptyList(),
    val decoderName: String? = null,
)

/**
 * What this device can really decode/render, discovered at runtime. Used to build the Jellyfin
 * DeviceProfile and shown verbatim in Settings → About so nothing is overstated.
 */
data class DeviceCapabilities(
    val engineName: String,
    val video: List<VideoCodecSupport>,
    /** HDR formats the display + decoder can present without tone-mapping. */
    val hdr: Set<HdrFormat>,
    /** Whether HDR content may be sent even when [hdr] lacks it because the engine tone-maps (mpv). */
    val canToneMapHdr: Boolean,
    /** Audio codecs decodable to PCM on-device. */
    val audioDecode: Set<String>,
    /** Audio codecs the connected output accepts as bitstream (passthrough). */
    val audioPassthrough: Set<String>,
    val maxAudioChannels: Int,
    val containers: Set<String>,
    /** Subtitle formats the player can render when embedded in the container. */
    val subtitleEmbedded: Set<String>,
    /** Subtitle formats the player can load as external files. */
    val subtitleExternal: Set<String>,
    /** True when ASS/SSA are rendered by libass with full styling. */
    val libassRendering: Boolean,
    val supportsSubtitleDelay: Boolean,
    val notes: List<String> = emptyList(),
    /** Dolby Vision decoder/display support (Android); mpv on Windows uses [canToneMapHdr]. */
    val dolbyVision: DolbyVisionSupport = DolbyVisionSupport.None,
) {
    fun videoSupport(codec: String) = video.firstOrNull { it.codec == codec }
    val supportsHevc get() = videoSupport(Codecs.HEVC) != null
    val supportsHevcMain10 get() = (videoSupport(Codecs.HEVC)?.maxBitDepth ?: 0) >= 10
    val audioCodecs get() = audioDecode + audioPassthrough

    /**
     * Video range types Jellyfin may send untouched for [codec]. Every Dolby Vision flavour is
     * allowed when it has somewhere correct to go: the DV decoder ([DolbyVisionSupport.native]),
     * the profile 7 → 8.1 conversion, or its HDR10/HLG/SDR base layer on a display that shows
     * that. Profile 5 / AV1 10.0 have no base layer, so they need a real DV path (or local tone
     * mapping on mpv for Windows); otherwise the server converts them. DOVIInvalid (DV signalling
     * that contradicts the base layer) is always left to the server.
     */
    fun allowedRangeTypes(codec: String = Codecs.HEVC): List<String> {
        val result = mutableListOf("SDR")
        if (codec == Codecs.H264) return result
        if (canToneMapHdr) {
            result += listOf("HDR10", "HDR10Plus", "HLG")
            if (codec != Codecs.VP9) result += listOf("DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithHDR10Plus")
            if (codec == Codecs.HEVC) result += listOf("DOVIWithEL", "DOVIWithELHDR10Plus")
            return result
        }
        val hdr10 = HdrFormat.HDR10 in hdr
        val hlg = HdrFormat.HLG in hdr
        if (hdr10) result += listOf("HDR10", "HDR10Plus")
        if (hlg) result += "HLG"
        val dv = dolbyVision
        when (codec) {
            Codecs.HEVC -> {
                if (dv.native(5)) result += "DOVI"
                val p8 = dv.native(8)
                if (p8 || hdr10) result += listOf("DOVIWithHDR10", "DOVIWithHDR10Plus")
                if (p8 || hlg) result += "DOVIWithHLG"
                result += "DOVIWithSDR"
                val p7 = dv.routeFor(7) != DoviRoute.BaseLayer
                if (p7 || hdr10) result += listOf("DOVIWithEL", "DOVIWithELHDR10Plus")
            }
            Codecs.AV1 -> {
                val p10 = dv.native(10)
                if (p10) result += "DOVI"
                if (p10 || hdr10) result += listOf("DOVIWithHDR10", "DOVIWithHDR10Plus")
                if (p10 || hlg) result += "DOVIWithHLG"
                result += "DOVIWithSDR"
            }
        }
        return result.distinct()
    }
}
