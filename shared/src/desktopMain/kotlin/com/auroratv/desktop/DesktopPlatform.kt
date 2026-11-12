package com.auroratv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.auroratv.core.Log
import com.auroratv.platform.FileStore
import com.auroratv.platform.Platform
import com.auroratv.platform.PlatformInfo
import com.auroratv.platform.SecureVault
import com.auroratv.playback.Codecs
import com.auroratv.playback.DeviceCapabilities
import com.auroratv.playback.PlaybackEngine
import com.auroratv.playback.VideoCodecSupport
import com.auroratv.settings.Settings
import kotlinx.coroutines.delay
import java.awt.BorderLayout
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.net.InetAddress
import javax.swing.JWindow
import javax.swing.Timer

class DesktopPlatform : Platform {
    /** Set by Main.kt once the main window exists. */
    var mainWindow: ComposeWindow? = null
    var onToggleFullscreen: () -> Unit = {}
    var onExit: () -> Unit = {}

    override val info = PlatformInfo(
        platformName = if (isWindows) "Windows" else System.getProperty("os.name"),
        deviceName = runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: (System.getenv("COMPUTERNAME") ?: "Desktop"),
        osVersion = "${System.getProperty("os.name")} ${System.getProperty("os.version")} · Java ${System.getProperty("java.version")}",
        isTv = false,
        cacheDir = appCacheDir().absolutePath,
    )
    override val files: FileStore = DesktopFileStore()
    override val vault: SecureVault = DpapiVault(files)

    override suspend fun probeCapabilities(): DeviceCapabilities {
        val lib = LibMpv.load()
        val notes = buildList {
            if (lib == null) add("libmpv was not found: playback is unavailable (${LibMpv.loadError}).")
            add("Hardware decoding uses D3D11VA/NVDEC when the GPU supports the codec; otherwise mpv decodes in software. The Info panel shows which is active.")
            add("HDR is passed through when Windows HDR is on; otherwise mpv tone-maps to SDR.")
        }
        // mpv decodes every common codec (FFmpeg), so the server only transcodes for bitrate limits.
        val video = listOf(Codecs.H264, Codecs.HEVC, Codecs.VP9, Codecs.AV1, Codecs.MPEG2, Codecs.VC1, Codecs.MPEG4).map {
            VideoCodecSupport(it, hardware = true, maxWidth = 7680, maxHeight = 4320, maxBitDepth = 12, decoderName = "FFmpeg (mpv)")
        }
        return DeviceCapabilities(
            engineName = "libmpv",
            video = video,
            hdr = emptySet(),
            canToneMapHdr = true,
            audioDecode = setOf("aac", "mp3", "mp2", "flac", "opus", "vorbis", "ac3", "eac3", "dts", "truehd", "alac", "pcm_s16le", "pcm_s24le", "wmav2", "wmapro"),
            audioPassthrough = emptySet(),
            maxAudioChannels = 8,
            containers = setOf("mkv", "mp4", "m4v", "mov", "webm", "avi", "ts", "mpegts", "m2ts", "mts", "wmv", "asf", "flv", "ogg", "ogm", "3gp", "mpg", "mpeg", "vob"),
            subtitleEmbedded = setOf("ass", "ssa", "srt", "subrip", "vtt", "webvtt", "pgssub", "dvdsub", "dvbsub", "mov_text", "ttml", "sub"),
            subtitleExternal = setOf("ass", "ssa", "srt", "subrip", "vtt", "webvtt", "sub", "pgssub", "ttml"),
            libassRendering = true,
            supportsSubtitleDelay = true,
            notes = notes,
        )
    }

    override fun createEngine(settings: Settings): PlaybackEngine = MpvEngine(settings)

    private fun contentBounds(window: ComposeWindow): Rectangle {
        val pane = window.contentPane
        val p = pane.locationOnScreen
        return Rectangle(p.x, p.y, pane.width, pane.height)
    }

    /**
     * mpv draws into a native child window, which Compose can't paint over. So the video lives in a
     * borderless window exactly covering the main window's content area, and the shared controls are
     * drawn in a transparent dialog stacked above it. Both follow the main window when it moves.
     */
    @Composable
    override fun PlayerHost(engine: PlaybackEngine, onClose: () -> Unit, controls: @Composable () -> Unit) {
        val mpv = engine as MpvEngine
        val owner = mainWindow ?: return
        var bounds by remember { mutableStateOf(contentBounds(owner)) }

        Box(Modifier.fillMaxSize().background(Color.Black))

        DisposableEffect(owner) {
            val video = JWindow(owner).apply {
                background = java.awt.Color.BLACK
                focusableWindowState = false
                isAutoRequestFocus = false
                contentPane.layout = BorderLayout()
                contentPane.background = java.awt.Color.BLACK
                contentPane.add(mpv.canvas, BorderLayout.CENTER)
                setBounds(bounds)
                isVisible = true
            }
            mpv.attach()
            val follow = object : ComponentAdapter() {
                fun sync() {
                    if (!owner.isShowing) return
                    val b = contentBounds(owner)
                    video.bounds = b
                    bounds = b
                }
                override fun componentMoved(e: ComponentEvent) = sync()
                override fun componentResized(e: ComponentEvent) = sync()
            }
            owner.addComponentListener(follow)
            onDispose {
                owner.removeComponentListener(follow)
                engine.release()
                video.isVisible = false
                // Give mpv a moment to stop rendering before its target window is destroyed.
                Timer(400) { video.dispose() }.apply { isRepeats = false; start() }
            }
        }

        val dialogState = rememberDialogState(
            position = WindowPosition(bounds.x.dp, bounds.y.dp),
            size = DpSize(bounds.width.dp, bounds.height.dp),
        )
        DialogWindow(
            // Alt+F4 / closing the overlay leaves the player rather than doing nothing.
            onCloseRequest = onClose,
            state = dialogState,
            title = "Aurora TV",
            undecorated = true,
            transparent = true,
            resizable = false,
            focusable = true,
        ) {
            LaunchedEffect(bounds) {
                window.bounds = bounds
            }
            LaunchedEffect(Unit) {
                delay(150)
                window.toFront()
                window.requestFocus()
            }
            // A 1/255 alpha fill keeps the transparent layer hit-testable so mouse moves reveal the controls.
            Box(Modifier.fillMaxSize().background(Color(0f, 0f, 0f, 0.004f))) {
                controls()
            }
        }
        Log.d("Player", "Player host bounds $bounds")
    }

    override fun toggleFullscreen() = onToggleFullscreen()
    override fun exitApp() = onExit()
}
