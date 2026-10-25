package com.clotube.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.data.DesktopDriveScanner
import com.clotube.desktop.media.DirectMemoryVideoPlayer
import com.clotube.desktop.media.LocalStreamServer
import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.model.DesktopSeason
import com.clotube.desktop.model.DesktopSeries
import com.clotube.desktop.ui.components.rememberThumbnailBitmap

@Composable
fun DesktopSeriesDetailScreen(
    seriesItem: DesktopMediaItem,
    accessToken: String,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var seriesDetails by remember { mutableStateOf<DesktopSeries?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedSeasonNumber by remember { mutableIntStateOf(1) }
    var activeEpisode by remember { mutableStateOf<DesktopEpisode?>(null) }

    LaunchedEffect(seriesItem.id) {
        val scanner = DesktopDriveScanner(accessToken)
        val loaded = scanner.loadSeriesDetails(seriesItem.id, seriesItem.title)
        seriesDetails = loaded
        val initialSeason = loaded.seasons.firstOrNull()
        if (initialSeason != null) {
            selectedSeasonNumber = initialSeason.seasonNumber
            activeEpisode = initialSeason.episodes.firstOrNull()
        }
        isLoading = false
    }

    if (isLoading || seriesDetails == null) {
        Box(
            modifier = modifier.fillMaxSize().background(Color(0xFF07090E)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFF00E5FF))
        }
        return
    }

    val series = seriesDetails!!
    val currentSeason = series.seasons.find { it.seasonNumber == selectedSeasonNumber }
        ?: series.seasons.firstOrNull() ?: DesktopSeason(1, "Season 1", emptyList())
    val episodes = currentSeason.episodes

    val activeStreamUrl = remember(activeEpisode?.id, accessToken) {
        activeEpisode?.let { ep ->
            LocalStreamServer.getStreamUrl(ep.id, accessToken)
        } ?: ""
    }

    // Persist player instance across fullscreen toggles!
    val player = remember(activeStreamUrl) {
        if (activeStreamUrl.isNotBlank()) DirectMemoryVideoPlayer(activeStreamUrl) else null
    }

    DisposableEffect(player) {
        onDispose {
            player?.release()
        }
    }

    val currentEpIndex = episodes.indexOfFirst { it.id == activeEpisode?.id }
    val hasPrev = currentEpIndex > 0
    val hasNext = currentEpIndex in 0 until (episodes.size - 1)

    // True Cinema Fullscreen Mode: Reuses the exact same active player without interruption
    if (isFullscreen && player != null) {
        DesktopSeriesPlayerScreen(
            player = player,
            seriesTitle = series.title,
            episodeTitle = activeEpisode?.title ?: "",
            seasonNumber = activeEpisode?.seasonNumber ?: 1,
            episodeNumber = activeEpisode?.episodeNumber ?: 1,
            isFullscreen = true,
            onToggleFullscreen = onToggleFullscreen,
            onNavigateBack = { onToggleFullscreen() },
            onNextEpisode = if (hasNext) { { activeEpisode = episodes[currentEpIndex + 1] } } else null,
            onPreviousEpisode = if (hasPrev) { { activeEpisode = episodes[currentEpIndex - 1] } } else null,
            hasNextEpisode = hasNext,
            hasPreviousEpisode = hasPrev,
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    var seasonDropdownExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090E)),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(key = "breadcrumb") {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onBack() }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Back to TV Series",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        item(key = "series_header_block") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "CLOTUBE ORIGINAL SERIES",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = series.title,
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${series.releaseYear}  •  ${series.ageRating}  •  ${currentSeason.title}  •  ${series.genres}",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { }
                            .pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF131824),
                        border = BorderStroke(1.dp, Color(0xFF263346))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "My list",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { }
                            .pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF131824),
                        border = BorderStroke(1.dp, Color(0xFF263346)),
                        contentColor = Color.White
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.MoreHoriz,
                                contentDescription = "More",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        item(key = "main_split_view") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(580.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.67f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black)
                            .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(14.dp))
                    ) {
                        if (player != null) {
                            DesktopSeriesPlayerScreen(
                                player = player,
                                seriesTitle = series.title,
                                episodeTitle = activeEpisode?.title ?: "",
                                seasonNumber = activeEpisode?.seasonNumber ?: 1,
                                episodeNumber = activeEpisode?.episodeNumber ?: 1,
                                isFullscreen = false,
                                onToggleFullscreen = onToggleFullscreen,
                                onNavigateBack = onBack,
                                onNextEpisode = if (hasNext) { { activeEpisode = episodes[currentEpIndex + 1] } } else null,
                                onPreviousEpisode = if (hasPrev) { { activeEpisode = episodes[currentEpIndex - 1] } } else null,
                                hasNextEpisode = hasNext,
                                hasPreviousEpisode = hasPrev,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color(0xFF00E5FF))
                            }
                        }
                    }

                    activeEpisode?.let { ep ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "EPISODE ${ep.episodeNumber}",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = ep.title,
                                    color = Color.White,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = ep.durationFormatted,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 13.sp
                                )
                            }
                            Text(
                                text = ep.synopsis,
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("4K UHD", "HDR", "Dolby Atmos", "CC").forEach { tag ->
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF101520),
                                        border = BorderStroke(1.dp, Color(0xFF1E2838))
                                    ) {
                                        Text(
                                            text = tag,
                                            color = Color(0xFFCBD5E1),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(0.33f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF0C1018),
                    border = BorderStroke(1.dp, Color(0xFF18202F))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "EPISODE LIST",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${episodes.size} episodes",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            Box {
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { seasonDropdownExpanded = true }
                                        .pointerHoverIcon(PointerIcon.Hand),
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF131824),
                                    border = BorderStroke(1.dp, Color(0xFF263346))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = currentSeason.title,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Icon(
                                            imageVector = Icons.Rounded.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = Color(0xFF94A3B8),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = seasonDropdownExpanded,
                                    onDismissRequest = { seasonDropdownExpanded = false },
                                    modifier = Modifier.background(Color(0xFF131824))
                                ) {
                                    series.seasons.forEach { season ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "${season.title} (${season.episodes.size} eps)",
                                                    color = if (season.seasonNumber == selectedSeasonNumber) Color(0xFF00E5FF) else Color.White,
                                                    fontSize = 12.sp
                                                )
                                            },
                                            onClick = {
                                                selectedSeasonNumber = season.seasonNumber
                                                seasonDropdownExpanded = false
                                                activeEpisode = season.episodes.firstOrNull()
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00E5FF))
                                )
                                Text(
                                    text = "Direct Stream Ready",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = "Mark all watched",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable { }
                                    .pointerHoverIcon(PointerIcon.Hand)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = Color(0xFF18202F), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(episodes, key = { it.id }) { ep ->
                                val isSelected = activeEpisode?.id == ep.id
                                val thumb = rememberDriveThumbnail(ep.thumbnailUrl, accessToken)

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { activeEpisode = ep }
                                        .pointerHoverIcon(PointerIcon.Hand),
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) Color(0xFF121B2B) else Color(0xFF0F141F),
                                    border = if (isSelected) BorderStroke(1.dp, Color(0xFF2563EB)) else BorderStroke(1.dp, Color(0xFF18202F))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(96.dp)
                                                .height(54.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF161E2E)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (thumb != null) {
                                                Image(
                                                    bitmap = thumb,
                                                    contentDescription = ep.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }

                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF2563EB)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.PlayArrow,
                                                        contentDescription = null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                }
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(22.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xCC000000)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "${ep.episodeNumber}",
                                                        color = Color(0xFF94A3B8),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "EPISODE ${ep.episodeNumber}",
                                                color = Color(0xFF38BDF8),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = ep.title,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${ep.durationFormatted} • Direct Stream",
                                                color = Color(0xFF64748B),
                                                fontSize = 10.sp
                                            )
                                        }

                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Rounded.GraphicEq,
                                                contentDescription = "Playing",
                                                tint = Color(0xFF00E5FF),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDone,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Progress synced to cloud",
                                color = Color(0xFF64748B),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        item(key = "about_the_series") {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFF141A26), thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(0.6f)) {
                    Text(
                        text = "ABOUT THE SERIES",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = series.tagline,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = series.synopsis,
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }

                Column(
                    modifier = Modifier.weight(0.35f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetadataLine(label = "Creators", value = series.creators)
                    MetadataLine(label = "Cast", value = series.cast)
                    MetadataLine(label = "Genres", value = series.genres)
                }
            }
        }
    }
}

@Composable
private fun rememberDriveThumbnail(url: String?, accessToken: String = ""): ImageBitmap? {
    return rememberThumbnailBitmap(primaryUrl = url, accessToken = accessToken)
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFF64748B), fontSize = 12.sp)
        Text(text = value, color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
