package com.auroratv.playback

import kotlinx.coroutines.flow.StateFlow

/** A subtitle the engine should show. */
sealed interface SubtitleTrackRef {
    /** The [ordinal]-th subtitle track inside the container (0-based, in stream order). */
    data class Embedded(val ordinal: Int, val codec: String?) : SubtitleTrackRef

    /** A sidecar subtitle file served by Jellyfin. [id] is stable for the session. */
    data class External(
        val id: String,
        val url: String,
        val codec: String,
        val language: String?,
        val label: String,
    ) : SubtitleTrackRef
}

/** A font file for libass, e.g. from an MKV attachment the server stripped while transcoding. */
class SubtitleFont(val name: String, val data: ByteArray)

data class EngineSource(
    val url: String,
    val headers: Map<String, String>,
    val startPositionMs: Long,
    /** 0-based audio track ordinal within the container, or null for the container default. */
    val audioOrdinal: Int?,
    val subtitle: SubtitleTrackRef?,
    /** All external subtitles to register so they can be switched without reloading. */
    val externalSubtitles: List<SubtitleTrackRef.External>,
    val isHls: Boolean,
    val title: String,
    /**
     * Fonts the ASS/SSA subtitles need when the container isn't played directly (transcode or
     * remux drops MKV attachments). Empty for Direct Play: engines read attachments from the file.
     */
    val fonts: List<SubtitleFont> = emptyList(),
    /** Set for files whose big start (fonts) is kept on the device; see MediaHeader. */
    val cacheKey: String? = null,
)

data class EngineState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val isEnded: Boolean = false,
    val error: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

/**
 * Native playback engine abstraction: Media3 ExoPlayer on Android, libmpv on Windows. The shared
 * [PlayerController] drives it; engines never talk to Jellyfin themselves.
 */
interface PlaybackEngine {
    val name: String
    val state: StateFlow<EngineState>

    fun load(source: EngineSource)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun selectAudio(ordinal: Int)
    fun selectSubtitle(ref: SubtitleTrackRef?)
    /** Positive values show subtitles later. Only called when capabilities report support. */
    fun setSubtitleDelay(delayMs: Long)
    fun setSubtitleScale(scale: Float)
    /** Look of plain text subtitles; [hdr] dims the text for HDR video. ASS/SSA keep their own styles. */
    fun setSubtitleStyle(style: SubtitleStyle, hdr: Boolean) {}
    /** Live technical details for the playback info panel (decoder, resolution, dropped frames...). */
    fun stats(): List<Pair<String, String>>
    fun release()
}
