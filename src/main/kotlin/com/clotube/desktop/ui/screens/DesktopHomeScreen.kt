package com.clotube.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.data.DesktopDriveScanner
import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.components.ClotubeGlyphIcon
import com.clotube.desktop.ui.components.DesktopInlinePlayerPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

enum class DesktopNavSection(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    MOVIES("Movies", Icons.Rounded.Movie),
    TV_SERIES("TV Series", Icons.Rounded.Tv),
    RECENTS("Recents", Icons.Rounded.History)
}

private val thumbnailCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun rememberDriveThumbnail(url: String): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf(thumbnailCache[url]) }

    LaunchedEffect(url) {
        if (url.isNotBlank() && bitmap == null) {
            withContext(Dispatchers.IO) {
                try {
                    val connection = (java.net.URI(url).toURL().openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        doInput = true
                        connect()
                    }
                    if (connection.responseCode in 200..299) {
                        val bytes = connection.inputStream.use { it.readBytes() }
                        val decoded = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                        thumbnailCache[url] = decoded
                        bitmap = decoded
                    }
                } catch (_: Exception) {}
            }
        }
    }
    return bitmap
}

@Composable
fun DesktopHomeScreen(
    userEmail: String,
    userName: String,
    accessToken: String = "",
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
    modifier: Modifier = Modifier
) {
    var currentSection by remember(activeSection) { mutableStateOf(activeSection) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSeriesForDetail by remember { mutableStateOf<DesktopMediaItem?>(null) }

    var activeInlineMedia by remember { mutableStateOf<DesktopMediaItem?>(null) }
    var inlineSeriesEpisodes by remember { mutableStateOf<List<DesktopEpisode>>(emptyList()) }

    LaunchedEffect(activeInlineMedia?.id) {
        val media = activeInlineMedia ?: run {
            inlineSeriesEpisodes = emptyList()
            return@LaunchedEffect
        }
        if (inlineSeriesEpisodes.any { it.id == media.id }) {
            return@LaunchedEffect
        }
        val isSeries = media.genre.contains("Series", ignoreCase = true) || series.any { it.id == media.id }
        if (isSeries) {
            withContext(Dispatchers.IO) {
                try {
                    val scanner = DesktopDriveScanner(accessToken)
                    val details = scanner.loadSeriesDetails(media.id, media.title)
                    val allEpisodes = details.seasons.flatMap { it.episodes }
                    if (allEpisodes.isNotEmpty()) {
                        inlineSeriesEpisodes = allEpisodes
                        val firstEp = allEpisodes.first()
                        if (activeInlineMedia?.id == media.id) {
                            activeInlineMedia = DesktopMediaItem(
                                id = firstEp.id,
                                title = "${media.title} - ${firstEp.title}",
                                thumbnailUrl = firstEp.thumbnailUrl.ifBlank { media.thumbnailUrl },
                                genre = "S${firstEp.seasonNumber}:E${firstEp.episodeNumber}",
                                durationMs = firstEp.durationMs
                            )
                        }
                    } else {
                        inlineSeriesEpisodes = emptyList()
                    }
                } catch (_: Exception) {
                    inlineSeriesEpisodes = emptyList()
                }
            }
        } else {
            inlineSeriesEpisodes = emptyList()
        }
    }

    val navigateTo: (DesktopNavSection) -> Unit = { section ->
        selectedSeriesForDetail = null
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
        DesktopFigmaSidebar(
            activeSection = currentSection,
            recentsCount = continueWatching.size,
            totalItemsCount = movies.size + series.size,
            onSelectSection = navigateTo
        )

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(Color(0xFF141923))
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFF07090E))
        ) {
            DesktopFigmaTopBar(
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                userName = userName.ifBlank { userEmail.substringBefore('@').ifBlank { "Clotube User" } },
                userEmail = userEmail,
                onSignOut = onSignOut
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (selectedSeriesForDetail != null) {
                    DesktopSeriesDetailScreen(
                        seriesItem = selectedSeriesForDetail!!,
                        accessToken = accessToken,
                        onBack = { selectedSeriesForDetail = null }
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
                                activeInlineMedia = activeInlineMedia,
                                inlineSeriesEpisodes = inlineSeriesEpisodes,
                                onPlayMedia = { media ->
                                    activeInlineMedia = media
                                },
                                onCloseInlinePlayer = {
                                    activeInlineMedia = null
                                },
                                onEpisodeSelected = { ep ->
                                    activeInlineMedia = DesktopMediaItem(
                                        id = ep.id,
                                        title = ep.title,
                                        thumbnailUrl = ep.thumbnailUrl,
                                        genre = "S${ep.seasonNumber}:E${ep.episodeNumber}",
                                        durationMs = ep.durationMs
                                    )
                                },
                                onNavigateSection = navigateTo,
                                onSeriesClick = { seriesItem ->
                                    selectedSeriesForDetail = seriesItem
                                }
                            )
                        }
                        DesktopNavSection.MOVIES -> {
                            FigmaMoviesContent(
                                movies = filteredMovies,
                                onMediaClick = { movie ->
                                    activeInlineMedia = movie
                                    currentSection = DesktopNavSection.HOME
                                }
                            )
                        }
                        DesktopNavSection.TV_SERIES -> {
                            FigmaTvSeriesContent(
                                series = filteredSeries,
                                continueWatching = continueWatching,
                                onMediaClick = { seriesItem ->
                                    selectedSeriesForDetail = seriesItem
                                }
                            )
                        }
                        DesktopNavSection.RECENTS -> {
                            FigmaRecentsContent(
                                recents = continueWatching.ifEmpty { filteredMovies.take(3) },
                                onMediaClick = { item ->
                                    activeInlineMedia = item
                                    currentSection = DesktopNavSection.HOME
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
private fun FigmaHomeContent(
    featuredItem: DesktopMediaItem?,
    continueWatching: List<DesktopMediaItem>,
    movies: List<DesktopMediaItem>,
    series: List<DesktopMediaItem>,
    accessToken: String,
    activeInlineMedia: DesktopMediaItem?,
    inlineSeriesEpisodes: List<DesktopEpisode>,
    onPlayMedia: (DesktopMediaItem) -> Unit,
    onCloseInlinePlayer: () -> Unit,
    onEpisodeSelected: (DesktopEpisode) -> Unit,
    onNavigateSection: (DesktopNavSection) -> Unit,
    onSeriesClick: (DesktopMediaItem) -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = activeInlineMedia != null,
            enter = expandVertically(
                animationSpec = tween(450, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top
            ) + fadeIn(animationSpec = tween(300)),
            exit = shrinkVertically(
                animationSpec = tween(350, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top
            ) + fadeOut(animationSpec = tween(200))
        ) {
            activeInlineMedia?.let { media ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 8.dp)
                ) {
                    DesktopInlinePlayerPanel(
                        media = media,
                        accessToken = accessToken,
                        seriesEpisodes = inlineSeriesEpisodes,
                        onEpisodeSelected = onEpisodeSelected,
                        onClosePlayer = onCloseInlinePlayer
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            featuredItem?.let { hero ->
                item(key = "home_hero_billboard") {
                    FigmaHeroBanner(
                        item = hero,
                        overline = "FEATURED IN YOUR DRIVE",
                        primaryButtonText = "Resume watching",
                        onPrimaryClick = {
                            onPlayMedia(hero)
                            coroutineScope.launch { listState.animateScrollToItem(0) }
                        }
                    )
                }
            }

            if (continueWatching.isNotEmpty()) {
                item(key = "home_continue_watching") {
                    FigmaCarouselSection(
                        overline = "PICK UP WHERE YOU LEFT OFF",
                        title = "Continue watching",
                        actionLabel = "View all",
                        items = continueWatching.take(5),
                        onActionClick = { onNavigateSection(DesktopNavSection.RECENTS) },
                        onItemClick = { item ->
                            onPlayMedia(item)
                            coroutineScope.launch { listState.animateScrollToItem(0) }
                        }
                    )
                }
            }

            if (movies.isNotEmpty()) {
                item(key = "home_movies_row") {
                    FigmaCarouselSection(
                        overline = "MOVIES COLLECTION",
                        title = "Movies",
                        actionLabel = "View all",
                        items = movies.take(5),
                        onActionClick = { onNavigateSection(DesktopNavSection.MOVIES) },
                        onItemClick = { item ->
                            onPlayMedia(item)
                            coroutineScope.launch { listState.animateScrollToItem(0) }
                        }
                    )
                }
            }

            if (series.isNotEmpty()) {
                item(key = "home_series_row") {
                    FigmaCarouselSection(
                        overline = "MULTI-SEASON SHOWS",
                        title = "TV Series",
                        actionLabel = "View all",
                        items = series.take(5),
                        onActionClick = { onNavigateSection(DesktopNavSection.TV_SERIES) },
                        onItemClick = onSeriesClick
                    )
                }
            }
        }
    }
}

@Composable
private fun FigmaMoviesContent(
    movies: List<DesktopMediaItem>,
    onMediaClick: (DesktopMediaItem) -> Unit
) {
    val genres = listOf("All", "Action", "Adventure", "Drama", "Thriller", "Mystery", "Comedy")
    var selectedGenre by remember { mutableStateOf("All") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
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
                        text = "Your private cinema collection stored in Google Drive.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "%,d".format(movies.size),
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "movies available",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )
                }
            }
        }

        movies.firstOrNull()?.let { featuredMovie ->
            item(key = "movies_hero_banner") {
                FigmaHeroBanner(
                    item = featuredMovie,
                    overline = "FEATURED MOVIE",
                    primaryButtonText = "Watch now",
                    onPrimaryClick = { onMediaClick(featuredMovie) }
                )
            }
        }

        item(key = "movies_filter_row") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                genres.forEach { genre ->
                    val isSelected = selectedGenre == genre
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { selectedGenre = genre }
                            .pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) Color(0xFF2563EB) else Color(0xFF111622),
                        border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF1E2638))
                    ) {
                        Text(
                            text = genre,
                            color = if (isSelected) Color.White else Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }

        val rows = movies.chunked(4)
        items(rows) { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                for (i in 0 until 4) {
                    val item = rowItems.getOrNull(i)
                    if (item != null) {
                        DesktopFigmaMediaCard(
                            item = item,
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

@Composable
private fun FigmaTvSeriesContent(
    series: List<DesktopMediaItem>,
    continueWatching: List<DesktopMediaItem>,
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
                        text = "TV Series",
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
                    onPrimaryClick = { onMediaClick(featuredShow) }
                )
            }
        }

        val rows = series.chunked(3)
        items(rows) { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                for (i in 0 until 3) {
                    val item = rowItems.getOrNull(i)
                    if (item != null) {
                        DesktopFigmaMediaCard(
                            item = item,
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
            val thumb = rememberDriveThumbnail(item.thumbnailUrl)
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

// ============================================================================
// CLEAN HERO BANNER (NO FAKE SPEC PILLS / CLEAN METADATA)
// ============================================================================
@Composable
private fun FigmaHeroBanner(
    item: DesktopMediaItem,
    overline: String,
    primaryButtonText: String,
    onPrimaryClick: () -> Unit
) {
    val backdrop = rememberDriveThumbnail(item.thumbnailUrl)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
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
                            Color(0xF5080B11),
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

            // Clean real metadata only
            val metadataString = buildString {
                if (item.durationMs > 0L) append("${formatDuration(item.durationMs)}   •   ")
                append(item.genre)
            }
            Text(
                text = metadataString,
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(24.dp))

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
                    onClick = { onItemClick(item) },
                    modifier = Modifier
                        .width(248.dp)
                        .height(144.dp)
                )
            }
        }
    }
}

// ============================================================================
// CLEAN MEDIA CARD (NO FAKE 4K BADGE, ACCURATE DURATION ONLY)
// ============================================================================
@Composable
fun DesktopFigmaMediaCard(
    item: DesktopMediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap = rememberDriveThumbnail(item.thumbnailUrl)

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .pointerHoverIcon(PointerIcon.Hand),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF101520),
        border = BorderStroke(1.dp, Color(0xFF1E2638))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
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
                                listOf(Color(0xFF172033), Color(0xFF0C101A))
                            )
                        )
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x1A000000),
                                Color(0x4D000000),
                                Color(0xE607090E)
                            )
                        )
                    )
            )

            // Top-Right Duration Badge (Only when duration is present)
            if (item.durationMs > 0L) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    shape = RoundedCornerShape(5.dp),
                    color = Color(0xCC080B11)
                ) {
                    Text(
                        text = formatDuration(item.durationMs),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
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
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
}

@Composable
private fun DesktopFigmaSidebar(
    activeSection: DesktopNavSection,
    recentsCount: Int,
    totalItemsCount: Int,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClotubeGlyphIcon(size = 34.dp)
                Spacer(modifier = Modifier.width((-4).dp))
                Text(
                    text = "lotube",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp
                )
            }

            Icon(
                imageVector = Icons.Rounded.Menu,
                contentDescription = "Menu",
                tint = Color(0xFF64748B),
                modifier = Modifier.size(18.dp)
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
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
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

                    if (section == DesktopNavSection.RECENTS && recentsCount > 0) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2563EB)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = recentsCount.coerceAtMost(99).toString(),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
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

        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0D111A),
            border = BorderStroke(1.dp, Color(0xFF1A2232))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudQueue,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Cloud library",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "Synced",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF1E293B))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.68f)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF2563EB), Color(0xFF00E5FF))
                                )
                            )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "$totalItemsCount titles indexed in Google Drive",
                    color = Color(0xFF64748B),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun DesktopFigmaTopBar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    userName: String,
    userEmail: String,
    onSignOut: () -> Unit
) {
    var showAccountMenu by remember { mutableStateOf(false) }
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
                color = Color(0xFF0C1928),
                border = BorderStroke(1.dp, Color(0xFF16324F))
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
                            .background(Color(0xFF00E5FF))
                    )
                    Icon(
                        imageVector = Icons.Rounded.CloudDone,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Direct stream",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
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
                                    listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initials,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
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
                    modifier = Modifier.background(Color(0xFF131824))
                ) {
                    DropdownMenuItem(
                        text = { Text("Sign Out ($userEmail)", color = Color(0xFFF87171), fontSize = 12.sp) },
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

private fun formatDuration(durationMs: Long): String {
    val totalMinutes = (durationMs / 60000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h %02dm".format(minutes) else "${minutes}m"
}
