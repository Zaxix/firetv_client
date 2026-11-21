package com.auroratv.playback

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * An engine seen through a window of its file, for a virtual episode (one of several episodes in one
 * file): positions, duration and seeks are the episode's own (0 to its length), loading starts
 * inside it, and its end counts as the end. With no window it passes everything straight through.
 * Subtitles, audio and the picture are the file's, so they stay in sync without anything special.
 */
class ClippedEngine(private val inner: PlaybackEngine) : PlaybackEngine by inner {
    /** Where the episode sits in the file (ms), or null for a whole file. */
    @kotlin.concurrent.Volatile var window: com.auroratv.jellyfin.Window? = null
        private set
    /** The end of the window was reached: reported as the end of playback until a seek or Play. */
    @kotlin.concurrent.Volatile private var endReached = false

    fun setWindow(w: com.auroratv.jellyfin.Window?) { window = w; endReached = false }

    /** The file position for an episode position (for the server: transcode start points). */
    fun absolute(positionMs: Long): Long = (window?.startMs ?: 0L) + positionMs
    /** The engine's own position in the file. */
    val rawPositionMs: Long get() = inner.state.value.positionMs

    private fun map(s: EngineState): EngineState {
        val w = window ?: return s
        val len = w.lengthMs
        return s.copy(
            positionMs = (s.positionMs - w.startMs).coerceIn(0, len),
            durationMs = if (s.durationMs > 0) minOf(len, s.durationMs - w.startMs).coerceAtLeast(0) else len,
            bufferedMs = (s.bufferedMs - w.startMs).coerceIn(0, len),
            isEnded = s.isEnded || endReached,
            isPlaying = s.isPlaying && !endReached,
        )
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    override val state: StateFlow<EngineState> = object : StateFlow<EngineState> {
        override val value: EngineState get() = map(inner.state.value)
        override val replayCache: List<EngineState> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<EngineState>): Nothing {
            var last: EngineState? = null
            inner.state.collect { raw ->
                val v = map(raw)
                if (v != last) { last = v; collector.emit(v) }
            }
        }
    }

    override fun load(source: EngineSource) {
        endReached = false
        window?.let { w -> com.auroratv.core.Log.i("Player", "Virtual episode: file ${w.startMs}–${w.endMs} ms, starting at ${absolute(source.startPositionMs)} ms") }
        inner.load(source.copy(startPositionMs = absolute(source.startPositionMs), endPositionMs = window?.endMs))
    }

    override fun seekTo(positionMs: Long) {
        val w = window
        if (w == null) { inner.seekTo(positionMs); return }
        endReached = false
        inner.seekTo(w.startMs + positionMs.coerceIn(0, (w.lengthMs - 500).coerceAtLeast(0)))
    }

    override fun play() {
        if (endReached) return // the episode is over; "Next Episode" or a seek starts something new
        inner.play()
    }

    /**
     * The engines stop at the window's end themselves (EngineSource.endPositionMs). This is the
     * safety net, called twice a second: past the end, playback stops and reads as ended. True when
     * it just ended.
     */
    fun checkEnd(): Boolean {
        val w = window ?: return false
        if (endReached) return false
        val raw = inner.state.value
        if (raw.positionMs < w.endMs + END_SLACK_MS) return false
        endReached = true
        inner.pause()
        return true
    }

    private companion object {
        /** Only when an engine has run on past the end it was given. */
        const val END_SLACK_MS = 300L
    }
}
