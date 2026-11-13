package com.auroratv.jellyfin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MediaStream(
    @SerialName("Index") val index: Int,
    @SerialName("Type") val type: String,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsHearingImpaired") val isHearingImpaired: Boolean = false,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsTextSubtitleStream") val isTextSubtitleStream: Boolean = false,
    @SerialName("SupportsExternalStream") val supportsExternalStream: Boolean = false,
    @SerialName("DeliveryMethod") val deliveryMethod: String? = null,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("Channels") val channels: Int? = null,
    @SerialName("ChannelLayout") val channelLayout: String? = null,
    @SerialName("BitRate") val bitRate: Long? = null,
    @SerialName("BitDepth") val bitDepth: Int? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("Profile") val profile: String? = null,
    @SerialName("Level") val level: Double? = null,
    @SerialName("RealFrameRate") val realFrameRate: Double? = null,
    @SerialName("VideoRange") val videoRange: String? = null,
    @SerialName("VideoRangeType") val videoRangeType: String? = null,
    @SerialName("VideoDoViTitle") val videoDoViTitle: String? = null,
) {
    val isVideo get() = type == "Video"
    val isAudio get() = type == "Audio"
    val isSubtitle get() = type == "Subtitle"
    val isEmbedded get() = !isExternal
}

@Serializable
data class MediaSourceInfo(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("Protocol") val protocol: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("TranscodingSubProtocol") val transcodingSubProtocol: String? = null,
    @SerialName("TranscodingContainer") val transcodingContainer: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream> = emptyList(),
    @SerialName("DefaultAudioStreamIndex") val defaultAudioStreamIndex: Int? = null,
    @SerialName("DefaultSubtitleStreamIndex") val defaultSubtitleStreamIndex: Int? = null,
    @SerialName("IsRemote") val isRemote: Boolean = false,
    @SerialName("ETag") val eTag: String? = null,
) {
    val videoStream get() = mediaStreams.firstOrNull { it.isVideo }
    val audioStreams get() = mediaStreams.filter { it.isAudio }
    val subtitleStreams get() = mediaStreams.filter { it.isSubtitle }
}

@Serializable
data class PlaybackInfoRequest(
    @SerialName("UserId") val userId: String,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Long? = null,
    @SerialName("StartTimeTicks") val startTimeTicks: Long? = null,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("DeviceProfile") val deviceProfile: DeviceProfile,
    @SerialName("EnableDirectPlay") val enableDirectPlay: Boolean = true,
    @SerialName("EnableDirectStream") val enableDirectStream: Boolean = true,
    @SerialName("EnableTranscoding") val enableTranscoding: Boolean = true,
    @SerialName("AllowVideoStreamCopy") val allowVideoStreamCopy: Boolean = true,
    @SerialName("AllowAudioStreamCopy") val allowAudioStreamCopy: Boolean = true,
    @SerialName("AutoOpenLiveStream") val autoOpenLiveStream: Boolean = true,
)

@Serializable
data class PlaybackInfoResponse(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)

@Serializable
data class PlaybackReport(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long = 0,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("IsMuted") val isMuted: Boolean = false,
    @SerialName("CanSeek") val canSeek: Boolean = true,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
    @SerialName("PlayMethod") val playMethod: String? = null,
    @SerialName("RepeatMode") val repeatMode: String = "RepeatNone",
)
