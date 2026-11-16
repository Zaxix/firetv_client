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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.BlurHash
import com.auroratv.ui.theme.pixelsToImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * A frosted-glass colour field made from a piece of artwork: its BlurHash (a 32×18 image the server
 * already sent with the item, so nothing is downloaded) with the frosting (dimming towards the
 * edges, a soft lighter middle) baked into those few hundred pixels, then stretched over the screen
 * by the GPU. That's a single full-screen draw, which matters on Fire TV sticks whose graphics chips
 * are limited by how many full-screen layers they blend per frame.
 */
@Composable
fun FrostedField(hash: String?, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    val dark = colors.isDark
    val bg = colors.background
    val frosted = produceState<ImageBitmap?>(FrostCache.get(hash, dark), hash, dark) {
        if (value == null && hash != null) {
            value = withContext(Dispatchers.Default) { frost(hash, bg, dark) }?.also { FrostCache.put(hash, dark, it) }
        }
    }.value
    val painter = remember(frosted) { frosted?.let { BitmapPainter(it, filterQuality = FilterQuality.Low) } }
    if (painter != null) Image(painter, null, modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    else Box(modifier.fillMaxSize().background(bg))
}

/** BlurHash → small bitmap with the dimming applied per pixel (blended towards the theme background). */
private fun frost(hash: String, bg: Color, dark: Boolean): ImageBitmap? {
    val w = 32; val h = 18
    val src = BlurHash.decodePixels(hash, w, h) ?: return null
    val base = if (dark) 0.40f else 0.30f
    val br = (bg.red * 255).toInt(); val bgG = (bg.green * 255).toInt(); val bb = (bg.blue * 255).toInt()
    val out = IntArray(w * h) { i ->
        val x = (i % w) / (w - 1f); val y = (i / w) / (h - 1f)
        // Darker at the left (where text sits) and towards the bottom, lighter in the upper middle.
        val dim = (base + 0.22f * (1f - x) * 0.6f + 0.18f * y * y).coerceIn(0f, 0.85f)
        val p = src[i]
        val r = ((p shr 16) and 0xFF); val g = ((p shr 8) and 0xFF); val b = (p and 0xFF)
        fun mix(c: Int, t: Int) = (c + (t - c) * dim).toInt().coerceIn(0, 255)
        (0xFF shl 24) or (mix(r, br) shl 16) or (mix(g, bgG) shl 8) or mix(b, bb)
    }
    return pixelsToImageBitmap(out, w, h)
}

/** Recently frosted fields (UI thread only), so moving back and forth between cards is free. */
private object FrostCache {
    private val map = LinkedHashMap<String, ImageBitmap>()
    fun get(hash: String?, dark: Boolean): ImageBitmap? = hash?.let { k -> map.remove("$dark$k")?.also { map["$dark$k"] = it } }
    fun put(hash: String, dark: Boolean, bmp: ImageBitmap) {
        map["$dark$hash"] = bmp
        if (map.size > 64) map.keys.firstOrNull()?.let { map.remove(it) }
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
