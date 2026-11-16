package com.auroratv.android

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import com.auroratv.ui.AppContainer
import com.auroratv.ui.AuroraApp

class AuroraApplication : Application()

class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppContainer(AndroidPlatform(this))
        // Optional deep link, e.g. `adb shell am start ... --es route detail:<itemId>` (used by CI and launchers).
        container.initialRoute = intent?.getStringExtra("route")
        // `--ez exactResume true` (CI only): resume at the exact saved position, to time it against the keyframe start.
        ExoPlayerEngine.exactResume = intent?.getBooleanExtra("exactResume", false) == true
        // `--es player mpv|exoplayer` (adb/CI): switch the video player, same as Settings → Playback.
        intent?.getStringExtra("player")?.let { p ->
            com.auroratv.settings.PlayerEngineChoice.entries.firstOrNull { it.name.equals(p, ignoreCase = true) }
                ?.let { choice ->
                    val change: (com.auroratv.settings.Settings) -> com.auroratv.settings.Settings = { it.copy(playerEngine = choice) }
                    container.settings.update(change)
                    container.launchSettingsOverride = change // and again once the profile's settings load
                }
        }
        // Back presses that Compose didn't consume (e.g. nothing focused) still go through app navigation.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!container.backHandler()) finish()
            }
        })
        setContent { AuroraApp(container) }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val handled = super.dispatchKeyEvent(event)
        // Debug builds log remote input so CI can verify D-pad delivery; release builds don't.
        if (event.action == android.view.KeyEvent.ACTION_DOWN && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.util.Log.d("AuroraKeys", "key=${android.view.KeyEvent.keyCodeToString(event.keyCode)} handled=$handled focus=${currentFocus?.javaClass?.simpleName} touchMode=${window.decorView.isInTouchMode}")
        }
        return handled
    }
}
