package com.auroratv.playback

import com.auroratv.core.AppError
import com.auroratv.core.Log
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.model.MediaSourceInfo
import com.auroratv.jellyfin.model.MediaStream
import com.auroratv.jellyfin.model.PlaybackInfoRequest
import com.auroratv.jellyfin.model.msToTicks

enum class PlayMethod(val jellyfinName: String, val label: String) {
    DirectPlay("DirectPlay", "Direct Play"),
    DirectStream("DirectStream", "Direct Stream (remux)"),
    Transcode("Transcode", "Transcode"),
}

/** Everything needed to start (or restart) playback of one media source. */
data class PlaybackPlan(
    val mediaSource: MediaSourceInfo,
    val playSessionId: String?,
    val method: PlayMethod,
    val url: String,
    val headers: Map<String, String>,
    val audioIndex: Int?,
    val subtitleIndex: Int?,
    /** Why the server chose to transcode, parsed from the TranscodingUrl (e.g. VideoCodecNotSupported). */
    val transcodeReasons: List<String>,
) {
    val audioStreams get() = mediaSource.audioStreams
    val subtitleStreams get() = mediaSource.subtitleStreams
}

/**
 * Wraps Jellyfin's PlaybackInfo negotiation: we send the measured device profile plus chosen streams;
 * the server answers with direct-play support or a transcoding URL tailored to the device.
 */
class PlaybackNegotiator(
    private val api: JellyfinApi,
    private val capabilities: DeviceCapabilities,
) {
    private val tag = "Negotiator"

    suspend fun negotiate(
        itemId: String,
        mediaSourceId: String?,
        startPositionMs: Long,
        audioIndex: Int?,
        subtitleIndex: Int?,
        maxBitrateMbps: Int,
        allowDirectPlay: Boolean,
        forceTranscode: Boolean = false,
    ): PlaybackPlan {
        val profile = DeviceProfileBuilder.build(capabilities, maxBitrateMbps, allowDirectPlay && !forceTranscode)
        val request = PlaybackInfoRequest(
            userId = api.userId ?: throw AppError.SessionExpired(),
            maxStreamingBitrate = profile.maxStreamingBitrate,
            startTimeTicks = startPositionMs.msToTicks(),
            audioStreamIndex = audioIndex,
            subtitleStreamIndex = subtitleIndex ?: -1,
            mediaSourceId = mediaSourceId,
            deviceProfile = profile,
            enableDirectPlay = allowDirectPlay && !forceTranscode,
            enableDirectStream = !forceTranscode,
            enableTranscoding = true,
        )
        val response = api.playbackInfo(itemId, request)
        response.errorCode?.let { code ->
            throw AppError.Playback(
                when (code) {
                    "NotAllowed" -> "Your account isn't allowed to play this item."
                    "NoCompatibleStream" -> "The server couldn't find a format this device can play, and transcoding isn't available."
                    "RateLimitExceeded" -> "Too many streams are active on this server right now."
                    else -> "The server refused playback ($code)."
                }
            )
        }
        val source = response.mediaSources.firstOrNull { mediaSourceId == null || it.id == mediaSourceId }
            ?: response.mediaSources.firstOrNull()
            ?: throw AppError.Playback("This item has no playable media.")

        return planFor(itemId, source, response.playSessionId, audioIndex, subtitleIndex)
    }

    fun planFor(itemId: String, source: MediaSourceInfo, playSessionId: String?, audioIndex: Int?, subtitleIndex: Int?): PlaybackPlan {
        val headers = api.mediaHeaders()
        val (method, url) = when {
            source.supportsDirectPlay && source.transcodingUrl == null ->
                PlayMethod.DirectPlay to api.directStreamUrl(itemId, source.id, source.container, playSessionId)
            source.transcodingUrl != null -> {
                // SupportsDirectStream means the server will copy the streams into a new container (remux).
                val m = if (source.supportsDirectStream) PlayMethod.DirectStream else PlayMethod.Transcode
                m to api.absolute(source.transcodingUrl)
            }
            source.supportsDirectPlay ->
                PlayMethod.DirectPlay to api.directStreamUrl(itemId, source.id, source.container, playSessionId)
            else -> throw AppError.Playback("The server offered no way to play this file on this device.")
        }
        val reasons = source.transcodingUrl?.let { parseTranscodeReasons(it) } ?: emptyList()
        Log.i(tag, "Play method ${method.jellyfinName} for ${source.container}; reasons=$reasons")
        return PlaybackPlan(source, playSessionId, method, url, headers, audioIndex, subtitleIndex, reasons)
    }

    companion object {
        fun parseTranscodeReasons(url: String): List<String> {
            val value = url.substringAfter("?", "").split('&')
                .firstOrNull { it.startsWith("TranscodeReasons=", ignoreCase = true) }
                ?.substringAfter('=') ?: return emptyList()
            return value.replace("%2C", ",", ignoreCase = true).split(',').filter { it.isNotBlank() }
        }

        /** 0-based ordinal of [stream] among embedded streams of the same type. */
        fun embeddedOrdinal(streams: List<MediaStream>, stream: MediaStream): Int? {
            if (stream.isExternal) return null
            val sameType = streams.filter { it.type == stream.type && !it.isExternal }.sortedBy { it.index }
            return sameType.indexOfFirst { it.index == stream.index }.takeIf { it >= 0 }
        }
    }
}
