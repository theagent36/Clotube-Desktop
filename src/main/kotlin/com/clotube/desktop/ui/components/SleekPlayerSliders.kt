package com.clotube.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * A slim, sleek slider that matches cinema player styling with a 3dp track
 * and glowing circular thumb.
 */
@Composable
fun SleekSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 3.dp,
    thumbSize: Dp = 10.dp,
    inactiveTrackColor: Color = Color(0xFF1E2838),
    activeTrackBrush: Brush = Brush.horizontalGradient(
        colors = listOf(Color(0xFF2563EB), Color(0xFF00E5FF))
    ),
    thumbColor: Color = Color(0xFF00E5FF),
    thumbBorderColor: Color = Color.White
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val currentFraction = (if (isDragging) dragProgress else value).coerceIn(0f, 1f)
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .height(24.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        val seekVal = (offset.x / size.width).coerceIn(0f, 1f)
                        onValueChange(seekVal)
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val seekVal = if (size.width > 0) (offset.x / size.width).coerceIn(0f, 1f) else 0f
                        dragProgress = seekVal
                        onValueChange(seekVal)
                    },
                    onDragEnd = {
                        isDragging = false
                        onValueChange(dragProgress)
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (size.width > 0) {
                            val seekVal = (change.position.x / size.width).coerceIn(0f, 1f)
                            dragProgress = seekVal
                            onValueChange(seekVal)
                        }
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val thumbRadiusPx = with(density) { (thumbSize / 2).toPx() }
        val thumbDiameterPx = with(density) { thumbSize.toPx() }

        // Inactive background track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(inactiveTrackColor)
        )

        // Active highlighted track (gradient cyan/blue)
        Box(
            modifier = Modifier
                .fillMaxWidth(currentFraction)
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(activeTrackBrush)
        )

        // Sleek glowing thumb circle with crisp white inner rim
        val thumbOffset = ((widthPx * currentFraction) - thumbRadiusPx)
            .coerceIn(0f, (widthPx - thumbDiameterPx).coerceAtLeast(0f))
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffset.toInt(), 0) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(thumbColor)
                .border(1.5.dp, thumbBorderColor, CircleShape)
        )
    }
}

/**
 * Slim video scrubber with 3dp track height matching cinema player design.
 */
@Composable
fun SleekVideoScrubber(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    SleekSlider(
        value = progress,
        onValueChange = onSeek,
        modifier = modifier
    )
}

/**
 * Slim volume slider with 3dp track height and glowing circular thumb matching the scrubber.
 */
@Composable
fun SleekVolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    SleekSlider(
        value = volume,
        onValueChange = onVolumeChange,
        modifier = modifier
    )
}
