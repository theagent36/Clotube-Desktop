package com.clotube.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.clotube.desktop.ui.theme.*

@Composable
fun DesktopHeader(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    userName: String = "Angshuman",
    userEmail: String = "user@gmail.com",
    userPhotoUrl: String? = null,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAccountMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(VoidBackground)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Wide Pill Search Bar
        Box(
            modifier = Modifier
                .width(440.dp)
                .height(42.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(ElevatedCard)
                .border(width = 1.dp, color = BorderSubtle, shape = RoundedCornerShape(21.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search",
                    tint = TextSlateMuted,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Search movies, series, genres...",
                            style = ClotubeTypography.bodyMedium.copy(color = TextMutedSubtle)
                        )
                    }

                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        cursorBrush = SolidColor(AccentSecondary),
                        textStyle = ClotubeTypography.bodyMedium.copy(color = TextWhite),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (searchQuery.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onSearchQueryChange("") }
                            .pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear search",
                            tint = TextSlateMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Right side indicators: Direct stream badge & user avatar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Direct Cloud Streaming Indicator Badge
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0F1B2B))
                    .border(width = 1.dp, color = Color(0x6600E5FF), shape = RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Glowing cyan pulse dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .shadow(4.dp, CircleShape, spotColor = LiveCyan)
                        .clip(CircleShape)
                        .background(LiveCyan)
                )

                Icon(
                    imageVector = Icons.Filled.CloudDone,
                    contentDescription = "Direct Cloud",
                    tint = LiveCyan,
                    modifier = Modifier.size(15.dp)
                )

                Text(
                    text = "Direct Stream • Zero Compression",
                    style = ClotubeTypography.labelSmall.copy(
                        color = LiveCyan,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp
                    )
                )
            }

            // Notifications button
            val notifInteraction = remember { MutableInteractionSource() }
            val isNotifHovered by notifInteraction.collectIsHoveredAsState()

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isNotifHovered) ElevatedCardHover else ElevatedCard)
                    .border(width = 1.dp, color = BorderSubtle, shape = CircleShape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .hoverable(notifInteraction)
                    .clickable(interactionSource = notifInteraction, indication = null) {},
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = "Notifications",
                    tint = if (isNotifHovered) TextWhite else TextSlateMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            // User Avatar Button & Account Dropdown
            Box {
                val avatarInteraction = remember { MutableInteractionSource() }
                val isAvatarHovered by avatarInteraction.collectIsHoveredAsState()

                val initials = remember(userName) {
                    val parts = userName.trim().split(" ")
                    if (parts.size >= 2) {
                        "${parts[0].firstOrNull() ?: 'U'}${parts[1].firstOrNull() ?: 'C'}".uppercase()
                    } else {
                        userName.take(2).uppercase().ifEmpty { "US" }
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isAvatarHovered || showAccountMenu) ElevatedCardHover else ElevatedCard)
                        .border(
                            width = 1.dp,
                            color = if (isAvatarHovered || showAccountMenu) AccentSecondary else BorderSubtle,
                            shape = RoundedCornerShape(20.dp)
                        )
                        .pointerHoverIcon(PointerIcon.Hand)
                        .hoverable(avatarInteraction)
                        .clickable(
                            interactionSource = avatarInteraction,
                            indication = null,
                            onClick = { showAccountMenu = !showAccountMenu }
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!userPhotoUrl.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                        ) {
                            AsyncImage(
                                model = userPhotoUrl,
                                contentDescription = userName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(AccentGradient),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = initials,
                                style = ClotubeTypography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            )
                        }
                    }

                    Text(
                        text = userName,
                        style = ClotubeTypography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            color = if (isAvatarHovered || showAccountMenu) TextWhite else TextSlateMuted
                        )
                    )
                }

                // Top-right Account Dialog / Dropdown
                DropdownMenu(
                    expanded = showAccountMenu,
                    onDismissRequest = { showAccountMenu = false },
                    modifier = Modifier
                        .width(260.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderHighlight, RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // User Profile Summary
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AccentGradient),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = initials,
                                    style = ClotubeTypography.labelLarge.copy(
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = userName,
                                    style = ClotubeTypography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = TextWhite
                                    )
                                )
                                Text(
                                    text = userEmail,
                                    style = ClotubeTypography.bodySmall.copy(color = TextSlateMuted)
                                )
                            }
                        }

                        // Connection Status Pill
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0F1B2B))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(LiveCyan)
                            )
                            Text(
                                text = "Google Drive Connected",
                                style = ClotubeTypography.labelSmall.copy(
                                    color = LiveCyan,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }

                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)

                        // Sign Out Button
                        val signOutInteraction = remember { MutableInteractionSource() }
                        val isSignOutHovered by signOutInteraction.collectIsHoveredAsState()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSignOutHovered) Color(0xFF2A1519) else Color.Transparent)
                                .pointerHoverIcon(PointerIcon.Hand)
                                .hoverable(signOutInteraction)
                                .clickable(
                                    interactionSource = signOutInteraction,
                                    indication = null,
                                    onClick = {
                                        showAccountMenu = false
                                        onSignOut()
                                    }
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Sign Out",
                                tint = if (isSignOutHovered) Color(0xFFF28B82) else TextSlateMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Disconnect & Sign Out",
                                style = ClotubeTypography.labelLarge.copy(
                                    color = if (isSignOutHovered) Color(0xFFF28B82) else TextSlateMuted,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
