package com.clotube.desktop.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Core Dark Cinema Palette
val VoidBackground = Color(0xFF0B0C10)
val SurfaceDark = Color(0xFF12141C)
val ElevatedCard = Color(0xFF1E2230)
val ElevatedCardHover = Color(0xFF262C3E)
val BorderSubtle = Color(0xFF242938)
val BorderHighlight = Color(0xFF333B50)

// Accents
val AccentPrimary = Color(0xFF2F80ED)     // Electric Blue
val AccentSecondary = Color(0xFF00E5FF)   // Teal / Cyan
val AccentSecondaryGlow = Color(0x6600E5FF)
val AccentGradient = Brush.horizontalGradient(
    colors = listOf(AccentPrimary, AccentSecondary)
)

// Text Colors
val TextWhite = Color(0xFFFFFFFF)
val TextSlateMuted = Color(0xFF94A3B8)
val TextMutedSubtle = Color(0xFF64748B)

// Status & Indicators
val LiveCyan = Color(0xFF00E5FF)
val StarGold = Color(0xFFFFB800)
val ProgressTrack = Color(0xFF282E40)

// Scrims & Overlays
val CardScrimGradient = Brush.verticalGradient(
    0.0f to Color.Transparent,
    0.5f to Color(0x660B0C10),
    1.0f to Color(0xF20B0C10)
)

val HeroScrimGradient = Brush.horizontalGradient(
    0.0f to Color(0xF20B0C10),
    0.5f to Color(0xCC0B0C10),
    1.0f to Color(0x1A0B0C10)
)

val HeroBottomScrim = Brush.verticalGradient(
    0.0f to Color.Transparent,
    0.7f to Color(0x990B0C10),
    1.0f to Color(0xFF0B0C10)
)
