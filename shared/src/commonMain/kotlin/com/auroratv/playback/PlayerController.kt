package com.auroratv.playback

import com.auroratv.core.AppError
import com.auroratv.core.Log
import com.auroratv.core.appResult
import com.auroratv.core.toAppError
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.ChapterInfo
import com.auroratv.jellyfin.model.MediaSegmentDto
import com.auroratv.jellyfin.model.MediaStream
import com.auroratv.jellyfin.model.PlaybackReport
import com.auroratv.jellyfin.model.msToTicks
import com.auroratv.jellyfin.model.ticksToMs
import com.auroratv.settings.ResumeBehavior
import com.auroratv.settings.Settings
import com.auroratv.settings.SkipIntroMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val item: BaseItemDto? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val plan: PlaybackPlan? = null,
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null,
    val subtitleDelayMs: Long = 0,
    val subtitleScale: Float = 1f,
    val segments: List<MediaSegmentDto> = emptyList(),
    val chapters: List<ChapterInfo> = emptyList(),
    val nextEpisode: BaseItemDto? = null,
    val ended: Boolean = false,
) {
    val audioStreams: List<MediaStream> get() = plan?.audioStreams.orEmpty()
    val subtitleStreams: List<MediaStream> get() = plan?.subtitleStreams.orEmpty()

    fun activeSegment(positionMs: Long): MediaSegmentDto? = segments.firstOrNull {
        positionMs >= it.startTicks.ticksToMs() && positionMs < it.endTicks.ticksToMs() - 1_000
    }
}

/**
 * Coordinates one playback session: negotiation, engine control, track switching, resume, intro
 * skipping, next-episode lookup and progress reporting to Jellyfin. Shared by Android and Windows.
 */
class PlayerController(
    private val api: JellyfinApi,
    private val engine: PlaybackEngine,
    private val capabilities: DeviceCapabilities,
    private val settings: () -> Settings,
    /** Application-lifetime scope so the final "stopped" report survives the screen closing. */
    private val appScope: CoroutineScope,
) {
    private val tag = "Player"
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private val negotiator = PlaybackNegotiator(api, capabilities)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    val engineState: StateFlow<EngineState> get() = engine.state

    private var reportJob: Job? = null
    private var startReported = false
    private var stoppedReported = false
    private val autoSkipped = mutableSetOf<String>()
    private var currentItemId: String? = null

    fun start(itemId: String, startFromBeginning: Boolean = false) {
        currentItemId = itemId
        startReported = false
        stoppedReported = false
        autoSkipped.clear()
        val s = settings()
        _state.value = PlayerUiState(loading = true, subtitleDelayMs = s.subtitleDelayMs, subtitleScale = s.subtitleScale)
        scope.launch {
            val result = appResult {
                val item = api.item(itemId)
                val source = item.mediaSources.firstOrNull()
                val streams = source?.mediaStreams ?: item.mediaStreams
                val audio = TrackSelector.pickAudio(streams, s.preferredAudioLanguage, source?.defaultAudioStreamIndex)
                val audioLang = streams.firstOrNull { it.index == audio }?.language
                val subtitle = TrackSelector.pickSubtitle(streams, s.subtitleMode, s.preferredSubtitleLanguage, audioLang, source?.defaultSubtitleStreamIndex)
                val startMs = if (startFromBeginning || s.resumeBehavior == ResumeBehavior.StartOver) 0L else item.resumePositionMs
                val plan = negotiator.negotiate(itemId, source?.id, startMs, audio, subtitle, s.maxBitrateMbps, s.enableDirectPlay)
                _state.update { it.copy(item = item, plan = plan, audioIndex = audio, subtitleIndex = subtitle, chapters = item.chapters) }
                loadEngine(plan, startMs)
                // Secondary lookups never block playback.
                launch { _state.update { it.copy(segments = runCatching { api.mediaSegments(itemId) }.getOrDefault(emptyList())) } }
                launch { _state.update { it.copy(nextEpisode = findNextEpisode(item)) } }
            }
            result.onFailure { e ->
                val err = e.toAppError()
                Log.e(tag, "Failed to start playback", e)
                _state.update { it.copy(loading = false, error = err.userMessage) }
            }
        }
        observeEngine()
    }

    private fun loadEngine(plan: PlaybackPlan, startMs: Long) {
        val streams = plan.mediaSource.mediaStreams
        val externals = streams.filter { it.isSubtitle && it.deliveryMethod == "External" && it.deliveryUrl != null }
            .filter { (it.codec ?: "").lowercase() in capabilities.subtitleExternal }
            .map { externalRef(it) }
        val audioOrdinal = if (plan.method == PlayMethod.DirectPlay)
            plan.audioIndex?.let { idx -> streams.firstOrNull { it.index == idx } }?.let { PlaybackNegotiator.embeddedOrdinal(streams, it) }
        else null
        val subtitleRef = subtitleRefFor(plan, plan.subtitleIndex)
        val source = EngineSource(
            url = plan.url,
            headers = plan.headers,
            // Transcoded HLS streams already start at StartTimeTicks on the server side.
            startPositionMs = if (plan.method == PlayMethod.DirectPlay) startMs else startMs,
            audioOrdinal = audioOrdinal,
            subtitle = subtitleRef,
            externalSubtitles = externals,
            isHls = plan.method != PlayMethod.DirectPlay,
            title = _state.value.item?.name ?: "",
        )
        engine.load(source)
        val st = _state.value
        if (capabilities.supportsSubtitleDelay) engine.setSubtitleDelay(st.subtitleDelayMs)
        engine.setSubtitleScale(st.subtitleScale)
        _state.update { it.copy(loading = false, error = null, ended = false) }
    }

    private fun externalRef(stream: MediaStream) = SubtitleTrackRef.External(
        id = "ext-${stream.index}",
        url = api.absolute(stream.deliveryUrl!!),
        codec = (stream.codec ?: "srt").lowercase(),
        language = stream.language,
        label = TrackSelector.label(stream),
    )

    /** How [index] can be shown without asking the server for a new stream, or null if it can't. */
    private fun subtitleRefFor(plan: PlaybackPlan, index: Int?): SubtitleTrackRef? {
        index ?: return null
        val streams = plan.mediaSource.mediaStreams
        val stream = streams.firstOrNull { it.index == index } ?: return null
        val codec = (stream.codec ?: "").lowercase()
        return when {
            stream.deliveryMethod == "External" && stream.deliveryUrl != null && codec in capabilities.subtitleExternal -> externalRef(stream)
            plan.method == PlayMethod.DirectPlay && !stream.isExternal && codec in capabilities.subtitleEmbedded ->
                PlaybackNegotiator.embeddedOrdinal(streams, stream)?.let { SubtitleTrackRef.Embedded(it, codec) }
            else -> null // burned in by the server (Encode) or not deliverable
        }
    }

    private var engineObserver: Job? = null

    private fun observeEngine() {
        if (engineObserver != null) return
        engineObserver = scope.launch {
            engine.state.distinctUntilChangedBy { Triple(it.isPlaying, it.isEnded, it.error) }.collect { es ->
                val st = _state.value
                val plan = st.plan ?: return@collect
                if (es.error != null) {
                    _state.update { it.copy(error = es.error) }
                    return@collect
                }
                if (es.isPlaying && !startReported) {
                    startReported = true
                    report(plan, es, start = true)
                    startProgressLoop()
                } else if (startReported && !es.isEnded) {
                    report(plan, es) // pause/resume reported immediately
                }
                if (es.isEnded && !st.ended) {
                    _state.update { it.copy(ended = true) }
                    reportStopped(ended = true)
                }
            }
        }
        scope.launch {
            // Auto-skip intros when enabled. Checked twice a second; cheap.
            while (isActive) {
                delay(500)
                if (settings().skipIntro != SkipIntroMode.Auto) continue
                val seg = _state.value.activeSegment(engine.state.value.positionMs) ?: continue
                if (seg.type == "Intro" || seg.type == "Recap") {
                    val key = "${currentItemId}:${seg.startTicks}"
                    if (autoSkipped.add(key)) engine.seekTo(seg.endTicks.ticksToMs())
                }
            }
        }
    }

    private fun startProgressLoop() {
        reportJob?.cancel()
        reportJob = scope.launch {
            while (isActive) {
                delay(10_000)
                val plan = _state.value.plan ?: continue
                val es = engine.state.value
                if (es.isPlaying) report(plan, es)
            }
        }
    }

    private fun buildReport(plan: PlaybackPlan, es: EngineState, positionMs: Long = es.positionMs) = PlaybackReport(
        itemId = currentItemId ?: "",
        mediaSourceId = plan.mediaSource.id,
        playSessionId = plan.playSessionId,
        positionTicks = positionMs.msToTicks(),
        isPaused = !es.isPlaying,
        audioStreamIndex = _state.value.audioIndex,
        subtitleStreamIndex = _state.value.subtitleIndex ?: -1,
        playMethod = plan.method.jellyfinName,
    )

    private fun report(plan: PlaybackPlan, es: EngineState, start: Boolean = false) {
        val r = buildReport(plan, es)
        appScope.launch {
            runCatching { if (start) api.reportStart(r) else api.reportProgress(r) }
                .onFailure { Log.w(tag, "Progress report failed", it) }
        }
    }

    private fun reportStopped(ended: Boolean) {
        if (stoppedReported) return
        val plan = _state.value.plan ?: return
        stoppedReported = true
        reportJob?.cancel()
        val es = engine.state.value
        val pos = if (ended && es.durationMs > 0) es.durationMs else es.positionMs
        val r = buildReport(plan, es, pos)
        val sessionId = plan.playSessionId
        val transcoding = plan.method != PlayMethod.DirectPlay
        appScope.launch {
            runCatching { api.reportStopped(r) }.onFailure { Log.w(tag, "Stop report failed", it) }
            if (transcoding && sessionId != null) api.stopTranscoding(sessionId)
        }
    }

    // ---- Controls -----------------------------------------------------------------------------

    fun togglePlayPause() { if (engine.state.value.isPlaying) engine.pause() else engine.play() }
    fun play() = engine.play()
    fun pause() = engine.pause()

    fun seekTo(positionMs: Long) {
        val duration = engine.state.value.durationMs
        engine.seekTo(if (duration > 0) positionMs.coerceIn(0, duration - 500) else positionMs.coerceAtLeast(0))
    }

    fun seekBy(deltaMs: Long) = seekTo(engine.state.value.positionMs + deltaMs)

    fun skipSegment() {
        val seg = _state.value.activeSegment(engine.state.value.positionMs) ?: return
        engine.seekTo(seg.endTicks.ticksToMs())
    }

    fun selectAudio(index: Int) {
        val st = _state.value
        val plan = st.plan ?: return
        if (index == st.audioIndex) return
        val stream = plan.audioStreams.firstOrNull { it.index == index } ?: return
        _state.update { it.copy(audioIndex = index) }
        if (plan.method == PlayMethod.DirectPlay) {
            PlaybackNegotiator.embeddedOrdinal(plan.mediaSource.mediaStreams, stream)?.let { engine.selectAudio(it); return }
        }
        renegotiate(audioIndex = index, subtitleIndex = st.subtitleIndex)
    }

    fun selectSubtitle(index: Int?) {
        val st = _state.value
        val plan = st.plan ?: return
        if (index == st.subtitleIndex) return
        val previousBurned = st.subtitleIndex != null && subtitleRefFor(plan, st.subtitleIndex) == null
        _state.update { it.copy(subtitleIndex = index) }
        if (index == null) {
            engine.selectSubtitle(null)
            if (previousBurned) renegotiate(st.audioIndex, null)
            return
        }
        val ref = subtitleRefFor(plan, index)
        if (ref != null && !previousBurned) engine.selectSubtitle(ref)
        else renegotiate(st.audioIndex, index) // server must burn in or repackage this subtitle
    }

    fun adjustSubtitleDelay(deltaMs: Long) {
        if (!capabilities.supportsSubtitleDelay) return
        val v = (_state.value.subtitleDelayMs + deltaMs).coerceIn(-30_000, 30_000)
        _state.update { it.copy(subtitleDelayMs = v) }
        engine.setSubtitleDelay(v)
    }

    fun setSubtitleScale(scale: Float) {
        val v = scale.coerceIn(0.5f, 2.5f)
        _state.update { it.copy(subtitleScale = v) }
        engine.setSubtitleScale(v)
    }

    private fun renegotiate(audioIndex: Int?, subtitleIndex: Int?) {
        val st = _state.value
        val itemId = currentItemId ?: return
        val old = st.plan
        val position = engine.state.value.positionMs
        _state.update { it.copy(loading = true) }
        scope.launch {
            appResult {
                val s = settings()
                old?.playSessionId?.takeIf { old.method != PlayMethod.DirectPlay }?.let { api.stopTranscoding(it) }
                val plan = negotiator.negotiate(itemId, old?.mediaSource?.id, position, audioIndex, subtitleIndex, s.maxBitrateMbps, s.enableDirectPlay)
                _state.update { it.copy(plan = plan) }
                loadEngine(plan, position)
            }.onFailure { e -> _state.update { it.copy(loading = false, error = e.toAppError().userMessage) } }
        }
    }

    fun retry() {
        val id = currentItemId ?: return
        val pos = engine.state.value.positionMs
        _state.update { it.copy(error = null, loading = true) }
        val plan = _state.value.plan
        if (plan == null) { start(id); return }
        scope.launch {
            appResult {
                val s = settings()
                // After an engine error, ask the server to transcode: the most likely cause is an unsupported stream.
                val newPlan = negotiator.negotiate(id, plan.mediaSource.id, pos, _state.value.audioIndex, _state.value.subtitleIndex, s.maxBitrateMbps, s.enableDirectPlay, forceTranscode = true)
                _state.update { it.copy(plan = newPlan) }
                loadEngine(newPlan, pos)
            }.onFailure { e -> _state.update { it.copy(loading = false, error = e.toAppError().userMessage) } }
        }
    }

    private suspend fun findNextEpisode(item: BaseItemDto): BaseItemDto? {
        if (!item.isEpisode || item.seriesId == null) return null
        return runCatching {
            api.episodes(item.seriesId, startItemId = item.id, limit = 2).firstOrNull { it.id != item.id }
        }.getOrNull()
    }

    /** Ends the current item and starts the next episode. */
    fun playNext() {
        val next = _state.value.nextEpisode ?: return
        reportStopped(ended = _state.value.ended)
        start(next.id, startFromBeginning = true)
    }

    fun stop() {
        reportStopped(ended = false)
        engine.pause()
    }

    fun release() {
        stop()
        scope.cancel()
        engine.release()
    }

    val supportsSubtitleDelay get() = capabilities.supportsSubtitleDelay
    val engineName get() = engine.name
    fun engineStats() = engine.stats()
    val capabilityInfo get() = capabilities

    @Suppress("unused")
    private fun requireApiUser() = api.userId ?: throw AppError.SessionExpired()
}
