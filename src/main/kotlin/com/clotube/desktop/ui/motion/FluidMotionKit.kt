package com.clotube.desktop.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object FluidMotionKit {
    val CinemaDecel = CubicBezierEasing(0.12f, 1f, 0.2f, 1f)
    val SilkSpring = spring<Float>(dampingRatio = 0.78f, stiffness = 380f)
    val SilkSpringDp = spring<Dp>(dampingRatio = 0.78f, stiffness = 380f)
    val TactilePress = spring<Float>(dampingRatio = 0.85f, stiffness = 500f)
    val TactilePressDp = spring<Dp>(dampingRatio = 0.85f, stiffness = 500f)
}

/**
 * GPU-isolated cascading entrance animation.
 * Animates translationY and alpha strictly via Skia graphicsLayer with zero layout re-measurement.
 */
@Composable
fun FluidEntranceContainer(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val alphaAnim = remember { Animatable(0f) }
    val yOffsetAnim = remember { Animatable(18f) }

    LaunchedEffect(Unit) {
        delay((index * 20L).coerceAtMost(260L))
        launch {
            alphaAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 220, easing = FluidMotionKit.CinemaDecel)
            )
        }
        launch {
            yOffsetAnim.animateTo(
                targetValue = 0f,
                animationSpec = FluidMotionKit.SilkSpring
            )
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            alpha = alphaAnim.value
            translationY = yOffsetAnim.value * density
        }
    ) {
        content()
    }
}
