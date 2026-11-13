package com.auroratv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import com.auroratv.jellyfin.Artwork
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.BlurHash

/** Server sizes are rounded up to 64 px steps so nearby layouts share cache entries. */
private fun bucket(px: Float): Int = ((px / 64f).toInt() + 1).coerceAtLeast(1) * 64

/**
 * Pixels to request for artwork drawn [width] wide, at this display's density and including the
 * focus zoom (so a focused card is never upscaled). Capped at 3840 (4K).
 */
@Composable
fun artworkPx(width: Dp, zoom: Float = 1.12f): Int = with(LocalDensity.current) { bucket(width.toPx() * zoom).coerceAtMost(3840) }

/** Pixels to request for full-screen artwork: the window's real width, capped at 4K. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun screenArtworkPx(): Int {
    val w = LocalWindowInfo.current.containerSize.width
    return if (w <= 0) 1920 else bucket(w.toFloat() - 1).coerceIn(1280, 3840)
}

/**
 * Network artwork with an instant BlurHash placeholder (decoded to a tiny 32x18 bitmap). Coil sizes
 * the decode to the layout, caches in memory and on disk, and cancels the request if the card leaves
 * the screen before it loads.
 */
@Composable
fun ArtworkImage(
    artwork: Artwork?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    showPlaceholder: Boolean = true,
    colorFilter: androidx.compose.ui.graphics.ColorFilter? = null,
    /** Extra request headers, for the few image endpoints that need the access token. */
    headers: Map<String, String> = emptyMap(),
    crossfade: Boolean = true,
) {
    val colors = Aurora.colors
    val placeholder = remember(artwork?.blurHash) {
        artwork?.blurHash?.let { BlurHash.decode(it) }?.let { BitmapPainter(it) }
    }
    Box(modifier.background(if (showPlaceholder) colors.skeleton else androidx.compose.ui.graphics.Color.Transparent)) {
        if (placeholder != null && showPlaceholder) {
            Image(placeholder, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (artwork != null) {
            val context = LocalPlatformContext.current
            val fade = if (crossfade) Aurora.motion.duration(220) else 0
            val request = remember(artwork.url, fade, headers) {
                // The server already scaled the image to the size we need (see artworkPx), so decode it
                // as-is: Coil re-sampling it down to the un-zoomed layout size is what made focused
                // cards look soft.
                ImageRequest.Builder(context).data(artwork.url).size(Size.ORIGINAL).crossfade(fade)
                    .apply {
                        if (headers.isNotEmpty()) httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
                    }
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                alignment = alignment,
                colorFilter = colorFilter,
            )
        }
    }
}

/** Title logos are usually white; in Light theme they're drawn as a dark silhouette so they stay readable. */
@Composable
fun logoColorFilter(): androidx.compose.ui.graphics.ColorFilter? {
    val colors = Aurora.colors
    return if (colors.isDark) null else androidx.compose.ui.graphics.ColorFilter.tint(colors.onBackground, androidx.compose.ui.graphics.BlendMode.SrcIn)
}
