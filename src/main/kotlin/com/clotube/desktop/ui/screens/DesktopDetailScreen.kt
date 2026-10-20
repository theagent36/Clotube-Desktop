package com.clotube.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.clotube.desktop.model.Episode
import com.clotube.desktop.model.MediaItem
import com.clotube.desktop.model.MockData
import com.clotube.desktop.ui.theme.*

@Composable
fun DesktopDetailScreen(
    media: MediaItem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSeasonIndex by remember(media.id) { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var selectedEpisode by remember(media.id) {
        mutableStateOf(media.seasons.firstOrNull()?.episodes?.firstOrNull())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VoidBackground)
            .padding(horizontal = 28.dp, vertical = 20.dp)
    ) {
        // Back Navigation Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onBack)
                .padding(vertical = 6.dp, horizontal = 4.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextSlateMuted,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Back to Browse",
                style = ClotubeTypography.labelLarge.copy(color = TextSlateMuted)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Dual-Pane Layout
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            // Left Pane (65% width): Video Player Viewport & Details
            Column(
                modifier = Modifier
                    .weight(0.65f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 16:9 Video Player Viewport Placeholder
                VideoPlayerViewport(
                    media = media,
                    currentEpisode = selectedEpisode,
                    isPlaying = isPlaying,
                    onPlayToggle = { isPlaying = !isPlaying }
                )

                // Metadata & Description below player
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Title & Action buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = selectedEpisode?.let { "${media.title}: ${it.title}" } ?: media.title,
                                        style = ClotubeTypography.headlineLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = TextWhite
                                        )
                                    )
                                    Text(
                                        text = media.genre,
                                        style = ClotubeTypography.bodyMedium.copy(color = TextSlateMuted)
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    IconButtonBadge(icon = Icons.Outlined.BookmarkBorder, label = "Watchlist")
                                    IconButtonBadge(icon = Icons.Outlined.Share, label = "Share")
                                }
                            }

                            // Tags row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                BadgeTag(text = media.quality, highlight = true)
                                BadgeTag(text = media.frameRate)
                                BadgeTag(text = media.hdr)
                                BadgeTag(text = media.codec)
                                BadgeTag(text = media.year)

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(start = 6.dp)
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

                            Spacer(modifier = Modifier.height(6.dp))

                            // Synopsis
                            Text(
                                text = selectedEpisode?.synopsis ?: media.description,
                                style = ClotubeTypography.bodyLarge.copy(
                                    color = TextSlateMuted,
                                    lineHeight = 22.sp
                                )
                            )
                        }
                    }
                }
            }

            // Right Pane (35% width): Season Selector & Episode List
            Box(
                modifier = Modifier
                    .weight(0.35f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                if (media.isSeries && media.seasons.isNotEmpty()) {
                    val currentSeason = media.seasons.getOrElse(selectedSeasonIndex) { media.seasons.first() }

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Season Selector Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Episodes",
                                style = ClotubeTypography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextWhite
                                )
                            )

                            // Season selector pills
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                media.seasons.forEachIndexed { index, season ->
                                    val isSelected = index == selectedSeasonIndex
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) AccentPrimary else ElevatedCard)
                                            .border(
                                                1.dp,
                                                if (isSelected) AccentSecondary else BorderSubtle,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .clickable {
                                                selectedSeasonIndex = index
                                                selectedEpisode = season.episodes.firstOrNull()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = season.title,
                                            style = ClotubeTypography.labelSmall.copy(
                                                color = if (isSelected) Color.White else TextSlateMuted,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // Scrollable Episodes List
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(currentSeason.episodes) { episode ->
                                EpisodeCard(
                                    episode = episode,
                                    isSelected = selectedEpisode?.episodeNumber == episode.episodeNumber,
                                    onClick = {
                                        selectedEpisode = episode
                                        isPlaying = true
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Movie details & technical stream info
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Text(
                            text = "Stream Details",
                            style = ClotubeTypography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                        )

                        StreamDetailItem(label = "Source", value = "Direct Cloud Drive")
                        StreamDetailItem(label = "Resolution", value = "3840 x 2160 (4K UHD)")
                        StreamDetailItem(label = "Frame Rate", value = "60.000 FPS")
                        StreamDetailItem(label = "Color Space", value = "BT.2020 / HDR10")
                        StreamDetailItem(label = "Video Bitrate", value = "48.5 Mbps Direct")
                        StreamDetailItem(label = "Audio Format", value = "Dolby Atmos (TrueHD 7.1)")
                        StreamDetailItem(label = "Compression", value = "0% Lossless Passthrough")

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "More Like This",
                            style = ClotubeTypography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextWhite
                            )
                        )

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(MockData.trendingMovies.take(3)) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ElevatedCard)
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 64.dp, height = 40.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    ) {
                                        AsyncImage(
                                            model = item.thumbnailUrl,
                                            contentDescription = item.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            style = ClotubeTypography.titleMedium.copy(fontSize = 13.sp),
                                            maxLines = 1
                                        )
                                        Text(
                                            text = item.duration,
                                            style = ClotubeTypography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoPlayerViewport(
    media: MediaItem,
    currentEpisode: Episode?,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
    ) {
        // Video backdrop / preview frame
        AsyncImage(
            model = currentEpisode?.thumbnailUrl ?: media.backdropUrl,
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Dim overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x73000000))
        )

        // Center big play/pause button
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(72.dp)
                .shadow(20.dp, CircleShape, spotColor = AccentSecondary)
                .clip(CircleShape)
                .background(AccentGradient)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onPlayToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(38.dp)
            )
        }

        // Bottom playback control bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xF20B0C10))
                    )
                )
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Scrub Slider Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(ProgressTrack)
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.38f)
                        .background(AccentGradient)
                )
            }

            // Controls row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left controls: Play, skip, time
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = TextWhite,
                        modifier = Modifier
                            .size(20.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClick = onPlayToggle)
                    )

                    Icon(
                        imageVector = Icons.Filled.FastForward,
                        contentDescription = "Forward 10s",
                        tint = TextSlateMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                    )

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Volume",
                        tint = TextSlateMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                    )

                    Text(
                        text = "00:42:15 / 01:54:00",
                        style = ClotubeTypography.labelSmall.copy(color = TextWhite)
                    )
                }

                // Right controls: Quality pill, CC, Fullscreen
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BadgeTag(text = "4K 60FPS", highlight = true)

                    Icon(
                        imageVector = Icons.Filled.ClosedCaption,
                        contentDescription = "Captions",
                        tint = AccentSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                    )

                    Icon(
                        imageVector = Icons.Filled.Fullscreen,
                        contentDescription = "Fullscreen",
                        tint = TextWhite,
                        modifier = Modifier
                            .size(22.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                    )
                }
            }
        }
    }
}

@Composable
fun EpisodeCard(
    episode: Episode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Color(0xFF1B2538) else if (isHovered) ElevatedCardHover else ElevatedCard)
            .border(
                1.dp,
                if (isSelected) AccentSecondary else if (isHovered) BorderHighlight else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail with episode number overlay
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 54.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(VoidBackground)
        ) {
            AsyncImage(
                model = episode.thumbnailUrl,
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Episode number badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xCC0B0C10))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "E${episode.episodeNumber}",
                    style = ClotubeTypography.labelSmall.copy(color = TextWhite, fontSize = 9.sp)
                )
            }

            // Progress bar
            if (episode.progress != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter)
                        .background(ProgressTrack)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(episode.progress)
                            .background(AccentGradient)
                    )
                }
            }
        }

        // Title, duration, synopsis
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${episode.episodeNumber}. ${episode.title}",
                    style = ClotubeTypography.titleMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) AccentSecondary else TextWhite,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = episode.duration,
                    style = ClotubeTypography.labelSmall.copy(color = TextSlateMuted)
                )
            }

            Text(
                text = episode.synopsis,
                style = ClotubeTypography.bodySmall.copy(color = TextSlateMuted),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun StreamDetailItem(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = ClotubeTypography.bodyMedium.copy(color = TextSlateMuted))
        Text(text = value, style = ClotubeTypography.bodyMedium.copy(color = TextWhite, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
fun IconButtonBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ElevatedCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable {}
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = TextSlateMuted, modifier = Modifier.size(16.dp))
        Text(text = label, style = ClotubeTypography.labelSmall.copy(color = TextWhite))
    }
}
