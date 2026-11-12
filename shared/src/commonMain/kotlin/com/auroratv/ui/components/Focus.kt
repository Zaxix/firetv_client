package com.auroratv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    ringWidth: Dp = 3.dp,
    scaleOnFocus: Boolean = true,
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
                shadowElevation = if (active) 24f else 0f
                this.shape = shape
                clip = false
            }
            .then(requesterModifier)
            .onFocusChanged {
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
                onClick = onClick,
            )
            .border(if (active) ringWidth else 0.dp, if (active) colors.focusRing else Color.Transparent, shape)
            .clip(shape)
    ) {
        content(active)
    }
}

/**
 * Moves focus with arrow keys / D-pad when no child consumed the key. Android does this natively;
 * desktop Compose doesn't, so the same remote-style navigation is applied everywhere.
 */
@Composable
fun Modifier.arrowKeyNavigation(): Modifier {
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
