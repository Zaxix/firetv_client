package com.auroratv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.withSign

/**
 * BlurHash decoder. Jellyfin sends a BlurHash for every image, which gives us (1) an instant blurred
 * placeholder while artwork downloads and (2) the artwork's average colour for accent tinting —
 * without downloading or analysing the image itself.
 */
object BlurHash {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    private fun decode83(str: String, from: Int, to: Int): Int {
        var value = 0
        for (i in from until to) {
            val idx = CHARS.indexOf(str[i])
            if (idx < 0) return 0
            value = value * 83 + idx
        }
        return value
    }

    private fun srgbToLinear(v: Int): Double {
        val x = v / 255.0
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSrgb(v: Double): Int {
        val x = v.coerceIn(0.0, 1.0)
        val s = if (x <= 0.0031308) x * 12.92 else 1.055 * x.pow(1 / 2.4) - 0.055
        return (s * 255 + 0.5).toInt().coerceIn(0, 255)
    }

    private fun signPow(v: Double, exp: Double) = kotlin.math.abs(v).pow(exp).withSign(v)

    /** Average colour of the image (the DC component), or null for an invalid hash. */
    fun averageColor(hash: String?): Color? {
        if (hash == null || hash.length < 6) return null
        val dc = decode83(hash, 2, 6)
        return Color(red = dc shr 16 and 255, green = dc shr 8 and 255, blue = dc and 255)
    }

    /** Decodes to a small [width]x[height] bitmap (keep it tiny, e.g. 32x18; it's scaled up and blurred). */
    fun decode(hash: String?, width: Int = 32, height: Int = 18, punch: Double = 1.0): ImageBitmap? {
        if (hash == null || hash.length < 6) return null
        return runCatching {
            val sizeFlag = decode83(hash, 0, 1)
            val numY = sizeFlag / 9 + 1
            val numX = sizeFlag % 9 + 1
            if (hash.length != 4 + 2 * numX * numY) return null
            val quantMax = decode83(hash, 1, 2)
            val maxValue = (quantMax + 1) / 166.0 * punch
            val colors = Array(numX * numY) { i ->
                if (i == 0) {
                    val v = decode83(hash, 2, 6)
                    doubleArrayOf(srgbToLinear(v shr 16), srgbToLinear(v shr 8 and 255), srgbToLinear(v and 255))
                } else {
                    val from = 4 + i * 2
                    val v = decode83(hash, from, from + 2)
                    val r = v / (19 * 19); val g = (v / 19) % 19; val b = v % 19
                    doubleArrayOf(
                        signPow((r - 9) / 9.0, 2.0) * maxValue,
                        signPow((g - 9) / 9.0, 2.0) * maxValue,
                        signPow((b - 9) / 9.0, 2.0) * maxValue,
                    )
                }
            }
            val pixels = IntArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                var r = 0.0; var g = 0.0; var b = 0.0
                for (j in 0 until numY) for (i in 0 until numX) {
                    val basis = cos(PI * x * i / width) * cos(PI * y * j / height)
                    val c = colors[i + j * numX]
                    r += c[0] * basis; g += c[1] * basis; b += c[2] * basis
                }
                pixels[x + y * width] = (0xFF shl 24) or (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(b)
            }
            pixelsToImageBitmap(pixels, width, height)
        }.getOrNull()
    }

    /** A readable accent derived from artwork: keeps hue, lifts lightness so it works as a highlight. */
    fun accentFrom(hash: String?, dark: Boolean): Color? {
        val avg = averageColor(hash) ?: return null
        val r = avg.red; val g = avg.green; val b = avg.blue
        val maxC = max(r, max(g, b)); val minC = kotlin.math.min(r, kotlin.math.min(g, b))
        if (maxC - minC < 0.06f) return null // near-grey artwork: keep the theme accent
        val scale = if (dark) (0.85f / maxC).coerceAtMost(3f) else (0.55f / maxC).coerceAtMost(1.5f)
        return Color((r * scale).coerceIn(0f, 1f), (g * scale).coerceIn(0f, 1f), (b * scale).coerceIn(0f, 1f))
    }
}

/** Platform conversion from ARGB pixels to an [ImageBitmap]. */
expect fun pixelsToImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap
