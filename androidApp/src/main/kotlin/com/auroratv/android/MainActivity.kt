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
        // Back presses that Compose didn't consume (e.g. nothing focused) still go through app navigation.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!container.backHandler()) finish()
            }
        })
        setContent { AuroraApp(container) }
    }
}
