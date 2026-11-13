package com.auroratv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.auroratv.jellyfin.model.TrickplayInfo
import com.auroratv.playback.EngineState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auroratv.jellyfin.Artwork
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.ticksToMs
import com.auroratv.playback.PlayMethod
import com.auroratv.playback.PlayerController
import com.auroratv.playback.SkipRange
import com.auroratv.playback.TrackSelector
import com.auroratv.settings.Settings
import com.auroratv.settings.SkipIntroMode
import com.auroratv.ui.LocalSession
import com.auroratv.ui.components.ArtworkImage
import com.auroratv.ui.components.AuroraButton
import com.auroratv.ui.components.CardStyle
import com.auroratv.ui.components.FocusSurface
import com.auroratv.ui.components.LoadingState
import com.auroratv.ui.components.ProgressStrip
import com.auroratv.ui.components.arrowKeyNavigation
import com.auroratv.ui.components.formatRuntime
import com.auroratv.ui.components.formatTime
import com.auroratv.ui.components.requestWidth
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private enum class Panel(val title: String) { None(""), Episodes("Episodes"), Chapters("Chapters"), Audio("Audio"), Subtitles("Subtitles"), Info("Info") }

/** Intro / recap / credits highlight colour on the timeline. */
private val SkipYellow = Color(0xFFFFD60A)

/** How long a Skip Intro / Skip Credits / Next Episode prompt stays up before getting out of the way. */
private const val SKIP_PROMPT_MS = 8_000L

/** D-pad scrubbing commits this long after the last press (holding keeps accumulating). */
private const val SCRUB_COMMIT_MS = 450L

/**
 * tvOS-style playback UI drawn over the native video surface.
 *
 * Performance: the engine publishes position a few times a second. Only the leaf composables that
 * show time (timeline, clocks) read it, and everything else reads [derivedStateOf] values that
 * change rarely (playing, buffering, active skip range), so the overlay isn't re-laid-out while
 * video plays and, with the controls hidden, nothing redraws at all. On Windows the overlay is a
 * separate transparent window, so this directly frees the GPU for video.
 */
@Composable
fun PlayerChrome(
    controller: PlayerController,
    settings: StateFlow<Settings>,
    onExit: () -> Unit,
    onError: (Throwable) -> Unit,
) {
    val uiState = controller.state.collectAsState()
    val ui by uiState
    val esState = controller.engineState.collectAsState()
    val s by settings.collectAsState()
    val scope = rememberCoroutineScope()

    val isPlaying by remember { derivedStateOf { esState.value.isPlaying } }
    val isBuffering by remember { derivedStateOf { esState.value.isBuffering && !esState.value.isEnded } }
    val activeSkip by remember { derivedStateOf { uiState.value.activeSkip(esState.value.positionMs) } }

    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.None) }
    var interaction by remember { mutableIntStateOf(0) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var scrubJob by remember { mutableStateOf<Job?>(null) }
    var repeatCount by remember { mutableIntStateOf(0) }
    var flash by remember { mutableStateOf<ImageVector?>(null) }

    val rootFocus = remember { FocusRequester() }
    val scrubFocus = remember { FocusRequester() }
    val skipFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }

    val showUpNext = ui.ended && ui.nextEpisode != null
    val isEpisode = ui.item?.isEpisode == true
    val skip = activeSkip?.takeIf { s.skipIntro != SkipIntroMode.Off }

    // Skip / Next Episode prompts hide themselves after a few seconds (like tvOS) and come back
    // whenever the controls are shown, instead of sitting over the credits.
    var skipExpired by remember { mutableStateOf(false) }
    LaunchedEffect(skip) {
        skipExpired = false
        if (skip != null) { delay(SKIP_PROMPT_MS); skipExpired = true }
    }
    val showSkip = skip != null && !showUpNext && ui.error == null && panel == Panel.None && (!skipExpired || controlsVisible)

    fun exit() { controller.stop(); onExit() }
    fun poke() { interaction++ }
    fun focusLater(r: FocusRequester) { scope.launch { delay(30); runCatching { r.requestFocus() } } }
    fun showControls() { controlsVisible = true; poke(); focusLater(scrubFocus) }
    fun hideControls() { controlsVisible = false; panel = Panel.None; runCatching { rootFocus.requestFocus() } }
    fun togglePlay() {
        flash = if (esState.value.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow
        controller.togglePlayPause(); poke()
    }
    fun openPanel(p: Panel) {
        if (p == Panel.Episodes) controller.loadEpisodes()
        panel = p; poke()
    }
    fun commitScrub() {
        scrubJob?.cancel()
        scrubTarget?.let { controller.seekTo(it) }
        scrubTarget = null
        repeatCount = 0
    }

    /** Accumulates repeated seek presses (hold to accelerate) and commits once the user pauses. */
    fun scrubBy(deltaMs: Long) {
        val es = esState.value
        val base = scrubTarget ?: es.positionMs
        val target = (base + deltaMs).coerceIn(0, if (es.durationMs > 0) es.durationMs else Long.MAX_VALUE)
        scrubTarget = target
        poke()
        scrubJob?.cancel()
        scrubJob = scope.launch { delay(SCRUB_COMMIT_MS); commitScrub() }
    }

    /** Mouse/touch: dragging previews (no seeking until release), so the decoder isn't flooded. */
    fun pointerScrub(fraction: Float) {
        val d = esState.value.durationMs
        if (d <= 0) return
        scrubJob?.cancel()
        scrubTarget = (d * fraction.coerceIn(0f, 1f)).toLong()
        poke()
    }

    LaunchedEffect(flash) { if (flash != null) { delay(650); flash = null } }
    LaunchedEffect(interaction, controlsVisible, isPlaying, panel, scrubTarget == null) {
        if (controlsVisible && isPlaying && panel == Panel.None && scrubTarget == null && ui.error == null) {
            delay(5_000)
            hideControls()
        }
    }
    LaunchedEffect(Unit) { focusLater(scrubFocus) }
    LaunchedEffect(panel) { if (panel != Panel.None) { delay(80); runCatching { panelFocus.requestFocus() } } }
    LaunchedEffect(showSkip, controlsVisible) {
        if (controlsVisible || panel != Panel.None) return@LaunchedEffect
        delay(50)
        // Focus the prompt while it's up; when it hides, hand focus back so the remote keeps working.
        runCatching { if (showSkip) skipFocus.requestFocus() else rootFocus.requestFocus() }
    }

    var countdown by remember { mutableIntStateOf(10) }
    LaunchedEffect(showUpNext) {
        if (!showUpNext) return@LaunchedEffect
        controlsVisible = false; panel = Panel.None
        countdown = 10
        if (!s.autoPlayNext) return@LaunchedEffect
        while (countdown > 0) { delay(1_000); countdown-- }
        controller.playNext()
    }
    LaunchedEffect(ui.ended, ui.nextEpisode) {
        if (ui.ended && ui.nextEpisode == null) { delay(800); onExit() }
    }

    fun handleKey(ev: KeyEvent): Boolean {
        val down = ev.type == KeyEventType.KeyDown
        val up = ev.type == KeyEventType.KeyUp
        when (ev.key) {
            Key.MediaPlayPause, Key.Spacebar -> { if (down) togglePlay(); return true }
            Key.MediaPlay -> { if (down) controller.play(); return true }
            Key.MediaPause -> { if (down) controller.pause(); return true }
            Key.MediaFastForward -> { if (down) scrubBy(s.seekForwardSeconds * 1000L); return true }
            Key.MediaRewind -> { if (down) scrubBy(-s.seekBackSeconds * 1000L); return true }
            Key.MediaNext -> { if (down && ui.nextEpisode != null) controller.playNext(); return true }
            Key.Back, Key.Escape -> {
                // One press leaves the player; with a panel open it closes the panel first.
                if (up) { if (panel != Panel.None) { panel = Panel.None; showControls() } else exit() }
                return true
            }
        }
        if (showUpNext || ui.error != null || panel != Panel.None) { poke(); return false }
        if (!controlsVisible) {
            if (!down) return ev.key in setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)
            when (ev.key) {
                Key.DirectionLeft -> { repeatCount++; scrubBy(-s.seekBackSeconds * 1000L * accel(repeatCount)); return true }
                Key.DirectionRight -> { repeatCount++; scrubBy(s.seekForwardSeconds * 1000L * accel(repeatCount)); return true }
                Key.DirectionUp, Key.Menu, Key.Info -> { showControls(); return true }
                Key.DirectionDown -> {
                    // Like swiping down on tvOS: straight to Episodes (series) or Info.
                    controlsVisible = true; openPanel(if (isEpisode) Panel.Episodes else Panel.Info); return true
                }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (showSkip) return false // the focused Skip button handles it
                    togglePlay(); showControls(); return true
                }
            }
            return false
        }
        poke()
        return false
    }

    Box(
        Modifier.fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent(::handleKey)
            .arrowKeyNavigation()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Press) {
                            if (!controlsVisible) controlsVisible = true
                            poke()
                        }
                    }
                }
            }
            .pointerInput(panel) {
                // Clicking the picture closes a panel, otherwise toggles play/pause (mouse on Windows).
                detectTapGestures(onTap = { if (panel != Panel.None) panel = Panel.None else togglePlay() })
            }
    ) {
        // Spinner only if buffering lasts a moment, so quick seeks don't flash it.
        var showSpinner by remember { mutableStateOf(false) }
        val wantSpinner = ui.loading || (isBuffering && ui.error == null)
        LaunchedEffect(wantSpinner) { if (wantSpinner) { delay(250); showSpinner = true } else showSpinner = false }
        if (showSpinner) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(46.dp))
            }
        }

        // Brief centred play/pause glyph when toggling, like tvOS.
        AnimatedVisibility(flash != null, Modifier.align(Alignment.Center), enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 1.2f)) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                flash?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(56.dp)) }
            }
        }

        // ---- Controls ----
        AnimatedVisibility(controlsVisible && !showUpNext && ui.error == null, enter = fadeIn(tween(Aurora.motion.duration(200))), exit = fadeOut(tween(Aurora.motion.duration(350)))) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.18f to Color.Transparent, 0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))

                // Always-visible way out of the player.
                Row(Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassPill(text = "Back", onClick = ::exit, icon = Icons.Filled.ArrowBack)
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 64.dp, vertical = 40.dp)) {
                    val item = ui.item
                    if (item?.isEpisode == true) {
                        Text(item.seriesName.orEmpty().uppercase(), style = AuroraType.caption, color = Color.White.copy(alpha = 0.7f))
                        Text(listOfNotNull(item.episodeLabel, item.name).joinToString("  ·  "), style = AuroraType.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(item?.name.orEmpty(), style = AuroraType.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(item?.productionYear?.toString(), item?.officialRating, formatRuntime(item?.runtimeMs)).joinToString("  ·  ")
                        if (meta.isNotBlank()) Text(meta, style = AuroraType.caption, color = Color.White.copy(alpha = 0.7f))
                    }
                    Spacer(Modifier.height(18.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassIcon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (isPlaying) "Pause" else "Play", ::togglePlay)
                        GlassIcon(Icons.Filled.Replay10, "Back ${s.seekBackSeconds} seconds", { scrubBy(-s.seekBackSeconds * 1000L) })
                        GlassIcon(Icons.Filled.Forward30, "Forward ${s.seekForwardSeconds} seconds", { scrubBy(s.seekForwardSeconds * 1000L) })
                        Spacer(Modifier.width(14.dp))
                        if (isEpisode) GlassPill(text = "Episodes", onClick = { openPanel(Panel.Episodes) })
                        if (ui.chapters.size > 1) GlassPill(text = "Chapters", onClick = { openPanel(Panel.Chapters) })
                        if (ui.audioStreams.size > 1) GlassPill(text = "Audio", onClick = { openPanel(Panel.Audio) })
                        GlassPill(text = "Subtitles", onClick = { openPanel(Panel.Subtitles) })
                        GlassPill(text = "Info", onClick = { openPanel(Panel.Info) })
                        Spacer(Modifier.weight(1f))
                        if (ui.nextEpisode != null) GlassPill(text = "Next Episode", onClick = { controller.playNext() }, icon = Icons.Filled.SkipNext)
                    }
                    Spacer(Modifier.height(22.dp))

                    Scrubber(
                        engine = esState,
                        scrubTarget = scrubTarget,
                        chapters = remember(ui.chapters) { ui.chapters.map { it.startPositionTicks.ticksToMs() } },
                        skipRanges = ui.skipRanges,
                        preview = ui.item?.id?.let { id -> ui.trickplay?.let { (src, info) -> TrickplayRef(id, src, info) } },
                        focusRequester = scrubFocus,
                        onScrub = { delta -> scrubBy(delta) },
                        onSelect = { if (scrubTarget != null) commitScrub() else togglePlay() },
                        onDown = { openPanel(if (isEpisode) Panel.Episodes else Panel.Info) },
                        onPointerScrub = ::pointerScrub,
                        onPointerRelease = ::commitScrub,
                        seekStepMs = s.seekForwardSeconds * 1000L,
                    )
                    Spacer(Modifier.height(8.dp))
                    TimeRow(esState, scrubTarget, ui.skipRanges)
                }
            }
        }

        // ---- Scrub-only bar when seeking with controls hidden ----
        scrubTarget?.let { target ->
            if (!controlsVisible) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))).padding(horizontal = 64.dp, vertical = 40.dp)) {
                    val duration by remember { derivedStateOf { esState.value.durationMs } }
                    Track(
                        target, duration, bufferedMs = 0, focused = true, chapters = emptyList(), skipRanges = ui.skipRanges, showBubble = true,
                        preview = ui.item?.id?.let { id -> ui.trickplay?.let { (src, info) -> TrickplayRef(id, src, info) } },
                    )
                }
            }
        }

        // ---- Skip intro / recap / credits (auto-hides; returns with the controls) ----
        AnimatedVisibility(
            showSkip,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = if (controlsVisible) 230.dp else 64.dp),
            enter = fadeIn(tween(Aurora.motion.duration(200))),
            exit = fadeOut(tween(Aurora.motion.duration(300))),
        ) {
            val shown = skip ?: return@AnimatedVisibility
            val next = shown.kind == SkipRange.Kind.Credits && ui.nextEpisode != null
            AuroraButton(if (next) "Next Episode" else shown.kind.label, {
                if (next) controller.playNext() else controller.skipSegment()
            }, primary = true, focusRequester = skipFocus)
        }

        if (showUpNext) UpNextCard(ui.nextEpisode!!, controller, if (s.autoPlayNext) countdown else null, onExit = ::exit)

        ui.error?.let { message ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(620.dp)) {
                    Text("Playback problem", style = AuroraType.title, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Text(message, style = AuroraType.body, color = Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.height(24.dp))
                    val retryFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { delay(50); runCatching { retryFocus.requestFocus() } }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AuroraButton("Try with transcoding", { controller.retry() }, primary = true, focusRequester = retryFocus)
                        AuroraButton("Exit", ::exit)
                    }
                }
            }
        }

        // ---- Bottom sheet panels ----
        AnimatedVisibility(
            panel != Panel.None,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(Aurora.motion.duration(260))) { it } + fadeIn(),
            exit = slideOutVertically(tween(Aurora.motion.duration(200))) { it } + fadeOut(),
        ) {
            Column(
                Modifier.fillMaxWidth().height(360.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(Color(0xE6121214))
                    .padding(start = 56.dp, end = 56.dp, top = 22.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(panel.title, style = AuroraType.section, color = Color.White)
                    Spacer(Modifier.weight(1f))
                    Text("Back to close", style = AuroraType.caption, color = Color.White.copy(alpha = 0.5f))
                }
                Spacer(Modifier.height(14.dp))
                when (panel) {
                    Panel.Episodes -> EpisodesPanel(controller, panelFocus) { panel = Panel.None }
                    Panel.Chapters -> ChaptersPanel(controller, remember { esState.value.positionMs }, panelFocus) { panel = Panel.None }
                    Panel.Audio -> OptionRow(
                        options = ui.audioStreams.map { it.index to TrackSelector.label(it) },
                        selected = ui.audioIndex, firstFocus = panelFocus,
                        onSelect = { idx -> idx?.let { controller.selectAudio(it) }; panel = Panel.None },
                    )
                    Panel.Subtitles -> Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassPill(text = "A−", onClick = { controller.setSubtitleScale(ui.subtitleScale - 0.1f) })
                            Text("Size ${(ui.subtitleScale * 100).toInt()}%", style = AuroraType.label, color = Color.White)
                            GlassPill(text = "A+", onClick = { controller.setSubtitleScale(ui.subtitleScale + 0.1f) })
                            if (controller.supportsSubtitleDelay && ui.subtitleIndex != null) {
                                Spacer(Modifier.width(24.dp))
                                GlassPill(text = "−0.1s", onClick = { controller.adjustSubtitleDelay(-100) })
                                Text("Delay ${ui.subtitleDelayMs} ms", style = AuroraType.label, color = Color.White)
                                GlassPill(text = "+0.1s", onClick = { controller.adjustSubtitleDelay(100) })
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OptionRow(
                            options = listOf<Pair<Int?, String>>(null to "Off") + ui.subtitleStreams.map { it.index to TrackSelector.label(it) },
                            selected = ui.subtitleIndex, firstFocus = panelFocus,
                            onSelect = { idx -> controller.selectSubtitle(idx); panel = Panel.None },
                        )
                    }
                    Panel.Info -> InfoPanel(controller, panelFocus)
                    Panel.None -> Unit
                }
            }
        }
    }
}

/** Elapsed / remaining clocks. Reads the engine position itself so only this row recomposes as time passes. */
@Composable
private fun TimeRow(engine: State<EngineState>, scrubTarget: Long?, skipRanges: List<SkipRange>) {
    val es by engine
    val pos = scrubTarget ?: es.positionMs
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(pos), style = AuroraType.label, color = Color.White)
        if (skipRanges.isNotEmpty()) {
            Spacer(Modifier.width(18.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(SkipYellow))
            Spacer(Modifier.width(6.dp))
            Text(
                skipRanges.map { when (it.kind) { SkipRange.Kind.Credits -> "Credits"; SkipRange.Kind.Recap -> "Recap"; SkipRange.Kind.Intro -> "Intro" } }.distinct().joinToString(" · "),
                style = AuroraType.caption, color = Color.White.copy(alpha = 0.75f),
            )
        }
        Spacer(Modifier.weight(1f))
        Text("-" + formatTime((es.durationMs - pos).coerceAtLeast(0)), style = AuroraType.label, color = Color.White.copy(alpha = 0.8f))
    }
}

private fun accel(repeat: Int): Long = when {
    repeat < 4 -> 1
    repeat < 10 -> 2
    repeat < 20 -> 4
    else -> 8
}

/** Frosted capsule used for player buttons (white with black text when focused, like tvOS). */
@Composable
private fun GlassPill(text: String, onClick: () -> Unit, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    FocusSurface(onClick = onClick, shape = RoundedCornerShape(50), sheen = false, modifier = modifier) { focused ->
        Row(
            Modifier.background(if (focused) Color.White else Color.White.copy(alpha = 0.16f)).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) Icon(icon, null, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(20.dp))
            Text(text, style = AuroraType.label, color = if (focused) Color.Black else Color.White)
        }
    }
}

@Composable
private fun GlassIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, shape = CircleShape, sheen = false, modifier = Modifier.size(46.dp)) { focused ->
        Box(Modifier.fillMaxSize().background(if (focused) Color.White else Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun <T> OptionRow(options: List<Pair<T, String>>, selected: T?, firstFocus: FocusRequester, onSelect: (T?) -> Unit) {
    if (options.isEmpty()) {
        Text("None available", style = AuroraType.body, color = Color.White.copy(alpha = 0.7f)); return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.widthIn(max = 720.dp)) {
        itemsIndexed(options) { i, (value, label) ->
            FocusSurface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(12.dp), sheen = false, scaleOnFocus = false,
                modifier = Modifier.fillMaxWidth().then(if ((selected == null && i == 0) || value == selected) Modifier.focusRequester(firstFocus) else Modifier),
            ) { focused ->
                Row(Modifier.fillMaxWidth().background(if (focused) Color.White else Color.Transparent).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp)) { if (value == selected) Icon(Icons.Filled.Check, null, tint = if (focused) Color.Black else Color.White) }
                    Spacer(Modifier.width(10.dp))
                    Text(label, style = AuroraType.bodyStrong, color = if (focused) Color.Black else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** In-player episode browser: season capsules and a row of episode stills; selecting one plays it. */
@Composable
private fun EpisodesPanel(controller: PlayerController, focus: FocusRequester, onClose: () -> Unit) {
    val ui by controller.state.collectAsState()
    val api = LocalSession.current.api
    val current = ui.item ?: return
    var season by remember { mutableStateOf(current.seasonId) }
    LaunchedEffect(season) { controller.loadEpisodes(season) }
    val episodes = season?.let { ui.episodesBySeason[it] }

    if (ui.seasons.size > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.seasons, key = { it.id }) { sn ->
                val selected = sn.id == season
                FocusSurface(onClick = { season = sn.id }, shape = RoundedCornerShape(50), sheen = false) { focused ->
                    Text(
                        sn.name ?: "Season ${sn.indexNumber}", style = AuroraType.label,
                        color = if (focused) Color.Black else Color.White,
                        modifier = Modifier.background(if (focused) Color.White else if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
    if (episodes == null) { Box(Modifier.fillMaxWidth().height(180.dp)) { LoadingState() }; return }
    val currentIndex = episodes.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    val listState = rememberLazyListState((currentIndex - 1).coerceAtLeast(0))
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
        itemsIndexed(episodes, key = { _, e -> e.id }) { i, ep ->
            val playing = ep.id == current.id
            Column(Modifier.width(CardStyle.Landscape.width)) {
                FocusSurface(
                    onClick = { controller.playEpisode(ep.id); onClose() },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).then(if (i == currentIndex) Modifier.focusRequester(focus) else Modifier),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        ArtworkImage(api.images.landscape(ep, CardStyle.Landscape.requestWidth()), ep.name, Modifier.fillMaxSize())
                        if (playing) {
                            Text(
                                "NOW PLAYING", style = AuroraType.caption, color = Color.Black,
                                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(SkipYellow).padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        ep.progressFraction?.takeIf { !playing && ep.userData?.played != true }?.let {
                            ProgressStrip(it, Modifier.align(Alignment.BottomCenter).padding(10.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(listOfNotNull(ep.indexNumber?.let { "Episode $it" }, formatRuntime(ep.runtimeMs)).joinToString(" · "), style = AuroraType.caption, color = Color.White.copy(alpha = 0.6f))
                Text(ep.name.orEmpty(), style = AuroraType.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ChaptersPanel(controller: PlayerController, positionMs: Long, focus: FocusRequester, onClose: () -> Unit) {
    val ui by controller.state.collectAsState()
    val api = LocalSession.current.api
    val item = ui.item ?: return
    val current = ui.chapters.indexOfLast { it.startPositionTicks.ticksToMs() <= positionMs }.coerceAtLeast(0)
    LazyRow(state = rememberLazyListState((current - 1).coerceAtLeast(0)), horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
        itemsIndexed(ui.chapters) { i, ch ->
            Column(Modifier.width(240.dp)) {
                FocusSurface(
                    onClick = { controller.seekTo(ch.startPositionTicks.ticksToMs()); onClose() },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).then(if (i == current) Modifier.focusRequester(focus) else Modifier),
                ) {
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                        val img = api.images.chapter(item.id, i, ch.imageTag, 480)
                        if (img != null) ArtworkImage(Artwork(img, null), ch.name, Modifier.fillMaxSize())
                        else Text("${i + 1}", style = AuroraType.title, color = Color.White.copy(alpha = 0.5f))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(formatTime(ch.startPositionTicks.ticksToMs()), style = AuroraType.caption, color = Color.White.copy(alpha = 0.6f))
                Text(ch.name ?: "Chapter ${i + 1}", style = AuroraType.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun InfoPanel(controller: PlayerController, focus: FocusRequester) {
    val ui by controller.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }
    val plan = ui.plan
    val lines = buildList {
        add("Engine" to controller.engineName)
        plan?.let { p ->
            add("Play method" to p.method.label)
            if (p.method != PlayMethod.DirectPlay && p.transcodeReasons.isNotEmpty()) add("Reason" to p.transcodeReasons.joinToString(", ") { r -> r.replace(Regex("([a-z])([A-Z])"), "$1 $2") })
            add("Container" to (p.mediaSource.container?.uppercase() ?: "—"))
            p.mediaSource.videoStream?.let { v ->
                add("Video" to listOfNotNull(v.codec?.uppercase(), v.profile, v.width?.let { w -> "${w}x${v.height}" }, v.bitDepth?.let { b -> "$b-bit" }, v.videoRangeType).joinToString(" · "))
            }
            p.audioStreams.firstOrNull { it.index == ui.audioIndex }?.let { a -> add("Audio" to (a.displayTitle ?: a.codec.orEmpty())) }
            p.subtitleStreams.firstOrNull { it.index == ui.subtitleIndex }?.let { st -> add("Subtitles" to "${st.displayTitle ?: st.codec} (${st.deliveryMethod ?: "?"})") }
        }
        if (tick >= 0) addAll(controller.engineStats())
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(end = 24.dp)) {
            ui.item?.overview?.let { Text(it, style = AuroraType.body, color = Color.White.copy(alpha = 0.85f), maxLines = 7, overflow = TextOverflow.Ellipsis) }
            Box(Modifier.size(1.dp).focusRequester(focus).focusable())
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(lines) { (k, v) ->
                Row {
                    Text(k, style = AuroraType.caption, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.width(140.dp))
                    Text(v, style = AuroraType.caption, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun UpNextCard(next: BaseItemDto, controller: PlayerController, countdown: Int?, onExit: () -> Unit) {
    val focus = remember { FocusRequester() }
    val api = LocalSession.current.api
    LaunchedEffect(Unit) { delay(50); runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f)))) {
        Row(Modifier.align(Alignment.BottomEnd).padding(64.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.width(320.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
                ArtworkImage(api.images.landscape(next, 640), next.name, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.width(420.dp)) {
                Text("UP NEXT", style = AuroraType.caption, color = Color.White.copy(alpha = 0.7f))
                Text(listOfNotNull(next.episodeLabel, next.name).joinToString(" · "), style = AuroraType.section, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(next.overview ?: "", style = AuroraType.body, color = Color.White.copy(alpha = 0.75f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AuroraButton(if (countdown != null) "Play in $countdown" else "Play Next", { controller.playNext() }, icon = Icons.Filled.PlayArrow, primary = true, focusRequester = focus)
                    AuroraButton("Back to Browse", onExit)
                }
            }
        }
    }
}

/** Which trickplay sheet set to draw scrub previews from. */
private data class TrickplayRef(val itemId: String, val mediaSourceId: String, val info: TrickplayInfo)

/**
 * tvOS scrubber: thin track that thickens on focus, a time bubble (and a trickplay thumbnail when
 * the server has them) over the playhead while scrubbing, chapter ticks, and intro/recap/credits
 * ranges highlighted in yellow. Works with the D-pad (hold to accelerate), and with a mouse:
 * click to jump, drag to scrub, hover to preview.
 */
@Composable
private fun Scrubber(
    engine: State<EngineState>,
    scrubTarget: Long?,
    chapters: List<Long>,
    skipRanges: List<SkipRange>,
    preview: TrickplayRef?,
    focusRequester: FocusRequester,
    onScrub: (Long) -> Unit,
    onSelect: () -> Unit,
    onDown: () -> Unit,
    onPointerScrub: (Float) -> Unit,
    onPointerRelease: () -> Unit,
    seekStepMs: Long,
) {
    val es by engine
    var focused by remember { mutableStateOf(false) }
    var held by remember { mutableIntStateOf(0) }
    var hover by remember { mutableStateOf<Float?>(null) }
    val scrub by rememberUpdatedState(onPointerScrub)
    val release by rememberUpdatedState(onPointerRelease)
    val duration = es.durationMs
    val hoverMs = hover?.takeIf { scrubTarget == null && duration > 0 }?.let { (it * duration).toLong() }
    Box(
        Modifier.fillMaxWidth().height(44.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (!focused) return@onPreviewKeyEvent false
                when {
                    ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft -> { held++; onScrub(-seekStepMs / 3 * accel(held)); true }
                    ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight -> { held++; onScrub(seekStepMs / 3 * accel(held)); true }
                    ev.type == KeyEventType.KeyUp && (ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight) -> { held = 0; true }
                    // Down from the timeline opens Episodes/Info, like swiping down on tvOS.
                    ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown -> { onDown(); true }
                    ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionCenter || ev.key == Key.Enter) -> true
                    ev.type == KeyEventType.KeyUp && (ev.key == Key.DirectionCenter || ev.key == Key.Enter) -> { onSelect(); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        hover = when (e.type) {
                            PointerEventType.Exit -> null
                            else -> (e.changes.first().position.x / size.width).coerceIn(0f, 1f)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { o -> scrub(o.x / size.width); release() })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> scrub(o.x / size.width) },
                    onHorizontalDrag = { change, _ -> change.consume(); scrub(change.position.x / size.width) },
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                )
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        Track(
            positionMs = scrubTarget ?: es.positionMs, durationMs = duration, bufferedMs = es.bufferedMs, focused = focused,
            chapters = chapters, skipRanges = skipRanges, showBubble = focused || scrubTarget != null,
            preview = preview, hoverMs = hoverMs,
        )
    }
}

@Composable
private fun Track(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    focused: Boolean,
    chapters: List<Long>,
    skipRanges: List<SkipRange>,
    showBubble: Boolean,
    preview: TrickplayRef? = null,
    hoverMs: Long? = null,
) {
    val height by animateDpAsState(if (focused) 10.dp else 5.dp, tween(Aurora.motion.duration(150)))
    fun frac(ms: Long) = if (durationMs > 0) (ms.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.BottomStart) {
        val w = maxWidth
        val bar = Modifier.padding(bottom = 6.dp)
        Box(bar.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f)))
        Box(bar.fillMaxWidth(frac(bufferedMs)).height(height).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.32f)))
        skipRanges.forEach { r ->
            val start = frac(r.startMs); val end = frac(r.endMs)
            if (end > start) Box(bar.offset(x = w * start).width(w * (end - start)).height(height).clip(RoundedCornerShape(50)).background(SkipYellow))
        }
        Box(bar.fillMaxWidth(frac(positionMs)).height(height).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.92f)))
        if (durationMs > 0) chapters.filter { it > 0 }.forEach { c ->
            Box(bar.offset(x = w * frac(c)).size(width = 2.dp, height = height).background(Color.Black.copy(alpha = 0.55f)))
        }
        if (focused || showBubble) {
            Box(Modifier.padding(bottom = 6.dp - (16.dp - height) / 2).offset(x = w * frac(positionMs) - 8.dp).size(16.dp).clip(CircleShape).background(Color.White))
        }
        // The bubble follows the mouse when hovering, otherwise the playhead while scrubbing.
        val bubbleMs = hoverMs ?: positionMs.takeIf { showBubble }
        if (bubbleMs != null) {
            val thumbW = 256.dp
            val x = (w * frac(bubbleMs) - 36.dp).coerceAtMost(w - 72.dp).coerceAtLeast(0.dp)
            if (preview != null && durationMs > 0) {
                val tx = (w * frac(bubbleMs) - thumbW / 2).coerceAtMost(w - thumbW).coerceAtLeast(0.dp)
                // The thumbnail is taller than this 44dp track, so measure it unbounded and hang it
                // bottom-aligned just above the time bubble.
                TrickplayThumb(preview, bubbleMs, thumbW, Modifier.offset(x = tx, y = (-54).dp).wrapContentSize(Alignment.BottomStart, unbounded = true))
            }
            Text(
                formatTime(bubbleMs), style = AuroraType.caption, color = Color.Black, textAlign = TextAlign.Center,
                modifier = Modifier.offset(x = x, y = (-20).dp).width(72.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(vertical = 3.dp),
            )
        }
    }
}

/**
 * One frame from a Jellyfin trickplay sprite sheet. The sheet is decoded once at its native size
 * and the wanted cell is selected with an alignment offset, so moving the playhead within a sheet
 * costs no network or decode work.
 */
@Composable
private fun TrickplayThumb(ref: TrickplayRef, positionMs: Long, width: Dp, modifier: Modifier = Modifier) {
    val api = LocalSession.current.api
    val info = ref.info
    val perSheet = info.tileWidth * info.tileHeight
    val n = (positionMs / info.interval).toInt().coerceIn(0, (info.thumbnailCount - 1).coerceAtLeast(0))
    val sheet = n / perSheet
    val cell = n % perSheet
    val col = cell % info.tileWidth
    val row = cell / info.tileWidth
    val density = LocalDensity.current
    val scale = with(density) { width.toPx() / info.width }
    val height = width * info.height / info.width
    val headers = remember(api) { api.mediaHeaders() }
    val url = remember(ref, sheet) { api.images.trickplayTile(ref.itemId, ref.mediaSourceId, info.width, sheet) }
    val cellAlign = remember(col, row) {
        Alignment { _, _, _ -> IntOffset(-col * info.width, -row * info.height) }
    }
    Box(modifier.size(width, height).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
        Box(
            Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(with(density) { info.width.toDp() }, with(density) { info.height.toDp() })
                .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
                .clipToBounds()
        ) {
            ArtworkImage(
                Artwork(url, null), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.None, alignment = cellAlign, showPlaceholder = false,
                headers = headers, crossfade = false,
            )
        }
    }
}
