package com.auroratv.desktop

import com.auroratv.AppInfo
import com.auroratv.core.Log
import com.auroratv.playback.EngineSource
import com.auroratv.playback.EngineState
import com.auroratv.playback.PlaybackEngine
import com.auroratv.playback.SubtitleTrackRef
import com.auroratv.settings.Settings
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.awt.Canvas
import java.awt.Color
import java.util.Locale
import kotlin.concurrent.thread

/**
 * libmpv engine for Windows: renders through gpu-next (D3D11) into a native child window, decodes on
 * the GPU (d3d11va/nvdec via hwdec=auto-safe), tone-maps or passes through HDR, and renders ASS/SSA
 * with libass including embedded MKV fonts.
 */
class MpvEngine(private val settings: Settings) : PlaybackEngine {
    private val tag = "MpvEngine"
    override val name = "libmpv (gpu-next, libass)"

    /** Heavyweight AWT canvas whose native window handle mpv renders into. */
    val canvas: Canvas = Canvas().apply { background = Color.BLACK; isFocusable = false }

    private val lib = LibMpv.load()
    private var ctx: Pointer? = null
    @Volatile private var released = false

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private var pendingSource: EngineSource? = null
    private var currentSource: EngineSource? = null
    private var pendingSubtitle: SubtitleTrackRef? = null
    private var pendingAudio: Int? = null
    private val externalSids = mutableMapOf<String, Int>() // ref id -> mpv sid
    @Volatile private var fileLoaded = false
    @Volatile private var paused = false
    @Volatile private var pausedForCache = false
    @Volatile private var seeking = false

    init {
        if (lib == null) _state.value = EngineState(isBuffering = false, error = "The video engine (libmpv) is missing from this installation. Reinstall Aurora TV.")
    }

    /** Called once the canvas is on screen (it needs a native HWND before mpv can initialise). */
    fun attach() {
        if (ctx != null || lib == null) return
        val handle = mpvCreate() ?: return
        ctx = handle
        val wid = Native.getComponentID(canvas)
        fun opt(k: String, v: String) {
            val r = lib.mpv_set_option_string(handle, k, v)
            if (r < 0) Log.w(tag, "Option $k=$v rejected: ${lib.mpv_error_string(r)}")
        }
        opt("wid", wid.toString())
        opt("vo", "gpu-next,gpu")
        opt("hwdec", if (settings.hardwareDecoding) "auto-safe" else "no")
        opt("target-colorspace-hint", "yes")
        opt("keep-open", "no")
        opt("idle", "yes")
        opt("force-window", "yes")
        opt("terminal", "no")
        opt("msg-level", "all=warn")
        opt("input-default-bindings", "no")
        opt("input-vo-keyboard", "no")
        opt("input-cursor", "no")
        opt("cursor-autohide", "no")
        opt("osc", "no")
        opt("osd-level", "0")
        opt("cache", "yes")
        opt("demuxer-max-bytes", "300MiB")
        opt("demuxer-readahead-secs", "30")
        opt("network-timeout", "30")
        opt("user-agent", "AuroraTV/${AppInfo.VERSION} (Windows)")
        opt("sub-auto", "no")
        opt("embeddedfonts", "yes")
        opt("sub-ass-override", "scale")
        opt("sub-font-provider", "auto")
        if (settings.audioPassthrough) opt("audio-spdif", "ac3,eac3,dts,dts-hd,truehd")
        val init = lib.mpv_initialize(handle)
        if (init < 0) {
            _state.update { it.copy(error = "The video engine failed to start (${lib.mpv_error_string(init)}).") }
            return
        }
        listOf(
            "time-pos" to LibMpv.FORMAT_DOUBLE, "duration" to LibMpv.FORMAT_DOUBLE, "demuxer-cache-time" to LibMpv.FORMAT_DOUBLE,
            "pause" to LibMpv.FORMAT_FLAG, "paused-for-cache" to LibMpv.FORMAT_FLAG, "seeking" to LibMpv.FORMAT_FLAG,
            "width" to LibMpv.FORMAT_INT64, "height" to LibMpv.FORMAT_INT64,
        ).forEach { (n, f) -> lib.mpv_observe_property(handle, 0, n, f) }
        startEventLoop(handle)
        pendingSource?.let { pendingSource = null; load(it) }
    }

    private fun mpvCreate(): Pointer? = lib?.mpv_create().also {
        if (it == null) _state.update { s -> s.copy(error = "The video engine could not be created.") }
    }

    private fun startEventLoop(handle: Pointer) = thread(name = "mpv-events", isDaemon = true) {
        val l = lib ?: return@thread
        while (!released) {
            val ev = l.mpv_wait_event(handle, 1.0)
            if (released) break
            when (ev.getInt(0)) {
                LibMpv.EVENT_NONE -> Unit
                LibMpv.EVENT_SHUTDOWN -> break
                LibMpv.EVENT_START_FILE -> { fileLoaded = false; publish(buffering = true) }
                LibMpv.EVENT_FILE_LOADED -> { fileLoaded = true; onFileLoaded(); publish() }
                LibMpv.EVENT_PLAYBACK_RESTART -> publish()
                LibMpv.EVENT_END_FILE -> {
                    val data = ev.getPointer(16)
                    val reason = data?.getInt(0) ?: -1
                    val error = data?.getInt(4) ?: 0
                    when (reason) {
                        LibMpv.END_REASON_EOF -> _state.update { it.copy(isEnded = true, isPlaying = false, isBuffering = false) }
                        LibMpv.END_REASON_ERROR -> _state.update {
                            it.copy(error = "Playback failed: ${l.mpv_error_string(error)}. Transcoding on the server may help.", isPlaying = false, isBuffering = false)
                        }
                    }
                }
                LibMpv.EVENT_PROPERTY_CHANGE -> onProperty(ev.getPointer(16))
            }
        }
        // The event thread owns destruction so no call can race with a freed handle.
        // terminate_destroy blocks until mpv's threads stop, which is fine here (not the UI thread).
        runCatching { l.mpv_terminate_destroy(handle) }
        Log.i(tag, "mpv destroyed")
    }

    private fun onProperty(p: Pointer?) {
        p ?: return
        val name = p.getPointer(0)?.getString(0, "UTF-8") ?: return
        val format = p.getInt(8)
        val data = p.getPointer(16)
        fun dbl() = if (format == LibMpv.FORMAT_DOUBLE && data != null) data.getDouble(0) else null
        fun flag() = if (format == LibMpv.FORMAT_FLAG && data != null) data.getInt(0) != 0 else null
        fun int64() = if (format == LibMpv.FORMAT_INT64 && data != null) data.getLong(0) else null
        when (name) {
            "time-pos" -> dbl()?.let { v -> _state.update { it.copy(positionMs = (v * 1000).toLong()) } }
            "duration" -> dbl()?.let { v -> _state.update { it.copy(durationMs = (v * 1000).toLong()) } }
            "demuxer-cache-time" -> dbl()?.let { v -> _state.update { it.copy(bufferedMs = (v * 1000).toLong()) } }
            "pause" -> flag()?.let { paused = it; publish() }
            "paused-for-cache" -> flag()?.let { pausedForCache = it; publish() }
            "seeking" -> flag()?.let { seeking = it; publish() }
            "width" -> int64()?.let { v -> _state.update { it.copy(videoWidth = v.toInt()) } }
            "height" -> int64()?.let { v -> _state.update { it.copy(videoHeight = v.toInt()) } }
        }
    }

    private fun publish(buffering: Boolean? = null) {
        _state.update {
            it.copy(
                isPlaying = fileLoaded && !paused && !pausedForCache && !it.isEnded,
                isBuffering = buffering ?: (!fileLoaded || pausedForCache || seeking),
            )
        }
    }

    private fun prop(name: String): String? {
        val l = lib ?: return null; val c = ctx ?: return null
        val ptr = l.mpv_get_property_string(c, name) ?: return null
        return try { ptr.getString(0, "UTF-8") } finally { l.mpv_free(ptr) }
    }

    private fun set(name: String, value: String) {
        val l = lib ?: return; val c = ctx ?: return
        val r = l.mpv_set_property_string(c, name, value)
        if (r < 0) Log.w(tag, "set $name failed: ${l.mpv_error_string(r)}")
    }

    private fun command(vararg args: String) {
        val l = lib ?: return; val c = ctx ?: return
        val r = l.mpv_command(c, arrayOf(*args, null))
        if (r < 0) Log.w(tag, "command ${args.firstOrNull()} failed: ${l.mpv_error_string(r)}")
    }

    private fun onFileLoaded() {
        val src = currentSource ?: return
        externalSids.clear()
        src.externalSubtitles.forEach { ext ->
            command("sub-add", ext.url, "auto", ext.label, ext.language ?: "")
        }
        // Map external URLs to the sids mpv assigned.
        val count = prop("track-list/count")?.toIntOrNull() ?: 0
        for (i in 0 until count) {
            if (prop("track-list/$i/type") != "sub" || prop("track-list/$i/external") != "yes") continue
            val file = prop("track-list/$i/external-filename") ?: continue
            val sid = prop("track-list/$i/id")?.toIntOrNull() ?: continue
            src.externalSubtitles.firstOrNull { it.url == file }?.let { externalSids[it.id] = sid }
        }
        pendingAudio?.let { selectAudio(it) }
        selectSubtitle(pendingSubtitle)
    }

    override fun load(source: EngineSource) {
        if (ctx == null) { pendingSource = source; return }
        currentSource = source
        pendingAudio = source.audioOrdinal
        pendingSubtitle = source.subtitle
        fileLoaded = false
        _state.value = EngineState(isBuffering = true)
        // Only the token header: mpv splits list options on commas, and the full MediaBrowser header has commas.
        source.headers["X-Emby-Token"]?.let { set("http-header-fields", "X-Emby-Token: $it") }
        set("start", String.format(Locale.ROOT, "%.3f", source.startPositionMs / 1000.0))
        set("pause", "no")
        command("loadfile", source.url, "replace")
    }

    override fun play() = set("pause", "no")
    override fun pause() = set("pause", "yes")
    override fun seekTo(positionMs: Long) = command("seek", String.format(Locale.ROOT, "%.3f", positionMs / 1000.0), "absolute+keyframes")

    override fun selectAudio(ordinal: Int) {
        pendingAudio = ordinal
        if (fileLoaded) set("aid", (ordinal + 1).toString())
    }

    override fun selectSubtitle(ref: SubtitleTrackRef?) {
        pendingSubtitle = ref
        if (!fileLoaded) return
        when (ref) {
            null -> set("sid", "no")
            is SubtitleTrackRef.Embedded -> set("sid", (ref.ordinal + 1).toString())
            is SubtitleTrackRef.External -> externalSids[ref.id]?.let { set("sid", it.toString()) }
                ?: Log.w(tag, "External subtitle not registered: ${ref.id}")
        }
    }

    override fun setSubtitleDelay(delayMs: Long) = set("sub-delay", String.format(Locale.ROOT, "%.3f", delayMs / 1000.0))
    override fun setSubtitleScale(scale: Float) = set("sub-scale", String.format(Locale.ROOT, "%.2f", scale))

    override fun stats(): List<Pair<String, String>> = buildList {
        prop("video-codec")?.let { add("Video codec" to it) }
        prop("hwdec-current")?.let { add("Hardware decoder" to if (it == "no" || it.isBlank()) "none (software)" else it) }
        prop("video-params/pixelformat")?.let { add("Pixel format" to it) }
        prop("video-params/gamma")?.let { g -> add("Transfer" to g + (prop("video-params/primaries")?.let { " / $it" } ?: "")) }
        prop("current-vo")?.let { add("Video output" to it) }
        prop("estimated-vf-fps")?.let { add("Frame rate" to it.take(6)) }
        prop("audio-codec")?.let { add("Audio codec" to it) }
        prop("audio-params/channel-count")?.let { add("Audio channels" to it) }
        prop("current-ao")?.let { add("Audio output" to it) }
        prop("frame-drop-count")?.let { add("Dropped frames" to it) }
        prop("demuxer-cache-duration")?.let { add("Buffered" to "${it.substringBefore('.')} s") }
    }

    override fun release() {
        if (released) return
        released = true
        val c = ctx; val l = lib
        ctx = null
        if (c != null && l != null) {
            runCatching { l.mpv_command(c, arrayOf("stop", null)) }
            l.mpv_wakeup(c) // the event loop sees `released` and destroys the handle
        }
    }
}
