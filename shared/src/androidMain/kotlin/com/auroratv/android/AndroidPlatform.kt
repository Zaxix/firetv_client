package com.auroratv.android

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.auroratv.core.Log
import com.auroratv.platform.FileStore
import com.auroratv.platform.Platform
import com.auroratv.platform.PlatformInfo
import com.auroratv.platform.SecureVault
import com.auroratv.playback.DeviceCapabilities
import com.auroratv.playback.PlaybackEngine
import java.io.File

class AndroidFileStore(private val dir: File) : FileStore {
    init { dir.mkdirs() }
    override fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()
    override fun write(name: String, content: String) {
        // Write-then-rename so a crash mid-write never leaves a truncated settings file.
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(File(dir, name))) { File(dir, name).writeText(content); tmp.delete() }
    }
    override fun delete(name: String) { File(dir, name).delete() }
}

class AndroidPlatform(private val activity: Activity) : Platform {
    private val isFireTv = activity.packageManager.hasSystemFeature("amazon.hardware.fire_tv") || Build.MODEL.startsWith("AFT")
    private val isTv = isFireTv || activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    override val info = PlatformInfo(
        platformName = if (isFireTv) "Fire TV" else if (isTv) "Android TV" else "Android",
        deviceName = runCatching { Settings.Global.getString(activity.contentResolver, "device_name") }.getOrNull()
            ?: "${Build.MANUFACTURER} ${Build.MODEL}",
        osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        isTv = true,
        cacheDir = activity.cacheDir.absolutePath,
    )
    override val files: FileStore = AndroidFileStore(activity.filesDir)
    override val vault: SecureVault = KeystoreVault(activity)

    init {
        Log.sink = Log.Sink { level, tag, message, error ->
            when (level) {
                Log.Level.DEBUG -> android.util.Log.d(tag, message)
                Log.Level.INFO -> android.util.Log.i(tag, message)
                Log.Level.WARN -> android.util.Log.w(tag, message + (error?.let { " (${it::class.simpleName}: ${Log.redact(it.message ?: "")})" } ?: ""))
                Log.Level.ERROR -> android.util.Log.e(tag, message + (error?.let { " (${it::class.simpleName}: ${Log.redact(it.message ?: "")})" } ?: ""))
            }
        }
    }

    override suspend fun probeCapabilities(): DeviceCapabilities = AndroidCapabilities.probe(activity)

    override fun createEngine(settings: com.auroratv.settings.Settings): PlaybackEngine = ExoPlayerEngine(activity, settings.assRenderMode)

    @Composable
    override fun PlayerHost(engine: PlaybackEngine, controls: @Composable () -> Unit) {
        val exo = engine as ExoPlayerEngine
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        // Pause when the app goes to the background (Home button, input switch, screensaver).
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) engine.pause() }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { exo.view }, modifier = Modifier.fillMaxSize())
            controls()
        }
    }

    override fun hideKeyboard() {
        val imm = activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
    }

    override fun exitApp() { activity.finish() }
}
