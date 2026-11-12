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
) {
    fun videoSupport(codec: String) = video.firstOrNull { it.codec == codec }
    val supportsHevc get() = videoSupport(Codecs.HEVC) != null
    val supportsHevcMain10 get() = (videoSupport(Codecs.HEVC)?.maxBitDepth ?: 0) >= 10
    val audioCodecs get() = audioDecode + audioPassthrough

    /** Video range types Jellyfin may send untouched. */
    fun allowedRangeTypes(): List<String> {
        val result = mutableListOf("SDR")
        val formats = if (canToneMapHdr) HdrFormat.entries.toSet() else hdr
        if (HdrFormat.HDR10 in formats) result += "HDR10"
        if (HdrFormat.HDR10_PLUS in formats || HdrFormat.HDR10 in formats) result += "HDR10Plus"
        if (HdrFormat.HLG in formats) result += "HLG"
        if (HdrFormat.DOLBY_VISION in formats) result += HdrFormat.DOLBY_VISION.jellyfinRangeTypes
        else {
            // Dolby Vision files with a backwards-compatible base layer can still play as that base layer.
            if (HdrFormat.HDR10 in formats) result += "DOVIWithHDR10"
            if (HdrFormat.HLG in formats) result += "DOVIWithHLG"
            result += "DOVIWithSDR"
        }
        return result.distinct()
    }
}
