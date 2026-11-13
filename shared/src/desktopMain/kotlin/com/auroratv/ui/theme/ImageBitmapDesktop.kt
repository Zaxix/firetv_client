package com.auroratv.ui.theme

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage

actual fun pixelsToImageBitmap(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}
