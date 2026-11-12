package com.auroratv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.auroratv.data.SessionState
import com.auroratv.data.UserSession
import com.auroratv.ui.components.FocusSurface
import com.auroratv.ui.components.arrowKeyNavigation
import com.auroratv.ui.components.LoadingState
import com.auroratv.ui.nav.LocalEntry
import com.auroratv.ui.nav.LocalNavigator
import com.auroratv.ui.nav.Navigator
import com.auroratv.ui.nav.Route
import com.auroratv.ui.nav.Section
import com.auroratv.ui.screens.ConnectScreen
import com.auroratv.ui.screens.ContinueScreen
import com.auroratv.ui.screens.DetailScreen
import com.auroratv.ui.screens.FavoritesScreen
import com.auroratv.ui.screens.HomeScreen
import com.auroratv.ui.screens.LibrariesScreen
import com.auroratv.ui.screens.LibraryScreen
import com.auroratv.ui.screens.LoginScreen
import com.auroratv.ui.screens.SearchScreen
import com.auroratv.ui.screens.SettingsScreen
import com.auroratv.ui.screens.player.PlayerScreen
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraTheme
import com.auroratv.ui.theme.AuroraType
import io.ktor.client.HttpClient
import okio.Path.Companion.toPath

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No container") }
val LocalSession = staticCompositionLocalOf<UserSession> { error("No session") }


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AuroraApp(container: AppContainer) {
    val settings by container.settings.settings.collectAsState()
    val cacheDir = container.platform.info.cacheDir
    setSingletonImageLoaderFactory { context ->
        val imageHttp = HttpClient()
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory({ imageHttp })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .diskCache { DiskCache.Builder().directory("$cacheDir/artwork".toPath()).maxSizeBytes(300L * 1024 * 1024).build() }
            .build()
    }

    AuroraTheme(settings.theme, settings.animations) {
        CompositionLocalProvider(
            LocalContainer provides container,
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.auroratv.ui.components.MinimalBringIntoViewSpec,
        ) {
            Box(Modifier.fillMaxSize().background(Aurora.colors.background).arrowKeyNavigation()) {
                val state by container.sessions.state.collectAsState()
                LaunchedEffect(Unit) { container.sessions.restore() }
                when (val s = state) {
                    SessionState.Starting -> Splash()
                    SessionState.NeedsServer -> ConnectScreen(container)
                    is SessionState.NeedsLogin -> key(s.server.id) { LoginScreen(container, s.server, s.message) }
                    is SessionState.Ready -> key(s.session.server.id, s.session.user.id) {
                        CompositionLocalProvider(LocalSession provides s.session) { MainShell(container) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Aurora", style = AuroraType.hero, color = Aurora.colors.onBackground)
            Spacer(Modifier.height(24.dp))
            Box(Modifier.size(48.dp)) { LoadingState() }
        }
    }
}

@Composable
private fun MainShell(container: AppContainer) {
    val navigator = remember {
        Navigator(Route.Root(Section.Home)).also { nav ->
            val link = container.initialRoute
            container.initialRoute = null
            when {
                link == null -> Unit
                link.startsWith("section:") -> Section.entries.firstOrNull { it.name.equals(link.substringAfter(':'), true) }?.let(nav::selectSection)
                link.startsWith("detail:") -> nav.push(Route.Detail(link.substringAfter(':')))
                link.startsWith("player:") -> nav.push(Route.Player(link.substringAfter(':')))
            }
        }
    }
    val entry = navigator.current

    val topBar = remember { TopBarState() }

    // tvOS behaviour: Back walks up the stack, then to the tab bar, then to Home, then exits.
    fun handleBack(): Boolean = when {
        navigator.pop() -> true
        !topBar.hasFocus -> { topBar.contentAtTop = true; runCatching { topBar.currentTab.requestFocus() }.isSuccess }
        navigator.currentSection != Section.Home -> { navigator.selectSection(Section.Home); true }
        else -> false
    }
    // Entering the app from sign-in: make sure no on-screen keyboard is left capturing the D-pad.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide(); container.platform.hideKeyboard() }
    DisposableEffect(navigator) {
        container.backHandler = ::handleBack
        onDispose { container.backHandler = { false } }
    }

    // Each new screen starts with the tab bar shown; screens that scroll then report their position.
    LaunchedEffect(entry) { topBar.contentAtTop = true }
    CompositionLocalProvider(LocalNavigator provides navigator, LocalTopBar provides topBar) {
        Box(
            Modifier.fillMaxSize().onKeyEvent { ev ->
                // Bubbling handler: screens that need Back (player, dialogs) consume it first.
                if ((ev.key == Key.Back || ev.key == Key.Escape) && ev.type == KeyEventType.KeyUp) {
                    if (!handleBack()) container.platform.exitApp()
                    true
                } else if ((ev.key == Key.Back || ev.key == Key.Escape) && ev.type == KeyEventType.KeyDown) {
                    true
                } else if (ev.key == Key.F11 && ev.type == KeyEventType.KeyUp) {
                    container.platform.toggleFullscreen(); true
                } else false
            }
        ) {
            val isPlayer = entry.route is Route.Player
            val motion = Aurora.motion
            if (!isPlayer) {
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = entry,
                        transitionSpec = { fadeIn(tween(motion.duration(220))) togetherWith fadeOut(tween(motion.duration(120))) },
                    ) { e ->
                        CompositionLocalProvider(LocalEntry provides e) { RouteContent(container, e.route) }
                    }
                }
                TopTabBar(navigator, topBar)
            } else {
                CompositionLocalProvider(LocalEntry provides entry) { RouteContent(container, entry.route) }
            }
        }
    }
}

@Composable
private fun RouteContent(container: AppContainer, route: Route) {
    when (route) {
        is Route.Root -> when (route.section) {
            Section.Home -> HomeScreen()
            Section.Movies -> LibraryScreen(Route.Library(null, "Movies", listOf("Movie"), "movies"))
            Section.Shows -> LibraryScreen(Route.Library(null, "TV Shows", listOf("Series"), "tvshows"))
            Section.Continue -> ContinueScreen()
            Section.Favorites -> FavoritesScreen()
            Section.Search -> SearchScreen()
            Section.Libraries -> LibrariesScreen()
            Section.Settings -> SettingsScreen(container)
        }
        is Route.Library -> LibraryScreen(route)
        is Route.Detail -> DetailScreen(route)
        is Route.Player -> PlayerScreen(container, route)
    }
}

private fun Section.icon(): ImageVector = when (this) {
    Section.Home -> Icons.Filled.Home
    Section.Movies -> Icons.Filled.Movie
    Section.Shows -> Icons.Filled.Tv
    Section.Continue -> Icons.Filled.History
    Section.Favorites -> Icons.Filled.Favorite
    Section.Search -> Icons.Filled.Search
    Section.Libraries -> Icons.Filled.VideoLibrary
    Section.Settings -> Icons.Filled.Settings
}

/** Shared state for the tvOS-style tab bar: visible while content is scrolled to the top or the bar has focus. */
class TopBarState {
    var contentAtTop by mutableStateOf(true)
    var hasFocus by mutableStateOf(false)
    val currentTab = FocusRequester()
    val visible get() = contentAtTop || hasFocus
}

val LocalTopBar = staticCompositionLocalOf { TopBarState() }

/** Space screens leave at the top so their first row clears the tab bar. */
val TopBarSpace = 96.dp

/** Screens call this with their list state so the tab bar hides once the user scrolls down, as on tvOS. */
@Composable
fun ReportScrollToTopBar(atTop: () -> Boolean) {
    val bar = LocalTopBar.current
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { atTop() }.collect { bar.contentAtTop = it } }
}

private fun Section.short(): String = when (this) {
    Section.Continue -> "Up Next"
    else -> label
}

/** tvOS top tab bar: centred text tabs, a selected-tab capsule, and a white capsule on focus. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TopTabBar(navigator: Navigator, bar: TopBarState) {
    val colors = Aurora.colors
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (bar.visible) 1f else 0f, tween(Aurora.motion.duration(250)))
    val offset by animateDpAsState(if (bar.visible) 0.dp else (-24).dp, tween(Aurora.motion.duration(250)))
    Box(
        Modifier.fillMaxWidth().height(TopBarSpace)
            .graphicsLayer { this.alpha = alpha; translationY = offset.toPx() }
            .background(Brush.verticalGradient(listOf(colors.background.copy(alpha = 0.75f), colors.background.copy(alpha = 0f)))),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.onFocusChanged { bar.hasFocus = it.hasFocus }.focusRestorer(bar.currentTab)
                .clip(RoundedCornerShape(50)).background(colors.onBackground.copy(alpha = 0.08f)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Section.entries.forEach { section ->
                val selected = navigator.currentSection == section
                val iconOnly = section == Section.Search || section == Section.Settings
                FocusSurface(
                    onClick = { navigator.selectSection(section) },
                    shape = RoundedCornerShape(50),
                    sheen = false,
                    scaleOnFocus = true,
                    modifier = if (selected) Modifier.focusRequester(bar.currentTab) else Modifier,
                ) { focused ->
                    val fg = when { focused -> Color.Black; selected -> colors.onBackground; else -> colors.onSurfaceMuted }
                    Box(
                        Modifier.background(
                            when { focused -> Color.White; selected -> colors.onBackground.copy(alpha = 0.16f); else -> Color.Transparent }
                        ).padding(horizontal = if (iconOnly) 14.dp else 20.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (iconOnly) Icon(section.icon(), section.label, tint = fg, modifier = Modifier.size(22.dp))
                        else Text(section.short(), style = AuroraType.tab, color = fg, maxLines = 1)
                    }
                }
            }
        }
    }
}
