package com.clotube.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clotube.desktop.getActiveDisplayRefreshRate
import kotlinx.coroutines.delay

/**
 * Display-refresh-rate synchronized buffering spinner.
 * Paced via coroutines so it never spins at 500+ FPS or elevates GPU temperatures.
 */
@Composable
fun PacedBufferingSpinner(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF00E5FF),
    size: Dp = 46.dp
) {
    var rotationAngle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        val displayHz = getActiveDisplayRefreshRate()
        val frameIntervalMs = (1000L / displayHz.toLong()).coerceIn(4L, 33L)
        val degreesPerFrame = 360f / (1000f / frameIntervalMs.toFloat())

        while (true) {
            rotationAngle = (rotationAngle + degreesPerFrame) % 360f
            delay(frameIntervalMs)
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = rotationAngle },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

