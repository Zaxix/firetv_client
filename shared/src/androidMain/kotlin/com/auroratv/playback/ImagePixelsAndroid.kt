package com.auroratv.playback

import android.graphics.BitmapFactory

actual fun decodeImagePixels(bytes: ByteArray, sampleSize: Int): DecodedPixels? = runCatching {
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize.coerceAtLeast(1)
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    try {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        DecodedPixels(bmp.width, bmp.height, px)
    } finally {
        bmp.recycle()
    }
}.getOrNull()
