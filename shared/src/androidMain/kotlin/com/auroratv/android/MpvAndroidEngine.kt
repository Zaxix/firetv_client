package com.auroratv.android

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.auroratv.AppInfo
import com.auroratv.core.Log
import com.auroratv.playback.EngineSource
import com.auroratv.playback.EngineState
import com.auroratv.playback.PlaybackEngine
import com.auroratv.playback.SubtitleTrackRef
import com.auroratv.settings.AssRenderMode
import com.auroratv.settings.Settings
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale

/** An Android engine that draws video into its own [View]. */
interface AndroidVideoEngine : PlaybackEngine {
    val view: View
}

/**
 * libmpv engine for Android TV (opt-in in Settings → Playback). Same libass ASS/SSA renderer and
 * these options: hardware MediaCodec decoding with gpu-next rendering
 * (so subtitles are composited on the GPU over the video), FFmpeg software fallback for any codec
 * MediaCodec can't do, embedded MKV fonts, system fonts through fontconfig, and fonts downloaded
 * from the server for transcoded streams.
 *
 * Privacy: libmpv 1.0.0 echoes its verbose log to logcat. The access token is therefore only
 * ever set as an *option* (logged at debug level, which isn't requested) and is stripped from
 * URLs before they are handed to mpv (mpv logs the URLs it opens).
 */
class MpvAndroidEngine(
    private val context: Context,
    private val settings: Settings,
    /** HDMI passthrough formats the probe found (Exo does passthrough automatically; mpv needs to be told). */
    private val passthrough: Set<String>,
) : AndroidVideoEngine {
    private val tag = "MpvEngine"
    override val name = "libmpv (gpu-next, MediaCodec, libass)"

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val configDir = File(context.filesDir, "mpv")
    private val cacheDir = File(context.cacheDir, "mpv")
    private val fontsDir = File(context.cacheDir, "mpv-fonts")

    /** Commands that may block (sub-add downloads the file) run here, never on the UI or event thread. */
    private val worker = HandlerThread("mpv-cmd").apply { start() }
    private val workHandler = Handler(worker.looper)

    /** Created on first use (after construction, so the observer below exists). */
    private val lib: MPVLib? by lazy {
        runCatching { create() }
            .onFailure { Log.e(tag, "libmpv failed to start", it) }
            .getOrNull()
            .also { if (it == null) _state.value = EngineState(isBuffering = false, error = "The MPV player couldn't start on this device. Switch to ExoPlayer in Settings → Playback.") }
    }

    /** Guards surface attach/detach against destruction on the worker thread. */
    private val lock = Any()
    @Volatile private var destroyed = false
    @Volatile private var awaitingLoad = false
    @Volatile private var released = false
    @Volatile private var fileLoaded = false
    @Volatile private var paused = false
    @Volatile private var pausedForCache = false
    @Volatile private var seeking = false
    @Volatile private var eof = false
    @Volatile private var latestPos = -1L
    @Volatile private var latestBuffered = -1L
    private var lastFlush = 0L
    private var currentSource: EngineSource? = null
    private var pendingAudio: Int? = null
    private var pendingSubtitle: SubtitleTrackRef? = null
    private val externalSids = mutableMapOf<String, Int>()
    private var tokenSet: String? = null
    private val frameRate = if (settings.matchFrameRate) (context as? Activity)?.let { FrameRateMatcher(it) } else null

    override val view: View = SurfaceView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        keepScreenOn = true
        isFocusable = false
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                synchronized(lock) {
                    if (destroyed) return
                    val l = lib ?: return
                    runCatching {
                        l.attachSurface(holder.surface)
                        l.setOptionString("force-window", "yes")
                        l.setOptionString("vo", VO)
                    }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                synchronized(lock) {
                    if (destroyed) return
                    runCatching { lib?.setPropertyString("android-surface-size", "${width}x$height") }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                synchronized(lock) {
                    if (destroyed) return
                    val l = lib ?: return
                    runCatching {
                        l.setOptionString("vo", "null")
                        l.setOptionString("force-window", "no")
                        l.detachSurface()
                    }
                }
            }
        })
    }

    private fun create(): MPVLib {
        configDir.mkdirs(); cacheDir.mkdirs(); fontsDir.mkdirs()
        writeFontsConf()
        val l = MPVLib.create(context) ?: error("MPVLib.create() returned null")
        fun opt(k: String, v: String) { val r = l.setOptionString(k, v); if (r < 0) Log.w(tag, "Option $k=$v rejected ($r)") }
        // Config dir holds fonts.conf, which mpv hands to libass/fontconfig so system fonts (incl. CJK) work.
        opt("config", "yes")
        opt("config-dir", configDir.path)
        opt("gpu-shader-cache-dir", cacheDir.path)
        opt("icc-cache-dir", cacheDir.path)
        opt("msg-level", "all=warn")
        opt("terminal", "no")
        opt("vo", VO)
        opt("gpu-context", "android")
        opt("opengl-es", "yes")
        opt("ao", "audiotrack")
        // Direct MediaCodec decoding into the GPU renderer, falling back to the copy path, then software.
        opt("hwdec", if (settings.hardwareDecoding) "mediacodec,mediacodec-copy" else "no")
        opt("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        opt("profile", "fast")
        opt("force-window", "no")
        opt("keep-open", "yes") // stop at the end (eof-reached) so the Up Next card can take over
        opt("idle", "yes")
        opt("input-default-bindings", "no")
        opt("osc", "no")
        opt("osd-level", "0")
        opt("ytdl", "no")
        opt("save-position-on-quit", "no")
        opt("audio-set-media-role", "yes")
        opt("user-agent", "${AppInfo.NAME}/${AppInfo.VERSION} (Android; mpv)")
        // Buffering sized for TV-box RAM.
        opt("cache", "yes")
        // Smaller read-ahead on 1–2 GB devices (Fire TV sticks), where 200 MB of cache crowds out the
        // rest of the system and invites the low-memory killer.
        val lowRam = runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            am.isLowRamDevice || info.totalMem < 2_600L * 1024 * 1024
        }.getOrDefault(false)
        opt("demuxer-max-bytes", if (lowRam) "96MiB" else "150MiB")
        opt("demuxer-max-back-bytes", if (lowRam) "24MiB" else "50MiB")
        opt("demuxer-readahead-secs", "60")
        opt("cache-pause-initial", "no")
        opt("cache-pause-wait", "2")
        opt("network-timeout", "30")
        opt("hr-seek", "no")
        opt("video-sync", "audio")
        // Subtitles: libass with embedded MKV fonts, fontconfig system fonts, and any server fonts.
        opt("sub-auto", "no")
        opt("embeddedfonts", "yes")
        opt("sub-font-provider", "auto")
        opt("sub-fonts-dir", fontsDir.path)
        opt("sub-ass-override", if (settings.assRenderMode == AssRenderMode.Full) "scale" else "force")
        opt("sub-scale-with-window", "yes")
        opt("sub-use-margins", "no")
        if (passthrough.isNotEmpty()) opt("audio-spdif", passthrough.joinToString(",") { if (it == "dts") "dts,dts-hd" else it })
        l.init()
        l.addObserver(observer)
        listOf(
            "time-pos" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "duration" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "demuxer-cache-time" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "pause" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "seeking" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "width" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "height" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "container-fps" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
        ).forEach { (n, f) -> l.observeProperty(n, f) }
        Log.i(tag, "libmpv ready (hwdec=${settings.hardwareDecoding}, passthrough=$passthrough)")
        return l
    }

    /** fontconfig setup (same approach as mpv-android): system font dirs plus a persistent cache. */
    private fun writeFontsConf() {
        val conf = File(configDir, "fonts.conf")
        val fcCache = File(context.cacheDir, "fontconfig").apply { mkdirs() }
        val text = """
            <?xml version="1.0"?>
            <fontconfig>
                <dir>/system/fonts/</dir>
                <dir>/product/fonts/</dir>
                <cachedir>${fcCache.path}</cachedir>
                <alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family><family>Noto Sans CJK JP</family></prefer></alias>
                <alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>
                <alias><family>monospace</family><prefer><family>Droid Sans Mono</family></prefer></alias>
            </fontconfig>
        """.trimIndent()
        runCatching { if (!conf.exists() || conf.readText() != text) conf.writeText(text) }
            .onFailure { Log.w(tag, "Couldn't write fonts.conf: ${it.message}") }
    }

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) {}
        override fun eventProperty(property: String, value: String) {}

        override fun eventProperty(property: String, value: Long) {
            when (property) {
                "width" -> _state.update { it.copy(videoWidth = value.toInt()) }
                "height" -> _state.update { it.copy(videoHeight = value.toInt()) }
            }
        }

        override fun eventProperty(property: String, value: Double) {
            when (property) {
                // Coalesced to ~4 Hz by flushProgress.
                "time-pos" -> { latestPos = (value * 1000).toLong(); flushProgress() }
                "demuxer-cache-time" -> { latestBuffered = (value * 1000).toLong(); flushProgress() }
                "duration" -> _state.update { it.copy(durationMs = (value * 1000).toLong()) }
                "container-fps" -> frameRate?.let { m -> view.post { m.match(value.toFloat()) } }
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            when (property) {
                "pause" -> paused = value
                "paused-for-cache" -> pausedForCache = value
                "seeking" -> seeking = value
                "eof-reached" -> {
                    eof = value
                    if (value && fileLoaded) _state.update { it.copy(isEnded = true) }
                }
                else -> return
            }
            publish()
        }

        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> { awaitingLoad = true; fileLoaded = false; eof = false; publish(buffering = true) }
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> { awaitingLoad = false; fileLoaded = true; workHandler.post { onFileLoaded() }; publish() }
                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> publish()
                // (Replacing a file also ends the previous one; only a file that never loaded is an error.)
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> if (awaitingLoad && !released) {
                    awaitingLoad = false
                    // Ended before anything loaded: the stream couldn't be opened or decoded.
                    _state.update { it.copy(error = "MPV couldn't play this stream. Transcoding on the server may help.", isPlaying = false, isBuffering = false) }
                }
            }
        }
    }

    private fun flushProgress(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastFlush < 250_000_000L) return
        lastFlush = now
        val pos = latestPos; val buf = latestBuffered
        if (pos < 0 && buf < 0) return
        _state.update { s ->
            val np = if (pos >= 0) pos else s.positionMs
            val nb = if (buf >= 0) buf else s.bufferedMs
            if (np / 250 == s.positionMs / 250 && nb / 1000 == s.bufferedMs / 1000) s else s.copy(positionMs = np, bufferedMs = nb)
        }
    }

    private fun publish(buffering: Boolean? = null) {
        flushProgress(force = true)
        _state.update {
            it.copy(
                isPlaying = fileLoaded && !paused && !pausedForCache && !eof,
                isBuffering = buffering ?: (!fileLoaded || pausedForCache || seeking),
            )
        }
    }

    private fun onFileLoaded() {
        if (destroyed) return
        val l = lib ?: return
        val src = currentSource ?: return
        externalSids.clear()
        src.externalSubtitles.forEach { ext ->
            runCatching { l.command(arrayOf("sub-add", stripCredentials(ext.url), "auto", ext.label, ext.language ?: "")) }
        }
        val count = l.getPropertyInt("track-list/count") ?: 0
        for (i in 0 until count) {
            if (l.getPropertyString("track-list/$i/type") != "sub" || l.getPropertyBoolean("track-list/$i/external") != true) continue
            val file = l.getPropertyString("track-list/$i/external-filename") ?: continue
            val sid = l.getPropertyInt("track-list/$i/id") ?: continue
            src.externalSubtitles.firstOrNull { stripCredentials(it.url) == file }?.let { externalSids[it.id] = sid }
        }
        pendingAudio?.let { applyAudio(it) }
        applySubtitle(pendingSubtitle)
    }

    override fun load(source: EngineSource) {
        val l = lib ?: return
        if (released) return
        currentSource = source
        pendingAudio = source.audioOrdinal
        pendingSubtitle = source.subtitle
        fileLoaded = false; eof = false
        latestPos = -1L; latestBuffered = -1L
        _state.value = EngineState(isBuffering = true, positionMs = source.startPositionMs)
        // Token header as an option (see class docs). Jellyfin's HLS endpoints only accept the token
        // in the URL or an `Authorization: MediaBrowser Token=…` header (not X-Emby-Token), and mpv splits
        // list options on commas, so this comma-free form is the one that works for every request,
        // including the playlists and segments FFmpeg's HLS demuxer fetches.
        source.headers["X-Emby-Token"]?.takeIf { it != tokenSet }?.let { token ->
            l.setOptionString("http-header-fields", "Authorization: MediaBrowser Token=\"$token\"")
            tokenSet = token
        }
        workHandler.post { writeFonts(source) }
        workHandler.post {
            l.setOptionString("start", String.format(Locale.ROOT, "%.3f", source.startPositionMs / 1000.0))
            l.setOptionString("pause", "no")
            l.command(arrayOf("loadfile", stripCredentials(source.url), "replace"))
        }
    }

    /** Server fonts for transcoded streams go where libass looks (sub-fonts-dir), replacing the last item's. */
    private fun writeFonts(source: EngineSource) {
        runCatching {
            fontsDir.listFiles()?.forEach { it.delete() }
            source.fonts.forEach { f -> File(fontsDir, f.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")).writeBytes(f.data) }
        }.onFailure { Log.w(tag, "Couldn't write subtitle fonts: ${it.message}") }
    }

    /** Runs [block] only while mpv is alive; calls after release are ignored rather than crashing. */
    private inline fun mpv(block: (MPVLib) -> Unit) {
        if (destroyed || released) return
        val l = lib ?: return
        runCatching { block(l) }.onFailure { Log.w(tag, "mpv call failed: ${it.message}") }
    }

    override fun play() {
        if (eof) seekTo(0)
        mpv { it.setPropertyBoolean("pause", false) }
    }
    override fun pause() = mpv { it.setPropertyBoolean("pause", true) }

    override fun seekTo(positionMs: Long) {
        latestPos = positionMs
        eof = false
        _state.update { it.copy(positionMs = positionMs, isEnded = false) }
        workHandler.post { mpv { it.command(arrayOf("seek", String.format(Locale.ROOT, "%.3f", positionMs / 1000.0), "absolute+keyframes")) } }
    }

    override fun selectAudio(ordinal: Int) {
        pendingAudio = ordinal
        if (fileLoaded) workHandler.post { applyAudio(ordinal) }
    }

    private fun applyAudio(ordinal: Int) = mpv { it.setPropertyString("aid", (ordinal + 1).toString()) }

    override fun selectSubtitle(ref: SubtitleTrackRef?) {
        pendingSubtitle = ref
        if (fileLoaded) workHandler.post { applySubtitle(ref) }
    }

    private fun applySubtitle(ref: SubtitleTrackRef?) = mpv { l ->
        when (ref) {
            null -> l.setPropertyString("sid", "no")
            is SubtitleTrackRef.Embedded -> l.setPropertyString("sid", (ref.ordinal + 1).toString())
            is SubtitleTrackRef.External -> externalSids[ref.id]?.let { l.setPropertyString("sid", it.toString()) }
                ?: Log.w(tag, "External subtitle not registered: ${ref.id}")
        }
    }

    override fun setSubtitleDelay(delayMs: Long) = mpv { it.setPropertyDouble("sub-delay", delayMs / 1000.0) }
    override fun setSubtitleScale(scale: Float) = mpv { it.setPropertyDouble("sub-scale", scale.toDouble()) }

    override fun setSubtitleStyle(style: com.auroratv.playback.SubtitleStyle, hdr: Boolean) =
        com.auroratv.playback.MpvSubtitleStyle.options(style, hdr).forEach { (k, v) -> mpv { it.setPropertyString(k, v) } }

    override fun stats(): List<Pair<String, String>> = buildList {
        if (destroyed || released) return@buildList
        val l = lib ?: return@buildList
        fun p(n: String) = runCatching { l.getPropertyString(n) }.getOrNull()?.takeIf { it.isNotBlank() }
        p("video-codec")?.let { add("Video codec" to it) }
        p("hwdec-current")?.let { add("Hardware decoder" to if (it == "no") "none (software)" else it) }
        p("video-params/pixelformat")?.let { add("Pixel format" to it) }
        p("current-vo")?.let { add("Video output" to it) }
        p("container-fps")?.let { add("Frame rate" to it.take(6)) }
        p("audio-codec")?.let { add("Audio codec" to it) }
        p("audio-params/channel-count")?.let { add("Audio channels" to it) }
        p("current-ao")?.let { add("Audio output" to it) }
        p("frame-drop-count")?.let { add("Dropped frames" to it) }
        p("demuxer-cache-duration")?.let { add("Buffered" to "${it.substringBefore('.')} s") }
    }

    override fun release() {
        if (released) return
        released = true
        frameRate?.restore()
        val l = lib ?: run { worker.quitSafely(); return }
        workHandler.post {
            runCatching { l.removeObserver(observer) }
            runCatching { l.command(arrayOf("stop")) }
            synchronized(lock) {
                destroyed = true
                runCatching { l.destroy() }
            }
            // Copies of the server's subtitle fonts for this playback only (they're downloaded again
            // for the next one), so they don't outlive the player.
            runCatching { fontsDir.listFiles()?.forEach { it.delete() } }
            Log.i(tag, "mpv destroyed")
        }
        worker.quitSafely()
    }

    companion object {
        private const val VO = "gpu-next,gpu"

        /** mpv logs the URLs it opens, so the token never goes in a URL (it's sent as a header). */
        fun stripCredentials(url: String): String {
            val q = url.indexOf('?')
            if (q < 0) return url
            val kept = url.substring(q + 1).split('&').filter { p ->
                val k = p.substringBefore('=').lowercase()
                k != "api_key" && k != "apikey" && k != "x-emby-token"
            }
            return url.substring(0, q) + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
        }
    }
}
