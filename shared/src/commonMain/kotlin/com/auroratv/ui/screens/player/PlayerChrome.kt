package com.auroratv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ViewList
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auroratv.jellyfin.model.ticksToMs
import com.auroratv.playback.PlayMethod
import com.auroratv.playback.PlayerController
import com.auroratv.playback.TrackSelector
import com.auroratv.settings.Settings
import com.auroratv.settings.SkipIntroMode
import com.auroratv.ui.components.AuroraButton
import com.auroratv.ui.components.FocusSurface
import com.auroratv.ui.components.IconAction
import com.auroratv.ui.components.arrowKeyNavigation
import com.auroratv.ui.components.LoadingState
import com.auroratv.ui.components.formatTime
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private enum class Panel { None, Audio, Subtitles, Chapters, Info }

/**
 * Apple TV-style playback controls. Everything here is plain Compose drawn above the native video
 * surface; animations are opacity/offset only and never touch the decoder.
 */
@Composable
fun PlayerChrome(
    controller: PlayerController,
    settings: StateFlow<Settings>,
    onExit: () -> Unit,
    onError: (Throwable) -> Unit,
) {
    val ui by controller.state.collectAsState()
    val es by controller.engineState.collectAsState()
    val s by settings.collectAsState()
    val scope = rememberCoroutineScope()

    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(Panel.None) }
    var interaction by remember { mutableIntStateOf(0) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var scrubJob by remember { mutableStateOf<Job?>(null) }
    var repeatCount by remember { mutableIntStateOf(0) }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val skipFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }

    val segment = ui.activeSegment(es.positionMs)?.takeIf { s.skipIntro != SkipIntroMode.Off }
    val showUpNext = ui.ended && ui.nextEpisode != null

    fun poke() { interaction++ }
    fun showControls() {
        controlsVisible = true; poke()
        scope.launch { delay(16); runCatching { playFocus.requestFocus() } }
    }
    fun hideControls() {
        controlsVisible = false; panel = Panel.None
        runCatching { rootFocus.requestFocus() }
    }

    /** Accumulates repeated seek presses (hold to accelerate) and commits once the user pauses. */
    fun scrubBy(deltaMs: Long) {
        val base = scrubTarget ?: es.positionMs
        val target = (base + deltaMs).coerceIn(0, if (es.durationMs > 0) es.durationMs else Long.MAX_VALUE)
        scrubTarget = target
        poke()
        scrubJob?.cancel()
        scrubJob = scope.launch {
            delay(650)
            controller.seekTo(target)
            scrubTarget = null
            repeatCount = 0
        }
    }

    // Auto-hide controls 4.5s after the last interaction while playing.
    LaunchedEffect(interaction, controlsVisible, es.isPlaying, panel) {
        if (controlsVisible && es.isPlaying && panel == Panel.None && ui.error == null) {
            delay(4_500)
            hideControls()
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { playFocus.requestFocus() } }
    LaunchedEffect(panel) { if (panel != Panel.None) { delay(50); runCatching { panelFocus.requestFocus() } } }
    LaunchedEffect(segment?.startTicks, controlsVisible) {
        if (segment != null && !controlsVisible) { delay(50); runCatching { skipFocus.requestFocus() } }
    }

    // Up Next countdown when an episode ends.
    var countdown by remember { mutableIntStateOf(10) }
    LaunchedEffect(showUpNext) {
        if (!showUpNext) return@LaunchedEffect
        controlsVisible = false
        countdown = 10
        if (!s.autoPlayNext) return@LaunchedEffect
        while (countdown > 0) { delay(1_000); countdown-- }
        controller.playNext()
    }
    LaunchedEffect(ui.ended, ui.nextEpisode) {
        // Movie (or last episode) finished: leave the player.
        if (ui.ended && ui.nextEpisode == null) { delay(800); onExit() }
    }

    fun handleKey(ev: KeyEvent): Boolean {
        val down = ev.type == KeyEventType.KeyDown
        val up = ev.type == KeyEventType.KeyUp
        when (ev.key) {
            Key.MediaPlayPause, Key.Spacebar -> { if (down) { controller.togglePlayPause(); poke() }; return true }
            Key.MediaPlay -> { if (down) controller.play(); return true }
            Key.MediaPause -> { if (down) controller.pause(); return true }
            Key.MediaFastForward -> { if (down) scrubBy(s.seekForwardSeconds * 1000L); return true }
            Key.MediaRewind -> { if (down) scrubBy(-s.seekBackSeconds * 1000L); return true }
            Key.MediaNext -> { if (down && ui.nextEpisode != null) controller.playNext(); return true }
            Key.Back, Key.Escape -> {
                if (up) when {
                    panel != Panel.None -> { panel = Panel.None; showControls() }
                    controlsVisible && !showUpNext && ui.error == null -> hideControls()
                    else -> { controller.stop(); onExit() }
                }
                return true
            }
        }
        if (showUpNext || ui.error != null) return false
        if (!controlsVisible && panel == Panel.None) {
            if (!down) return ev.key in setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)
            when (ev.key) {
                Key.DirectionLeft -> { repeatCount++; scrubBy(-s.seekBackSeconds * 1000L * accel(repeatCount)); return true }
                Key.DirectionRight -> { repeatCount++; scrubBy(s.seekForwardSeconds * 1000L * accel(repeatCount)); return true }
                Key.DirectionUp, Key.DirectionDown, Key.Menu, Key.Info -> { showControls(); return true }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (segment != null) return false // the focused Skip button handles it
                    showControls(); return true
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
    ) {
        val colors = Aurora.colors

        if (ui.loading || (es.isBuffering && !es.isEnded && ui.error == null)) {
            Box(Modifier.fillMaxSize()) { LoadingState() }
        }

        // ---- Controls ----
        AnimatedVisibility(controlsVisible && !showUpNext && ui.error == null, enter = fadeIn(tween(Aurora.motion.duration(200))), exit = fadeOut(tween(Aurora.motion.duration(300)))) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
                // Title
                Column(Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 40.dp, end = 64.dp)) {
                    val item = ui.item
                    if (item?.isEpisode == true) {
                        Text(item.seriesName ?: "", style = AuroraType.section, color = Color.White.copy(alpha = 0.8f))
                        Text(listOfNotNull(item.episodeLabel, item.name).joinToString(" · "), style = AuroraType.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(item?.name ?: "", style = AuroraType.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 64.dp, vertical = 40.dp)) {
                    SeekBar(
                        positionMs = scrubTarget ?: es.positionMs,
                        durationMs = es.durationMs,
                        bufferedMs = es.bufferedMs,
                        chapters = ui.chapters.map { it.startPositionTicks.ticksToMs() },
                        onScrub = { delta -> scrubBy(delta) },
                        onCommit = { scrubJob?.cancel(); scrubTarget?.let { controller.seekTo(it) }; scrubTarget = null },
                        seekStepMs = s.seekForwardSeconds * 1000L,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatTime(scrubTarget ?: es.positionMs), style = AuroraType.label, color = Color.White)
                        Spacer(Modifier.weight(1f))
                        Text("-" + formatTime((es.durationMs - (scrubTarget ?: es.positionMs)).coerceAtLeast(0)), style = AuroraType.label, color = Color.White.copy(alpha = 0.8f))
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IconAction(Icons.Filled.Replay10, "Back ${s.seekBackSeconds} seconds", { controller.seekBy(-s.seekBackSeconds * 1000L); poke() })
                        IconAction(if (es.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (es.isPlaying) "Pause" else "Play", { controller.togglePlayPause(); poke() }, size = 68.dp, focusRequester = playFocus)
                        IconAction(Icons.Filled.Forward30, "Forward ${s.seekForwardSeconds} seconds", { controller.seekBy(s.seekForwardSeconds * 1000L); poke() })
                        Spacer(Modifier.weight(1f))
                        if (ui.chapters.size > 1) IconAction(Icons.Filled.ViewList, "Chapters", { panel = Panel.Chapters })
                        if (ui.audioStreams.size > 1) IconAction(Icons.Filled.Audiotrack, "Audio", { panel = Panel.Audio })
                        IconAction(Icons.Filled.ClosedCaption, "Subtitles", { panel = Panel.Subtitles })
                        IconAction(Icons.Filled.Info, "Playback info", { panel = Panel.Info })
                        if (ui.nextEpisode != null) IconAction(Icons.Filled.SkipNext, "Next episode", { controller.playNext() })
                    }
                }
            }
        }

        // ---- Scrub-only bar when seeking with controls hidden ----
        if (!controlsVisible && scrubTarget != null) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))).padding(horizontal = 64.dp, vertical = 40.dp)) {
                Text(formatTime(scrubTarget!!), style = AuroraType.title, color = Color.White)
                Spacer(Modifier.height(10.dp))
                ProgressTrack(scrubTarget!!, es.durationMs, es.bufferedMs, focused = true)
            }
        }

        // ---- Skip intro / recap / credits ----
        if (segment != null && !showUpNext && ui.error == null) {
            val label = when (segment.type) { "Intro" -> "Skip Intro"; "Recap" -> "Skip Recap"; "Outro" -> if (ui.nextEpisode != null) "Next Episode" else "Skip Credits"; else -> "Skip" }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = if (controlsVisible) 200.dp else 64.dp)) {
                AuroraButton(label, {
                    if (segment.type == "Outro" && ui.nextEpisode != null) controller.playNext() else controller.skipSegment()
                }, primary = true, focusRequester = skipFocus)
            }
        }

        // ---- Up next ----
        if (showUpNext) {
            UpNextCard(ui.nextEpisode!!, controller, if (s.autoPlayNext) countdown else null, onExit = { controller.stop(); onExit() })
        }

        // ---- Error ----
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
                        AuroraButton("Exit", { controller.stop(); onExit() })
                    }
                }
            }
        }

        // ---- Side panels ----
        AnimatedVisibility(
            panel != Panel.None,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(Aurora.motion.duration(220))) { it } + fadeIn(),
            exit = slideOutHorizontally(tween(Aurora.motion.duration(180))) { it } + fadeOut(),
        ) {
            Column(
                Modifier.fillMaxHeight().width(440.dp).background(colors.surfaceGlass.copy(alpha = 0.94f)).padding(28.dp)
            ) {
                when (panel) {
                    Panel.Audio -> {
                        PanelTitle("Audio")
                        OptionList(
                            options = ui.audioStreams.map { it.index to TrackSelector.label(it) },
                            selected = ui.audioIndex,
                            firstFocus = panelFocus,
                            onSelect = { idx -> idx?.let { controller.selectAudio(it) }; panel = Panel.None },
                        )
                    }
                    Panel.Subtitles -> {
                        PanelTitle("Subtitles")
                        if (controller.supportsSubtitleDelay && ui.subtitleIndex != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AuroraButton("−0.1s", { controller.adjustSubtitleDelay(-100) })
                                Text("Delay ${ui.subtitleDelayMs} ms", style = AuroraType.label, color = colors.onBackground)
                                AuroraButton("+0.1s", { controller.adjustSubtitleDelay(100) })
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AuroraButton("A−", { controller.setSubtitleScale(ui.subtitleScale - 0.1f) })
                            Text("Size ${(ui.subtitleScale * 100).toInt()}%", style = AuroraType.label, color = colors.onBackground)
                            AuroraButton("A+", { controller.setSubtitleScale(ui.subtitleScale + 0.1f) })
                        }
                        Spacer(Modifier.height(12.dp))
                        OptionList(
                            options = listOf<Pair<Int?, String>>(null to "Off") + ui.subtitleStreams.map { it.index to TrackSelector.label(it) },
                            selected = ui.subtitleIndex,
                            firstFocus = panelFocus,
                            onSelect = { idx -> controller.selectSubtitle(idx); panel = Panel.None },
                        )
                    }
                    Panel.Chapters -> {
                        PanelTitle("Chapters")
                        OptionList(
                            options = ui.chapters.mapIndexed { i, ch -> i to "${formatTime(ch.startPositionTicks.ticksToMs())}  ${ch.name ?: "Chapter ${i + 1}"}" },
                            selected = ui.chapters.indexOfLast { it.startPositionTicks.ticksToMs() <= es.positionMs },
                            firstFocus = panelFocus,
                            onSelect = { i -> i?.let { controller.seekTo(ui.chapters[it].startPositionTicks.ticksToMs()) }; panel = Panel.None },
                        )
                    }
                    Panel.Info -> {
                        PanelTitle("Playback Info")
                        InfoPanel(controller, panelFocus)
                    }
                    Panel.None -> Unit
                }
            }
        }
    }
}

private fun accel(repeat: Int): Long = when {
    repeat < 4 -> 1
    repeat < 10 -> 2
    repeat < 20 -> 4
    else -> 8
}

@Composable
private fun PanelTitle(text: String) {
    Text(text, style = AuroraType.title, color = Aurora.colors.onBackground)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun <T> OptionList(options: List<Pair<T, String>>, selected: T?, firstFocus: FocusRequester, onSelect: (T?) -> Unit) {
    val colors = Aurora.colors
    if (options.isEmpty()) {
        Text("None available", style = AuroraType.body, color = colors.onSurfaceMuted)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        itemsIndexed(options) { i, (value, label) ->
            FocusSurface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(10.dp),
                ringWidth = 0.dp,
                scaleOnFocus = false,
                modifier = Modifier.fillMaxWidth().then(if ((selected == null && i == 0) || value == selected) Modifier.focusRequester(firstFocus) else Modifier),
            ) { focused ->
                Row(
                    Modifier.fillMaxWidth().background(if (focused) colors.focusFill else Color.Transparent).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(24.dp)) {
                        if (value == selected) Icon(Icons.Filled.Check, null, tint = if (focused) colors.background else colors.onBackground)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(label, style = AuroraType.bodyStrong, color = if (focused) colors.background else colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun InfoPanel(controller: PlayerController, focus: FocusRequester) {
    val ui by controller.state.collectAsState()
    val colors = Aurora.colors
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }
    val plan = ui.plan
    val lines = buildList {
        add("Engine" to controller.engineName)
        plan?.let { p ->
            add("Play method" to p.method.label)
            if (p.method != PlayMethod.DirectPlay && p.transcodeReasons.isNotEmpty()) add("Reason" to p.transcodeReasons.joinToString(", ") { r -> r.replace(Regex("([a-z])([A-Z])"), "$1 $2") })
            add("Container" to (p.mediaSource.container?.uppercase() ?: "—"))
            p.mediaSource.bitrate?.let { add("Source bitrate" to "${it / 1_000_000} Mbps") }
            p.mediaSource.videoStream?.let { v ->
                add("Video" to listOfNotNull(v.codec?.uppercase(), v.profile, v.width?.let { w -> "${w}x${v.height}" }, v.bitDepth?.let { b -> "$b-bit" }, v.videoRangeType).joinToString(" · "))
            }
            p.audioStreams.firstOrNull { it.index == ui.audioIndex }?.let { a -> add("Audio" to (a.displayTitle ?: a.codec.orEmpty())) }
            p.subtitleStreams.firstOrNull { it.index == ui.subtitleIndex }?.let { st ->
                add("Subtitles" to "${st.displayTitle ?: st.codec} (${st.deliveryMethod ?: "?"})")
            }
        }
        @Suppress("UNUSED_EXPRESSION") tick
        addAll(controller.engineStats())
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(lines) { (k, v) ->
            Column {
                Text(k, style = AuroraType.caption, color = colors.onSurfaceFaint)
                Text(v, style = AuroraType.body, color = colors.onBackground)
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.size(1.dp).focusRequester(focus).focusable())
        }
    }
}

@Composable
private fun UpNextCard(next: com.auroratv.jellyfin.model.BaseItemDto, controller: PlayerController, countdown: Int?, onExit: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))) {
        Column(Modifier.align(Alignment.BottomEnd).padding(64.dp).width(520.dp)) {
            Text("Up Next", style = AuroraType.caption, color = Color.White.copy(alpha = 0.7f))
            Text(listOfNotNull(next.episodeLabel, next.name).joinToString(" · "), style = AuroraType.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(next.overview ?: "", style = AuroraType.body, color = Color.White.copy(alpha = 0.75f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AuroraButton(if (countdown != null) "Play in $countdown" else "Play Next", { controller.playNext() }, icon = Icons.Filled.PlayArrow, primary = true, focusRequester = focus)
                AuroraButton("Back to Browse", onExit)
            }
        }
    }
}

/**
 * Focusable seek bar. Left/Right scrub in [seekStepMs] steps (held keys accelerate via the caller);
 * Enter commits immediately. Grows when focused, like tvOS.
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chapters: List<Long>,
    onScrub: (Long) -> Unit,
    onCommit: () -> Unit,
    seekStepMs: Long,
) {
    var focused by remember { mutableStateOf(false) }
    var held by remember { mutableIntStateOf(0) }
    Box(
        Modifier.fillMaxWidth().height(28.dp)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (!focused) return@onPreviewKeyEvent false
                when {
                    ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft -> { held++; onScrub(-seekStepMs / 3 * accel(held)); true }
                    ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight -> { held++; onScrub(seekStepMs / 3 * accel(held)); true }
                    ev.type == KeyEventType.KeyUp && (ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight) -> { held = 0; true }
                    ev.type == KeyEventType.KeyUp && (ev.key == Key.DirectionCenter || ev.key == Key.Enter) -> { onCommit(); true }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        ProgressTrack(positionMs, durationMs, bufferedMs, focused, chapters)
    }
}

@Composable
private fun ProgressTrack(positionMs: Long, durationMs: Long, bufferedMs: Long, focused: Boolean, chapters: List<Long> = emptyList()) {
    val height by animateDpAsState(if (focused) 10.dp else 6.dp, tween(Aurora.motion.duration(150)))
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterStart) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f)))
        Box(Modifier.fillMaxWidth(buffered).height(height).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.35f)))
        Box(Modifier.fillMaxWidth(fraction).height(height).clip(RoundedCornerShape(50)).background(Color.White))
        if (durationMs > 0) chapters.filter { it > 0 }.forEach { c ->
            Box(Modifier.offset(x = w * (c.toFloat() / durationMs)).size(width = 2.dp, height = height).background(Color.Black.copy(alpha = 0.5f)))
        }
        if (focused) Box(Modifier.offset(x = w * fraction - 9.dp).size(18.dp).clip(CircleShape).background(Color.White))
    }
}
