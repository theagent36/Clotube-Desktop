package com.clotube.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = TextWhite,
    secondary = AccentSecondary,
    onSecondary = VoidBackground,
    background = VoidBackground,
    onBackground = TextWhite,
    surface = SurfaceDark,
    onSurface = TextWhite,
    surfaceVariant = ElevatedCard,
    onSurfaceVariant = TextSlateMuted,
    outline = BorderSubtle,
    outlineVariant = BorderHighlight
)

@Composable
fun ClotubeTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = ClotubeTypography,
        content = content
    )
}
