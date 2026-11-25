package com.auroratv.jellyfin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Jellyfin DeviceProfile (10.9+ schema). Sent with PlaybackInfo so the server can choose a play method. */
@Serializable
data class DeviceProfile(
    @SerialName("Name") val name: String,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Long,
    @SerialName("MaxStaticBitrate") val maxStaticBitrate: Long,
    @SerialName("MusicStreamingTranscodingBitrate") val musicStreamingTranscodingBitrate: Long = 384_000,
    @SerialName("DirectPlayProfiles") val directPlayProfiles: List<DirectPlayProfile>,
    @SerialName("TranscodingProfiles") val transcodingProfiles: List<TranscodingProfile>,
    @SerialName("ContainerProfiles") val containerProfiles: List<ContainerProfile> = emptyList(),
    @SerialName("CodecProfiles") val codecProfiles: List<CodecProfile>,
    @SerialName("SubtitleProfiles") val subtitleProfiles: List<SubtitleProfile>,
)

@Serializable
data class DirectPlayProfile(
    @SerialName("Container") val container: String,
    @SerialName("Type") val type: String,
    @SerialName("VideoCodec") val videoCodec: String? = null,
    @SerialName("AudioCodec") val audioCodec: String? = null,
)

@Serializable
data class TranscodingProfile(
    @SerialName("Container") val container: String,
    @SerialName("Type") val type: String,
    @SerialName("VideoCodec") val videoCodec: String,
    @SerialName("AudioCodec") val audioCodec: String,
    @SerialName("Protocol") val protocol: String,
    @SerialName("Context") val context: String = "Streaming",
    @SerialName("MaxAudioChannels") val maxAudioChannels: String? = null,
    @SerialName("MinSegments") val minSegments: Int = 1,
    @SerialName("BreakOnNonKeyFrames") val breakOnNonKeyFrames: Boolean = true,
    @SerialName("CopyTimestamps") val copyTimestamps: Boolean = false,
    @SerialName("EnableSubtitlesInManifest") val enableSubtitlesInManifest: Boolean = false,
    @SerialName("Conditions") val conditions: List<ProfileCondition> = emptyList(),
)

@Serializable
data class ContainerProfile(
    @SerialName("Type") val type: String,
    @SerialName("Container") val container: String,
    @SerialName("Conditions") val conditions: List<ProfileCondition>,
)

@Serializable
data class CodecProfile(
    @SerialName("Type") val type: String,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Conditions") val conditions: List<ProfileCondition> = emptyList(),
    @SerialName("ApplyConditions") val applyConditions: List<ProfileCondition> = emptyList(),
)

@Serializable
data class ProfileCondition(
    @SerialName("Condition") val condition: String,
    @SerialName("Property") val property: String,
    @SerialName("Value") val value: String,
    @SerialName("IsRequired") val isRequired: Boolean = false,
)

@Serializable
data class SubtitleProfile(
    @SerialName("Format") val format: String,
    @SerialName("Method") val method: String,
)
