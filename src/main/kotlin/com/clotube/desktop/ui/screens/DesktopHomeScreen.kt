package com.clotube.desktop.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.clotube.desktop.data.DesktopContinueWatchingItem
import com.clotube.desktop.data.DesktopSeriesCache
import com.clotube.desktop.data.DesktopWatchHistoryManager
import com.clotube.desktop.data.DesktopWatchHistorySync
import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.components.ThumbnailSize
import com.clotube.desktop.ui.components.rememberThumbnailBitmap
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SearchEpisodeMatch(
    val episode: DesktopEpisode,
    val parentSeries: DesktopMediaItem
)

enum class DesktopNavSection(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    MOVIES("Movies", Icons.Rounded.Movie),
    SERIES("Series", Icons.Rounded.Tv),
    RECENTS("Recents", Icons.Rounded.History)
}

// [4.7] Hoisted immutable graphics tokens to prevent 60fps GC allocation churn
private val CardBottomScrimBrush = Brush.verticalGradient(
    listOf(Color.Transparent, Color(0xCC05070B), Color(0xF205070B))
)
private val CardSurfaceColor = Color(0xFF0F1420)
private val CardBorderColor = Color(0xFF1B2333)
private val CardShape = RoundedCornerShape(12.dp)
private val ChipShape = RoundedCornerShape(18.dp)
private val ChipActiveColor = Color(0xFF2563EB)
private val ChipInactiveColor = Color(0xFF111622)
private val ChipBorderColor = Color(0xFF1E2638)
private val BadgeShape = RoundedCornerShape(4.dp)
private val BadgeBackground = Color(0xCC000000)
private val CardProgressTrackColor = Color(0x661E293B)
private val CardProgressFillColor = Color(0xFF00E5FF)

@Composable
fun DesktopHomeScreen(
    userEmail: String,
    userName: String,
    accessToken: String = "",
    userAvatarUrl: String = "",
    onSignOut: () -> Unit,
    activeSection: DesktopNavSection = DesktopNavSection.HOME,
    onNavigateSection: (DesktopNavSection) -> Unit = {},
    featuredItem: DesktopMediaItem? = null,
    continueWatching: List<DesktopMediaItem> = emptyList(),
    movies: List<DesktopMediaItem> = emptyList(),
    series: List<DesktopMediaItem> = emptyList(),
    onMediaClick: (DesktopMediaItem) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onRemoveHistoryItem: (DesktopMediaItem) -> Unit = {},
    isAppFullscreen: Boolean = false,
    onFullscreenToggle: (Boolean) -> Unit = {},
    isFullscreen: Boolean = isAppFullscreen,
    onToggleFullscreen: () -> Unit = { onFullscreenToggle(!isAppFullscreen) },
    onRefresh: () -> Unit = {},
    onRescan: () -> Unit = onRefresh,
    modifier: Modifier = Modifier
) {
    val effectiveFullscreen = isFullscreen || isAppFullscreen
    var currentSection by remember(activeSection) { mutableStateOf(activeSection) }
    var searchQuery by remember { mutableStateOf("") }
    var targetedEpisodeId by remember { mutableStateOf<String?>(null) }
    val searchFocusRequester = remember { FocusRequester() }

    var selectedSeriesForDetail by remember { mutableStateOf<DesktopMediaItem?>(null) }
    var selectedMovieForPlayer by remember { mutableStateOf<DesktopMediaItem?>(null) }
    var watchHistoryRevision by remember { mutableIntStateOf(0) }

    val navigateTo: (DesktopNavSection) -> Unit = { section ->
        selectedSeriesForDetail = null
        selectedMovieForPlayer = null
        targetedEpisodeId = null
        if (effectiveFullscreen) {
            onToggleFullscreen()
        }
        currentSection = section
        onNavigateSection(section)
    }

    val filteredMovies = remember(movies, searchQuery) {
        if (searchQuery.isBlank()) movies
        else movies.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }
    val filteredSeries = remember(series, searchQuery) {
        if (searchQuery.isBlank()) series
        else series.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    val cleanSearch = searchQuery.trim()
    val isSearchActive = cleanSearch.length >= 2

    val matchedMovies = remember(cleanSearch, movies) {
        if (!isSearchActive) emptyList()
        else movies.filter { 
            it.title.contains(cleanSearch, ignoreCase = true) || 
            it.genre.contains(cleanSearch, ignoreCase = true) 
        }.take(6)
    }

    val matchedSeries = remember(cleanSearch, series) {
        if (!isSearchActive) emptyList()
        else series.filter { 
            it.title.contains(cleanSearch, ignoreCase = true) || 
            it.genre.contains(cleanSearch, ignoreCase = true) 
        }.take(6)
    }

    val matchedEpisodes = remember(cleanSearch, series, userEmail) {
        if (!isSearchActive) emptyList()
        else {
            val list = mutableListOf<SearchEpisodeMatch>()
            for (s in series) {
                val cached = DesktopSeriesCache.get(userEmail, s.id) ?: continue
                for (season in cached.seasons) {
                    for (ep in season.episodes) {
                        val epCode = "S%02dE%02d".format(ep.seasonNumber, ep.episodeNumber)
                        val epCodeAlt = "%dx%02d".format(ep.seasonNumber, ep.episodeNumber)
                        val epCodeAlt2 = "Ep ${ep.episodeNumber}"
                        val matches = ep.title.contains(cleanSearch, ignoreCase = true) ||
                                epCode.contains(cleanSearch, ignoreCase = true) ||
                                epCodeAlt.contains(cleanSearch, ignoreCase = true) ||
                                epCodeAlt2.contains(cleanSearch, ignoreCase = true) ||
                                s.title.contains(cleanSearch, ignoreCase = true)
                        if (matches) {
                            list.add(SearchEpisodeMatch(ep, s))
                        }
                    }
                }
            }
            list.take(8)
        }
    }

    // Keyboard Shortcuts (Ctrl+K to focus search, ESC to clear search)
    val dispatcher = remember {
        KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED) {
                if ((e.isControlDown || e.isMetaDown) && e.keyCode == KeyEvent.VK_K) {
                    try {
                        searchFocusRequester.requestFocus()
                        true
                    } catch (_: Throwable) { false }
                } else if (e.keyCode == KeyEvent.VK_ESCAPE && searchQuery.isNotBlank()) {
                    searchQuery = ""
                    true
                } else false
            } else false
        }
    }

    DisposableEffect(dispatcher) {
        val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        kfm.addKeyEventDispatcher(dispatcher)
        onDispose {
            kfm.removeKeyEventDispatcher(dispatcher)
        }
    }

    val isAnyPlayerActive = selectedMovieForPlayer != null || selectedSeriesForDetail != null
    val hideNavigation = effectiveFullscreen

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090E))
    ) {
        // 1. LEFT SIDEBAR (Hidden during active media fullscreen)
        if (!hideNavigation) {
            DesktopFigmaSidebar(
                activeSection = currentSection,
                onSelectSection = navigateTo
            )

            // Vertical Divider
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(Color(0xFF141923))
            )
        }

        // 2. RIGHT WORKSPACE
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFF07090E))
        ) {
            // Top App Bar & Floating Global Search Overlay (Hidden during active media fullscreen)
            if (!hideNavigation) {
                Box(modifier = Modifier.fillMaxWidth().zIndex(10f)) {
                    DesktopFigmaTopBar(
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        searchFocusRequester = searchFocusRequester,
                        userName = userName.ifBlank { userEmail.substringBefore('@').ifBlank { "Clotube User" } },
                        userEmail = userEmail,
                        userAvatarUrl = userAvatarUrl,
                        onSignOut = onSignOut
                    )

                    // Floating Global Search Overlay
                    if (isSearchActive) {
                        val totalResults = matchedMovies.size + matchedSeries.size + matchedEpisodes.size
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xF70C111C),
                            border = BorderStroke(1.dp, Color(0xFF263346)),
                            shadowElevation = 20.dp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 28.dp, top = 62.dp)
                                .widthIn(min = 520.dp, max = 640.dp)
                                .heightIn(max = 500.dp)
                        ) {
                            if (totalResults == 0) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(28.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No results found for \"$cleanSearch\"",
                                        color = Color(0xFF64748B),
                                        fontSize = 13.sp
                                    )
                                }
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    // SERIES SECTION
                                    if (matchedSeries.isNotEmpty()) {
                                        item(key = "hdr_series") {
                                            Text(
                                                text = "TV SERIES",
                                                color = Color(0xFF38BDF8),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.2.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }

                                        items(matchedSeries, key = { "search_series_${it.id}" }) { s ->
                                            val thumb = rememberThumbnailBitmap(s.thumbnailUrl, s.fallbackThumbnailUrl, accessToken, userEmail, ThumbnailSize.CARD)
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        searchQuery = ""
                                                        targetedEpisodeId = null
                                                        selectedSeriesForDetail = s
                                                    }
                                                    .pointerHoverIcon(PointerIcon.Hand),
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFF131824),
                                                border = BorderStroke(1.dp, Color(0xFF1E2838))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier.width(60.dp).height(38.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1A2232)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (thumb != null) {
                                                            Image(bitmap = thumb, contentDescription = s.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                        }
                                                    }
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(text = s.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text(text = s.genre.ifBlank { "Series" }, color = Color(0xFF64748B), fontSize = 11.sp, maxLines = 1)
                                                    }
                                                    Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF1E293B)) {
                                                        Text(text = "Series", color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // EPISODES SECTION
                                    if (matchedEpisodes.isNotEmpty()) {
                                        item(key = "hdr_episodes") {
                                            Text(
                                                text = "EPISODES",
                                                color = Color(0xFF38BDF8),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.2.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }

                                        items(matchedEpisodes, key = { "search_ep_${it.episode.id}" }) { match ->
                                            val thumb = rememberThumbnailBitmap(match.episode.thumbnailUrl, null, accessToken, userEmail, ThumbnailSize.CARD)
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        searchQuery = ""
                                                        targetedEpisodeId = match.episode.id
                                                        selectedSeriesForDetail = match.parentSeries
                                                    }
                                                    .pointerHoverIcon(PointerIcon.Hand),
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFF131824),
                                                border = BorderStroke(1.dp, Color(0xFF1E2838))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier.width(60.dp).height(38.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1A2232)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (thumb != null) {
                                                            Image(bitmap = thumb, contentDescription = match.episode.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                        }
                                                        Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                    }
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        val epCode = "S%02dE%02d".format(match.episode.seasonNumber, match.episode.episodeNumber)
                                                        Text(text = "$epCode • ${match.episode.title}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text(text = "${match.parentSeries.title} • ${match.episode.durationFormatted}", color = Color(0xFF64748B), fontSize = 11.sp, maxLines = 1)
                                                    }
                                                    Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF1E293B)) {
                                                        Text(text = "Play Episode", color = Color(0xFF38BDF8), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // MOVIES SECTION
                                    if (matchedMovies.isNotEmpty()) {
                                        item(key = "hdr_movies") {
                                            Text(
                                                text = "MOVIES",
                                                color = Color(0xFF38BDF8),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.2.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }

                                        items(matchedMovies, key = { "search_movie_${it.id}" }) { m ->
                                            val thumb = rememberThumbnailBitmap(m.thumbnailUrl, m.fallbackThumbnailUrl, accessToken, userEmail, ThumbnailSize.CARD)
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        searchQuery = ""
                                                        selectedMovieForPlayer = m
                                                    }
                                                    .pointerHoverIcon(PointerIcon.Hand),
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFF131824),
                                                border = BorderStroke(1.dp, Color(0xFF1E2838))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier.width(60.dp).height(38.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1A2232)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (thumb != null) {
                                                            Image(bitmap = thumb, contentDescription = m.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                        }
                                                    }
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(text = m.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text(text = "${m.releaseYear} • ${m.genre}", color = Color(0xFF64748B), fontSize = 11.sp, maxLines = 1)
                                                    }
                                                    Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF1E293B)) {
                                                        Text(text = "Movie", color = Color(0xFFCBD5E1), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
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
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (selectedSeriesForDetail != null) {
                    DesktopSeriesDetailScreen(
                        seriesItem = selectedSeriesForDetail!!,
                        accessToken = accessToken,
                        userEmail = userEmail,
                        initialEpisodeId = targetedEpisodeId,
                        isFullscreen = effectiveFullscreen,
                        onToggleFullscreen = onToggleFullscreen,
                        onBack = { 
                            if (effectiveFullscreen) onToggleFullscreen() else {
                                selectedSeriesForDetail = null
                                targetedEpisodeId = null
                                watchHistoryRevision++
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (selectedMovieForPlayer != null) {
                    val movieResumePos = remember(selectedMovieForPlayer?.id, userEmail, watchHistoryRevision) {
                        selectedMovieForPlayer?.let { movie ->
                            com.clotube.desktop.data.DesktopWatchHistoryManager.getPlaybackPosition(userEmail, movie.id)
                        } ?: 0L
                    }

                    DesktopMoviePlayerScreen(
                        initialMovie = selectedMovieForPlayer!!,
                        allMovies = filteredMovies,
                        accessToken = accessToken,
                        userEmail = userEmail,
                        initialSeekMs = movieResumePos,
                        onBack = {
                            if (effectiveFullscreen) onToggleFullscreen()
                            selectedMovieForPlayer = null
                            watchHistoryRevision++
                        },
                        isFullscreen = effectiveFullscreen,
                        onToggleFullscreen = onToggleFullscreen
                    )
                } else {
                    when (currentSection) {
                        DesktopNavSection.HOME -> {
                            FigmaHomeContent(
                                featuredItem = featuredItem ?: filteredMovies.firstOrNull() ?: filteredSeries.firstOrNull(),
                                continueWatching = continueWatching,
                                movies = filteredMovies,
                                series = filteredSeries,
                                userEmail = userEmail,
                                accessToken = accessToken,
                                watchHistoryRevision = watchHistoryRevision,
                                onMovieClick = { movie ->
                                    selectedMovieForPlayer = movie
                                    onMediaClick(movie)
                                },
                                onNavigateSection = navigateTo,
                                onSeriesClick = { seriesItem ->
                                    selectedSeriesForDetail = seriesItem
                                },
                                onContinueWatchingClick = { item ->
                                    val isMovie = movies.any { it.id == item.mediaId }
                                    if (item.isSeries && !item.seriesId.isNullOrBlank() && !isMovie) {
                                        val matchedSeries = series.find { it.id == item.seriesId }
                                        if (matchedSeries != null) {
                                            targetedEpisodeId = item.mediaId
                                            selectedSeriesForDetail = matchedSeries
                                        }
                                    } else {
                                        val matchedMovie = movies.find { it.id == item.mediaId }
                                        if (matchedMovie != null) {
                                            selectedMovieForPlayer = matchedMovie
                                        }
                                    }
                                },
                                onRescan = onRescan
                            )
                        }
                        DesktopNavSection.MOVIES -> {
                            FigmaMoviesContent(
                                movies = filteredMovies,
                                userEmail = userEmail,
                                accessToken = accessToken,
                                watchHistoryRevision = watchHistoryRevision,
                                onMovieClick = { movieItem ->
                                    selectedMovieForPlayer = movieItem
                                    onMediaClick(movieItem)
                                }
                            )
                        }
                        DesktopNavSection.SERIES -> {
                            FigmaSeriesContent(
                                series = filteredSeries,
                                userEmail = userEmail,
                                accessToken = accessToken,
                                watchHistoryRevision = watchHistoryRevision,
                                onSeriesClick = { seriesItem, epId ->
                                    targetedEpisodeId = epId
                                    selectedSeriesForDetail = seriesItem
                                }
                            )
                        }
                        DesktopNavSection.RECENTS -> {
                            FigmaRecentsContent(
                                userEmail = userEmail,
                                accessToken = accessToken,
                                movies = movies,
                                series = series,
                                watchHistoryRevision = watchHistoryRevision,
                                onMovieClick = { movieItem ->
                                    selectedMovieForPlayer = movieItem
                                    onMediaClick(movieItem)
                                },
                                onSeriesEpisodeClick = { seriesItem, episodeId ->
                                    targetedEpisodeId = episodeId
                                    selectedSeriesForDetail = seriesItem
                                },
                                onHistoryChanged = {
                                    watchHistoryRevision++
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopFigmaSidebar(
    activeSection: DesktopNavSection,
    onSelectSection: (DesktopNavSection) -> Unit
) {
    Column(
        modifier = Modifier
            .width(236.dp)
            .fillMaxHeight()
            .background(Color(0xFF090C12))
            .padding(horizontal = 16.dp, vertical = 20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Image(
                painter = androidx.compose.ui.res.painterResource("app_icon.png"),
                contentDescription = "Clotube Logo",
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Text(
                text = "Clotube",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "BROWSE",
            color = Color(0xFF475569),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        DesktopNavSection.entries.forEach { section ->
            val isSelected = activeSection == section
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelectSection(section) }
                    .pointerHoverIcon(PointerIcon.Hand),
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) Color(0xFF101D30) else Color.Transparent,
                border = if (isSelected) BorderStroke(1.dp, Color(0xFF1D3557)) else null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = section.label,
                        tint = if (isSelected) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = section.label,
                        color = if (isSelected) Color.White else Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "Settings",
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Settings",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun DesktopFigmaTopBar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    searchFocusRequester: FocusRequester,
    userName: String,
    userEmail: String,
    userAvatarUrl: String = "",
    onSignOut: () -> Unit
) {
    var showAccountMenu by remember { mutableStateOf(false) }
    val avatarBitmap = rememberThumbnailBitmap(primaryUrl = userAvatarUrl)

    val initials = remember(userName) {
        userName.split(" ")
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .take(2)
            .joinToString("")
            .ifBlank { "CU" }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Surface(
            modifier = Modifier
                .width(420.dp)
                .height(40.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF10141F),
            border = BorderStroke(1.dp, Color(0xFF1E2638))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search",
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(16.dp)
                    )
                    androidx.compose.foundation.text.BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.weight(1f).focusRequester(searchFocusRequester),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search movies, series, episodes...",
                                    color = Color(0xFF64748B),
                                    fontSize = 13.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .clickable { onSearchChange("") }
                            .pointerHoverIcon(PointerIcon.Hand)
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0xFF192030),
                        border = BorderStroke(1.dp, Color(0xFF283248))
                    ) {
                        Text(
                            text = "⌘ K",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF091F14),
                border = BorderStroke(1.dp, Color(0xFF134E2F))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF22C55E))
                    )
                    Icon(
                        imageVector = Icons.Rounded.Wifi,
                        contentDescription = "Online",
                        tint = Color(0xFF22C55E),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Online",
                        color = Color(0xFF22C55E),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Box {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF4F46E5), Color(0xFF7C3AED))
                            )
                        )
                        .border(1.5.dp, Color(0xFF38BDF8), CircleShape)
                        .clickable { showAccountMenu = true }
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap,
                            contentDescription = userName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = initials,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                DropdownMenu(
                    expanded = showAccountMenu,
                    onDismissRequest = { showAccountMenu = false },
                    modifier = Modifier
                        .background(Color(0xFF10141F))
                        .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(12.dp))
                        .widthIn(min = 240.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF4F46E5), Color(0xFF7C3AED))
                                        )
                                    )
                                    .border(1.5.dp, Color(0xFF38BDF8), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (avatarBitmap != null) {
                                    Image(
                                        bitmap = avatarBitmap,
                                        contentDescription = userName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text(
                                        text = initials,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = userName,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = userEmail,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF161F2E),
                            border = BorderStroke(1.dp, Color(0xFF23324A))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E))
                                )
                                Text(
                                    text = "Google Account Connected",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFF1E2838), thickness = 1.dp)

                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Logout,
                                    contentDescription = "Sign Out",
                                    tint = Color(0xFFF87171),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Sign Out",
                                    color = Color(0xFFF87171),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        },
                        onClick = {
                            showAccountMenu = false
                            onSignOut()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingShelf(
    items: List<DesktopContinueWatchingItem>,
    userEmail: String,
    accessToken: String,
    onItemClick: (DesktopContinueWatchingItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val displayItems = remember(items) { items.take(5) }
    if (displayItems.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00E5FF))
                )
                Text(
                    text = "CONTINUE WATCHING",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp
                )
            }

            Text(
                text = "${displayItems.size} in progress",
                color = Color(0xFF64748B),
                fontSize = 11.sp
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            displayItems.forEach { item ->
                ContinueWatchingCard(
                    item = item,
                    userEmail = userEmail,
                    accessToken = accessToken,
                    onClick = { onItemClick(item) },
                    modifier = Modifier.width(248.dp).height(142.dp)
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    item: DesktopContinueWatchingItem,
    userEmail: String,
    accessToken: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isHovered by remember { mutableStateOf(false) }

    val posterBitmap = rememberThumbnailBitmap(
        primaryUrl = item.thumbnailUrl,
        fallbackUrl = item.fallbackThumbnailUrl,
        accessToken = accessToken,
        userEmail = userEmail,
        size = ThumbnailSize.CARD
    )

    // Calculate remaining runtime specifically for the top-right pill
    val remainingMs = (item.durationMs - item.positionMs).coerceAtLeast(0L)
    val remainingStr = remember(remainingMs) {
        DesktopWatchHistoryManager.formatRemainingTime(remainingMs)
    }

    // Sanitize bottom subtitle: strip any residual "X left" or "• X left" text completely
    val bottomSubtitle = remember(item.subtitle, item.isSeries) {
        val clean = item.subtitle
            .replace(Regex("(?i)\\s*•?\\s*\\d+h?\\s*\\d*m?\\s*left"), "")
            .trim()
        if (clean.equals("Movie", ignoreCase = true) || clean.contains("left", ignoreCase = true)) {
            ""
        } else {
            clean
        }
    }

    val isRealSeries = item.isSeries && (item.seasonNumber ?: 0) >= 1 && (item.episodeNumber ?: 0) >= 1
    val badgeText = if (isRealSeries) {
        "S${item.seasonNumber}:E${item.episodeNumber}"
    } else if (item.isSeries) {
        "SERIES"
    } else {
        "MOVIE"
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
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
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0F141F),
        border = BorderStroke(
            1.dp,
            if (isHovered) Color(0xFF00E5FF).copy(alpha = 0.8f) else Color(0xFF1E2838)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (posterBitmap != null) {
                Image(
                    bitmap = posterBitmap,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF161E2E)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.title.take(1),
                        color = Color(0xFF38BDF8),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Dark gradient overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0x66080B11),
                                Color(0x40080B11),
                                Color(0xF2080B11)
                            )
                        )
                    )
            )

            // Top Badges
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Type Badge (MOVIE, SERIES, or S1:E8)
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xCC0C111C),
                    border = BorderStroke(1.dp, Color(0xFF263346))
                ) {
                    Text(
                        text = badgeText,
                        color = Color(0xFF00E5FF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }

                // Top-right Remaining Time Capsule (The ONLY place where "X left" appears)
                if (remainingStr.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xCC000000)
                    ) {
                        Text(
                            text = "$remainingStr left",
                            color = Color(0xFFCBD5E1),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Hover Play Overlay
            if (isHovered) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E5FF).copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF090D14),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Bottom Title & Progress Bar (No time left here)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (bottomSubtitle.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = bottomSubtitle,
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Bottom 3dp Progress Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x551E2838))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(item.progressFraction.coerceIn(0.02f, 1f))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF00E5FF), Color(0xFF2563EB))
                                )
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun FigmaHomeContent(
    featuredItem: DesktopMediaItem?,
    continueWatching: List<DesktopMediaItem>,
    movies: List<DesktopMediaItem>,
    series: List<DesktopMediaItem>,
    userEmail: String = "",
    accessToken: String = "",
    watchHistoryRevision: Int = 0,
    onMovieClick: (DesktopMediaItem) -> Unit,
    onNavigateSection: (DesktopNavSection) -> Unit,
    onSeriesClick: (DesktopMediaItem) -> Unit,
    onContinueWatchingClick: ((DesktopContinueWatchingItem) -> Unit)? = null,
    onRescan: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    // Keyed on watchHistoryRevision so returning from player immediately reflects new progress!
    val continueWatchingItems = remember(userEmail, movies, series, watchHistoryRevision) {
        com.clotube.desktop.data.DesktopWatchHistoryManager.getContinueWatching(userEmail, movies, series)
    }

    // Combine all indexed movies and series for the hero billboard slideshow pool
    val allMediaPool = remember(movies, series) {
        val combined = (movies + series).filter { !it.thumbnailUrl.isNullOrBlank() }
        if (combined.isNotEmpty()) combined else listOfNotNull(featuredItem)
    }

    if (movies.isEmpty() && series.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 520.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF0C1018),
                border = BorderStroke(1.dp, Color(0xFF1E2638))
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF131D2D)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudOff,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Text(
                        text = "No Media Indexed in Google Drive",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Clotube connected to your account, but found 0 movies or series folders in Drive. Check that your folders are named 'Movies' or 'Series', or verify your internet connection.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = onRescan,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(42.dp).pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Rescan Cloud Library", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // 1. REPLACED: Randomized Auto-playing Hero Slideshow
        FigmaHeroSlideshowBanner(
            items = allMediaPool,
            seriesList = series,
            userEmail = userEmail,
            accessToken = accessToken,
            onMovieClick = onMovieClick,
            onSeriesClick = onSeriesClick
        )

        // 2. NEW: Continue Watching Shelf (Only renders when in-progress media exists)
        if (continueWatchingItems.isNotEmpty()) {
            ContinueWatchingShelf(
                items = continueWatchingItems,
                userEmail = userEmail,
                accessToken = accessToken,
                onItemClick = { item ->
                    onContinueWatchingClick?.invoke(item)
                }
            )
        } else if (continueWatching.isNotEmpty()) {
            FigmaCarouselSection(
                overline = "PICK UP WHERE YOU LEFT OFF",
                title = "Continue watching",
                actionLabel = "View all",
                items = continueWatching.take(5),
                accessToken = accessToken,
                onActionClick = { onNavigateSection(DesktopNavSection.RECENTS) },
                onItemClick = onMovieClick
            )
        }

        if (movies.isNotEmpty()) {
            FigmaCarouselSection(
                overline = "MOVIES COLLECTION",
                title = "Movies",
                actionLabel = "View all",
                items = movies.take(5),
                accessToken = accessToken,
                onActionClick = { onNavigateSection(DesktopNavSection.MOVIES) },
                onItemClick = onMovieClick
            )
        }

        if (series.isNotEmpty()) {
            FigmaCarouselSection(
                overline = "MULTI-SEASON SHOWS",
                title = "Series",
                actionLabel = "View all",
                items = series.take(5),
                accessToken = accessToken,
                onActionClick = { onNavigateSection(DesktopNavSection.SERIES) },
                onItemClick = onSeriesClick
            )
        }
    }
}

@Composable
private fun FigmaMoviesContent(
    movies: List<DesktopMediaItem>,
    userEmail: String,
    accessToken: String,
    watchHistoryRevision: Int = 0,
    onMovieClick: (DesktopMediaItem) -> Unit
) {
    val lastWatchedMovie = remember(movies, userEmail, watchHistoryRevision) {
        DesktopWatchHistoryManager.getLastWatchedMovie(userEmail, movies)
    }

    val featuredMovie = remember(movies, lastWatchedMovie) {
        lastWatchedMovie?.let { l -> movies.find { it.id == l.mediaId } }
            ?: movies.find { it.title.equals("Crime 101", ignoreCase = true) }
            ?: movies.firstOrNull()
    }

    val genreFilters = remember(movies) {
        val extracted = movies
            .flatMap { it.genre.split(" • ", ",", "•").map { g -> g.trim() } }
            .filter { it.isNotBlank() && it !in listOf("Movie", "Cinema") }
            .distinct()
        listOf("All") + extracted
    }
    var selectedGenre by remember { mutableStateOf("All") }

    val filteredMovies = remember(movies, selectedGenre) {
        if (selectedGenre == "All") movies
        else movies.filter { it.genre.contains(selectedGenre, ignoreCase = true) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item(key = "movies_header_row") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "EXPLORE THE COLLECTION",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Movies",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Your private cinema collection stored in Google Drive.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${movies.size}",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "movies available",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )
                }
            }
        }

        featuredMovie?.let { hero ->
            item(key = "movie_hero_billboard") {
                MovieHeroBillboard(
                    movieItem = hero,
                    resumeItem = lastWatchedMovie,
                    userEmail = userEmail,
                    accessToken = accessToken,
                    onPlayClick = { onMovieClick(hero) }
                )
            }
        }

        if (genreFilters.size > 1) {
            item(key = "movies_genre_chips") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    genreFilters.forEach { genre ->
                        val isSelected = selectedGenre.equals(genre, ignoreCase = true)
                        val count = remember(movies, genre) {
                            if (genre == "All") movies.size
                            else movies.count { it.genre.contains(genre, ignoreCase = true) }
                        }

                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { selectedGenre = genre }
                                .pointerHoverIcon(PointerIcon.Hand),
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) Color(0xFF2563EB) else Color(0xFF111622),
                            border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF1E2638))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Text(
                                    text = genre,
                                    color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) Color(0x33000000) else Color(0xFF1A2232)
                                ) {
                                    Text(
                                        text = "$count",
                                        color = if (isSelected) Color.White.copy(alpha = 0.95f) else Color(0xFF64748B),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5-Column Movies Grid
        val rows = filteredMovies.chunked(5)
        items(rows, key = { row -> row.joinToString { it.id } }) { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                for (i in 0 until 5) {
                    val item = rowItems.getOrNull(i)
                    if (item != null) {
                        DesktopSeriesGridCard(
                            seriesItem = item,
                            userEmail = userEmail,
                            accessToken = accessToken,
                            onClick = { onMovieClick(item) },
                            modifier = Modifier.weight(1f).aspectRatio(16f / 9.5f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieHeroBillboard(
    movieItem: DesktopMediaItem,
    resumeItem: DesktopContinueWatchingItem?,
    userEmail: String,
    accessToken: String,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bannerBitmap = rememberThumbnailBitmap(
        primaryUrl = movieItem.thumbnailUrl,
        fallbackUrl = movieItem.fallbackThumbnailUrl,
        accessToken = accessToken,
        userEmail = userEmail,
        size = ThumbnailSize.HERO
    )

    val isResume = resumeItem != null && resumeItem.mediaId == movieItem.id && resumeItem.positionMs > 0L

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0C1018))
            .border(1.dp, Color(0xFF1A2233), RoundedCornerShape(16.dp))
    ) {
        if (bannerBitmap != null) {
            Image(
                bitmap = bannerBitmap,
                contentDescription = movieItem.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF161E2E), Color(0xFF0A0E17))
                        )
                    )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xF5080B11),
                            Color(0xDD080B11),
                            Color(0x88080B11),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x99080B11),
                            Color(0xE6080B11)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 36.dp, vertical = 28.dp)
                .widthIn(max = 580.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isResume) Color(0xFF00E5FF) else Color(0xFF38BDF8))
                )
                Text(
                    text = if (isResume) "RESUME WATCHING" else "FEATURED MOVIE",
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = movieItem.title.uppercase(),
                color = Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (movieItem.durationMs > 0L) {
                    Text(
                        text = formatDuration(movieItem.durationMs),
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = "•", color = Color(0xFF64748B), fontSize = 12.sp)
                }
                Text(
                    text = movieItem.genre.ifBlank { "Action • Drama" },
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                if (isResume && resumeItem?.subtitle?.contains("left", ignoreCase = true) == true) {
                    Text(text = "•", color = Color(0xFF64748B), fontSize = 12.sp)
                    Text(
                        text = resumeItem.subtitle.substringAfterLast("•").trim(),
                        color = Color(0xFF00E5FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val genreList = movieItem.genre.split(" • ", ",", "•").map { it.trim() }.filter { it.isNotBlank() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                genreList.take(3).forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF101520).copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, Color(0xFF1E2838))
                    ) {
                        Text(
                            text = tag,
                            color = Color(0xFFCBD5E1),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPlayClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(38.dp).pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isResume) "Resume watching" else "Watch now",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { }
                        .pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF131824).copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color(0xFF263346))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
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
            }
        }

        // Bottom Scrubber Bar (Only rendered when movie is in-progress)
        if (isResume && resumeItem != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.BottomStart)
                    .background(Color(0x331E2838))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(resumeItem.progressFraction.coerceIn(0.02f, 1f))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF00E5FF), Color(0xFF2563EB))
                            )
                        )
                )
            }
        }
    }
}

// -------------------------------------------------------------
// 2. SERIES CONTENT WITH LAST-WATCHED HERO BILLBOARD & SCRUBBER
// -------------------------------------------------------------
@Composable
private fun FigmaSeriesContent(
    series: List<DesktopMediaItem>,
    userEmail: String,
    accessToken: String,
    watchHistoryRevision: Int = 0,
    onSeriesClick: (DesktopMediaItem, String?) -> Unit
) {
    val lastWatchedSeries = remember(series, userEmail, watchHistoryRevision) {
        DesktopWatchHistoryManager.getLastWatchedSeries(userEmail, series)
    }

    val featuredSeries = remember(series, lastWatchedSeries) {
        lastWatchedSeries?.seriesId?.let { sId -> series.find { it.id == sId } }
            ?: series.find { it.title.equals("Dark", ignoreCase = true) }
            ?: series.firstOrNull()
    }

    val genreFilters = remember(series) {
        val extracted = series
            .flatMap { it.genre.split(" • ", ",", "•").map { g -> g.trim() } }
            .map { if (it.equals("Science-Fiction", ignoreCase = true)) "Sci-Fi" else it }
            .filter { it.isNotBlank() && it !in listOf("Series", "TV Series") }
            .distinct()
        listOf("All") + extracted
    }
    var selectedGenre by remember { mutableStateOf("All") }

    val filteredSeries = remember(series, selectedGenre) {
        if (selectedGenre == "All") series
        else series.filter { item ->
            val clean = item.genre.replace("Science-Fiction", "Sci-Fi", ignoreCase = true)
            clean.contains(selectedGenre, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item(key = "series_header_row") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "STORIES THAT KEEP GOING",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Series",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Complete series and seasons discovered in your Google Drive.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${series.size}",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "series in your library",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )
                }
            }
        }

        featuredSeries?.let { hero ->
            item(key = "series_hero_billboard") {
                SeriesHeroBillboard(
                    seriesItem = hero,
                    resumeItem = if (lastWatchedSeries?.seriesId == hero.id) lastWatchedSeries else null,
                    userEmail = userEmail,
                    accessToken = accessToken,
                    onResumeEpisode = { epId -> onSeriesClick(hero, epId) },
                    onViewEpisodes = { onSeriesClick(hero, null) }
                )
            }
        }

        if (genreFilters.size > 1) {
            item(key = "series_genre_chips") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    genreFilters.forEach { genre ->
                        val isSelected = selectedGenre.equals(genre, ignoreCase = true)
                        val count = remember(series, genre) {
                            if (genre == "All") series.size
                            else series.count { item ->
                                val clean = item.genre.replace("Science-Fiction", "Sci-Fi", ignoreCase = true)
                                clean.contains(genre, ignoreCase = true)
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { selectedGenre = genre }
                                .pointerHoverIcon(PointerIcon.Hand),
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) Color(0xFF2563EB) else Color(0xFF111622),
                            border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF1E2638))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Text(
                                    text = genre,
                                    color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) Color(0x33000000) else Color(0xFF1A2232)
                                ) {
                                    Text(
                                        text = "$count",
                                        color = if (isSelected) Color.White.copy(alpha = 0.95f) else Color(0xFF64748B),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5-Column Series Grid
        val rows = filteredSeries.chunked(5)
        items(rows, key = { row -> row.joinToString { it.id } }) { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                for (i in 0 until 5) {
                    val item = rowItems.getOrNull(i)
                    if (item != null) {
                        DesktopSeriesGridCard(
                            seriesItem = item,
                            userEmail = userEmail,
                            accessToken = accessToken,
                            onClick = { onSeriesClick(item, null) },
                            modifier = Modifier.weight(1f).aspectRatio(16f / 9.5f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesHeroBillboard(
    seriesItem: DesktopMediaItem,
    resumeItem: DesktopContinueWatchingItem?,
    userEmail: String,
    accessToken: String,
    onResumeEpisode: (String) -> Unit,
    onViewEpisodes: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bannerBitmap = rememberThumbnailBitmap(
        primaryUrl = resumeItem?.thumbnailUrl ?: seriesItem.thumbnailUrl,
        fallbackUrl = seriesItem.fallbackThumbnailUrl,
        accessToken = accessToken,
        userEmail = userEmail,
        size = ThumbnailSize.HERO
    )

    val isResume = resumeItem != null && resumeItem.mediaId.isNotBlank()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0C1018))
            .border(1.dp, Color(0xFF1A2233), RoundedCornerShape(16.dp))
    ) {
        if (bannerBitmap != null) {
            Image(
                bitmap = bannerBitmap,
                contentDescription = seriesItem.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF161E2E), Color(0xFF0A0E17))
                        )
                    )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xF5080B11),
                            Color(0xDD080B11),
                            Color(0x88080B11),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x99080B11),
                            Color(0xE6080B11)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 36.dp, vertical = 28.dp)
                .widthIn(max = 600.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isResume) Color(0xFF00E5FF) else Color(0xFF38BDF8))
                )
                Text(
                    text = if (isResume) "RESUME EPISODE" else "FEATURED SERIES",
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = seriesItem.title.uppercase(),
                color = Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            if (isResume && resumeItem != null) {
                val epCode = "S${resumeItem.seasonNumber}E${resumeItem.episodeNumber}"
                Text(
                    text = "$epCode • ${resumeItem.episodeTitle}",
                    color = Color(0xFF00E5FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            val genreList = seriesItem.genre.split(" • ", ",", "•").map { it.trim() }.filter { it.isNotBlank() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = genreList.joinToString(" • "),
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (isResume && resumeItem?.subtitle?.contains("left", ignoreCase = true) == true) {
                    Text(text = "•", color = Color(0xFF64748B), fontSize = 12.sp)
                    Text(
                        text = resumeItem.subtitle.substringAfterLast("•").trim(),
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                genreList.take(3).forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF101520).copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, Color(0xFF1E2838))
                    ) {
                        Text(
                            text = tag,
                            color = Color(0xFFCBD5E1),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (isResume && resumeItem != null) {
                            onResumeEpisode(resumeItem.mediaId)
                        } else {
                            onViewEpisodes()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(38.dp).pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isResume && resumeItem != null) {
                            "Resume S${resumeItem.seasonNumber}E${resumeItem.episodeNumber}"
                        } else {
                            "View Episodes"
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onViewEpisodes() }
                        .pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF131824).copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, Color(0xFF263346))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "All Episodes",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Bottom Scrubber Bar (Only rendered when episode is in-progress)
        if (isResume && resumeItem != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.BottomStart)
                    .background(Color(0x331E2838))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(resumeItem.progressFraction.coerceIn(0.02f, 1f))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF00E5FF), Color(0xFF2563EB))
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun DesktopSeriesGridCard(
    seriesItem: DesktopMediaItem,
    userEmail: String,
    accessToken: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isHovered by remember { mutableStateOf(false) }

    val posterBitmap = rememberThumbnailBitmap(
        primaryUrl = seriesItem.thumbnailUrl,
        fallbackUrl = seriesItem.fallbackThumbnailUrl,
        accessToken = accessToken,
        userEmail = userEmail,
        size = ThumbnailSize.CARD
    )

    // Lookup cached seasons count for the badge
    val cachedSeries = remember(seriesItem.id, userEmail) {
        DesktopSeriesCache.get(userEmail, seriesItem.id)
    }
    val seasonCount = cachedSeries?.seasons?.size ?: 0

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
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
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0F141F),
        shadowElevation = if (isHovered) 8.dp else 0.dp,
        border = BorderStroke(
            1.dp,
            if (isHovered) Color(0xFF00E5FF).copy(alpha = 0.8f) else Color(0xFF1A2233)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (posterBitmap != null) {
                Image(
                    bitmap = posterBitmap,
                    contentDescription = seriesItem.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF141A26)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = seriesItem.title.take(1),
                        color = Color(0xFF38BDF8),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Dark gradient overlay for bottom text
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0x66080B11),
                                Color(0xF2080B11)
                            )
                        )
                    )
            )

            // Season Count Badge (Top Right)
            if (seasonCount > 0) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xD90C111C),
                    border = BorderStroke(1.dp, Color(0xFF1E2838)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "$seasonCount ${if (seasonCount == 1) "Season" else "Seasons"}",
                        color = Color(0xFF00E5FF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            // Hover Action: Play Button Overlay
            if (isHovered) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E5FF).copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF090D14),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Bottom Title & Genres
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = seriesItem.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = seriesItem.genre.ifBlank { "TV Series" },
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FigmaRecentsContent(
    userEmail: String,
    accessToken: String,
    movies: List<DesktopMediaItem>,
    series: List<DesktopMediaItem>,
    watchHistoryRevision: Int,
    onMovieClick: (DesktopMediaItem) -> Unit,
    onSeriesEpisodeClick: (DesktopMediaItem, String) -> Unit,
    onHistoryChanged: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isClearing by remember { mutableStateOf(false) }

    val historyItems = remember(userEmail, movies, series, watchHistoryRevision) {
        com.clotube.desktop.data.DesktopWatchHistoryManager.getAllWatchHistory(userEmail, movies, series)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header Section
        item(key = "recents_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "YOUR WATCH HISTORY",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Recents",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Jump back into what you've watched.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }

                if (historyItems.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isClearing) {
                                isClearing = true
                                coroutineScope.launch {
                                    com.clotube.desktop.data.DesktopWatchHistoryManager.clearAllHistory(userEmail)
                                    com.clotube.desktop.data.DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken, force = true)
                                    onHistoryChanged()
                                    isClearing = false
                                }
                            }
                            .pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF131824),
                        border = BorderStroke(1.dp, Color(0xFF263346))
                    ) {
                        Text(
                            text = if (isClearing) "Clearing..." else "Clear watch history",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // 2. Empty State
        if (historyItems.isEmpty()) {
            item(key = "recents_empty_state") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF131824)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = null,
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Watch History Yet",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Movies and series you watch on desktop or mobile will appear here.",
                            color = Color(0xFF64748B),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            // 3. Watched Items List
            items(historyItems, key = { it.mediaId }) { item ->
                RecentHistoryItemCard(
                    item = item,
                    userEmail = userEmail,
                    accessToken = accessToken,
                    onClick = {
                        if (item.isSeries && !item.seriesId.isNullOrBlank()) {
                            val matched = series.find { it.id == item.seriesId }
                            if (matched != null) {
                                onSeriesEpisodeClick(matched, item.mediaId)
                            }
                        } else {
                            val matched = movies.find { it.id == item.mediaId }
                            if (matched != null) {
                                onMovieClick(matched)
                            }
                        }
                    },
                    onRemove = {
                        coroutineScope.launch {
                            com.clotube.desktop.data.DesktopWatchHistoryManager.removeHistoryItem(userEmail, item.mediaId)
                            com.clotube.desktop.data.DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken, force = true)
                            onHistoryChanged()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun RecentHistoryItemCard(
    item: com.clotube.desktop.data.DesktopContinueWatchingItem,
    userEmail: String,
    accessToken: String,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }

    val posterBitmap = rememberThumbnailBitmap(
        primaryUrl = item.thumbnailUrl,
        fallbackUrl = item.fallbackThumbnailUrl,
        accessToken = accessToken,
        userEmail = userEmail,
        size = ThumbnailSize.CARD
    )

    val percentWatched = (item.progressFraction * 100).toInt().coerceIn(0, 100)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
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
        shape = RoundedCornerShape(12.dp),
        color = if (isHovered) Color(0xFF131824) else Color(0xFF0F141F),
        border = BorderStroke(1.dp, if (isHovered) Color(0xFF263346) else Color(0xFF18202F))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .width(124.dp)
                    .height(68.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF161E2E)),
                contentAlignment = Alignment.Center
            ) {
                if (posterBitmap != null) {
                    Image(
                        bitmap = posterBitmap,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (isHovered) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E5FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color(0xFF090D14),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Title & Subtitle Info
            Column(
                modifier = Modifier.widthIn(min = 180.dp, max = 280.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.subtitle,
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Percentage & Progress Line
            Column(
                modifier = Modifier.weight(2f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "$percentWatched% watched",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF1E2838))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(item.progressFraction.coerceIn(0.01f, 1f))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF2563EB), Color(0xFF38BDF8))
                                )
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Delete item button (✕)
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove from history",
                    tint = if (isHovered) Color.White else Color(0xFF64748B),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun HeroSlideCardContent(
    item: DesktopMediaItem,
    seriesList: List<DesktopMediaItem>,
    onMovieClick: (DesktopMediaItem) -> Unit,
    onSeriesClick: (DesktopMediaItem) -> Unit,
    modifier: Modifier = Modifier,
    userEmail: String = "",
    accessToken: String = ""
) {
    val isSeries = remember(item.id, seriesList) {
        seriesList.any { it.id == item.id }
    }
    val backdrop = rememberThumbnailBitmap(
        primaryUrl = item.thumbnailUrl,
        fallbackUrl = item.fallbackThumbnailUrl,
        accessToken = accessToken,
        userEmail = userEmail,
        size = ThumbnailSize.HERO
    )

    Box(modifier = modifier) {
        // Poster Artwork
        if (backdrop != null) {
            Image(
                bitmap = backdrop,
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
                            listOf(Color(0xFF161E2E), Color(0xFF090D14))
                        )
                    )
            )
        }

        // Cinema Vignette Gradients
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xF8080B11),
                            Color(0xEE080B11),
                            Color(0xB3080B11),
                            Color(0x4D080B11),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x66080B11),
                            Color(0xE607090E)
                        )
                    )
                )
        )

        // Metadata Details Column
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 36.dp, vertical = 28.dp)
                .widthIn(max = 620.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(12.dp)
                        .background(Color(0xFF42A5F5))
                )
                Text(
                    text = if (isSeries) "FEATURED SERIES IN YOUR DRIVE" else "FEATURED IN YOUR DRIVE",
                    color = Color(0xFFCBD5E1),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = item.title.uppercase(),
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (item.durationMs > 0L) {
                    Text(
                        text = formatDuration(item.durationMs),
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = "•", color = Color(0xFF64748B), fontSize = 13.sp)
                }

                Text(
                    text = item.genre.ifBlank { if (isSeries) "TV Series • Drama" else "Action • Drama" },
                    color = Color(0xFF42A5F5),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val genreTokens = remember(item.genre) {
                item.genre.split("•", ",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals("Movie", ignoreCase = true) }
            }

            if (genreTokens.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    genreTokens.take(4).forEach { genreTag ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0x99131D2D),
                            border = BorderStroke(1.dp, Color(0x661E3A5F))
                        ) {
                            Text(
                                text = genreTag,
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Action Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (isSeries) onSeriesClick(item)
                        else onMovieClick(item)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .height(42.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSeries) "View series" else "Resume watching",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { }
                        .pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0x991E2636),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "My list",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FigmaHeroSlideshowBanner(
    items: List<DesktopMediaItem>,
    seriesList: List<DesktopMediaItem>,
    onMovieClick: (DesktopMediaItem) -> Unit,
    onSeriesClick: (DesktopMediaItem) -> Unit,
    modifier: Modifier = Modifier,
    userEmail: String = "",
    accessToken: String = ""
) {
    if (items.isEmpty()) return

    val shuffledItems = remember(items) { items.shuffled() }
    var currentIndex by remember(shuffledItems) { mutableIntStateOf(0) }
    var outgoingIndex by remember(shuffledItems) { mutableIntStateOf(-1) }
    var isMovingForward by remember { mutableStateOf(true) }

    var timerProgressFraction by remember { mutableFloatStateOf(0f) }
    var slideTransitionFraction by remember { mutableFloatStateOf(1f) }
    val isTransitioning = slideTransitionFraction < 1f

    var manualTriggerKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentIndex, shuffledItems, manualTriggerKey) {
        if (shuffledItems.size <= 1) return@LaunchedEffect

        val displayHz = com.clotube.desktop.getActiveDisplayRefreshRate()
        val frameIntervalMs = (1000L / displayHz.toLong()).coerceIn(4L, 33L)

        // 1. PHASE A: SLIDE TRANSITION (Paced at monitor refresh rate, no 258 FPS spikes)
        if (outgoingIndex != -1) {
            val slideDurationMs = 500L
            val slideStartTime = System.currentTimeMillis()

            while (true) {
                val elapsed = System.currentTimeMillis() - slideStartTime
                if (elapsed >= slideDurationMs) {
                    slideTransitionFraction = 1f
                    outgoingIndex = -1
                    break
                }
                val rawFrac = (elapsed.toFloat() / slideDurationMs.toFloat()).coerceIn(0f, 1f)
                slideTransitionFraction = androidx.compose.animation.core.FastOutSlowInEasing.transform(rawFrac)
                kotlinx.coroutines.delay(frameIntervalMs)
            }
        } else {
            slideTransitionFraction = 1f
        }

        // 2. PHASE B: COUNTDOWN TIMER (5000ms display phase)
        timerProgressFraction = 0f
        val countdownDurationMs = 5000L
        val countdownStartTime = System.currentTimeMillis()

        while (true) {
            val elapsed = System.currentTimeMillis() - countdownStartTime
            if (elapsed >= countdownDurationMs) {
                timerProgressFraction = 1f
                break
            }
            timerProgressFraction = (elapsed.toFloat() / countdownDurationMs.toFloat()).coerceIn(0f, 1f)
            kotlinx.coroutines.delay(frameIntervalMs)
        }

        isMovingForward = true
        outgoingIndex = currentIndex
        currentIndex = (currentIndex + 1) % shuffledItems.size
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(380.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF101520))
            .border(1.dp, Color(0xFF1B2333), RoundedCornerShape(18.dp))
    ) {
        val currentItem = shuffledItems[currentIndex.coerceIn(0, shuffledItems.lastIndex)]
        val outgoingItem = if (outgoingIndex in shuffledItems.indices) shuffledItems[outgoingIndex] else null

        Box(modifier = Modifier.fillMaxSize()) {
            if (isTransitioning && outgoingItem != null) {
                HeroSlideCardContent(
                    item = outgoingItem,
                    seriesList = seriesList,
                    onMovieClick = onMovieClick,
                    onSeriesClick = onSeriesClick,
                    userEmail = userEmail,
                    accessToken = accessToken,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = (if (isMovingForward) -slideTransitionFraction else slideTransitionFraction) * size.width
                            alpha = (1f - slideTransitionFraction).coerceIn(0f, 1f)
                        }
                )
            }

            HeroSlideCardContent(
                item = currentItem,
                seriesList = seriesList,
                onMovieClick = onMovieClick,
                onSeriesClick = onSeriesClick,
                userEmail = userEmail,
                accessToken = accessToken,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (isTransitioning) {
                            translationX = (if (isMovingForward) (1f - slideTransitionFraction) else -(1f - slideTransitionFraction)) * size.width
                            alpha = slideTransitionFraction.coerceIn(0f, 1f)
                        } else {
                            translationX = 0f
                            alpha = 1f
                        }
                    }
            )
        }

        // Progress Bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(3.5.dp)
                .background(Color(0x26FFFFFF))
                .drawWithContent {
                    drawContent()
                    val progress = timerProgressFraction
                    if (progress > 0f) {
                        drawRect(
                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(Color(0xFF0D47A1), Color(0xFF42A5F5))
                            ),
                            size = androidx.compose.ui.geometry.Size(
                                width = size.width * progress.coerceIn(0f, 1f),
                                height = size.height
                            )
                        )
                    }
                }
        )

        // Chevron Controls & Indicator Dots
        if (shuffledItems.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        if (!isTransitioning) {
                            isMovingForward = false
                            outgoingIndex = currentIndex
                            currentIndex = if (currentIndex > 0) currentIndex - 1 else shuffledItems.lastIndex
                            manualTriggerKey++
                        }
                    },
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(0x800C111A))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronLeft,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    shuffledItems.take(8).forEachIndexed { index, _ ->
                        val isSelected = index == (currentIndex % 8)
                        Box(
                            modifier = Modifier
                                .width(if (isSelected) 18.dp else 6.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (isSelected) Color(0xFF42A5F5) else Color(0x66FFFFFF))
                                .clickable {
                                    if (!isTransitioning && currentIndex != index) {
                                        isMovingForward = index > currentIndex
                                        outgoingIndex = currentIndex
                                        currentIndex = index
                                        manualTriggerKey++
                                    }
                                }
                        )
                    }
                }

                IconButton(
                    onClick = {
                        if (!isTransitioning) {
                            isMovingForward = true
                            outgoingIndex = currentIndex
                            currentIndex = (currentIndex + 1) % shuffledItems.size
                            manualTriggerKey++
                        }
                    },
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(0x800C111A))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FigmaHeroBanner(
    item: DesktopMediaItem,
    overline: String,
    primaryButtonText: String,
    accessToken: String = "",
    onPrimaryClick: () -> Unit
) {
    val heroBitmap = rememberThumbnailBitmap(
        primaryUrl = item.thumbnailUrl,
        fallbackUrl = item.fallbackThumbnailUrl,
        accessToken = accessToken,
        size = ThumbnailSize.HERO
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(370.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF101520))
            .border(1.dp, Color(0xFF1B2333), RoundedCornerShape(18.dp))
    ) {
        if (heroBitmap != null) {
            Image(
                bitmap = heroBitmap,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xF8080B11),
                            Color(0xCC080B11),
                            Color(0x4D080B11)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 36.dp, vertical = 28.dp)
                .widthIn(max = 580.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(12.dp)
                        .background(Color(0xFF00E5FF))
                )
                Text(
                    text = overline,
                    color = Color(0xFFCBD5E1),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = item.title.uppercase(),
                color = Color.White,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (item.durationMs > 0L) {
                    Text(
                        text = formatDuration(item.durationMs),
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = "•", color = Color(0xFF64748B), fontSize = 13.sp)
                }

                Text(
                    text = item.genre.ifBlank { "Action • Drama" },
                    color = Color(0xFF38BDF8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val genreTokens = remember(item.genre) {
                item.genre.split("•", ",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals("Movie", ignoreCase = true) }
            }

            if (genreTokens.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    genreTokens.forEach { genreTag ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0x99131D2D),
                            border = BorderStroke(1.dp, Color(0x661E3A5F))
                        ) {
                            Text(
                                text = genreTag,
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPrimaryClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .height(42.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = primaryButtonText,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { }
                        .pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0x991E2636),
                    border = BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "My list",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FigmaCarouselSection(
    overline: String,
    title: String,
    actionLabel: String,
    items: List<DesktopMediaItem>,
    accessToken: String = "",
    onActionClick: () -> Unit,
    onItemClick: (DesktopMediaItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = overline,
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onActionClick() }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = actionLabel,
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = actionLabel,
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(items, key = { it.id }) { item ->
                DesktopFigmaMediaCard(
                    item = item,
                    accessToken = accessToken,
                    onClick = { onItemClick(item) },
                    modifier = Modifier
                        .width(248.dp)
                        .height(144.dp)
                )
            }
        }
    }
}

@Composable
fun DesktopFigmaMediaCard(
    item: DesktopMediaItem,
    accessToken: String = "",
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val thumbnailBitmap = rememberThumbnailBitmap(
        primaryUrl = item.thumbnailUrl,
        fallbackUrl = item.fallbackThumbnailUrl,
        accessToken = accessToken,
        size = ThumbnailSize.CARD
    )

    Box(
        modifier = modifier
            .clip(CardShape)
            .background(CardSurfaceColor)
            .border(1.dp, CardBorderColor, CardShape)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        // Poster / Thumbnail Layer
        if (thumbnailBitmap != null) {
            Image(
                bitmap = thumbnailBitmap,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Cinema card fallback using hoisted surface color
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CardSurfaceColor),
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

        // Duration Badge (Top Right)
        if (item.durationMs > 0L) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(BadgeBackground, BadgeShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = formatDuration(item.durationMs),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Bottom Scrim using hoisted brush
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(CardBottomScrimBrush)
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
                    text = item.genre,
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        val progressFraction = if (item.progressMs > 0L && item.durationMs > 0L) {
            (item.progressMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

        if (progressFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter)
                    .background(CardProgressTrackColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction)
                        .fillMaxHeight()
                        .background(CardProgressFillColor)
                )
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalMinutes = (durationMs / 60000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h %02dm".format(minutes) else "${minutes}m"
}
