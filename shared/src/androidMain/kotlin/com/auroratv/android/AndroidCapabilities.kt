package com.auroratv.android

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.auroratv.core.Log
import com.auroratv.playback.Codecs
import com.auroratv.playback.DeviceCapabilities
import com.auroratv.playback.DolbyVisionSupport
import com.auroratv.playback.HdrFormat
import com.auroratv.playback.VideoCodecSupport

/**
 * Measures what this Fire TV / Android TV can actually do: hardware decoders (and their max size,
 * bit depth and profiles), the display's HDR modes, audio decoders, and which bitstream formats the
 * connected HDMI sink accepts for passthrough. Nothing is assumed per device model.
 */
object AndroidCapabilities {
    private const val TAG = "Caps"

    private data class Probe(val jellyfin: String, val mime: String)

    private val VIDEO = listOf(
        Probe(Codecs.H264, MimeTypes.VIDEO_H264),
        Probe(Codecs.HEVC, MimeTypes.VIDEO_H265),
        Probe(Codecs.VP9, MimeTypes.VIDEO_VP9),
        Probe(Codecs.AV1, MimeTypes.VIDEO_AV1),
        Probe(Codecs.MPEG2, MimeTypes.VIDEO_MPEG2),
        Probe(Codecs.VC1, "video/wvc1"),
        Probe(Codecs.MPEG4, MimeTypes.VIDEO_MP4V),
    )

    @OptIn(UnstableApi::class)
    fun probe(context: Context): DeviceCapabilities {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        val notes = mutableListOf<String>()

        val video = VIDEO.mapNotNull { p -> probeVideo(codecs, p) }
        val hdr = probeHdr(context, codecs)
        val dolbyVision = probeDolbyVision(codecs, HdrFormat.DOLBY_VISION in hdr)
        if (video.none { it.codec == Codecs.HEVC }) notes += "No HEVC decoder found: HEVC content will be transcoded by the server."
        else if (video.first { it.codec == Codecs.HEVC }.maxBitDepth < 10) notes += "HEVC decoder is 8-bit only: Main10 content will be transcoded."
        if (hdr.isEmpty()) notes += "Display did not report HDR support: HDR content is tone-mapped by the server."

        // Audio: platform decoders + FFmpeg extension (software) + HDMI passthrough.
        val audioMimes = mapOf(
            "aac" to MimeTypes.AUDIO_AAC, "mp3" to MimeTypes.AUDIO_MPEG, "flac" to MimeTypes.AUDIO_FLAC, "opus" to MimeTypes.AUDIO_OPUS,
            "vorbis" to MimeTypes.AUDIO_VORBIS, "ac3" to MimeTypes.AUDIO_AC3, "eac3" to MimeTypes.AUDIO_E_AC3, "dts" to MimeTypes.AUDIO_DTS,
            "truehd" to MimeTypes.AUDIO_TRUEHD, "alac" to MimeTypes.AUDIO_ALAC, "pcm_s16le" to MimeTypes.AUDIO_RAW,
        )
        val ffmpeg = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
        val audioDecode = audioMimes.filter { (_, mime) ->
            codecs.any { info -> info.supportedTypes.any { it.equals(mime, true) } } ||
                (ffmpeg && runCatching { FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false))
        }.keys.toMutableSet()
        audioDecode += setOf("aac", "mp3") // always decodable by MediaCodec on Android TV
        if (!ffmpeg) notes += "FFmpeg audio extension unavailable: DTS/TrueHD need passthrough or server transcoding."

        @Suppress("DEPRECATION")
        val audioCaps = runCatching { AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null) }.getOrNull()
        val passthrough = mutableSetOf<String>()
        if (audioCaps != null) {
            if (audioCaps.supportsEncoding(C.ENCODING_AC3)) passthrough += "ac3"
            if (audioCaps.supportsEncoding(C.ENCODING_E_AC3) || audioCaps.supportsEncoding(C.ENCODING_E_AC3_JOC)) passthrough += "eac3"
            if (audioCaps.supportsEncoding(C.ENCODING_DTS) || audioCaps.supportsEncoding(C.ENCODING_DTS_HD)) passthrough += "dts"
            if (audioCaps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) passthrough += "truehd"
        }
        val maxChannels = maxOf(audioCaps?.maxChannelCount ?: 2, if (passthrough.isNotEmpty()) 6 else 2, if (ffmpeg) 6 else 2).coerceAtMost(8)

        val caps = DeviceCapabilities(
            engineName = "Media3 ExoPlayer ${androidx.media3.common.MediaLibraryInfo.VERSION}",
            video = video,
            hdr = hdr,
            canToneMapHdr = false,
            audioDecode = audioDecode,
            audioPassthrough = passthrough,
            maxAudioChannels = maxChannels,
            containers = setOf("mp4", "m4v", "mkv", "webm", "mov", "ts", "mpegts", "m2ts", "avi", "ogg"),
            // libass (ass-media) renders ASS/SSA; Media3 renders the rest natively.
            subtitleEmbedded = setOf("ass", "ssa", "srt", "subrip", "vtt", "webvtt", "pgssub", "dvdsub", "dvbsub", "mov_text", "ttml"),
            subtitleExternal = setOf("ass", "ssa", "srt", "subrip", "vtt", "webvtt", "ttml"),
            libassRendering = true,
            supportsSubtitleDelay = false,
            dolbyVision = dolbyVision,
            notes = notes + "Subtitle delay is not supported by the Android engine; use the server's subtitle tools if timing is off.",
        )
        Log.i(TAG, "Android ${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}: $caps")
        return caps
    }

    /**
     * Capabilities when libmpv plays instead of ExoPlayer. Video stays limited to what MediaCodec
     * decodes in hardware (software HEVC/4K is too slow on TV boxes), but FFmpeg decodes every
     * common audio codec, mpv demuxes every common container, renders bitmap subtitles (PGS,
     * VobSub) itself, and supports subtitle delay. ASS/SSA goes through libass in both engines.
     */
    fun forMpv(base: DeviceCapabilities): DeviceCapabilities = base.copy(
        engineName = "libmpv 0.42 (MediaCodec + FFmpeg, libass)",
        audioDecode = base.audioDecode + setOf("aac", "mp3", "mp2", "flac", "opus", "vorbis", "ac3", "eac3", "dts", "truehd", "alac", "pcm_s16le", "pcm_s24le", "wmav2", "wmapro"),
        maxAudioChannels = maxOf(base.maxAudioChannels, 6),
        containers = base.containers + setOf("wmv", "asf", "flv", "mpeg", "mpg", "vob", "3gp", "m2v", "ogv"),
        subtitleEmbedded = base.subtitleEmbedded + setOf("pgssub", "dvdsub", "dvbsub", "mov_text"),
        subtitleExternal = base.subtitleExternal + setOf("sub", "smi", "sup"),
        libassRendering = true,
        supportsSubtitleDelay = true,
        // mpv decodes Dolby Vision files as their base layer (it has no Android DV output path);
        // profile 5 is left to the server, which converts it.
        dolbyVision = DolbyVisionSupport.None,
        notes = base.notes.filterNot { it.startsWith("Subtitle delay") || it.startsWith("FFmpeg audio extension") },
    )

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.") && !info.name.contains(".sw.")

    private fun probeVideo(codecs: List<MediaCodecInfo>, p: Probe): VideoCodecSupport? {
        val candidates = codecs.filter { info -> info.supportedTypes.any { it.equals(p.mime, ignoreCase = true) } }
        if (candidates.isEmpty()) return null
        val best = candidates.sortedByDescending { if (isHardware(it)) 1 else 0 }.first()
        val hw = isHardware(best)
        val caps = runCatching { best.getCapabilitiesForType(p.mime) }.getOrNull() ?: return null
        val vc = caps.videoCapabilities ?: return null
        var maxW = vc.supportedWidths.upper
        var maxH = vc.supportedHeights.upper
        // Software decoders can't keep up with 4K on TV SoCs; cap them at 1080p so the server transcodes.
        if (!hw) { maxW = minOf(maxW, 1920); maxH = minOf(maxH, 1080) }
        if (hw && vc.isSizeSupported(3840, 2160)) { maxW = maxOf(maxW, 3840); maxH = maxOf(maxH, 2160) }
        val profiles = caps.profileLevels.map { it.profile }.toSet()
        val tenBit = when (p.jellyfin) {
            Codecs.HEVC -> CodecProfileLevel.HEVCProfileMain10 in profiles || CodecProfileLevel.HEVCProfileMain10HDR10 in profiles
            Codecs.VP9 -> CodecProfileLevel.VP9Profile2 in profiles || CodecProfileLevel.VP9Profile2HDR in profiles
            Codecs.AV1 -> CodecProfileLevel.AV1ProfileMain10 in profiles || CodecProfileLevel.AV1ProfileMain10HDR10 in profiles
            else -> false
        }
        val jfProfiles = when (p.jellyfin) {
            Codecs.H264 -> listOf("high", "main", "baseline", "constrained baseline")
            Codecs.HEVC -> if (tenBit) listOf("main", "main 10") else listOf("main")
            else -> emptyList()
        }
        return VideoCodecSupport(
            codec = p.jellyfin, hardware = hw, maxWidth = maxW, maxHeight = maxH,
            maxBitDepth = if (tenBit) 10 else 8, profiles = jfProfiles, decoderName = best.name,
        )
    }

    private fun probeHdr(context: Context, codecs: List<MediaCodecInfo>): Set<HdrFormat> {
        if (Build.VERSION.SDK_INT < 24) return emptySet()
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptySet()
        @Suppress("DEPRECATION")
        val types = display.hdrCapabilities?.supportedHdrTypes?.toSet() ?: emptySet()
        val result = mutableSetOf<HdrFormat>()
        if (Display.HdrCapabilities.HDR_TYPE_HDR10 in types) result += HdrFormat.HDR10
        if (Display.HdrCapabilities.HDR_TYPE_HLG in types) result += HdrFormat.HLG
        if (Build.VERSION.SDK_INT >= 29 && Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types) result += HdrFormat.HDR10_PLUS
        if (Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types) result += HdrFormat.DOLBY_VISION
        return result
    }

    /** Dolby Vision profile number for each MediaCodec DV profile constant. */
    private val DV_PROFILES = mapOf(
        CodecProfileLevel.DolbyVisionProfileDvheDtr to 4,
        CodecProfileLevel.DolbyVisionProfileDvheStn to 5,
        CodecProfileLevel.DolbyVisionProfileDvheDtb to 7,
        CodecProfileLevel.DolbyVisionProfileDvheSt to 8,
        CodecProfileLevel.DolbyVisionProfileDvavSe to 9,
        CodecProfileLevel.DolbyVisionProfileDvav110 to 10,
    )

    /** Fire TV 4K (1st/2nd gen) and 4K Max (1st/2nd gen): DV + HDR10+ streams misbehave (media3 #11096). */
    private val HDR10_PLUS_IN_DV_BUG_MODELS = setOf("AFTMM", "AFTKA", "AFTKM", "AFTKRT")

    /**
     * Which Dolby Vision profiles the decoder declares, whether the display takes DV, and whether
     * profile 7 can be converted. Profile 7 is only worth converting where the decoder takes 8 but
     * not 7 (Fire TV, Chromecast, most TVs); the Shield decodes 7 without declaring it.
     */
    private fun probeDolbyVision(codecs: List<MediaCodecInfo>, display: Boolean): DolbyVisionSupport {
        val profiles = mutableSetOf<Int>()
        codecs.filter { info -> info.supportedTypes.any { it.equals(MimeTypes.VIDEO_DOLBY_VISION, true) } }.forEach { info ->
            runCatching { info.getCapabilitiesForType(MimeTypes.VIDEO_DOLBY_VISION).profileLevels }.getOrNull()
                ?.forEach { pl -> DV_PROFILES[pl.profile]?.let(profiles::add) }
        }
        if (Build.MODEL.startsWith("SHIELD Android TV") && 8 in profiles) profiles += 7
        val needsConversion = display && 8 in profiles && 7 !in profiles
        val support = DolbyVisionSupport(
            display = display,
            decoderProfiles = profiles,
            canConvertProfile7 = needsConversion && LibDoviLoader.available,
            masksHdr10Plus = Build.MANUFACTURER.equals("Amazon", true) && Build.MODEL in HDR10_PLUS_IN_DV_BUG_MODELS,
        )
        Log.i(TAG, "Dolby Vision: display=$display decoderProfiles=${profiles.sorted()} convertP7=${support.canConvertProfile7} maskHdr10Plus=${support.masksHdr10Plus}")
        return support
    }
}
