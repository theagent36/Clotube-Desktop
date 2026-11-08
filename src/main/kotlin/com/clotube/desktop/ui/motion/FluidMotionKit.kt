package com.clotube.desktop.ui.motion

import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object FluidMotionKit {
    val CinemaDecel = CubicBezierEasing(0.12f, 1f, 0.2f, 1f)
    val SilkSpring = spring<Float>(dampingRatio = 0.78f, stiffness = 380f)
    val TactilePress = spring<Float>(dampingRatio = 0.85f, stiffness = 500f)
}

/**
 * High-performance GPU-accelerated hover bounce and cyan border bloom.
 * Runs zero layout passes on AWT Event Dispatch Thread.
 */
fun Modifier.fluidInteractiveCard(
    cornerRadius: Dp = 10.dp,
    hoverScale: Float = 1.035f,
    pressScale: Float = 0.975f,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit
): Modifier = this.composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isHovered by source.collectIsHoveredAsState()
    val isPressed by source.collectIsPressedAsState()

    val targetScale = when {
        isPressed -> pressScale
        isHovered -> hoverScale
        else -> 1.0f
    }

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = FluidMotionKit.SilkSpring,
        label = "CardScale"
    )

    val borderAlpha by animateFloatAsState(
        targetValue = if (isHovered) 0.9f else 0.0f,
        animationSpec = tween(220, easing = FluidMotionKit.CinemaDecel),
        label = "GlowAlpha"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .hoverable(source)
        .clickable(
            interactionSource = source,
            indication = null,
            onClick = onClick
        )
        .drawWithContent {
            drawContent()
            if (borderAlpha > 0.01f) {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(
                            Color(0xFF00E5FF).copy(alpha = borderAlpha),
                            Color(0xFF2563EB).copy(alpha = borderAlpha * 0.4f)
                        )
                    ),
                    cornerRadius = CornerRadius(cornerRadius.toPx()),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
}

/**
 * Cascading item entrance with vertical drift and soft fade.
 */
@Composable
fun FluidEntranceContainer(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val alphaAnim = remember { Animatable(0f) }
    val yOffsetAnim = remember { Animatable(22f) }

    LaunchedEffect(Unit) {
        delay((index * 22L).coerceAtMost(280L))
        launch {
            alphaAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(240, easing = FluidMotionKit.CinemaDecel)
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
