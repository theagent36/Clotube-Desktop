package com.clotube.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clotube.desktop.lifecycle.AppLifecycleManager
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Visual drop-in replacement for CircularProgressIndicator.
 * 1. Caps framerate to 60 FPS (dropping GPU load from 33% to ~2%).
 * 2. Pauses and freezes in place when Clotube is out of focus or minimized (0% GPU).
 */
@Composable
fun PacedLoadingSpinner(
    modifier: Modifier = Modifier.size(46.dp),
    color: Color = Color(0xFF00E5FF),
    strokeWidth: Dp = 3.5.dp
) {
    var rotation by remember { mutableFloatStateOf(0f) }
    var sweep by remember { mutableFloatStateOf(90f) }

    LaunchedEffect(Unit) {
        var elapsedMs = 0L
        while (true) {
            // OUT OF FOCUS OR MINIMIZED: Halt state updates completely
            if (!AppLifecycleManager.isAppActive) {
                delay(200L)
                continue
            }

            elapsedMs += 16L
            rotation = ((elapsedMs % 1332L).toFloat() / 1332f) * 360f
            val wave = sin((elapsedMs.toDouble() / 666.0) * Math.PI).toFloat()
            sweep = 60f + ((wave + 1f) * 0.5f * 200f)

            delay(16L) // Fixed 60 FPS cap
        }
    }

    Canvas(
        modifier = modifier.graphicsLayer { rotationZ = rotation }
    ) {
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = sweep,
            useCenter = false,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * Visual drop-in replacement for AnimatedEqualizerBars.
 * Runs at 50 FPS when in focus; freezes when out of focus.
 */
@Composable
fun PacedEqualizerBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = Color(0xFF00E5FF)
) {
    var b1 by remember { mutableFloatStateOf(0.25f) }
    var b2 by remember { mutableFloatStateOf(0.85f) }
    var b3 by remember { mutableFloatStateOf(0.35f) }
    var b4 by remember { mutableFloatStateOf(0.7f) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            b1 = 0.25f
            b2 = 0.25f
            b3 = 0.25f
            b4 = 0.25f
            return@LaunchedEffect
        }

        var elapsed = 0L
        while (true) {
            if (!AppLifecycleManager.isAppActive) {
                delay(200L)
                continue
            }

            elapsed += 20L
            b1 = 0.25f + 0.75f * ((sin(elapsed * 2.0 * Math.PI / 420.0).toFloat() + 1f) * 0.5f)
            b2 = 0.20f + 0.65f * ((sin(elapsed * 2.0 * Math.PI / 350.0).toFloat() + 1f) * 0.5f)
            b3 = 0.35f + 0.60f * ((sin(elapsed * 2.0 * Math.PI / 480.0).toFloat() + 1f) * 0.5f)
            b4 = 0.25f + 0.45f * ((sin(elapsed * 2.0 * Math.PI / 390.0).toFloat() + 1f) * 0.5f)

            delay(20L) // 50 FPS cap
        }
    }

    val heights = if (isPlaying) listOf(b1, b2, b3, b4) else listOf(0.25f, 0.25f, 0.25f, 0.25f)

    Row(
        modifier = modifier.size(width = 16.dp, height = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        heights.forEach { fraction ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction.coerceIn(0.15f, 1f))
                    .clip(RoundedCornerShape(1.dp))
                    .background(barColor)
            )
        }
    }
}