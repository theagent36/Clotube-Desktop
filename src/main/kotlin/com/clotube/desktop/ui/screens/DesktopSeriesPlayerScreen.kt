package com.clotube.desktop.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.media.DirectMemoryVideoPlayer
import com.clotube.desktop.media.LocalStreamServer
import kotlinx.coroutines.delay
import java.awt.Cursor
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import kotlin.math.min
import kotlin.math.roundToInt

private enum class SeriesPlayerPopupType {
    NONE, SUBTITLES, AUDIO, SPEED
}

private enum class SeriesSeekFeedbackDirection {
    REWIND, FORWARD
}

@Composable
fun DesktopSeriesPlayerScreen(
    player: DirectMemoryVideoPlayer,
    seriesTitle: String,
    episodeTitle: String = "",
    seasonNumber: Int = 1,
    episodeNumber: Int = 1,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onNavigateBack: () -> Unit,
    onNextEpisode: (() -> Unit)? = null,
    onPreviousEpisode: (() -> Unit)? = null,
    hasNextEpisode: Boolean = false,
    hasPreviousEpisode: Boolean = false,
    modifier: Modifier = Modifier
) {
    DesktopSeriesPlayerContent(
        player = player,
        seriesTitle = seriesTitle,
        episodeTitle = episodeTitle,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        isFullscreen = isFullscreen,
        onToggleFullscreen = onToggleFullscreen,
        onNavigateBack = onNavigateBack,
        onNextEpisode = onNextEpisode,
        onPreviousEpisode = onPreviousEpisode,
        hasNextEpisode = hasNextEpisode,
        hasPreviousEpisode = hasPreviousEpisode,
        modifier = modifier
    )
}

@Composable
fun DesktopSeriesPlayerScreen(
    mediaUrl: String,
    seriesTitle: String,
    episodeTitle: String = "",
    seasonNumber: Int = 1,
    episodeNumber: Int = 1,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onNavigateBack: () -> Unit,
    onNextEpisode: (() -> Unit)? = null,
    onPreviousEpisode: (() -> Unit)? = null,
    hasNextEpisode: Boolean = false,
    hasPreviousEpisode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val player = remember(mediaUrl) { DirectMemoryVideoPlayer(mediaUrl) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    DesktopSeriesPlayerContent(
        player = player,
        seriesTitle = seriesTitle,
        episodeTitle = episodeTitle,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        isFullscreen = isFullscreen,
        onToggleFullscreen = onToggleFullscreen,
        onNavigateBack = onNavigateBack,
        onNextEpisode = onNextEpisode,
        onPreviousEpisode = onPreviousEpisode,
        hasNextEpisode = hasNextEpisode,
        hasPreviousEpisode = hasPreviousEpisode,
        modifier = modifier
    )
}

@Composable
fun DesktopSeriesPlayerScreen(
    fileId: String,
    accessToken: String,
    seriesTitle: String,
    episodeTitle: String = "",
    seasonNumber: Int = 1,
    episodeNumber: Int = 1,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onNavigateBack: () -> Unit,
    onNextEpisode: (() -> Unit)? = null,
    onPreviousEpisode: (() -> Unit)? = null,
    hasNextEpisode: Boolean = false,
    hasPreviousEpisode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val streamUrl = remember(fileId, accessToken) {
        LocalStreamServer.getStreamUrl(fileId, accessToken)
    }

    DesktopSeriesPlayerScreen(
        mediaUrl = streamUrl,
        seriesTitle = seriesTitle,
        episodeTitle = episodeTitle,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        isFullscreen = isFullscreen,
        onToggleFullscreen = onToggleFullscreen,
        onNavigateBack = onNavigateBack,
        onNextEpisode = onNextEpisode,
        onPreviousEpisode = onPreviousEpisode,
        hasNextEpisode = hasNextEpisode,
        hasPreviousEpisode = hasPreviousEpisode,
        modifier = modifier
    )
}

@Composable
private fun DesktopSeriesPlayerContent(
    player: DirectMemoryVideoPlayer,
    seriesTitle: String,
    episodeTitle: String,
    seasonNumber: Int,
    episodeNumber: Int,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onNavigateBack: () -> Unit,
    onNextEpisode: (() -> Unit)?,
    onPreviousEpisode: (() -> Unit)?,
    hasNextEpisode: Boolean,
    hasPreviousEpisode: Boolean,
    modifier: Modifier = Modifier
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var optimisticSeekMs by remember { mutableLongStateOf(-1L) }
    var activePopup by remember { mutableStateOf(SeriesPlayerPopupType.NONE) }

    var currentSubtitleId by remember { mutableIntStateOf(-1) }
    var currentAudioId by remember { mutableIntStateOf(-1) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }

    var seekFeedbackDirection by remember { mutableStateOf<SeriesSeekFeedbackDirection?>(null) }
    var seekFeedbackTrigger by remember { mutableIntStateOf(0) }

    val invisibleCursor = remember {
        val transparentImg = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        Toolkit.getDefaultToolkit().createCustomCursor(transparentImg, Point(0, 0), "invisibleSeriesCursor")
    }
    val defaultCursor = remember { Cursor.getDefaultCursor() }
    val hiddenPointerIcon = remember(invisibleCursor) { PointerIcon(invisibleCursor) }

    val isCursorHidden = !controlsVisible && player.isPlaying && !isScrubbing && activePopup == SeriesPlayerPopupType.NONE

    LaunchedEffect(isCursorHidden) {
        try {
            val activeWin = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            activeWin?.cursor = if (isCursorHidden) invisibleCursor else defaultCursor
        } catch (_: Throwable) {}
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                val activeWin = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                activeWin?.cursor = defaultCursor
            } catch (_: Throwable) {}
        }
    }

    LaunchedEffect(seekFeedbackTrigger) {
        if (seekFeedbackDirection != null) {
            delay(650L)
            seekFeedbackDirection = null
        }
    }

    LaunchedEffect(player.isFirstFrameRendered) {
        if (player.isFirstFrameRendered) {
            currentSubtitleId = player.mediaPlayer.subpictures().track()
            currentAudioId = player.mediaPlayer.audio().track()
        }
    }

    LaunchedEffect(controlsVisible, player.isPlaying, isScrubbing, activePopup) {
        if (controlsVisible && player.isPlaying && !isScrubbing && activePopup == SeriesPlayerPopupType.NONE) {
            delay(3500L)
            controlsVisible = false
        }
    }

    LaunchedEffect(player.currentTimeMs) {
        if (optimisticSeekMs >= 0L) {
            if (kotlin.math.abs(player.currentTimeMs - optimisticSeekMs) < 1500L) {
                optimisticSeekMs = -1L
            }
        }
    }

    DisposableEffect(player, isFullscreen, activePopup, hasNextEpisode, hasPreviousEpisode) {
        val dispatcher = KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED) {
                controlsVisible = true
                try {
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow?.cursor = defaultCursor
                } catch (_: Throwable) {}

                when (e.keyCode) {
                    KeyEvent.VK_LEFT -> {
                        val newTime = (player.currentTimeMs - 10000L).coerceAtLeast(0L)
                        player.seekToTime(newTime)
                        optimisticSeekMs = newTime
                        seekFeedbackDirection = SeriesSeekFeedbackDirection.REWIND
                        seekFeedbackTrigger++
                        true
                    }
                    KeyEvent.VK_RIGHT -> {
                        val newTime = (player.currentTimeMs + 10000L).coerceAtMost(player.totalDurationMs)
                        player.seekToTime(newTime)
                        optimisticSeekMs = newTime
                        seekFeedbackDirection = SeriesSeekFeedbackDirection.FORWARD
                        seekFeedbackTrigger++
                        true
                    }
                    KeyEvent.VK_SPACE -> {
                        player.togglePlayPause()
                        true
                    }
                    KeyEvent.VK_N -> {
                        if (hasNextEpisode && onNextEpisode != null) {
                            onNextEpisode()
                            true
                        } else false
                    }
                    KeyEvent.VK_P -> {
                        if (hasPreviousEpisode && onPreviousEpisode != null) {
                            onPreviousEpisode()
                            true
                        } else false
                    }
                    KeyEvent.VK_F -> {
                        onToggleFullscreen()
                        true
                    }
                    KeyEvent.VK_ESCAPE -> {
                        if (activePopup != SeriesPlayerPopupType.NONE) {
                            activePopup = SeriesPlayerPopupType.NONE
                            true
                        } else if (isFullscreen) {
                            onToggleFullscreen()
                            true
                        } else {
                            onNavigateBack()
                            true
                        }
                    }
                    else -> false
                }
            } else false
        }

        val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        kfm.addKeyEventDispatcher(dispatcher)
        onDispose {
            kfm.removeKeyEventDispatcher(dispatcher)
        }
    }

    val activeFraction = when {
        isScrubbing -> scrubFraction
        optimisticSeekMs >= 0L && player.totalDurationMs > 0L -> (optimisticSeekMs.toFloat() / player.totalDurationMs.toFloat()).coerceIn(0f, 1f)
        player.totalDurationMs > 0L -> (player.currentTimeMs.toFloat() / player.totalDurationMs.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    val displayTimeMs = when {
        isScrubbing -> (scrubFraction * player.totalDurationMs).toLong()
        optimisticSeekMs >= 0L -> optimisticSeekMs
        else -> player.currentTimeMs
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerHoverIcon(if (isCursorHidden) hiddenPointerIcon else PointerIcon.Default)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Press) {
                            controlsVisible = true
                            try {
                                KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow?.cursor = defaultCursor
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
    ) {
        val tick = player.frameTick
        val frame = tick.let { player.currentFrame }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onToggleFullscreen() },
                        onTap = {
                            if (activePopup != SeriesPlayerPopupType.NONE) {
                                activePopup = SeriesPlayerPopupType.NONE
                            } else {
                                controlsVisible = !controlsVisible
                            }
                        }
                    )
                }
        ) {
            if (frame != null) {
                val scale = min(size.width / frame.width.toFloat(), size.height / frame.height.toFloat())
                val targetW = (frame.width * scale).toInt()
                val targetH = (frame.height * scale).toInt()
                val offsetX = ((size.width - targetW) / 2f).toInt()
                val offsetY = ((size.height - targetH) / 2f).toInt()

                drawImage(
                    image = frame,
                    dstOffset = IntOffset(offsetX, offsetY),
                    dstSize = IntSize(targetW, targetH)
                )
            }
        }

        // Minimalist spinner only
        if (!player.isFirstFrameRendered || player.isBuffering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (player.isFirstFrameRendered) 0.45f else 1f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF00E5FF),
                    strokeWidth = 3.5.dp,
                    modifier = Modifier.size(46.dp)
                )
            }
        }

        SeriesSeekFeedbackIndicator(
            direction = SeriesSeekFeedbackDirection.REWIND,
            visible = seekFeedbackDirection == SeriesSeekFeedbackDirection.REWIND,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 54.dp)
        )
        SeriesSeekFeedbackIndicator(
            direction = SeriesSeekFeedbackDirection.FORWARD,
            visible = seekFeedbackDirection == SeriesSeekFeedbackDirection.FORWARD,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 54.dp)
        )

        SeriesInPlayerSettingsPopup(
            activePopup = activePopup,
            player = player,
            currentSubtitleId = currentSubtitleId,
            currentAudioId = currentAudioId,
            currentSpeed = currentSpeed,
            onSelectSubtitle = { id ->
                currentSubtitleId = id
                player.mediaPlayer.subpictures().setTrack(id)
                activePopup = SeriesPlayerPopupType.NONE
            },
            onSelectAudio = { id ->
                currentAudioId = id
                player.mediaPlayer.audio().setTrack(id)
                activePopup = SeriesPlayerPopupType.NONE
            },
            onSelectSpeed = { speed ->
                currentSpeed = speed
                player.mediaPlayer.controls().setRate(speed)
                activePopup = SeriesPlayerPopupType.NONE
            },
            onDismiss = { activePopup = SeriesPlayerPopupType.NONE },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 74.dp, end = 24.dp)
        )

        AnimatedVisibility(
            visible = controlsVisible || isScrubbing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.75f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.88f)
                            )
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (isFullscreen) onToggleFullscreen() else onNavigateBack()
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = seriesTitle,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val epNumberText = "S%02dE%02d".format(seasonNumber, episodeNumber)
                        val subline = if (episodeTitle.isNotBlank()) "$epNumberText • $episodeTitle" else epNumberText
                        Text(
                            text = subline,
                            color = Color(0xFF42A5F5),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                SeriesBottomTransportBar(
                    player = player,
                    activeFraction = activeFraction,
                    displayTimeMs = displayTimeMs,
                    isScrubbing = isScrubbing,
                    isFullscreen = isFullscreen,
                    currentSpeed = currentSpeed,
                    activePopup = activePopup,
                    onPreviousEpisode = onPreviousEpisode,
                    onNextEpisode = onNextEpisode,
                    hasPreviousEpisode = hasPreviousEpisode,
                    hasNextEpisode = hasNextEpisode,
                    onTogglePopup = { popup ->
                        activePopup = if (activePopup == popup) SeriesPlayerPopupType.NONE else popup
                    },
                    onScrubStart = { frac ->
                        isScrubbing = true
                        scrubFraction = frac
                    },
                    onScrubbing = { frac ->
                        scrubFraction = frac
                    },
                    onScrubEnd = { frac ->
                        isScrubbing = false
                        if (player.totalDurationMs > 0L) {
                            val targetTime = (frac * player.totalDurationMs).toLong()
                            optimisticSeekMs = targetTime
                            player.seekToTime(targetTime)
                        } else {
                            player.seekToFraction(frac)
                        }
                    },
                    onToggleFullscreen = onToggleFullscreen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun SeriesSeekFeedbackIndicator(
    direction: SeriesSeekFeedbackDirection,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(120)) + scaleIn(initialScale = 0.85f, animationSpec = tween(140)),
        exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 1.05f, animationSpec = tween(200)),
        modifier = modifier
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0xB30C111C),
            border = BorderStroke(1.dp, Color(0x6642A5F5)),
            elevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .size(74.dp)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (direction == SeriesSeekFeedbackDirection.REWIND) Icons.Default.FastRewind else Icons.Default.FastForward,
                    contentDescription = null,
                    tint = Color(0xFF42A5F5),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (direction == SeriesSeekFeedbackDirection.REWIND) "-10" else "+10",
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
private fun SeriesInPlayerSettingsPopup(
    activePopup: SeriesPlayerPopupType,
    player: DirectMemoryVideoPlayer,
    currentSubtitleId: Int,
    currentAudioId: Int,
    currentSpeed: Float,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var cachedPopupType by remember { mutableStateOf(SeriesPlayerPopupType.SUBTITLES) }
    if (activePopup != SeriesPlayerPopupType.NONE) {
        cachedPopupType = activePopup
    }
    val displayType = if (activePopup != SeriesPlayerPopupType.NONE) activePopup else cachedPopupType

    val transformOrigin = remember(displayType) {
        when (displayType) {
            SeriesPlayerPopupType.SUBTITLES -> TransformOrigin(0.32f, 1.0f)
            SeriesPlayerPopupType.AUDIO     -> TransformOrigin(0.58f, 1.0f)
            SeriesPlayerPopupType.SPEED     -> TransformOrigin(0.82f, 1.0f)
            SeriesPlayerPopupType.NONE      -> TransformOrigin(0.82f, 1.0f)
        }
    }

    AnimatedVisibility(
        visible = activePopup != SeriesPlayerPopupType.NONE,
        enter = scaleIn(
            initialScale = 0.12f,
            transformOrigin = transformOrigin,
            animationSpec = tween(durationMillis = 240, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))
        ) + fadeIn(animationSpec = tween(durationMillis = 160)),
        exit = scaleOut(
            targetScale = 0.08f,
            transformOrigin = transformOrigin,
            animationSpec = tween(durationMillis = 190, easing = FastOutLinearInEasing)
        ) + fadeOut(animationSpec = tween(durationMillis = 150)),
        modifier = modifier
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            backgroundColor = Color(0xF20F1420),
            border = BorderStroke(1.dp, Color(0xFF263248)),
            elevation = 16.dp,
            modifier = Modifier
                .widthIn(min = 220.dp, max = 290.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                when (displayType) {
                    SeriesPlayerPopupType.SUBTITLES -> {
                        Text(
                            text = "SUBTITLES",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )

                        SeriesMenuOptionItem(
                            label = "Subtitles Off",
                            isSelected = currentSubtitleId == -1,
                            onClick = { onSelectSubtitle(-1) }
                        )

                        val spuTracks = player.mediaPlayer.subpictures().trackDescriptions() ?: emptyList()
                        spuTracks.filter { it.id() != -1 }.forEach { track ->
                            SeriesMenuOptionItem(
                                label = track.description(),
                                isSelected = track.id() == currentSubtitleId,
                                onClick = { onSelectSubtitle(track.id()) }
                            )
                        }
                    }

                    SeriesPlayerPopupType.AUDIO -> {
                        Text(
                            text = "AUDIO TRACK",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )

                        val audioTracks = player.mediaPlayer.audio().trackDescriptions() ?: emptyList()
                        if (audioTracks.isEmpty()) {
                            SeriesMenuOptionItem(
                                label = "Default Audio",
                                isSelected = true,
                                onClick = onDismiss
                            )
                        } else {
                            audioTracks.forEach { track ->
                                SeriesMenuOptionItem(
                                    label = track.description(),
                                    isSelected = track.id() == currentAudioId,
                                    onClick = { onSelectAudio(track.id()) }
                                )
                            }
                        }
                    }

                    SeriesPlayerPopupType.SPEED -> {
                        Text(
                            text = "PLAYBACK SPEED",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )

                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                            SeriesMenuOptionItem(
                                label = "${speed}x" + if (speed == 1.0f) " (Normal)" else "",
                                isSelected = currentSpeed == speed,
                                onClick = { onSelectSpeed(speed) }
                            )
                        }
                    }

                    SeriesPlayerPopupType.NONE -> {}
                }
            }
        }
    }
}

@Composable
private fun SeriesMenuOptionItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (isSelected) Color(0xFF132238) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (isSelected) Color(0xFF42A5F5) else Color.White,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Text(
                text = "✓",
                color = Color(0xFF42A5F5),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun SeriesBottomTransportBar(
    player: DirectMemoryVideoPlayer,
    activeFraction: Float,
    displayTimeMs: Long,
    isScrubbing: Boolean,
    isFullscreen: Boolean,
    currentSpeed: Float,
    activePopup: SeriesPlayerPopupType,
    onPreviousEpisode: (() -> Unit)?,
    onNextEpisode: (() -> Unit)?,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    onTogglePopup: (SeriesPlayerPopupType) -> Unit,
    onScrubStart: (Float) -> Unit,
    onScrubbing: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val containerWidthPx = with(density) { maxWidth.toPx() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(containerWidthPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downFrac = (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onScrubStart(downFrac)
                            var lastFrac = downFrac
                            down.consume()

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.pressed) {
                                    lastFrac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    change.consume()
                                    onScrubbing(lastFrac)
                                } else {
                                    change.consume()
                                    onScrubEnd(lastFrac)
                                    break
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x33FFFFFF))
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth(activeFraction.coerceIn(0.001f, 1f))
                        .height(3.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFF0D47A1), Color(0xFF42A5F5))
                            )
                        )
                )

                val thumbSize = if (isScrubbing) 15.dp else 11.dp
                Box(
                    modifier = Modifier
                        .offset {
                            val xPos = (activeFraction * containerWidthPx) - with(density) { (thumbSize / 2).toPx() }
                            IntOffset(
                                xPos.roundToInt().coerceIn(
                                    0,
                                    (containerWidthPx - with(density) { thumbSize.toPx() }).roundToInt().coerceAtLeast(0)
                                ),
                                0
                            )
                        }
                        .size(thumbSize)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color(0xFF42A5F5))
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(
                    onClick = { onPreviousEpisode?.invoke() },
                    enabled = hasPreviousEpisode && onPreviousEpisode != null,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Episode (P)",
                        tint = if (hasPreviousEpisode && onPreviousEpisode != null) Color.White else Color(0x40FFFFFF),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { player.togglePlayPause() },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(
                    onClick = { onNextEpisode?.invoke() },
                    enabled = hasNextEpisode && onNextEpisode != null,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Episode (N)",
                        tint = if (hasNextEpisode && onNextEpisode != null) Color.White else Color(0x40FFFFFF),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
                            player.setVolumeLevel(if (player.volume > 0) 0 else 100)
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                player.volume == 0 -> Icons.AutoMirrored.Filled.VolumeMute
                                player.volume < 50 -> Icons.AutoMirrored.Filled.VolumeDown
                                else -> Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = "Volume",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    SeriesSlickVolumeSlider(
                        volume = player.volume,
                        onVolumeChange = { player.setVolumeLevel(it) }
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "${formatDuration(displayTimeMs)} /${formatDuration(player.totalDurationMs)}",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(
                    onClick = { onTogglePopup(SeriesPlayerPopupType.SUBTITLES) },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ClosedCaption,
                        contentDescription = "Subtitles",
                        tint = if (activePopup == SeriesPlayerPopupType.SUBTITLES) Color(0xFF42A5F5) else Color(0xFFCBD5E1),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { onTogglePopup(SeriesPlayerPopupType.AUDIO) },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Audiotrack,
                        contentDescription = "Audio Tracks",
                        tint = if (activePopup == SeriesPlayerPopupType.AUDIO) Color(0xFF42A5F5) else Color(0xFFCBD5E1),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (activePopup == SeriesPlayerPopupType.SPEED) Color(0xFF1E293B) else Color(0x33FFFFFF),
                    modifier = Modifier
                        .clickable { onTogglePopup(SeriesPlayerPopupType.SPEED) }
                        .padding(horizontal = 2.dp)
                ) {
                    Text(
                        text = "${currentSpeed}x",
                        color = if (activePopup == SeriesPlayerPopupType.SPEED) Color(0xFF42A5F5) else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                IconButton(
                    onClick = onToggleFullscreen,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = "Toggle Fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesSlickVolumeSlider(
    volume: Int,
    onVolumeChange: (Int) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    val volumeFraction = (volume / 100f).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = Modifier
            .width(76.dp)
            .height(24.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val frac = (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onVolumeChange((frac * 100).roundToInt())
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            val currentFrac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            change.consume()
                            onVolumeChange((currentFrac * 100).roundToInt())
                        } else {
                            change.consume()
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x33FFFFFF))
        )

        Box(
            modifier = Modifier
                .fillMaxWidth(volumeFraction)
                .height(3.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFF0D47A1), Color(0xFF42A5F5))
                    )
                )
        )

        val thumbSize = if (isDragging) 11.dp else 9.dp
        Box(
            modifier = Modifier
                .offset {
                    val xPos = (volumeFraction * containerWidthPx) - with(density) { (thumbSize / 2).toPx() }
                    IntOffset(
                        xPos.roundToInt().coerceIn(
                            0,
                            (containerWidthPx - with(density) { thumbSize.toPx() }).roundToInt().coerceAtLeast(0)
                        ),
                        0
                    )
                }
                .size(thumbSize)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFF42A5F5))
        )
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
