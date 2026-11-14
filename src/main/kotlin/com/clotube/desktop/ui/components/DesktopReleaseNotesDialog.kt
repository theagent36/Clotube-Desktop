package com.clotube.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.update.DesktopUpdateManager

data class ReleaseSection(
    val heading: String?,
    val bullets: List<String> = emptyList(),
    val paragraphs: List<String> = emptyList()
)

fun cleanMarkdownBody(raw: String): String {
    var text = raw
    val installIdx = text.indexOf("### Installation Instructions", ignoreCase = true)
    if (installIdx >= 0) {
        text = text.substring(0, installIdx)
    }
    val lines = text.lines()
    val dividerIdx = lines.indexOfFirst { line ->
        val trimmed = line.trim()
        trimmed == "---" || trimmed == "___" || trimmed == "***"
    }
    if (dividerIdx >= 0) {
        text = lines.subList(0, dividerIdx).joinToString("\n")
    }
    return text.trim()
}

fun parseReleaseSections(body: String): List<ReleaseSection> {
    val cleaned = cleanMarkdownBody(body)
    val lines = cleaned.lines()
    val sections = mutableListOf<ReleaseSection>()

    var currentHeading: String? = null
    val currentBullets = mutableListOf<String>()
    val currentParagraphs = mutableListOf<String>()

    fun flush() {
        if (currentHeading != null || currentBullets.isNotEmpty() || currentParagraphs.isNotEmpty()) {
            sections.add(
                ReleaseSection(
                    heading = currentHeading,
                    bullets = currentBullets.toList(),
                    paragraphs = currentParagraphs.toList()
                )
            )
            currentBullets.clear()
            currentParagraphs.clear()
        }
    }

    for (rawLine in lines) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue

        // Ignore top-level # or ## headers (e.g. "## What's New in v2.0.3")
        if (trimmed.startsWith("# ") || trimmed.startsWith("## ")) {
            continue
        }

        if (trimmed.startsWith("### ")) {
            flush()
            currentHeading = trimmed.removePrefix("###").trim()
            continue
        }

        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            val bulletText = trimmed.substring(2).trim()
            if (bulletText.isNotEmpty()) {
                currentBullets.add(bulletText)
            }
        } else {
            currentParagraphs.add(trimmed)
        }
    }
    flush()
    return sections
}

fun buildAnnotatedMarkdownString(rawText: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val len = rawText.length
        while (i < len) {
            if (i + 1 < len && rawText[i] == '*' && rawText[i + 1] == '*') {
                val end = rawText.indexOf("**", i + 2)
                if (end != -1) {
                    val boldContent = rawText.substring(i + 2, end)
                    withStyle(
                        SpanStyle(
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    ) {
                        append(boldContent)
                    }
                    i = end + 2
                    continue
                }
            } else if (rawText[i] == '`') {
                val end = rawText.indexOf('`', i + 1)
                if (end != -1) {
                    val codeContent = rawText.substring(i + 1, end)
                    withStyle(
                        SpanStyle(
                            color = Color(0xFF93C5FD),
                            fontFamily = FontFamily.Monospace,
                            background = Color(0x1F60A5FA)
                        )
                    ) {
                        append(codeContent)
                    }
                    i = end + 1
                    continue
                }
            }
            append(rawText[i])
            i++
        }
    }
}

@Composable
fun DesktopReleaseNotesOverlay(
    modifier: Modifier = Modifier
) {
    val state by DesktopUpdateManager.releaseNotesState.collectAsState()
    if (!state.isVisible) return

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val modalBorderBrush = remember {
        Brush.verticalGradient(
            colors = listOf(Color(0x38FFFFFF), Color(0x14FFFFFF))
        )
    }

    val selectedRelease = remember(state.releases, state.selectedVersion) {
        state.releases.firstOrNull { it.version == state.selectedVersion }
            ?: state.releases.firstOrNull()
    }

    val sections = remember(selectedRelease?.body) {
        parseReleaseSections(selectedRelease?.body.orEmpty())
    }

    // Fullscreen scrim blocking background interactions and supporting click / Escape dismiss
    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                    DesktopUpdateManager.dismissReleaseNotes()
                    true
                } else {
                    false
                }
            }
            .background(Color(0xC205070B))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { DesktopUpdateManager.dismissReleaseNotes() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0E131C),
            border = BorderStroke(1.dp, modalBorderBrush),
            shadowElevation = 32.dp,
            modifier = Modifier
                .width(620.dp)
                .heightIn(max = 640.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* Consume clicks within modal */ }
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
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
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFF60A5FA),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = if (state.isAutomaticPostUpdate) "UPDATED TO LATEST VERSION" else "RELEASE NOTES",
                                color = Color(0xFF60A5FA),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "What's New in Clotube",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    val closeInteraction = remember { MutableInteractionSource() }
                    val isCloseHovered by closeInteraction.collectIsHoveredAsState()

                    Surface(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = closeInteraction,
                                indication = null,
                                onClick = { DesktopUpdateManager.dismissReleaseNotes() }
                            )
                            .pointerHoverIcon(PointerIcon.Hand),
                        shape = CircleShape,
                        color = if (isCloseHovered) Color(0xFF1E2638) else Color(0xFF141A26),
                        border = BorderStroke(1.dp, if (isCloseHovered) Color(0xFF334155) else Color(0xFF222B3D))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close",
                                tint = if (isCloseHovered) Color.White else Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Version Selector Strip (if multiple versions are available)
                if (state.releases.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (release in state.releases) {
                            val isSelected = release.version == (selectedRelease?.version ?: state.selectedVersion)
                            val isInstalled = release.version == state.currentAppVersion

                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable {
                                        DesktopUpdateManager.selectReleaseNotesVersion(release.version)
                                    }
                                    .pointerHoverIcon(PointerIcon.Hand),
                                shape = RoundedCornerShape(20.dp),
                                color = if (isSelected) Color(0x2460A5FA) else Color(0xFF090C12),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0x6660A5FA) else Color(0xFF1A2230)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "v${release.version}",
                                        color = if (isSelected) Color(0xFF93C5FD) else Color(0xFF94A3B8),
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp
                                    )
                                    if (isInstalled) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isSelected) Color(0x3360A5FA) else Color(0xFF1E293B),
                                            border = BorderStroke(
                                                0.5.dp,
                                                if (isSelected) Color(0x6660A5FA) else Color(0xFF334155)
                                            )
                                        ) {
                                            Text(
                                                text = "INSTALLED",
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color(0xFF93C5FD) else Color(0xFF64748B),
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Release Title & Tag Banner
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = selectedRelease?.title.orEmpty().ifBlank {
                            "Release v${selectedRelease?.version ?: state.selectedVersion}"
                        },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0x2460A5FA),
                        border = BorderStroke(1.dp, Color(0x4760A5FA))
                    ) {
                        Text(
                            text = "v${selectedRelease?.version ?: state.selectedVersion}",
                            color = Color(0xFF93C5FD),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable Structured Markdown Body
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (sections.isEmpty()) {
                        if (state.isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFF60A5FA),
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No release details available.",
                                    color = Color(0xFF64748B),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        for (section in sections) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF090C12),
                                border = BorderStroke(1.dp, Color(0xFF1A2230)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (!section.heading.isNullOrBlank()) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(width = 3.dp, height = 14.dp)
                                                    .clip(RoundedCornerShape(1.5.dp))
                                                    .background(Color(0xFF60A5FA))
                                            )
                                            Text(
                                                text = section.heading,
                                                color = Color.White,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    for (para in section.paragraphs) {
                                        Text(
                                            text = buildAnnotatedMarkdownString(para),
                                            color = Color(0xFFCBD5E1),
                                            fontSize = 12.5.sp,
                                            lineHeight = 19.sp,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }

                                    for (bullet in section.bullets) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(top = 7.dp)
                                                    .size(5.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF60A5FA))
                                            )
                                            Text(
                                                text = buildAnnotatedMarkdownString(bullet),
                                                color = Color(0xFFCBD5E1),
                                                fontSize = 12.5.sp,
                                                lineHeight = 19.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Footer Bar
                HorizontalDivider(color = Color(0xFF161D2B))
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Clotube Windows v${state.currentAppVersion}",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )

                    Button(
                        onClick = { DesktopUpdateManager.dismissReleaseNotes() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF090A0F)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 9.dp),
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Text(
                            text = if (state.isAutomaticPostUpdate) "Got It" else "Close",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

