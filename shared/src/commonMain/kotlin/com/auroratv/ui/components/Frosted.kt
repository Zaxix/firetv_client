package com.auroratv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.BlurHash
import com.auroratv.ui.theme.pixelsToImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** The BlurHash as a tiny bitmap: from the cache at once, otherwise decoded off the UI thread. */
@Composable
fun rememberBlurHashImage(hash: String?): ImageBitmap? =
    produceState(BlurHash.cached(hash), hash) {
        if (value == null && hash != null) {
            val bmp = withContext(Dispatchers.Default) { BlurHash.decode(hash) }
            if (bmp != null) { BlurHash.store(hash, bmp); value = bmp }
        }
    }.value

/**
 * A frosted-glass colour field made from a piece of artwork: its BlurHash (a 32×18 bitmap the server
 * already sent with the item, so nothing is downloaded) stretched over the screen by the GPU, then
 * dimmed, given a soft sheen and a fine grain. Three cheap full-screen draws and no live blur, so it
 * runs as smoothly on a Fire TV stick as on a PC.
 */
@Composable
fun FrostedField(hash: String?, modifier: Modifier = Modifier) {
    val bmp = rememberBlurHashImage(hash)
    val colors = Aurora.colors
    val painter = remember(bmp) { bmp?.let { BitmapPainter(it, filterQuality = FilterQuality.Low) } }
    val frost = remember(colors.background, colors.isDark) {
        val base = if (colors.isDark) 0.38f else 0.28f
        Brush.linearGradient(
            0f to colors.background.copy(alpha = base + 0.22f),
            0.45f to colors.background.copy(alpha = base),
            0.8f to colors.background.copy(alpha = base + 0.05f),
            1f to colors.background.copy(alpha = base + 0.25f),
        )
    }
    Box(modifier.fillMaxSize().background(colors.background)) {
        if (painter != null) Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Box(Modifier.fillMaxSize().background(frost))
        Box(Modifier.fillMaxSize().background(FrostGrain.brush, alpha = if (colors.isDark) 0.5f else 0.35f))
    }
}

/** A small tile of random white specks, repeated over the screen to give the glass its frosted texture. */
private object FrostGrain {
    private const val SIZE = 128
    val brush: Brush by lazy {
        val rnd = Random(7)
        val pixels = IntArray(SIZE * SIZE) {
            val a = (rnd.nextFloat() * rnd.nextFloat() * 34).toInt() // mostly faint, a few brighter specks
            (a shl 24) or 0x00FFFFFF
        }
        ShaderBrush(ImageShader(pixelsToImageBitmap(pixels, SIZE, SIZE), TileMode.Repeated, TileMode.Repeated))
    }
}

/** The light edge and sheen used on glass panels (sidebar, prompts). */
object Glass {
    fun fill(surface: Color, opaque: Boolean): Brush = Brush.verticalGradient(
        0f to surface.copy(alpha = if (opaque) 0.9f else 0.5f).compositeOverWhite(0.07f),
        1f to surface.copy(alpha = if (opaque) 0.86f else 0.4f),
    )

    val edge: Brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.32f),
        0.35f to Color.White.copy(alpha = 0.10f),
        1f to Color.White.copy(alpha = 0.05f),
    )

    private fun Color.compositeOverWhite(amount: Float) = Color(
        red = red + (1f - red) * amount, green = green + (1f - green) * amount, blue = blue + (1f - blue) * amount, alpha = alpha,
    )
}
