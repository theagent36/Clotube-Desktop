package com.clotube.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.awt.Frame
import java.awt.KeyboardFocusManager
import kotlin.math.sin

fun isAppWindowActiveAndVisible(): Boolean {
    return try {
        val activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        if (activeWindow == null || !activeWindow.isVisible) return false
        val frame = activeWindow as? Frame ?: return true
        (frame.extendedState and Frame.ICONIFIED) == 0
    } catch (_: Throwable) {
        true
    }
}

/**
 * Visual drop-in replacement for CircularProgressIndicator that runs at a capped 60 FPS
 * and drops to 0% GPU when the window is in the background or minimized.
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
        val startTime = System.currentTimeMillis()
        while (true) {
            if (!isAppWindowActiveAndVisible()) {
                delay(250L) // Sleep when unfocused/minimized: 0% GPU
                continue
            }

            val elapsed = System.currentTimeMillis() - startTime
            rotation = ((elapsed % 1332L).toFloat() / 1332f) * 360f
            val wave = sin((elapsed.toDouble() / 666.0) * Math.PI).toFloat()
            sweep = 60f + ((wave + 1f) * 0.5f * 200f)

            delay(16L) // Capped at 60 FPS
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
