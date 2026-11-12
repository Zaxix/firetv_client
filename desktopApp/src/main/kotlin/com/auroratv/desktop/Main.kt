package com.auroratv.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import com.auroratv.ui.AppContainer
import com.auroratv.ui.AuroraApp
import kotlinx.serialization.Serializable
import java.awt.Dimension
import javax.imageio.ImageIO

@Serializable
private data class SavedWindow(val x: Float? = null, val y: Float? = null, val width: Float = 1440f, val height: Float = 840f, val fullscreen: Boolean = false, val maximized: Boolean = false)

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--self-test") {
        kotlin.system.exitProcess(MpvSelfTest.run(args.getOrElse(1) { "aurora-selftest.txt" }, args.drop(2)))
    }
    // Logs go to %APPDATA%\AuroraTV\aurora.log (already redacted by Log).
    val logFile = java.io.File(appDataDir(), "aurora.log")
    runCatching { if (logFile.length() > 2_000_000) logFile.delete() }
    Log.sink = Log.Sink { level, tag, message, error ->
        val line = "${java.time.LocalDateTime.now()} [$level] $tag: $message" + (error?.let { " (${it::class.simpleName}: ${Log.redact(it.message ?: "")})" } ?: "")
        println(line)
        runCatching { logFile.appendText(line + "\n") }
    }
    Thread.setDefaultUncaughtExceptionHandler { t, e -> Log.e("Crash", "Uncaught on ${t.name}", e) }

    val platform = DesktopPlatform()
    val container = AppContainer(platform)
    val saved = platform.files.read("window.json")?.let { runCatching { AuroraJson.decodeFromString(SavedWindow.serializer(), it) }.getOrNull() } ?: SavedWindow()
    val icon = runCatching { BitmapPainter(ImageIO.read(DesktopPlatform::class.java.getResourceAsStream("/aurora.png")).toComposeImageBitmap()) }.getOrNull()
        ?: runCatching { BitmapPainter(ImageIO.read(Thread.currentThread().contextClassLoader.getResourceAsStream("aurora.png")).toComposeImageBitmap()) }.getOrNull()

    application {
        val state = rememberWindowState(
            placement = when { saved.fullscreen -> WindowPlacement.Fullscreen; saved.maximized -> WindowPlacement.Maximized; else -> WindowPlacement.Floating },
            position = if (saved.x != null && saved.y != null) WindowPosition(saved.x.dp, saved.y.dp) else WindowPosition.PlatformDefault,
            size = DpSize(saved.width.dp, saved.height.dp),
        )
        fun persist() {
            val pos = state.position
            val w = SavedWindow(
                x = if (pos is WindowPosition.Absolute) pos.x.value else null,
                y = if (pos is WindowPosition.Absolute) pos.y.value else null,
                width = state.size.width.value.takeIf { it.isFinite() } ?: 1440f,
                height = state.size.height.value.takeIf { it.isFinite() } ?: 840f,
                fullscreen = state.placement == WindowPlacement.Fullscreen,
                maximized = state.placement == WindowPlacement.Maximized,
            )
            runCatching { platform.files.write("window.json", AuroraJson.encodeToString(SavedWindow.serializer(), w)) }
        }
        val gamepad = remember { GamepadBridge() }

        Window(
            onCloseRequest = { persist(); gamepad.stop(); exitApplication() },
            state = state,
            title = "Aurora TV",
            icon = icon,
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(960, 540)
                platform.mainWindow = window
                platform.onToggleFullscreen = {
                    state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                }
                platform.onExit = { persist(); gamepad.stop(); exitApplication() }
                gamepad.start()
            }
            AuroraApp(container)
        }
    }
}
