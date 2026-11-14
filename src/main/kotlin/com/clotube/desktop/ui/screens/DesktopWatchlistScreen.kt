package com.clotube.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.clotube.desktop.data.*
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.components.ThumbnailSize
import com.clotube.desktop.ui.components.rememberThumbnailBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private enum class WatchlistFilter(val label: String) {
    ALL("All"),
    WATCHED("Watched"),
    WATCH_LATER("Watch Later"),
    REWATCH("Re-watch"),
    CUSTOM_ONLY("Not in Drive")
}

@Composable
fun FigmaWatchlistContent(
    userEmail: String,
    accessToken: String,
    movies: List<DesktopMediaItem>,
    series: List<DesktopMediaItem>,
    onMovieClick: (DesktopMediaItem) -> Unit,
    onSeriesClick: (DesktopMediaItem) -> Unit
) {
    val revision = DesktopWatchlistManager.watchlistRevision.intValue
    val allEntries = remember(userEmail, revision) {
        DesktopWatchlistManager.getVisibleEntries(userEmail)
    }

    LaunchedEffect(userEmail, revision) {
        if (userEmail.isNotBlank()) {
            DesktopWatchlistManager.resolveMissingOffDrivePosters(
                email = userEmail,
                accessToken = accessToken,
                driveMovies = movies,
                driveSeries = series
            )
        }
    }

    var selectedFilter by remember { mutableStateOf(WatchlistFilter.ALL) }
    var filterQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<WatchlistEntry?>(null) }

    var watchedExpanded by remember { mutableStateOf(true) }
    var watchLaterExpanded by remember { mutableStateOf(true) }
    var rewatchExpanded by remember { mutableStateOf(true) }

    val coroutineScope = rememberCoroutineScope()

    val driveTitleSet = remember(movies, series) {
        (movies + series).map { DesktopWatchlistManager.normalizeTitle(it.title) }.toSet()
    }

    val filterCounts = remember(allEntries, driveTitleSet) {
        mapOf(
            WatchlistFilter.ALL to allEntries.size,
            WatchlistFilter.WATCHED to allEntries.count { it.status == WatchlistStatus.WATCHED },
            WatchlistFilter.WATCH_LATER to allEntries.count { it.status == WatchlistStatus.WATCH_LATER },
            WatchlistFilter.REWATCH to allEntries.count { it.status == WatchlistStatus.REWATCH },
            WatchlistFilter.CUSTOM_ONLY to allEntries.count {
                it.driveId == null && !driveTitleSet.contains(it.normalizedTitle)
            }
        )
    }

    val filteredEntries = remember(allEntries, selectedFilter, filterQuery, driveTitleSet) {
        allEntries.filter { entry ->
            val matchesFilter = when (selectedFilter) {
                WatchlistFilter.ALL -> true
                WatchlistFilter.WATCHED -> entry.status == WatchlistStatus.WATCHED
                WatchlistFilter.WATCH_LATER -> entry.status == WatchlistStatus.WATCH_LATER
                WatchlistFilter.REWATCH -> entry.status == WatchlistStatus.REWATCH
                WatchlistFilter.CUSTOM_ONLY -> entry.driveId == null && !driveTitleSet.contains(entry.normalizedTitle)
            }
            val matchesQuery = filterQuery.isBlank() ||
                entry.title.contains(filterQuery.trim(), ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    val watchedEntries = remember(filteredEntries) {
        filteredEntries.filter { it.status == WatchlistStatus.WATCHED }
    }
    val watchLaterEntries = remember(filteredEntries) {
        filteredEntries.filter { it.status == WatchlistStatus.WATCH_LATER }
    }
    val rewatchEntries = remember(filteredEntries) {
        filteredEntries.filter { it.status == WatchlistStatus.REWATCH }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 8.dp)
    ) {
        // ----------------------------------------------------
        // STUDIO HEADER BLOCK
        // ----------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = "PERSISTENT LIBRARY TRACKER",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Watchlist",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.4).sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Organized across Watched, Watch Later, and Re-watch—persisted even if removed from Drive.",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF090A0F)
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier
                    .height(40.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Add title",
                    tint = Color(0xFF090A0F),
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Add Title",
                    color = Color(0xFF090A0F),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ----------------------------------------------------
        // STUDIO CAPSULE FILTER CHIPS + PILL SEARCH INPUT
        // ----------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchlistFilter.entries.forEach { filter ->
                    val isSelected = selectedFilter == filter
                    val count = filterCounts[filter] ?: 0

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { selectedFilter = filter }
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
                                text = filter.label,
                                color = if (isSelected) Color(0xFF090A0F) else Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )

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
            }

            Surface(
                modifier = Modifier
                    .width(250.dp)
                    .height(38.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF0E121B),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSearchFocused) Color(0xFF334155) else Color(0xFF1A2230)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Filter watchlist",
                        tint = if (isSearchFocused) Color(0xFFCBD5E1) else Color(0xFF64748B),
                        modifier = Modifier.size(15.dp)
                    )

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (filterQuery.isEmpty()) {
                            Text(
                                text = "Filter watchlist...",
                                color = Color(0xFF64748B),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        BasicTextField(
                            value = filterQuery,
                            onValueChange = { filterQuery = it },
                            singleLine = true,
                            cursorBrush = SolidColor(Color(0xFF60A5FA)),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 12.sp
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isSearchFocused = it.isFocused }
                        )
                    }

                    if (filterQuery.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1A2230))
                                .clickable { filterQuery = "" }
                                .pointerHoverIcon(PointerIcon.Hand),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear filter",
                                tint = Color(0xFFCBD5E1),
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // ----------------------------------------------------
        // CATEGORIZED 3-SECTION WATCHLIST (WATCHED / WATCH LATER / RE-WATCH)
        // ----------------------------------------------------
        val showAllThreeSections = selectedFilter == WatchlistFilter.ALL && filterQuery.isBlank()

        if (!showAllThreeSections && filteredEntries.isEmpty()) {
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
                        imageVector = Icons.Rounded.BookmarkBorder,
                        contentDescription = null,
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = "No titles in this view",
                        color = Color(0xFFCBD5E1),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Click \"Add Title\" to track any movie or series—even if it isn't on Google Drive.",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. WATCHED CATEGORY
                if (showAllThreeSections || watchedEntries.isNotEmpty()) {
                    watchlistCategorySection(
                        sectionKey = "watched",
                        title = "Watched",
                        subtitle = "Completed movies and series",
                        icon = Icons.Rounded.CheckCircle,
                        accentColor = Color(0xFF10B981),
                        emptyPlaceholder = "No watched movies or series yet.",
                        entries = watchedEntries,
                        isExpanded = watchedExpanded,
                        onToggleExpand = { watchedExpanded = !watchedExpanded },
                        userEmail = userEmail,
                        accessToken = accessToken,
                        movies = movies,
                        series = series,
                        coroutineScope = coroutineScope,
                        onMovieClick = onMovieClick,
                        onSeriesClick = onSeriesClick,
                        onEditEntry = { editingEntry = it }
                    )
                }

                // 2. WATCH LATER CATEGORY
                if (showAllThreeSections || watchLaterEntries.isNotEmpty()) {
                    watchlistCategorySection(
                        sectionKey = "watch_later",
                        title = "Watch Later",
                        subtitle = "Queued up for your next session",
                        icon = Icons.Rounded.Bookmark,
                        accentColor = Color(0xFF60A5FA),
                        emptyPlaceholder = "No titles saved to Watch Later yet.",
                        entries = watchLaterEntries,
                        isExpanded = watchLaterExpanded,
                        onToggleExpand = { watchLaterExpanded = !watchLaterExpanded },
                        userEmail = userEmail,
                        accessToken = accessToken,
                        movies = movies,
                        series = series,
                        coroutineScope = coroutineScope,
                        onMovieClick = onMovieClick,
                        onSeriesClick = onSeriesClick,
                        onEditEntry = { editingEntry = it }
                    )
                }

                // 3. RE-WATCH CATEGORY
                if (showAllThreeSections || rewatchEntries.isNotEmpty()) {
                    watchlistCategorySection(
                        sectionKey = "rewatch",
                        title = "Re-watch",
                        subtitle = "Favorites lined up for another watch",
                        icon = Icons.Rounded.Replay,
                        accentColor = Color(0xFFCBD5E1),
                        emptyPlaceholder = "No titles marked for Re-watch yet.",
                        entries = rewatchEntries,
                        isExpanded = rewatchExpanded,
                        onToggleExpand = { rewatchExpanded = !rewatchExpanded },
                        userEmail = userEmail,
                        accessToken = accessToken,
                        movies = movies,
                        series = series,
                        coroutineScope = coroutineScope,
                        onMovieClick = onMovieClick,
                        onSeriesClick = onSeriesClick,
                        onEditEntry = { editingEntry = it }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        WatchlistEditorDialog(
            initialEntry = null,
            onDismiss = { showAddDialog = false },
            onSave = { title, type, status ->
                showAddDialog = false
                coroutineScope.launch {
                    DesktopWatchlistManager.upsertEntry(
                        email = userEmail,
                        title = title,
                        mediaType = type,
                        status = status,
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
        )
    }

    editingEntry?.let { target ->
        WatchlistEditorDialog(
            initialEntry = target,
            onDismiss = { editingEntry = null },
            onSave = { title, type, status ->
                editingEntry = null
                coroutineScope.launch {
                    DesktopWatchlistManager.updateEntryById(
                        email = userEmail,
                        entryId = target.id,
                        newTitle = title,
                        newMediaType = type,
                        newStatus = status
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
        )
    }
}

private fun LazyListScope.watchlistCategorySection(
    sectionKey: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    emptyPlaceholder: String,
    entries: List<WatchlistEntry>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    userEmail: String,
    accessToken: String,
    movies: List<DesktopMediaItem>,
    series: List<DesktopMediaItem>,
    coroutineScope: CoroutineScope,
    onMovieClick: (DesktopMediaItem) -> Unit,
    onSeriesClick: (DesktopMediaItem) -> Unit,
    onEditEntry: (WatchlistEntry) -> Unit
) {
    item(key = "header_$sectionKey") {
        WatchlistCategoryHeader(
            title = title,
            subtitle = subtitle,
            icon = icon,
            accentColor = accentColor,
            count = entries.size,
            isExpanded = isExpanded,
            onToggleExpand = onToggleExpand,
            modifier = Modifier.padding(top = if (sectionKey == "watched") 2.dp else 14.dp, bottom = 2.dp)
        )
    }

    if (isExpanded) {
        if (entries.isEmpty()) {
            item(key = "empty_$sectionKey") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0B0E16))
                        .border(1.dp, Color(0xFF161D2B), RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = emptyPlaceholder,
                        color = Color(0xFF64748B),
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            items(entries, key = { "${sectionKey}_${it.id}" }) { entry ->
                val matchedDriveMovie = remember(entry, movies) {
                    movies.firstOrNull {
                        (entry.driveId != null && it.id == entry.driveId) ||
                            DesktopWatchlistManager.normalizeTitle(it.title) == entry.normalizedTitle
                    }
                }
                val matchedDriveSeries = remember(entry, series) {
                    series.firstOrNull {
                        (entry.driveId != null && it.id == entry.driveId) ||
                            DesktopWatchlistManager.normalizeTitle(it.title) == entry.normalizedTitle
                    }
                }
                val matchedMedia = matchedDriveMovie ?: matchedDriveSeries

                WatchlistRowCard(
                    entry = entry,
                    matchedMedia = matchedMedia,
                    userEmail = userEmail,
                    accessToken = accessToken,
                    onOpenMedia = {
                        when {
                            matchedDriveMovie != null -> onMovieClick(matchedDriveMovie)
                            matchedDriveSeries != null -> onSeriesClick(matchedDriveSeries)
                        }
                    },
                    onStatusChange = { newStatus ->
                        coroutineScope.launch {
                            DesktopWatchlistManager.updateEntryById(
                                email = userEmail,
                                entryId = entry.id,
                                newStatus = newStatus
                            )
                            DesktopWatchlistSync.pushLocalWatchlist(userEmail, accessToken)
                        }
                    },
                    onEdit = { onEditEntry(entry) },
                    onDelete = {
                        coroutineScope.launch {
                            DesktopWatchlistManager.deleteEntryById(userEmail, entry.id)
                            DesktopWatchlistSync.pushLocalWatchlist(userEmail, accessToken)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun WatchlistCategoryHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    count: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggleExpand() }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(accentColor.copy(alpha = 0.14f))
                .border(1.dp, accentColor.copy(alpha = 0.30f), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accentColor,
                modifier = Modifier.size(15.dp)
            )
        }

        Text(
            text = title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF141A26))
                .border(1.dp, Color(0xFF1E2638), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = "$count",
                color = Color(0xFFCBD5E1),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = subtitle,
            color = Color(0xFF64748B),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Color(0xFF161D2B))
        )

        Icon(
            imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
            contentDescription = if (isExpanded) "Collapse $title" else "Expand $title",
            tint = Color(0xFF64748B),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun WatchlistRowCard(
    entry: WatchlistEntry,
    matchedMedia: DesktopMediaItem?,
    userEmail: String,
    accessToken: String,
    onOpenMedia: () -> Unit,
    onStatusChange: (WatchlistStatus) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }
    val isAvailableInDrive = matchedMedia != null

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

    val primaryPosterUrl = remember(matchedMedia?.thumbnailUrl, entry.posterUrl) {
        matchedMedia?.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: entry.posterUrl?.takeIf { it.isNotBlank() }
    }
    val fallbackPosterUrl = remember(matchedMedia?.fallbackThumbnailUrl, entry.posterUrl) {
        matchedMedia?.fallbackThumbnailUrl?.takeIf { it.isNotBlank() }
            ?: entry.posterUrl?.takeIf { it.isNotBlank() }
    }

    val thumbBitmap = rememberThumbnailBitmap(
        primaryUrl = primaryPosterUrl,
        fallbackUrl = fallbackPosterUrl,
        accessToken = accessToken,
        userEmail = userEmail,
        size = ThumbnailSize.CARD
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (isAvailableInDrive) {
                    Modifier.clickable { onOpenMedia() }.pointerHoverIcon(PointerIcon.Hand)
                } else Modifier
            )
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
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF161E2E))
                    .border(1.dp, Color(0xFF222B3D), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbBitmap != null) {
                    Image(
                        bitmap = thumbBitmap,
                        contentDescription = entry.title,
                        contentScale = ContentScale.Crop,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = if (entry.mediaType == WatchlistMediaType.SERIES) Icons.Rounded.Tv else Icons.Rounded.Movie,
                        contentDescription = null,
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = entry.mediaType.label,
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text("•", color = Color(0xFF475569), fontSize = 11.sp)
                    Text(
                        text = if (isAvailableInDrive) "In Google Drive" else "Custom Entry (Off-Drive)",
                        color = if (isAvailableInDrive) Color(0xFF60A5FA) else Color(0xFF64748B),
                        fontSize = 11.sp
                    )
                }
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0B0E16))
                    .border(1.dp, Color(0xFF1A2230), RoundedCornerShape(8.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                WatchlistStatus.entries.forEach { statusOption ->
                    val active = entry.status == statusOption
                    val segmentLabel = if (statusOption == WatchlistStatus.REWATCH) "Re-watch" else statusOption.label
                    val segmentFill = when {
                        !active -> Color.Transparent
                        statusOption == WatchlistStatus.WATCHED -> Color(0x1F10B981)
                        statusOption == WatchlistStatus.WATCH_LATER -> Color(0x2460A5FA)
                        statusOption == WatchlistStatus.REWATCH -> Color(0xFF1E2534)
                        else -> Color.Transparent
                    }
                    val segmentBorder = when {
                        !active -> Color.Transparent
                        statusOption == WatchlistStatus.WATCHED -> Color(0x4010B981)
                        statusOption == WatchlistStatus.WATCH_LATER -> Color(0x4760A5FA)
                        statusOption == WatchlistStatus.REWATCH -> Color(0x33FFFFFF)
                        else -> Color.Transparent
                    }
                    val textColor = when {
                        !active -> Color(0xFF64748B)
                        statusOption == WatchlistStatus.WATCHED -> Color(0xFF6EE7B7)
                        statusOption == WatchlistStatus.WATCH_LATER -> Color(0xFF93C5FD)
                        statusOption == WatchlistStatus.REWATCH -> Color(0xFFF1F5F9)
                        else -> Color(0xFFCBD5E1)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(segmentFill)
                            .border(1.dp, segmentBorder, RoundedCornerShape(6.dp))
                            .clickable { onStatusChange(statusOption) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = segmentLabel,
                            color = textColor,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            val editInteraction = remember { MutableInteractionSource() }
            val isEditHovered by editInteraction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (isEditHovered) Color(0xFF1A2230) else Color.Transparent)
                    .clickable(interactionSource = editInteraction, indication = null, onClick = onEdit)
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "Edit item",
                    tint = if (isEditHovered) Color.White else Color(0xFF64748B),
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            val removeInteraction = remember { MutableInteractionSource() }
            val isRemoveHovered by removeInteraction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (isRemoveHovered) Color(0x1AE11D48) else Color.Transparent)
                    .clickable(interactionSource = removeInteraction, indication = null, onClick = onDelete)
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete from watchlist",
                    tint = if (isRemoveHovered) Color(0xFFFB7185) else Color(0xFF64748B),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun WatchlistEditorDialog(
    initialEntry: WatchlistEntry?,
    onDismiss: () -> Unit,
    onSave: (String, WatchlistMediaType, WatchlistStatus) -> Unit
) {
    var title by remember { mutableStateOf(initialEntry?.title ?: "") }
    var mediaType by remember { mutableStateOf(initialEntry?.mediaType ?: WatchlistMediaType.MOVIE) }
    var status by remember { mutableStateOf(initialEntry?.status ?: WatchlistStatus.WATCHED) }
    var isInputFocused by remember { mutableStateOf(false) }

    val dialogBorder = remember {
        Brush.verticalGradient(
            colors = listOf(Color(0xFF283347), Color(0xFF141923))
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF0E131C),
            border = BorderStroke(1.dp, dialogBorder),
            shadowElevation = 24.dp,
            modifier = Modifier.width(430.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (initialEntry == null) "CUSTOM WATCHLIST ENTRY" else "EDIT WATCHLIST ENTRY",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        text = if (initialEntry == null) "Add to Watchlist" else "Edit Watchlist Item",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "TITLE",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF090C12),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isInputFocused) Color(0xFF60A5FA) else Color(0xFF1E2838)
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (title.isEmpty()) {
                                Text(
                                    text = "Enter movie or series name...",
                                    color = Color(0xFF475569),
                                    fontSize = 13.sp
                                )
                            }
                            BasicTextField(
                                value = title,
                                onValueChange = { title = it },
                                singleLine = true,
                                cursorBrush = SolidColor(Color(0xFF60A5FA)),
                                textStyle = TextStyle(
                                    color = Color.White,
                                    fontSize = 13.sp
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { isInputFocused = it.isFocused }
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "TYPE",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF090C12))
                            .border(1.dp, Color(0xFF1A2230), RoundedCornerShape(8.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        WatchlistMediaType.entries.forEach { type ->
                            val selected = mediaType == type
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) Color(0xFF1A2333) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (selected) Color(0xFF2E3B52) else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { mediaType = type }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = type.label,
                                    color = if (selected) Color.White else Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "STATUS",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF090C12))
                            .border(1.dp, Color(0xFF1A2230), RoundedCornerShape(8.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        WatchlistStatus.entries.forEach { st ->
                            val selected = status == st
                            val label = if (st == WatchlistStatus.REWATCH) "Re-watch" else st.label
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) Color(0xFF1A2333) else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (selected) Color(0xFF2E3B52) else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { status = st }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) Color.White else Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDismiss() }
                            .pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF141A26),
                        border = BorderStroke(1.dp, Color(0xFF222B3D))
                    ) {
                        Text(
                            text = "Cancel",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    val canSave = title.isNotBlank()
                    Button(
                        onClick = { if (canSave) onSave(title.trim(), mediaType, status) },
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF090A0F),
                            disabledContainerColor = Color(0xFF1E2433),
                            disabledContentColor = Color(0xFF64748B)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 9.dp),
                        modifier = Modifier.pointerHoverIcon(if (canSave) PointerIcon.Hand else PointerIcon.Default)
                    ) {
                        Text(
                            text = "Save",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
