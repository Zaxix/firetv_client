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
import androidx.compose.foundation.focusGroup
import kotlinx.coroutines.launch
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
import com.auroratv.settings.effectiveInterfaceScale
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

    // One scale for the whole UI (cards, text, spacing), so the layout keeps its proportions at any size.
    val platformDensity = androidx.compose.ui.platform.LocalDensity.current
    val scale = settings.effectiveInterfaceScale(container.platform.info.isTv) / 100f
    val density = remember(platformDensity, scale) { androidx.compose.ui.unit.Density(platformDensity.density * scale, platformDensity.fontScale) }
    AuroraTheme(settings.theme, settings.animations) {
        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides density,
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
            com.auroratv.ui.components.AppLogoImage(Modifier.size(120.dp))
            Spacer(Modifier.height(16.dp))
            Text(com.auroratv.AppInfo.NAME, style = AuroraType.hero, color = Aurora.colors.onBackground)
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
                link.startsWith("brand:") -> com.auroratv.brands.BrandCatalog.all.firstOrNull { it.id == link.substringAfter(':') }?.let { nav.push(Route.Brand(it.id, it.name)) }
            }
        }
    }
    val entry = navigator.current

    val sideBar = remember { SideBarState() }
    /** The screen area; requesting focus here returns to the card/button that had it before the sidebar. */
    val contentFocus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val shellScope = androidx.compose.runtime.rememberCoroutineScope()

    // tvOS behaviour: Back walks up the stack, then to the sidebar, then to Home, then exits.
    fun handleBack(): Boolean = when {
        navigator.pop() -> true
        !sideBar.hasFocus -> runCatching { sideBar.currentItem.requestFocus() }.isSuccess
        navigator.currentSection != Section.Home -> { navigator.selectSection(Section.Home); true }
        else -> false
    }
    // Entering the app from sign-in: make sure no on-screen keyboard is left capturing the D-pad.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide(); container.platform.hideKeyboard() }
    // Look for a newer build once a day (never during playback: the prompt waits for the player to close).
    LaunchedEffect(Unit) { container.updates.checkIfDue() }
    val updatePrompt by container.updates.prompt.collectAsState()
    DisposableEffect(navigator) {
        container.backHandler = ::handleBack
        onDispose { container.backHandler = { false } }
    }

    CompositionLocalProvider(LocalNavigator provides navigator, LocalSideBar provides sideBar) {
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
                Box(Modifier.fillMaxSize().focusRequester(contentFocus).focusGroup().focusRestorer()) {
                    AnimatedContent(
                        targetState = entry,
                        transitionSpec = { fadeIn(tween(motion.duration(220))) togetherWith fadeOut(tween(motion.duration(120))) },
                    ) { e ->
                        CompositionLocalProvider(LocalEntry provides e) { RouteContent(container, e.route) }
                    }
                }
                SideBar(navigator, sideBar) { sameScreen ->
                    if (sameScreen) {
                        // Choosing the section you're already on just closes the sidebar and goes back
                        // to where you were on that screen.
                        if (runCatching { contentFocus.requestFocus() }.isFailure) focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right)
                    } else {
                        // After choosing a section, go to its content (like tvOS) once it has composed.
                        shellScope.launch {
                            kotlinx.coroutines.delay(120)
                            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right)
                        }
                    }
                }
            } else {
                CompositionLocalProvider(LocalEntry provides entry) { RouteContent(container, entry.route) }
            }
            val prompt = updatePrompt
            if (prompt != null && !isPlayer) {
                com.auroratv.ui.screens.UpdatePrompt(container.updates, prompt)
                DisposableEffect(prompt.build) {
                    // Give focus back to the screen once the card closes.
                    onDispose { shellScope.launch { kotlinx.coroutines.delay(80); runCatching { sideBar.currentItem.requestFocus() } } }
                }
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
        is Route.Brand -> com.auroratv.ui.screens.BrandHubScreen(route)
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

/** Shared state for the sidebar: whether it has focus (expanded) and how to put focus back on it. */
class SideBarState {
    var hasFocus by mutableStateOf(false)
    val currentItem = FocusRequester()
}

val LocalSideBar = staticCompositionLocalOf { SideBarState() }

/** Width of the collapsed sidebar rail, including its margin. */
val SideBarRail = 100.dp

/** Space screens leave at the top (there's no top bar any more; just breathing room). */
val ScreenTop = 40.dp

private fun Section.short(): String = when (this) {
    Section.Continue -> "Up Next"
    else -> label
}

/**
 * tvOS-style sidebar. Collapsed it's a slim floating glass rail of icons that stays out of the
 * way of rows; when it gets focus (Left from the content, or Back) it widens to show labels and
 * dims the content behind it. Select switches section and hands focus back to the content.
 * Only the rail re-lays out while it animates; the content underneath is untouched.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SideBar(navigator: Navigator, bar: SideBarState, onSelected: (sameScreen: Boolean) -> Unit) {
    val colors = Aurora.colors
    val motion = Aurora.motion
    val expanded = bar.hasFocus
    val width by animateDpAsState(if (expanded) 264.dp else 68.dp, tween(motion.duration(220)))
    val scrim by androidx.compose.animation.core.animateFloatAsState(if (expanded) 1f else 0f, tween(motion.duration(220)))
    Box(Modifier.fillMaxSize()) {
        if (scrim > 0f) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = scrim }
                    .background(Brush.horizontalGradient(0f to colors.background.copy(alpha = 0.92f), 0.42f to colors.background.copy(alpha = 0.55f), 0.75f to Color.Transparent))
            )
        }
        Column(
            Modifier.padding(start = 16.dp, top = 24.dp, bottom = 24.dp)
                .fillMaxHeight()
                .width(width)
                .clip(RoundedCornerShape(26.dp))
                .background(colors.surface.copy(alpha = if (expanded) 0.94f else 0.72f))
                .onFocusChanged { bar.hasFocus = it.hasFocus }
                .focusRestorer(bar.currentItem)
                .padding(vertical = 14.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val top = listOf(Section.Search)
            val main = listOf(Section.Home, Section.Continue, Section.Movies, Section.Shows, Section.Favorites, Section.Libraries)
            top.forEach { SideBarItem(it, navigator, bar, expanded, onSelected) }
            Spacer(Modifier.height(10.dp))
            main.forEach { SideBarItem(it, navigator, bar, expanded, onSelected) }
            Spacer(Modifier.weight(1f))
            SideBarItem(Section.Settings, navigator, bar, expanded, onSelected)
        }
    }
}

@Composable
private fun SideBarItem(section: Section, navigator: Navigator, bar: SideBarState, expanded: Boolean, onSelected: (sameScreen: Boolean) -> Unit) {
    val colors = Aurora.colors
    val selected = navigator.currentSection == section
    FocusSurface(
        onClick = {
            val same = navigator.currentSection == section && navigator.current.route == Route.Root(section)
            navigator.selectSection(section)
            onSelected(same)
        },
        shape = RoundedCornerShape(16.dp),
        sheen = false,
        scaleOnFocus = false,
        modifier = Modifier.fillMaxWidth().then(if (selected) Modifier.focusRequester(bar.currentItem) else Modifier),
    ) { focused ->
        val fg = when { focused -> Color.Black; selected -> colors.onBackground; else -> colors.onSurfaceMuted }
        Row(
            Modifier.fillMaxWidth()
                .background(when { focused -> Color.White; selected -> colors.onBackground.copy(alpha = 0.14f); else -> Color.Transparent })
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(section.icon(), section.label, tint = fg, modifier = Modifier.size(24.dp))
            if (expanded) {
                Spacer(Modifier.width(16.dp))
                Text(section.short(), style = AuroraType.tab, color = fg, maxLines = 1)
            }
        }
    }
}
