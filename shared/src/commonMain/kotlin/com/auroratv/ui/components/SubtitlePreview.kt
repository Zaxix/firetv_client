package com.auroratv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auroratv.playback.SubtitleEdge
import com.auroratv.playback.SubtitleStyle
import com.auroratv.ui.theme.AuroraType

/** A sample frame showing how plain subtitles will look with [style] (drawn by the app, so it's approximate). */
@Composable
fun SubtitlePreview(style: SubtitleStyle, scale: Float, modifier: Modifier = Modifier) {
    val text = "I'll meet you by the harbour at nine."
    val fg = Color(style.textArgb(hdr = false))
    val base = AuroraType.bodyStrong.copy(
        fontSize = (20 * scale).sp, lineHeight = (26 * scale).sp,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Medium,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        shadow = if (style.edge == SubtitleEdge.Shadow) androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.8f), androidx.compose.ui.geometry.Offset(3f, 3f), 4f) else null,
    )
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF5B7BA6), Color(0xFFE2B47A), Color(0xFF2E3C55)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp + maxHeight * (style.raisePercent / 100f))
                .background(Color.Black.copy(alpha = style.background.alpha / 255f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            if (style.edge == SubtitleEdge.Outline) {
                Text(text, style = base.copy(color = Color.Black, drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f, join = androidx.compose.ui.graphics.StrokeJoin.Round)))
            }
            Text(text, style = base.copy(color = fg))
        }
        Text("Sample", style = AuroraType.caption, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
    }
}

