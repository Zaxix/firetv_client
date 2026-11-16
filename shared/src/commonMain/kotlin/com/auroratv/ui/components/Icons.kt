package com.auroratv.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Episodes & Seasons: two stacked screens (a front one with another peeking out behind it). */
val EpisodesIcon: ImageVector by lazy {
    fun stroke(path: String) = addPathNodes(path)
    ImageVector.Builder("Episodes", 24.dp, 24.dp, 24f, 24f)
        // Back screen: only the edges that show above and to the right of the front one.
        .addPath(
            stroke("M7 6.5V6a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v7.5a2 2 0 0 1-2 2h-.5"),
            fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        // Front screen.
        .addPath(
            stroke("M5 8.5h10a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7.5a2 2 0 0 1 2-2z"),
            fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}
