package com.auroratv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Sharkfin's two brand colours (purple to blue, in the spirit of Jellyfin's palette). */
val SharkfinPurple = Color(0xFFAA5CC3)
val SharkfinBlue = Color(0xFF00A4DC)

/**
 * The Sharkfin mark: a fin cutting through a wave, filled with the purple-to-blue gradient.
 * Original artwork; sources in docs/brand (`logo.svg`, `icon.svg`).
 */
val AppLogo: ImageVector by lazy {
    val gradient = Brush.linearGradient(listOf(SharkfinPurple, SharkfinBlue), start = Offset(70f, 36f), end = Offset(200f, 226f))
    ImageVector.Builder(
        name = "AppLogo",
        defaultWidth = 48.dp,
        defaultHeight = 48.dp,
        viewportWidth = 256f,
        viewportHeight = 256f,
    )
        .addPath(pathData = addPathNodes(FIN_PATH), fill = gradient)
        .addPath(pathData = addPathNodes(WAVE_PATH), fill = gradient)
        .build()
}

/** The logo in its gradient (reads on both the dark and light themes). */
@Composable
fun AppLogoImage(modifier: Modifier = Modifier) {
    Image(AppLogo, contentDescription = com.auroratv.AppInfo.NAME, modifier = modifier)
}

private const val FIN_PATH = "M56 172C80 118 120 66 194 36C166 88 166 138 206 172Z"
private const val WAVE_PATH = "M22 196C40 182 58 182 76 196C94 210 112 210 130 196C148 182 166 182 184 196C202 210 220 210 234 198L234 214C220 226 202 226 184 212C166 198 148 198 130 212C112 226 94 226 76 212C58 198 40 198 22 212Z"
