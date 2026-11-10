package com.clotube.desktop.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import com.clotube.desktop.data.DesktopAppSettings
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
import com.clotube.desktop.ui.components.ThumbnailSize
import com.clotube.desktop.ui.components.PacedEqualizerBars
import com.clotube.desktop.ui.components.PacedLoadingSpinner
import com.clotube.desktop.ui.motion.FluidMotionKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean

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

    // 1. Frame-1 Cache Read Scoped to User Profile
    val cachedSeries = remember(seriesItem.id, userEmail) { 
        DesktopSeriesCache.get(userEmail, seriesItem.id) 
    }
    var seriesDetails by remember(seriesItem.id, userEmail) { mutableStateOf<DesktopSeries?>(cachedSeries) }
    var isLoading by remember(seriesItem.id, userEmail) { mutableStateOf(cachedSeries == null) }

    val initialResume = remember(seriesItem.id, userEmail) {
        DesktopWatchHistoryManager.getSeriesResumeState(userEmail, seriesItem.id)
    }
    val targetEpisodeId = initialEpisodeId ?: initialResume?.lastEpisodeId

    val initialTargetEpisode = remember(seriesItem.id, userEmail, targetEpisodeId) {
        if (!targetEpisodeId.isNullOrBlank() && cachedSeries != null) {
            cachedSeries.seasons.flatMap { it.episodes }.find { it.id == targetEpisodeId }
                ?: cachedSeries.seasons.firstOrNull()?.episodes?.firstOrNull()
        } else {
            cachedSeries?.seasons?.firstOrNull()?.episodes?.firstOrNull()
        }
    }
    val initialTargetSeasonNumber = remember(seriesItem.id, userEmail, initialTargetEpisode) {
        initialTargetEpisode?.seasonNumber 
            ?: cachedSeries?.seasons?.find { s -> s.episodes.any { it.id == initialTargetEpisode?.id } }?.seasonNumber
            ?: cachedSeries?.seasons?.firstOrNull()?.seasonNumber ?: 1
    }

    var playingEpisode by remember(seriesItem.id, userEmail) {
        mutableStateOf<DesktopEpisode?>(initialTargetEpisode)
    }
    val browsingSeasonState = remember(seriesItem.id, userEmail) {
        mutableIntStateOf(initialTargetSeasonNumber)
    }
    var browsingSeasonNumber by browsingSeasonState
    var selectedSeasonNumber by browsingSeasonState

    var seasonDropdownExpanded by remember { mutableStateOf(false) }

    val historyRev = DesktopWatchHistoryManager.historyRevision.intValue
    var watchedEpisodeIds by remember(userEmail, historyRev) {
        mutableStateOf(DesktopWatchHistoryManager.getWatchedEpisodeIds(userEmail))
    }

    // 2. Off-Thread Drive Scanning (Runs strictly on Dispatchers.IO to never block the EDT)
    LaunchedEffect(seriesItem.id, userEmail) {
        yield()
        if (seriesDetails == null) {
            isLoading = true
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val scanner = DesktopDriveScanner(accessToken)
                    val result = scanner.loadSeriesDetails(seriesItem.id, seriesItem.title)
                    DesktopSeriesCache.save(userEmail, result)
                    result
                } catch (e: Exception) {
                    System.err.println("[DesktopSeriesDetailScreen] Failed to scan series: ${e.message}")
                    null
                }
            }

            if (loaded != null) {
                withContext(Dispatchers.Main) {
                    seriesDetails = loaded

                    // Resume targeted episode if navigating from Continue Watching or resume state
                    val latestResume = DesktopWatchHistoryManager.getSeriesResumeState(userEmail, seriesItem.id)
                    val effectiveTargetId = initialEpisodeId ?: latestResume?.lastEpisodeId
                    val matchedSeason = if (!effectiveTargetId.isNullOrBlank()) {
                        loaded.seasons.find { s -> s.episodes.any { it.id == effectiveTargetId } }
                    } else null
                    val matchedEp = matchedSeason?.episodes?.find { it.id == effectiveTargetId }

                    if (matchedSeason != null && matchedEp != null) {
                        browsingSeasonNumber = matchedSeason.seasonNumber
                        playingEpisode = matchedEp
                    } else {
                        val initialSeason = loaded.seasons.firstOrNull()
                        if (initialSeason != null) {
                            browsingSeasonNumber = initialSeason.seasonNumber
                            playingEpisode = initialSeason.episodes.firstOrNull()
                        }
                    }
                }
            }
            isLoading = false
        } else {
            // Background quiet revalidation: Only update state if Drive data actually changed
            withContext(Dispatchers.IO) {
                try {
                    val scanner = DesktopDriveScanner(accessToken)
                    val fresh = scanner.loadSeriesDetails(seriesItem.id, seriesItem.title)
                    if (fresh.seasons.isNotEmpty()) {
                        DesktopSeriesCache.save(userEmail, fresh)

                        val currentCount = seriesDetails?.seasons?.sumOf { it.episodes.size } ?: 0
                        val freshCount = fresh.seasons.sumOf { it.episodes.size }
                        val currentIds = seriesDetails?.seasons?.flatMap { it.episodes }?.map { it.id } ?: emptyList()
                        val freshIds = fresh.seasons.flatMap { it.episodes }.map { it.id }

                        // Only assign on Main thread if there is an actual difference to avoid wiping scroll state
                        if (currentCount != freshCount || currentIds != freshIds) {
                            withContext(Dispatchers.Main) {
                                seriesDetails = fresh
                            }
                        }
                    }
                } catch (e: Exception) {
                    System.err.println("[DesktopSeriesDetailScreen] Background refresh error: ${e.message}")
                }
            }
        }
    }

    // 3. IMMEDIATE LOADING SPINNER (Renders on Frame 1 while IO thread scans Drive)
    if (isLoading || seriesDetails == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF06080C)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF38BDF8),
                    strokeWidth = 3.5.dp,
                    modifier = Modifier.size(46.dp)
                )
                Text(
                    text = "Indexing series information from google drive takes time for the first time",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        return
    }

    val series = seriesDetails!!

    val currentSeason = series.seasons.find { it.seasonNumber == selectedSeasonNumber }
        ?: series.seasons.firstOrNull() ?: DesktopSeason(1, "Season 1", emptyList())
    val displayedEpisodes = currentSeason.episodes
    val seasonEpisodes = displayedEpisodes

    val activeEpisodeIndex = displayedEpisodes.indexOfFirst { it.id == playingEpisode?.id }.coerceAtLeast(0)
    val currentEpisode = playingEpisode ?: displayedEpisodes.firstOrNull() ?: DesktopEpisode(
        id = "",
        seriesId = series.id,
        seriesTitle = series.title,
        seasonNumber = 1,
        episodeNumber = 1,
        title = ""
    )

    // Remember scroll state for the episode playlist LazyColumn
    val episodeListState = rememberLazyListState()
    val hasAutoScrolledToActive = remember(seriesItem.id, selectedSeasonNumber) { mutableStateOf(false) }

    // Auto-scroll to active episode once on load, never fighting manual user scrolling
    LaunchedEffect(playingEpisode?.id, selectedSeasonNumber, seasonEpisodes.size) {
        if (!hasAutoScrolledToActive.value && seasonEpisodes.isNotEmpty() && !episodeListState.isScrollInProgress) {
            val targetIdx = seasonEpisodes.indexOfFirst { it.id == playingEpisode?.id }
            if (targetIdx > 0) {
                hasAutoScrolledToActive.value = true
                episodeListState.scrollToItem(targetIdx)
            }
        }
    }

    val episodeListScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = Offset.Zero
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Absorb all remaining boundary delta so the outer page never scrolls
                return available
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return available
            }
        }
    }

    // Synchronize season dropdown if filtered by season
    LaunchedEffect(currentEpisode.seasonNumber) {
        if (selectedSeasonNumber != currentEpisode.seasonNumber) {
            selectedSeasonNumber = currentEpisode.seasonNumber
        }
    }

    val allEpisodes = remember(series.seasons) {
        series.seasons.flatMap { it.episodes }
    }
    val globalEpIndex = allEpisodes.indexOfFirst { it.id == playingEpisode?.id }
    val hasPrevGlobal = globalEpIndex > 0
    val hasNextGlobal = globalEpIndex in 0 until (allEpisodes.size - 1)
    val nextEpisode = if (hasNextGlobal) allEpisodes[globalEpIndex + 1] else null
    val prevEpisode = if (hasPrevGlobal) allEpisodes[globalEpIndex - 1] else null

    val activeEpisode = playingEpisode

    val activeStreamUrl = remember(activeEpisode?.id, accessToken) {
        activeEpisode?.let { ep ->
            if (ep.streamUrl.isNotBlank()) ep.streamUrl else LocalStreamServer.getStreamUrl(ep.id, accessToken)
        } ?: ""
    }

    val subtitleFontSize = DesktopAppSettings.state.subtitleFontSizeSp
    val cinemaColor = DesktopAppSettings.state.cinemaColorEnhancement
    val shouldResume = DesktopAppSettings.state.resumePlayback

    // 1. Resolve saved position for the active episode
    val savedPos = remember(activeEpisode?.id, userEmail) {
        val ep = activeEpisode
        if (userEmail.isNotBlank() && ep != null) {
            com.clotube.desktop.data.DesktopWatchHistoryManager.getPlaybackPosition(userEmail, ep.id)
        } else 0L
    }

    val initialSeekTarget = if (shouldResume && savedPos > 1500L) savedPos else 0L

    val player = remember(activeEpisode?.id, activeStreamUrl, subtitleFontSize, cinemaColor) {
        if (activeStreamUrl.isNotBlank()) {
            DirectMemoryVideoPlayer(
                mediaUrl = activeStreamUrl,
                initialSeekMs = initialSeekTarget
            )
        } else null
    }

    val hasAppliedResume = remember(playingEpisode?.id) { AtomicBoolean(false) }

    LaunchedEffect(playingEpisode?.id, player?.isFirstFrameRendered) {
        val p = player ?: return@LaunchedEffect

        // MID-STREAM GUARD: If the player is ALREADY playing (e.g. during fullscreen toggle), NEVER restart or seek!
        if (p.currentTimeMs >= 1500L) {
            hasAppliedResume.set(true)
            return@LaunchedEffect
        }

        val shouldResume = DesktopAppSettings.state.resumePlayback
        if (shouldResume && savedPos > 1500L && hasAppliedResume.compareAndSet(false, true)) {
            var attempts = 0
            while (attempts < 30 && (!p.isFirstFrameRendered || p.totalDurationMs <= 0L)) {
                delay(100L)
                attempts++
            }
            if (p.currentTimeMs < 1500L) {
                p.seekToTime(savedPos)
                println("[SeriesPlayer] Applied initial resume at ${savedPos}ms")
            }
        }
    }

    val progressSaveScope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()) }

    val currentPlayingEpisode by rememberUpdatedState(playingEpisode)
    val currentPlayer by rememberUpdatedState(player)

    // 2. Periodic Progress Recording
    val saveActiveEpisodeProgress = {
        val ep = currentPlayingEpisode
        val p = currentPlayer
        if (ep != null && userEmail.isNotBlank()) {
            val curPos = p?.currentTimeMs ?: 0L
            val totalDur = if (ep.durationMs > 0L) ep.durationMs else (p?.totalDurationMs ?: 0L)

            // Guard write: only persist meaningful progress (>= 2000ms) or when near completion (>= 90%)
            if (curPos >= 2000L || (totalDur > 0L && curPos >= totalDur * 0.90f)) {
                // Record progress synchronously to in-memory/disk store
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

                // Asynchronously sync to Google Drive
                if (accessToken.isNotBlank()) {
                    progressSaveScope.launch {
                        com.clotube.desktop.data.DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken)
                    }
                }
            }
        }
    }
    val saveSeriesProgress = saveActiveEpisodeProgress

    LaunchedEffect(player?.isPlaying, activeEpisode?.id, userEmail) {
        if (player?.isPlaying == true && activeEpisode != null && userEmail.isNotBlank()) {
            while (isActive) {
                delay(5000L)
                saveActiveEpisodeProgress()
            }
        } else if (player?.isPlaying == false && activeEpisode != null && userEmail.isNotBlank()) {
            saveActiveEpisodeProgress()
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
            initialSeekMs = 0L,
            onInitialSeekApplied = null,
            onProgressUpdate = { currentPos ->
                playingEpisode?.let { ep ->
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
                    if (accessToken.isNotBlank()) {
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken)
                        }
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
                    playingEpisode = nextEpisode
                    if (selectedSeasonNumber != nextEpisode.seasonNumber) {
                        selectedSeasonNumber = nextEpisode.seasonNumber
                    }
                }
            } else null,
            onPreviousEpisode = if (hasPrevGlobal && prevEpisode != null) {
                {
                    saveActiveEpisodeProgress()
                    playingEpisode = prevEpisode
                    if (selectedSeasonNumber != prevEpisode.seasonNumber) {
                        selectedSeasonNumber = prevEpisode.seasonNumber
                    }
                }
            } else null,
            hasNextEpisode = hasNextGlobal,
            hasPreviousEpisode = hasPrevGlobal,
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    // Top-lit directional gradient borders simulating ambient overhead lighting
    val topLitPanelBorder = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF243247), // Catches overhead light
                Color(0xFF101724)  // Fades into shadow at the base
            )
        )
    }

    val topLitCardBorderDefault = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF1E2838),
                Color(0xFF101622)
            )
        )
    }

    val topLitCardBorderHover = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0x55FFFFFF),
                Color(0x14FFFFFF)
            )
        )
    }

    val topLitCardBorderSelected = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF38BDF8),
                Color(0xFF0284C7)
            )
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF06080C)),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Breadcrumb Action
        item(key = "breadcrumb") {
            val backInteraction = remember { MutableInteractionSource() }
            val isBackHovered by backInteraction.collectIsHoveredAsState()

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = backInteraction,
                        indication = null
                    ) {
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
                    tint = if (isBackHovered) Color.White else Color(0xFF94A3B8),
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Back to Series",
                    color = if (isBackHovered) Color.White else Color(0xFF94A3B8),
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
                        text = "CLOTUBE ORIGINAL SERIES",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp
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

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        metaTokens.forEachIndexed { index, token ->
                            if (index > 0) {
                                Text(
                                    text = "•",
                                    color = Color(0xFF475569),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = token,
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
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
                            .border(1.dp, topLitPanelBorder, RoundedCornerShape(14.dp))
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
                                initialSeekMs = 0L,
                                onInitialSeekApplied = null,
                                onProgressUpdate = { currentPos ->
                                    playingEpisode?.let { ep ->
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
                                        if (accessToken.isNotBlank()) {
                                            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken)
                                            }
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
                                        playingEpisode = nextEpisode
                                        if (selectedSeasonNumber != nextEpisode.seasonNumber) {
                                            selectedSeasonNumber = nextEpisode.seasonNumber
                                        }
                                    }
                                } else null,
                                onPreviousEpisode = if (hasPrevGlobal && prevEpisode != null) {
                                    {
                                        saveActiveEpisodeProgress()
                                        playingEpisode = prevEpisode
                                        if (selectedSeasonNumber != prevEpisode.seasonNumber) {
                                            selectedSeasonNumber = prevEpisode.seasonNumber
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
                                    color = Color(0xFF38BDF8),
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
                                letterSpacing = 1.4.sp
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

                // RIGHT COLUMN: Episode List & Season Dropdown
                Surface(
                    modifier = Modifier
                        .weight(0.33f)
                        .fillMaxHeight()
                        // Absorb nested scroll boundary deltas
                        .nestedScroll(episodeListScrollConnection)
                        // Consume mouse wheel events on Main pass after inner LazyColumn processes them
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    if (event.type == PointerEventType.Scroll) {
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF0A0E17), // Stepped surface elevation
                    border = BorderStroke(1.dp, topLitPanelBorder)
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
                                    letterSpacing = 1.5.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${seasonEpisodes.size} episodes",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.2).sp
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
                                    color = Color(0xFF121622),
                                    border = BorderStroke(1.dp, Color(0xFF1E2838))
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
                                        .background(Color(0xFF0E131C))
                                        .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(10.dp))
                                ) {
                                    series.seasons.forEach { season ->
                                        val isCurrent = season.seasonNumber == selectedSeasonNumber
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "${season.title} (${season.episodes.size} eps)",
                                                    color = if (isCurrent) Color(0xFF60A5FA) else Color(0xFFCBD5E1),
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                selectedSeasonNumber = season.seasonNumber
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
                                        .background(if (areAllSeasonEpisodesWatched) Color(0xFF10B981) else Color(0xFF38BDF8))
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

                        // Scrollable List of Episodes
                        LazyColumn(
                            state = episodeListState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seasonEpisodes, key = { it.id }) { ep ->
                                val isSelected = playingEpisode?.id == ep.id
                                val isWatched = watchedEpisodeIds.contains(ep.id) || DesktopWatchHistoryManager.isEpisodeWatched(userEmail, ep.id)

                                val interactionSource = remember { MutableInteractionSource() }
                                val isHovered by interactionSource.collectIsHoveredAsState()
                                val isPressed by interactionSource.collectIsPressedAsState()

                                val cardScale by animateFloatAsState(
                                    targetValue = when {
                                        isPressed -> 0.985f
                                        isHovered -> 1.015f
                                        else -> 1.0f
                                    },
                                    animationSpec = if (isPressed) com.clotube.desktop.ui.motion.FluidMotionKit.TactilePress
                                                    else com.clotube.desktop.ui.motion.FluidMotionKit.SilkSpring,
                                    label = "episode_card_scale"
                                )

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            scaleX = cardScale
                                            scaleY = cardScale
                                            clip = false
                                        }
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(
                                            interactionSource = interactionSource,
                                            indication = null
                                        ) {
                                            if (playingEpisode?.id != ep.id) {
                                                saveActiveEpisodeProgress()
                                                playingEpisode = ep
                                            }
                                        }
                                        .pointerHoverIcon(PointerIcon.Hand),
                                    shape = RoundedCornerShape(10.dp),
                                    color = when {
                                        isSelected -> Color(0xFF121D2F)
                                        isHovered -> Color(0xFF141C2B)
                                        else -> Color(0xFF0F141F)
                                    },
                                    border = BorderStroke(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        brush = when {
                                            isSelected -> SolidColor(Color(0xFF38BDF8))
                                            isHovered -> Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color(0x14FFFFFF)))
                                            else -> Brush.verticalGradient(listOf(Color(0xFF222B3D), Color(0xFF131824)))
                                        }
                                    )
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
                                            // Compute dedicated Google Drive frame fallback for this specific episode
                                            val episodeDriveThumb = remember(ep.id) {
                                                "https://drive.google.com/thumbnail?id=${ep.id}&sz=w800"
                                            }

                                            // Self-healing guard: reject empty URLs or URLs matching the main series poster
                                            val primaryEpisodeThumb = remember(ep.thumbnailUrl, seriesItem.thumbnailUrl) {
                                                ep.thumbnailUrl?.takeIf { url ->
                                                    url.isNotBlank() && 
                                                    url != seriesItem.thumbnailUrl && 
                                                    url != seriesItem.fallbackThumbnailUrl
                                                }
                                            }

                                            val thumbnail = rememberThumbnailBitmap(
                                                primaryUrl = primaryEpisodeThumb,
                                                fallbackUrl = episodeDriveThumb,
                                                accessToken = accessToken,
                                                userEmail = userEmail,
                                                size = ThumbnailSize.CARD
                                            )

                                            if (thumbnail != null) {
                                                Image(
                                                    bitmap = thumbnail,
                                                    contentDescription = ep.title,
                                                    contentScale = ContentScale.Crop,
                                                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color(0xFF141A26)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Movie,
                                                        contentDescription = null,
                                                        tint = Color(0xFF475569),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }

                                            when {
                                                isSelected -> {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(Color.Black.copy(alpha = 0.45f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(26.dp)
                                                                .clip(CircleShape)
                                                                .background(Color(0xD90A0E17))
                                                                .border(1.dp, Color(0x33FFFFFF), CircleShape),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.PlayArrow,
                                                                contentDescription = "Playing",
                                                                tint = Color.White,
                                                                modifier = Modifier.size(15.dp)
                                                            )
                                                        }
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
                                                                .size(26.dp)
                                                                .clip(CircleShape)
                                                                .background(Color(0xD90A0E17))
                                                                .border(1.dp, Color(0x33FFFFFF), CircleShape),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.PlayArrow,
                                                                contentDescription = null,
                                                                tint = Color.White,
                                                                modifier = Modifier.size(17.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                isWatched -> {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(22.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(0xD90A0E17))
                                                            .border(1.dp, Color(0x26FFFFFF), CircleShape),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Check,
                                                            contentDescription = "Watched",
                                                            tint = Color(0xFF10B981),
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
                                                            text = "${ep.episodeNumber}",
                                                            color = Color(0xFF94A3B8),
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }

                                            // Thumbnail Progress Line
                                            val epProgressMs = remember(ep.id, userEmail) {
                                                DesktopWatchHistoryManager.getPlaybackPosition(userEmail, ep.id)
                                            }
                                            val epProgressFraction = if (ep.durationMs > 0L) {
                                                (epProgressMs.toFloat() / ep.durationMs.toFloat()).coerceIn(0f, 1f)
                                            } else 0f
                                            if (epProgressFraction > 0f && !isWatched) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(2.5.dp)
                                                        .align(Alignment.BottomCenter)
                                                        .background(Color(0x331E2838))
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .fillMaxWidth(epProgressFraction)
                                                            .background(
                                                                Brush.horizontalGradient(
                                                                    listOf(Color(0xFF1D4ED8), Color(0xFF60A5FA))
                                                                )
                                                            )
                                                    )
                                                }
                                            }
                                        }

                                        // Episode Title & Runtime
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "EPISODE ${ep.episodeNumber}",
                                                color = Color(0xFF38BDF8),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.4.sp
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = ep.title,
                                                color = if (isWatched && !isSelected) Color(0xFFCBD5E1).copy(alpha = 0.75f) else Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Text(
                                                    text = ep.durationFormatted,
                                                    color = Color(0xFF94A3B8),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = "•",
                                                    color = Color(0xFF475569),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "Direct Stream",
                                                    color = Color(0xFF64748B),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }

                                        // Right End: Playing Indicator and Watched Toggle Button
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Rounded.GraphicEq,
                                                    contentDescription = "Playing",
                                                    tint = Color(0xFF60A5FA),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            IconButton(
                                                onClick = {
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
                                                },
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .pointerHoverIcon(PointerIcon.Hand)
                                            ) {
                                                if (isWatched) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.CheckCircle,
                                                        contentDescription = "Watched",
                                                        tint = Color(0xFF10B981),
                                                        modifier = Modifier.size(19.dp)
                                                    )
                                                } else {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(17.dp)
                                                            .clip(CircleShape)
                                                            .border(1.5.dp, Color(0xFF222B3D), CircleShape)
                                                    )
                                                }
                                            }
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
                                tint = Color(0xFF64748B),
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
                        letterSpacing = 1.6.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (series.tagline.isNotBlank()) {
                        Text(
                            text = series.tagline,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = series.synopsis.ifBlank { "No overview available for this series." },
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        lineHeight = 21.sp
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
private fun AnimatedEqualizerBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = Color(0xFF38BDF8)
) {
    PacedEqualizerBars(
        isPlaying = isPlaying,
        modifier = modifier,
        barColor = barColor
    )
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
