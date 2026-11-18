package com.auroratv.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun pixelsToImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

@Suppress("DEPRECATION") // newInstance(byte[], ...) is the only overload before API 31
actual fun decodeImageRegion(bytes: ByteArray, x: Int, y: Int, w: Int, h: Int): ImageBitmap? = runCatching {
    val decoder = android.graphics.BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return null
    try {
        val rect = android.graphics.Rect(x, y, (x + w).coerceAtMost(decoder.width), (y + h).coerceAtMost(decoder.height))
        if (rect.isEmpty) return null
        decoder.decodeRegion(rect, android.graphics.BitmapFactory.Options())?.asImageBitmap()
    } finally {
        decoder.recycle()
    }
}.getOrNull()
