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

/**
 * Display-refresh-rate and window-lifecycle synchronized buffering spinner.
 * Routed through [DesktopFramePacer.paceLoop] so background GPU usage drops to 0% when minimized or unfocused.
 */
@Composable
fun PacedBufferingSpinner(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF00E5FF),
    size: Dp = 46.dp
) {
    var rotationAngle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        DesktopFramePacer.paceLoop { frameStepMs ->
            rotationAngle = (rotationAngle + (360f * frameStepMs / 1000f)) % 360f
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

