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
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            android.util.Log.d("AuroraKeys", "key=${android.view.KeyEvent.keyCodeToString(event.keyCode)} handled=$handled focus=${currentFocus?.javaClass?.simpleName} touchMode=${window.decorView.isInTouchMode}")
        }
        return handled
    }
}
