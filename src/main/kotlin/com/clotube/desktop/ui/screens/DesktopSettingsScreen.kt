package com.clotube.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.clotube.desktop.data.DesktopAppSettings
import com.clotube.desktop.update.DesktopUpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SettingsTab(val label: String, val icon: ImageVector) {
    ACCOUNT("Account & Drive", Icons.Rounded.CloudQueue),
    PLAYBACK("Playback Engine", Icons.Rounded.PlayCircle),
    DISPLAY("Display & Power", Icons.Rounded.Monitor),
    AUDIO_SUBS("Audio & Subtitles", Icons.Rounded.Subtitles),
    STORAGE("Storage & Cache", Icons.Rounded.Storage),
    ABOUT("About & Engine", Icons.Rounded.Info)
}

@Composable
fun DesktopSettingsScreen(
    userEmail: String,
    userName: String,
    onSignOut: () -> Unit,
    onRescanLibrary: () -> Unit = {},
    onClearWatchHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(SettingsTab.ACCOUNT) }
    val scope = rememberCoroutineScope()

    var cacheSizeBytes by remember { mutableLongStateOf(0L) }
    var isCacheClearing by remember { mutableStateOf(false) }
    var isRescanning by remember { mutableStateOf(false) }

    var showSignOutDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    var toastMessage by remember { mutableStateOf<String?>(null) }
    var toastJob by remember { mutableStateOf<Job?>(null) }

    fun showToast(msg: String) {
        toastJob?.cancel()
        toastMessage = msg
        toastJob = scope.launch {
            delay(2500L)
            toastMessage = null
        }
    }

    LaunchedEffect(userEmail) {
        cacheSizeBytes = DesktopAppSettings.calculateCacheSizeBytes(userEmail)
    }

    val settings = DesktopAppSettings.state

    val rescanAngle = remember { Animatable(0f) }

    LaunchedEffect(isRescanning) {
        if (isRescanning) {
            while (isRescanning) {
                rescanAngle.animateTo(
                    targetValue = rescanAngle.value + 360f,
                    animationSpec = tween(900, easing = LinearEasing)
                )
            }
        } else {
            rescanAngle.snapTo(0f)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF07090E))
        ) {
            // LEFT SUB-NAV: Categories Pane
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF090C12))
                    .padding(horizontal = 16.dp, vertical = 24.dp)
            ) {
                Text(
                    text = "SETTINGS",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                SettingsTab.entries.forEach { tab ->
                    val isSelected = activeTab == tab
                    val tabInteraction = remember { MutableInteractionSource() }
                    val isTabHovered by tabInteraction.collectIsHoveredAsState()

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = tabInteraction,
                                indication = null
                            ) { activeTab = tab }
                            .pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(8.dp),
                        color = when {
                            isSelected -> Color(0xFF131824)
                            isTabHovered -> Color(0xFF0E131D)
                            else -> Color.Transparent
                        },
                        border = if (isSelected) BorderStroke(1.dp, Color(0xFF1E283A)) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(if (isSelected) Color(0xFF60A5FA) else Color.Transparent)
                            )
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = when {
                                    isSelected -> Color(0xFF60A5FA)
                                    isTabHovered -> Color(0xFFCBD5E1)
                                    else -> Color(0xFF64748B)
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = tab.label,
                                color = when {
                                    isSelected -> Color.White
                                    isTabHovered -> Color(0xFFE2E8F0)
                                    else -> Color(0xFF94A3B8)
                                },
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Vertical Separator
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(Color(0xFF141923))
            )

            // RIGHT WORKSPACE: Category Details Card
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 36.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column {
                    Text(
                        text = activeTab.label,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (activeTab) {
                            SettingsTab.ACCOUNT -> "Manage your connected Google Account and cloud media indexing preferences."
                            SettingsTab.PLAYBACK -> "Configure media pre-buffering, autoplay behaviors, and seek step intervals."
                            SettingsTab.DISPLAY -> "Direct3D 11 hardware decoding, monitor frame pacing, and background eco modes."
                            SettingsTab.AUDIO_SUBS -> "Preferred languages, default subtitle states, and persistent audio volume."
                            SettingsTab.STORAGE -> "Local disk usage for artwork, series metadata caches, and watch history."
                            SettingsTab.ABOUT -> "Application architecture, bundled 64-bit LibVLC core, and local stream proxy."
                        },
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }

                when (activeTab) {
                    SettingsTab.ACCOUNT -> {
                        SettingsCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8))
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = userName.firstOrNull()?.uppercase() ?: "C",
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = userName.ifBlank { "Clotube User" },
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(text = userEmail, color = Color(0xFF94A3B8), fontSize = 12.sp)
                                    }
                                }
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showSignOutDialog = true }
                                        .pointerHoverIcon(PointerIcon.Hand),
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0x1AE11D48),
                                    border = BorderStroke(1.dp, Color(0x33E11D48))
                                ) {
                                    Text(
                                        text = "Sign Out",
                                        color = Color(0xFFFB7185),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }

                        SettingsCard {
                            SettingsToggleRow(
                                title = "Sync Watch History to Cloud",
                                subtitle = "Keep playback timestamps in sync across desktop sessions via Google Drive.",
                                checked = settings.autoSyncHistory,
                                onCheckedChange = {
                                    DesktopAppSettings.update { copy(autoSyncHistory = it) }
                                    showToast(if (it) "Cloud history sync enabled" else "Cloud history sync paused")
                                }
                            )
                            HorizontalDivider(color = Color(0xFF161D2B), modifier = Modifier.padding(vertical = 12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("Delta Library Revalidation", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Check Google Drive for newly added movies and series in the background.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = !isRescanning) {
                                            if (!isRescanning) {
                                                isRescanning = true
                                                onRescanLibrary()
                                                showToast("Google Drive delta scan initiated")
                                                scope.launch {
                                                    delay(2500L)
                                                    isRescanning = false
                                                }
                                            }
                                        }
                                        .pointerHoverIcon(if (!isRescanning) PointerIcon.Hand else PointerIcon.Default),
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF141A26),
                                    border = BorderStroke(1.dp, Color(0xFF222B3D))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = null,
                                            tint = Color(0xFF94A3B8),
                                            modifier = Modifier
                                                .size(14.dp)
                                                .then(if (isRescanning) Modifier.rotate(rescanAngle.value) else Modifier)
                                        )
                                        Text(
                                            text = if (isRescanning) "Scanning Drive..." else "Rescan Drive Now",
                                            color = Color(0xFFCBD5E1),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SettingsTab.PLAYBACK -> {
                        SettingsCard {
                            SettingsToggleRow(
                                title = "Smart Playback Resume",
                                subtitle = "Automatically resume movies and episodes where you previously left off.",
                                checked = settings.resumePlayback,
                                onCheckedChange = {
                                    DesktopAppSettings.update { copy(resumePlayback = it) }
                                    showToast(if (it) "Playback resume enabled" else "Playback will start from 00:00")
                                }
                            )
                            HorizontalDivider(color = Color(0xFF161D2B), modifier = Modifier.padding(vertical = 12.dp))
                            SettingsToggleRow(
                                title = "Auto-Play Next Episode",
                                subtitle = "Automatically start the next episode when the current one finishes.",
                                checked = settings.autoPlayNextEpisode,
                                onCheckedChange = {
                                    DesktopAppSettings.update { copy(autoPlayNextEpisode = it) }
                                    showToast(if (it) "Auto-play enabled" else "Auto-play disabled")
                                }
                            )
                            HorizontalDivider(color = Color(0xFF161D2B), modifier = Modifier.padding(vertical = 12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("Stream Pre-Buffer Depth", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Higher buffer prevents stutter on high-bitrate 4K files.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                                StudioSegmentedControl(
                                    items = listOf(1500, 3000, 5000),
                                    selectedItem = settings.bufferDepthMs,
                                    onItemSelected = { ms ->
                                        DesktopAppSettings.update { copy(bufferDepthMs = ms) }
                                        val label = when (ms) {
                                            1500 -> "1.5s"
                                            3000 -> "3.0s"
                                            5000 -> "5.0s"
                                            else -> "${ms}ms"
                                        }
                                        showToast("Buffer depth set to $label")
                                    },
                                    labelProvider = { ms ->
                                        when (ms) {
                                            1500 -> "1.5s (Fast)"
                                            3000 -> "3.0s (Normal)"
                                            5000 -> "5.0s (Smooth)"
                                            else -> "${ms}ms"
                                        }
                                    }
                                )
                            }
                        }
                    }

                    SettingsTab.DISPLAY -> {
                        SettingsCard {
                            SettingsToggleRow(
                                title = "Hardware Acceleration (Direct3D 11 / NVDEC)",
                                subtitle = "Use GPU hardware decoding (--avcodec-hw=any) to offload video processing from CPU. Takes effect on next media play.",
                                checked = settings.hardwareAcceleration,
                                onCheckedChange = {
                                    DesktopAppSettings.update { copy(hardwareAcceleration = it) }
                                    showToast(
                                        if (it) "GPU decoding enabled (active on next video)"
                                        else "Software decoding fallback enabled"
                                    )
                                }
                            )
                            HorizontalDivider(color = Color(0xFF161D2B), modifier = Modifier.padding(vertical = 12.dp))
                            SettingsToggleRow(
                                title = "Cinema Color & Dynamic Range Optimization",
                                subtitle = "Expands 16-235 limited range to 0-255 full PC levels and enriches saturation for 4K HDR and BluRay streams.",
                                checked = settings.cinemaColorEnhancement,
                                onCheckedChange = {
                                    DesktopAppSettings.update { copy(cinemaColorEnhancement = it) }
                                    showToast(
                                        if (it) "Cinema Color Optimization enabled"
                                        else "Cinema Color Optimization disabled (Flat raw output)"
                                    )
                                }
                            )
                            HorizontalDivider(color = Color(0xFF161D2B), modifier = Modifier.padding(vertical = 12.dp))
                            SettingsToggleRow(
                                title = "Background Eco Mode",
                                subtitle = "Freeze Compose render cycles and drop GPU usage when the app window is minimized.",
                                checked = settings.backgroundEcoMode,
                                onCheckedChange = {
                                    DesktopAppSettings.update { copy(backgroundEcoMode = it) }
                                }
                            )
                            HorizontalDivider(color = Color(0xFF161D2B), modifier = Modifier.padding(vertical = 12.dp))
                            SettingsToggleRow(
                                title = "Battery Saver (Cap at 60 FPS)",
                                subtitle = "Restrict Compose UI frame pacing to 60 FPS on high refresh displays (120Hz-165Hz+).",
                                checked = settings.batterySaver60Fps,
                                onCheckedChange = {
                                    DesktopAppSettings.update { copy(batterySaver60Fps = it) }
                                }
                            )
                        }
                    }

                    SettingsTab.AUDIO_SUBS -> {
                        SettingsCard {
                            SettingsToggleRow(
                                title = "Sticky Volume Persistence",
                                subtitle = "Preserve the master volume across consecutive media items and app restarts.",
                                checked = settings.rememberVolume,
                                onCheckedChange = {
                                    DesktopAppSettings.update { copy(rememberVolume = it) }
                                    showToast(if (it) "Volume persistence enabled" else "Volume resets to 100% on start")
                                }
                            )
                            HorizontalDivider(color = Color(0xFF161D2B), modifier = Modifier.padding(vertical = 12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("Default Subtitles State", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Initial subtitle track selection when starting playback.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                                StudioSegmentedControl(
                                    items = listOf("Off", "English", "Auto-Detect"),
                                    selectedItem = settings.defaultSubtitleState,
                                    onItemSelected = { opt ->
                                        DesktopAppSettings.update { copy(defaultSubtitleState = opt) }
                                        showToast("Default subtitles: $opt")
                                    },
                                    labelProvider = { it }
                                )
                            }
                        }
                    }

                    SettingsTab.STORAGE -> {
                        SettingsCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("Disk Cache Consumption", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    val mb = "%.2f MB".format(cacheSizeBytes / (1024.0 * 1024.0))
                                    Text("Local poster thumbnails and series episode data: $mb", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                                val canClearCache = !isCacheClearing && cacheSizeBytes > 0L
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(enabled = canClearCache) {
                                            scope.launch {
                                                isCacheClearing = true
                                                val bytesBefore = cacheSizeBytes
                                                DesktopAppSettings.clearThumbnailCache(userEmail)
                                                cacheSizeBytes = DesktopAppSettings.calculateCacheSizeBytes(userEmail)
                                                val freedMb = "%.2f MB".format((bytesBefore - cacheSizeBytes).coerceAtLeast(0L) / (1024.0 * 1024.0))
                                                isCacheClearing = false
                                                showToast("Thumbnail cache cleared ($freedMb freed)")
                                            }
                                        }
                                        .pointerHoverIcon(if (canClearCache) PointerIcon.Hand else PointerIcon.Default),
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (canClearCache) Color(0xFF141A26) else Color(0xFF0F141E),
                                    border = BorderStroke(1.dp, if (canClearCache) Color(0xFF222B3D) else Color(0xFF19202D))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteSweep,
                                            contentDescription = null,
                                            tint = if (canClearCache) Color(0xFF94A3B8) else Color(0xFF475569),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (isCacheClearing) "Purging..." else "Clear Image Cache",
                                            color = if (canClearCache) Color(0xFFCBD5E1) else Color(0xFF64748B),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = Color(0xFF161D2B), modifier = Modifier.padding(vertical = 12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("Local Playback History", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Erase local resume marks from this device without affecting Google Drive.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { showClearHistoryDialog = true }
                                        .pointerHoverIcon(PointerIcon.Hand),
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0x1AE11D48),
                                    border = BorderStroke(1.dp, Color(0x33E11D48))
                                ) {
                                    Text(
                                        text = "Clear History",
                                        color = Color(0xFFFB7185),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }

                    SettingsTab.ABOUT -> {
                        SoftwareUpdateSettingsCard()
                        SettingsCard {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Application Version", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                Text("Clotube Windows v${DesktopUpdateManager.getCurrentAppVersion()}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            HorizontalDivider(color = Color(0xFF161D2B), modifier = Modifier.padding(vertical = 10.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Decoding Engine", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                val engineText = if (settings.hardwareAcceleration) {
                                    "GPU Hardware Accelerated (D3D11VA / DXVA2)"
                                } else {
                                    "CPU Multi-threaded Software (avcodec)"
                                }
                                Text(
                                    text = engineText,
                                    color = if (settings.hardwareAcceleration) Color(0xFF38BDF8) else Color(0xFFF59E0B),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            HorizontalDivider(color = Color(0xFF161D2B), modifier = Modifier.padding(vertical = 10.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("UI Pipeline", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                Text("Jetpack Compose Multiplatform (Skia D3D)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            HorizontalDivider(color = Color(0xFF161D2B), modifier = Modifier.padding(vertical = 10.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Local Stream Daemon", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                Text("127.0.0.1 (LocalStreamServer)", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // FLOATING TOAST NOTIFICATION
        AnimatedVisibility(
            visible = toastMessage != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xE60E131C),
                border = BorderStroke(1.dp, Brush.verticalGradient(listOf(Color(0xFF222B3D), Color(0xFF131824)))),
                modifier = Modifier.shadow(16.dp, RoundedCornerShape(24.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = toastMessage ?: "",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // CONFIRMATION DIALOG: Clear History
        if (showClearHistoryDialog) {
            DestructiveConfirmationDialog(
                title = "Clear Local Watch History?",
                description = "This will remove all resume progress and timestamps saved locally on this computer. Progress stored on Google Drive will remain untouched.",
                confirmButtonText = "Clear Local History",
                onConfirm = {
                    showClearHistoryDialog = false
                    onClearWatchHistory()
                    showToast("Local watch history cleared")
                },
                onDismiss = { showClearHistoryDialog = false }
            )
        }

        // CONFIRMATION DIALOG: Sign Out
        if (showSignOutDialog) {
            DestructiveConfirmationDialog(
                title = "Sign Out of Clotube?",
                description = "You will be signed out of your Google account and returned to the authentication screen. Cached offline media tokens will be cleared.",
                confirmButtonText = "Sign Out",
                onConfirm = {
                    showSignOutDialog = false
                    onSignOut()
                },
                onDismiss = { showSignOutDialog = false }
            )
        }
    }
}

@Composable
fun StudioToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "switch_thumb_offset"
    )

    val trackColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF2563EB) else Color(0xFF1B2230),
        animationSpec = tween(durationMillis = 200),
        label = "switch_track_color"
    )

    Box(
        modifier = modifier
            .width(44.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(trackColor)
            .border(
                width = 1.dp,
                color = if (checked) Color(0xFF3B82F6) else Color(0xFF263244),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) }
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(18.dp)
                .shadow(elevation = 3.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun <T> StudioSegmentedControl(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    labelProvider: (T) -> String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0D111A),
        border = BorderStroke(1.dp, Color(0xFF1A2230))
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                val interactionSource = remember { MutableInteractionSource() }

                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onItemSelected(item) }
                        .pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(6.dp),
                    color = if (isSelected) Color(0xFF1A2333) else Color.Transparent,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isSelected) Color(0xFF2E3B52) else Color.Transparent
                    )
                ) {
                    Text(
                        text = labelProvider(item),
                        color = if (isSelected) Color.White else Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DestructiveConfirmationDialog(
    title: String,
    description: String,
    confirmButtonText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF0E131E),
            border = BorderStroke(1.dp, Brush.verticalGradient(listOf(Color(0xFF2B1D24), Color(0xFF191217)))),
            modifier = Modifier
                .width(420.dp)
                .shadow(24.dp, RoundedCornerShape(14.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x26E11D48)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFFB7185),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = description,
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Text("Cancel", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onConfirm() }
                            .pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE11D48),
                        border = BorderStroke(1.dp, Color(0xFFF43F5E))
                    ) {
                        Text(
                            text = confirmButtonText,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0E131C),
        border = BorderStroke(1.dp, Brush.verticalGradient(listOf(Color(0xFF222B3D), Color(0xFF131824))))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp)
        }
        StudioToggleSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SoftwareUpdateSettingsCard() {
    val isChecking by DesktopUpdateManager.isCheckingManually.collectAsState()
    val statusMessage by DesktopUpdateManager.statusBannerMessage.collectAsState()
    val currentVersion = remember { DesktopUpdateManager.getCurrentAppVersion() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0E131C),
        border = BorderStroke(1.dp, Color(0xFF1A2230)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Software Updates",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Installed version: v$currentVersion • Source: GitHub Releases",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = { DesktopUpdateManager.checkForUpdatesManually() },
                    enabled = !isChecking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF090A0F),
                        disabledContainerColor = Color(0xFF1E293B),
                        disabledContentColor = Color(0xFF64748B)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
                    modifier = Modifier.pointerHoverIcon(if (!isChecking) PointerIcon.Hand else PointerIcon.Default)
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            color = Color(0xFF60A5FA),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(
                            modifier = Modifier.width(8.dp)
                        )
                        Text(
                            text = "Checking…",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "Check for Updates",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                val isError = statusMessage!!.startsWith("Unable")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isError) Color(0x1AE11D48)
                            else Color(0x1410B981)
                        )
                        .border(
                            1.dp,
                            if (isError) Color(0x40FB7185)
                            else Color(0x3310B981),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = statusMessage!!,
                        color = if (isError) Color(0xFFFDA4AF)
                                else Color(0xFF6EE7B7),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
