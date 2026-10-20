package com.clotube.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
import com.clotube.desktop.model.MockData
import com.clotube.desktop.ui.theme.*

@Composable
fun DesktopHomeScreen(
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(VoidBackground),
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        // 1. Hero Billboard
        item {
            HeroBillboard(
                media = MockData.heroBillboard,
                onWatchClick = { onMediaClick(MockData.heroBillboard) }
            )
        }

        // 2. Continue Watching Shelf
        item {
            MediaShelf(
                title = "Continue Watching",
                subtitle = "Pick up where you left off",
                items = MockData.continueWatchingList,
                onItemClick = onMediaClick,
                isProgressShelf = true
            )
        }

        // 3. Trending Movies Shelf
        item {
            MediaShelf(
                title = "Trending Movies",
                subtitle = "Top streaming hits this week",
                items = MockData.trendingMovies,
                onItemClick = onMediaClick
            )
        }

        // 4. Popular Series Shelf
        item {
            MediaShelf(
                title = "Popular Series",
                subtitle = "Binge-worthy multi-season dramas",
                items = MockData.popularSeries,
                onItemClick = onMediaClick
            )
        }
    }
}

@Composable
fun HeroBillboard(
    media: MediaItem,
    onWatchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(420.dp)
            .padding(horizontal = 28.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
    ) {
        // Backdrop Image with fallback background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF1B2A4A), VoidBackground),
                        radius = 1200f
                    )
                )
        ) {
            AsyncImage(
                model = media.backdropUrl,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Gradient Scrim Overlays
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HeroScrimGradient)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HeroBottomScrim)
        )

        // Billboard Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.Start
        ) {
            // Eyebrow badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x3300E5FF))
                    .border(1.dp, Color(0x6600E5FF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(AccentSecondary)
                )
                Text(
                    text = "CLOTUBE PREMIERE",
                    style = ClotubeTypography.labelSmall.copy(
                        color = AccentSecondary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Title
            Text(
                text = media.title,
                style = ClotubeTypography.displayLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = TextWhite
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Metadata Badges (4K 60FPS, HDR, HEVC, Year, Rating)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BadgeTag(text = media.quality, highlight = true)
                BadgeTag(text = media.frameRate)
                BadgeTag(text = media.hdr)
                BadgeTag(text = media.codec)

                Text(
                    text = "•",
                    color = TextSlateMuted,
                    fontSize = 14.sp
                )

                Text(
                    text = media.year,
                    style = ClotubeTypography.bodyMedium.copy(color = TextWhite)
                )

                Text(
                    text = "•",
                    color = TextSlateMuted,
                    fontSize = 14.sp
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Rating",
                        tint = StarGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = media.rating,
                        style = ClotubeTypography.labelLarge.copy(
                            color = TextWhite,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Description preview
            Text(
                text = media.description,
                style = ClotubeTypography.bodyLarge.copy(
                    color = TextSlateMuted,
                    lineHeight = 22.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 680.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Primary "Resume Watching" Pill Button
                val watchInteraction = remember { MutableInteractionSource() }
                val isWatchHovered by watchInteraction.collectIsHoveredAsState()

                Button(
                    onClick = onWatchClick,
                    interactionSource = watchInteraction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isWatchHovered) AccentSecondary else AccentPrimary,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                    modifier = Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .shadow(
                            elevation = if (isWatchHovered) 16.dp else 8.dp,
                            shape = RoundedCornerShape(24.dp),
                            spotColor = if (isWatchHovered) AccentSecondary else AccentPrimary
                        )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (media.progress != null) "Resume Watching" else "Watch Now",
                            style = ClotubeTypography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        )
                    }
                }

                // Secondary "Details & Episodes" Button
                val infoInteraction = remember { MutableInteractionSource() }
                val isInfoHovered by infoInteraction.collectIsHoveredAsState()

                OutlinedButton(
                    onClick = onWatchClick,
                    interactionSource = infoInteraction,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isInfoHovered) Color(0x33FFFFFF) else Color(0x1AFFFFFF),
                        contentColor = TextWhite
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = Brush.horizontalGradient(
                            listOf(
                                if (isInfoHovered) AccentSecondary else BorderHighlight,
                                BorderHighlight
                            )
                        )
                    ),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "Info",
                            tint = TextWhite,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Details & Episodes",
                            style = ClotubeTypography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MediaShelf(
    title: String,
    subtitle: String,
    items: List<MediaItem>,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    isProgressShelf: Boolean = false
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Shelf Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = ClotubeTypography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                )
                Text(
                    text = subtitle,
                    style = ClotubeTypography.bodySmall.copy(color = TextSlateMuted)
                )
            }

            // "Explore All" pill
            val seeAllInteraction = remember { MutableInteractionSource() }
            val isSeeAllHovered by seeAllInteraction.collectIsHoveredAsState()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .hoverable(seeAllInteraction)
                    .clickable(interactionSource = seeAllInteraction, indication = null) {}
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Explore All",
                    style = ClotubeTypography.labelSmall.copy(
                        color = if (isSeeAllHovered) AccentSecondary else TextSlateMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = "See All",
                    tint = if (isSeeAllHovered) AccentSecondary else TextSlateMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Horizontal Carousel
        LazyRow(
            contentPadding = PaddingValues(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(items) { item ->
                MediaCard(
                    media = item,
                    onClick = { onItemClick(item) },
                    showProgress = isProgressShelf
                )
            }
        }
    }
}

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
        // 16:9 Thumbnail preview container
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
            // Thumbnail Image with gradient fallback
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

            // Bottom gradient scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CardScrimGradient)
            )

            // Top Resolution Badge (4K / UHD)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                BadgeTag(text = media.quality, highlight = isHovered)
            }

            // Top Duration Pill
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

            // Hover Play Button Overlay
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

            // Progress Bar (for Continue Watching)
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

        // Title & metadata below card
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
