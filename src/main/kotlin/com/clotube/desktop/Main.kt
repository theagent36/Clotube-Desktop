package com.clotube.desktop

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.clotube.desktop.auth.AuthState
import com.clotube.desktop.auth.DesktopAuthManager
import com.clotube.desktop.data.DesktopDriveScanner
import com.clotube.desktop.data.DesktopLibraryCache
import com.clotube.desktop.media.LocalStreamServer
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.model.MediaItem
import com.clotube.desktop.model.MockData
import com.clotube.desktop.ui.components.DesktopHeader
import com.clotube.desktop.ui.navigation.NavDestination
import com.clotube.desktop.ui.navigation.Sidebar
import com.clotube.desktop.ui.screens.DesktopDetailScreen
import com.clotube.desktop.ui.screens.DesktopHomeScreen
import com.clotube.desktop.ui.screens.DesktopLoginScreen
import com.clotube.desktop.ui.screens.DesktopNavSection
import com.clotube.desktop.ui.screens.DesktopVideoPlayerScreen
import com.clotube.desktop.ui.screens.MediaCard
import com.clotube.desktop.ui.theme.*
import java.awt.Dimension
import javax.swing.UIManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() = application {
    // 1. Force IPv4 socket stack for Google Drive streaming on dGPU profiles
    System.setProperty("java.net.preferIPv4Stack", "true")
    System.setProperty("java.net.preferIPv6Addresses", "false")

    // 2. Hardware presentation settings
    System.setProperty("compose.interop.blending", "false")
    System.setProperty("skiko.renderApi", "DIRECT3D")

    // 3. Force all Swing/AWT defaults to pure black to eliminate white background flashes
    try {
        UIManager.put("Panel.background", java.awt.Color.BLACK)
        UIManager.put("Panel.foreground", java.awt.Color.BLACK)
        UIManager.put("Window.background", java.awt.Color.BLACK)
        UIManager.put("Viewport.background", java.awt.Color.BLACK)
        UIManager.put("control", java.awt.Color.BLACK)
    } catch (_: Throwable) {}

    // 4. Start local streaming server
    LocalStreamServer.start()

    val windowState = rememberWindowState(
        width = 1440.dp,
        height = 900.dp,
        position = WindowPosition.Aligned(Alignment.Center)
    )

    @Suppress("DEPRECATION")
    Window(
        onCloseRequest = {
            exitApplication()
        },
        title = "Clotube",
        icon = painterResource("ic_clotube.png"),
        state = windowState
    ) {
        SideEffect {
            window.background = java.awt.Color.BLACK
            window.contentPane.background = java.awt.Color.BLACK
            window.rootPane.background = java.awt.Color.BLACK
        }

        // Enforce minimum window constraints
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(1024, 600)
        }

        ClotubeTheme {
            App()
        }
    }
}

@Composable
fun ClotubeRootApp() {
    App()
}

@Composable
fun MainDesktopLayout(
    authenticatedState: AuthState.Authenticated,
    onSignOut: () -> Unit,
    movies: List<DesktopMediaItem> = emptyList(),
    series: List<DesktopMediaItem> = emptyList()
) {
    var activeSection by remember { mutableStateOf(DesktopNavSection.HOME) }
    var activePlayingMedia by remember { mutableStateOf<DesktopMediaItem?>(null) }

    // Seed continue watching items from real drive media if available, maintaining progress
    var continueWatching by remember(movies, series) {
        mutableStateOf<List<DesktopMediaItem>>(
            if (movies.isNotEmpty() || series.isNotEmpty()) {
                val pool = (movies + series).take(4)
                pool.mapIndexed { index, item ->
                    val simDuration = if (item.durationMs > 0L) item.durationMs else (7200000L - index * 600000L)
                    val fraction = when (index) {
                        0 -> 0.62f
                        1 -> 0.28f
                        2 -> 0.34f
                        else -> 0.45f
                    }
                    item.copy(
                        durationMs = simDuration,
                        progressMs = (simDuration * fraction).toLong()
                    )
                }
            } else emptyList()
        )
    }

    Crossfade(
        targetState = activePlayingMedia,
        animationSpec = tween(durationMillis = 250),
        label = "VideoPlayerCrossfade"
    ) { playingItem ->
        if (playingItem != null) {
            DesktopVideoPlayerScreen(
                media = playingItem,
                accessToken = authenticatedState.accessToken,
                onClose = { activePlayingMedia = null },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF07090E))
            ) {
                DesktopHomeScreen(
                    userEmail = authenticatedState.userEmail,
                    userName = authenticatedState.userName,
                    userAvatarUrl = authenticatedState.userPhotoUrl ?: "",
                    onSignOut = onSignOut,
                    activeSection = activeSection,
                    onNavigateSection = { section -> activeSection = section },
                    featuredItem = movies.firstOrNull() ?: series.firstOrNull(),
                    continueWatching = continueWatching,
                    movies = movies,
                    series = series,
                    onMediaClick = { clickedItem ->
                        val simDuration = if (clickedItem.durationMs > 0L) clickedItem.durationMs else 7200000L
                        val existing = continueWatching.find { it.id == clickedItem.id }
                        val updated = existing?.copy(progressMs = (simDuration * 0.5f).toLong())
                            ?: clickedItem.copy(durationMs = simDuration, progressMs = (simDuration * 0.2f).toLong())
                        continueWatching = listOf(updated) + continueWatching.filter { it.id != clickedItem.id }
                    },
                    onClearHistory = {
                        continueWatching = emptyList()
                    },
                    onRemoveHistoryItem = { item ->
                        continueWatching = continueWatching.filter { it.id != item.id }
                    },
                    accessToken = authenticatedState.accessToken,
                    modifier = Modifier.fillMaxSize()
                )
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
