package com.auroratv.ui.theme

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage

actual fun pixelsToImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

actual fun decodeImageRegion(bytes: ByteArray, x: Int, y: Int, w: Int, h: Int): ImageBitmap? = runCatching {
    javax.imageio.ImageIO.createImageInputStream(java.io.ByteArrayInputStream(bytes)).use { input ->
        val reader = javax.imageio.ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
        try {
            reader.input = input
            val param = reader.defaultReadParam.apply { sourceRegion = java.awt.Rectangle(x, y, w, h) }
            reader.read(0, param).toComposeImageBitmap()
        } finally {
            reader.dispose()
        }
    }
}.getOrNull()
