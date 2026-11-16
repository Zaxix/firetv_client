package com.auroratv.playback

import com.auroratv.core.AppError
import com.auroratv.core.Log
import com.auroratv.core.appResult
import com.auroratv.core.toAppError
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.model.TrickplayInfo
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

/** A part of the timeline the user may want to skip; drawn in yellow on the progress bar. */
data class SkipRange(val kind: Kind, val startMs: Long, val endMs: Long) {
    enum class Kind(val label: String) { Recap("Skip Recap"), Intro("Skip Intro"), Credits("Skip Credits") }
}

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
    /** Ranges found by [SkipDetector] for kinds the server didn't mark. */
    val detectedSkips: List<SkipRange> = emptyList(),
    /** Where [detectedSkips] came from, shown in the Info panel. */
    val detectedSource: String? = null,
    val chapters: List<ChapterInfo> = emptyList(),
    val nextEpisode: BaseItemDto? = null,
    val ended: Boolean = false,
    /** Seasons of the current series and their episodes, for the in-player episode browser. */
    val seasons: List<BaseItemDto> = emptyList(),
    val episodesBySeason: Map<String, List<BaseItemDto>> = emptyMap(),
) {
    /**
     * Intro/recap/credits ranges: server media segments, else chapter names; then anything the
     * [SkipDetector] found for kinds still missing. Computed once per state.
     */
    val skipRanges: List<SkipRange> by lazy {
        val server = serverSkipRanges
        val have = server.map { it.kind }.toSet()
        (server + detectedSkips.filter { it.kind !in have }).sortedBy { it.startMs }
    }

    /** Ranges the server provides (segments or chapter names), without client-side detection. */
    val serverSkipRanges: List<SkipRange> by lazy {
        run {
            val fromSegments = segments.mapNotNull { seg ->
                val kind = when (seg.type) { "Intro" -> SkipRange.Kind.Intro; "Recap" -> SkipRange.Kind.Recap; "Outro" -> SkipRange.Kind.Credits; else -> null }
                kind?.let { SkipRange(it, seg.startTicks.ticksToMs(), seg.endTicks.ticksToMs()) }
            }.filter { it.endMs > it.startMs }
            if (fromSegments.isNotEmpty()) return@run fromSegments
            val runtime = item?.runtimeMs ?: return@run emptyList()
            chapters.mapIndexedNotNull { i, ch ->
                val name = ch.name?.lowercase()?.trim() ?: return@mapIndexedNotNull null
                val kind = when {
                    CREDITS.containsMatchIn(name) -> SkipRange.Kind.Credits
                    RECAP.containsMatchIn(name) -> SkipRange.Kind.Recap
                    INTRO.containsMatchIn(name) -> SkipRange.Kind.Intro
                    else -> null
                } ?: return@mapIndexedNotNull null
                val start = ch.startPositionTicks.ticksToMs()
                val end = chapters.getOrNull(i + 1)?.startPositionTicks?.ticksToMs() ?: runtime
                SkipRange(kind, start, end).takeIf { end > start }
            }
        }
    }

    /**
     * Scrub-preview sheets for the playing source: the smallest set at least 320 px wide (sharp
     * enough for the preview, cheap to download). Null if the server hasn't generated trickplay.
     */
    val trickplay: Pair<String, TrickplayInfo>? by lazy {
        val sets = item?.trickplay ?: return@lazy null
        val sourceId = plan?.mediaSource?.id?.takeIf { it in sets } ?: sets.keys.firstOrNull() ?: return@lazy null
        val infos = sets[sourceId]?.values?.filter { it.interval > 0 && it.tileWidth > 0 && it.tileHeight > 0 }.orEmpty()
        val pick = infos.filter { it.width >= 320 }.minByOrNull { it.width } ?: infos.maxByOrNull { it.width } ?: return@lazy null
        sourceId to pick
    }

    /**
     * When "Next Episode" becomes available: the start of the credits (from media segments or a
     * credits chapter) or, when the server has no credits marker, the final two minutes.
     * Null when there is no next episode.
     */
    val nextEpisodeFromMs: Long? by lazy {
        if (nextEpisode == null) return@lazy null
        skipRanges.filter { it.kind == SkipRange.Kind.Credits }.minOfOrNull { it.startMs }
            ?: item?.runtimeMs?.takeIf { it > 0 }?.let { (it - 120_000).coerceAtLeast(it / 2) }
    }

    fun activeSkip(positionMs: Long): SkipRange? = skipRanges.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 1_000 }

    val audioStreams: List<MediaStream> get() = plan?.audioStreams.orEmpty()
    val subtitleStreams: List<MediaStream> get() = plan?.subtitleStreams.orEmpty()

    private companion object {
        val INTRO = Regex("""\b(intro|opening|op|opening credits|theme song)\b""")
        val RECAP = Regex("""\b(recap|previously)\b""")
        val CREDITS = Regex("""\b(credits|ending|ed|outro|end credits|closing)\b""")
    }
}

/**
 * Coordinates one playback session: negotiation, engine control, track switching, resume, intro
 * skipping, next-episode lookup and progress reporting to Jellyfin. Shared by Android and Windows.
 */
class PlayerController(
    private val api: JellyfinApi,
    private val skipDetector: SkipDetector?,
    private val engine: PlaybackEngine,
    private val capabilities: DeviceCapabilities,
    private val settings: () -> Settings,
    /** Application-lifetime scope so the final "stopped" report survives the screen closing. */
    private val appScope: CoroutineScope,
    /** Work done ahead of time by the detail page (item, PlaybackInfo, fonts); optional. */
    private val prewarmer: PlaybackPrewarmer? = null,
    /** The item as the previous screen had it, so the player can show its artwork immediately. */
    preview: BaseItemDto? = null,
    /** Per-series audio/subtitle choices (null: don't remember). */
    private val trackMemory: TrackMemory? = null,
    /** Detected skip markers kept for episodes in progress (null: don't keep). */
    private val skipMarkers: com.auroratv.profiles.SkipMarkerStore? = null,
) {
    private val tag = "Player"
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private val negotiator = PlaybackNegotiator(api, capabilities)

    private val _state = MutableStateFlow(PlayerUiState(item = preview, loading = true))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    val engineState: StateFlow<EngineState> get() = engine.state

    private var reportJob: Job? = null
    private var startReported = false
    private var stoppedReported = false
    private val autoSkipped = mutableSetOf<String>()
    private var currentItemId: String? = null
    private var prewarmedNext: String? = null

    /** Last remote/keyboard/mouse input in the player. Survives auto-played episodes. */
    private var lastInteraction = kotlin.time.TimeSource.Monotonic.markNow()

    /** Called by the player UI on any user input. */
    fun noteInteraction() { lastInteraction = kotlin.time.TimeSource.Monotonic.markNow() }

    val idleMs: Long get() = lastInteraction.elapsedNow().inWholeMilliseconds

    /** True when the viewer should be asked "Are you still watching?" instead of auto-playing next. */
    fun shouldAskStillWatching(): Boolean =
        StillWatching.shouldAsk(settings().stillWatching, idleMs, _state.value.item?.runtimeMs)

    /** From Play to the picture moving: logged once per start (tag "Perf") so CI can track it. */
    private var playMark: kotlin.time.TimeMark? = null
    private var playNote = ""

    fun start(itemId: String, startFromBeginning: Boolean = false) {
        playMark = kotlin.time.TimeSource.Monotonic.markNow()
        playNote = ""
        currentItemId = itemId
        startReported = false
        stoppedReported = false
        autoSkipped.clear()
        val s = settings()
        val preview = _state.value.item?.takeIf { it.id == itemId } ?: _state.value.nextEpisode?.takeIf { it.id == itemId }
        _state.value = PlayerUiState(item = preview, loading = true, subtitleDelayMs = s.subtitleDelayMs, subtitleScale = s.subtitleScale)
        scope.launch {
            val result = appResult {
                val t0 = kotlin.time.TimeSource.Monotonic.markNow()
                val warm = prewarmer?.take(api, capabilities, s, itemId)
                val prepared = when {
                    warm == null -> PlaybackPreparer.prepare(api, negotiator, s, itemId, startFromBeginning, trackMemory = trackMemory)
                    !startFromBeginning || warm.startMs == 0L -> warm
                    // Direct Play URLs don't depend on the start point; a transcode does, so ask again.
                    warm.plan.method == PlayMethod.DirectPlay -> PreparedPlayback(warm.item, warm.plan, 0L, warm.audioIndex, warm.subtitleIndex, warm.fonts)
                    else -> PlaybackPreparer.prepare(api, negotiator, s, itemId, startFromBeginning = true, knownItem = warm.item, trackMemory = trackMemory)
                }
                val item = prepared.item
                val plan = prepared.plan
                val startMs = prepared.startMs
                if (prepared.fonts.isNotEmpty()) { fontsKey = "$itemId/${plan.mediaSource.id}"; fonts = prepared.fonts }
                _state.update { it.copy(item = item, plan = plan, audioIndex = prepared.audioIndex, subtitleIndex = prepared.subtitleIndex, chapters = item.chapters) }
                loadEngine(plan, startMs)
                playNote = "${if (startMs > 0) "resume at ${startMs / 60_000}:${(startMs / 1000 % 60).toString().padStart(2, '0')}" else "from the start"}, " +
                    "${plan.method.jellyfinName}, ${if (warm != null) "prepared ahead" else "cold"}"
                Log.i(tag, "Ready to play in ${t0.elapsedNow().inWholeMilliseconds} ms (${if (warm != null) "prepared on the detail page" else "cold"}, ${plan.method.jellyfinName})")
                // Secondary lookups never block playback.
                launch {
                    _state.update { it.copy(segments = runCatching { api.mediaSegments(itemId) }.getOrDefault(emptyList())) }
                    // Then fill whatever the server didn't mark (intro, credits...) from local/online detection.
                    val have = _state.value.serverSkipRanges.map { it.kind }.toSet()
                    if (have.containsAll(listOf(SkipRange.Kind.Intro, SkipRange.Kind.Credits))) return@launch
                    // Resuming an episode: markers worked out last time are still here, no need to detect again.
                    skipMarkers?.get(itemId)?.let { (ranges, source) ->
                        if (currentItemId == itemId) _state.update { it.copy(detectedSkips = ranges, detectedSource = "$source, remembered") }
                        return@launch
                    }
                    val detector = skipDetector ?: return@launch
                    val found = runCatching { detector.detect(item, have) }.getOrNull() ?: return@launch
                    if (currentItemId == itemId) {
                        _state.update { it.copy(detectedSkips = found.ranges, detectedSource = found.source) }
                        if (item.isEpisode) skipMarkers?.put(itemId, found.ranges, found.source)
                        // The episode it was compared with gets its markers too (ready when it's played).
                        if (item.isEpisode) found.others.forEach { (id, ranges) -> if (ranges.isNotEmpty() && skipMarkers?.get(id) == null) skipMarkers?.put(id, ranges, found.source) }
                    }
                }
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

    private var fontsKey: String? = null
    private var fonts: List<SubtitleFont> = emptyList()

    /**
     * When the server transcodes or remuxes, MKV font attachments don't survive into the stream, so
     * ASS/SSA typesetting would fall back to default fonts. Download them from Jellyfin instead and
     * hand them to the engine (libass on both ExoPlayer and mpv). Direct Play needs none of this.
     */
    private suspend fun subtitleFonts(plan: PlaybackPlan): List<SubtitleFont> {
        if (plan.method == PlayMethod.DirectPlay) return emptyList()
        if (plan.subtitleStreams.none { (it.codec ?: "").lowercase() in setOf("ass", "ssa") }) return emptyList()
        val itemId = currentItemId ?: return emptyList()
        val key = "$itemId/${plan.mediaSource.id}"
        if (key == fontsKey) return fonts
        val loaded = PlaybackPreparer.downloadFonts(api, itemId, plan.mediaSource)
        fontsKey = key
        fonts = loaded
        return loaded
    }

    private suspend fun loadEngine(plan: PlaybackPlan, startMs: Long) {
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
            fonts = subtitleFonts(plan),
        )
        engine.load(source)
        val st = _state.value
        if (capabilities.supportsSubtitleDelay) engine.setSubtitleDelay(st.subtitleDelayMs)
        engine.setSubtitleScale(st.subtitleScale)
        engine.setSubtitleStyle(settings().subtitleStyle, isHdr(plan))
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
                if (es.isPlaying) playMark?.let { mark ->
                    playMark = null
                    Log.i("Perf", "Playing after ${mark.elapsedNow().inWholeMilliseconds} ms ($playNote)")
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
                // From the credits on, get the next episode ready so "Next Episode" starts at once.
                val st = _state.value
                val next = st.nextEpisode
                val from = st.nextEpisodeFromMs
                if (prewarmer != null && next != null && from != null && engine.state.value.positionMs >= from && prewarmedNext != next.id) {
                    prewarmedNext = next.id
                    prewarmer.prewarm(api, capabilities, settings(), next.id)
                }
                if (settings().skipIntro != SkipIntroMode.Auto) continue
                val range = _state.value.activeSkip(engine.state.value.positionMs) ?: continue
                if (range.kind != SkipRange.Kind.Credits) {
                    val key = "${currentItemId}:${range.startMs}"
                    if (autoSkipped.add(key)) engine.seekTo(range.endMs)
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
        // Finished (the server counts 90% as watched): its temporary skip markers can go.
        if (ended || (es.durationMs > 0 && pos >= es.durationMs * 9 / 10)) currentItemId?.let { skipMarkers?.finished(it) }
        val r = buildReport(plan, es, pos)
        val sessionId = plan.playSessionId
        val transcoding = plan.method != PlayMethod.DirectPlay
        appScope.launch {
            runCatching { api.reportStopped(r) }.onFailure { Log.w(tag, "Stop report failed", it) }
            com.auroratv.data.WatchActivity.changed() // Home: new resume point / history for recommendations
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
        val range = _state.value.activeSkip(engine.state.value.positionMs) ?: return
        engine.seekTo(range.endMs)
    }

    // ---- Episode browser -----------------------------------------------------------------------

    /** Loads the series' seasons (once) and the given season's episodes (default: the playing one). */
    fun loadEpisodes(seasonId: String? = null) {
        val item = _state.value.item ?: return
        val seriesId = item.seriesId ?: return
        scope.launch {
            appResult {
                val seasons = _state.value.seasons.ifEmpty { api.seasons(seriesId) }
                val target = seasonId ?: item.seasonId ?: seasons.firstOrNull()?.id ?: return@appResult
                val eps = _state.value.episodesBySeason[target] ?: api.episodes(seriesId, seasonId = target)
                _state.update { it.copy(seasons = seasons, episodesBySeason = it.episodesBySeason + (target to eps)) }
            }.onFailure { Log.w(tag, "Couldn't load episodes", it) }
        }
    }

    /** Switches playback to another episode, reporting the current one as stopped first. */
    fun playEpisode(episodeId: String) {
        if (episodeId == currentItemId) return
        val keep = _state.value
        reportStopped(ended = false)
        start(episodeId)
        // Keep the browser's data so reopening it is instant.
        _state.update { it.copy(seasons = keep.seasons, episodesBySeason = keep.episodesBySeason) }
    }

    fun selectAudio(index: Int) {
        val st = _state.value
        val plan = st.plan ?: return
        if (index == st.audioIndex) return
        val stream = plan.audioStreams.firstOrNull { it.index == index } ?: return
        _state.update { it.copy(audioIndex = index) }
        if (settings().rememberTracks) st.item?.let { trackMemory?.rememberAudio(it, stream) }
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
        if (settings().rememberTracks) st.item?.let { item -> trackMemory?.rememberSubtitle(item, index?.let { i -> plan.subtitleStreams.firstOrNull { it.index == i } }) }
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

    /** Applies a new subtitle look at once (the caller saves it in Settings). */
    fun setSubtitleStyle(style: SubtitleStyle) {
        engine.setSubtitleStyle(style, _state.value.plan?.let(::isHdr) ?: false)
    }

    /** HDR reaches the screen (a transcode is usually tone-mapped to SDR by the server). */
    private fun isHdr(plan: PlaybackPlan): Boolean = plan.method != PlayMethod.Transcode &&
        plan.mediaSource.videoStream?.videoRangeType?.let { it.isNotBlank() && !it.equals("SDR", ignoreCase = true) } ?: false

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
