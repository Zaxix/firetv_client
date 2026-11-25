package com.auroratv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import com.auroratv.ui.nav.contentRegion
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


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, org.jetbrains.compose.resources.ExperimentalResourceApi::class)
@Composable
fun AuroraApp(container: AppContainer) {
    val settings by container.settings.settings.collectAsState()
    val cacheDir = container.platform.info.cacheDir
    setSingletonImageLoaderFactory { context ->
        // Its own connection pool, so posters never queue behind API calls (or the other way round).
        val imageHttp = com.auroratv.jellyfin.platformHttpEngine(maxRequestsPerHost = 12)?.let { HttpClient(it) } ?: HttpClient()
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
            // UI sounds: loaded once from the bundled files; settings applied as they change.
            LaunchedEffect(Unit) {
                val out = container.platform.uiSoundOutput() ?: return@LaunchedEffect
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.auroratv.ui.sound.UiSound.entries.forEach { sound ->
                        runCatching { out.load(sound, com.auroratv.resources.Res.readBytes(sound.file)) }
                    }
                }
                com.auroratv.ui.sound.UiSounds.output = out
            }
            LaunchedEffect(settings.navigationSounds, settings.uiSoundVolume) {
                com.auroratv.ui.sound.UiSounds.enabled = settings.navigationSounds
                com.auroratv.ui.sound.UiSounds.volume = settings.uiSoundVolume / 100f
            }
            Box(Modifier.fillMaxSize().background(Aurora.colors.background).onPreviewKeyEvent { ev ->
                // Arrow presses (only these) let the next focus change make the "move" sound.
                if (ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionUp || ev.key == Key.DirectionDown || ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight)) {
                    com.auroratv.ui.sound.UiSounds.noteDirectionalKey()
                }
                false
            }.arrowKeyNavigation()) {
                val state by container.sessions.state.collectAsState()
                LaunchedEffect(Unit) { container.sessions.start(skipPicker = container.initialRoute != null) }
                when (val s = state) {
                    SessionState.Starting -> Splash()
                    SessionState.NeedsServer -> ConnectScreen(container)
                    is SessionState.NeedsLogin -> key(s.server.id, s.username) { LoginScreen(container, s.server, s.message, s.username, s.canCancel) }
                    is SessionState.ChoosingProfile -> com.auroratv.ui.screens.ProfilesScreen(container, s)
                    is SessionState.Ready -> key(s.session.server.id, s.session.user.id, s.session.local?.id) {
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

private fun openLink(nav: Navigator, link: String) {
    when {
        link.startsWith("section:") -> Section.entries.firstOrNull { it.name.equals(link.substringAfter(':'), true) }?.let(nav::selectSection)
        link.startsWith("detail:") -> nav.push(Route.Detail(link.substringAfter(':')))
        link.startsWith("player:") -> nav.push(Route.Player(link.substringAfter(':')))
        link.startsWith("order:") -> nav.push(Route.EpisodeOrder(link.substringAfter(':')))
        link.startsWith("brand:") -> com.auroratv.brands.BrandCatalog.all.firstOrNull { it.id == link.substringAfter(':') }?.let { nav.push(Route.Brand(it.id, it.name)) }
    }
}

@Composable
private fun MainShell(container: AppContainer) {
    val navigator = remember {
        Navigator(Route.Root(Section.Home)).also { nav ->
            val link = container.initialRoute
            container.initialRoute = null
            if (link != null) openLink(nav, link)
        }
    }
    LaunchedEffect(navigator) {
        container.links.collect { link -> if (link != null) { container.links.value = null; openLink(navigator, link) } }
    }
    val entry = navigator.current

    val sideBar = remember { SideBarState() }
    /** Sidebar / content / profile-button focus rules (see FocusRegions). */
    val regions = remember { com.auroratv.ui.nav.FocusRegions() }
    /** The screen area; requesting focus here returns to the card/button that had it before the sidebar. */
    val contentFocus = regions.content
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val shellScope = androidx.compose.runtime.rememberCoroutineScope()

    // tvOS behaviour: Back walks up the stack, then to the sidebar, then to Home, then exits.
    // Back: closes the sidebar if it's open, then walks up the stack, then goes to Home; on Home it
    // asks for a second press within a couple of seconds before leaving the app.
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) { if (exitArmed) { kotlinx.coroutines.delay(2_500); exitArmed = false } }
    fun navigateBack(): Boolean = when {
        regions.headerHasFocus -> {
            if (!regions.focusContent()) focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
            true
        }
        sideBar.hasFocus -> {
            if (runCatching { contentFocus.requestFocus() }.isFailure) focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right)
            true
        }
        navigator.pop() -> true
        navigator.currentSection != Section.Home -> { navigator.selectSection(Section.Home); true }
        !exitArmed -> { exitArmed = true; true }
        else -> false
    }
    fun handleBack(): Boolean {
        container.platform.hideKeyboard() // Back always puts the on-screen keyboard away
        return navigateBack()
    }
    // Entering the app from sign-in: make sure no on-screen keyboard is left capturing the D-pad.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide(); container.platform.hideKeyboard() }
    // Look for a newer build every time the app opens (the prompt waits for the player to close).
    LaunchedEffect(Unit) {
        // After Home's first requests (the check goes to GitHub, not the media server, but shares the connection and the CPU).
        if (com.auroratv.core.PerfTuning.pass2) kotlinx.coroutines.delay(4_000)
        container.updates.checkOnLaunch()
    }
    // Probe the decoders (a few hundred ms on a Fire TV) once Home is up, not when Play is first pressed.
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(2_500); container.capabilities() }
    val updatePrompt by container.updates.prompt.collectAsState()
    val promptKeys = remember(updatePrompt?.build) { com.auroratv.ui.screens.UpdatePromptKeys(container.updates) }
    val backdrop = remember { com.auroratv.ui.components.BackdropState() }
    DisposableEffect(navigator) {
        container.backHandler = ::handleBack
        onDispose { container.backHandler = { false } }
    }

    // Safety net: if focus ends up nowhere (the focused card was removed when a row reloaded, a
    // screen changed under it), the D-pad would do nothing. Put it back on the screen's content.
    var shellFocused by remember { mutableStateOf(true) }
    LaunchedEffect(shellFocused, entry, updatePrompt) {
        if (shellFocused || entry.route.isFullScreen() || updatePrompt != null) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        if (!shellFocused) runCatching { contentFocus.requestFocus() }
    }

    // Which region holds focus, logged on change (CI's remote-control tests read it; cheap otherwise).
    val focusRegion = when {
        regions.headerHasFocus -> "header"
        sideBar.hasFocus -> "sidebar"
        shellFocused -> "content"
        else -> "none"
    }
    LaunchedEffect(focusRegion) { com.auroratv.core.Log.i("AuroraFocus", "region=$focusRegion") }

    CompositionLocalProvider(LocalNavigator provides navigator, LocalSideBar provides sideBar, com.auroratv.ui.nav.LocalFocusRegions provides regions) {
        Box(
            Modifier.fillMaxSize().onFocusChanged { shellFocused = it.hasFocus }.onPreviewKeyEvent { ev ->
                // The update card, when shown, gets every key first (it's modal).
                if (updatePrompt != null && !entry.route.isFullScreen()) return@onPreviewKeyEvent promptKeys.onKey(ev)
                // Holding Left lets focus move into the sidebar (see SideBarState.allowEntry).
                if (ev.key == Key.DirectionLeft) sideBar.allowEntry = ev.type == KeyEventType.KeyDown
                else if (ev.type == KeyEventType.KeyDown) sideBar.allowEntry = false
                false
            }.onKeyEvent { ev ->
                // Bubbling handler: screens that need Back (player, dialogs) consume it first.
                if ((ev.key == Key.Back || ev.key == Key.Escape) && ev.type == KeyEventType.KeyUp) {
                    if (!handleBack()) container.platform.exitApp()
                    true
                } else if ((ev.key == Key.Back || ev.key == Key.Escape) && ev.type == KeyEventType.KeyDown) {
                    true
                } else if (ev.key == Key.DirectionUp && ev.type == KeyEventType.KeyDown && !entry.route.isFullScreen() &&
                    !regions.headerHasFocus && !sideBar.hasFocus) {
                    // Up that nothing on screen used: move up if there's somewhere to go, otherwise this is
                    // the top of the screen, and Up from there (only) reaches the profile button.
                    if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)) regions.openHeader()
                    true
                } else false
            }
        ) {
            val isPlayer = entry.route.isFullScreen()
            // No UI sounds over a playing video.
            LaunchedEffect(isPlayer) { com.auroratv.ui.sound.UiSounds.suppressed = isPlayer }
            val motion = Aurora.motion
            if (!isPlayer) {
                // Frosted glass behind every screen (Home and detail pages paint their own over it).
                LaunchedEffect(entry) { backdrop.hash = null }
                // Home and detail pages cover the whole screen with their own; skip the hidden layer.
                val ownBackground = entry.route is Route.Detail || entry.route == Route.Root(Section.Home)
                if (!ownBackground) com.auroratv.ui.components.ShellBackdrop(backdrop)
                Box(Modifier.fillMaxSize().contentRegion()) { Box(Modifier.fillMaxSize().focusRequester(contentFocus).focusGroup().focusRestorer()) {
                    AnimatedContent(
                        targetState = entry,
                        transitionSpec = { fadeIn(tween(motion.duration(220))) togetherWith fadeOut(tween(motion.duration(120))) },
                    ) { e ->
                        CompositionLocalProvider(LocalEntry provides e, com.auroratv.ui.components.LocalBackdrop provides backdrop) { RouteContent(container, e.route) }
                    }
                } }
                SideBar(
                    navigator, sideBar,
                    // Right closes the sidebar exactly like Back: back to the card/button you were on.
                    onClose = { if (runCatching { contentFocus.requestFocus() }.isFailure) focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right) },
                ) { sameScreen ->
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
                // Who's watching: switch, add, edit or remove profiles (Up from the top of a screen).
                val session = LocalSession.current
                com.auroratv.ui.screens.ProfileButton(
                    container, session.profile,
                    Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 34.dp).then(regions.headerModifier()),
                )
            } else {
                CompositionLocalProvider(LocalEntry provides entry) { RouteContent(container, entry.route) }
            }
            androidx.compose.animation.AnimatedVisibility(
                exitArmed && !isPlayer,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
                enter = fadeIn(tween(motion.duration(150))), exit = fadeOut(tween(motion.duration(250))),
            ) {
                Text(
                    "Press Back again to exit",
                    style = AuroraType.bodyStrong, color = Aurora.colors.onBackground,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(com.auroratv.ui.components.Glass.fill(Aurora.colors.surface, opaque = true))
                        .border(1.dp, com.auroratv.ui.components.Glass.edge, RoundedCornerShape(50))
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
            val prompt = updatePrompt
            if (prompt != null && !isPlayer) {
                com.auroratv.ui.screens.UpdatePrompt(container.updates, prompt, promptKeys)
                DisposableEffect(prompt.build) {
                    // Give focus back to the screen once the card closes.
                    onDispose { shellScope.launch { kotlinx.coroutines.delay(80); if (runCatching { contentFocus.requestFocus() }.isFailure) sideBar.open() } }
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
        is Route.DiscMenu -> com.auroratv.discmenu.DiscMenuScreen(container, route)
        is Route.SplitEditor -> com.auroratv.ui.screens.SplitEditorScreen(route)
        is Route.EpisodeOrder -> com.auroratv.ui.screens.EpisodeOrderScreen(route)
    }
}

/** Screens that take the whole display: no sidebar, no UI sounds, no shell key handling. */
private fun Route.isFullScreen(): Boolean = this is Route.Player || this is Route.DiscMenu

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
    /**
     * The sidebar only takes focus when asked to: Left from the content (while the key is held) or
     * [open]. Without this, focus that's dropped while a screen changes (the card you clicked goes
     * away) landed on the first thing on screen, the sidebar, and opened it by itself.
     */
    var allowEntry by mutableStateOf(false)

    fun open(): Boolean {
        allowEntry = true
        return try { runCatching { currentItem.requestFocus() }.isSuccess } finally { allowEntry = false }
    }
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
private fun SideBar(navigator: Navigator, bar: SideBarState, onClose: () -> Unit, onSelected: (sameScreen: Boolean) -> Unit) {
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
                // Glass: translucent tinted fill with a light top edge (no live blur, which most Fire TV
                // models can't do and which would cost GPU time on every frame).
                .background(com.auroratv.ui.components.Glass.fill(colors.surface, opaque = expanded))
                .border(1.dp, com.auroratv.ui.components.Glass.edge, RoundedCornerShape(26.dp))
                .onFocusChanged { bar.hasFocus = it.hasFocus }
                .onPreviewKeyEvent { ev ->
                    if (ev.key == Key.DirectionRight && bar.hasFocus) { if (ev.type == KeyEventType.KeyDown) onClose(); true } else false
                }
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
        modifier = Modifier.fillMaxWidth()
            .focusProperties { canFocus = bar.hasFocus || bar.allowEntry }
            .then(if (selected) Modifier.focusRequester(bar.currentItem) else Modifier),
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
