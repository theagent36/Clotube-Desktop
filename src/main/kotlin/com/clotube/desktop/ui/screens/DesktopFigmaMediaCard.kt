package com.clotube.desktop.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.components.rememberThumbnailBitmap
import com.clotube.desktop.ui.motion.fluidInteractiveCard
import kotlinx.coroutines.launch

@Composable
fun DesktopFigmaMediaCard(
    item: DesktopMediaItem,
    accessToken: String = "",
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val coroutineScope = rememberCoroutineScope()

    val clickFlashAlpha = remember { Animatable(0f) }

    // Dynamic Physics: 1.0f (Idle) -> 1.035f (Hover) -> 0.96f (Click Press)
    val cardScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.96f
            isHovered -> 1.035f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "card_scale_anim"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isPressed -> Color(0xFF00E5FF)
            isHovered -> Color(0xFF38BDF8)
            else -> Color(0xFF1B2333)
        },
        animationSpec = tween(durationMillis = 200),
        label = "card_border_anim"
    )

    val thumbnailBitmap = rememberThumbnailBitmap(
        primaryUrl = item.thumbnailUrl,
        fallbackUrl = item.fallbackThumbnailUrl,
        accessToken = accessToken
    )

    Box(
        modifier = modifier
            .fluidInteractiveCard(
                cornerRadius = 10.dp,
                interactionSource = interactionSource,
                onClick = {
                    coroutineScope.launch {
                        clickFlashAlpha.snapTo(0.45f)
                        clickFlashAlpha.animateTo(0f, tween(260))
                    }
                    onClick()
                }
            )
            .shadow(
                elevation = if (isHovered) 16.dp else 4.dp,
                shape = RoundedCornerShape(10.dp),
                ambientColor = Color(0xFF00E5FF).copy(alpha = 0.2f),
                spotColor = Color(0xFF38BDF8).copy(alpha = 0.4f)
            )
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0F1420))
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        // Thumbnail Image Layer
        if (thumbnailBitmap != null) {
            Image(
                bitmap = thumbnailBitmap,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF1E293B), Color(0xFF090D16))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Movie,
                        contentDescription = null,
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.title,
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Hover Play Beacon
        if (isHovered) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC00E5FF))
                    .shadow(12.dp, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Duration Pill (Top Right)
        if (item.durationMs > 0L) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formatDurationBadge(item.durationMs),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Metadata Scrim (Bottom)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xCC05070B), Color(0xF205070B))
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.genre.ifBlank { "Movie" },
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Flash Ripple Layer Triggered on Click
        if (clickFlashAlpha.value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF00E5FF).copy(alpha = clickFlashAlpha.value))
            )
        }
    }
}

private fun formatDurationBadge(durationMs: Long): String {
    val totalMinutes = (durationMs / 60000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h %02dm".format(minutes) else "${minutes}m"
}

