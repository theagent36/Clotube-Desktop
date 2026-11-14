package com.clotube.desktop.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.clotube.desktop.data.DesktopContinueWatchingItem
import com.clotube.desktop.data.DesktopSeriesCache
import com.clotube.desktop.data.DesktopWatchHistoryManager
import com.clotube.desktop.data.DesktopWatchHistorySync
import com.clotube.desktop.data.DesktopWatchlistManager
import com.clotube.desktop.data.DesktopWatchlistSync
import com.clotube.desktop.data.WatchlistMediaType
import com.clotube.desktop.data.WatchlistStatus
import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.components.ThumbnailSize
import com.clotube.desktop.ui.components.rememberThumbnailBitmap
import com.clotube.desktop.ui.components.DesktopFramePacer
import com.clotube.desktop.ui.motion.FluidEntranceContainer
import com.clotube.desktop.ui.motion.FluidMotionKit
import com.clotube.desktop.lifecycle.AppLifecycleManager
import com.clotube.desktop.network.DesktopNetworkMonitor
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
    WATCHLIST("Watchlist", Icons.Rounded.Bookmarks),
    RECENTS("Recents", Icons.Rounded.History),
    SETTINGS("Settings", Icons.Rounded.Settings);

    companion object {
        // Compatibility alias so legacy DesktopNavSection.TV_SERIES calls resolve to SERIES
        val TV_SERIES: DesktopNavSection get() = SERIES
    }
}

private sealed interface DesktopActiveView {
    data class Series(val series: DesktopMediaItem, val episodeId: String?) : DesktopActiveView
    data class Movie(val movie: DesktopMediaItem) : DesktopActiveView
    data class Library(val section: DesktopNavSection) : DesktopActiveView
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
    isSyncing: Boolean = false,
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
    val coroutineScope = rememberCoroutineScope()

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
    val watchlistRevision = DesktopWatchlistManager.watchlistRevision.intValue

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

    val matchedWatchlistEntries = remember(cleanSearch, userEmail, watchlistRevision, matchedMovies, matchedSeries) {
        if (!isSearchActive) emptyList()
        else {
            val driveNormalized = (matchedMovies + matchedSeries).map { DesktopWatchlistManager.normalizeTitle(it.title) }.toSet()
            DesktopWatchlistManager.getVisibleEntries(userEmail).filter { entry ->
                entry.title.contains(cleanSearch, ignoreCase = true) &&
                    !driveNormalized.contains(entry.normalizedTitle)
            }.take(6)
        }
    }

    val cleanNormalized = remember(cleanSearch) { DesktopWatchlistManager.normalizeTitle(cleanSearch) }
    val hasExactWatchlistMatch = remember(cleanNormalized, userEmail, watchlistRevision) {
        if (!isSearchActive) true
        else DesktopWatchlistManager.getVisibleEntries(userEmail).any { it.normalizedTitle == cleanNormalized }
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
                        onSignOut = onSignOut,
                        onOpenSettings = { navigateTo(DesktopNavSection.SETTINGS) }
                    )

                    // Floating Global Search Overlay
                    if (isSearchActive) {
                        val totalResults = matchedMovies.size + matchedSeries.size + matchedEpisodes.size + matchedWatchlistEntries.size
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
                            if (totalResults == 0 && hasExactWatchlistMatch) {
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
                                    if (totalResults == 0) {
                                        item(key = "no_res_info") {
                                            Text(
                                                text = "No catalog results found for \"$cleanSearch\"",
                                                color = Color(0xFF64748B),
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                            )
                                        }
                                    }

                                    // SERIES SECTION
                                    if (matchedSeries.isNotEmpty()) {
                                        item(key = "hdr_series") {
                                            Text(
                                                text = "SERIES",
                                                color = Color(0xFF38BDF8),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.2.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }

                                        items(matchedSeries, key = { "search_series_${it.id}" }) { s ->
                                            val thumb = rememberThumbnailBitmap(s.thumbnailUrl, s.fallbackThumbnailUrl, accessToken, userEmail, ThumbnailSize.CARD)
                                            val watchlistStatus = remember(s.id, s.title, userEmail, watchlistRevision) {
                                                DesktopWatchlistManager.getStatusForMedia(userEmail, s.title, s.id)
                                            }

                                            SearchResultRowCard(
                                                onClick = {
                                                    searchQuery = ""
                                                    targetedEpisodeId = null
                                                    selectedSeriesForDetail = s
                                                }
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
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(
                                                            text = s.title,
                                                            color = Color.White,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f, fill = false)
                                                        )
                                                        if (watchlistStatus == WatchlistStatus.WATCHED) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = Color(0x1F10B981),
                                                                border = BorderStroke(1.dp, Color(0x4010B981))
                                                            ) {
                                                                Text(
                                                                    text = "Watched",
                                                                    color = Color(0xFF6EE7B7),
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                        } else if (watchlistStatus == WatchlistStatus.WATCH_LATER) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = Color(0x2460A5FA),
                                                                border = BorderStroke(1.dp, Color(0x4760A5FA))
                                                            ) {
                                                                Text(
                                                                    text = "Watch Later",
                                                                    color = Color(0xFF93C5FD),
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                        } else if (watchlistStatus == WatchlistStatus.REWATCH) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = Color(0xFF1E2534),
                                                                border = BorderStroke(1.dp, Color(0x33FFFFFF))
                                                            ) {
                                                                Text(
                                                                    text = "Rewatch",
                                                                    color = Color(0xFFF1F5F9),
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Text(text = s.genre.ifBlank { "Series" }, color = Color(0xFF64748B), fontSize = 11.sp, maxLines = 1)
                                                }
                                                Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF1E293B)) {
                                                    Text(text = "Series", color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
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
                                            SearchResultRowCard(
                                                onClick = {
                                                    searchQuery = ""
                                                    targetedEpisodeId = match.episode.id
                                                    selectedSeriesForDetail = match.parentSeries
                                                }
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
                                            val watchlistStatus = remember(m.id, m.title, userEmail, watchlistRevision) {
                                                DesktopWatchlistManager.getStatusForMedia(userEmail, m.title, m.id)
                                                    ?: if (DesktopWatchHistoryManager.isEpisodeWatched(userEmail, m.id)) {
                                                        WatchlistStatus.WATCHED
                                                    } else null
                                            }

                                            SearchResultRowCard(
                                                onClick = {
                                                    searchQuery = ""
                                                    selectedMovieForPlayer = m
                                                }
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
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(
                                                            text = m.title,
                                                            color = Color.White,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f, fill = false)
                                                        )
                                                        if (watchlistStatus == WatchlistStatus.WATCHED) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = Color(0x1F10B981),
                                                                border = BorderStroke(1.dp, Color(0x4010B981))
                                                            ) {
                                                                Text(
                                                                    text = "Watched",
                                                                    color = Color(0xFF6EE7B7),
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                        } else if (watchlistStatus == WatchlistStatus.WATCH_LATER) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = Color(0x2460A5FA),
                                                                border = BorderStroke(1.dp, Color(0x4760A5FA))
                                                            ) {
                                                                Text(
                                                                    text = "Watch Later",
                                                                    color = Color(0xFF93C5FD),
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                        } else if (watchlistStatus == WatchlistStatus.REWATCH) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = Color(0xFF1E2534),
                                                                border = BorderStroke(1.dp, Color(0x33FFFFFF))
                                                            ) {
                                                                Text(
                                                                    text = "Rewatch",
                                                                    color = Color(0xFFF1F5F9),
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Text(text = "${m.releaseYear} • ${m.genre}", color = Color(0xFF64748B), fontSize = 11.sp, maxLines = 1)
                                                }
                                                Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF1E293B)) {
                                                    Text(text = "Movie", color = Color(0xFFCBD5E1), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                }
                                            }
                                        }
                                    }

                                    // FROM YOUR WATCHLIST SECTION (Off-Drive / Custom / Deleted Titles)
                                    if (matchedWatchlistEntries.isNotEmpty()) {
                                        item(key = "hdr_watchlist") {
                                            Text(
                                                text = "FROM YOUR WATCHLIST",
                                                color = Color(0xFFF59E0B),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.2.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }

                                        items(matchedWatchlistEntries, key = { "search_wl_${it.id}" }) { entry ->
                                            SearchResultRowCard(
                                                onClick = {
                                                    // Toggle status on row click
                                                    val next = when (entry.status) {
                                                        WatchlistStatus.WATCHED -> WatchlistStatus.WATCH_LATER
                                                        WatchlistStatus.WATCH_LATER -> WatchlistStatus.REWATCH
                                                        WatchlistStatus.REWATCH -> WatchlistStatus.WATCHED
                                                    }
                                                    coroutineScope.launch {
                                                        DesktopWatchlistManager.updateEntryById(userEmail, entry.id, newStatus = next)
                                                        DesktopWatchlistSync.pushLocalWatchlist(userEmail, accessToken)
                                                    }
                                                }
                                            ) {
                                                val wlThumb = rememberThumbnailBitmap(
                                                    primaryUrl = entry.posterUrl?.takeIf { it.isNotBlank() },
                                                    fallbackUrl = null,
                                                    accessToken = accessToken,
                                                    userEmail = userEmail,
                                                    size = ThumbnailSize.CARD
                                                )

                                                Box(
                                                    modifier = Modifier
                                                        .width(40.dp)
                                                        .height(54.dp)
                                                        .clip(RoundedCornerShape(5.dp))
                                                        .background(Color(0xFF1A2232)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (wlThumb != null) {
                                                        Image(
                                                            bitmap = wlThumb,
                                                            contentDescription = entry.title,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    } else {
                                                        Icon(
                                                            imageVector = if (entry.mediaType == WatchlistMediaType.SERIES) Icons.Rounded.Tv else Icons.Rounded.Movie,
                                                            contentDescription = null,
                                                            tint = Color(0xFF94A3B8),
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = entry.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(text = "${entry.mediaType.label} • Not in Drive", color = Color(0xFF64748B), fontSize = 11.sp, maxLines = 1)
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = when (entry.status) {
                                                        WatchlistStatus.WATCHED -> Color(0x1F10B981)
                                                        WatchlistStatus.WATCH_LATER -> Color(0x2460A5FA)
                                                        WatchlistStatus.REWATCH -> Color(0xFF1E2534)
                                                    },
                                                    border = BorderStroke(
                                                        1.dp,
                                                        when (entry.status) {
                                                            WatchlistStatus.WATCHED -> Color(0x4010B981)
                                                            WatchlistStatus.WATCH_LATER -> Color(0x4760A5FA)
                                                            WatchlistStatus.REWATCH -> Color(0x33FFFFFF)
                                                        }
                                                    ),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .clickable {
                                                            val nextStatus = when (entry.status) {
                                                                WatchlistStatus.WATCHED -> WatchlistStatus.WATCH_LATER
                                                                WatchlistStatus.WATCH_LATER -> WatchlistStatus.REWATCH
                                                                WatchlistStatus.REWATCH -> WatchlistStatus.WATCHED
                                                            }
                                                            coroutineScope.launch {
                                                                DesktopWatchlistManager.updateEntryById(userEmail, entry.id, newStatus = nextStatus)
                                                                DesktopWatchlistSync.pushLocalWatchlist(userEmail, accessToken)
                                                            }
                                                        }
                                                        .pointerHoverIcon(PointerIcon.Hand)
                                                ) {
                                                    Text(
                                                        text = entry.status.label,
                                                        color = when (entry.status) {
                                                            WatchlistStatus.WATCHED -> Color(0xFF6EE7B7)
                                                            WatchlistStatus.WATCH_LATER -> Color(0xFF93C5FD)
                                                            WatchlistStatus.REWATCH -> Color(0xFFF1F5F9)
                                                        },
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Quick-Add Off-Drive Title Action Row
                                    if (isSearchActive && !hasExactWatchlistMatch) {
                                        item(key = "quick_add_watchlist_row") {
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        coroutineScope.launch {
                                                            DesktopWatchlistManager.upsertEntry(
                                                                email = userEmail,
                                                                title = cleanSearch,
                                                                mediaType = WatchlistMediaType.MOVIE,
                                                                status = WatchlistStatus.WATCHED,
                                                                driveId = null
                                                            )
                                                            DesktopWatchlistSync.pushLocalWatchlist(userEmail, accessToken)
                                                            DesktopWatchlistManager.resolveMissingOffDrivePosters(
                                                                email = userEmail,
                                                                accessToken = accessToken,
                                                                driveMovies = movies,
                                                                driveSeries = series
                                                            )
                                                        }
                                                    }
                                                    .pointerHoverIcon(PointerIcon.Hand),
                                                shape = RoundedCornerShape(8.dp),
                                                color = Color(0xFF10B981).copy(alpha = 0.12f),
                                                border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f))
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Add,
                                                        contentDescription = "Add",
                                                        tint = Color(0xFF34D399),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Text(
                                                        text = "Mark \"$cleanSearch\" as Watched (Save to Watchlist)",
                                                        color = Color(0xFF34D399),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
            val activeView: DesktopActiveView = when {
                selectedSeriesForDetail != null -> DesktopActiveView.Series(selectedSeriesForDetail!!, targetedEpisodeId)
                selectedMovieForPlayer != null -> DesktopActiveView.Movie(selectedMovieForPlayer!!)
                else -> DesktopActiveView.Library(currentSection)
            }

            AnimatedContent(
                targetState = activeView,
                transitionSpec = {
                    val enteringDetailOrPlayer = (targetState is DesktopActiveView.Movie || targetState is DesktopActiveView.Series) && initialState is DesktopActiveView.Library
                    val exitingToLibrary = targetState is DesktopActiveView.Library && (initialState is DesktopActiveView.Movie || initialState is DesktopActiveView.Series)

                    if (enteringDetailOrPlayer) {
                        (scaleIn(
                            initialScale = 0.90f,
                            animationSpec = tween(380, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
                        ) + fadeIn(
                            animationSpec = tween(280)
                        )).togetherWith(
                            scaleOut(
                                targetScale = 1.05f,
                                animationSpec = tween(260)
                            ) + fadeOut(
                                animationSpec = tween(200)
                            )
                        )
                    } else if (exitingToLibrary) {
                        (scaleIn(
                            initialScale = 1.04f,
                            animationSpec = tween(320, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
                        ) + fadeIn(
                            animationSpec = tween(240)
                        )).togetherWith(
                            scaleOut(
                                targetScale = 0.92f,
                                animationSpec = tween(260)
                            ) + fadeOut(
                                animationSpec = tween(180)
                            )
                        )
                    } else if (initialState is DesktopActiveView.Library && targetState is DesktopActiveView.Library) {
                        // DIRECTION-AWARE HORIZON SLIDE & DEPTH CROSS-DISSOLVE
                        // (Home <-> Movies <-> Series <-> Watchlist <-> Recents <-> Settings)
                        val fromSection = (initialState as DesktopActiveView.Library).section
                        val toSection = (targetState as DesktopActiveView.Library).section
                        val isMovingForward = toSection.ordinal > fromSection.ordinal

                        val slideOffset = 64 // Subtle 64px offset prevents motion sickness on wide desktop monitors

                        if (isMovingForward) {
                            (slideInHorizontally(
                                initialOffsetX = { slideOffset },
                                animationSpec = tween(300, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
                            ) + fadeIn(
                                animationSpec = tween(280)
                            ) + scaleIn(
                                initialScale = 0.985f,
                                animationSpec = tween(300, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
                            )).togetherWith(
                                slideOutHorizontally(
                                    targetOffsetX = { -slideOffset },
                                    animationSpec = tween(220, easing = FastOutLinearInEasing)
                                ) + fadeOut(
                                    animationSpec = tween(180)
                                ) + scaleOut(
                                    targetScale = 0.985f,
                                    animationSpec = tween(220)
                                )
                            )
                        } else {
                            (slideInHorizontally(
                                initialOffsetX = { -slideOffset },
                                animationSpec = tween(300, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
                            ) + fadeIn(
                                animationSpec = tween(280)
                            ) + scaleIn(
                                initialScale = 0.985f,
                                animationSpec = tween(300, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
                            )).togetherWith(
                                slideOutHorizontally(
                                    targetOffsetX = { slideOffset },
                                    animationSpec = tween(220, easing = FastOutLinearInEasing)
                                ) + fadeOut(
                                    animationSpec = tween(180)
                                ) + scaleOut(
                                    targetScale = 0.985f,
                                    animationSpec = tween(220)
                                )
                            )
                        }
                    } else {
                        (fadeIn(
                            animationSpec = tween(220)
                        ) + scaleIn(
                            initialScale = 0.98f,
                            animationSpec = tween(220, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
                        )).togetherWith(
                            fadeOut(
                                animationSpec = tween(180)
                            ) + scaleOut(
                                targetScale = 1.01f,
                                animationSpec = tween(180)
                            )
                        )
                    }
                },
                label = "CinemaScreenTransition"
            ) { viewState ->
                when (viewState) {
                    is DesktopActiveView.Series -> {
                        key(viewState.series.id) {
                            DesktopSeriesDetailScreen(
                                seriesItem = viewState.series,
                                accessToken = accessToken,
                                userEmail = userEmail,
                                initialEpisodeId = viewState.episodeId,
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
                        }
                    }
                    is DesktopActiveView.Movie -> {
                        val movieResumePos = remember(viewState.movie.id, userEmail, watchHistoryRevision) {
                            com.clotube.desktop.data.DesktopWatchHistoryManager.getPlaybackPosition(userEmail, viewState.movie.id)
                        }

                        key(viewState.movie.id) {
                            DesktopMoviePlayerScreen(
                                initialMovie = viewState.movie,
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
                        }
                    }
                    is DesktopActiveView.Library -> {
                        when (viewState.section) {
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
                                    onRescan = onRescan,
                                    isSyncing = isSyncing
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
                            DesktopNavSection.WATCHLIST -> {
                                FigmaWatchlistContent(
                                    userEmail = userEmail,
                                    accessToken = accessToken,
                                    movies = movies,
                                    series = series,
                                    onMovieClick = { movie ->
                                        selectedMovieForPlayer = movie
                                        onMediaClick(movie)
                                    },
                                    onSeriesClick = { seriesItem ->
                                        targetedEpisodeId = null
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
                            DesktopNavSection.SETTINGS -> {
                                DesktopSettingsScreen(
                                    userEmail = userEmail,
                                    userName = userName,
                                    onSignOut = onSignOut,
                                    onRescanLibrary = onRescan,
                                    onClearWatchHistory = {
                                        DesktopWatchHistoryManager.clearLocalHistory(userEmail)
                                        onClearHistory()
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
    }
}

@Composable
private fun SearchResultRowCard(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val coroutineScope = rememberCoroutineScope()
    val clickFlashAlpha = remember { Animatable(0f) }

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.985f
            isHovered -> 1.015f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "SearchRowScale"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isHovered -> Color(0xFF334155)
            else -> Color(0xFF1E2838)
        },
        animationSpec = tween(180),
        label = "SearchRowBorder"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (isHovered) 8.dp else 0.dp,
                shape = RoundedCornerShape(8.dp),
                spotColor = Color(0x99000000),
                ambientColor = Color(0x40000000)
            )
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHovered) Color(0xFF171F30) else Color(0xFF131824))
            .border(
                width = if (isHovered) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                coroutineScope.launch {
                    clickFlashAlpha.snapTo(0.3f)
                    clickFlashAlpha.animateTo(0f, animationSpec = tween(200))
                }
                onClick()
            }
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )

        if (clickFlashAlpha.value > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.White.copy(alpha = clickFlashAlpha.value * 0.35f))
            )
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
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp)
    ) {
        // Brand Logo Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            com.clotube.desktop.ui.components.ClotubeGlyphIcon(size = 32.dp)
            Text(
                text = "Clotube",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.3).sp
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // BROWSE SECTION OVERLINE
        Text(
            text = "BROWSE",
            color = Color(0xFF64748B),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Main Navigation List
        val navItems = listOf(
            DesktopNavSection.HOME,
            DesktopNavSection.MOVIES,
            DesktopNavSection.SERIES,
            DesktopNavSection.WATCHLIST,
            DesktopNavSection.RECENTS
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            navItems.forEach { section ->
                val isSelected = activeSection == section
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onSelectSection(section) }
                        .pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(10.dp),
                    color = when {
                        isSelected -> Color(0xFF131D31)
                        isHovered -> Color(0xFF0E131D)
                        else -> Color.Transparent
                    },
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) Color(0xFF1E283A) else Color.Transparent
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left 3dp Accent Indicator Pill for selected item
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(16.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (isSelected) Color(0xFF3B82F6) else Color.Transparent)
                        )

                        Spacer(modifier = Modifier.width(9.dp))

                        val itemIcon = if (section == DesktopNavSection.WATCHLIST) {
                            if (isSelected) Icons.Rounded.Bookmarks else Icons.Rounded.BookmarkBorder
                        } else {
                            section.icon
                        }

                        Icon(
                            imageVector = itemIcon,
                            contentDescription = section.label,
                            tint = when {
                                isSelected -> Color(0xFF3B82F6)
                                isHovered -> Color(0xFFCBD5E1)
                                else -> Color(0xFF64748B)
                            },
                            modifier = Modifier.size(18.dp)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = section.label,
                            color = when {
                                isSelected -> Color.White
                                isHovered -> Color(0xFFE2E8F0)
                                else -> Color(0xFF94A3B8)
                            },
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Bottom Settings Navigation Item
        val isSettingsSelected = activeSection == DesktopNavSection.SETTINGS
        val settingsInteraction = remember { MutableInteractionSource() }
        val isSettingsHovered by settingsInteraction.collectIsHoveredAsState()

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(
                    interactionSource = settingsInteraction,
                    indication = null
                ) { onSelectSection(DesktopNavSection.SETTINGS) }
                .pointerHoverIcon(PointerIcon.Hand),
            shape = RoundedCornerShape(10.dp),
            color = when {
                isSettingsSelected -> Color(0xFF131824)
                isSettingsHovered -> Color(0xFF0E131D)
                else -> Color.Transparent
            },
            border = BorderStroke(
                width = 1.dp,
                color = if (isSettingsSelected) Color(0xFF1E283A) else Color.Transparent
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isSettingsSelected) Color(0xFF60A5FA) else Color.Transparent)
                )

                Spacer(modifier = Modifier.width(9.dp))

                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    tint = when {
                        isSettingsSelected -> Color(0xFF60A5FA)
                        isSettingsHovered -> Color(0xFFCBD5E1)
                        else -> Color(0xFF64748B)
                    },
                    modifier = Modifier.size(18.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "Settings",
                    color = when {
                        isSettingsSelected -> Color.White
                        isSettingsHovered -> Color(0xFFE2E8F0)
                        else -> Color(0xFF94A3B8)
                    },
                    fontSize = 13.sp,
                    fontWeight = if (isSettingsSelected) FontWeight.SemiBold else FontWeight.Medium
                )
            }
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
    onSignOut: () -> Unit,
    onOpenSettings: () -> Unit = {}
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
        // Search Input Pill
        Surface(
            modifier = Modifier
                .width(420.dp)
                .height(40.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0E121B),
            border = BorderStroke(1.dp, Color(0xFF1A2230))
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
                            fontSize = 13.sp
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search movies, series...",
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
                        color = Color(0xFF141924),
                        border = BorderStroke(1.dp, Color(0xFF222B3D))
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

        // Right Actions (Restrained Online Status Pill + Account)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val isOnline by DesktopNetworkMonitor.isOnline.collectAsState()

            // STUDIO ONLINE / OFFLINE STATUS PILL (Non-fluorescent, reactive)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isOnline) Color(0xFF0E131C) else Color(0xFF1A1014),
                border = BorderStroke(1.dp, if (isOnline) Color(0xFF1B2433) else Color(0xFF3B1822))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) Color(0xFF10B981) else Color(0xFFEF4444)) // Emerald when online, Red when offline
                    )
                    Icon(
                        imageVector = if (isOnline) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                        contentDescription = if (isOnline) "Online" else "Offline",
                        tint = if (isOnline) Color(0xFF94A3B8) else Color(0xFFF87171),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = if (isOnline) "Online" else "Offline",
                        color = if (isOnline) Color(0xFFCBD5E1) else Color(0xFFFCA5A5),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .clickable { showAccountMenu = true }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8))
                                )
                            ),
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
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Column {
                        Text(
                            text = userName,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = "Google account",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }

                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Account menu",
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = showAccountMenu,
                    onDismissRequest = { showAccountMenu = false },
                    modifier = Modifier
                        .background(Color(0xFF0E131C))
                        .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(8.dp))
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = "Settings",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Settings",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        },
                        onClick = {
                            showAccountMenu = false
                            onOpenSettings()
                        }
                    )
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
                                    tint = Color(0xFFFB7185),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Sign Out ($userEmail)",
                                    color = Color(0xFFFB7185),
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
                        .background(Color(0xFF94A3B8))
                )
                Text(
                    text = "CONTINUE WATCHING",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
            }

            Text(
                text = "${displayItems.size} in progress",
                color = Color(0xFF64748B),
                fontSize = 11.sp
            )
        }

        // CAROUSEL LAZYROW WITH 16.dp OVERDRAW BUFFER
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    // Expand measurable width by 16dp on left and right so scaled cards never hit the clip boundary
                    val overdraw = 16.dp.roundToPx()
                    val placeable = measurable.measure(
                        constraints.copy(
                            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + (overdraw * 2) else constraints.maxWidth
                        )
                    )
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.placeRelative(-overdraw, 0)
                    }
                },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(displayItems, key = { _, it -> it.mediaId }) { index, item ->
                FluidEntranceContainer(index = index) {
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
}

@Composable
private fun ContinueWatchingCard(
    item: DesktopContinueWatchingItem,
    userEmail: String,
    accessToken: String,
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
        label = "continue_card_scale"
    )

    // Studio Top-Lit Glass Border (Replaces cyan rectangle box)
    val hoveredBorderBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0x55FFFFFF), // Subtle top rim light
                Color(0x14FFFFFF)  // Fades softly into shadow at bottom
            )
        )
    }

    val defaultBorderBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF222B3D),
                Color(0xFF131824)
            )
        )
    }

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
                        color = Color(0xFF94A3B8),
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
                        color = Color(0xFFCBD5E1),
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

            // Studio Frosted Glass Play Button on Hover (Replaces cyan blob)
            if (isHovered) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xD90A0E17))
                            .border(1.dp, Color(0x33FFFFFF), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
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

                // Bottom Progress Bar: Clean Ice-to-Cobalt Gradient (Restrained)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x331E2838))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(item.progressFraction.coerceIn(0.02f, 1f))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF2563EB), Color(0xFF60A5FA))
                                )
                            )
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
    onRescan: () -> Unit = {},
    isSyncing: Boolean = false
) {
    val scrollState = rememberScrollState()

    val globalRevision = DesktopWatchHistoryManager.historyRevision.intValue
    val watchlistRevision = DesktopWatchlistManager.watchlistRevision.intValue
    val continueWatchingItems = remember(userEmail, movies, series, watchHistoryRevision, globalRevision, watchlistRevision) {
        DesktopWatchHistoryManager.getContinueWatching(userEmail, movies, series)
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
            if (isSyncing) {
                // 1. ACTIVE INDEXING STATE
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D111A)),
                    border = BorderStroke(1.dp, Color(0xFF1B2234)),
                    modifier = Modifier.width(440.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF00E5FF),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Indexing items from Drive...",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Scanning your Google Drive for 'Movies' and 'Series' folders. This will only take a moment.",
                            color = Color(0xFF8E9BAE),
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            } else {
                // 2. VERIFIED EMPTY STATE (Only shown when scan is 100% finished and 0 items found)
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D111A)),
                    border = BorderStroke(1.dp, Color(0xFF1B2234)),
                    modifier = Modifier.width(480.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF131D2E)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudQueue,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "No Media Indexed in Google Drive",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Clotube connected to your account, but found 0 movies or series folders in Drive. Check that your folders are named 'Movies' or 'Series', or verify your internet connection.",
                            color = Color(0xFF8E9BAE),
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(22.dp))

                        Button(
                            onClick = onRescan,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Rescan Cloud Library", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
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
                overline = "CONTINUE WATCHING",
                title = "Continue Watching",
                actionLabel = "View all",
                items = continueWatching.take(5),
                accessToken = accessToken,
                userEmail = userEmail,
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
                userEmail = userEmail,
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
                userEmail = userEmail,
                onActionClick = { onNavigateSection(DesktopNavSection.SERIES) },
                onItemClick = onSeriesClick
            )
        }
    }
}

@Composable
private fun StudioCategoryChip(
    title: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .pointerHoverIcon(PointerIcon.Hand),
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) Color(0xFFE2E8F0) else Color(0xFF12151E),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) Color(0xFFE2E8F0) else Color(0xFF1E2433)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                color = if (isSelected) Color(0xFF090A0F) else Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )

            // Embedded Item Count Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) Color(0x26000000) else Color(0xFF1A202C))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "$count",
                    color = if (isSelected) Color(0xFF090A0F) else Color(0xFF64748B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
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
    val globalRevision = DesktopWatchHistoryManager.historyRevision.intValue
    val watchlistRevision = DesktopWatchlistManager.watchlistRevision.intValue
    val lastWatchedMovie = remember(movies, userEmail, watchHistoryRevision, globalRevision, watchlistRevision) {
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
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp
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

                        StudioCategoryChip(
                            title = genre,
                            count = count,
                            isSelected = isSelected,
                            onClick = { selectedGenre = genre }
                        )
                    }
                }
            }
        }

        // 5-Column Movies Grid
        val rows = filteredMovies.chunked(5)
        itemsIndexed(rows, key = { _, row -> row.joinToString { it.id } }) { rowIndex, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                for (i in 0 until 5) {
                    val item = rowItems.getOrNull(i)
                    if (item != null) {
                        val index = rowIndex * 5 + i
                        FluidEntranceContainer(
                            index = index,
                            modifier = Modifier.weight(1f)
                        ) {
                            MediaCardContextMenuBox(
                                item = item,
                                userEmail = userEmail,
                                accessToken = accessToken
                            ) {
                                DesktopSeriesGridCard(
                                    seriesItem = item,
                                    userEmail = userEmail,
                                    accessToken = accessToken,
                                    onClick = { onMovieClick(item) },
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9.5f)
                                )
                            }
                        }
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
                        .background(Color(0xFF94A3B8))
                )
                Text(
                    text = if (isResume) "RESUME WATCHING" else "FEATURED MOVIE",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
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
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF090A0F)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(38.dp).pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF090A0F),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isResume) "Resume watching" else "Watch now",
                        color = Color(0xFF090A0F),
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
                    color = Color(0x1FFFFFFF),
                    border = BorderStroke(1.dp, Color(0x26FFFFFF))
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
                                listOf(Color(0xFF2563EB), Color(0xFF60A5FA))
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
    val globalRevision = DesktopWatchHistoryManager.historyRevision.intValue
    val watchlistRevision = DesktopWatchlistManager.watchlistRevision.intValue
    val lastWatchedSeries = remember(series, userEmail, watchHistoryRevision, globalRevision, watchlistRevision) {
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
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp
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

                        StudioCategoryChip(
                            title = genre,
                            count = count,
                            isSelected = isSelected,
                            onClick = { selectedGenre = genre }
                        )
                    }
                }
            }
        }

        // 5-Column Series Grid
        val rows = filteredSeries.chunked(5)
        itemsIndexed(rows, key = { _, row -> row.joinToString { it.id } }) { rowIndex, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                for (i in 0 until 5) {
                    val item = rowItems.getOrNull(i)
                    if (item != null) {
                        val index = rowIndex * 5 + i
                        FluidEntranceContainer(
                            index = index,
                            modifier = Modifier.weight(1f)
                        ) {
                            MediaCardContextMenuBox(
                                item = item,
                                userEmail = userEmail,
                                accessToken = accessToken
                            ) {
                                DesktopSeriesGridCard(
                                    seriesItem = item,
                                    userEmail = userEmail,
                                    accessToken = accessToken,
                                    onClick = { onSeriesClick(item, null) },
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9.5f)
                                )
                            }
                        }
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
                        .background(Color(0xFF94A3B8))
                )
                Text(
                    text = if (isResume) "RESUME EPISODE" else "FEATURED SERIES",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
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
                    color = Color(0xFFCBD5E1),
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF090A0F)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(38.dp).pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF090A0F),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isResume && resumeItem != null) {
                            "Resume S${resumeItem.seasonNumber}E${resumeItem.episodeNumber}"
                        } else {
                            "View Episodes"
                        },
                        color = Color(0xFF090A0F),
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
                    color = Color(0x1FFFFFFF),
                    border = BorderStroke(1.dp, Color(0x26FFFFFF))
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
                                listOf(Color(0xFF2563EB), Color(0xFF60A5FA))
                            )
                        )
                )
            }
        }
    }
}

@Composable
fun MediaCardContextMenuBox(
    item: DesktopMediaItem,
    userEmail: String,
    accessToken: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val revision = DesktopWatchlistManager.watchlistRevision.intValue
    val currentStatus = remember(item.id, item.title, userEmail, revision) {
        DesktopWatchlistManager.getStatusForMedia(userEmail, item.title, item.id)
    }

    var isPopupMounted by remember { mutableStateOf(false) }
    var targetExpanded by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(IntOffset.Zero) }
    val coroutineScope = rememberCoroutineScope()

    val dismissMenu: () -> Unit = {
        targetExpanded = false
    }

    Box(
        modifier = modifier.pointerInput(item.id, userEmail) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        val pos = event.changes.firstOrNull()?.position
                        if (pos != null) {
                            menuOffset = IntOffset(pos.x.roundToInt(), pos.y.roundToInt())
                            isPopupMounted = true
                            targetExpanded = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
        }
    ) {
        content()

        if (isPopupMounted) {
            Popup(
                offset = menuOffset,
                onDismissRequest = dismissMenu,
                properties = PopupProperties(focusable = true)
            ) {
                val scaleAnim = remember { Animatable(0.86f) }
                val alphaAnim = remember { Animatable(0f) }
                val offsetYAnim = remember { Animatable(-8f) }

                LaunchedEffect(targetExpanded) {
                    if (targetExpanded) {
                        launch {
                            alphaAnim.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(
                                    durationMillis = 160,
                                    easing = FluidMotionKit.CinemaDecel
                                )
                            )
                        }
                        launch {
                            offsetYAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = 0.78f,
                                    stiffness = 460f
                                )
                            )
                        }
                        scaleAnim.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = 0.76f,
                                stiffness = 480f
                            )
                        )
                    } else {
                        launch {
                            scaleAnim.animateTo(
                                targetValue = 0.92f,
                                animationSpec = tween(
                                    durationMillis = 120,
                                    easing = FastOutLinearInEasing
                                )
                            )
                        }
                        alphaAnim.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(
                                durationMillis = 120,
                                easing = FastOutLinearInEasing
                            )
                        )
                        isPopupMounted = false
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0F141F),
                    border = BorderStroke(1.dp, Color(0xFF263248)),
                    shadowElevation = 18.dp,
                    modifier = Modifier
                        .width(188.dp)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = scaleAnim.value
                            scaleY = scaleAnim.value
                            translationY = offsetYAnim.value
                            alpha = alphaAnim.value
                        }
                ) {
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = "SET WATCH STATUS",
                            color = Color(0xFF64748B),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )

                        val options = listOf(
                            Triple(WatchlistStatus.WATCHED, Icons.Rounded.CheckCircle, Color(0xFF10B981)),
                            Triple(WatchlistStatus.WATCH_LATER, Icons.Rounded.BookmarkAdd, Color(0xFF60A5FA)),
                            Triple(WatchlistStatus.REWATCH, Icons.Rounded.Replay, Color(0xFFCBD5E1))
                        )

                        options.forEachIndexed { index, (statusOption, iconVector, accentColor) ->
                            val isSelected = currentStatus == statusOption
                            var rowHovered by remember { mutableStateOf(false) }

                            val selectedBg = when (statusOption) {
                                WatchlistStatus.WATCHED -> Color(0x1F10B981)
                                WatchlistStatus.WATCH_LATER -> Color(0x2460A5FA)
                                WatchlistStatus.REWATCH -> Color(0xFF1E2534)
                            }
                            val selectedTextColor = when (statusOption) {
                                WatchlistStatus.WATCHED -> Color(0xFF6EE7B7)
                                WatchlistStatus.WATCH_LATER -> Color(0xFF93C5FD)
                                WatchlistStatus.REWATCH -> Color(0xFFF1F5F9)
                            }

                            val itemAlpha = remember { Animatable(0f) }
                            val itemSlideX = remember { Animatable(-8f) }

                            LaunchedEffect(targetExpanded) {
                                if (targetExpanded) {
                                    delay(index * 18L)
                                    launch {
                                        itemAlpha.animateTo(
                                            targetValue = 1f,
                                            animationSpec = tween(150, easing = FluidMotionKit.CinemaDecel)
                                        )
                                    }
                                    itemSlideX.animateTo(
                                        targetValue = 0f,
                                        animationSpec = FluidMotionKit.SilkSpring
                                    )
                                }
                            }

                            val hoverNudgeX by animateFloatAsState(
                                targetValue = if (rowHovered) 3f else 0f,
                                animationSpec = FluidMotionKit.TactilePress,
                                label = "ContextMenuRowNudge"
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        when {
                                            rowHovered -> Color(0xFF1A2436)
                                            isSelected -> selectedBg
                                            else -> Color.Transparent
                                        }
                                    )
                                    .clickable {
                                        dismissMenu()
                                        coroutineScope.launch {
                                            DesktopWatchlistManager.upsertEntry(
                                                email = userEmail,
                                                title = item.title,
                                                mediaType = if (item.isSeries) WatchlistMediaType.SERIES else WatchlistMediaType.MOVIE,
                                                status = statusOption,
                                                driveId = item.id
                                            )
                                            DesktopWatchlistSync.pushLocalWatchlist(userEmail, accessToken)
                                        }
                                    }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val ev = awaitPointerEvent()
                                                when (ev.type) {
                                                    PointerEventType.Enter -> rowHovered = true
                                                    PointerEventType.Exit -> rowHovered = false
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .graphicsLayer {
                                        alpha = itemAlpha.value
                                        translationX = itemSlideX.value + hoverNudgeX
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = iconVector,
                                        contentDescription = statusOption.label,
                                        tint = accentColor,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = statusOption.label,
                                        color = if (isSelected) selectedTextColor else Color(0xFFCBD5E1),
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Active",
                                        tint = accentColor,
                                        modifier = Modifier.size(14.dp)
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

@Composable
fun DesktopSeriesGridCard(
    seriesItem: DesktopMediaItem,
    userEmail: String,
    accessToken: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val cardScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.97f
            isHovered -> 1.03f
            else -> 1.0f
        },
        animationSpec = com.clotube.desktop.ui.motion.FluidMotionKit.SilkSpring,
        label = "series_card_scale"
    )

    // Studio Top-Lit Glass Border (Replaces cyan rectangle box)
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

    val posterBitmap = rememberThumbnailBitmap(
        primaryUrl = seriesItem.thumbnailUrl,
        fallbackUrl = seriesItem.fallbackThumbnailUrl,
        accessToken = accessToken,
        userEmail = userEmail,
        size = ThumbnailSize.CARD
    )

    val cachedSeries = remember(seriesItem.id, userEmail) {
        DesktopSeriesCache.get(userEmail, seriesItem.id)
    }
    val seasonCount = cachedSeries?.seasons?.size ?: 0

    val watchlistRevision = DesktopWatchlistManager.watchlistRevision.intValue
    val historyRevision = DesktopWatchHistoryManager.historyRevision.intValue

    val cardWatchlistStatus = remember(seriesItem.id, seriesItem.title, userEmail, watchlistRevision) {
        if (userEmail.isBlank()) null
        else DesktopWatchlistManager.getStatusForMedia(userEmail, seriesItem.title, seriesItem.id)
    }
    val isCardHistoryWatched = remember(seriesItem.id, seriesItem.isSeries, userEmail, historyRevision) {
        !seriesItem.isSeries && userEmail.isNotBlank() && DesktopWatchHistoryManager.isEpisodeWatched(userEmail, seriesItem.id)
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
                onClick = onClick
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
                        color = Color(0xFF94A3B8),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Dark vignette overlay
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

            // Season Count / Movie Badge
            if (seasonCount > 0) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xD90C111C),
                    border = BorderStroke(1.dp, Color(0xFF242E40)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "$seasonCount${if (seasonCount == 1) "Season" else "Seasons"}",
                        color = Color(0xFFCBD5E1),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
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
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Watched",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(12.dp)
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
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bookmark,
                            contentDescription = "Watch Later",
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(12.dp)
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
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Replay,
                            contentDescription = "Rewatch",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(12.dp)
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

            // Card Bottom Details
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                Text(
                    text = seriesItem.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = seriesItem.genre.ifBlank { "Series" },
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
    movies: List<DesktopMediaItem> = emptyList(),
    series: List<DesktopMediaItem> = emptyList(),
    watchHistoryRevision: Int,
    onMovieClick: (DesktopMediaItem) -> Unit,
    onSeriesEpisodeClick: (DesktopMediaItem, String) -> Unit = { _, _ -> },
    onHistoryChanged: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    var isClearing by remember { mutableStateOf(false) }

    val globalRevision = DesktopWatchHistoryManager.historyRevision.intValue
    val historyItems = remember(userEmail, movies, series, watchHistoryRevision, globalRevision) {
        DesktopWatchHistoryManager.getAllWatchHistory(userEmail, movies, series)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 8.dp)
    ) {
        // ----------------------------------------------------
        // HEADER BLOCK
        // ----------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = "YOUR WATCH HISTORY",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Recents",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.4).sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Jump back into what you've watched.",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp
                )
            }

            if (historyItems.isNotEmpty()) {
                val clearInteraction = remember { MutableInteractionSource() }
                val isClearHovered by clearInteraction.collectIsHoveredAsState()

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = clearInteraction,
                            indication = null,
                            enabled = !isClearing
                        ) {
                            isClearing = true
                            coroutineScope.launch {
                                DesktopWatchHistoryManager.clearAllHistory(userEmail)
                                DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken, force = true)
                                onHistoryChanged()
                                isClearing = false
                            }
                        }
                        .pointerHoverIcon(if (!isClearing) PointerIcon.Hand else PointerIcon.Default),
                    shape = RoundedCornerShape(8.dp),
                    color = if (isClearHovered) Color(0x1AE11D48) else Color(0xFF121622),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isClearHovered) Color(0x33FB7185) else Color(0xFF1E2838)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = "Clear watch history",
                            tint = if (isClearHovered) Color(0xFFFB7185) else Color(0xFF94A3B8),
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (isClearing) "Clearing..." else "Clear watch history",
                            color = if (isClearHovered) Color(0xFFFB7185) else Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ----------------------------------------------------
        // RECENT ITEMS LIST OR EMPTY STATE
        // ----------------------------------------------------
        if (historyItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0B0E16))
                    .border(1.dp, Color(0xFF161D2B), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.HistoryToggleOff,
                        contentDescription = null,
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(42.dp)
                    )
                    Text(
                        text = "No watch history",
                        color = Color(0xFFCBD5E1),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Movies and series you watch will appear here.",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 64.dp)
            ) {
                items(historyItems, key = { it.mediaId }) { item ->
                    DesktopRecentHistoryCard(
                        item = item,
                        userEmail = userEmail,
                        accessToken = accessToken,
                        onClick = {
                            if (item.isSeries && !item.seriesId.isNullOrBlank()) {
                                val matched = series.find { it.id == item.seriesId }
                                if (matched != null) {
                                    onSeriesEpisodeClick(matched, item.mediaId)
                                } else {
                                    val dummyMedia = DesktopMediaItem(
                                        id = item.seriesId,
                                        title = item.title,
                                        genre = "Series",
                                        streamUrl = ""
                                    )
                                    onSeriesEpisodeClick(dummyMedia, item.mediaId)
                                }
                            } else {
                                val matched = movies.find { it.id == item.mediaId }
                                if (matched != null) {
                                    onMovieClick(matched)
                                } else {
                                    val dummyMovie = DesktopMediaItem(
                                        id = item.mediaId,
                                        title = item.title,
                                        genre = item.subtitle.ifBlank { "Movie" },
                                        streamUrl = ""
                                    )
                                    onMovieClick(dummyMovie)
                                }
                            }
                        },
                        onRemove = {
                            coroutineScope.launch {
                                DesktopWatchHistoryManager.removeHistoryItem(userEmail, item.mediaId)
                                DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken, force = true)
                                onHistoryChanged()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopRecentHistoryCard(
    item: DesktopContinueWatchingItem,
    userEmail: String,
    accessToken: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isHovered by remember { mutableStateOf(false) }

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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
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
        color = if (isHovered) Color(0xFF141C2B) else Color(0xFF0F141F),
        border = BorderStroke(
            width = 1.dp,
            brush = if (isHovered) hoveredBorderBrush else defaultBorderBrush
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ----------------------------------------------------
            // THUMBNAIL CONTAINER WITH HOVER PLAY DISC
            // ----------------------------------------------------
            Box(
                modifier = Modifier
                    .width(114.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0A0D14))
            ) {
                val thumb = rememberThumbnailBitmap(
                    primaryUrl = item.thumbnailUrl,
                    fallbackUrl = item.fallbackThumbnailUrl,
                    accessToken = accessToken,
                    userEmail = userEmail
                )
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Vignette
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0x66000000))
                            )
                        )
                )

                // Frosted Play Disc on Hover
                if (isHovered) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
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
            }

            Spacer(modifier = Modifier.width(16.dp))

            // ----------------------------------------------------
            // TITLE & SUBTITLE
            // ----------------------------------------------------
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.subtitle.ifBlank { if (item.isSeries) "Series Episode" else "Feature Film" },
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(20.dp))

            // ----------------------------------------------------
            // PROGRESS BAR & STATUS
            // ----------------------------------------------------
            Column(
                modifier = Modifier.width(200.dp),
                horizontalAlignment = Alignment.End
            ) {
                val percent = (item.progressFraction * 100).toInt()
                Text(
                    text = if (percent > 0) "$percent% watched" else "Not started",
                    color = if (percent > 0) Color(0xFFCBD5E1) else Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = if (percent > 0) FontWeight.Medium else FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x331E2838))
                ) {
                    if (percent > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(item.progressFraction.coerceIn(0.02f, 1f))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFF2563EB), Color(0xFF60A5FA))
                                    )
                                )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // ----------------------------------------------------
            // REMOVE ITEM GHOST BUTTON
            // ----------------------------------------------------
            val removeInteraction = remember { MutableInteractionSource() }
            val isRemoveHovered by removeInteraction.collectIsHoveredAsState()

            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (isRemoveHovered) Color(0x1AE11D48) else Color.Transparent)
                    .clickable(
                        interactionSource = removeInteraction,
                        indication = null,
                        onClick = onRemove
                    )
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove from history",
                    tint = if (isRemoveHovered) Color(0xFFFB7185) else Color(0xFF64748B),
                    modifier = Modifier.size(16.dp)
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
                        .background(Color(0xFF94A3B8)) // Studio Ice Slate
                )
                Text(
                    text = if (isSeries) "FEATURED SERIES IN YOUR DRIVE" else "FEATURED IN YOUR DRIVE",
                    color = Color(0xFF94A3B8), // Non-fluorescent muted slate
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = item.title.uppercase(),
                color = Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.3).sp,
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
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = "•", color = Color(0xFF475569), fontSize = 12.sp)
                }

                Text(
                    text = item.genre.ifBlank { if (isSeries) "Series" else "Movie" },
                    color = Color(0xFFCBD5E1), // Clean neutral silver
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Genre Badges
            val genreTokens = remember(item.genre) {
                item.genre.split("•", ",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals("Movie", ignoreCase = true) && !it.equals("TV Series", ignoreCase = true) && !it.equals("Series", ignoreCase = true) }
            }

            if (genreTokens.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    genreTokens.take(4).forEach { genreTag ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0x331E293B),
                            border = BorderStroke(1.dp, Color(0x33475569))
                        ) {
                            Text(
                                text = genreTag,
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(22.dp))
            } else {
                Spacer(modifier = Modifier.height(18.dp))
            }

            // Professional Action CTAs
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // High-Contrast Pure White Primary CTA
                Button(
                    onClick = {
                        if (isSeries) onSeriesClick(item)
                        else onMovieClick(item)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF090A0F)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .height(40.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF090A0F),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSeries) "View series" else "Resume watching",
                        color = Color(0xFF090A0F),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Frosted Glass Secondary CTA
                Surface(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { }
                        .pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x1FFFFFFF),
                    border = BorderStroke(1.dp, Color(0x26FFFFFF))
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

        // 1. PHASE A: SLIDE TRANSITION (Paced at adaptive frame rate, eco-mode suspended)
        if (outgoingIndex != -1) {
            val slideDurationMs = 500L
            var slideElapsedMs = 0L

            while (slideElapsedMs < slideDurationMs) {
                // Check Background Eco Mode suspension
                if (AppLifecycleManager.isEcoSuspended) {
                    kotlinx.coroutines.delay(250L)
                    continue
                }

                val step = DesktopFramePacer.targetFrameDelayMs
                kotlinx.coroutines.delay(step)
                slideElapsedMs += step
                val rawFrac = (slideElapsedMs.toFloat() / slideDurationMs.toFloat()).coerceIn(0f, 1f)
                slideTransitionFraction = androidx.compose.animation.core.FastOutSlowInEasing.transform(rawFrac)
            }
            slideTransitionFraction = 1f
            outgoingIndex = -1
        } else {
            slideTransitionFraction = 1f
        }

        // 2. PHASE B: COUNTDOWN TIMER (5000ms display phase)
        timerProgressFraction = 0f
        val countdownDurationMs = 5000L
        var activeCountdownElapsedMs = 0L

        while (activeCountdownElapsedMs < countdownDurationMs) {
            // Check Background Eco Mode suspension
            if (AppLifecycleManager.isEcoSuspended) {
                kotlinx.coroutines.delay(250L)
                continue
            }

            val step = DesktopFramePacer.targetFrameDelayMs
            kotlinx.coroutines.delay(step)
            activeCountdownElapsedMs += step
            timerProgressFraction = (activeCountdownElapsedMs.toFloat() / countdownDurationMs.toFloat()).coerceIn(0f, 1f)
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
                .height(3.dp)
                .background(Color(0x26FFFFFF))
                .drawWithContent {
                    drawContent()
                    val progress = timerProgressFraction
                    if (progress > 0f) {
                        drawRect(
                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(Color(0x80FFFFFF), Color.White)
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

                // Slide Dots: Clean White Active Pill instead of neon cyan
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    shuffledItems.take(8).forEachIndexed { index, _ ->
                        val isSelected = index == (currentIndex % 8)
                        Box(
                            modifier = Modifier
                                .width(if (isSelected) 18.dp else 6.dp)
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (isSelected) Color.White else Color(0x40FFFFFF))
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
                        .background(Color(0xFF94A3B8))
                )
                Text(
                    text = overline,
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
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
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
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
                            color = Color(0x331E293B),
                            border = BorderStroke(1.dp, Color(0x33475569))
                        ) {
                            Text(
                                text = genreTag,
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF090A0F)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .height(40.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF090A0F),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = primaryButtonText,
                        color = Color(0xFF090A0F),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { }
                        .pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0x1FFFFFFF),
                    border = BorderStroke(1.dp, Color(0x26FFFFFF))
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
    userEmail: String = "",
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
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.3).sp
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

        // CAROUSEL LAZYROW WITH 16.dp OVERDRAW BUFFER
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    // Expand measurable width by 16dp on left and right so scaled cards never hit the clip boundary
                    val overdraw = 16.dp.roundToPx()
                    val placeable = measurable.measure(
                        constraints.copy(
                            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + (overdraw * 2) else constraints.maxWidth
                        )
                    )
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.placeRelative(-overdraw, 0)
                    }
                },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                FluidEntranceContainer(index = index) {
                    MediaCardContextMenuBox(
                        item = item,
                        userEmail = userEmail,
                        accessToken = accessToken
                    ) {
                        DesktopFigmaMediaCard(
                            item = item,
                            accessToken = accessToken,
                            userEmail = userEmail,
                            onClick = { onItemClick(item) },
                            modifier = Modifier
                                .width(248.dp)
                                .height(144.dp)
                        )
                    }
                }
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
