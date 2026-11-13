package com.clotube.desktop.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.update.DesktopUpdateManager
import com.clotube.desktop.update.UpdateUiState
import java.util.Locale

@Composable
fun DesktopUpdateOverlay(
    modifier: Modifier = Modifier
) {
    val uiState by DesktopUpdateManager.uiState.collectAsState()
    if (uiState is UpdateUiState.Hidden) return

    val modalBorderBrush = remember {
        Brush.verticalGradient(
            colors = listOf(Color(0x38FFFFFF), Color(0x14FFFFFF))
        )
    }

    // Scrim backdrop blocking background clicks cleanly inside the Compose canvas
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xB805070B))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { /* Consume background clicks while update dialog is active */ }
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0E131C),
            border = BorderStroke(1.dp, modalBorderBrush),
            shadowElevation = 28.dp,
            modifier = Modifier
                .width(500.dp)
                .wrapContentHeight()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* Prevent click propagation to scrim */ }
                )
        ) {
            AnimatedContent(
                targetState = uiState,
                transitionSpec = {
                    fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(140))
                },
                contentKey = { state ->
                    when (state) {
                        is UpdateUiState.Hidden -> "hidden"
                        is UpdateUiState.Available -> "available_${state.showDownloadOptions}"
                        is UpdateUiState.Downloading -> "downloading"
                        is UpdateUiState.ReadyToInstall -> "ready_${state.scheduledForNextBoot}"
                        is UpdateUiState.Installing -> "installing"
                        is UpdateUiState.Error -> "error"
                    }
                },
                label = "update_modal_transition"
            ) { state ->
                when (state) {
                    is UpdateUiState.Hidden -> Unit
                    is UpdateUiState.Available -> UpdateAvailableContent(state)
                    is UpdateUiState.Downloading -> UpdateDownloadingContent(state)
                    is UpdateUiState.ReadyToInstall -> UpdateReadyToInstallContent(state)
                    is UpdateUiState.Installing -> UpdateInstallingContent(state)
                    is UpdateUiState.Error -> UpdateErrorContent(state)
                }
            }
        }
    }
}

@Composable
private fun UpdateAvailableContent(state: UpdateUiState.Available) {
    val release = state.release
    val notesScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x1F60A5FA))
                        .border(1.dp, Color(0x4060A5FA), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdateAlt,
                        contentDescription = "Update Available",
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "SOFTWARE UPDATE",
                        color = Color(0xFF60A5FA),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp
                    )
                    Text(
                        text = "Update Available",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.3).sp
                    )
                }
            }

            // Close / Dismiss Icon (acts as temporary close without skipping)
            val closeInteraction = remember { MutableInteractionSource() }
            val isCloseHovered by closeInteraction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (isCloseHovered) Color(0xFF1A2230) else Color.Transparent)
                    .clickable(
                        interactionSource = closeInteraction,
                        indication = null,
                        onClick = { DesktopUpdateManager.dismissDialog() }
                    )
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = if (isCloseHovered) Color.White else Color(0xFF64748B),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Version Transition Pill Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF090C12))
                .border(1.dp, Color(0xFF1A2230), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "v${state.currentVersion}",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
                Icon(
                    imageVector = Icons.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = Color(0xFF475569),
                    modifier = Modifier.size(14.dp)
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0x2460A5FA),
                    border = BorderStroke(1.dp, Color(0x4760A5FA))
                ) {
                    Text(
                        text = "v${release.version}",
                        color = Color(0xFF93C5FD),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            if (release.msiSizeBytes > 0L) {
                Text(
                    text = formatBytes(release.msiSizeBytes),
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Scrollable Release Notes Container
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "WHAT'S NEW IN ${release.tagName.uppercase(Locale.US)}",
                color = Color(0xFF64748B),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 90.dp, max = 185.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF090C12))
                    .border(1.dp, Color(0xFF1A2230), RoundedCornerShape(10.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = release.releaseNotes.ifBlank { "Performance improvements and stability updates." },
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(notesScroll)
                )
            }
        }

        // Action Bar:
        // Stage 1: [Skip this version]   [Remind me Later] [Download]
        // Stage 2 (after clicking Download): [← Back]   [Download] [Download & Install]
        if (!state.showDownloadOptions) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Option 1: Skip this version
                val skipInteraction = remember { MutableInteractionSource() }
                val isSkipHovered by skipInteraction.collectIsHoveredAsState()
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSkipHovered) Color(0xFF141A26) else Color.Transparent)
                        .clickable(
                            interactionSource = skipInteraction,
                            indication = null,
                            onClick = { DesktopUpdateManager.onSkipThisVersion(release) }
                        )
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 10.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = "Skip this version",
                        color = if (isSkipHovered) Color(0xFFCBD5E1) else Color(0xFF64748B),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Option 2: Remind me Later (24h)
                    SecondaryObsidianButton(
                        text = "Remind me Later",
                        onClick = { DesktopUpdateManager.onRemindMeLater(release) }
                    )

                    // Option 3: Download (reveals Download vs Download & Install)
                    PrimaryStudioWhiteButton(
                        text = "Download",
                        icon = Icons.Rounded.Download,
                        onClick = { DesktopUpdateManager.onExpandDownloadOptions() }
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Choose how you want to download and apply v${release.version}:",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Remind me later remains accessible
                    val laterInteraction = remember { MutableInteractionSource() }
                    val isLaterHovered by laterInteraction.collectIsHoveredAsState()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isLaterHovered) Color(0xFF141A26) else Color.Transparent)
                            .clickable(
                                interactionSource = laterInteraction,
                                indication = null,
                                onClick = { DesktopUpdateManager.onRemindMeLater(release) }
                            )
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = "Remind me Later",
                            color = if (isLaterHovered) Color(0xFFCBD5E1) else Color(0xFF64748B),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Sub-option 3.1: Download only (then prompt Install Now vs Next Boot)
                        SecondaryObsidianButton(
                            text = "Download",
                            onClick = {
                                DesktopUpdateManager.startDownload(
                                    release = release,
                                    installImmediately = false
                                )
                            }
                        )

                        // Sub-option 3.2: Download & Install automatically
                        PrimaryStudioWhiteButton(
                            text = "Download & Install",
                            icon = Icons.Rounded.Bolt,
                            onClick = {
                                DesktopUpdateManager.startDownload(
                                    release = release,
                                    installImmediately = true
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateDownloadingContent(state: UpdateUiState.Downloading) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.progressFraction.coerceIn(0f, 1f),
        animationSpec = tween(140),
        label = "update_download_progress"
    )
    val percentText = "${(animatedProgress * 100).toInt()}%"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (state.installImmediatelyAfter) "DOWNLOADING & PREPARING INSTALL" else "DOWNLOADING UPDATE",
                color = Color(0xFF60A5FA),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp
            )
            Text(
                text = "Downloading Clotube v${state.release.version}",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = if (state.installImmediatelyAfter) {
                    "Clotube will install the update and relaunch automatically once finished."
                } else {
                    "You will be prompted to install now or on your next Windows boot once downloaded."
                },
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )
        }

        // Progress Bar + Telemetry
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (state.totalBytes > 0L) {
                        "${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)}"
                    } else {
                        formatBytes(state.bytesDownloaded)
                    },
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = percentText,
                    color = Color(0xFF93C5FD),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF161E2E))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF1D4ED8), Color(0xFF60A5FA))
                            )
                        )
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            SecondaryObsidianButton(
                text = "Cancel",
                onClick = { DesktopUpdateManager.cancelDownload() }
            )
        }
    }
}

@Composable
private fun UpdateReadyToInstallContent(state: UpdateUiState.ReadyToInstall) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x1F10B981))
                    .border(1.dp, Color(0x4010B981), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Ready to install",
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = if (state.scheduledForNextBoot) "SCHEDULED FOR NEXT BOOT" else "DOWNLOAD COMPLETE",
                    color = Color(0xFF6EE7B7),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
                Text(
                    text = if (state.scheduledForNextBoot) {
                        "Update Scheduled for Next Boot"
                    } else {
                        "Clotube v${state.version} Ready to Install"
                    },
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Text(
            text = if (state.scheduledForNextBoot) {
                "Clotube v${state.version} will automatically install the next time you boot and sign into Windows. Your profiles, Drive credentials, and watch history will remain intact."
            } else {
                "The installer (${state.installerFile.name}) has been downloaded. Install now to update and relaunch immediately, or schedule installation for your next Windows boot."
            },
            color = Color(0xFFCBD5E1),
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.scheduledForNextBoot) {
                SecondaryObsidianButton(
                    text = "Install Now Instead",
                    onClick = {
                        DesktopUpdateManager.installNowAndRelaunch(
                            installerFile = state.installerFile,
                            version = state.version
                        )
                    }
                )
                Spacer(modifier = Modifier.width(10.dp))
                PrimaryStudioWhiteButton(
                    text = "Done",
                    onClick = { DesktopUpdateManager.dismissDialog() }
                )
            } else {
                SecondaryObsidianButton(
                    text = "Install on Next Boot",
                    onClick = {
                        DesktopUpdateManager.scheduleInstallOnNextBoot(
                            installerFile = state.installerFile,
                            version = state.version
                        )
                    }
                )
                Spacer(modifier = Modifier.width(10.dp))
                PrimaryStudioWhiteButton(
                    text = "Install Now",
                    icon = Icons.Rounded.RestartAlt,
                    onClick = {
                        DesktopUpdateManager.installNowAndRelaunch(
                            installerFile = state.installerFile,
                            version = state.version
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun UpdateInstallingContent(state: UpdateUiState.Installing) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(
            color = Color(0xFF60A5FA),
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(28.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Installing Clotube v${state.version}…",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Clotube will close briefly and relaunch automatically once the update completes.",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun UpdateErrorContent(state: UpdateUiState.Error) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x1AE11D48))
                    .border(1.dp, Color(0x40FB7185), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = "Update Error",
                    tint = Color(0xFFFB7185),
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "UPDATE ERROR",
                    color = Color(0xFFFB7185),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "Could Not Complete Update",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            text = state.message,
            color = Color(0xFFCBD5E1),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SecondaryObsidianButton(
                text = "Close",
                onClick = { DesktopUpdateManager.dismissDialog() }
            )
            state.retryRelease?.let { release ->
                Spacer(modifier = Modifier.width(10.dp))
                PrimaryStudioWhiteButton(
                    text = "Retry",
                    icon = Icons.Rounded.Refresh,
                    onClick = {
                        DesktopUpdateManager.startDownload(
                            release = release,
                            installImmediately = false
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun PrimaryStudioWhiteButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color(0xFF090A0F)
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
        modifier = Modifier
            .height(38.dp)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF090A0F),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = Color(0xFF090A0F),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SecondaryObsidianButton(
    text: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()

    Surface(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .pointerHoverIcon(PointerIcon.Hand),
        shape = RoundedCornerShape(8.dp),
        color = if (isHovered) Color(0xFF1A2233) else Color(0xFF141A26),
        border = BorderStroke(1.dp, if (isHovered) Color(0xFF334155) else Color(0xFF222B3D))
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (isHovered) Color.White else Color(0xFFCBD5E1),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

