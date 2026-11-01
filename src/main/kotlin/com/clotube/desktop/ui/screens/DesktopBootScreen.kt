package com.clotube.desktop.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.ui.components.ClotubeGlyphIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DesktopBootScreen(
    onBootComplete: () -> Unit
) {
    // Stage Animators
    val auraRadius = remember { Animatable(0f) }
    val auraAlpha = remember { Animatable(0f) }
    val rippleScale = remember { Animatable(0.2f) }
    val rippleAlpha = remember { Animatable(0f) }

    val iconScale = remember { Animatable(0.6f) }
    val iconAlpha = remember { Animatable(0f) }

    val streakWidth = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val letterSpacingAnim = remember { Animatable(14f) }

    LaunchedEffect(Unit) {
        // Initial black pause
        delay(150L)

        // Phase 1: Radial aura expansion + shockwave pulse
        launch {
            auraAlpha.animateTo(0.65f, tween(400, easing = LinearEasing))
            auraAlpha.animateTo(0.35f, tween(1000, easing = FastOutSlowInEasing))
        }
        launch {
            auraRadius.animateTo(280f, tween(1200, easing = FastOutSlowInEasing))
        }

        launch {
            rippleAlpha.snapTo(0.85f)
            launch {
                rippleScale.animateTo(
                    targetValue = 2.4f,
                    animationSpec = tween(750, easing = CubicBezierEasing(0.1f, 0.8f, 0.3f, 1f))
                )
            }
            rippleAlpha.animateTo(0f, tween(750, easing = FastOutSlowInEasing))
        }

        // Phase 2: 3D Glyph zooms into focus
        delay(120L)
        launch {
            iconAlpha.animateTo(1f, tween(350))
        }
        iconScale.animateTo(
            targetValue = 1.0f,
            animationSpec = tween(550, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
        )

        // Phase 3: Anamorphic Light Streak & Cinematic Typography Tracking
        delay(80L)
        launch {
            streakWidth.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        }
        launch {
            textAlpha.animateTo(1f, tween(450))
        }
        letterSpacingAnim.animateTo(4.5f, tween(600, easing = FastOutSlowInEasing))

        // Phase 4: Hold cinema lockup before launching app
        delay(550L)
        onBootComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center
    ) {
        // 1. Skia GPU Aura & Shockwave Ripple Layer
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerOffset = Offset(size.width / 2f, size.height / 2f)

            // Ambient background halo
            if (auraAlpha.value > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFF00E5FF).copy(alpha = auraAlpha.value),
                            0.45f to Color(0xFF1D4ED8).copy(alpha = auraAlpha.value * 0.5f),
                            1.0f to Color.Transparent
                        ),
                        center = centerOffset,
                        radius = auraRadius.value.dp.toPx()
                    ),
                    center = centerOffset,
                    radius = auraRadius.value.dp.toPx()
                )
            }

            // Expanding sonic shockwave ring
            if (rippleAlpha.value > 0f) {
                val baseRadius = 56.dp.toPx()
                drawCircle(
                    color = Color(0xFF00E5FF).copy(alpha = rippleAlpha.value),
                    radius = baseRadius * rippleScale.value,
                    center = centerOffset,
                    style = Stroke(width = 1.8.dp.toPx())
                )
            }
        }

        // 2. Central Brand Identity Column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Floating 3D Glyph
            ClotubeGlyphIcon(
                size = 110.dp,
                modifier = Modifier.graphicsLayer {
                    scaleX = iconScale.value
                    scaleY = iconScale.value
                    alpha = iconAlpha.value
                }
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Anamorphic Light Streak
            Box(
                modifier = Modifier
                    .size(width = (240 * streakWidth.value).dp, height = 1.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFF00E5FF),
                                Color(0xFF38BDF8),
                                Color(0xFF00E5FF),
                                Color.Transparent
                            )
                        )
                    )
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Cinematic Letter-Spaced Brand Wordmark
            Text(
                text = "CLOTUBE",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = letterSpacingAnim.value.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha.value
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "DIRECT CLOUD CINEMA",
                color = Color(0xFF94A3B8),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha.value * 0.75f
                }
            )
        }
    }
}
