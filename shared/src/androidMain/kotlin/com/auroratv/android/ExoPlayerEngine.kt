package com.auroratv.android

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.auroratv.AppInfo
import com.auroratv.core.Log
import com.auroratv.playback.EngineSource
import com.auroratv.playback.EngineState
import com.auroratv.playback.PlaybackEngine
import com.auroratv.playback.SubtitleTrackRef
import com.auroratv.settings.AssRenderMode
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.kt.buildWithAssSupport
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Media3 ExoPlayer engine with hardware MediaCodec decoding, the Jellyfin FFmpeg audio extension as
 * fallback, automatic HDMI passthrough, and libass (ass-media) rendering for ASS/SSA subtitles with
 * full styling, positioning, karaoke and animation.
 */
@OptIn(UnstableApi::class)
class ExoPlayerEngine(context: Context, assMode: AssRenderMode) : PlaybackEngine {
    private val tag = "ExoEngine"
    override val name = "Media3 ExoPlayer + libass"

    val view: PlayerView = PlayerView(context).apply {
        useController = false
        setShutterBackgroundColor(Color.BLACK)
        setBackgroundColor(Color.BLACK)
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        keepScreenOn = true
        isFocusable = false
        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        subtitleView?.apply {
            setApplyEmbeddedStyles(true)
            setApplyEmbeddedFontSizes(true)
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
        }
    }

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("AuroraTV/${AppInfo.VERSION} (Android)")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)

    private val trackSelector = DefaultTrackSelector(context).apply {
        setParameters(buildUponParameters().setTunnelingEnabled(false).build())
    }

    /**
     * Deeper buffering than Media3's defaults (50 s max): keeps up to 2 minutes / ~160 MB ahead so
     * network hiccups and slow server transcodes don't stall playback, starts after 1.5 s, and keeps
     * 15 s behind the playhead so short rewinds (and "skip back") don't re-fetch.
     */
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 30_000,
            /* maxBufferMs = */ 120_000,
            /* bufferForPlaybackMs = */ 1_500,
            /* bufferForPlaybackAfterRebufferMs = */ 4_000,
        )
        .setTargetBufferBytes(160 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .setBackBuffer(15_000, /* retainBackBufferFromKeyframe = */ true)
        .build()

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .buildWithAssSupport(
            context = context,
            renderType = if (assMode == AssRenderMode.Full) AssRenderType.OVERLAY_OPEN_GL else AssRenderType.CUES,
            // Cap libass rendering at 1080p: on 4K TVs this halves overlay cost with no visible loss at TV distance.
            config = AssHandlerConfig(maxRenderPixels = 1920 * 1080),
            subtitleView = view.subtitleView,
            dataSourceFactory = DefaultDataSource.Factory(context, httpFactory),
            extractorsFactory = DefaultExtractorsFactory(),
            renderersFactory = DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true),
        )

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingAudio: Int? = null
    private var pendingSubtitle: SubtitleTrackRef? = null
    private var pendingApplied = false
    private var externalIds = mapOf<String, String>() // our ref id -> Media3 format id
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var droppedFrames = 0

    init {
        view.player = player
        // Scrubbing jumps to the nearest keyframe: near-instant instead of decoding up to the exact frame.
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()

            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) { Player.STATE_IDLE -> "idle"; Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; else -> "ended" }
                Log.i(tag, "state=$name pos=${player.currentPosition} buffered=${player.bufferedPosition}")
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (!pendingApplied && !tracks.isEmpty) {
                    pendingApplied = true
                    pendingAudio?.let { selectAudio(it) }
                    selectSubtitle(pendingSubtitle)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(tag, "Playback error ${error.errorCodeName}", error)
                _state.update { it.copy(error = friendlyError(error), isPlaying = false, isBuffering = false) }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoder = decoderName
            }
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDecoder = decoderName
            }
            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean,
            ) {
                // ExoPlayer retries failed loads silently, which looks like endless buffering; log the
                // path (never the query string, which can carry an api key) and the cause.
                val status = (error as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                Log.w(tag, "Load error ${loadEventInfo.uri.path} status=${status ?: "-"} ${error.javaClass.simpleName}: ${error.message?.take(120)}")
            }
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                this@ExoPlayerEngine.droppedFrames += droppedFrames
            }
        })
        scope.launch {
            while (isActive) {
                publish()
                delay(if (player.isPlaying) 250 else 500)
            }
        }
    }

    private fun publish() {
        val size = player.videoSize
        // StateFlow drops equal values, so an idle/paused player doesn't wake the UI at all.
        _state.value = EngineState(
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            bufferedMs = player.bufferedPosition.coerceAtLeast(0),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE,
            isEnded = player.playbackState == Player.STATE_ENDED,
            error = _state.value.error,
            videoWidth = size.width,
            videoHeight = size.height,
        )
    }

    private fun friendlyError(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "This device couldn't decode the video or audio. Transcoding on the server usually fixes this."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "The connection to the server was lost."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused the stream. The transcode may have failed on the server."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "This file's container couldn't be read on this device."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "Audio output failed. Try turning off passthrough on your receiver or transcoding."
        else -> "Playback failed (${e.errorCodeName})."
    }

    private fun subtitleMime(codec: String) = when (codec) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    override fun load(source: EngineSource) {
        httpFactory.setDefaultRequestProperties(source.headers)
        // Sideloaded subtitle ids are numeric and well above any embedded track id (ass-media requirement).
        externalIds = source.externalSubtitles.mapIndexed { i, ext -> ext.id to (1000 + i).toString() }.toMap()
        val subs = source.externalSubtitles.map { ext ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(ext.url))
                .setMimeType(subtitleMime(ext.codec))
                .setLanguage(ext.language)
                .setLabel(ext.label)
                .setId(externalIds.getValue(ext.id))
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(source.url)
            .apply { if (source.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(subs)
            .build()
        pendingAudio = source.audioOrdinal
        pendingSubtitle = source.subtitle
        pendingApplied = false
        videoDecoder = null; audioDecoder = null; droppedFrames = 0
        _state.update { EngineState(isBuffering = true) }
        player.setMediaItem(item, source.startPositionMs)
        player.prepare()
        player.playWhenReady = true
    }

    override fun play() { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    private fun groups(type: Int) = player.currentTracks.groups.filter { it.type == type }

    override fun selectAudio(ordinal: Int) {
        val group = groups(C.TRACK_TYPE_AUDIO).getOrNull(ordinal) ?: run { pendingAudio = ordinal; return }
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                .build()
        )
    }

    override fun selectSubtitle(ref: SubtitleTrackRef?) {
        pendingSubtitle = ref
        val builder = trackSelector.buildUponParameters()
        if (ref == null) {
            trackSelector.setParameters(builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT).build())
            return
        }
        val text = groups(C.TRACK_TYPE_TEXT)
        val externalFormatIds = externalIds.values.toSet()
        fun Format.isExternal() = id?.let { fid -> externalFormatIds.any { fid == it || fid.endsWith(":$it") } } == true
        val group = when (ref) {
            is SubtitleTrackRef.Embedded -> text.filter { !it.getTrackFormat(0).isExternal() }.getOrNull(ref.ordinal)
            is SubtitleTrackRef.External -> {
                val fid = externalIds[ref.id]
                text.firstOrNull { g -> g.getTrackFormat(0).id.let { it == fid || it?.endsWith(":$fid") == true } }
            }
        }
        if (group == null) {
            Log.w(tag, "Subtitle track not found yet: $ref (have ${text.size} text groups)")
            return
        }
        trackSelector.setParameters(
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                .build()
        )
    }

    override fun setSubtitleDelay(delayMs: Long) {
        // Not supported by Media3's text pipeline; capabilities report this so the UI hides the control.
    }

    override fun setSubtitleScale(scale: Float) {
        view.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
    }

    override fun stats(): List<Pair<String, String>> = buildList {
        player.videoFormat?.let { f ->
            add("Decoded video" to listOfNotNull(f.sampleMimeType, f.codecs, "${f.width}x${f.height}", f.frameRate.takeIf { it > 0 }?.let { "${(it * 100).toInt() / 100.0} fps" }).joinToString(" · "))
            f.colorInfo?.let { c -> add("Colour" to "transfer=${c.colorTransfer} space=${c.colorSpace}") }
        }
        videoDecoder?.let { add("Video decoder" to it) }
        player.audioFormat?.let { f -> add("Decoded audio" to listOfNotNull(f.sampleMimeType, "${f.channelCount}ch", "${f.sampleRate} Hz").joinToString(" · ")) }
        audioDecoder?.let { add("Audio decoder" to it) }
        add("Dropped frames" to droppedFrames.toString())
        add("Buffered" to "${((player.bufferedPosition - player.currentPosition) / 1000).coerceAtLeast(0)} s")
    }

    override fun release() {
        scope.cancel()
        view.player = null
        player.release()
    }
}
