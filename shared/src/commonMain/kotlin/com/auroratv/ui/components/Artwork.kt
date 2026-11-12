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
import com.auroratv.jellyfin.Artwork
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.BlurHash

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
            val fade = Aurora.motion.duration(220)
            val request = remember(artwork.url, fade) {
                ImageRequest.Builder(context).data(artwork.url).crossfade(fade).build()
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
