package com.auroratv.playback

actual fun decodeImagePixels(bytes: ByteArray, sampleSize: Int): DecodedPixels? = runCatching {
    javax.imageio.ImageIO.createImageInputStream(java.io.ByteArrayInputStream(bytes)).use { input ->
        val reader = javax.imageio.ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
        try {
            reader.input = input
            val s = sampleSize.coerceAtLeast(1)
            val param = reader.defaultReadParam.apply { setSourceSubsampling(s, s, 0, 0) }
            val img = reader.read(0, param)
            val px = IntArray(img.width * img.height)
            img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
            DecodedPixels(img.width, img.height, px)
        } finally {
            reader.dispose()
        }
    }
}.getOrNull()
