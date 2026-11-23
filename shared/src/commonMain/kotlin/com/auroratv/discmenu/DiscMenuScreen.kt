package com.auroratv.discmenu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.auroratv.core.Log
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.ui.AppContainer
import com.auroratv.ui.LocalSession
import com.auroratv.ui.nav.LocalEntry
import com.auroratv.ui.nav.LocalNavigator
import com.auroratv.ui.nav.Route
import kotlinx.coroutines.launch

private const val TAG = "DiscMenu"
/** A frame that takes longer than this doesn't push the menu's clock further than this (a stall or a pause must not skip animations). */
private const val MAX_FRAME_STEP_MS = 100L
private const val TOAST_MS = 3_500L
/** How far ahead sparkle frames are decoded. */
private const val SPARKLE_LOOKAHEAD_FRAMES = 6
/** Engine choice, not a measured value: how quickly the Play Movie letter's first picture fades in over the loop. */
private const val LETTER_FADE_IN_MS = 150f

/** Remembered while the film page's entry stays on the back stack: coming back from playback skips the intro. */
private class DiscMenuVisit { var hasPlayed = false }

private sealed interface MenuLoad {
    data object Busy : MenuLoad
    data class Failed(val message: String) : MenuLoad
    class Ready(val server: ServerData, val installed: InstalledPack, val assets: DiscMenuAssets, val pack: DiscMenuPackDef) : MenuLoad
}

/** What the server says about the film, as the menu's actions need it. */
internal class ServerData(val item: BaseItemDto) {
    private val chapters = item.chapters
    private val runtimeMs = item.runtimeMs
    private val streams = item.mediaSources.firstOrNull()?.mediaStreams ?: item.mediaStreams
    val chapterCount get() = chapters.size

    fun chapterStartMs(number: Int): Long? {
        val c = chapters.getOrNull(number - 1) ?: return null
        val ms = c.startPositionTicks / 10_000L
        return if (runtimeMs != null && ms >= runtimeMs) null else ms
    }

    fun isAvailable(action: String): Boolean = when {
        action == "chapter:last" -> chapters.isNotEmpty()
        action.startsWith("chapter:") -> action.removePrefix("chapter:").toIntOrNull()?.let { chapterStartMs(it) != null } ?: false
        action.startsWith("subtitle:") -> DiscSubtitles.isAvailable(streams, action.removePrefix("subtitle:"))
        else -> true
    }
}

@Composable
fun DiscMenuScreen(container: AppContainer, route: Route.DiscMenu) {
    val navigator = LocalNavigator.current
    val session = LocalSession.current
    val entry = LocalEntry.current
    val visit = remember(entry) { entry?.model("discmenu.visit") { DiscMenuVisit() } ?: DiscMenuVisit() }

    val load by produceState<MenuLoad>(MenuLoad.Busy, route.itemId) {
        value = runCatching {
            val platform = container.platform.discMenus ?: return@runCatching MenuLoad.Failed("This device can't show disc menus.")
            val item = runCatching { session.api.item(route.itemId) }.getOrNull() ?: route.preview
                ?: return@runCatching MenuLoad.Failed("Couldn't load the film from the server.")
            val installed = container.discMenus.packFor(item)
                ?: return@runCatching MenuLoad.Failed("No disc menu pack for this film in ${platform.packsLocation}")
            val assets = platform.assets(installed.folder)
            val missing = PackCheck.missingRequired(installed.def, assets)
            if (missing.isNotEmpty()) return@runCatching MenuLoad.Failed("The menu pack '${installed.def.id}' is incomplete. Missing: ${missing.take(6).joinToString()}${if (missing.size > 6) " and ${missing.size - 6} more" else ""}")
            val (pack, dropped) = PackCheck.withoutMissingOptional(installed.def, assets)
            if (dropped.isNotEmpty()) Log.w(TAG, "optional pack files missing, skipped: $dropped")
            MenuLoad.Ready(ServerData(item), installed, assets, pack)
        }.getOrElse { MenuLoad.Failed("The disc menu couldn't be opened: ${it.message}") }
    }

    when (val l = load) {
        MenuLoad.Busy -> Box(Modifier.fillMaxSize().background(Color.Black))
        is MenuLoad.Failed -> MenuNotice(l.message) { navigator.pop() }
        is MenuLoad.Ready -> DiscMenuStage(container, route, l, visit)
    }
}

@Composable
private fun MenuNotice(message: String, onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable().onPreviewKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyUp) onBack()
            true
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(message + "\n\nPress Back to return.", color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(64.dp))
    }
}

private class Toast(val text: String, val until: Long)

@Composable
private fun DiscMenuStage(container: AppContainer, route: Route.DiscMenu, load: MenuLoad.Ready, visit: DiscMenuVisit) {
    val navigator = LocalNavigator.current
    val platform = checkNotNull(container.platform.discMenus)
    val pack = load.pack
    val server = load.server
    val scope = rememberCoroutineScope()

    val logic = remember { MenuLogic(pack, server::isAvailable) }
    val media = remember { platform.createMedia(load.installed.folder, pack) }
    val sprites = remember { SpriteBank(load.assets, pack.sparkles) }
    val credits = remember { pack.credits?.let { CreditsLayout.build(server.item.people, it) }.orEmpty() }
    val art = remember {
        PageArtStore(pack, load.assets, plateFor = { id -> pack.credits?.pages?.get(id)?.takeIf { credits.containsKey(id) }?.cleanPlate ?: pack.pages.getValue(id).plate })
    }
    val textMeasurer = rememberTextMeasurer(cacheSize = 24)
    val canvasDef = pack.canvas

    val clock = remember { mutableLongStateOf(0L) }
    var uiState by remember { mutableStateOf<MenuState>(MenuState.Idle) }
    var toast by remember { mutableStateOf<Toast?>(null) }
    var subtitleChoice by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var warnedChapterCount by remember { mutableStateOf(false) }
    val lastSparkle = remember { HashMap<String, androidx.compose.ui.graphics.ImageBitmap>() }
    val focus = remember { FocusRequester() }
    val preloadedFor = remember { arrayOfNulls<String>(1) }

    fun now() = clock.longValue
    fun showToast(text: String) { toast = Toast(text, now() + TOAST_MS) }

    fun playMovie() {
        visit.hasPlayed = true
        navigator.push(Route.Player(server.item.id, startFromBeginning = true, preview = server.item, subtitleCode = subtitleChoice))
    }

    fun playChapter(number: Int) {
        val ms = server.chapterStartMs(number)
        if (ms == null) { showToast("Chapter $number isn't available on this server"); return }
        visit.hasPlayed = true
        navigator.push(Route.Player(server.item.id, startFromBeginning = false, preview = server.item, startAtMs = ms, subtitleCode = subtitleChoice))
    }

    fun sync() {
        for (e in logic.drainEffects()) when (e) {
            MenuEffect.PlayMovie -> playMovie()
            is MenuEffect.PlayChapter -> playChapter(e.number)
            MenuEffect.PlayLastChapter -> playChapter(server.chapterCount)
            is MenuEffect.Gate -> showToast(e.message)
            is MenuEffect.SelectSubtitle -> { subtitleChoice = e.code; showToast(DiscSubtitles.label(e.code)) }
            MenuEffect.StartTransition -> {
                (logic.state as? MenuState.Transition)?.target?.let { target -> scope.launch { art.ensure(target) } }
                media.apply(e)
            }
            MenuEffect.PrepareTransition -> {
                // the page the button leads to is decoded while the transition video plays
                (logic.state as? MenuState.Main)?.activation?.buttonId?.let { id ->
                    pack.main.buttons.firstOrNull { it.id == id }?.action?.removePrefix("goto:")?.let { target -> scope.launch { art.ensure(target) } }
                }
                media.apply(e)
            }
            else -> media.apply(e)
        }
        val s = logic.state
        if (s is MenuState.Page) {
            if (preloadedFor[0] != s.id) {
                preloadedFor[0] = s.id
                val wanted = art.neighbours(s.id) + s.id
                scope.launch { art.ensure(s.id, keep = wanted); wanted.forEach { art.ensure(it, keep = wanted) } }
            }
            if (!warnedChapterCount && pack.sequences["scenes"]?.contains(s.id) == true) {
                warnedChapterCount = true
                val slots = pack.pages.values.flatMap { it.hotspots }.mapNotNull { it.action.removePrefix("chapter:").toIntOrNull()?.takeIf { _ -> it.action.startsWith("chapter:") } }.maxOrNull() ?: 0
                if (server.chapterCount != slots) showToast("This copy lists ${server.chapterCount} chapters; the disc menu has $slots scenes")
            }
        }
        uiState = s
    }

    DisposableEffect(Unit) {
        media.listener = object : DiscMenuMediaListener {
            override fun onIntroFirstPicture() { logic.introStarted(now()); sync() }
            override fun onIntroFailed() { logic.introFailed(now()); sync() }
            override fun onMediaProblem(what: String) { Log.w(TAG, "media problem: $what"); showToast("Menu media problem: $what") }
        }
        onDispose {
            // Everything the menu started stops here: players, decoded images, sparkle data.
            media.listener = null
            logic.shutdown()
            logic.drainEffects().forEach { media.apply(it) }
            media.release()
            sprites.clear()
            art.clear()
            lastSparkle.clear()
        }
    }

    LaunchedEffect(Unit) {
        runCatching { sprites.load() }.onFailure {
            Log.e(TAG, "sparkles unreadable", it)
            showToast("Sparkle frames couldn't be read: ${it.message}")
        }
        // The first page the menu can open is decoded before it is needed; the rest follow the player's choices.
        ready = true
    }

    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        runCatching { focus.requestFocus() }
        logic.start(0L, withIntro = !visit.hasPlayed)
        sync()
        var last = 0L
        var virtualNow = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { ns ->
                val dt = if (last == 0L) 0L else ((ns - last) / 1_000_000L).coerceIn(0L, MAX_FRAME_STEP_MS)
                last = ns
                virtualNow += dt
                clock.longValue = virtualNow
                logic.update(virtualNow)
                sync()
                toast?.let { if (virtualNow >= it.until) toast = null }
            }
        }
    }

    // Sparkle frames a few steps ahead of the clock, decoded off the main thread.
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val current = sparkleAt(pack, logic.state, sprites, clock.longValue) ?: continue
            for (k in 0 until SPARKLE_LOOKAHEAD_FRAMES) sprites.ensure(current.first, current.second + k)
        }
    }

    fun press(k: MenuKey) {
        val stay = logic.onKey(k, now())
        sync()
        if (!stay) navigator.pop()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                val mapped = mapKey(ev.key, ev.type)
                if (mapped != null) { press(mapped); true }
                else ev.key in HANDLED_KEYS
            }
            .pointerInput(logic) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val at = MenuGeometry.windowToCanvas(change.position.x, change.position.y, size.width.toFloat(), size.height.toFloat(), canvasDef) ?: continue
                        when (event.type) {
                            PointerEventType.Move -> { logic.hover(at.first, at.second, now()); sync() }
                            PointerEventType.Release -> {
                                val s = logic.state
                                if (s is MenuState.Intro || s is MenuState.Transition) press(MenuKey.Select)
                                else { logic.click(at.first, at.second, now()); sync() }
                            }
                            else -> Unit
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The videos keep the canvas's shape, centred; the bars around them stay black.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(canvasDef.w.toFloat() / canvasDef.h).clipToBounds()) {
                media.LoopSurface(Modifier.fillMaxSize())
                media.EffectSurface(Modifier.fillMaxSize().graphicsLayer { alpha = effectAlpha(uiState, clock.longValue, pack, media.effectVisible) })
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            clock.longValue
            art.version
            val fit = MenuGeometry.fit(size.width, size.height, canvasDef)
            withTransform({
                translate(fit.offsetX, fit.offsetY)
                scale(fit.scale, fit.scale, pivot = Offset.Zero)
            }) {
                drawMenu(pack, uiState, clock.longValue, sprites, art, lastSparkle, subtitleChoice, credits, textMeasurer)
            }
        }
        toast?.let { t ->
            Box(Modifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.BottomCenter) {
                Text(
                    t.text, color = Color.White, fontSize = 20.sp,
                    modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

private val HANDLED_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
    Key.Back, Key.Escape, Key.PageUp, Key.PageDown, Key.MediaNext, Key.MediaPrevious, Key.MediaFastForward, Key.MediaRewind, Key.ButtonA, Key.ButtonB,
)

/** Arrows act on press (and repeat while held); Select and Back act on release, like the rest of the app. */
private fun mapKey(key: Key, type: KeyEventType): MenuKey? = when (type) {
    KeyEventType.KeyDown -> when (key) {
        Key.DirectionUp -> MenuKey.Up
        Key.DirectionDown -> MenuKey.Down
        Key.DirectionLeft -> MenuKey.Left
        Key.DirectionRight -> MenuKey.Right
        Key.PageDown, Key.MediaNext, Key.MediaFastForward -> MenuKey.NextPage
        Key.PageUp, Key.MediaPrevious, Key.MediaRewind -> MenuKey.PreviousPage
        else -> null
    }
    KeyEventType.KeyUp -> when (key) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA -> MenuKey.Select
        Key.Back, Key.Escape, Key.ButtonB -> MenuKey.Back
        else -> null
    }
    else -> null
}

/** How visible the shared effect surface (intro, transition, letter) is right now. */
private fun effectAlpha(state: MenuState, now: Long, pack: DiscMenuPackDef, surfaceVisible: Boolean): Float {
    if (!surfaceVisible) return 0f
    return when (state) {
        is MenuState.Intro, is MenuState.Transition -> 1f
        is MenuState.Letter -> ((now - state.startedAt) / LETTER_FADE_IN_MS).coerceIn(0f, 1f)
        is MenuState.Main -> {
            val until = state.introFadeUntil
            val fade = pack.video.intro?.crossfadeMs ?: 0L
            if (until == null || fade <= 0) 0f else ((until - now).toFloat() / fade).coerceIn(0f, 1f)
        }
        else -> 0f
    }
}

/** The sparkle sequence and frame showing on the main menu at [now], or null when none. */
private fun sparkleAt(pack: DiscMenuPackDef, state: MenuState, bank: SpriteBank, now: Long): Pair<String, Int>? {
    val s = state as? MenuState.Main ?: return null
    val button = pack.main.buttons.firstOrNull { it.id == s.selected } ?: return null
    val act = s.activation
    val activatedName = if (act != null) pack.main.buttons.firstOrNull { it.id == act.buttonId }?.activated else null
    if (act != null && activatedName != null) {
        val seq = bank.sequence(activatedName) ?: return null
        return activatedName to MenuGeometry.holdIndex(now - act.at, seq.fps, seq.frames)
    }
    val name = button.selected ?: return null
    val seq = bank.sequence(name) ?: return null
    return name to MenuGeometry.loopIndex(now - s.selectedAt, seq.fps, seq.frames)
}

private fun parseColor(hex: String, fallback: Color): Color = runCatching {
    val h = hex.removePrefix("#")
    Color(0xFF000000L or h.toLong(16))
}.getOrDefault(fallback)

private fun DrawScope.drawMenu(
    pack: DiscMenuPackDef,
    state: MenuState,
    now: Long,
    sprites: SpriteBank,
    art: PageArtStore,
    lastSparkle: MutableMap<String, androidx.compose.ui.graphics.ImageBitmap>,
    subtitleChoice: String?,
    credits: Map<String, List<String>>,
    measurer: TextMeasurer,
) {
    when (state) {
        is MenuState.Main -> drawSparkle(pack, state, now, sprites, lastSparkle)
        is MenuState.Page -> drawPage(pack, state, art, subtitleChoice, credits, measurer)
        else -> Unit
    }
}

private fun DrawScope.drawSparkle(pack: DiscMenuPackDef, state: MenuState.Main, now: Long, sprites: SpriteBank, last: MutableMap<String, androidx.compose.ui.graphics.ImageBitmap>) {
    val (name, frame) = sparkleAt(pack, state, sprites, now) ?: return
    val seq = sprites.sequence(name) ?: return
    val image = sprites.peek(name, frame)?.also { last[name] = it } ?: last[name] ?: return
    // Additive: the sparkle frames are light on black, laid over the video.
    drawImage(image, topLeft = Offset(seq.x.toFloat(), seq.y.toFloat()), blendMode = BlendMode.Plus)
}

private fun DrawScope.drawPage(pack: DiscMenuPackDef, state: MenuState.Page, art: PageArtStore, subtitleChoice: String?, credits: Map<String, List<String>>, measurer: TextMeasurer) {
    val def = pack.pages[state.id] ?: return
    val a = art.peek(state.id)
    drawRect(Color.Black, size = Size(pack.canvas.w.toFloat(), pack.canvas.h.toFloat()))
    a?.plate?.let { drawImage(it, dstSize = IntSize(pack.canvas.w, pack.canvas.h), filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium) }
    val creditsDef = pack.credits
    val lines = credits[state.id]
    if (creditsDef != null && lines != null) drawCredits(creditsDef, lines, measurer)
    val selected = def.hotspots.firstOrNull { it.id == state.selected } ?: return
    val activating = state.activation?.hotspotId == selected.id
    val kind = if (activating && selected.activated != null) "act" else "sel"
    val image = a?.highlights?.get("${selected.id}/$kind")
    val spriteRect = selected.spriteRect?.takeIf { it.size == 4 }
    if (image != null && spriteRect != null) {
        drawImage(image, topLeft = Offset(spriteRect[0].toFloat(), spriteRect[1].toFloat()))
    } else {
        // No observed highlight picture for this item: a plain outline, in the measured (activated) or approximate (selected) colour.
        val style = if (activating) pack.activatedOutline else pack.selectedOutline
        outline(selected.rect, parseColor(style.color, Color(0xFFA55A3E)), style.thicknessPx.toFloat())
    }
    // The chosen subtitle on the Languages page keeps an outline so the choice stays visible.
    if (subtitleChoice != null) {
        def.hotspots.firstOrNull { it.action == "subtitle:$subtitleChoice" && it.id != selected.id }?.let {
            outline(it.rect, parseColor(pack.activatedOutline.color, Color(0xFF5E5A42)), pack.activatedOutline.thicknessPx / 2f)
        }
    }
}

private fun DrawScope.outline(rect: List<Int>, color: Color, thickness: Float) {
    if (rect.size != 4) return
    drawRect(
        color, topLeft = Offset(rect[0] + thickness / 2, rect[1] + thickness / 2),
        size = Size(rect[2] - rect[0] - thickness, rect[3] - rect[1] - thickness), style = Stroke(thickness),
    )
}

/** The server's cast or crew, in the colour and line pitch measured from the baked text. */
private fun DrawScope.drawCredits(def: CreditsDef, lines: List<String>, measurer: TextMeasurer) {
    val style = TextStyle(
        color = parseColor(def.color, Color(0xFF74594A)),
        fontSize = def.fontSizePx.toFloat().toSp(),
        fontFamily = FontFamily.SansSerif,
        textGeometricTransform = TextGeometricTransform(scaleX = def.textScaleX),
    )
    val shown = lines.ifEmpty { listOf("No more listed on this server") }
    // The horizontal squeeze is applied when painting, so the width limit is the unsqueezed one.
    val limit = (def.maxWidthPx / def.textScaleX).toInt()
    shown.forEachIndexed { i, line ->
        val layout = measurer.measure(line, style, overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = limit))
        drawText(layout, topLeft = Offset(def.textLeftPx.toFloat(), def.firstLineCenterYPx + i * def.lineHeightPx - layout.size.height / 2f))
    }
}
