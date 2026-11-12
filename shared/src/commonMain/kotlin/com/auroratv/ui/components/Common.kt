package com.auroratv.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auroratv.jellyfin.ImageUrls
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType

/** Horizontal padding used by every screen so rails, headers and hero text align. */
val ScreenPadding = 56.dp

/** Pill button used for primary actions (Play, Resume, Favorite...). */
@Composable
fun AuroraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    focusKey: String? = null,
) {
    val colors = Aurora.colors
    FocusSurface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        focusKey = focusKey,
        ringWidth = 0.dp,
        modifier = modifier.then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) { focused ->
        val bg = when {
            focused -> colors.focusFill
            primary -> colors.onBackground.copy(alpha = 0.18f)
            else -> colors.onBackground.copy(alpha = 0.10f)
        }
        val fg = if (focused) colors.background else colors.onBackground
        Row(
            Modifier.background(bg).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
            Text(text, style = AuroraType.label, color = fg)
        }
    }
}

/** Round icon-only button (player controls, compact actions). */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    focusRequester: FocusRequester? = null,
) {
    val colors = Aurora.colors
    FocusSurface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        ringWidth = 0.dp,
        modifier = modifier.size(size).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    ) { focused ->
        Box(
            Modifier.fillMaxSize().background(if (focused) colors.focusFill else colors.onBackground.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription, tint = if (focused) colors.background else colors.onBackground, modifier = Modifier.size(size * 0.48f))
        }
    }
}

/** Selectable chip for filters, sorts and seasons. */
@Composable
fun AuroraChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, focusKey: String? = null) {
    val colors = Aurora.colors
    FocusSurface(onClick = onClick, shape = RoundedCornerShape(50), ringWidth = 0.dp, focusKey = focusKey, modifier = modifier) { focused ->
        val bg = when {
            focused -> colors.focusFill
            selected -> colors.onBackground.copy(alpha = 0.22f)
            else -> Color.Transparent
        }
        Text(
            text,
            style = AuroraType.label,
            color = if (focused) colors.background else if (selected) colors.onBackground else colors.onSurfaceMuted,
            modifier = Modifier.background(bg).padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/**
 * A titled horizontal rail of cards. LazyRow composes only visible cards; focusRestorer returns focus
 * to the last-focused card when moving vertically back into the rail.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ContentRail(
    title: String,
    items: List<BaseItemDto>,
    images: ImageUrls,
    style: CardStyle,
    railKey: String,
    onItemClick: (BaseItemDto) -> Unit,
    modifier: Modifier = Modifier,
    onItemFocused: ((BaseItemDto) -> Unit)? = null,
    subtitleFor: ((BaseItemDto) -> String?)? = null,
) {
    val colors = Aurora.colors
    Column(modifier.fillMaxWidth()) {
        Text(title, style = AuroraType.section, color = colors.onBackground, modifier = Modifier.padding(start = ScreenPadding, bottom = 14.dp))
        val state = rememberLazyListState()
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.focusRestorer(),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { _, item ->
                MediaCard(
                    item = item,
                    images = images,
                    style = style,
                    onClick = { onItemClick(item) },
                    focusKey = "$railKey/${item.id}",
                    onFocused = onItemFocused?.let { { it(item) } },
                    subtitle = subtitleFor?.invoke(item),
                )
            }
        }
    }
}

/** Shimmering placeholder blocks shown instead of a blank screen while content loads. */
@Composable
fun SkeletonRail(style: CardStyle, count: Int = 7, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    val alpha = shimmerAlpha()
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.padding(start = ScreenPadding, bottom = 14.dp).width(180.dp).height(22.dp).clip(RoundedCornerShape(6.dp)).background(colors.skeleton))
        Row(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            repeat(count) {
                Box(
                    Modifier.width(style.width).aspectRatio(style.aspect).clip(RoundedCornerShape(12.dp))
                        .graphicsLayer { this.alpha = alpha }.background(colors.skeletonHighlight)
                )
            }
        }
    }
}

@Composable
fun shimmerAlpha(): Float {
    if (!Aurora.motion.enabled) return 0.8f
    val transition = rememberInfiniteTransition()
    val a by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    return a
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Aurora.colors.onBackground, strokeWidth = 3.dp, modifier = Modifier.size(42.dp))
    }
}

/** Friendly error with a focusable Retry button. */
@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier, retryFocus: FocusRequester? = null) {
    val colors = Aurora.colors
    Column(
        modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Something's not right", style = AuroraType.title, color = colors.onBackground)
        Spacer(Modifier.height(12.dp))
        Text(message, style = AuroraType.body, color = colors.onSurfaceMuted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp))
        if (onRetry != null) {
            Spacer(Modifier.height(28.dp))
            AuroraButton("Try again", onRetry, primary = true, focusRequester = retryFocus)
        }
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    Column(modifier.fillMaxWidth().padding(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = AuroraType.section, color = colors.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(message, style = AuroraType.body, color = colors.onSurfaceMuted, textAlign = TextAlign.Center)
    }
}

/** Formats milliseconds as H:MM:SS or M:SS. */
fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    fun two(v: Long) = if (v < 10) "0$v" else "$v"
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

/** "1h 52m" style runtime. */
fun formatRuntime(ms: Long?): String? {
    ms ?: return null
    val minutes = ms / 60_000
    if (minutes <= 0) return null
    val h = minutes / 60; val m = minutes % 60
    return when { h == 0L -> "${m}m"; m == 0L -> "${h}h"; else -> "${h}h ${m}m" }
}

fun formatRating(r: Double?): String? = r?.let { val t = (it * 10).toInt(); "${t / 10}.${t % 10}" }
