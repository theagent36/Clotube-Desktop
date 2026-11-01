package com.clotube.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun PacedLoadingSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    color: Color = Color(0xFF00E5FF),
    strokeWidth: Dp = 3.5.dp
) {
    var rotation by remember { mutableFloatStateOf(0f) }
    var sweep by remember { mutableFloatStateOf(60f) }

    LaunchedEffect(Unit) {
        var elapsedMs = 0L
        DesktopFramePacer.paceLoop { frameStep ->
            elapsedMs += frameStep
            rotation = ((elapsedMs % 1332L).toFloat() / 1332f) * 360f
            val wave = sin((elapsedMs.toDouble() / 666.0) * PI).toFloat()
            sweep = 60f + ((wave + 1f) * 0.5f * 200f)
        }
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = strokeWidth.toPx()
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)

            drawArc(
                color = color,
                startAngle = rotation,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun PacedEqualizerBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = Color(0xFF00E5FF),
    barWidth: Dp = 2.5.dp,
    maxHeight: Dp = 14.dp
) {
    val barFractions = remember { mutableStateListOf(0.3f, 0.6f, 0.9f, 0.4f) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            barFractions[0] = 0.2f
            barFractions[1] = 0.2f
            barFractions[2] = 0.2f
            barFractions[3] = 0.2f
            return@LaunchedEffect
        }

        var elapsedMs = 0L
        DesktopFramePacer.paceLoop { frameStep ->
            elapsedMs += frameStep
            barFractions[0] = (0.25f + 0.65f * ((sin(elapsedMs * 0.008) + 1.0) / 2.0)).toFloat()
            barFractions[1] = (0.25f + 0.70f * ((sin(elapsedMs * 0.012 + 1.2) + 1.0) / 2.0)).toFloat()
            barFractions[2] = (0.25f + 0.75f * ((sin(elapsedMs * 0.006 + 2.4) + 1.0) / 2.0)).toFloat()
            barFractions[3] = (0.25f + 0.60f * ((sin(elapsedMs * 0.010 + 3.6) + 1.0) / 2.0)).toFloat()
        }
    }

    Row(
        modifier = modifier.height(maxHeight),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        barFractions.forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .fillMaxHeight(fraction.coerceIn(0.15f, 1.0f))
                    .background(barColor)
            )
        }
    }
}
