package com.clotube.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { activeTab = tab }
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
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = if (isSelected) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = tab.label,
                                color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
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
                                                    listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
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
                                Button(
                                    onClick = { showSignOutDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF33161C)),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFF7F1D1D)),
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Text(
                                        text = "Sign Out",
                                        color = Color(0xFFF87171),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
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
                            HorizontalDivider(color = Color(0xFF1E2638), modifier = Modifier.padding(vertical = 12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("Delta Library Revalidation", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Check Google Drive for newly added movies and series in the background.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        if (!isRescanning) {
                                            isRescanning = true
                                            onRescanLibrary()
                                            showToast("Google Drive delta scan initiated")
                                            scope.launch {
                                                delay(2500L)
                                                isRescanning = false
                                            }
                                        }
                                    },
                                    enabled = !isRescanning,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFF334155)),
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier
                                            .size(15.dp)
                                            .then(if (isRescanning) Modifier.rotate(rescanAngle.value) else Modifier)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isRescanning) "Scanning Drive..." else "Rescan Drive Now",
                                        color = Color.White,
                                        fontSize = 11.sp
                                    )
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
                            HorizontalDivider(color = Color(0xFF1E2638), modifier = Modifier.padding(vertical = 12.dp))
                            SettingsToggleRow(
                                title = "Auto-Play Next Episode",
                                subtitle = "Automatically start the next episode when the current one finishes.",
                                checked = settings.autoPlayNextEpisode,
                                onCheckedChange = {
                                    DesktopAppSettings.update { copy(autoPlayNextEpisode = it) }
                                    showToast(if (it) "Auto-play enabled" else "Auto-play disabled")
                                }
                            )
                            HorizontalDivider(color = Color(0xFF1E2638), modifier = Modifier.padding(vertical = 12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Stream Pre-Buffer Depth", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Higher buffer prevents stutter on high-bitrate 4K files.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(1500 to "1.5s (Fast)", 3000 to "3.0s (Normal)", 5000 to "5.0s (Smooth)").forEach { (ms, label) ->
                                        val isSelected = settings.bufferDepthMs == ms
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isSelected) Color(0xFF1B2E4B) else Color(0xFF131824),
                                            border = BorderStroke(1.dp, if (isSelected) Color(0xFF00E5FF) else Color(0xFF263346)),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable {
                                                    DesktopAppSettings.update { copy(bufferDepthMs = ms) }
                                                    showToast("Buffer depth set to ${label.substringBefore(" ")}")
                                                }
                                                .pointerHoverIcon(PointerIcon.Hand)
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
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
                            HorizontalDivider(color = Color(0xFF1E2638), modifier = Modifier.padding(vertical = 12.dp))
                            SettingsToggleRow(
                                title = "Background Eco Mode",
                                subtitle = "Freeze Compose render cycles and drop GPU usage when the app window is minimized.",
                                checked = settings.backgroundEcoMode,
                                onCheckedChange = {
                                    DesktopAppSettings.update { copy(backgroundEcoMode = it) }
                                }
                            )
                            HorizontalDivider(color = Color(0xFF1E2638), modifier = Modifier.padding(vertical = 12.dp))
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
                            HorizontalDivider(color = Color(0xFF1E2638), modifier = Modifier.padding(vertical = 12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Default Subtitles State", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Initial subtitle track selection when starting playback.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("Off", "English", "Auto-Detect").forEach { opt ->
                                        val isSelected = settings.defaultSubtitleState == opt
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isSelected) Color(0xFF1B2E4B) else Color(0xFF131824),
                                            border = BorderStroke(1.dp, if (isSelected) Color(0xFF00E5FF) else Color(0xFF263346)),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable {
                                                    DesktopAppSettings.update { copy(defaultSubtitleState = opt) }
                                                    showToast("Default subtitles: $opt")
                                                }
                                                .pointerHoverIcon(PointerIcon.Hand)
                                        ) {
                                            Text(
                                                text = opt,
                                                color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
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
                                    Text("Local poster thumbnails and TV series episode data: $mb", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isCacheClearing = true
                                            val bytesBefore = cacheSizeBytes
                                            DesktopAppSettings.clearThumbnailCache(userEmail)
                                            cacheSizeBytes = DesktopAppSettings.calculateCacheSizeBytes(userEmail)
                                            val freedMb = "%.2f MB".format((bytesBefore - cacheSizeBytes).coerceAtLeast(0L) / (1024.0 * 1024.0))
                                            isCacheClearing = false
                                            showToast("Thumbnail cache cleared ($freedMb freed)")
                                        }
                                    },
                                    enabled = !isCacheClearing && cacheSizeBytes > 0L,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFF334155)),
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Text(if (isCacheClearing) "Purging..." else "Clear Image Cache", color = Color.White, fontSize = 11.sp)
                                }
                            }
                            HorizontalDivider(color = Color(0xFF1E2638), modifier = Modifier.padding(vertical = 12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("Local Playback History", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Erase local resume marks from this device without affecting Google Drive.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { showClearHistoryDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF33161C)),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFF7F1D1D)),
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Text("Clear History", color = Color(0xFFF87171), fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    SettingsTab.ABOUT -> {
                        SettingsCard {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Application Version", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                Text("Clotube Windows v1.0.4", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            HorizontalDivider(color = Color(0xFF1E2638), modifier = Modifier.padding(vertical = 10.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Decoding Engine", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                val engineText = if (settings.hardwareAcceleration) {
                                    "GPU Hardware Accelerated (D3D11VA / DXVA2)"
                                } else {
                                    "CPU Multi-threaded Software (avcodec)"
                                }
                                Text(
                                    text = engineText,
                                    color = if (settings.hardwareAcceleration) Color(0xFF00E5FF) else Color(0xFFF59E0B),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            HorizontalDivider(color = Color(0xFF1E2638), modifier = Modifier.padding(vertical = 10.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("UI Pipeline", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                Text("Jetpack Compose Multiplatform (Skia D3D)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            HorizontalDivider(color = Color(0xFF1E2638), modifier = Modifier.padding(vertical = 10.dp))
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
                color = Color(0xE60F172A),
                border = BorderStroke(1.dp, Color(0xFF00E5FF)),
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
                        tint = Color(0xFF00E5FF),
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
            border = BorderStroke(1.dp, Color(0xFF2B1D24)),
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
                            .background(Color(0xFF33161C)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFF87171),
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
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF991B1B)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Text(confirmButtonText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
        color = Color(0xFF0D111A),
        border = BorderStroke(1.dp, Color(0xFF1A2232))
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF00E5FF),
                uncheckedThumbColor = Color(0xFF64748B),
                uncheckedTrackColor = Color(0xFF1E293B)
            ),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
        )
    }
}
