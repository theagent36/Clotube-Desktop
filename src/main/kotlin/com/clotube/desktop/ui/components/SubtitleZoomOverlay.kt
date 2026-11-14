package com.clotube.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object SubtitleParser {
    private val srtTimeRegex = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})"""
    )
    private val assTimeRegex = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{2})""")
    private val htmlTagRegex = Regex("""<[^>]+>""")
    private val assTagRegex = Regex("""\{[^}]+\}""")

    fun parseFile(file: File): List<SubtitleCue> {
        if (!file.exists() || !file.isFile) return emptyList()
        return try {
            val raw = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
            when (file.extension.lowercase()) {
                "ass", "ssa" -> parseAss(raw)
                else -> parseSrtOrVtt(raw)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parseSrtOrVtt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val blocks = content.replace("\r\n", "\n").split(Regex("""\n{2,}"""))

        for (block in blocks) {
            val lines = block.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            val timeLineIndex = lines.indexOfFirst { srtTimeRegex.containsMatchIn(it) }
            if (timeLineIndex == -1) continue

            val match = srtTimeRegex.find(lines[timeLineIndex]) ?: continue
            val startMs = toMillis(
                match.groupValues[1].toLong(),
                match.groupValues[2].toLong(),
                match.groupValues[3].toLong(),
                match.groupValues[4].toLong()
            )
            val endMs = toMillis(
                match.groupValues[5].toLong(),
                match.groupValues[6].toLong(),
                match.groupValues[7].toLong(),
                match.groupValues[8].toLong()
            )

            val dialogue = lines.drop(timeLineIndex + 1)
                .joinToString("\n")
                .replace(htmlTagRegex, "")
                .replace(assTagRegex, "")
                .trim()

            if (dialogue.isNotEmpty() && endMs > startMs) {
                cues.add(SubtitleCue(startMs, endMs, dialogue))
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseAss(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val payload = line.substringAfter(":").trim()
            val parts = payload.split(",", limit = 10)
            if (parts.size < 10) continue

            val startMs = parseAssTimestamp(parts[1].trim()) ?: continue
            val endMs = parseAssTimestamp(parts[2].trim()) ?: continue
            val text = parts[9]
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
                .replace(assTagRegex, "")
                .replace(htmlTagRegex, "")
                .trim()

            if (text.isNotEmpty() && endMs > startMs) {
                cues.add(SubtitleCue(startMs, endMs, text))
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseAssTimestamp(ts: String): Long? {
        val m = assTimeRegex.find(ts) ?: return null
        val h = m.groupValues[1].toLong()
        val min = m.groupValues[2].toLong()
        val s = m.groupValues[3].toLong()
        val cs = m.groupValues[4].toLong()
        return (h * 3600_000L) + (min * 60_000L) + (s * 1000L) + (cs * 10L)
    }

    private fun toMillis(h: Long, m: Long, s: Long, ms: Long): Long =
        (h * 3600_000L) + (m * 60_000L) + (s * 1000L) + ms
}

fun Modifier.onCtrlScrollSubtitleScale(
    currentPercent: Int,
    onScaleStep: (newPercent: Int) -> Unit
): Modifier = composed {
    val latestPercent by rememberUpdatedState(currentPercent)
    val latestOnScaleStep by rememberUpdatedState(onScaleStep)

    this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                    val deltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    if (deltaY != 0f) {
                        event.changes.forEach { it.consume() }
                        // Scroll up (deltaY < 0) -> +5% | Scroll down (deltaY > 0) -> -5%
                        val step = if (deltaY < 0f) 5 else -5
                        val current = latestPercent
                        val nextPercent = (current + step).coerceIn(25, 300)
                        if (nextPercent != current) {
                            latestOnScaleStep(nextPercent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SubtitleScaleOsdBadge(
    scalePercent: Int,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.92f),
        exit = fadeOut() + scaleOut(targetScale = 0.95f),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xCC0B0E16),
            border = BorderStroke(1.dp, Color(0x6600E5FF)),
            shadowElevation = 10.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Subtitles,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Subtitle Size: $scalePercent%",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SubtitleCanvasOverlay(
    cues: List<SubtitleCue>,
    currentTimeMs: Long,
    scalePercent: Int,
    modifier: Modifier = Modifier
) {
    if (cues.isEmpty()) return

    val activeText = remember(cues, currentTimeMs) {
        cues.lastOrNull { currentTimeMs in it.startMs..it.endMs }?.text
    } ?: return

    val ratio = scalePercent.coerceIn(25, 300) / 100f
    val scaledFontSp = (28f * ratio).sp
    val scaledLineHeightSp = (35f * ratio).sp

    Box(
        modifier = modifier
            .widthIn(max = 1040.dp)
            .background(Color(0xA605070B), RoundedCornerShape(6.dp))
            .padding(horizontal = 18.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = activeText,
            color = Color.White,
            fontSize = scaledFontSp,
            lineHeight = scaledLineHeightSp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black,
                    offset = Offset(1.5f, 1.5f),
                    blurRadius = 4f
                )
            )
        )
    }
}
