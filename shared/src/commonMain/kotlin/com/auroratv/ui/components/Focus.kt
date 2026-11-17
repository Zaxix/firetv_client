package com.auroratv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import com.auroratv.ui.nav.LocalEntry
import com.auroratv.ui.theme.Aurora

/**
 * The tvOS-style focus treatment shared by every card: lift (scale), soft shadow and a crisp ring.
 * Works identically for D-pad focus, keyboard focus and mouse hover. Scaling uses graphicsLayer only,
 * so focus changes never trigger relayout of the surrounding rail.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    focusKey: String? = null,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    ringWidth: Dp = 0.dp,
    scaleOnFocus: Boolean = true,
    /** tvOS-style specular highlight across artwork when focused. */
    sheen: Boolean = scaleOnFocus,
    /** Colour of the light around the card when focused (e.g. from its poster); null = neutral shadow. */
    glow: Color? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val motion = Aurora.motion
    val colors = Aurora.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val active = focused || hovered
    val scale by animateFloatAsState(
        targetValue = if (active && scaleOnFocus) motion.focusScale else 1f,
        animationSpec = tween(motion.duration(180)),
    )
    val entry = LocalEntry.current
    val requesterModifier = if (focusKey != null && entry != null) {
        DisposableEffect(focusKey) { onDispose { entry.focus.forget(focusKey) } }
        Modifier.focusRequester(entry.focus.requester(focusKey))
    } else Modifier

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                // A modest shadow: big elevations are drawn blurred by the GPU every frame the card
                // animates, which low-end TV GPUs (Fire TV sticks) feel while scrolling.
                shadowElevation = if (active && scaleOnFocus) (if (glow != null) 30f else 14f) else 0f
                if (glow != null) { ambientShadowColor = glow; spotShadowColor = glow }
                this.shape = shape
                clip = false
            }
            .then(requesterModifier)
            .onFocusChanged {
                if (it.isFocused && !focused) com.auroratv.ui.sound.UiSounds.focusMoved()
                focused = it.isFocused
                if (it.isFocused) {
                    if (focusKey != null) entry?.focus?.lastKey = focusKey
                    onFocused?.invoke()
                }
            }
            .hoverable(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = onLongClick,
                onClick = { com.auroratv.ui.sound.UiSounds.selected(); onClick() },
            )
            .border(if (active) ringWidth else 0.dp, if (active) colors.focusRing else Color.Transparent, shape)
            .clip(shape)
    ) {
        content(active)
        if (sheen && active) {
            // Soft diagonal glare, like light catching a glass card on tvOS.
            Box(
                Modifier.matchParentSize().background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.22f),
                        0.45f to Color.White.copy(alpha = 0.04f),
                        1f to Color.Transparent,
                    )
                )
            )
        }
    }
}

/**
 * Moves focus with arrow keys / D-pad when no child consumed the key. Android does this natively;
 * desktop Compose doesn't, so the same remote-style navigation is applied everywhere.
 */
@Composable
fun Modifier.arrowKeyNavigation(): Modifier {
    if (platformHandlesDpadFocus) return this // Android's ComposeView already moves focus for D-pad keys
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    return this.onKeyEvent { ev ->
        if (ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent false
        val dir = when (ev.key) {
            androidx.compose.ui.input.key.Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
            androidx.compose.ui.input.key.Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
            androidx.compose.ui.input.key.Key.DirectionLeft -> androidx.compose.ui.focus.FocusDirection.Left
            androidx.compose.ui.input.key.Key.DirectionRight -> androidx.compose.ui.focus.FocusDirection.Right
            else -> return@onKeyEvent false
        }
        focusManager.moveFocus(dir)
        true
    }
}

/** True where the platform moves focus for D-pad/arrow keys itself (Android). */
internal expect val platformHandlesDpadFocus: Boolean

/**
 * Scrolls only as far as needed to show the focused element plus a small margin. Android TV's default
 * pivot behaviour scrolls the focused item to ~30% of the viewport, which cut off the top of Home.
 */
@OptIn(ExperimentalFoundationApi::class)
val MinimalBringIntoViewSpec = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = containerSize * 0.05f
        val trailing = offset + size
        return when {
            size >= containerSize - 2 * margin -> offset - margin // taller than the viewport: show its start
            offset < margin -> offset - margin
            trailing > containerSize - margin -> trailing - (containerSize - margin)
            else -> 0f
        }
    }
}

/**
 * Horizontal rows: keep the focused card inside the row's own content area ([start]…width−[end]),
 * not merely on screen. Rows span the full width underneath the sidebar, so the shared rule above
 * (5% from the edge) let the first card stop half under the sidebar when coming back from the end of
 * a row, instead of returning to its place. With the row's padding as the margin, the first card
 * always lands back exactly where it started (the row can't scroll past its start).
 */
@OptIn(ExperimentalFoundationApi::class)
@androidx.compose.runtime.Composable
fun RowScroll(start: Dp = ScreenStart, end: Dp = ScreenPadding, content: @androidx.compose.runtime.Composable () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val spec = remember(start, end, density) {
        val lead = with(density) { start.toPx() }
        val trail = with(density) { end.toPx() }
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val trailing = offset + size
                return when {
                    size >= containerSize - lead - trail -> offset - lead
                    offset < lead -> offset - lead
                    trailing > containerSize - trail -> trailing - (containerSize - trail)
                    else -> 0f
                }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * When focus enters this element (a row with its heading, the details page's hero, an episode with
 * its text), bring the whole element into view, not just the focused card or button. The scroll
 * container honours both requests at once, so the heading/hero is shown along with the focus.
 * Asked again shortly after, in case the focused child's own (smaller) request was handled last.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.revealOnFocus(): Modifier = composed {
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var inside by remember { mutableStateOf(false) }
    var top by remember { mutableStateOf(0f) }
    Modifier
        .then(if (UiTrace.on) Modifier.onGloballyPositioned { top = it.positionInRoot().y } else Modifier)
        .bringIntoViewRequester(requester)
        .onFocusChanged { f ->
            if (f.hasFocus && !inside) scope.launch {
                requester.bringIntoView()
                kotlinx.coroutines.delay(260)
                requester.bringIntoView()
                if (UiTrace.on) { kotlinx.coroutines.delay(400); com.auroratv.core.Log.i("AuroraLayout", "revealed top=${top.toInt()}") }
            }
            inside = f.hasFocus
        }
}

/** CI only (`--ez traceUi true`): log UI sounds and a few layout positions the tests check. */
object UiTrace {
    @kotlin.concurrent.Volatile var on: Boolean = false
}
