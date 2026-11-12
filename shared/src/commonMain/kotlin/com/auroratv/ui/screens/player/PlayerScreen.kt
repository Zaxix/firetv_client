package com.auroratv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.auroratv.playback.DeviceCapabilities
import com.auroratv.playback.PlayerController
import com.auroratv.ui.AppContainer
import com.auroratv.ui.LocalSession
import com.auroratv.ui.components.LoadingState
import com.auroratv.ui.nav.LocalNavigator
import com.auroratv.ui.nav.Route

@Composable
fun PlayerScreen(container: AppContainer, route: Route.Player) {
    val caps by produceState<DeviceCapabilities?>(null) { value = container.capabilities() }
    val c = caps
    if (c == null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) { LoadingState() }
        return
    }
    PlayerSession(container, route, c)
}

@Composable
private fun PlayerSession(container: AppContainer, route: Route.Player, caps: DeviceCapabilities) {
    val session = LocalSession.current
    val navigator = LocalNavigator.current
    val engine = remember { container.platform.createEngine(container.settings.current) }
    val controller = remember {
        PlayerController(session.api, engine, caps, { container.settings.current }, container.appScope)
    }
    DisposableEffect(controller) { onDispose { controller.release() } }
    LaunchedEffect(route.itemId) { controller.start(route.itemId, route.startFromBeginning) }

    container.platform.PlayerHost(engine) {
        PlayerChrome(
            controller = controller,
            settings = container.settings.settings,
            onExit = { navigator.pop() },
            onError = container::onError,
        )
    }
}
