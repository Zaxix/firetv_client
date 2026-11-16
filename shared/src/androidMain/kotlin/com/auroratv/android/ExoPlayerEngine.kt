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
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
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

/** A resume may start this far before the saved position (to the previous keyframe), never further. */
private const val MAX_KEYFRAME_REWIND_MS = 12_000L
/** Buffering with at least this much downloaded ahead counts as "stuck", not "waiting for data"… */
private const val STUCK_AHEAD_MS = 3_000L
/** …once it has lasted this long. */
private const val STUCK_AFTER_MS = 6_000L

/**
 * Media3 ExoPlayer engine with hardware MediaCodec decoding, the Jellyfin FFmpeg audio extension as
 * fallback, automatic HDMI passthrough, and libass (ass-media) rendering for ASS/SSA subtitles with
 * full styling, positioning, karaoke and animation.
 */
@OptIn(UnstableApi::class)
class ExoPlayerEngine(
    context: Context,
    assMode: AssRenderMode,
    matchFrameRate: Boolean = false,
    dolbyVision: com.auroratv.playback.DolbyVisionSupport = com.auroratv.playback.DolbyVisionSupport.None,
) : AndroidVideoEngine {
    private val tag = "ExoEngine"
    override val name = "Media3 ExoPlayer + libass"

    override val view: PlayerView = PlayerView(context).apply {
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

    /** What the stream fetched until the first frame: HTTP requests opened and bytes (loader threads). */
    private val startRequests = java.util.concurrent.atomic.AtomicInteger()
    private val startBytes = java.util.concurrent.atomic.AtomicLong()
    private val transferCounter = object : androidx.media3.datasource.TransferListener {
        override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) { if (isNetwork) startRequests.incrementAndGet() }
        override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) { if (isNetwork) startBytes.addAndGet(bytesTransferred.toLong()) }
        override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) = Unit
    }

    // The app's shared OkHttp pool (see SharedOkHttp): the stream reuses connections the API already opened.
    private val httpFactory: androidx.media3.datasource.HttpDataSource.Factory =
        if (com.auroratv.playback.ResumeTuning.sharedConnections) OkHttpDataSource.Factory(
            com.auroratv.jellyfin.SharedOkHttp.client.newBuilder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        ).setUserAgent("${AppInfo.NAME}/${AppInfo.VERSION} (Android)").setTransferListener(transferCounter)
        else androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("${AppInfo.NAME}/${AppInfo.VERSION} (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setTransferListener(transferCounter)

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
        // Up to 160 MB, but never more than about a third of what this device lets the app use: the
        // buffer lives on the Java heap, and running it close to the limit means constant garbage
        // collection pauses (stutter) on 1–1.5 GB Fire TV sticks.
        .setTargetBufferBytes(minOf(160L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 3).toInt())
        .setPrioritizeTimeOverSizeThresholds(false)
        .setBackBuffer(15_000, /* retainBackBufferFromKeyframe = */ true)
        .build()

    private val assRenderType = if (assMode == AssRenderMode.Full) AssRenderType.OVERLAY_OPEN_GL else AssRenderType.CUES
    // Cap libass rendering at 1080p: on 4K TVs this halves overlay cost with no visible loss at TV distance.
    private val assHandler = AssHandler(assRenderType, AssHandlerConfig(maxRenderPixels = 1920 * 1080))
    private val assParsers = AssSubtitleParserFactory(assHandler)
    private val extractors = DefaultExtractorsFactory().withAssMkvSupport(assParsers, assHandler)
    private val mediaSourceFactory = DefaultMediaSourceFactory(
        DefaultDataSource.Factory(context, httpFactory),
        extractors,
    ).setSubtitleParserFactory(assParsers)
    /** Same, reading a font-heavy file's start from the device when it's there (MediaHeaderCache). */
    private val headerCachedSourceFactory = MediaHeaderCache.factory(httpFactory)?.let { cached ->
        DefaultMediaSourceFactory(DefaultDataSource.Factory(context, cached), extractors).setSubtitleParserFactory(assParsers)
    }
    /** Fonts for the current item; (re)added after ass-media resets on each media item transition. */
    private var sourceFonts: List<com.auroratv.playback.SubtitleFont> = emptyList()

    /** Routes Dolby Vision tracks (native, profile 7 → 8.1, or base layer); see DolbyVisionPlayback.kt. */
    private val renderersFactory = DolbyVisionRenderersFactory(context, dolbyVision)

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
        .setMediaSourceFactory(mediaSourceFactory)
        .setRenderersFactory(
            renderersFactory
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
                .withAssSupport(assHandler)
        )
        .build()
        .also { p ->
            // Same wiring as ass-media's buildWithAssSupport, but keeping the AssHandler so fonts that
            // the server strips during transcoding can be added (see EngineSource.fonts).
            if (assRenderType == AssRenderType.OVERLAY_OPEN_GL) view.subtitleView?.withAssSupport(assHandler)
            assHandler.init(p)
        }

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
    /**
     * Resuming a direct-played file: where we were asked to start, until the file's index is read and
     * we can move to the keyframe just before it (see [startAtKeyframe]). 0 when there's nothing to do.
     */
    private var keyframeStartMs = 0L
    /** Set while that keyframe move is in flight, to undo it if the file's index points far back. */
    private var keyframeCheckMs = 0L
    /** Start-up timeline of the current item (elapsedRealtime ms), logged once at the first frame. */
    private var loadAt = 0L
    private var tracksAt = 0L
    private var readyAt = 0L
    private var firstFrameAt = 0L
    private var videoInitMs = -1L
    private var startLogged = true

    private fun logStartIfDone() {
        if (startLogged || firstFrameAt == 0L || readyAt == 0L) return
        startLogged = true
        fun since(t: Long) = if (t == 0L) "-" else "${t - loadAt} ms"
        Log.i("Perf", "Engine start: tracks known ${since(tracksAt)}, first frame ${since(firstFrameAt)}, ready ${since(readyAt)}; " +
            "${startRequests.get()} request(s), ${startBytes.get() / 1024} KB fetched; video decoder set up in ${if (videoInitMs >= 0) "$videoInitMs ms" else "-"}")
    }
    private val frameRate = if (matchFrameRate) (context as? android.app.Activity)?.let { FrameRateMatcher(it) } else null

    init {
        view.player = player
        // Scrubbing jumps to the nearest keyframe: near-instant instead of decoding up to the exact frame.
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                sourceFonts.forEach { assHandler.addFont(it.name, it.data) }
                if (sourceFonts.isNotEmpty()) Log.i(tag, "Gave ${sourceFonts.size} server font(s) to libass")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) { Player.STATE_IDLE -> "idle"; Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; else -> "ended" }
                Log.i(tag, "state=$name pos=${player.currentPosition} buffered=${player.bufferedPosition}")
                // The keyframe move is settled by now (adjustments arrive before READY); later seeks are the viewer's.
                if (playbackState == Player.STATE_READY) {
                    keyframeCheckMs = 0
                    if (readyAt == 0L) { readyAt = android.os.SystemClock.elapsedRealtime(); logStartIfDone() }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                frameRate?.let { m -> player.videoFormat?.frameRate?.takeIf { it > 0f }?.let(m::match) }
                if (!tracks.isEmpty && tracksAt == 0L) tracksAt = android.os.SystemClock.elapsedRealtime()
                if (!tracks.isEmpty) startAtKeyframe()
                if (!pendingApplied && !tracks.isEmpty) {
                    pendingApplied = true
                    pendingAudio?.let { selectAudio(it) }
                    selectSubtitle(pendingSubtitle)
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                val asked = keyframeCheckMs
                if (asked <= 0 || reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) return
                keyframeCheckMs = 0
                val back = asked - newPosition.positionMs
                if (back > MAX_KEYFRAME_REWIND_MS) {
                    // Keyframes (or the file's index) are too sparse: go to the exact spot after all.
                    Log.i(tag, "Resume keyframe was ${back / 1000} s back; starting at the exact position instead")
                    player.setSeekParameters(SeekParameters.EXACT)
                    player.seekTo(asked)
                    player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                } else {
                    Log.i(tag, "Resuming from the keyframe $back ms before the saved position")
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
                if (videoInitMs < 0) videoInitMs = initializationDurationMs
            }
            override fun onRendererReadyChanged(eventTime: AnalyticsListener.EventTime, rendererIndex: Int, rendererTrackType: Int, isRendererReady: Boolean) {
                rendererReady[rendererIndex] = rendererTrackType to isRendererReady
            }
            override fun onIsLoadingChanged(eventTime: AnalyticsListener.EventTime, isLoading: Boolean) {
                if (!isLoading && player.playbackState == Player.STATE_BUFFERING) Log.i(tag, "Stopped loading while buffering (buffered ${(player.bufferedPosition - player.currentPosition) / 1000.0} s ahead)")
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (firstFrameAt == 0L) { firstFrameAt = android.os.SystemClock.elapsedRealtime(); logStartIfDone() }
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
                watchStuck()
                delay(if (player.isPlaying) 250 else 500)
            }
        }
    }

    /** Per renderer: track type and whether it's letting playback be ready (Media3 reports changes). */
    private val rendererReady = HashMap<Int, Pair<Int, Boolean>>()
    private var stuckSince = 0L
    private var stuckNudged = false

    private fun trackTypeName(t: Int) = when (t) { C.TRACK_TYPE_VIDEO -> "video"; C.TRACK_TYPE_AUDIO -> "audio"; C.TRACK_TYPE_TEXT -> "subtitles"; else -> "type $t" }

    /**
     * Buffering although plenty is downloaded means one renderer (video, audio or subtitles) isn't
     * ready rather than the network. Logs which, and nudges the player once (a re-seek to where it
     * is, which resets the renderers); if still stuck, says so in the log for a bug report.
     */
    private fun watchStuck() {
        val now = android.os.SystemClock.elapsedRealtime()
        val ahead = player.bufferedPosition - player.currentPosition
        val stuck = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady && ahead >= STUCK_AHEAD_MS
        if (!stuck) { stuckSince = 0; if (player.isPlaying) stuckNudged = false; return }
        if (stuckSince == 0L) { stuckSince = now; return }
        val forMs = now - stuckSince
        if (forMs < STUCK_AFTER_MS) return
        val waiting = rendererReady.values.filter { !it.second }.joinToString { trackTypeName(it.first) }.ifEmpty { "none reported" }
        if (!stuckNudged) {
            stuckNudged = true
            Log.w(tag, "Stuck buffering ${forMs / 1000} s with ${ahead / 1000} s downloaded ahead (loading=${player.isLoading}); waiting on: $waiting. Nudging with a re-seek.")
            stuckSince = now
            player.seekTo(player.currentPosition)
        } else if (forMs >= STUCK_AFTER_MS * 2) {
            Log.w(tag, "Still stuck after the nudge (${ahead / 1000} s ahead, loading=${player.isLoading}); waiting on: $waiting; video=$videoDecoder audio=$audioDecoder")
            stuckSince = now
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
            .apply { source.cacheKey?.let { setCustomCacheKey(it) } }
            .setSubtitleConfigurations(subs)
            .build()
        // Fonts from MKV attachments (transcoded streams lose them). Added in onMediaItemTransition:
        // ass-media clears its pending fonts there, and our listener runs after its own.
        sourceFonts = source.fonts
        pendingAudio = source.audioOrdinal
        pendingSubtitle = source.subtitle
        pendingApplied = false
        videoDecoder = null; audioDecoder = null; droppedFrames = 0
        // Transcodes already begin exactly at the start position (the server cuts a keyframe there).
        keyframeStartMs = if (!source.isHls && source.startPositionMs > 0 && com.auroratv.playback.ResumeTuning.keyframeStart) source.startPositionMs else 0L
        loadAt = android.os.SystemClock.elapsedRealtime(); tracksAt = 0; readyAt = 0; firstFrameAt = 0; videoInitMs = -1; startLogged = false
        startRequests.set(0); startBytes.set(0)
        keyframeCheckMs = 0
        _state.update { EngineState(isBuffering = true) }
        val cachedFactory = headerCachedSourceFactory
        if (source.cacheKey != null && cachedFactory != null) player.setMediaSource(cachedFactory.createMediaSource(item), source.startPositionMs)
        else player.setMediaItem(item, source.startPositionMs)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Resume speed. Media3 always opens a file at the exact start position: it loads from the keyframe
     * before it, decodes every frame in between without showing them, and only then counts its 1.5 s
     * of start-up buffer. With keyframes 5–10 s apart that's tens of MB and seconds of hidden decoding,
     * which a fresh start (already on a keyframe) never pays. Once the file's index is known, move the
     * start back to that keyframe: the data being fetched already begins there, so playback can start
     * as soon as 1.5 s of it has arrived, a few seconds before where the viewer stopped.
     */
    private fun startAtKeyframe() {
        val asked = keyframeStartMs
        if (asked <= 0) return
        keyframeStartMs = 0
        if (!player.isCurrentMediaItemSeekable || player.playbackState == Player.STATE_READY) return
        keyframeCheckMs = asked
        player.setSeekParameters(SeekParameters.PREVIOUS_SYNC)
        player.seekTo(asked)
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
    }

    override fun play() { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) {
        // The viewer's own seek: the resume keyframe check is over (it used to treat a skip back
        // during a slow start as "keyframe too far back" and jump to the saved spot again).
        keyframeStartMs = 0; keyframeCheckMs = 0
        player.seekTo(positionMs)
    }

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

    override fun setSubtitleStyle(style: com.auroratv.playback.SubtitleStyle, hdr: Boolean) {
        val sv = view.subtitleView ?: return
        val edge = when (style.edge) {
            com.auroratv.playback.SubtitleEdge.Outline -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
            com.auroratv.playback.SubtitleEdge.Shadow -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            com.auroratv.playback.SubtitleEdge.None -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
        }
        sv.setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                style.textArgb(hdr).toInt(),
                (style.background.alpha shl 24), // box behind the text (transparent when off)
                Color.TRANSPARENT,
                edge,
                Color.BLACK,
                if (style.bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT,
            )
        )
        sv.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION + style.raisePercent / 100f)
    }

    override fun stats(): List<Pair<String, String>> = buildList {
        player.videoFormat?.let { f ->
            add("Decoded video" to listOfNotNull(f.sampleMimeType, f.codecs, "${f.width}x${f.height}", f.frameRate.takeIf { it > 0 }?.let { "${(it * 100).toInt() / 100.0} fps" }).joinToString(" · "))
            f.colorInfo?.let { c -> add("Colour" to "transfer=${c.colorTransfer} space=${c.colorSpace}") }
        }
        videoDecoder?.let { add("Video decoder" to it) }
        renderersFactory.session?.describe()?.let { add("HDR path" to it) }
        player.audioFormat?.let { f -> add("Decoded audio" to listOfNotNull(f.sampleMimeType, "${f.channelCount}ch", "${f.sampleRate} Hz").joinToString(" · ")) }
        audioDecoder?.let { add("Audio decoder" to it) }
        add("Dropped frames" to droppedFrames.toString())
        add("Buffered" to "${((player.bufferedPosition - player.currentPosition) / 1000).coerceAtLeast(0)} s")
    }

    override fun release() {
        frameRate?.restore()
        scope.cancel()
        view.player = null
        player.release()
    }
}
