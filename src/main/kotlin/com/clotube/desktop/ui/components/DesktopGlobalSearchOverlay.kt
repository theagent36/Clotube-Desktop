package com.clotube.desktop.ui.components

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.data.DesktopDriveCatalogMatcher
import com.clotube.desktop.data.DesktopGlobalSearchEngine
import com.clotube.desktop.data.DesktopSearchHistoryManager
import com.clotube.desktop.data.DesktopWatchHistoryManager
import com.clotube.desktop.data.DesktopWatchlistManager
import com.clotube.desktop.data.RecentSearchEntry
import com.clotube.desktop.data.WatchlistStatus
import com.clotube.desktop.image.DesktopCanonicalImageCache
import com.clotube.desktop.image.rememberCanonicalArtwork
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.model.GlobalSearchResult
import com.clotube.desktop.model.SearchMediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Obsidian Cinema Palette
private val ObsidianCanvas = Color(0xFF07090E)
private val ObsidianSurface = Color(0xFF10131B)
private val ObsidianElevated = Color(0xFF181C26)
private val ObsidianBorder = Color(0xFF222938)
private val AccentBlue = Color(0xFF3877EE)
private val AccentBlueSoft = Color(0xFF93C5FD)
private val EmeraldText = Color(0xFF34D399)
private val EmeraldFill = Color(0x2110B981)
private val EmeraldBorder = Color(0x5210B981)
private val SlateMuted = Color(0xFF64748B)
private val SlateSecondary = Color(0xFF94A3B8)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextBody = Color(0xFFCBD5E1)
private val StarAmber = Color(0xFFFBBF24)

enum class SearchAvailabilityFilter(val label: String) {
    ALL("All"),
    IN_DRIVE("In Drive"),
    NOT_IN_DRIVE("Not in Drive")
}

enum class SearchMediaTypeFilter(val label: String) {
    ALL_TYPES("All Types"),
    MOVIES("Movies"),
    TV_SERIES("TV Series")
}

enum class SearchWatchlistFilter(val label: String) {
    ANY_STATUS("Any Status"),
    WATCH_LATER("Watch Later"),
    WATCHED("Watched"),
    REWATCH("Rewatch")
}

enum class SearchSortOption(val label: String) {
    RELEVANCE("Relevance"),
    TITLE_AZ("Title A–Z"),
    YEAR_NEWEST("Year Newest")
}

/**
 * Floating Hybrid Global Search Overlay anchored beneath the desktop top bar.
 *
 * - Blank input displays per-profile local Recent Searches from [DesktopSearchHistoryManager].
 * - Queries of length >= 2 run instantaneous (0 ms) local Drive catalog search and concurrently
 *   debounce (250 ms) external TMDB/Cinemeta/IMDb lookups merged via [DesktopDriveCatalogMatcher].
 * - Provides Availability, Media Type, Watchlist Status, and Sort chip strips.
 */
@Composable
fun DesktopGlobalSearchOverlay(
    searchQuery: String,
    userEmail: String,
    accessToken: String,
    movies: List<DesktopMediaItem>,
    series: List<DesktopMediaItem>,
    onSelectRecentQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenDriveMovie: (DesktopMediaItem) -> Unit,
    onOpenDriveSeries: (DesktopMediaItem, String?) -> Unit,
    onOpenExternalPreview: (GlobalSearchResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val cleanQuery = searchQuery.trim()
    val isQuerySearchable = cleanQuery.length >= 2

    val recentSearchesRevision = DesktopSearchHistoryManager.recentSearchesRevision.intValue
    val watchlistRevision = DesktopWatchlistManager.watchlistRevision.intValue
    val historyRevision = DesktopWatchHistoryManager.historyRevision.intValue

    // 1. Filter & Sort State
    var availabilityFilter by remember { mutableStateOf(SearchAvailabilityFilter.ALL) }
    var mediaTypeFilter by remember { mutableStateOf(SearchMediaTypeFilter.ALL_TYPES) }
    var watchlistFilter by remember { mutableStateOf(SearchWatchlistFilter.ANY_STATUS) }
    var sortOption by remember { mutableStateOf(SearchSortOption.RELEVANCE) }

    // 2. Instantaneous (0 ms) Local Drive Catalog Search
    val localResults = remember(cleanQuery, userEmail, movies, series) {
        if (!isQuerySearchable) {
            emptyList()
        } else {
            DesktopGlobalSearchEngine.searchLocalDriveCatalog(
                query = cleanQuery,
                userEmail = userEmail,
                movies = movies,
                series = series
            )
        }
    }

    // 3. Concurrent Debounced (250 ms) External Catalog Search
    var onlineResults by remember { mutableStateOf<List<GlobalSearchResult>>(emptyList()) }
    var isFetchingOnline by remember { mutableStateOf(false) }

    LaunchedEffect(cleanQuery) {
        if (cleanQuery.length < 2) {
            onlineResults = emptyList()
            isFetchingOnline = false
            return@LaunchedEffect
        }

        onlineResults = emptyList()
        isFetchingOnline = true
        try {
            delay(250L)
            val fetched = DesktopGlobalSearchEngine.searchOnlineCatalog(
                query = cleanQuery,
                debounceMs = 0L
            )
            onlineResults = fetched
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onlineResults = emptyList()
        } finally {
            isFetchingOnline = false
        }
    }

    // 4. 4-Tier Deduplication & Metadata Enrichment
    val mergedResults = remember(localResults, onlineResults) {
        if (localResults.isEmpty() && onlineResults.isEmpty()) {
            emptyList()
        } else {
            DesktopDriveCatalogMatcher.mergeAndDeduplicate(
                localResults = localResults,
                onlineResults = onlineResults
            )
        }
    }

    val filteredAndSortedResults = remember(
        mergedResults,
        availabilityFilter,
        mediaTypeFilter,
        watchlistFilter,
        sortOption,
        watchlistRevision,
        historyRevision,
        userEmail
    ) {
        val filtered = mergedResults.filter { item ->
            val availabilityOk = when (availabilityFilter) {
                SearchAvailabilityFilter.ALL -> true
                SearchAvailabilityFilter.IN_DRIVE -> item.isInDrive
                SearchAvailabilityFilter.NOT_IN_DRIVE -> !item.isInDrive
            }
            if (!availabilityOk) return@filter false

            val mediaTypeOk = when (mediaTypeFilter) {
                SearchMediaTypeFilter.ALL_TYPES -> true
                SearchMediaTypeFilter.MOVIES -> item.mediaType == SearchMediaType.MOVIE
                SearchMediaTypeFilter.TV_SERIES ->
                    item.mediaType == SearchMediaType.TV_SERIES || item.mediaType == SearchMediaType.EPISODE
            }
            if (!mediaTypeOk) return@filter false

            if (watchlistFilter != SearchWatchlistFilter.ANY_STATUS) {
                val status = resolveSearchCardWatchlistStatus(item, userEmail)
                val target = when (watchlistFilter) {
                    SearchWatchlistFilter.WATCH_LATER -> WatchlistStatus.WATCH_LATER
                    SearchWatchlistFilter.WATCHED -> WatchlistStatus.WATCHED
                    SearchWatchlistFilter.REWATCH -> WatchlistStatus.REWATCH
                    SearchWatchlistFilter.ANY_STATUS -> null
                }
                if (status != target) return@filter false
            }

            true
        }

        when (sortOption) {
            SearchSortOption.RELEVANCE -> filtered
            SearchSortOption.TITLE_AZ -> filtered.sortedWith(
                compareBy<GlobalSearchResult> { it.title.lowercase() }
                    .thenByDescending { it.isInDrive }
                    .thenByDescending { it.relevanceScore }
            )
            SearchSortOption.YEAR_NEWEST -> filtered.sortedWith(
                compareByDescending<GlobalSearchResult> { it.year.toIntOrNull() ?: 0 }
                    .thenByDescending { it.isInDrive }
                    .thenByDescending { it.relevanceScore }
                    .thenBy { it.title.lowercase() }
            )
        }
    }

    val recentSearches = remember(userEmail, recentSearchesRevision) {
        DesktopSearchHistoryManager.getRecentSearches(userEmail)
    }

    val hasActiveFilters = availabilityFilter != SearchAvailabilityFilter.ALL ||
        mediaTypeFilter != SearchMediaTypeFilter.ALL_TYPES ||
        watchlistFilter != SearchWatchlistFilter.ANY_STATUS

    // Full-Page Workspace Canvas anchored below top bar (64.dp)
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 64.dp)
            .background(ObsidianCanvas)
    ) {
        Surface(
            color = ObsidianCanvas,
            modifier = Modifier.fillMaxSize()
        ) {
            if (cleanQuery.isBlank()) {
                RecentSearchesPanel(
                    entries = recentSearches,
                    onClickEntry = { entry ->
                        onSelectRecentQuery(entry.query)
                    },
                    onRemoveEntry = { entry ->
                        DesktopSearchHistoryManager.removeSearchQuery(userEmail, entry.query)
                    },
                    onClearAll = {
                        DesktopSearchHistoryManager.clearRecentSearches(userEmail)
                    },
                    onDismiss = onDismiss
                )
            } else if (!isQuerySearchable) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp, vertical = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ManageSearch,
                            contentDescription = null,
                            tint = SlateMuted,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Type at least 2 characters to search your Drive and online catalogs...",
                            color = SlateSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Full-width Filter & Sort Chip Strips
                    SearchFilterAndSortHeader(
                        availabilityFilter = availabilityFilter,
                        onSelectAvailability = { availabilityFilter = it },
                        mediaTypeFilter = mediaTypeFilter,
                        onSelectMediaType = { mediaTypeFilter = it },
                        watchlistFilter = watchlistFilter,
                        onSelectWatchlist = { watchlistFilter = it },
                        sortOption = sortOption,
                        onSelectSort = { sortOption = it },
                        resultCount = filteredAndSortedResults.size,
                        isFetchingOnline = isFetchingOnline,
                        onDismiss = onDismiss
                    )

                    HorizontalDivider(color = Color(0xFF1B2130), thickness = 1.dp)

                    // Non-blocking inline remote search status bar (never hides 0 ms local Drive results)
                    if (isFetchingOnline) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0C1018))
                                .padding(horizontal = 28.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.8.dp,
                                color = AccentBlue
                            )
                            Text(
                                text = "Searching IMDb & external catalogs...",
                                color = SlateSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        HorizontalDivider(color = Color(0xFF171C28), thickness = 1.dp)
                    }

                    // Responsive Multi-Column Results Canvas
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val columnCount = when {
                            maxWidth >= 1260.dp -> 3
                            maxWidth >= 740.dp -> 2
                            else -> 1
                        }

                        val inDriveResults = remember(filteredAndSortedResults) {
                            filteredAndSortedResults.filter { it.isInDrive }
                        }
                        val cloudResults = remember(filteredAndSortedResults) {
                            filteredAndSortedResults.filter { !it.isInDrive }
                        }

                        if (filteredAndSortedResults.isEmpty()) {
                            SearchEmptyState(
                                cleanQuery = cleanQuery,
                                isFetchingOnline = isFetchingOnline,
                                hasActiveFilters = hasActiveFilters,
                                mergedResultsNotEmpty = mergedResults.isNotEmpty(),
                                onResetFilters = {
                                    availabilityFilter = SearchAvailabilityFilter.ALL
                                    mediaTypeFilter = SearchMediaTypeFilter.ALL_TYPES
                                    watchlistFilter = SearchWatchlistFilter.ANY_STATUS
                                }
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // -------------------------------------------------------------
                                // SECTION 1: IN DRIVE MEDIA (Available for instant Drive streaming)
                                // -------------------------------------------------------------
                                val showDriveSection = availabilityFilter == SearchAvailabilityFilter.ALL ||
                                    availabilityFilter == SearchAvailabilityFilter.IN_DRIVE

                                if (showDriveSection) {
                                    item(key = "header_in_drive") {
                                        SearchSectionHeader(
                                            icon = Icons.Rounded.Folder,
                                            iconTint = Color(0xFF34D399),
                                            title = "In Drive Media",
                                            countText = "${inDriveResults.size} in Drive",
                                            countPillBg = Color(0x2110B981),
                                            countPillBorder = Color(0x5210B981),
                                            countPillText = Color(0xFF34D399),
                                            subtitle = "Available for instant streaming from your Google Drive"
                                        )
                                    }

                                    if (inDriveResults.isNotEmpty()) {
                                        val driveRows = inDriveResults.chunked(columnCount)
                                        items(
                                            items = driveRows,
                                            key = { row -> "drive_row_" + row.joinToString("-") { it.id } }
                                        ) { rowItems ->
                                            SearchResultGridRow(
                                                rowItems = rowItems,
                                                columnCount = columnCount,
                                                watchlistRevision = watchlistRevision,
                                                historyRevision = historyRevision,
                                                userEmail = userEmail,
                                                accessToken = accessToken,
                                                searchQuery = searchQuery,
                                                onOpenDriveMovie = onOpenDriveMovie,
                                                onOpenDriveSeries = onOpenDriveSeries,
                                                onOpenExternalPreview = onOpenExternalPreview
                                            )
                                        }
                                    } else if (availabilityFilter == SearchAvailabilityFilter.ALL && cloudResults.isNotEmpty()) {
                                        item(key = "empty_drive_note") {
                                            DriveEmptyInlineNote(cleanQuery = cleanQuery)
                                        }
                                    }
                                }

                                // -------------------------------------------------------------
                                // SECTION 2: SEARCHED FROM THE CLOUD (External TMDB / IMDb catalog)
                                // -------------------------------------------------------------
                                val showCloudSection = availabilityFilter == SearchAvailabilityFilter.ALL ||
                                    availabilityFilter == SearchAvailabilityFilter.NOT_IN_DRIVE

                                if (showCloudSection && (cloudResults.isNotEmpty() || availabilityFilter == SearchAvailabilityFilter.NOT_IN_DRIVE)) {
                                    if (showDriveSection) {
                                        item(key = "divider_between_sections") {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 20.dp, bottom = 4.dp)
                                            ) {
                                                HorizontalDivider(color = Color(0xFF1E2638), thickness = 1.dp)
                                            }
                                        }
                                    }

                                    item(key = "header_cloud") {
                                        SearchSectionHeader(
                                            icon = Icons.Rounded.TravelExplore,
                                            iconTint = AccentBlueSoft,
                                            title = "Searched from the Cloud",
                                            countText = "${cloudResults.size} from Cloud",
                                            countPillBg = AccentBlue.copy(alpha = 0.16f),
                                            countPillBorder = AccentBlue.copy(alpha = 0.40f),
                                            countPillText = AccentBlueSoft,
                                            subtitle = "External catalog matches (TMDB / IMDb)"
                                        )
                                    }

                                    if (cloudResults.isNotEmpty()) {
                                        val cloudRows = cloudResults.chunked(columnCount)
                                        items(
                                            items = cloudRows,
                                            key = { row -> "cloud_row_" + row.joinToString("-") { "${it.id}_${it.externalSource}" } }
                                        ) { rowItems ->
                                            SearchResultGridRow(
                                                rowItems = rowItems,
                                                columnCount = columnCount,
                                                watchlistRevision = watchlistRevision,
                                                historyRevision = historyRevision,
                                                userEmail = userEmail,
                                                accessToken = accessToken,
                                                searchQuery = searchQuery,
                                                onOpenDriveMovie = onOpenDriveMovie,
                                                onOpenDriveSeries = onOpenDriveSeries,
                                                onOpenExternalPreview = onOpenExternalPreview
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
}

// -----------------------------------------------------------------------------
// SECTION HEADERS & GRID ROW HELPERS
// -----------------------------------------------------------------------------

@Composable
private fun SearchSectionHeader(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    countText: String,
    countPillBg: Color,
    countPillBorder: Color,
    countPillText: Color,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(17.dp)
                )
            }

            Text(
                text = title,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = countPillBg,
                border = BorderStroke(1.dp, countPillBorder)
            ) {
                Text(
                    text = countText,
                    color = countPillText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Text(
            text = subtitle,
            color = SlateSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 38.dp)
        )
    }
}

@Composable
private fun DriveEmptyInlineNote(cleanQuery: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F131C),
        border = BorderStroke(1.dp, Color(0xFF1E2536)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOff,
                contentDescription = null,
                tint = SlateMuted,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "No matching files in your Google Drive for \"$cleanQuery\"",
                color = SlateSecondary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SearchResultGridRow(
    rowItems: List<GlobalSearchResult>,
    columnCount: Int,
    watchlistRevision: Int,
    historyRevision: Int,
    userEmail: String,
    accessToken: String,
    searchQuery: String,
    onOpenDriveMovie: (DesktopMediaItem) -> Unit,
    onOpenDriveSeries: (DesktopMediaItem, String?) -> Unit,
    onOpenExternalPreview: (GlobalSearchResult) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        for (item in rowItems) {
            val status = remember(item.id, item.externalId, item.title, watchlistRevision, historyRevision, userEmail) {
                resolveSearchCardWatchlistStatus(item, userEmail)
            }

            Box(modifier = Modifier.weight(1f)) {
                GlobalSearchResultCard(
                    result = item,
                    watchlistStatus = status,
                    accessToken = accessToken,
                    userEmail = userEmail,
                    onClick = {
                        DesktopSearchHistoryManager.recordSearchQuery(userEmail, searchQuery)
                        if (item.isInDrive) {
                            when (item.mediaType) {
                                SearchMediaType.MOVIE -> item.driveMediaItem?.let(onOpenDriveMovie)
                                SearchMediaType.TV_SERIES -> item.driveMediaItem?.let { onOpenDriveSeries(it, null) }
                                SearchMediaType.EPISODE -> {
                                    val parent = item.parentSeriesItem ?: item.driveMediaItem
                                    if (parent != null) onOpenDriveSeries(parent, item.episodeId)
                                }
                            }
                        } else {
                            onOpenExternalPreview(item)
                        }
                    }
                )
            }
        }

        val remainder = columnCount - rowItems.size
        if (remainder > 0) {
            Spacer(modifier = Modifier.weight(remainder.toFloat()))
        }
    }
}

@Composable
private fun SearchEmptyState(
    cleanQuery: String,
    isFetchingOnline: Boolean,
    hasActiveFilters: Boolean,
    mergedResultsNotEmpty: Boolean,
    onResetFilters: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 60.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(ObsidianElevated)
                    .border(1.dp, ObsidianBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isFetchingOnline) Icons.Rounded.TravelExplore else Icons.Rounded.SearchOff,
                    contentDescription = null,
                    tint = if (isFetchingOnline) AccentBlueSoft else SlateMuted,
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = when {
                    isFetchingOnline -> "Checking external catalogs for \"$cleanQuery\"..."
                    hasActiveFilters && mergedResultsNotEmpty ->
                        "No results match the active filters for \"$cleanQuery\""
                    else -> "No results found for \"$cleanQuery\""
                },
                color = SlateSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            if (hasActiveFilters && mergedResultsNotEmpty) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AccentBlue.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onResetFilters() }
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Text(
                        text = "Reset Filters",
                        color = AccentBlueSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// RECENT SEARCHES PANEL (BLANK INPUT STATE)
// -----------------------------------------------------------------------------

@Composable
private fun RecentSearchesPanel(
    entries: List<RecentSearchEntry>,
    onClickEntry: (RecentSearchEntry) -> Unit,
    onRemoveEntry: (RecentSearchEntry) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 840.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = AccentBlueSoft,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "RECENT SEARCHES",
                        color = SlateSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (entries.isNotEmpty()) {
                        val clearInteraction = remember { MutableInteractionSource() }
                        val clearHovered by clearInteraction.collectIsHoveredAsState()
                        Text(
                            text = "Clear All",
                            color = if (clearHovered) TextPrimary else SlateSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (clearHovered) ObsidianElevated else Color.Transparent)
                                .clickable(
                                    interactionSource = clearInteraction,
                                    indication = null,
                                    onClick = onClearAll
                                )
                                .pointerHoverIcon(PointerIcon.Hand)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(ObsidianElevated)
                            .border(1.dp, ObsidianBorder, CircleShape)
                            .clickable { onDismiss() }
                            .pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close search",
                            tint = SlateMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                color = Color(0xFF1B2130),
                thickness = 1.dp,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
            )

            if (entries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ManageSearch,
                        contentDescription = null,
                        tint = SlateMuted,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "No recent searches yet",
                        color = SlateSecondary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Search your Google Drive library or external TMDB/IMDb catalogs",
                        color = SlateMuted,
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        items = entries,
                        key = { "recent_${it.query.lowercase()}_${it.timestamp}" }
                    ) { entry ->
                        RecentSearchRow(
                            entry = entry,
                            onClick = { onClickEntry(entry) },
                            onRemove = { onRemoveEntry(entry) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSearchRow(
    entry: RecentSearchEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val rowInteraction = remember { MutableInteractionSource() }
    val rowHovered by rowInteraction.collectIsHoveredAsState()

    val removeInteraction = remember { MutableInteractionSource() }
    val removeHovered by removeInteraction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (rowHovered) ObsidianElevated else Color.Transparent)
            .clickable(
                interactionSource = rowInteraction,
                indication = null,
                onClick = onClick
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = if (rowHovered) AccentBlueSoft else SlateMuted,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = entry.query,
                color = if (rowHovered) TextPrimary else TextBody,
                fontSize = 13.sp,
                fontWeight = if (rowHovered) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (removeHovered) Color(0xFF283144) else Color.Transparent)
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
                contentDescription = "Remove recent search",
                tint = if (removeHovered) TextPrimary else SlateMuted,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// FILTER & SORT CHIP STRIPS
// -----------------------------------------------------------------------------

@Composable
private fun SearchFilterAndSortHeader(
    availabilityFilter: SearchAvailabilityFilter,
    onSelectAvailability: (SearchAvailabilityFilter) -> Unit,
    mediaTypeFilter: SearchMediaTypeFilter,
    onSelectMediaType: (SearchMediaTypeFilter) -> Unit,
    watchlistFilter: SearchWatchlistFilter,
    onSelectWatchlist: (SearchWatchlistFilter) -> Unit,
    sortOption: SearchSortOption,
    onSelectSort: (SearchSortOption) -> Unit,
    resultCount: Int,
    isFetchingOnline: Boolean,
    onDismiss: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0C1018))
            .padding(horizontal = 28.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Row 1: Availability + Media Type + Result Count + Close
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SearchAvailabilityFilter.entries.forEach { option ->
                    CompactFilterChip(
                        label = option.label,
                        selected = availabilityFilter == option,
                        onClick = { onSelectAvailability(option) }
                    )
                }

                FilterStripDivider()

                SearchMediaTypeFilter.entries.forEach { option ->
                    CompactFilterChip(
                        label = option.label,
                        selected = mediaTypeFilter == option,
                        onClick = { onSelectMediaType(option) }
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isFetchingOnline && resultCount == 0) "..." else "$resultCount found",
                    color = SlateMuted,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )

                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(ObsidianSurface)
                        .border(1.dp, ObsidianBorder, CircleShape)
                        .clickable { onDismiss() }
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close search",
                        tint = SlateMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        // Row 2: Watchlist Status + Sort
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SearchWatchlistFilter.entries.forEach { option ->
                CompactFilterChip(
                    label = option.label,
                    selected = watchlistFilter == option,
                    onClick = { onSelectWatchlist(option) }
                )
            }

            FilterStripDivider()

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Sort,
                contentDescription = "Sort",
                tint = SlateMuted,
                modifier = Modifier.size(14.dp)
            )

            SearchSortOption.entries.forEach { option ->
                CompactFilterChip(
                    label = option.label,
                    selected = sortOption == option,
                    onClick = { onSelectSort(option) }
                )
            }
        }
    }
}

@Composable
private fun FilterStripDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .width(1.dp)
            .height(16.dp)
            .background(Color(0xFF232B3D))
    )
}

@Composable
private fun CompactFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        targetValue = when {
            selected -> AccentBlue.copy(alpha = 0.20f)
            hovered -> ObsidianElevated
            else -> ObsidianSurface
        },
        animationSpec = tween(140),
        label = "FilterChipBg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> AccentBlue
            hovered -> Color(0xFF334155)
            else -> ObsidianBorder
        },
        animationSpec = tween(140),
        label = "FilterChipBorder"
    )

    Surface(
        shape = RoundedCornerShape(7.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Text(
            text = label,
            color = if (selected) TextPrimary else if (hovered) TextBody else SlateSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

// -----------------------------------------------------------------------------
// SEARCH RESULT CARD & STATUS PILLS
// -----------------------------------------------------------------------------

@Composable
private fun GlobalSearchResultCard(
    result: GlobalSearchResult,
    watchlistStatus: WatchlistStatus?,
    accessToken: String,
    userEmail: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val scope = rememberCoroutineScope()
    val flashAlpha = remember { Animatable(0f) }

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.99f
            isHovered -> 1.008f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "GlobalSearchCardScale"
    )

    val cardBg by animateColorAsState(
        targetValue = if (isHovered) ObsidianElevated else Color(0xFF131722),
        animationSpec = tween(150),
        label = "GlobalSearchCardBg"
    )

    val cardBorder by animateColorAsState(
        targetValue = if (isHovered) AccentBlue.copy(alpha = 0.55f) else Color(0xFF1E2534),
        animationSpec = tween(150),
        label = "GlobalSearchCardBorder"
    )

    val fallbackThumb = result.backdropUrl.ifBlank {
        result.driveMediaItem?.fallbackThumbnailUrl.orEmpty()
    }.ifBlank { null }

    val canonicalKey = remember(result.id, result.mediaType, result.driveMediaItem?.id) {
        val driveId = result.driveMediaItem?.id?.takeIf { it.isNotBlank() }
        when (result.mediaType) {
            SearchMediaType.EPISODE ->
                if (driveId != null) DesktopCanonicalImageCache.heroKey(driveId)
                else "search_ep_${result.id}"
            SearchMediaType.TV_SERIES ->
                if (driveId != null) DesktopCanonicalImageCache.seriesPosterKey(driveId)
                else "search_series_${result.id}"
            SearchMediaType.MOVIE ->
                if (driveId != null) DesktopCanonicalImageCache.moviePosterKey(driveId)
                else "search_movie_${result.id}"
        }
    }
    val thumb = rememberCanonicalArtwork(
        canonicalKey = canonicalKey,
        candidateUrl = result.posterUrl,
        accessToken = accessToken,
        fallbackUrl = fallbackThumb
    )

    val isEpisode = result.mediaType == SearchMediaType.EPISODE
    val thumbWidth = if (isEpisode) 74.dp else 46.dp
    val thumbHeight = if (isEpisode) 48.dp else 66.dp

    val subtitleText = remember(result) {
        when (result.mediaType) {
            SearchMediaType.EPISODE -> {
                val epCode = if (result.seasonNumber != null && result.episodeNumber != null) {
                    "S%02dE%02d".format(result.seasonNumber, result.episodeNumber)
                } else {
                    "Episode"
                }
                val parentName = result.parentSeriesItem?.title?.takeIf { it.isNotBlank() }
                    ?: result.originalTitle.ifBlank { "TV Series" }
                "$epCode • $parentName"
            }
            SearchMediaType.MOVIE, SearchMediaType.TV_SERIES -> {
                val parts = mutableListOf<String>()
                if (result.castSubtitle.isNotBlank()) {
                    if (result.year.isNotBlank()) parts.add(result.year)
                    parts.add(result.castSubtitle)
                } else {
                    parts.add(if (result.mediaType == SearchMediaType.TV_SERIES) "TV Series" else "Movie")
                    if (result.year.isNotBlank()) parts.add(result.year)
                    if (result.genre.isNotBlank()) parts.add(result.genre)
                }
                parts.joinToString(" • ")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                scope.launch {
                    flashAlpha.snapTo(0.22f)
                    flashAlpha.animateTo(0f, animationSpec = tween(180))
                }
                onClick()
            }
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Poster / Episode Still Thumbnail
            Box(
                modifier = Modifier
                    .width(thumbWidth)
                    .height(thumbHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ObsidianElevated)
                    .border(1.dp, Color(0xFF252D3F), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = result.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = when (result.mediaType) {
                            SearchMediaType.MOVIE -> Icons.Rounded.Movie
                            SearchMediaType.TV_SERIES -> Icons.Rounded.Tv
                            SearchMediaType.EPISODE -> Icons.Rounded.PlayCircle
                        },
                        contentDescription = null,
                        tint = SlateMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (isEpisode) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xA607090E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Center Title, Subtitle & Status Pills
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = result.title,
                        color = TextPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (result.rating > 0.0) {
                        RatingPill(rating = result.rating)
                    }
                }

                Text(
                    text = subtitleText,
                    color = SlateSecondary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AvailabilityStatusPill(isInDrive = result.isInDrive)

                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0xFF141824),
                        border = BorderStroke(1.dp, ObsidianBorder)
                    ) {
                        Text(
                            text = when (result.mediaType) {
                                SearchMediaType.MOVIE -> "Movie"
                                SearchMediaType.TV_SERIES -> "TV Series"
                                SearchMediaType.EPISODE -> "Episode"
                            },
                            color = SlateSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (watchlistStatus != null && watchlistStatus != WatchlistStatus.UNWATCHED) {
                        WatchlistStatusPill(status = watchlistStatus)
                    }
                }
            }

            // Right Action Indicator
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = if (result.isInDrive) AccentBlue.copy(alpha = 0.14f) else ObsidianElevated,
                border = BorderStroke(
                    1.dp,
                    if (result.isInDrive) AccentBlue.copy(alpha = 0.35f) else ObsidianBorder
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = when {
                            result.isInDrive && result.mediaType == SearchMediaType.MOVIE -> Icons.Rounded.PlayArrow
                            result.isInDrive && result.mediaType == SearchMediaType.EPISODE -> Icons.Rounded.PlayArrow
                            result.isInDrive -> Icons.Rounded.ChevronRight
                            else -> Icons.AutoMirrored.Rounded.OpenInNew
                        },
                        contentDescription = null,
                        tint = if (result.isInDrive) AccentBlueSoft else SlateSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = when {
                            result.isInDrive && result.mediaType == SearchMediaType.MOVIE -> "Play"
                            result.isInDrive && result.mediaType == SearchMediaType.EPISODE -> "Play"
                            result.isInDrive -> "Open"
                            else -> "Details"
                        },
                        color = if (result.isInDrive) AccentBlueSoft else SlateSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (flashAlpha.value > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(AccentBlue.copy(alpha = flashAlpha.value))
            )
        }
    }
}

@Composable
private fun AvailabilityStatusPill(isInDrive: Boolean) {
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = if (isInDrive) EmeraldFill else ObsidianElevated,
        border = BorderStroke(1.dp, if (isInDrive) EmeraldBorder else Color(0xFF2D3748))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (isInDrive) EmeraldText else SlateMuted)
            )
            Text(
                text = if (isInDrive) "In Drive" else "Not in Drive",
                color = if (isInDrive) EmeraldText else SlateSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun WatchlistStatusPill(status: WatchlistStatus) {
    val (bg, border, textColor) = when (status) {
        WatchlistStatus.WATCHED -> Triple(
            Color(0x1F10B981),
            Color(0x4510B981),
            Color(0xFF6EE7B7)
        )
        WatchlistStatus.WATCH_LATER -> Triple(
            Color(0x2460A5FA),
            Color(0x4760A5FA),
            Color(0xFF93C5FD)
        )
        WatchlistStatus.REWATCH -> Triple(
            Color(0x2B3877EE),
            Color(0x7360A5FA),
            Color(0xFF60A5FA)
        )
        WatchlistStatus.UNWATCHED -> Triple(
            Color(0xFF1E2534),
            Color(0x3364748B),
            Color(0xFF94A3B8)
        )
    }

    Surface(
        shape = RoundedCornerShape(5.dp),
        color = bg,
        border = BorderStroke(1.dp, border)
    ) {
        Text(
            text = status.label,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun RatingPill(rating: Double) {
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = Color(0x21FBBF24),
        border = BorderStroke(1.dp, Color(0x47FBBF24))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = "Rating",
                tint = StarAmber,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = "%.1f".format(rating),
                color = StarAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// -----------------------------------------------------------------------------
// EXTERNAL TITLE PREVIEW MODAL ("NOT IN DRIVE" TITLES)
// -----------------------------------------------------------------------------

/**
 * Obsidian Cinema External Title Preview Modal displayed when clicking any `"Not in Drive"`
 * search card. Allows inspecting full TMDB/Cinemeta/IMDb metadata and directly updating or
 * removing the title's Watchlist status with Google Drive sync.
 */
@Composable
fun ExternalTitlePreviewModal(
    result: GlobalSearchResult,
    searchQuery: String,
    userEmail: String,
    accessToken: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val watchlistRevision = DesktopWatchlistManager.watchlistRevision.intValue

    // On-demand TMDB /find/{imdb_id} detail enrichment for IMDb hits missing plot overview/rating/backdrop
    var enrichedResult by remember(result.id) { mutableStateOf(result) }
    var isEnriching by remember(result.id) {
        mutableStateOf(result.overview.isBlank() && result.imdbId.startsWith("tt"))
    }

    LaunchedEffect(result.id) {
        enrichedResult = result
        if (result.overview.isBlank() && result.imdbId.startsWith("tt")) {
            isEnriching = true
            try {
                enrichedResult = DesktopGlobalSearchEngine.enrichExternalResult(result)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                enrichedResult = result
            } finally {
                isEnriching = false
            }
        } else {
            isEnriching = false
        }
    }

    val existingEntry = remember(enrichedResult, userEmail, watchlistRevision) {
        DesktopWatchlistManager.findEntryForSearchResult(userEmail, enrichedResult)
    }
    val activeStatus = existingEntry?.status

    val posterBitmap = rememberCanonicalArtwork(
        canonicalKey = "search_preview_poster_${enrichedResult.id}",
        candidateUrl = enrichedResult.posterUrl,
        accessToken = accessToken,
        fallbackUrl = enrichedResult.backdropUrl.ifBlank { null }
    )

    val backdropBitmap = rememberCanonicalArtwork(
        canonicalKey = "search_preview_backdrop_${enrichedResult.id}",
        candidateUrl = enrichedResult.backdropUrl,
        accessToken = accessToken
    )

    fun applyWatchlistStatus(status: WatchlistStatus) {
        if (searchQuery.trim().length >= 2) {
            DesktopSearchHistoryManager.recordSearchQuery(userEmail, searchQuery)
        }
        DesktopWatchlistManager.setSearchResultWatchlistStatus(
            email = userEmail,
            accessToken = accessToken,
            result = enrichedResult,
            status = status
        )
    }

    fun removeFromWatchlist() {
        if (existingEntry == null) return
        if (searchQuery.trim().length >= 2) {
            DesktopSearchHistoryManager.recordSearchQuery(userEmail, searchQuery)
        }
        DesktopWatchlistManager.removeSearchResultFromWatchlist(
            email = userEmail,
            accessToken = accessToken,
            result = enrichedResult
        )
    }

    // Dimmed Obsidian modal backdrop
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xCC04060A))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = ObsidianSurface,
            border = BorderStroke(1.dp, Color(0xFF283145)),
            shadowElevation = 28.dp,
            modifier = Modifier
                .widthIn(min = 560.dp, max = 680.dp)
                .padding(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* Keep clicks inside modal from dismissing */ }
                )
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                // Subtle TMDB w780 cinematic backdrop header when enriched
                if (backdropBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                    ) {
                        Image(
                            bitmap = backdropBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alpha = 0.24f,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0x3310131B),
                                            ObsidianSurface
                                        )
                                    )
                                )
                        )
                    }
                }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Top Row: "Not present in your Google Drive" Indicator Pill + Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ObsidianElevated,
                        border = BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudOff,
                                contentDescription = null,
                                tint = SlateSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Not present in your Google Drive",
                                color = TextBody,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    val closeInteraction = remember { MutableInteractionSource() }
                    val closeHovered by closeInteraction.collectIsHoveredAsState()
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(if (closeHovered) ObsidianElevated else Color(0xFF141824))
                            .border(1.dp, ObsidianBorder, CircleShape)
                            .clickable(
                                interactionSource = closeInteraction,
                                indication = null,
                                onClick = onDismiss
                            )
                            .pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close preview",
                            tint = if (closeHovered) TextPrimary else SlateSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Main Content: High-Resolution Poster + Metadata & Enriched Overview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Poster Container
                    Box(
                        modifier = Modifier
                            .width(156.dp)
                            .height(234.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianElevated)
                            .border(1.dp, Color(0xFF283247), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (posterBitmap != null) {
                            Image(
                                bitmap = posterBitmap,
                                contentDescription = enrichedResult.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = if (enrichedResult.mediaType == SearchMediaType.TV_SERIES) {
                                    Icons.Rounded.Tv
                                } else {
                                    Icons.Rounded.Movie
                                },
                                contentDescription = null,
                                tint = SlateMuted,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }

                    // Metadata & Synopsis Column
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = enrichedResult.title,
                                color = TextPrimary,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (enrichedResult.originalTitle.isNotBlank() &&
                                !enrichedResult.originalTitle.equals(enrichedResult.title, ignoreCase = true)
                            ) {
                                Text(
                                    text = enrichedResult.originalTitle,
                                    color = SlateMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Metadata Pills: Media Type, Year, Rating, Source
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = AccentBlue.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.40f))
                            ) {
                                Text(
                                    text = if (enrichedResult.mediaType == SearchMediaType.TV_SERIES) "TV Series" else "Movie",
                                    color = AccentBlueSoft,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }

                            if (enrichedResult.year.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = ObsidianElevated,
                                    border = BorderStroke(1.dp, ObsidianBorder)
                                ) {
                                    Text(
                                        text = enrichedResult.year,
                                        color = TextBody,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            if (enrichedResult.rating > 0.0) {
                                RatingPill(rating = enrichedResult.rating)
                            }

                            if (enrichedResult.externalSource.isNotBlank()) {
                                Text(
                                    text = enrichedResult.externalSource,
                                    color = SlateMuted,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (enrichedResult.genre.isNotBlank()) {
                            Text(
                                text = enrichedResult.genre,
                                color = SlateSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (enrichedResult.castSubtitle.isNotBlank()) {
                            Text(
                                text = "Starring: ${enrichedResult.castSubtitle}",
                                color = SlateSecondary,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Full Overview Synopsis (scrollable when lengthy, with non-blocking TMDB enrich indicator)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 60.dp, max = 136.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (isEnriching && enrichedResult.overview.isBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 1.8.dp,
                                        color = AccentBlue
                                    )
                                    Text(
                                        text = "Loading plot synopsis & rating...",
                                        color = SlateSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            } else {
                                Text(
                                    text = enrichedResult.overview.ifBlank {
                                        "No overview synopsis is currently available for this title."
                                    },
                                    color = if (enrichedResult.overview.isNotBlank()) TextBody else SlateMuted,
                                    fontSize = 12.5.sp,
                                    lineHeight = 19.sp
                                )
                            }
                        }
                    }
                }

                // Elevated Action Bar: Watchlist Controls
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ObsidianElevated,
                    border = BorderStroke(1.dp, ObsidianBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "WATCHLIST ACTIONS",
                                color = SlateSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp
                            )

                            if (activeStatus != null) {
                                WatchlistStatusPill(status = activeStatus)
                            } else {
                                Text(
                                    text = "Not in Watchlist",
                                    color = SlateMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ModalWatchlistActionButton(
                                label = "Watch Later",
                                icon = Icons.Rounded.BookmarkAdd,
                                isActive = activeStatus == WatchlistStatus.WATCH_LATER,
                                activeColor = AccentBlue,
                                onClick = { applyWatchlistStatus(WatchlistStatus.WATCH_LATER) },
                                modifier = Modifier.weight(1f)
                            )

                            ModalWatchlistActionButton(
                                label = "Watched",
                                icon = Icons.Rounded.CheckCircle,
                                isActive = activeStatus == WatchlistStatus.WATCHED,
                                activeColor = Color(0xFF10B981),
                                onClick = { applyWatchlistStatus(WatchlistStatus.WATCHED) },
                                modifier = Modifier.weight(1f)
                            )

                            ModalWatchlistActionButton(
                                label = "Rewatch",
                                icon = Icons.Rounded.Replay,
                                isActive = activeStatus == WatchlistStatus.REWATCH,
                                activeColor = AccentBlue,
                                onClick = { applyWatchlistStatus(WatchlistStatus.REWATCH) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Remove from Watchlist Button
                        val canRemove = existingEntry != null
                        val removeInteraction = remember { MutableInteractionSource() }
                        val removeHovered by removeInteraction.collectIsHoveredAsState()

                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = when {
                                !canRemove -> Color(0xFF121620)
                                removeHovered -> Color(0x33EF4444)
                                else -> Color(0x1FEF4444)
                            },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    !canRemove -> Color(0xFF1E2432)
                                    removeHovered -> Color(0x99F87171)
                                    else -> Color(0x4DEF4444)
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .clickable(
                                    enabled = canRemove,
                                    interactionSource = removeInteraction,
                                    indication = null,
                                    onClick = { removeFromWatchlist() }
                                )
                                .then(if (canRemove) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.BookmarkRemove,
                                    contentDescription = "Remove from Watchlist",
                                    tint = if (canRemove) Color(0xFFFCA5A5) else Color(0xFF475569),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Remove from Watchlist",
                                    color = if (canRemove) Color(0xFFFCA5A5) else Color(0xFF475569),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
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
private fun ModalWatchlistActionButton(
    label: String,
    icon: ImageVector,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val bg by animateColorAsState(
        targetValue = when {
            isActive -> activeColor
            hovered -> Color(0xFF23293A)
            else -> ObsidianSurface
        },
        animationSpec = tween(150),
        label = "ModalActionBg"
    )

    val border by animateColorAsState(
        targetValue = when {
            isActive -> activeColor
            hovered -> Color(0xFF3A4660)
            else -> ObsidianBorder
        },
        animationSpec = tween(150),
        label = "ModalActionBorder"
    )

    Surface(
        shape = RoundedCornerShape(9.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) Color.White else TextBody,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = if (isActive) Color.White else TextBody,
                fontSize = 12.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// -----------------------------------------------------------------------------
// WATCHLIST LOOKUP HELPER (DELEGATED TO CANONICAL CROSS-DEVICE MATCHER)
// -----------------------------------------------------------------------------

private fun resolveSearchCardWatchlistStatus(
    result: GlobalSearchResult,
    userEmail: String
): WatchlistStatus? {
    val status = DesktopWatchlistManager.getStatusForSearchResult(userEmail, result)
    if (status != null) return status

    // Preserve legacy watched-history inference for In-Drive movies and episodes
    if (result.isInDrive && userEmail.isNotBlank() &&
        (result.mediaType == SearchMediaType.MOVIE || result.mediaType == SearchMediaType.EPISODE)
    ) {
        if (DesktopWatchlistManager.isExplicitlyRemovedFromWatchlist(userEmail, result.title, result.id)) return null
        if (DesktopWatchHistoryManager.isEpisodeWatched(userEmail, result.id)) {
            return WatchlistStatus.WATCHED
        }
    }

    return null
}
