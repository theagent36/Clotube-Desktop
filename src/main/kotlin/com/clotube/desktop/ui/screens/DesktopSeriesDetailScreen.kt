package com.clotube.desktop.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.data.DesktopDriveScanner
import com.clotube.desktop.data.DesktopSeriesCache
import com.clotube.desktop.data.DesktopWatchHistoryManager
import com.clotube.desktop.data.DesktopWatchHistorySync
import com.clotube.desktop.media.DirectMemoryVideoPlayer
import com.clotube.desktop.media.LocalStreamServer
import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.model.DesktopSeason
import com.clotube.desktop.model.DesktopSeries
import com.clotube.desktop.ui.components.rememberThumbnailBitmap
import com.clotube.desktop.ui.components.PacedLoadingSpinner
import com.clotube.desktop.ui.components.isAppWindowActiveAndVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun DesktopSeriesDetailScreen(
    seriesItem: DesktopMediaItem,
    accessToken: String,
    userEmail: String = "",
    initialEpisodeId: String? = null,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()

    // 1. Frame-1 Cache Read
    val cachedSeries = remember(seriesItem.id, userEmail) { 
        DesktopSeriesCache.get(userEmail, seriesItem.id) 
    }
    var seriesDetails by remember(seriesItem.id, userEmail) { mutableStateOf<DesktopSeries?>(cachedSeries) }
    var isLoading by remember(seriesItem.id, userEmail) { mutableStateOf(cachedSeries == null) }

    // Resolve targeted episode if passed from Global Search or Series Resume Pointer
    val allCachedEpisodes = remember(cachedSeries) {
        cachedSeries?.seasons?.flatMap { it.episodes } ?: emptyList()
    }
    val targetedCachedEp = remember(allCachedEpisodes, initialEpisodeId, userEmail) {
        if (!initialEpisodeId.isNullOrBlank()) {
            allCachedEpisodes.find { it.id == initialEpisodeId }
        } else {
            val resumeState = DesktopWatchHistoryManager.getSeriesResumeState(userEmail, seriesItem.id)
            if (resumeState != null) {
                allCachedEpisodes.find { it.id == resumeState.lastEpisodeId }
            } else null
        }
    }

    var playingEpisode by remember(seriesItem.id, userEmail, initialEpisodeId) {
        mutableStateOf<DesktopEpisode?>(
            targetedCachedEp ?: cachedSeries?.seasons?.firstOrNull()?.episodes?.firstOrNull()
        )
    }

    var browsingSeasonNumber by remember(seriesItem.id, userEmail, initialEpisodeId) {
        mutableIntStateOf(
            targetedCachedEp?.seasonNumber ?: cachedSeries?.seasons?.firstOrNull()?.seasonNumber ?: 1
        )
    }

    var seasonDropdownExpanded by remember { mutableStateOf(false) }

    // Watched episode IDs for this user
    var watchedEpisodeIds by remember(userEmail) {
        mutableStateOf(DesktopWatchHistoryManager.getWatchedEpisodeIds(userEmail))
    }

    // 2. Stale-While-Revalidate Ingestion
    LaunchedEffect(seriesItem.id, userEmail, initialEpisodeId) {
        if (seriesDetails == null) {
            isLoading = true
            val scanner = DesktopDriveScanner(accessToken)
            val loaded = scanner.loadSeriesDetails(seriesItem.id, seriesItem.title)
            DesktopSeriesCache.save(userEmail, loaded)
            seriesDetails = loaded

            val allFreshEpisodes = loaded.seasons.flatMap { it.episodes }
            // Validate initialEpisodeId: ensure it actually belongs to this series
            val targetedFreshEp = if (!initialEpisodeId.isNullOrBlank()) {
                allFreshEpisodes.find { it.id == initialEpisodeId }
            } else {
                val resumeState = DesktopWatchHistoryManager.getSeriesResumeState(userEmail, seriesItem.id)
                if (resumeState != null) {
                    allFreshEpisodes.find { it.id == resumeState.lastEpisodeId }
                } else null
            }

            if (targetedFreshEp != null) {
                browsingSeasonNumber = targetedFreshEp.seasonNumber
                playingEpisode = targetedFreshEp
            } else {
                val initialSeason = loaded.seasons.firstOrNull()
                if (initialSeason != null) {
                    browsingSeasonNumber = initialSeason.seasonNumber
                    playingEpisode = initialSeason.episodes.firstOrNull()
                }
            }
            isLoading = false
        } else {
            try {
                val scanner = DesktopDriveScanner(accessToken)
                val fresh = scanner.loadSeriesDetails(seriesItem.id, seriesItem.title)
                if (fresh.seasons.isNotEmpty()) {
                    DesktopSeriesCache.save(userEmail, fresh)
                    seriesDetails = fresh
                    val allFreshEpisodes = fresh.seasons.flatMap { it.episodes }
                    if (playingEpisode == null || allFreshEpisodes.none { it.id == playingEpisode?.id }) {
                        val targetedFreshEp = if (!initialEpisodeId.isNullOrBlank()) {
                            allFreshEpisodes.find { it.id == initialEpisodeId }
                        } else null

                        if (targetedFreshEp != null) {
                            browsingSeasonNumber = targetedFreshEp.seasonNumber
                            playingEpisode = targetedFreshEp
                        } else {
                            val initialSeason = fresh.seasons.firstOrNull()
                            if (initialSeason != null) {
                                browsingSeasonNumber = initialSeason.seasonNumber
                                playingEpisode = initialSeason.episodes.firstOrNull()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("[DesktopSeriesDetailScreen] Background refresh failed for ${seriesItem.title}: ${e.message}")
            }
        }
    }

    if (isLoading || seriesDetails == null) {
        Box(
            modifier = modifier.fillMaxSize().background(Color(0xFF07090E)),
            contentAlignment = Alignment.Center
        ) {
            PacedLoadingSpinner(
                color = Color(0xFF00E5FF),
                modifier = Modifier.size(46.dp),
                strokeWidth = 3.5.dp
            )
        }
        return
    }

    val series = seriesDetails!!

    val currentSeason = series.seasons.find { it.seasonNumber == browsingSeasonNumber }
        ?: series.seasons.firstOrNull() ?: DesktopSeason(1, "Season 1", emptyList())
    val seasonEpisodes = currentSeason.episodes

    val allEpisodes = remember(series.seasons) {
        series.seasons.flatMap { it.episodes }
    }
    val globalEpIndex = allEpisodes.indexOfFirst { it.id == playingEpisode?.id }
    val hasPrevGlobal = globalEpIndex > 0
    val hasNextGlobal = globalEpIndex in 0 until (allEpisodes.size - 1)
    val nextEpisode = if (hasNextGlobal) allEpisodes[globalEpIndex + 1] else null
    val prevEpisode = if (hasPrevGlobal) allEpisodes[globalEpIndex - 1] else null

    val activeStreamUrl = remember(playingEpisode?.id, accessToken) {
        playingEpisode?.let { ep ->
            LocalStreamServer.getStreamUrl(ep.id, accessToken)
        } ?: ""
    }

    val player = remember(activeStreamUrl) {
        if (activeStreamUrl.isNotBlank()) DirectMemoryVideoPlayer(activeStreamUrl) else null
    }

    val activeEpisode = playingEpisode

    val saveActiveEpisodeProgress = {
        val ep = activeEpisode
        val p = player
        if (ep != null && p != null && userEmail.isNotBlank()) {
            val curPos = p.currentTimeMs
            val totalDur = if (ep.durationMs > 0L) ep.durationMs else p.totalDurationMs
            if (curPos > 0L) {
                coroutineScope.launch(Dispatchers.IO) {
                    com.clotube.desktop.data.DesktopWatchHistoryManager.recordPlaybackProgress(
                        email = userEmail,
                        fileId = ep.id,
                        title = ep.title,
                        positionMs = curPos,
                        durationMs = totalDur,
                        isSeries = true,
                        seriesId = seriesItem.id,
                        seasonNumber = ep.seasonNumber,
                        episodeNumber = ep.episodeNumber
                    )
                    com.clotube.desktop.data.DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken)
                }
            }
        }
    }

    // Periodic Progress Recorder (Records every 5 seconds during active series playback)
    LaunchedEffect(player?.isPlaying, activeEpisode?.id, userEmail) {
        if (player?.isPlaying == true && activeEpisode != null && userEmail.isNotBlank()) {
            while (isActive) {
                delay(5000L)
                saveActiveEpisodeProgress()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            saveActiveEpisodeProgress()
        }
    }

    DisposableEffect(player) {
        onDispose {
            saveActiveEpisodeProgress()
            player?.release()
        }
    }

    // Track which episode has already consumed its initial seek so fullscreen toggles never re-seek
    var resumedEpisodeId by remember(seriesItem.id) { mutableStateOf<String?>(null) }

    val savedPos = remember(playingEpisode?.id, userEmail) {
        playingEpisode?.let { ep ->
            DesktopWatchHistoryManager.getPlaybackPosition(userEmail, ep.id)
        } ?: 0L
    }

    // Only supply savedPos on the very first mount of this episode
    val effectiveInitialSeekMs = if (playingEpisode != null && resumedEpisodeId != playingEpisode?.id) {
        savedPos
    } else {
        0L
    }

    // Reset latch when episode changes so the next episode can resume
    LaunchedEffect(playingEpisode?.id) {
        if (resumedEpisodeId != playingEpisode?.id) {
            // Keep until first seek applied
        }
    }

    // Fullscreen Cinema Overlay
    if (isFullscreen && player != null) {
        DesktopSeriesPlayerScreen(
            player = player,
            seriesTitle = series.title,
            episodeTitle = playingEpisode?.title ?: "",
            seasonNumber = playingEpisode?.seasonNumber ?: 1,
            episodeNumber = playingEpisode?.episodeNumber ?: 1,
            nextEpisodeTitle = nextEpisode?.title ?: "",
            nextSeasonNumber = nextEpisode?.seasonNumber ?: 1,
            nextEpisodeNumber = nextEpisode?.episodeNumber ?: 1,
            initialSeekMs = effectiveInitialSeekMs,
            onInitialSeekApplied = {
                resumedEpisodeId = playingEpisode?.id
            },
            onProgressUpdate = { currentPos ->
                playingEpisode?.let { ep ->
                    coroutineScope.launch {
                        DesktopWatchHistoryManager.recordPlaybackProgress(
                            email = userEmail,
                            fileId = ep.id,
                            title = ep.title,
                            positionMs = currentPos,
                            durationMs = ep.durationMs,
                            isSeries = true,
                            seriesId = series.id,
                            seasonNumber = ep.seasonNumber,
                            episodeNumber = ep.episodeNumber
                        )
                        // Quiet background sync to Drive
                        DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken)
                    }
                }
            },
            isFullscreen = true,
            onToggleFullscreen = onToggleFullscreen,
            onNavigateBack = {
                saveActiveEpisodeProgress()
                onToggleFullscreen()
            },
            onNextEpisode = if (hasNextGlobal && nextEpisode != null) {
                {
                    saveActiveEpisodeProgress()
                    playingEpisode?.let { currentEp ->
                        watchedEpisodeIds = watchedEpisodeIds + currentEp.id
                        coroutineScope.launch {
                            DesktopWatchHistoryManager.setEpisodeWatched(
                                email = userEmail,
                                fileId = currentEp.id,
                                isWatched = true,
                                seriesId = series.id,
                                seasonNumber = currentEp.seasonNumber,
                                episodeNumber = currentEp.episodeNumber,
                                title = currentEp.title,
                                durationMs = currentEp.durationMs
                            )
                            // Advance series resume pointer to next episode
                            DesktopWatchHistoryManager.recordPlaybackProgress(
                                email = userEmail,
                                fileId = nextEpisode.id,
                                title = nextEpisode.title,
                                positionMs = 0L,
                                durationMs = nextEpisode.durationMs,
                                isSeries = true,
                                seriesId = series.id,
                                seasonNumber = nextEpisode.seasonNumber,
                                episodeNumber = nextEpisode.episodeNumber
                            )
                            DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken, force = true)
                        }
                    }
                    resumedEpisodeId = null // Allow next episode to resume
                    playingEpisode = nextEpisode
                    if (browsingSeasonNumber != nextEpisode.seasonNumber) {
                        browsingSeasonNumber = nextEpisode.seasonNumber
                    }
                }
            } else null,
            onPreviousEpisode = if (hasPrevGlobal && prevEpisode != null) {
                {
                    saveActiveEpisodeProgress()
                    resumedEpisodeId = null // Allow prev episode to resume
                    playingEpisode = prevEpisode
                    if (browsingSeasonNumber != prevEpisode.seasonNumber) {
                        browsingSeasonNumber = prevEpisode.seasonNumber
                    }
                }
            } else null,
            hasNextEpisode = hasNextGlobal,
            hasPreviousEpisode = hasPrevGlobal,
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090E)),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Breadcrumb Action
        item(key = "breadcrumb") {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        saveActiveEpisodeProgress()
                        onBack()
                    }
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
                    text = "Back to Series",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 2. Series Header Block
        item(key = "series_header_block") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "SERIES",
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

                    val seasonsCountText = "${series.seasons.size} ${if (series.seasons.size == 1) "Season" else "Seasons"}"
                    val metaTokens = listOf(
                        series.releaseYear,
                        series.ageRating,
                        seasonsCountText,
                        series.genres
                    ).filter { it.isNotBlank() }

                    Text(
                        text = metaTokens.joinToString("  •  "),
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

        // 3. MAIN SPLIT VIEW
        item(key = "main_split_view") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(580.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                // LEFT COLUMN: Viewport + Active Episode Info
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
                                episodeTitle = playingEpisode?.title ?: "",
                                seasonNumber = playingEpisode?.seasonNumber ?: 1,
                                episodeNumber = playingEpisode?.episodeNumber ?: 1,
                                nextEpisodeTitle = nextEpisode?.title ?: "",
                                nextSeasonNumber = nextEpisode?.seasonNumber ?: 1,
                                nextEpisodeNumber = nextEpisode?.episodeNumber ?: 1,
                                initialSeekMs = effectiveInitialSeekMs,
                                onInitialSeekApplied = {
                                    resumedEpisodeId = playingEpisode?.id
                                },
                                onProgressUpdate = { currentPos ->
                                    playingEpisode?.let { ep ->
                                        coroutineScope.launch {
                                            DesktopWatchHistoryManager.recordPlaybackProgress(
                                                email = userEmail,
                                                fileId = ep.id,
                                                title = ep.title,
                                                positionMs = currentPos,
                                                durationMs = ep.durationMs,
                                                isSeries = true,
                                                seriesId = series.id,
                                                seasonNumber = ep.seasonNumber,
                                                episodeNumber = ep.episodeNumber
                                            )
                                            // Quiet background sync to Drive
                                            DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken)
                                        }
                                    }
                                },
                                isFullscreen = false,
                                onToggleFullscreen = onToggleFullscreen,
                                onNavigateBack = {
                                    saveActiveEpisodeProgress()
                                    onBack()
                                },
                                onNextEpisode = if (hasNextGlobal && nextEpisode != null) {
                                    {
                                        saveActiveEpisodeProgress()
                                        playingEpisode?.let { currentEp ->
                                            watchedEpisodeIds = watchedEpisodeIds + currentEp.id
                                            coroutineScope.launch {
                                                DesktopWatchHistoryManager.setEpisodeWatched(
                                                    email = userEmail,
                                                    fileId = currentEp.id,
                                                    isWatched = true,
                                                    seriesId = series.id,
                                                    seasonNumber = currentEp.seasonNumber,
                                                    episodeNumber = currentEp.episodeNumber,
                                                    title = currentEp.title,
                                                    durationMs = currentEp.durationMs
                                                )
                                                // Advance series resume pointer to next episode
                                                DesktopWatchHistoryManager.recordPlaybackProgress(
                                                    email = userEmail,
                                                    fileId = nextEpisode.id,
                                                    title = nextEpisode.title,
                                                    positionMs = 0L,
                                                    durationMs = nextEpisode.durationMs,
                                                    isSeries = true,
                                                    seriesId = series.id,
                                                    seasonNumber = nextEpisode.seasonNumber,
                                                    episodeNumber = nextEpisode.episodeNumber
                                                )
                                                DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken, force = true)
                                            }
                                        }
                                        resumedEpisodeId = null
                                        playingEpisode = nextEpisode
                                        if (browsingSeasonNumber != nextEpisode.seasonNumber) {
                                            browsingSeasonNumber = nextEpisode.seasonNumber
                                        }
                                    }
                                } else null,
                                onPreviousEpisode = if (hasPrevGlobal && prevEpisode != null) {
                                    {
                                        saveActiveEpisodeProgress()
                                        resumedEpisodeId = null
                                        playingEpisode = prevEpisode
                                        if (browsingSeasonNumber != prevEpisode.seasonNumber) {
                                            browsingSeasonNumber = prevEpisode.seasonNumber
                                        }
                                    }
                                } else null,
                                hasNextEpisode = hasNextGlobal,
                                hasPreviousEpisode = hasPrevGlobal,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                PacedLoadingSpinner(
                                    color = Color(0xFF00E5FF),
                                    modifier = Modifier.size(46.dp),
                                    strokeWidth = 3.5.dp
                                )
                            }
                        }
                    }

                    playingEpisode?.let { ep ->
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
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = ep.durationFormatted,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 13.sp
                                )
                            }
                            if (ep.synopsis.isNotBlank()) {
                                Text(
                                    text = ep.synopsis,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // RIGHT COLUMN: Interactive Episode List
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
                                    text = "${seasonEpisodes.size} episodes",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            // Dynamic Season Selector Dropdown
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
                                    modifier = Modifier
                                        .background(Color(0xFF131824))
                                        .border(1.dp, Color(0xFF263346), RoundedCornerShape(8.dp))
                                ) {
                                    series.seasons.forEach { season ->
                                        val isCurrent = season.seasonNumber == browsingSeasonNumber
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "${season.title} (${season.episodes.size} eps)",
                                                    color = if (isCurrent) Color(0xFF00E5FF) else Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                browsingSeasonNumber = season.seasonNumber
                                                seasonDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Subheader with Watched Progress & "Mark All Watched" Toggle
                        val seasonWatchedCount = seasonEpisodes.count { watchedEpisodeIds.contains(it.id) }
                        val areAllSeasonEpisodesWatched = seasonEpisodes.isNotEmpty() && seasonWatchedCount == seasonEpisodes.size

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
                                        .background(if (areAllSeasonEpisodesWatched) Color(0xFF00E5FF) else Color(0xFF38BDF8))
                                )
                                Text(
                                    text = if (seasonEpisodes.isEmpty()) {
                                        "No episodes"
                                    } else if (seasonWatchedCount > 0) {
                                        "$seasonWatchedCount of ${seasonEpisodes.size} watched"
                                    } else {
                                        "${seasonEpisodes.size} episodes ready"
                                    },
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = if (areAllSeasonEpisodesWatched) "Mark all unwatched" else "Mark all watched",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable {
                                        val markWatched = !areAllSeasonEpisodesWatched
                                        val currentSeasonIds = seasonEpisodes.map { it.id }
                                        watchedEpisodeIds = if (markWatched) {
                                            watchedEpisodeIds + currentSeasonIds
                                        } else {
                                            watchedEpisodeIds - currentSeasonIds.toSet()
                                        }
                                        coroutineScope.launch {
                                            DesktopWatchHistoryManager.setSeasonEpisodesWatched(
                                                email = userEmail,
                                                episodes = seasonEpisodes,
                                                isWatched = markWatched,
                                                seriesId = series.id
                                            )
                                            DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken, force = true)
                                        }
                                    }
                                    .pointerHoverIcon(PointerIcon.Hand)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = Color(0xFF18202F), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Interactive Scrollable List of Episodes
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(seasonEpisodes, key = { it.id }) { ep ->
                                val isCurrentlyPlaying = playingEpisode?.id == ep.id
                                val isWatched = watchedEpisodeIds.contains(ep.id)
                                val thumb = rememberDriveThumbnail(ep.thumbnailUrl, accessToken = accessToken, userEmail = userEmail)

                                EpisodeListItemCard(
                                    episode = ep,
                                    thumbnail = thumb,
                                    isCurrentlyPlaying = isCurrentlyPlaying,
                                    isPlayerPlaying = player?.isPlaying == true,
                                    isWatched = isWatched,
                                    onSelectEpisode = {
                                        if (activeEpisode?.id != ep.id) {
                                            saveActiveEpisodeProgress()
                                            resumedEpisodeId = null
                                            playingEpisode = ep
                                        }
                                    },
                                    onToggleWatched = {
                                        val markWatched = !isWatched
                                        watchedEpisodeIds = if (markWatched) {
                                            watchedEpisodeIds + ep.id
                                        } else {
                                            watchedEpisodeIds - ep.id
                                        }
                                        coroutineScope.launch {
                                            DesktopWatchHistoryManager.setEpisodeWatched(
                                                email = userEmail,
                                                fileId = ep.id,
                                                isWatched = markWatched,
                                                seriesId = series.id,
                                                seasonNumber = ep.seasonNumber,
                                                episodeNumber = ep.episodeNumber,
                                                title = ep.title,
                                                durationMs = ep.durationMs
                                            )
                                            DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken, force = true)
                                        }
                                    }
                                )
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
                                text = "Progress saved to profile",
                                color = Color(0xFF64748B),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        // 4. "ABOUT THE SERIES" SECTION
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

                    if (series.tagline.isNotBlank()) {
                        Text(
                            text = series.tagline,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = series.synopsis.ifBlank { "No overview available for this series." },
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }

                Column(
                    modifier = Modifier.weight(0.35f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (series.creators.isNotBlank()) {
                        MetadataLine(label = "Creators", value = series.creators)
                    }
                    if (series.cast.isNotBlank()) {
                        MetadataLine(label = "Cast", value = series.cast)
                    }
                    if (series.genres.isNotBlank()) {
                        MetadataLine(label = "Genres", value = series.genres)
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeListItemCard(
    episode: DesktopEpisode,
    thumbnail: ImageBitmap?,
    isCurrentlyPlaying: Boolean,
    isPlayerPlaying: Boolean,
    isWatched: Boolean,
    onSelectEpisode: () -> Unit,
    onToggleWatched: () -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onSelectEpisode() }
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> isHovered = true
                            PointerEventType.Exit -> isHovered = false
                        }
                    }
                }
            },
        shape = RoundedCornerShape(10.dp),
        color = when {
            isCurrentlyPlaying -> Color(0xFF121B2B)
            isHovered -> Color(0xFF151D2A)
            else -> Color(0xFF0F141F)
        },
        border = when {
            isCurrentlyPlaying -> BorderStroke(1.dp, Color(0xFF2563EB))
            isHovered -> BorderStroke(1.dp, Color(0xFF2A3B54))
            else -> BorderStroke(1.dp, Color(0xFF18202F))
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 16:9 Thumbnail Box
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF161E2E)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = episode.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                when {
                    isCurrentlyPlaying -> {
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
                    }
                    isHovered -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E5FF).copy(alpha = 0.9f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = Color(0xFF090D14),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    isWatched -> {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0F241F).copy(alpha = 0.95f))
                                .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.7f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Watched",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(0xCC000000)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${episode.episodeNumber}",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Episode Title & Runtime
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "EPISODE ${episode.episodeNumber}",
                    color = Color(0xFF38BDF8),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = episode.title,
                    color = if (isWatched && !isCurrentlyPlaying) Color(0xFFCBD5E1).copy(alpha = 0.75f) else Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${episode.durationFormatted} • Direct Stream",
                    color = Color(0xFF64748B),
                    fontSize = 10.sp
                )
            }

            // Right End: Animated Soundwave or Quick Watched Toggle Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isCurrentlyPlaying) {
                    AnimatedEqualizerBars(
                        isPlaying = isPlayerPlaying,
                        barColor = Color(0xFF00E5FF)
                    )
                }

                if (isHovered || isWatched) {
                    IconButton(
                        onClick = onToggleWatched,
                        modifier = Modifier
                            .size(24.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            imageVector = if (isWatched) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = if (isWatched) "Mark Unwatched" else "Mark Watched",
                            tint = if (isWatched) Color(0xFF00E5FF) else Color(0xFF64748B),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedEqualizerBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = Color(0xFF00E5FF)
) {
    var b1 by remember { mutableFloatStateOf(0.25f) }
    var b2 by remember { mutableFloatStateOf(0.85f) }
    var b3 by remember { mutableFloatStateOf(0.35f) }
    var b4 by remember { mutableFloatStateOf(0.70f) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            b1 = 0.25f; b2 = 0.25f; b3 = 0.25f; b4 = 0.25f
            return@LaunchedEffect
        }
        val startTime = System.currentTimeMillis()
        while (true) {
            if (!isAppWindowActiveAndVisible()) {
                delay(250L)
                continue
            }
            val t = System.currentTimeMillis() - startTime
            b1 = 0.25f + ((kotlin.math.sin(t.toDouble() / 210.0) + 1.0) / 2.0 * 0.75f).toFloat()
            b2 = 0.20f + ((kotlin.math.sin(t.toDouble() / 175.0 + 1.0) + 1.0) / 2.0 * 0.65f).toFloat()
            b3 = 0.35f + ((kotlin.math.sin(t.toDouble() / 240.0 + 2.0) + 1.0) / 2.0 * 0.60f).toFloat()
            b4 = 0.25f + ((kotlin.math.sin(t.toDouble() / 195.0 + 0.5) + 1.0) / 2.0 * 0.70f).toFloat()
            delay(20L)
        }
    }

    val heights = if (isPlaying) listOf(b1, b2, b3, b4) else listOf(0.25f, 0.25f, 0.25f, 0.25f)

    Row(
        modifier = modifier.size(width = 16.dp, height = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        heights.forEach { fraction ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction.coerceIn(0.15f, 1f))
                    .clip(RoundedCornerShape(1.dp))
                    .background(barColor)
            )
        }
    }
}

@Composable
private fun rememberDriveThumbnail(url: String?, accessToken: String = "", userEmail: String = ""): ImageBitmap? {
    return rememberThumbnailBitmap(primaryUrl = url, accessToken = accessToken, userEmail = userEmail)
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFF64748B), fontSize = 12.sp)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            color = Color(0xFFCBD5E1),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
