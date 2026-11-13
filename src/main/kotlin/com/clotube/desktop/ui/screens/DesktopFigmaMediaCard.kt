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
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import com.clotube.desktop.data.DesktopWatchHistoryManager
import com.clotube.desktop.data.DesktopWatchlistManager
import com.clotube.desktop.data.WatchlistStatus
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.components.rememberThumbnailBitmap
import kotlinx.coroutines.launch

@Composable
fun DesktopFigmaMediaCard(
    item: DesktopMediaItem,
    accessToken: String = "",
    userEmail: String = "",
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val coroutineScope = rememberCoroutineScope()

    val clickFlashAlpha = remember { Animatable(0f) }

    val cardScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.97f
            isHovered -> 1.03f
            else -> 1.0f
        },
        animationSpec = com.clotube.desktop.ui.motion.FluidMotionKit.SilkSpring,
        label = "media_card_scale"
    )

    val hoveredBorderBrush = remember {
        Brush.verticalGradient(
            colors = listOf(Color(0x55FFFFFF), Color(0x14FFFFFF))
        )
    }

    val defaultBorderBrush = remember {
        Brush.verticalGradient(
            colors = listOf(Color(0xFF222B3D), Color(0xFF131824))
        )
    }

    val thumbnailBitmap = rememberThumbnailBitmap(
        primaryUrl = item.thumbnailUrl,
        fallbackUrl = item.fallbackThumbnailUrl,
        accessToken = accessToken
    )

    val watchlistRevision = DesktopWatchlistManager.watchlistRevision.intValue
    val historyRevision = DesktopWatchHistoryManager.historyRevision.intValue

    val cardWatchlistStatus = remember(item.id, item.title, userEmail, watchlistRevision) {
        if (userEmail.isBlank()) null
        else DesktopWatchlistManager.getStatusForMedia(userEmail, item.title, item.id)
    }
    val isCardHistoryWatched = remember(item.id, item.isSeries, userEmail, historyRevision) {
        !item.isSeries && userEmail.isNotBlank() && DesktopWatchHistoryManager.isEpisodeWatched(userEmail, item.id)
    }
    val showWatchedBadge = cardWatchlistStatus == WatchlistStatus.WATCHED ||
        (cardWatchlistStatus == null && isCardHistoryWatched)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
                clip = false
            }
            .shadow(
                elevation = if (isHovered) 14.dp else 3.dp,
                shape = RoundedCornerShape(12.dp),
                ambientColor = Color(0x40000000),
                spotColor = Color(0x99000000)
            )
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F141F))
            .border(
                border = BorderStroke(
                    width = 1.dp,
                    brush = if (isHovered || isPressed) hoveredBorderBrush else defaultBorderBrush
                ),
                shape = RoundedCornerShape(12.dp)
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    coroutineScope.launch {
                        clickFlashAlpha.snapTo(0.45f)
                        clickFlashAlpha.animateTo(0f, tween(260))
                    }
                    onClick()
                }
            )
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

        // Frosted Obsidian Play Disc on Hover
        if (isHovered) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xD90A0E17))
                .border(1.dp, Color(0x33FFFFFF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Duration Pill (Top Right)
        if (item.durationMs > 0L) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xCC0C111C),
                border = BorderStroke(1.dp, Color(0xFF263346)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Text(
                    text = formatDurationBadge(item.durationMs),
                    color = Color(0xFFCBD5E1),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // Top-Left Watchlist Status Badge
        if (showWatchedBadge) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xD90A0E17),
                border = BorderStroke(1.dp, Color(0x4010B981)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Watched",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "Watched",
                        color = Color(0xFF6EE7B7),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else if (cardWatchlistStatus == WatchlistStatus.WATCH_LATER) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xD90A0E17),
                border = BorderStroke(1.dp, Color(0x4760A5FA)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bookmark,
                        contentDescription = "Watch Later",
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "Watch Later",
                        color = Color(0xFF93C5FD),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else if (cardWatchlistStatus == WatchlistStatus.REWATCH) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xD90A0E17),
                border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Replay,
                        contentDescription = "Rewatch",
                        tint = Color(0xFFCBD5E1),
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "Rewatch",
                        color = Color(0xFFF1F5F9),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
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
                    .background(Color.White.copy(alpha = clickFlashAlpha.value * 0.4f))
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

