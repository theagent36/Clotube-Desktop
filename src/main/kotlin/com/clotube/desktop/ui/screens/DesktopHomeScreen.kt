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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.components.rememberThumbnailBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

enum class DesktopNavSection(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    MOVIES("Movies", Icons.Rounded.Movie),
    SERIES("Series", Icons.Rounded.Tv),
    RECENTS("Recents", Icons.Rounded.History)
}

private val thumbnailCache = ConcurrentHashMap<String, ImageBitmap>()

@Deprecated("Use rememberThumbnailBitmap with fallback support", ReplaceWith("rememberThumbnailBitmap(url)"))
@Composable
fun rememberDriveThumbnail(url: String?): ImageBitmap? {
    return rememberThumbnailBitmap(primaryUrl = url)
}

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

    var selectedSeriesForDetail by remember { mutableStateOf<DesktopMediaItem?>(null) }
    var selectedMovieForPlayer by remember { mutableStateOf<DesktopMediaItem?>(null) }

    val navigateTo: (DesktopNavSection) -> Unit = { section ->
        selectedSeriesForDetail = null
        selectedMovieForPlayer = null
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

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090E))
    ) {
        // 1. LEFT SIDEBAR (Hidden during OS fullscreen)
        if (!effectiveFullscreen) {
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
            // Top App Bar (Hidden during OS fullscreen)
            if (!effectiveFullscreen) {
                DesktopFigmaTopBar(
                    searchQuery = searchQuery,
                    onSearchChange = { searchQuery = it },
                    userName = userName.ifBlank { userEmail.substringBefore('@').ifBlank { "Clotube User" } },
                    userEmail = userEmail,
                    userAvatarUrl = userAvatarUrl,
                    onSignOut = onSignOut
                )
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
                        onBack = { 
                            if (effectiveFullscreen) {
                                onToggleFullscreen()
                            }
                            selectedSeriesForDetail = null 
                        }
                    )
                } else if (selectedMovieForPlayer != null) {
                    DesktopMoviePlayerScreen(
                        initialMovie = selectedMovieForPlayer!!,
                        allMovies = filteredMovies,
                        accessToken = accessToken,
                        onBack = {
                            if (effectiveFullscreen) {
                                onToggleFullscreen()
                            }
                            selectedMovieForPlayer = null
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
                                accessToken = accessToken,
                                onMovieClick = { movie ->
                                    selectedMovieForPlayer = movie
                                    onMediaClick(movie)
                                },
                                onNavigateSection = navigateTo,
                                onSeriesClick = { seriesItem ->
                                    selectedSeriesForDetail = seriesItem
                                },
                                onRescan = onRescan
                            )
                        }
                        DesktopNavSection.MOVIES -> {
                            FigmaMoviesContent(
                                movies = filteredMovies,
                                accessToken = accessToken,
                                onMediaClick = { movie ->
                                    selectedMovieForPlayer = movie
                                    onMediaClick(movie)
                                }
                            )
                        }
                        DesktopNavSection.SERIES -> {
                            FigmaSeriesContent(
                                series = filteredSeries,
                                continueWatching = continueWatching,
                                accessToken = accessToken,
                                onMediaClick = { seriesItem ->
                                    selectedSeriesForDetail = seriesItem
                                }
                            )
                        }
                        DesktopNavSection.RECENTS -> {
                            FigmaRecentsContent(
                                recents = continueWatching.ifEmpty { filteredMovies.take(3) },
                                accessToken = accessToken,
                                onMediaClick = { item ->
                                    selectedMovieForPlayer = item
                                    onMediaClick(item)
                                },
                                onClearHistory = onClearHistory,
                                onRemoveItem = onRemoveHistoryItem
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Clotube",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp
            )

            Icon(
                imageVector = Icons.Rounded.Menu,
                contentDescription = "Menu",
                tint = Color(0xFF64748B),
                modifier = Modifier
                    .size(18.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
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
                            fontSize = 13.sp
                        ),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search movies, series...",
                                    color = Color(0xFF64748B),
                                    fontSize = 13.sp
                                )
                            }
                            innerTextField()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

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
private fun FigmaHomeContent(
    featuredItem: DesktopMediaItem?,
    continueWatching: List<DesktopMediaItem>,
    movies: List<DesktopMediaItem>,
    series: List<DesktopMediaItem>,
    accessToken: String = "",
    onMovieClick: (DesktopMediaItem) -> Unit,
    onNavigateSection: (DesktopNavSection) -> Unit,
    onSeriesClick: (DesktopMediaItem) -> Unit,
    onRescan: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

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
            accessToken = accessToken,
            onMovieClick = onMovieClick,
            onSeriesClick = onSeriesClick
        )

        if (continueWatching.isNotEmpty()) {
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
    accessToken: String = "",
    onMediaClick: (DesktopMediaItem) -> Unit
) {
    val languageFilters = listOf("All", "Hindi", "Bengali", "English")
    var selectedLanguage by remember { mutableStateOf("All") }

    val filteredMovies = remember(movies, selectedLanguage) {
        if (selectedLanguage == "All") {
            movies
        } else {
            movies.filter {
                val lang = it.language.ifBlank {
                    com.clotube.desktop.data.DesktopOnlineMetadataResolver.detectMovieLanguage(it)
                }
                lang.equals(selectedLanguage, ignoreCase = true)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Page Header with Dynamic Counter
        item(key = "movies_page_header") {
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
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (selectedLanguage == "All") "Your private cinema collection stored in Google Drive."
                               else "Showing $selectedLanguage cinema titles from your Google Drive.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "%,d".format(filteredMovies.size),
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = if (selectedLanguage == "All") "movies available" else "$selectedLanguage movies",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Featured Movie Billboard (Updates to match selected language)
        filteredMovies.firstOrNull()?.let { featuredMovie ->
            item(key = "movies_hero_banner_${selectedLanguage}") {
                FigmaHeroBanner(
                    item = featuredMovie,
                    overline = if (selectedLanguage == "All") "FEATURED MOVIE" else "FEATURED ${selectedLanguage.uppercase()} MOVIE",
                    primaryButtonText = "Watch now",
                    accessToken = accessToken,
                    onPrimaryClick = { onMediaClick(featuredMovie) }
                )
            }
        }

        // Functional Language Filter Chips Row
        item(key = "movies_filter_row") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                languageFilters.forEach { lang ->
                    val isSelected = selectedLanguage == lang
                    val count = remember(movies, lang) {
                        if (lang == "All") movies.size
                        else movies.count {
                            val itemLang = it.language.ifBlank {
                                com.clotube.desktop.data.DesktopOnlineMetadataResolver.detectMovieLanguage(it)
                            }
                            itemLang.equals(lang, ignoreCase = true)
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { selectedLanguage = lang }
                            .pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) Color(0xFF2563EB) else Color(0xFF111622),
                        border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF1E2638))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = lang,
                                color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = "$count",
                                color = if (isSelected) Color(0xFF93C5FD) else Color(0xFF64748B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // Filtered Results Grid / Empty State
        if (filteredMovies.isEmpty()) {
            item(key = "movies_empty_filter_state") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0F1420)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No $selectedLanguage movies found",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "No movies in your Google Drive matched the $selectedLanguage language filter.",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            val rows = filteredMovies.chunked(4)
            items(
                items = rows,
                key = { row -> row.joinToString("-") { it.id } }
            ) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    for (i in 0 until 4) {
                        val item = rowItems.getOrNull(i)
                        if (item != null) {
                            DesktopFigmaMediaCard(
                                item = item,
                                accessToken = accessToken,
                                onClick = { onMediaClick(item) },
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(16f / 9.5f)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun FigmaSeriesContent(
    series: List<DesktopMediaItem>,
    continueWatching: List<DesktopMediaItem>,
    accessToken: String = "",
    onMediaClick: (DesktopMediaItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item(key = "series_page_header") {
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
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Complete series and seasons discovered in your Google Drive.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "%,d".format(series.size),
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "series in your library",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )
                }
            }
        }

        series.firstOrNull()?.let { featuredShow ->
            item(key = "series_hero_banner") {
                FigmaHeroBanner(
                    item = featuredShow,
                    overline = "FEATURED SERIES",
                    primaryButtonText = "View Episodes",
                    accessToken = accessToken,
                    onPrimaryClick = { onMediaClick(featuredShow) }
                )
            }
        }

        val rows = series.chunked(3)
        items(
            items = rows,
            key = { row -> row.joinToString("-") { it.id } }
        ) { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                for (i in 0 until 3) {
                    val item = rowItems.getOrNull(i)
                    if (item != null) {
                        DesktopFigmaMediaCard(
                            item = item,
                            accessToken = accessToken,
                            onClick = { onMediaClick(item) },
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(16f / 9.2f)
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
private fun FigmaRecentsContent(
    recents: List<DesktopMediaItem>,
    accessToken: String = "",
    onMediaClick: (DesktopMediaItem) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveItem: (DesktopMediaItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
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
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Jump back into what you've watched.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                }

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onClearHistory() }
                        .pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF101520),
                    border = BorderStroke(1.dp, Color(0xFF1E2638))
                ) {
                    Text(
                        text = "Clear watch history",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        items(recents, key = { "history_${it.id}" }) { item ->
            val thumb = rememberThumbnailBitmap(primaryUrl = item.thumbnailUrl, fallbackUrl = item.fallbackThumbnailUrl, accessToken = accessToken)
            val progress = if (item.durationMs > 0L && item.progressMs > 0L) {
                (item.progressMs.toFloat() / item.durationMs.toFloat()).coerceIn(0.05f, 1f)
            } else 0.35f

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onMediaClick(item) }
                    .pointerHoverIcon(PointerIcon.Hand),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0C1018),
                border = BorderStroke(1.dp, Color(0xFF161D2B))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(0.45f)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(120.dp)
                                .height(68.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF161E2E))
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb,
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Column {
                            Text(
                                text = item.title,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = item.genre,
                                color = Color(0xFF64748B),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(0.35f)
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = "${(progress * 100).toInt()}% watched",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF1E293B))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .fillMaxHeight()
                                    .background(Color(0xFF2563EB))
                            )
                        }
                    }

                    IconButton(
                        onClick = { onRemoveItem(item) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Remove",
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun FigmaHeroSlideshowBanner(
    items: List<DesktopMediaItem>,
    seriesList: List<DesktopMediaItem>,
    accessToken: String = "",
    onMovieClick: (DesktopMediaItem) -> Unit,
    onSeriesClick: (DesktopMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    // Stabilize shuffled items: only append newly added items rather than reshuffling the entire deck
    val shuffledItems = remember {
        mutableStateListOf<DesktopMediaItem>().apply {
            if (items.isNotEmpty()) {
                addAll(items.shuffled())
            }
        }
    }

    LaunchedEffect(items) {
        if (items.isEmpty()) return@LaunchedEffect
        val existingIds = shuffledItems.map { it.id }.toSet()
        val newItems = items.filterNot { existingIds.contains(it.id) }

        if (shuffledItems.isEmpty()) {
            shuffledItems.addAll(items.shuffled())
        } else if (newItems.isNotEmpty()) {
            // Append newly discovered items to the end of the random queue
            shuffledItems.addAll(newItems)
        }
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    var previousIndex by remember { mutableIntStateOf(0) }

    // 5-second progress timer
    val timerProgress = remember { Animatable(0f) }

    // Continuous auto-advance loop (Never paused by mouse hover)
    LaunchedEffect(currentIndex, shuffledItems) {
        if (shuffledItems.size <= 1) return@LaunchedEffect

        timerProgress.snapTo(0f)
        try {
            timerProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 5000, easing = LinearEasing)
            )
            previousIndex = currentIndex
            currentIndex = (currentIndex + 1) % shuffledItems.size
        } catch (_: CancellationException) {
            // Cancelled only if user manually clicks a chevron or dot indicator
        }
    }

    val isMovingForward = if (previousIndex == 0 && currentIndex == shuffledItems.lastIndex) {
        false
    } else if (previousIndex == shuffledItems.lastIndex && currentIndex == 0) {
        true
    } else {
        currentIndex >= previousIndex
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(380.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF101520))
            .border(1.dp, Color(0xFF1B2333), RoundedCornerShape(18.dp))
    ) {
        // ========================================================
        // 1. HORIZONTAL SLIDE TRANSITION CONTENT
        // ========================================================
        AnimatedContent(
            targetState = currentIndex,
            transitionSpec = {
                if (isMovingForward) {
                    (slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(550, easing = FastOutSlowInEasing)
                    ) + fadeIn(tween(400))).togetherWith(
                        slideOutHorizontally(
                            targetOffsetX = { fullWidth -> -fullWidth },
                            animationSpec = tween(550, easing = FastOutSlowInEasing)
                        ) + fadeOut(tween(350))
                    )
                } else {
                    (slideInHorizontally(
                        initialOffsetX = { fullWidth -> -fullWidth },
                        animationSpec = tween(550, easing = FastOutSlowInEasing)
                    ) + fadeIn(tween(400))).togetherWith(
                        slideOutHorizontally(
                            targetOffsetX = { fullWidth -> fullWidth },
                            animationSpec = tween(550, easing = FastOutSlowInEasing)
                        ) + fadeOut(tween(350))
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { targetIdx ->
            val safeIndex = if (shuffledItems.isNotEmpty()) targetIdx.coerceIn(0, shuffledItems.lastIndex) else 0
            val item = shuffledItems.getOrNull(safeIndex) ?: return@AnimatedContent
            val isSeries = remember(item.id, seriesList) {
                seriesList.any { it.id == item.id }
            }
            val backdrop = rememberThumbnailBitmap(primaryUrl = item.thumbnailUrl, fallbackUrl = item.fallbackThumbnailUrl, accessToken = accessToken)

            Box(modifier = Modifier.fillMaxSize()) {
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

        // ========================================================
        // 2. ROYAL BLUE GRADIENT COUNTDOWN TIMER BAR
        // ========================================================
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(3.5.dp)
                .background(Color(0x26FFFFFF))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(timerProgress.value)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF0D47A1), // Deep Royal Blue
                                Color(0xFF42A5F5)  // Light Royal Blue
                            )
                        )
                    )
            )
        }

        // ========================================================
        // 3. SLIDE CONTROLS & INDICATOR DOTS (BOTTOM RIGHT)
        // ========================================================
        if (shuffledItems.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Previous Chevron
                IconButton(
                    onClick = {
                        previousIndex = currentIndex
                        currentIndex = if (currentIndex > 0) currentIndex - 1 else shuffledItems.lastIndex
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x800C111A))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronLeft,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Dots
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
                                    previousIndex = currentIndex
                                    currentIndex = index
                                }
                        )
                    }
                }

                // Next Chevron
                IconButton(
                    onClick = {
                        previousIndex = currentIndex
                        currentIndex = (currentIndex + 1) % shuffledItems.size
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0x800C111A))
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
    val backdrop = rememberThumbnailBitmap(primaryUrl = item.thumbnailUrl, fallbackUrl = item.fallbackThumbnailUrl, accessToken = accessToken)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(370.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF101520))
            .border(1.dp, Color(0xFF1B2333), RoundedCornerShape(18.dp))
    ) {
        if (backdrop != null) {
            Image(
                bitmap = backdrop,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
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
        accessToken = accessToken
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F1420))
            .border(1.dp, Color(0xFF1B2333), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        // Poster / Thumbnail Layer
        if (thumbnailBitmap != null) {
            Image(
                bitmap = thumbnailBitmap,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Elegant cinema card fallback with gradient and title instead of black box
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF1E293B), Color(0xFF090D16))
                        )
                    ),
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
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp))
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

        // Bottom Scrim & Metadata
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xCC05070B), Color(0xF205070B))
                    )
                )
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
                    .background(Color(0x661E293B))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction)
                        .fillMaxHeight()
                        .background(Color(0xFF00E5FF))
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
