package com.clotube.desktop.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.ui.components.ClotubeGlyphIcon
import com.clotube.desktop.ui.theme.*

enum class NavDestination(val label: String, val icon: ImageVector, val activeIcon: ImageVector) {
    HOME("Home", Icons.Outlined.Home, Icons.Filled.Home),
    MOVIES("Movies", Icons.Outlined.Movie, Icons.Filled.Movie),
    SERIES("Series", Icons.Outlined.Tv, Icons.Filled.Tv),
    RECENTS("Recents", Icons.Outlined.History, Icons.Filled.History),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Filled.Settings)
}

@Composable
fun Sidebar(
    currentDestination: NavDestination,
    onDestinationSelected: (NavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    var isCollapsed by remember { mutableStateOf(false) }
    val sidebarWidth by animateDpAsState(
        targetValue = if (isCollapsed) 76.dp else 224.dp,
        animationSpec = tween(durationMillis = 220),
        label = "SidebarWidth"
    )

    Column(
        modifier = modifier
            .width(sidebarWidth)
            .fillMaxHeight()
            .background(SurfaceDark)
            .border(width = 1.dp, color = BorderSubtle)
            .padding(vertical = 20.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top section: Logo & Nav items
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Brand header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (isCollapsed) Arrangement.Center else Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ClotubeGlyphIcon(size = 32.dp)

                    if (!isCollapsed) {
                        Text(
                            text = "Clotube",
                            style = ClotubeTypography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.5).sp,
                                color = TextWhite
                            )
                        )
                    }
                }

                // Collapse rail toggle button
                val toggleInteraction = remember { MutableInteractionSource() }
                val isToggleHovered by toggleInteraction.collectIsHoveredAsState()

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isToggleHovered) ElevatedCardHover else Color.Transparent)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .hoverable(toggleInteraction)
                        .clickable(
                            interactionSource = toggleInteraction,
                            indication = null
                        ) { isCollapsed = !isCollapsed },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isCollapsed) Icons.Filled.ChevronRight else Icons.AutoMirrored.Filled.MenuOpen,
                        contentDescription = "Toggle Sidebar",
                        tint = if (isToggleHovered) AccentSecondary else TextSlateMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Main navigation items
            val mainItems = listOf(
                NavDestination.HOME,
                NavDestination.MOVIES,
                NavDestination.SERIES,
                NavDestination.RECENTS
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                mainItems.forEach { destination ->
                    SidebarItem(
                        destination = destination,
                        isSelected = currentDestination == destination,
                        isCollapsed = isCollapsed,
                        onClick = { onDestinationSelected(destination) }
                    )
                }
            }
        }

        // Bottom section: Settings & Version
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SidebarItem(
                destination = NavDestination.SETTINGS,
                isSelected = currentDestination == NavDestination.SETTINGS,
                isCollapsed = isCollapsed,
                onClick = { onDestinationSelected(NavDestination.SETTINGS) }
            )

            if (!isCollapsed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Windows x64",
                        style = ClotubeTypography.labelSmall
                    )
                    Text(
                        text = "v1.0.0",
                        style = ClotubeTypography.labelSmall.copy(color = AccentSecondary)
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarItem(
    destination: NavDestination,
    isSelected: Boolean,
    isCollapsed: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor = when {
        isSelected -> Color(0xFF1E283E)
        isHovered -> Color(0xFF181B26)
        else -> Color.Transparent
    }

    val contentColor = when {
        isSelected -> AccentSecondary
        isHovered -> TextWhite
        else -> TextSlateMuted
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = if (isSelected) BorderHighlight else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (isCollapsed) 0.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isCollapsed) Arrangement.Center else Arrangement.Start
        ) {
            Icon(
                imageVector = if (isSelected) destination.activeIcon else destination.icon,
                contentDescription = destination.label,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )

            if (!isCollapsed) {
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = destination.label,
                    style = ClotubeTypography.titleMedium.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = contentColor
                    )
                )

                if (isSelected) {
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(AccentSecondary)
                    )
                }
            }
        }
    }
}
