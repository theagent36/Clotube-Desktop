package com.clotube.desktop.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.clotube.desktop.model.MediaItem
import com.clotube.desktop.ui.theme.*

@Composable
fun MediaCard(
    media: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showProgress: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.03f else 1.0f,
        animationSpec = tween(180),
        label = "CardScale"
    )

    Column(
        modifier = modifier
            .width(280.dp)
            .scale(scale)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(158.dp)
                .shadow(
                    elevation = if (isHovered) 16.dp else 4.dp,
                    shape = RoundedCornerShape(14.dp),
                    spotColor = if (isHovered) AccentSecondaryGlow else Color.Black
                )
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = if (isHovered) 1.5.dp else 1.dp,
                    color = if (isHovered) AccentSecondary else BorderSubtle,
                    shape = RoundedCornerShape(14.dp)
                )
                .background(ElevatedCard)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF161E30), Color(0xFF0F1522))
                        )
                    )
            ) {
                AsyncImage(
                    model = media.thumbnailUrl,
                    contentDescription = media.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CardScrimGradient)
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                BadgeTag(text = media.quality, highlight = isHovered)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC0B0C10))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = media.duration,
                    style = ClotubeTypography.labelSmall.copy(
                        color = TextWhite,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            if (isHovered) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(46.dp)
                        .shadow(12.dp, CircleShape, spotColor = AccentSecondary)
                        .clip(CircleShape)
                        .background(AccentGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            if (showProgress && media.progress != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.BottomCenter)
                        .background(ProgressTrack)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = media.progress)
                            .background(AccentGradient)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = media.title,
                style = ClotubeTypography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (isHovered) AccentSecondary else TextWhite
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Rating",
                    tint = StarGold,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = media.rating,
                    style = ClotubeTypography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                )
            }
        }

        Text(
            text = media.genre,
            style = ClotubeTypography.bodySmall.copy(color = TextSlateMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun BadgeTag(
    text: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlight) Color(0x3300E5FF) else Color(0x991E2230))
            .border(
                width = 1.dp,
                color = if (highlight) Color(0x8800E5FF) else BorderSubtle,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = ClotubeTypography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = if (highlight) AccentSecondary else TextSlateMuted,
                letterSpacing = 0.4.sp
            )
        )
    }
}
