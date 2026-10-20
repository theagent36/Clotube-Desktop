package com.clotube.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.clotube.desktop.model.MediaItem
import com.clotube.desktop.model.MockData
import com.clotube.desktop.ui.components.DesktopHeader
import com.clotube.desktop.ui.navigation.NavDestination
import com.clotube.desktop.ui.navigation.Sidebar
import com.clotube.desktop.ui.screens.DesktopDetailScreen
import com.clotube.desktop.ui.screens.DesktopHomeScreen
import com.clotube.desktop.ui.screens.MediaCard
import com.clotube.desktop.ui.screens.MediaShelf
import com.clotube.desktop.ui.theme.*
import java.awt.Dimension

fun main() = application {
    val windowState = rememberWindowState(
        width = 1440.dp,
        height = 900.dp,
        position = WindowPosition.Aligned(Alignment.Center)
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Clotube",
        state = windowState
    ) {
        // Enforce minimum window constraints
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(1024, 600)
        }

        ClotubeTheme {
            ClotubeAppRoot()
        }
    }
}

@Composable
fun ClotubeAppRoot() {
    var currentDestination by remember { mutableStateOf(NavDestination.HOME) }
    var activeDetailMedia by remember { mutableStateOf<MediaItem?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showProfileModal by remember { mutableStateOf(false) }

    // Filter media when user searches
    val searchResults by remember(searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                emptyList()
            } else {
                MockData.allMedia.filter {
                    it.title.contains(searchQuery, ignoreCase = true) ||
                    it.genre.contains(searchQuery, ignoreCase = true) ||
                    it.description.contains(searchQuery, ignoreCase = true)
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = VoidBackground
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left Navigation Rail
            Sidebar(
                currentDestination = currentDestination,
                onDestinationSelected = { dest ->
                    currentDestination = dest
                    activeDetailMedia = null
                }
            )

            // Main Content Area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(VoidBackground)
            ) {
                // Persistent Top Bar
                DesktopHeader(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onAvatarClick = { showProfileModal = !showProfileModal }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when {
                        // 1. Search Results View
                        searchQuery.isNotBlank() -> {
                            SearchResultsView(
                                query = searchQuery,
                                results = searchResults,
                                onMediaClick = { activeDetailMedia = it }
                            )
                        }

                        // 2. Detail Screen View
                        activeDetailMedia != null -> {
                            DesktopDetailScreen(
                                media = activeDetailMedia!!,
                                onBack = { activeDetailMedia = null }
                            )
                        }

                        // 3. Tab Screens
                        currentDestination == NavDestination.HOME -> {
                            DesktopHomeScreen(
                                onMediaClick = { activeDetailMedia = it }
                            )
                        }

                        currentDestination == NavDestination.MOVIES -> {
                            CategoryGridView(
                                title = "Feature Films & Movies",
                                subtitle = "Lossless 4K streams ready for instant playback",
                                items = MockData.trendingMovies + listOf(MockData.heroBillboard),
                                onMediaClick = { activeDetailMedia = it }
                            )
                        }

                        currentDestination == NavDestination.TV_SERIES -> {
                            CategoryGridView(
                                title = "TV Series & Binge Shows",
                                subtitle = "Full seasons with instant cloud direct streaming",
                                items = MockData.popularSeries,
                                onMediaClick = { activeDetailMedia = it }
                            )
                        }

                        currentDestination == NavDestination.RECENTS -> {
                            CategoryGridView(
                                title = "Recently Played",
                                subtitle = "Resume your progress across all connected displays",
                                items = MockData.continueWatchingList,
                                onMediaClick = { activeDetailMedia = it },
                                showProgress = true
                            )
                        }

                        currentDestination == NavDestination.SETTINGS -> {
                            SettingsView()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchResultsView(
    query: String,
    results: List<MediaItem>,
    onMediaClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Search results for \"$query\"",
            style = ClotubeTypography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
        )

        if (results.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No matching media found for '$query'. Try another keyword.",
                    style = ClotubeTypography.bodyLarge.copy(color = TextSlateMuted)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(results) { item ->
                    MediaCard(media = item, onClick = { onMediaClick(item) })
                }
            }
        }
    }
}

@Composable
fun CategoryGridView(
    title: String,
    subtitle: String,
    items: List<MediaItem>,
    onMediaClick: (MediaItem) -> Unit,
    showProgress: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = ClotubeTypography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
            )
            Text(
                text = subtitle,
                style = ClotubeTypography.bodySmall.copy(color = TextSlateMuted)
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 270.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items) { item ->
                MediaCard(
                    media = item,
                    onClick = { onMediaClick(item) },
                    showProgress = showProgress
                )
            }
        }
    }
}

@Composable
fun SettingsView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "Desktop Preferences",
            style = ClotubeTypography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Playback & Hardware Acceleration",
                style = ClotubeTypography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextWhite)
            )

            SettingsToggleRow(title = "Direct Stream Passthrough", desc = "Play raw MKV/MP4 files without transcode", enabled = true)
            SettingsToggleRow(title = "Hardware Video Decode (D3D11VA)", desc = "Offload 4K HEVC/AV1 parsing to GPU", enabled = true)
            SettingsToggleRow(title = "HDR Tone Mapping", desc = "Enable Windows HDR auto-calibration", enabled = true)
            SettingsToggleRow(title = "Dolby Atmos Bitstream", desc = "Output untouched audio to HDMI receiver", enabled = false)
        }
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    desc: String,
    enabled: Boolean
) {
    var isChecked by remember { mutableStateOf(enabled) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = ClotubeTypography.bodyMedium.copy(color = TextWhite, fontWeight = FontWeight.Medium))
            Text(text = desc, style = ClotubeTypography.bodySmall.copy(color = TextSlateMuted))
        }

        Switch(
            checked = isChecked,
            onCheckedChange = { isChecked = it },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentPrimary,
                uncheckedThumbColor = TextSlateMuted,
                uncheckedTrackColor = ElevatedCard
            )
        )
    }
}
