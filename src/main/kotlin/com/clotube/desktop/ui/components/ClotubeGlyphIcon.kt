package com.clotube.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ClotubeGlyphIcon(
    size: Dp = 32.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        // 1. Subtle Outer Glow Ring
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x3300E5FF), Color.Transparent),
                center = Offset(w * 0.45f, h * 0.5f),
                radius = w * 0.55f
            ),
            radius = w * 0.5f,
            center = Offset(w * 0.5f, h * 0.5f)
        )

        // 2. Bold Stylized "C" Arc
        val strokeWidth = w * 0.18f
        val arcPadding = strokeWidth / 2f
        val arcRect = Size(w - arcPadding * 2f, h - arcPadding * 2f)

        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(
                    Color(0xFF00E5FF), // Electric Cyan
                    Color(0xFF2563EB), // Royal Blue
                    Color(0xFF7C3AED), // Violet
                    Color(0xFF00E5FF)  // Smooth loop back
                ),
                center = Offset(w * 0.5f, h * 0.5f)
            ),
            startAngle = 45f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(arcPadding, arcPadding),
            size = arcRect,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // 3. Central Media Play Core Accent
        val playPath = Path().apply {
            val cx = w * 0.44f
            val cy = h * 0.5f
            val r = w * 0.16f
            moveTo(cx - r * 0.6f, cy - r)
            lineTo(cx + r * 1.0f, cy)
            lineTo(cx - r * 0.6f, cy + r)
            close()
        }

        drawPath(
            path = playPath,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF00E5FF), Color(0xFF38BDF8)),
                start = Offset(w * 0.35f, h * 0.35f),
                end = Offset(w * 0.65f, h * 0.65f)
            )
        )
    }
}
