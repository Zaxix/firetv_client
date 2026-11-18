package com.auroratv.playback

/**
 * How a Dolby Vision track reaches the screen on this device.
 *
 * - [Native]: the device's Dolby Vision decoder handles the profile and the display is in DV mode.
 * - [ConvertToProfile8]: profile 7 (UHD Blu-ray, dual layer) on a decoder that only takes profile 8.
 *   The RPU is rewritten to profile 8.1 and the enhancement layer dropped on the fly (libdovi), so
 *   the display still switches to Dolby Vision. Nothing is re-encoded.
 * - [BaseLayer]: no usable Dolby Vision path, but the stream has a standard base layer (HDR10, HLG,
 *   SDR). The track is handed to the plain HEVC/AVC/AV1 decoder, which shows that base layer.
 * - [Unsupported]: no base layer to fall back to (profile 5, AV1 profile 10.0). The server is never
 *   allowed to send these unless [Native] applies, so this only shows up for odd files.
 */
enum class DoviRoute { Native, ConvertToProfile8, BaseLayer, Unsupported }

/**
 * Dolby Vision support measured on the device.
 *
 * @param display the connected display accepts a Dolby Vision signal.
 * @param decoderProfiles Dolby Vision profile numbers (5, 7, 8, 9, 10…) the DV decoder declares.
 * @param canConvertProfile7 libdovi is available, so profile 7 can be rewritten to 8.1.
 * @param masksHdr10Plus this model's DV pipeline mishandles streams that also carry HDR10+
 *   metadata (Fire TV 4K / 4K Max, media3 issue #11096); the HDR10+ SEI messages are masked so
 *   the decoder sees plain Dolby Vision.
 */
data class DolbyVisionSupport(
    val display: Boolean = false,
    val decoderProfiles: Set<Int> = emptySet(),
    val canConvertProfile7: Boolean = false,
    val masksHdr10Plus: Boolean = false,
) {
    fun native(profile: Int): Boolean = display && profile in decoderProfiles

    fun routeFor(profile: Int): DoviRoute = when {
        native(profile) -> DoviRoute.Native
        (profile == 7 || profile == 4) && canConvertProfile7 && native(8) -> DoviRoute.ConvertToProfile8
        profile in BASE_LAYER_PROFILES -> DoviRoute.BaseLayer
        else -> DoviRoute.Unsupported
    }

    /** One line for Settings → About. */
    val summary: String
        get() = when {
            decoderProfiles.isEmpty() -> "No Dolby Vision decoder: DV files play their HDR10/HLG/SDR base layer"
            !display -> "Decoder supports profiles ${decoderProfiles.sorted().joinToString()}, but the display doesn't accept Dolby Vision: base layer is used"
            else -> buildList {
                add("Profiles ${decoderProfiles.sorted().joinToString()}")
                if (7 !in decoderProfiles && canConvertProfile7 && 8 in decoderProfiles) add("profile 7 → 8.1 conversion")
                if (masksHdr10Plus) add("HDR10+ masked in DV streams")
            }.joinToString(" · ")
        }

    companion object {
        val None = DolbyVisionSupport()

        /** Profiles whose base layer is a standard HDR10/HLG/SDR stream (4, 7, 8, 9; 10 except 10.0). */
        val BASE_LAYER_PROFILES = setOf(4, 7, 8, 9, 10)
    }
}

/** Dolby Vision codec strings: `dvhe.08.06`, `dvh1.07.06`, `dvav.09.05`, `dav1.10.06`. */
object DolbyVisionCodecs {
    private val HEVC_PREFIXES = setOf("dvhe", "dvh1")
    private val AVC_PREFIXES = setOf("dvav", "dva1")
    private const val AV1_PREFIX = "dav1"

    /** The profile number in [codecs], or null when it isn't a Dolby Vision codec string. */
    fun profile(codecs: String?): Int? {
        if (codecs == null || codecs.length < 7 || codecs[4] != '.') return null
        val prefix = codecs.substring(0, 4).lowercase()
        if (prefix !in HEVC_PREFIXES && prefix !in AVC_PREFIXES && prefix != AV1_PREFIX) return null
        return codecs.substring(5, 7).toIntOrNull()
    }

    /** `dvhe.07.06` → `dvhe.08.06` (sample entry prefix and level kept). */
    fun withProfile(codecs: String, profile: Int): String =
        codecs.substring(0, 5) + profile.toString().padStart(2, '0') + codecs.substring(7)

    /** Jellyfin name of the codec carrying the base layer. */
    fun baseCodec(codecs: String): String? = when (codecs.take(4).lowercase()) {
        in HEVC_PREFIXES -> Codecs.HEVC
        in AVC_PREFIXES -> Codecs.H264
        AV1_PREFIX -> Codecs.AV1
        else -> null
    }
}
