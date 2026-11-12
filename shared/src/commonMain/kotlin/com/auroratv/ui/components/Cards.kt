package com.auroratv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auroratv.jellyfin.ImageUrls
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType

enum class CardStyle(val width: Dp, val aspect: Float) {
    Poster(150.dp, 2f / 3f),
    Landscape(272.dp, 16f / 9f),
    Square(160.dp, 1f),
}

/** Pixel width to request from the server for a card (2x for crisp scaling on 1080p/4K panels). */
fun CardStyle.requestWidth() = (width.value * 2).toInt()

@Composable
fun MediaCard(
    item: BaseItemDto,
    images: ImageUrls,
    style: CardStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusKey: String? = null,
    showTitle: Boolean = true,
    subtitle: String? = null,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = Aurora.colors
    val artwork = when (style) {
        CardStyle.Poster, CardStyle.Square -> images.poster(item, style.requestWidth()) ?: images.landscape(item, style.requestWidth())
        CardStyle.Landscape -> images.landscape(item, style.requestWidth()) ?: images.poster(item, style.requestWidth())
    }
    Column(modifier.width(style.width)) {
        FocusSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            focusKey = focusKey,
            onFocused = onFocused,
            modifier = Modifier.fillMaxWidth().aspectRatio(style.aspect),
        ) {
            Box(Modifier.fillMaxSize()) {
                ArtworkImage(artwork, item.name, Modifier.fillMaxSize())
                if (artwork == null) {
                    Text(
                        item.name ?: "",
                        style = AuroraType.label,
                        color = colors.onSurfaceMuted,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp),
                        maxLines = 3,
                    )
                }
                CardBadges(item, Modifier.align(Alignment.TopEnd).padding(8.dp))
                item.progressFraction?.takeIf { !(item.userData?.played ?: false) }?.let { progress ->
                    ProgressStrip(progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 10.dp))
                }
            }
        }
        if (showTitle) {
            Spacer(Modifier.height(10.dp))
            Text(
                cardTitle(item, style),
                style = AuroraType.label,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = subtitle ?: cardSubtitle(item)
            if (sub != null) {
                Text(sub, style = AuroraType.caption, color = colors.onSurfaceFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

fun cardTitle(item: BaseItemDto, style: CardStyle): String =
    if (item.isEpisode && style == CardStyle.Poster) item.seriesName ?: item.name.orEmpty() else item.name.orEmpty()

fun cardSubtitle(item: BaseItemDto): String? = when {
    item.isEpisode -> listOfNotNull(item.seriesName, item.episodeLabel).joinToString(" · ").ifBlank { null }
    item.isSeries -> item.productionYear?.toString()
    item.isSeason -> item.seriesName
    else -> item.productionYear?.toString()
}

@Composable
fun ProgressStrip(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.Black.copy(alpha = 0.45f))
    ) {
        Box(Modifier.fillMaxWidth(progress).height(4.dp).background(Color.White))
    }
}

@Composable
fun CardBadges(item: BaseItemDto, modifier: Modifier = Modifier) {
    val played = item.userData?.played == true
    val unplayed = item.userData?.unplayedItemCount?.takeIf { it > 0 && (item.isSeries || item.isSeason) }
    val favorite = item.userData?.isFavorite == true
    if (!played && unplayed == null && !favorite) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (favorite) Badge { Icon(Icons.Filled.Favorite, "Favorite", Modifier.size(14.dp), tint = Color.White) }
        if (played) Badge { Icon(Icons.Filled.Check, "Watched", Modifier.size(14.dp), tint = Color.White) }
        else if (unplayed != null) Badge { Text(if (unplayed > 99) "99+" else unplayed.toString(), style = AuroraType.caption, color = Color.White) }
    }
}

@Composable
private fun Badge(content: @Composable () -> Unit) {
    Box(
        Modifier.height(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Gradient used to keep text legible over artwork, in both themes. */
@Composable
fun scrimBrush(startAlpha: Float = 0f, endAlpha: Float = 0.92f): Brush {
    val bg = Aurora.colors.background
    return Brush.verticalGradient(listOf(bg.copy(alpha = startAlpha), bg.copy(alpha = endAlpha)))
}
