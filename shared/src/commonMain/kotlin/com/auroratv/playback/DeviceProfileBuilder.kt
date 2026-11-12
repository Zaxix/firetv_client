package com.auroratv.playback

import com.auroratv.jellyfin.model.CodecProfile
import com.auroratv.jellyfin.model.DeviceProfile
import com.auroratv.jellyfin.model.DirectPlayProfile
import com.auroratv.jellyfin.model.ProfileCondition
import com.auroratv.jellyfin.model.SubtitleProfile
import com.auroratv.jellyfin.model.TranscodingProfile

/**
 * Converts measured [DeviceCapabilities] into a Jellyfin [DeviceProfile]. The server then decides
 * Direct Play → Direct Stream (remux) → Transcode, so we only claim what the device really handles.
 */
object DeviceProfileBuilder {
    const val UNLIMITED_BITRATE = 200_000_000L

    fun build(caps: DeviceCapabilities, maxBitrateMbps: Int, allowDirectPlay: Boolean = true): DeviceProfile {
        val bitrate = if (maxBitrateMbps <= 0) UNLIMITED_BITRATE else maxBitrateMbps * 1_000_000L
        val videoCodecs = caps.video.map { it.codec }
        val audioCodecs = caps.audioCodecs.sorted()

        val directPlay = if (!allowDirectPlay) emptyList() else buildList {
            caps.containers.sorted().forEach { container ->
                add(
                    DirectPlayProfile(
                        container = container,
                        type = "Video",
                        videoCodec = videoCodecs.joinToString(","),
                        audioCodec = audioCodecs.joinToString(","),
                    )
                )
            }
            add(DirectPlayProfile(container = "mp3,flac,aac,m4a,ogg,opus,wav", type = "Audio"))
        }

        val transcodeVideo = buildList {
            if (caps.videoSupport(Codecs.HEVC)?.hardware == true) add(Codecs.HEVC)
            add(Codecs.H264)
        }
        // AAC is the universal fallback; add surround codecs the device can decode or pass through.
        val transcodeAudio = buildList {
            add("aac")
            if ("ac3" in caps.audioCodecs) add("ac3")
            if ("eac3" in caps.audioCodecs) add("eac3")
            add("mp3")
        }
        val hlsContainer = if (Codecs.HEVC in transcodeVideo) "mp4" else "ts"
        val transcoding = listOf(
            TranscodingProfile(
                container = hlsContainer,
                type = "Video",
                videoCodec = transcodeVideo.joinToString(","),
                audioCodec = transcodeAudio.joinToString(","),
                protocol = "hls",
                maxAudioChannels = caps.maxAudioChannels.toString(),
                minSegments = 1,
                breakOnNonKeyFrames = true,
            ),
            TranscodingProfile(container = "mp3", type = "Audio", videoCodec = "", audioCodec = "mp3", protocol = "http", maxAudioChannels = "2"),
        )

        val rangeTypes = caps.allowedRangeTypes().joinToString("|")
        val codecProfiles = buildList {
            caps.video.forEach { v ->
                val conditions = buildList {
                    add(ProfileCondition("LessThanEqual", "Width", v.maxWidth.toString()))
                    add(ProfileCondition("LessThanEqual", "Height", v.maxHeight.toString()))
                    add(ProfileCondition("LessThanEqual", "VideoBitDepth", v.maxBitDepth.toString()))
                    if (v.profiles.isNotEmpty()) add(ProfileCondition("EqualsAny", "VideoProfile", v.profiles.joinToString("|")))
                    if (v.codec in HDR_CAPABLE_CODECS) add(ProfileCondition("EqualsAny", "VideoRangeType", rangeTypes))
                }
                add(CodecProfile(type = "Video", codec = v.codec, conditions = conditions))
            }
            add(
                CodecProfile(
                    type = "VideoAudio",
                    conditions = listOf(ProfileCondition("LessThanEqual", "AudioChannels", caps.maxAudioChannels.toString())),
                )
            )
        }

        val subtitleProfiles = buildList {
            caps.subtitleEmbedded.sorted().forEach { add(SubtitleProfile(it, "Embed")) }
            caps.subtitleExternal.sorted().forEach { add(SubtitleProfile(it, "External")) }
            // Image formats we can't render are burned in by the server rather than dropped.
            (ALL_SUBTITLE_FORMATS - caps.subtitleEmbedded - caps.subtitleExternal).sorted().forEach { add(SubtitleProfile(it, "Encode")) }
        }

        return DeviceProfile(
            name = "Aurora TV (${caps.engineName})",
            maxStreamingBitrate = bitrate,
            maxStaticBitrate = bitrate,
            directPlayProfiles = directPlay,
            transcodingProfiles = transcoding,
            codecProfiles = codecProfiles,
            subtitleProfiles = subtitleProfiles,
        )
    }

    private val HDR_CAPABLE_CODECS = setOf(Codecs.HEVC, Codecs.AV1, Codecs.VP9)
    val ALL_SUBTITLE_FORMATS = setOf("ass", "ssa", "srt", "subrip", "vtt", "webvtt", "pgssub", "dvdsub", "dvbsub", "sub", "smi", "ttml", "mov_text")
}
