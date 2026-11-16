package com.clotube.desktop.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import com.clotube.desktop.ui.components.CinemaColorFilter
import com.clotube.desktop.ui.components.PacedLoadingSpinner
import com.clotube.desktop.ui.components.SubtitleCanvasOverlay
import com.clotube.desktop.ui.components.SubtitleScaleOsdBadge
import com.clotube.desktop.ui.components.onCtrlScrollSubtitleScale
import com.clotube.desktop.ui.motion.FluidMotionKit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.data.DesktopAppSettings
import com.clotube.desktop.media.DirectMemoryVideoPlayer
import com.clotube.desktop.media.LocalStreamServer
import com.clotube.desktop.model.DesktopEpisode
import kotlinx.coroutines.delay
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import androidx.compose.ui.input.pointer.PointerEventPass
import java.awt.AWTEvent
import java.awt.Container
import java.awt.Cursor
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private fun applyPlayerWindowCursor(targetCursor: Cursor) {
    try {
        for (win in Window.getWindows()) {
            if (win.isDisplayable) {
                win.cursor = targetCursor
                if (win is Container) {
                    for (child in win.components) {
                        child.cursor = targetCursor
                        if (child is Container) {
                            for (grandChild in child.components) {
                                grandChild.cursor = targetCursor
                            }
                        }
                    }
                }
            }
        }
    } catch (_: Throwable) {}
}

private enum class SeriesPlayerPopupType {
    NONE, SUBTITLES, AUDIO, SPEED
}

private enum class SeriesSeekFeedbackDirection {
    REWIND, FORWARD
}

@Composable
fun DesktopSeriesPlayerScreen(
    player: DirectMemoryVideoPlayer?,
    seriesTitle: String,
    currentEpisode: DesktopEpisode? = null,
    episodeTitle: String = "",
    seasonNumber: Int = 1,
    episodeNumber: Int = 1,
    nextEpisodeTitle: String = "",
    nextSeasonNumber: Int = 1,
    nextEpisodeNumber: Int = 1,
    initialSeekMs: Long = 0L,
    initialDurationMs: Long = 0L,
    isDriveTimestampLoaded: Boolean,
    onInitialSeekApplied: (() -> Unit)? = null,
    onProgressUpdate: ((Long) -> Unit)? = null,
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
        currentEpisode = currentEpisode,
        episodeTitle = episodeTitle,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        nextEpisodeTitle = nextEpisodeTitle,
        nextSeasonNumber = nextSeasonNumber,
        nextEpisodeNumber = nextEpisodeNumber,
        initialSeekMs = initialSeekMs,
        initialDurationMs = initialDurationMs,
        isDriveTimestampLoaded = isDriveTimestampLoaded,
        onInitialSeekApplied = onInitialSeekApplied,
        onProgressUpdate = onProgressUpdate,
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
    currentEpisode: DesktopEpisode? = null,
    episodeTitle: String = "",
    seasonNumber: Int = 1,
    episodeNumber: Int = 1,
    nextEpisodeTitle: String = "",
    nextSeasonNumber: Int = 1,
    nextEpisodeNumber: Int = 1,
    initialSeekMs: Long = 0L,
    initialDurationMs: Long = 0L,
    isDriveTimestampLoaded: Boolean = true,
    onInitialSeekApplied: (() -> Unit)? = null,
    onProgressUpdate: ((Long) -> Unit)? = null,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onNavigateBack: () -> Unit,
    onNextEpisode: (() -> Unit)? = null,
    onPreviousEpisode: (() -> Unit)? = null,
    hasNextEpisode: Boolean = false,
    hasPreviousEpisode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cinemaColor = DesktopAppSettings.state.cinemaColorEnhancement
    val player = remember(mediaUrl, cinemaColor) { DirectMemoryVideoPlayer(mediaUrl) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    DesktopSeriesPlayerContent(
        player = player,
        seriesTitle = seriesTitle,
        currentEpisode = currentEpisode,
        episodeTitle = episodeTitle,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        nextEpisodeTitle = nextEpisodeTitle,
        nextSeasonNumber = nextSeasonNumber,
        nextEpisodeNumber = nextEpisodeNumber,
        initialSeekMs = initialSeekMs,
        initialDurationMs = initialDurationMs,
        isDriveTimestampLoaded = isDriveTimestampLoaded,
        onInitialSeekApplied = onInitialSeekApplied,
        onProgressUpdate = onProgressUpdate,
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
    currentEpisode: DesktopEpisode? = null,
    episodeTitle: String = "",
    seasonNumber: Int = 1,
    episodeNumber: Int = 1,
    nextEpisodeTitle: String = "",
    nextSeasonNumber: Int = 1,
    nextEpisodeNumber: Int = 1,
    initialSeekMs: Long = 0L,
    initialDurationMs: Long = 0L,
    isDriveTimestampLoaded: Boolean = true,
    onInitialSeekApplied: (() -> Unit)? = null,
    onProgressUpdate: ((Long) -> Unit)? = null,
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
        currentEpisode = currentEpisode,
        episodeTitle = episodeTitle,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        nextEpisodeTitle = nextEpisodeTitle,
        nextSeasonNumber = nextSeasonNumber,
        nextEpisodeNumber = nextEpisodeNumber,
        initialSeekMs = initialSeekMs,
        initialDurationMs = initialDurationMs,
        isDriveTimestampLoaded = isDriveTimestampLoaded,
        onInitialSeekApplied = onInitialSeekApplied,
        onProgressUpdate = onProgressUpdate,
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
    player: DirectMemoryVideoPlayer?,
    seriesTitle: String,
    currentEpisode: DesktopEpisode? = null,
    episodeTitle: String,
    seasonNumber: Int,
    episodeNumber: Int,
    nextEpisodeTitle: String,
    nextSeasonNumber: Int,
    nextEpisodeNumber: Int,
    initialSeekMs: Long = 0L,
    initialDurationMs: Long = 0L,
    isDriveTimestampLoaded: Boolean,
    onInitialSeekApplied: (() -> Unit)? = null,
    onProgressUpdate: ((Long) -> Unit)? = null,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onNavigateBack: () -> Unit,
    onNextEpisode: (() -> Unit)?,
    onPreviousEpisode: (() -> Unit)?,
    hasNextEpisode: Boolean,
    hasPreviousEpisode: Boolean,
    modifier: Modifier = Modifier
) {
    val boundId = player?.boundMediaId ?: ""
    val currentEpisode = currentEpisode ?: remember(boundId, seriesTitle, episodeTitle, seasonNumber, episodeNumber) {
        DesktopEpisode(
            id = boundId,
            seriesId = "",
            seriesTitle = seriesTitle,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            title = episodeTitle
        )
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(currentEpisode.id, isFullscreen) {
        try {
            focusRequester.requestFocus()
        } catch (_: Throwable) {}
    }

    LaunchedEffect(currentEpisode.id, player) {
        val p = player ?: return@LaunchedEffect
        delay(3500L)
        if (!p.isFirstFrameRendered && !p.isPlaying && p.currentTimeMs == 0L) {
            println("[DesktopSeriesPlayerScreen] Watchdog: First frame not rendered after 3.5s. Restarting stream: ${p.mediaUrl}")
            p.restartStream(p.mediaUrl, initialSeekMs)
        }
    }

    var controlsVisible by remember(currentEpisode.id) { mutableStateOf(true) }
    val activeColorFilter = if (DesktopAppSettings.state.cinemaColorEnhancement) {
        CinemaColorFilter.ToneMappedColorFilter
    } else {
        null
    }

    var subtitleScalePercent by remember {
        mutableIntStateOf(DesktopAppSettings.state.subtitleScalePercent)
    }
    var showSubtitleScaleOsd by remember { mutableStateOf(false) }
    var subtitleScaleTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(DesktopAppSettings.state.subtitleScalePercent) {
        val newScale = DesktopAppSettings.state.subtitleScalePercent
        subtitleScalePercent = newScale
        player?.applySubtitleScale(newScale)
    }

    LaunchedEffect(subtitleScaleTrigger) {
        if (subtitleScaleTrigger > 0) {
            showSubtitleScaleOsd = true
            delay(1000L)
            showSubtitleScaleOsd = false
        }
    }
    var isScrubbing by remember(currentEpisode.id) { mutableStateOf(false) }
    var scrubFraction by remember(currentEpisode.id) { mutableFloatStateOf(0f) }
    var playNextDismissed by remember(currentEpisode.id) { mutableStateOf(false) }
    var optimisticSeekMs by remember(currentEpisode.id) { mutableLongStateOf(-1L) }
    var activePopup by remember { mutableStateOf(SeriesPlayerPopupType.NONE) }

    val isPlayerSynced = player == null || player.boundMediaId.isEmpty() || player.boundMediaId == currentEpisode.id
    val isVideoFrameReady = isDriveTimestampLoaded &&
        player != null &&
        player.isTargetFrameReady &&
        isPlayerSynced

    // Controls appear as soon as timestamp details are fetched from Google Drive
    val areVideoControlsReady = isDriveTimestampLoaded
    val areControlsReady = areVideoControlsReady

    val shouldShowInitialOverlay = !isVideoFrameReady
    val initialOverlayState = remember(currentEpisode.id) {
        MutableTransitionState(shouldShowInitialOverlay)
    }
    initialOverlayState.targetState = shouldShowInitialOverlay
    val isInitialOverlayActive = initialOverlayState.currentState || initialOverlayState.targetState

    val currentLoadingLabel = if (!isDriveTimestampLoaded) "Syncing watch history..." else "Loading video..."
    var displayedLoadingLabel by remember(currentEpisode.id) { mutableStateOf(currentLoadingLabel) }
    if (!isVideoFrameReady) {
        displayedLoadingLabel = currentLoadingLabel
    }

    LaunchedEffect(areControlsReady, isVideoFrameReady) {
        if (areControlsReady && !isVideoFrameReady) {
            controlsVisible = true
        }
    }

    val effectiveCurrentTimeMs: () -> Long = {
        val p = player
        if (p != null && isVideoFrameReady) {
            if (optimisticSeekMs >= 0L) optimisticSeekMs else p.currentTimeMs
        } else {
            if (optimisticSeekMs >= 0L) optimisticSeekMs else initialSeekMs
        }
    }

    val effectiveTotalDurationMs: () -> Long = {
        val p = player
        when {
            p != null && p.totalDurationMs > 0L -> p.totalDurationMs
            initialDurationMs > 0L -> initialDurationMs
            currentEpisode.durationMs > 0L -> currentEpisode.durationMs
            else -> 0L
        }
    }

    val rawFraction = if (areControlsReady && effectiveTotalDurationMs() > 0L) {
        (effectiveCurrentTimeMs().toFloat() / effectiveTotalDurationMs().toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val displayFraction = if (isScrubbing) scrubFraction else rawFraction

    var currentSubtitleId by remember(player) { mutableIntStateOf(-1) }
    var currentAudioId by remember(player) { mutableIntStateOf(-1) }
    var currentSpeed by remember(player) {
        mutableFloatStateOf(DesktopAppSettings.state.defaultSpeed)
    }

    var seekFeedbackDirection by remember { mutableStateOf<SeriesSeekFeedbackDirection?>(null) }
    var seekFeedbackTrigger by remember { mutableIntStateOf(0) }

    // Auto-Play & Binge State
    var hasAutoPlayTriggered by remember(currentEpisode.id) { mutableStateOf(false) }

    val shouldAutoPlay = DesktopAppSettings.state.autoPlayNextEpisode
    val seekStepMs = remember(DesktopAppSettings.state.seekStepSeconds) {
        DesktopAppSettings.state.seekStepSeconds * 1000L
    }

    // LibVLC Native Event Hook for finished playback
    val latestOnNextEpisode by rememberUpdatedState(onNextEpisode)
    val latestHasNextEpisode by rememberUpdatedState(hasNextEpisode)
    val latestShouldAutoPlay by rememberUpdatedState(shouldAutoPlay)

    DisposableEffect(player) {
        val activePlayer = player ?: return@DisposableEffect onDispose {}
        if (activePlayer.isDisposed) {
            return@DisposableEffect onDispose {}
        }

        val endListener = object : MediaPlayerEventAdapter() {
            override fun finished(mp: MediaPlayer) {
                SwingUtilities.invokeLater {
                    if (latestShouldAutoPlay && latestHasNextEpisode && !hasAutoPlayTriggered) {
                        hasAutoPlayTriggered = true
                        latestOnNextEpisode?.invoke()
                    }
                }
            }
        }

        val attached = activePlayer.addSafeEventListener(endListener)
        onDispose {
            if (attached) {
                activePlayer.removeSafeEventListener(endListener)
            }
        }
    }

    // Trigger condition: Last 35 seconds of the episode (derived state so it only changes boolean value once)
    val shouldShowAutoPlayPrompt by remember(player, isPlayerSynced, areControlsReady, shouldAutoPlay, hasNextEpisode, playNextDismissed, hasAutoPlayTriggered) {
        derivedStateOf {
            val p = player ?: return@derivedStateOf false
            val remaining = (p.totalDurationMs - p.currentTimeMs).coerceAtLeast(0L)
            areControlsReady &&
                    isPlayerSynced &&
                    p.totalDurationMs > 0L &&
                    shouldAutoPlay &&
                    hasNextEpisode &&
                    !playNextDismissed &&
                    !hasAutoPlayTriggered &&
                    p.totalDurationMs > 40000L &&
                    remaining in 1L..35000L
        }
    }

    // Dynamic countdown tracking actual remaining seconds (quantized to 1000ms steps)
    val autoPlayCountdownSeconds by remember(player, isPlayerSynced, areControlsReady) {
        derivedStateOf {
            val p = player
            if (!areControlsReady || !isPlayerSynced || p == null) 35
            else {
                val remaining = (p.totalDurationMs - p.currentTimeMs).coerceAtLeast(0L)
                (remaining / 1000L).toInt().coerceAtLeast(1)
            }
        }
    }

    // Auto-advance to next episode when runtime reaches 0 (<= 800ms)
    LaunchedEffect(player, isPlayerSynced, areControlsReady, shouldAutoPlay, hasNextEpisode, playNextDismissed, hasAutoPlayTriggered) {
        val p = player ?: return@LaunchedEffect
        if (shouldAutoPlay && hasNextEpisode && onNextEpisode != null) {
            snapshotFlow {
                if (areControlsReady && isPlayerSynced && p.totalDurationMs > 40000L) {
                    (p.totalDurationMs - p.currentTimeMs).coerceAtLeast(0L)
                } else {
                    Long.MAX_VALUE
                }
            }.collect { rem ->
                if (!playNextDismissed && !hasAutoPlayTriggered && rem in 1L..800L) {
                    hasAutoPlayTriggered = true
                    onNextEpisode()
                }
            }
        }
    }

    val invisibleCursor = remember {
        val transparentImg = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        Toolkit.getDefaultToolkit().createCustomCursor(transparentImg, Point(0, 0), "invisibleSeriesCursor")
    }
    val defaultCursor = remember { Cursor.getDefaultCursor() }
    val hiddenPointerIcon = remember(invisibleCursor) { PointerIcon(invisibleCursor) }

    val isCursorHidden = !controlsVisible &&
        isVideoFrameReady &&
        (player?.isPlaying == true) &&
        !isScrubbing &&
        activePopup == SeriesPlayerPopupType.NONE &&
        !shouldShowAutoPlayPrompt

    LaunchedEffect(isCursorHidden, isFullscreen) {
        applyPlayerWindowCursor(if (isCursorHidden) invisibleCursor else defaultCursor)
    }

    val wakeControlsAndCursor by rememberUpdatedState {
        if (!controlsVisible) {
            controlsVisible = true
        }
        applyPlayerWindowCursor(defaultCursor)
    }

    DisposableEffect(Unit) {
        var lastX = Int.MIN_VALUE
        var lastY = Int.MIN_VALUE
        val awtMouseListener = AWTEventListener { event ->
            if (event is MouseEvent) {
                when (event.id) {
                    MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED -> {
                        val dx = if (lastX == Int.MIN_VALUE) 999 else abs(event.xOnScreen - lastX)
                        val dy = if (lastY == Int.MIN_VALUE) 999 else abs(event.yOnScreen - lastY)
                        lastX = event.xOnScreen
                        lastY = event.yOnScreen
                        if (dx >= 2 || dy >= 2) {
                            wakeControlsAndCursor()
                        }
                    }
                    MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_CLICKED -> {
                        lastX = event.xOnScreen
                        lastY = event.yOnScreen
                        wakeControlsAndCursor()
                    }
                }
            }
        }
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(
                awtMouseListener,
                AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK
            )
        } catch (_: Throwable) {}

        onDispose {
            try {
                Toolkit.getDefaultToolkit().removeAWTEventListener(awtMouseListener)
            } catch (_: Throwable) {}
            applyPlayerWindowCursor(defaultCursor)
        }
    }

    LaunchedEffect(seekFeedbackTrigger) {
        if (seekFeedbackDirection != null) {
            delay(650L)
            seekFeedbackDirection = null
        }
    }

    LaunchedEffect(player?.isFirstFrameRendered) {
        val p = player
        if (p != null && p.isFirstFrameRendered) {
            if (currentSubtitleId != -99) {
                currentSubtitleId = p.getActiveSubtitleTrackId()
            }
            currentAudioId = p.getActiveAudioTrackId()
        }
    }

    LaunchedEffect(controlsVisible, player?.isPlaying, isScrubbing, activePopup, isVideoFrameReady) {
        if (isVideoFrameReady && controlsVisible && (player?.isPlaying == true) && !isScrubbing && activePopup == SeriesPlayerPopupType.NONE) {
            delay(3500L)
            controlsVisible = false
        }
    }

    LaunchedEffect(player, optimisticSeekMs) {
        val p = player ?: return@LaunchedEffect
        if (optimisticSeekMs >= 0L) {
            snapshotFlow { p.currentTimeMs }
                .collect { cur ->
                    if (kotlin.math.abs(cur - optimisticSeekMs) < 1500L) {
                        optimisticSeekMs = -1L
                    }
                }
        }
    }

    // Initial Resume Settlement Notification
    LaunchedEffect(isVideoFrameReady) {
        if (isVideoFrameReady) {
            onInitialSeekApplied?.invoke()
        }
    }

    // Periodic progress reporting every 5 seconds while actively playing
    LaunchedEffect(player, isPlayerSynced, isVideoFrameReady, onProgressUpdate) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            delay(5000L)
            if (isVideoFrameReady && isPlayerSynced && p.isPlaying && p.currentTimeMs > 0L) {
                onProgressUpdate?.invoke(p.currentTimeMs)
            }
        }
    }

    // Report progress when playback pauses
    LaunchedEffect(player?.isPlaying, isPlayerSynced, isVideoFrameReady) {
        val p = player ?: return@LaunchedEffect
        if (isVideoFrameReady && isPlayerSynced && !p.isPlaying && p.currentTimeMs > 0L) {
            onProgressUpdate?.invoke(p.currentTimeMs)
        }
    }

    // Report progress on player teardown/navigation
    DisposableEffect(player, isPlayerSynced, isVideoFrameReady) {
        val p = player
        onDispose {
            if (p != null && isVideoFrameReady && isPlayerSynced && p.currentTimeMs > 0L) {
                onProgressUpdate?.invoke(p.currentTimeMs)
            }
        }
    }

    DisposableEffect(player, areControlsReady, isFullscreen, activePopup, hasNextEpisode, hasPreviousEpisode, seekStepMs) {
        val dispatcher = KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED) {
                wakeControlsAndCursor()

                if (!areControlsReady) {
                    if (e.keyCode == KeyEvent.VK_ESCAPE) {
                        if (isFullscreen) {
                            onToggleFullscreen()
                            true
                        } else {
                            onNavigateBack()
                            true
                        }
                    } else false
                } else {
                    val p = player
                    when (e.keyCode) {
                        KeyEvent.VK_LEFT -> {
                            val totalMs = effectiveTotalDurationMs()
                            if (p != null && p.isFirstFrameRendered && totalMs > 0L) {
                                val newTime = (p.currentTimeMs - seekStepMs).coerceIn(0L, totalMs)
                                p.seekToTime(newTime, isUserScrub = true)
                                optimisticSeekMs = newTime
                                seekFeedbackDirection = SeriesSeekFeedbackDirection.REWIND
                                seekFeedbackTrigger++
                                onProgressUpdate?.invoke(newTime)
                            }
                            true
                        }
                        KeyEvent.VK_RIGHT -> {
                            val totalMs = effectiveTotalDurationMs()
                            if (p != null && p.isFirstFrameRendered && totalMs > 0L) {
                                val newTime = (p.currentTimeMs + seekStepMs).coerceIn(0L, totalMs)
                                p.seekToTime(newTime, isUserScrub = true)
                                optimisticSeekMs = newTime
                                seekFeedbackDirection = SeriesSeekFeedbackDirection.FORWARD
                                seekFeedbackTrigger++
                                onProgressUpdate?.invoke(newTime)
                            }
                            true
                        }
                        KeyEvent.VK_SPACE -> {
                            p?.togglePlayPause()
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
                }
            } else false
        }

        val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        kfm.addKeyEventDispatcher(dispatcher)
        onDispose {
            kfm.removeKeyEventDispatcher(dispatcher)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .pointerHoverIcon(if (isCursorHidden) hiddenPointerIcon else PointerIcon.Default)
            .pointerInput(isFullscreen, currentEpisode.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Press) {
                            wakeControlsAndCursor()
                        }
                    }
                }
            }
            .onCtrlScrollSubtitleScale(
                currentPercent = subtitleScalePercent,
                onScaleStep = { nextPercent ->
                    subtitleScalePercent = nextPercent
                    subtitleScaleTrigger++
                    player?.applySubtitleScale(nextPercent)
                    DesktopAppSettings.update { copy(subtitleScalePercent = nextPercent) }
                }
            )
    ) {
        val tick = player?.frameTick ?: 0L
        val frame = if (player != null) tick.let { player.currentFrame } else null

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
                    dstSize = IntSize(targetW, targetH),
                    colorFilter = activeColorFilter
                )
            }
        }

        // 1. Initial Load Overlay (Instant mount on Frame 1, smooth 320ms fade-out into first frame)
        AnimatedVisibility(
            visibleState = initialOverlayState,
            enter = EnterTransition.None,
            exit = fadeOut(animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    PacedLoadingSpinner(
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.size(46.dp),
                        strokeWidth = 3.5.dp
                    )
                    Text(
                        text = displayedLoadingLabel,
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 2. Mid-Playback Network Buffering Spinner (Strictly blocked until initial overlay fade-out finishes)
        if (!isInitialOverlayActive && player?.isBuffering == true) {
            PacedLoadingSpinner(
                color = Color(0xFF38BDF8),
                modifier = Modifier
                    .size(46.dp)
                    .align(Alignment.Center),
                strokeWidth = 3.5.dp
            )
        }


        if (areControlsReady) {
            // Seek Feedback Indicator
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

        // ========================================================
        // LAYER 2: CINEMATIC VIGNETTE SCRIM (BEHIND CONTROLS & POPUPS)
        // ========================================================
        AnimatedVisibility(
            visible = controlsVisible || isScrubbing,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black.copy(alpha = 0.80f),
                                0.15f to Color.Black.copy(alpha = 0.45f),
                                0.30f to Color.Transparent,
                                0.65f to Color.Transparent,
                                0.82f to Color.Black.copy(alpha = 0.55f),
                                1.0f to Color.Black.copy(alpha = 0.90f)
                            )
                        )
                    )
            )
        }

        // ========================================================
        // LAYER 3: CONTROLS HUD (TOP BAR & BOTTOM TRANSPORT BAR)
        // ========================================================
        AnimatedVisibility(
            visible = controlsVisible || isScrubbing,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Cinema Header (Title & Episode Info)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = seriesTitle,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val epNumberText = "S%02dE%02d".format(seasonNumber, episodeNumber)
                        val subline = if (episodeTitle.isNotBlank()) "$epNumberText • $episodeTitle" else epNumberText
                        Text(
                            text = subline,
                            color = Color(0xFF42A5F5),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // -------------------------------------------------------------------------
                // DOCKED BOTTOM TRANSPORT BAR (REVERTED FROM FLOATING CAPSULE)
                // -------------------------------------------------------------------------
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color(0xCC05070B),
                                    Color(0xF205070B)
                                )
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    SeriesBottomTransportBar(
                        player = player,
                        currentEpisodeId = currentEpisode.id,
                        isPlayerSynced = isPlayerSynced,
                        timeMsProvider = effectiveCurrentTimeMs,
                        durationMsProvider = effectiveTotalDurationMs,
                        onScrubbingStateChange = { isScrubbing = it },
                        onSeek = { targetMs ->
                            if (isPlayerSynced) {
                                val totalDur = effectiveTotalDurationMs()
                                if (totalDur > 0L) {
                                    optimisticSeekMs = targetMs
                                    player?.seekToTime(targetMs)
                                    onProgressUpdate?.invoke(targetMs)
                                }
                            }
                        },
                        isFullscreen = isFullscreen,
                        currentSpeed = currentSpeed,
                        activePopup = activePopup,
                        onPreviousEpisode = onPreviousEpisode,
                        onNextEpisode = onNextEpisode,
                        hasPreviousEpisode = hasPreviousEpisode,
                        hasNextEpisode = hasNextEpisode,
                        onTogglePopup = { popup ->
                            val p = player
                            if (p != null && activePopup != popup && popup != SeriesPlayerPopupType.NONE) {
                                if (popup == SeriesPlayerPopupType.SUBTITLES) {
                                    if (currentSubtitleId != -99) {
                                        currentSubtitleId = p.getActiveSubtitleTrackId()
                                    }
                                } else if (popup == SeriesPlayerPopupType.AUDIO) {
                                    currentAudioId = p.getActiveAudioTrackId()
                                }
                            }
                            activePopup = if (activePopup == popup) SeriesPlayerPopupType.NONE else popup
                        },
                        onToggleFullscreen = onToggleFullscreen,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // ========================================================
        // LAYER 4: FLOATING NEXT EPISODE PROMPT (ABOVE VIGNETTE)
        // ========================================================
        AnimatedVisibility(
            visible = shouldShowAutoPlayPrompt,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 76.dp, end = 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF121B2B), // Solid, fully opaque
                border = BorderStroke(1.dp, Color(0xFF2563EB)),
                elevation = 16.dp,
                modifier = Modifier
                    .shadow(16.dp, RoundedCornerShape(12.dp))
                    .widthIn(min = 280.dp, max = 320.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header: Status + Dismiss 'X'
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "UP NEXT IN ${autoPlayCountdownSeconds}S",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel Auto-Play",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .clickable { playNextDismissed = true }
                                .pointerHoverIcon(PointerIcon.Hand)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Next Episode Title & Index
                    val nextEpCode = "S%02dE%02d".format(nextSeasonNumber, nextEpisodeNumber)
                    val nextDisplayTitle = if (nextEpisodeTitle.isNotBlank()) "$nextEpCode • $nextEpisodeTitle" else nextEpCode
                    Text(
                        text = nextDisplayTitle,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Redesigned Full-Width "Play Now" Button (Dismiss Button Removed)
                    Button(
                        onClick = {
                            if (onNextEpisode != null) {
                                hasAutoPlayTriggered = true
                                onNextEpisode()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFF2563EB),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        elevation = ButtonDefaults.elevation(defaultElevation = 4.dp, pressedElevation = 2.dp),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Play Now",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // ========================================================
        // LAYER 5: POPUPS OVERLAY (HIGHEST Z-INDEX — NEVER DARKENED)
        // ========================================================
        if (activePopup != SeriesPlayerPopupType.NONE) {
            // Invisible touch barrier to dismiss popup when clicking outside
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { activePopup = SeriesPlayerPopupType.NONE }
            )
        }

        // Settings Popup Overlay
        SeriesInPlayerSettingsPopup(
            activePopup = activePopup,
            player = player,
            currentSubtitleId = currentSubtitleId,
            currentAudioId = currentAudioId,
            currentSpeed = currentSpeed,
            onSelectSubtitle = { trackId ->
                val p = player
                if (p != null) {
                    val isOff = trackId == -1
                    if (trackId == -99) {
                        p.reactivateExternalSubtitle()
                    } else {
                        val trackTitle = if (isOff) "Off" else (p.mediaPlayer.subpictures().trackDescriptions()?.find { it.id() == trackId }?.description() ?: "")
                        p.setSubtitleTrack(trackId, trackTitle, isOff)
                    }
                }
                currentSubtitleId = trackId
                activePopup = SeriesPlayerPopupType.NONE
            },
            onSelectAudio = { trackId, trackTitle ->
                player?.setAudioTrack(trackId, trackTitle)
                currentAudioId = trackId
                activePopup = SeriesPlayerPopupType.NONE
            },
            onSelectSpeed = { speed ->
                currentSpeed = speed
                player?.setPlaybackRate(speed)
                activePopup = SeriesPlayerPopupType.NONE
            },
            onDismiss = { activePopup = SeriesPlayerPopupType.NONE },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 74.dp, end = 20.dp)
        )
        }

        SubtitleCanvasOverlay(
            cues = player?.externalSubtitleCues ?: emptyList(),
            currentTimeMs = player?.currentTimeMs ?: 0L,
            scalePercent = subtitleScalePercent,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (controlsVisible) 96.dp else 36.dp, start = 24.dp, end = 24.dp)
        )

        SubtitleScaleOsdBadge(
            scalePercent = subtitleScalePercent,
            visible = showSubtitleScaleOsd,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = 32.dp)
        )
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
            border = BorderStroke(1.dp, Color(0xFF222B3D)),
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
                    tint = Color(0xFF60A5FA),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                val seekSec = DesktopAppSettings.state.seekStepSeconds
                Text(
                    text = if (direction == SeriesSeekFeedbackDirection.REWIND) "-$seekSec" else "+$seekSec",
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
    player: DirectMemoryVideoPlayer?,
    currentSubtitleId: Int,
    currentAudioId: Int,
    currentSpeed: Float,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int, String) -> Unit,
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
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xF20B0E16), // Frosted obsidian glass
            border = BorderStroke(1.dp, Color(0xFF1E2838)),
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
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        Divider(
                            color = Color(0xFF161D2B),
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )

                        // 1. Add External Subtitle File (VLC Action)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val p = player ?: return@clickable
                                    com.clotube.desktop.ui.utils.SubtitleFilePicker.openSubtitleDialog { path ->
                                        if (p.loadExternalSubtitle(path)) {
                                            onSelectSubtitle(-99)
                                        }
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Subtitle File",
                                tint = Color(0xFF60A5FA),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Add Subtitle File...",
                                color = Color(0xFF60A5FA),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Divider(
                            color = Color(0xFF161D2B),
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )

                        // 2. Active External Subtitle Entry (if loaded)
                        player?.externalSubtitleName?.let { extName ->
                            SeriesMenuOptionItem(
                                label = "[External] $extName",
                                isSelected = currentSubtitleId == -99,
                                onClick = {
                                    onSelectSubtitle(-99)
                                }
                            )
                        }

                        // 3. Subtitles Off Option
                        SeriesMenuOptionItem(
                            label = "Subtitles Off",
                            isSelected = currentSubtitleId == -1,
                            onClick = {
                                player?.setSubtitleTrack(-1, isOff = true)
                                onSelectSubtitle(-1)
                            }
                        )

                        // 4. Embedded Streams from Media Container
                        val spuTracks = player?.mediaPlayer?.subpictures()?.trackDescriptions() ?: emptyList()
                        spuTracks.filter { it.id() != -1 }.forEach { track ->
                            SeriesMenuOptionItem(
                                label = track.description(),
                                isSelected = currentSubtitleId == track.id(),
                                onClick = {
                                    onSelectSubtitle(track.id())
                                }
                            )
                        }
                    }

                    SeriesPlayerPopupType.AUDIO -> {
                        Text(
                            text = "AUDIO TRACK",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        Divider(
                            color = Color(0xFF161D2B),
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )

                        val audioTracks = player?.getAudioTracks() ?: emptyList()
                        if (audioTracks.isEmpty()) {
                            SeriesMenuOptionItem(
                                label = "Default Audio",
                                isSelected = true,
                                onClick = onDismiss
                            )
                        } else {
                            audioTracks.forEach { (trackId, trackDesc) ->
                                SeriesMenuOptionItem(
                                    label = trackDesc,
                                    isSelected = trackId == currentAudioId,
                                    onClick = { onSelectAudio(trackId, trackDesc) }
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
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        Divider(
                            color = Color(0xFF161D2B),
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
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
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .background(
                when {
                    isSelected -> Color(0xFF141C2B)
                    isHovered -> Color(0xFF0F1522)
                    else -> Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (isSelected) Color(0xFF60A5FA) else if (isHovered) Color(0xFFE2E8F0) else Color(0xFFCBD5E1),
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Text(
                text = "✓",
                color = Color(0xFF60A5FA),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun IsolatedSeriesPlayerTimecode(
    timeMsProvider: () -> Long,
    durationMsProvider: () -> Long,
    formatTime: (Long) -> String,
    modifier: Modifier = Modifier
) {
    val quantizedTick by remember {
        derivedStateOf { timeMsProvider().coerceAtLeast(0L) / 250L }
    }
    val formattedCurrent by remember {
        derivedStateOf {
            val currentMs = quantizedTick * 250L
            formatTime(currentMs)
        }
    }
    val formattedTotal by remember {
        derivedStateOf {
            val totalMs = durationMsProvider().coerceAtLeast(0L)
            formatTime(totalMs)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        Text(
            text = formattedCurrent,
            color = Color(0xFFE2E8F0),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            text = "/",
            color = Color(0xFF475569),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
            maxLines = 1
        )
        Text(
            text = formattedTotal,
            color = Color(0xFF64748B),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun IsolatedPlayerScrubber(
    player: DirectMemoryVideoPlayer?,
    currentEpisodeId: String = "",
    isPlayerSynced: Boolean = true,
    timeMsProvider: () -> Long,
    durationMsProvider: () -> Long,
    onScrubbingStateChange: ((Boolean) -> Unit)? = null,
    onSeek: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isScrubbing by remember(currentEpisodeId) { mutableStateOf(false) }
    var scrubProgress by remember(currentEpisodeId) { mutableFloatStateOf(0f) }
    var isTimelineHovered by remember(currentEpisodeId) { mutableStateOf(false) }
    var hoverFraction by remember(currentEpisodeId) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    val synced = isPlayerSynced && (player == null || player.boundMediaId.isEmpty() || currentEpisodeId.isEmpty() || player.boundMediaId == currentEpisodeId)

    val playbackProgress by remember(synced) {
        derivedStateOf {
            if (!synced) return@derivedStateOf 0f
            val quantizedMs = (timeMsProvider().coerceAtLeast(0L) / 250L) * 250L
            val totalMs = durationMsProvider()
            if (totalMs > 0L) (quantizedMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) else 0f
        }
    }

    val currentFraction = if (isScrubbing) scrubProgress else playbackProgress

    val trackHeight by animateDpAsState(
        targetValue = if (isTimelineHovered || isScrubbing) 6.dp else 3.5.dp,
        animationSpec = FluidMotionKit.SilkSpringDp,
        label = "scrubber_track_height"
    )

    val thumbSize by animateDpAsState(
        targetValue = when {
            isScrubbing -> 16.dp
            isTimelineHovered -> 13.dp
            else -> 9.dp
        },
        animationSpec = FluidMotionKit.SilkSpringDp,
        label = "scrubber_thumb_size"
    )

    val onValueChange: (Float) -> Unit = { fraction ->
        isScrubbing = true
        scrubProgress = fraction
        onScrubbingStateChange?.invoke(true)
    }

    val onValueChangeFinished: () -> Unit = {
        if (synced) {
            val totalDur = durationMsProvider()
            if (totalDur > 0L) {
                val targetMs = (scrubProgress * totalDur).toLong()
                if (onSeek != null) onSeek(targetMs) else player?.seekToTime(targetMs)
            }
        }
        isScrubbing = false
        onScrubbingStateChange?.invoke(false)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
    ) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val thumbRadiusPx = with(density) { (thumbSize / 2).toPx() }
        val isSeekable = synced && durationMsProvider() > 0L

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerHoverIcon(if (isSeekable) PointerIcon.Hand else PointerIcon.Default)
                .pointerInput(isSeekable) {
                    if (!isSeekable) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Enter, PointerEventType.Move -> {
                                    isTimelineHovered = true
                                    val x = event.changes.firstOrNull()?.position?.x ?: 0f
                                    hoverFraction = (x / size.width.toFloat()).coerceIn(0f, 1f)
                                }
                                PointerEventType.Exit -> {
                                    isTimelineHovered = false
                                }
                            }
                        }
                    }
                }
                .pointerInput(isSeekable) {
                    if (!isSeekable) return@pointerInput
                    detectTapGestures(
                        onPress = { offset ->
                            val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onValueChange(frac)
                            val released = tryAwaitRelease()
                            if (released) {
                                onValueChangeFinished()
                            }
                        }
                    )
                }
                .pointerInput(isSeekable) {
                    if (!isSeekable) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onValueChange(frac)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            hoverFraction = frac
                            onValueChange(frac)
                        },
                        onDragEnd = { onValueChangeFinished() },
                        onDragCancel = { onValueChangeFinished() }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            // Background Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0x33FFFFFF))
            )

            // Hover Preview Buffer Line
            if (isTimelineHovered && !isScrubbing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(hoverFraction)
                        .height(trackHeight)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x3394A3B8))
                )
            }

            // Active Progress Track
            Box(
                modifier = Modifier
                    .fillMaxWidth(currentFraction.coerceIn(0.001f, 1f))
                    .height(trackHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF1D4ED8), Color(0xFF60A5FA))
                        )
                    )
            )

            // Scrubber Thumb (Restrained Studio Ice Blue)
            Box(
                modifier = Modifier
                    .offset {
                        val xPos = (currentFraction * containerWidthPx) - thumbRadiusPx
                        IntOffset(
                            xPos.toInt().coerceIn(0, (containerWidthPx - (thumbRadiusPx * 2)).toInt().coerceAtLeast(0)),
                            0
                        )
                    }
                    .size(thumbSize)
                    .shadow(4.dp, CircleShape, spotColor = Color(0x66000000))
                    .clip(CircleShape)
                    .background(Color(0xFF60A5FA))
            )

            // Floating Magnetic Timecode Tooltip Bubble (High-contrast glass)
            if ((isTimelineHovered || isScrubbing) && durationMsProvider() > 0L) {
                val previewFraction = if (isScrubbing) currentFraction else hoverFraction
                val previewTimeMs = (previewFraction * durationMsProvider()).toLong()
                val tooltipWidthPx = with(density) { 62.dp.toPx() }
                val tooltipOffsetXPx = (previewFraction * containerWidthPx) - (tooltipWidthPx / 2f)

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = tooltipOffsetXPx.toInt().coerceIn(0, (containerWidthPx - tooltipWidthPx).toInt().coerceAtLeast(0)),
                                y = with(density) { (-28).dp.roundToPx() }
                            )
                        }
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xE60D121C))
                        .border(1.dp, Color(0xFF222B3D), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatDuration(previewTimeMs),
                        color = Color(0xFFE2E8F0),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesBottomTransportBar(
    player: DirectMemoryVideoPlayer?,
    currentEpisodeId: String = "",
    isPlayerSynced: Boolean = true,
    timeMsProvider: () -> Long,
    durationMsProvider: () -> Long,
    onScrubbingStateChange: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    isFullscreen: Boolean,
    currentSpeed: Float,
    activePopup: SeriesPlayerPopupType,
    onPreviousEpisode: (() -> Unit)?,
    onNextEpisode: (() -> Unit)?,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    onTogglePopup: (SeriesPlayerPopupType) -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // ========================================================
        // 1. FLUID INTERACTIVE TIMELINE SCRUBBER
        // ========================================================
        IsolatedPlayerScrubber(
            player = player,
            currentEpisodeId = currentEpisodeId,
            isPlayerSynced = isPlayerSynced,
            timeMsProvider = timeMsProvider,
            durationMsProvider = durationMsProvider,
            onScrubbingStateChange = onScrubbingStateChange,
            onSeek = onSeek
        )

        Spacer(modifier = Modifier.height(2.dp))

        // ========================================================
        // 2. TACTILE TRANSPORT CONTROLS ROW
        // ========================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Controls: Prev, -10s, Play/Pause, +10s, Next, Volume, Timer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FluidTransportIconButton(
                    onClick = { onPreviousEpisode?.invoke() },
                    enabled = hasPreviousEpisode && onPreviousEpisode != null,
                    icon = Icons.Default.SkipPrevious,
                    contentDescription = "Previous Episode (P)",
                    size = 32.dp,
                    iconSize = 20.dp
                )

                val seekSec = DesktopAppSettings.state.seekStepSeconds
                val stepMs = seekSec * 1000L
                FluidTransportIconButton(
                    onClick = {
                        val currentMs = timeMsProvider()
                        val totalMs = durationMsProvider()
                        if (totalMs > 0L) {
                            val newTime = (currentMs - stepMs).coerceIn(0L, totalMs)
                            onSeek(newTime)
                        }
                    },
                    icon = when (seekSec) {
                        5 -> Icons.Rounded.Replay5
                        30 -> Icons.Rounded.Replay30
                        else -> Icons.Rounded.Replay10
                    },
                    contentDescription = "Rewind ${seekSec}s",
                    size = 30.dp,
                    iconSize = 19.dp
                )

                // Prominent Play/Pause Button
                val playInteraction = remember { MutableInteractionSource() }
                val isPlayHovered by playInteraction.collectIsHoveredAsState()
                val isPlayPressed by playInteraction.collectIsPressedAsState()
                val playScale by animateFloatAsState(
                    targetValue = when {
                        isPlayPressed -> 0.88f
                        isPlayHovered -> 1.15f
                        else -> 1.0f
                    },
                    animationSpec = if (isPlayPressed) FluidMotionKit.TactilePress
                                    else FluidMotionKit.SilkSpring,
                    label = "play_btn_scale"
                )

                IconButton(
                    onClick = { player?.togglePlayPause() },
                    interactionSource = playInteraction,
                    modifier = Modifier
                        .size(34.dp)
                        .graphicsLayer {
                            scaleX = playScale
                            scaleY = playScale
                        }
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = if (player?.isPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                FluidTransportIconButton(
                    onClick = {
                        val currentMs = timeMsProvider()
                        val totalMs = durationMsProvider()
                        if (totalMs > 0L) {
                            val newTime = (currentMs + stepMs).coerceIn(0L, totalMs)
                            onSeek(newTime)
                        }
                    },
                    icon = when (seekSec) {
                        5 -> Icons.Rounded.Forward5
                        30 -> Icons.Rounded.Forward30
                        else -> Icons.Rounded.Forward10
                    },
                    contentDescription = "Forward ${seekSec}s",
                    size = 30.dp,
                    iconSize = 19.dp
                )

                FluidTransportIconButton(
                    onClick = { onNextEpisode?.invoke() },
                    enabled = hasNextEpisode && onNextEpisode != null,
                    icon = Icons.Default.SkipNext,
                    contentDescription = "Next Episode (N)",
                    size = 32.dp,
                    iconSize = 20.dp
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
                            val p = player ?: return@IconButton
                            if (p.isMuted || p.volume == 0) {
                                p.setMuted(false)
                                if (p.volume == 0) p.setVolumeLevel(100)
                            } else {
                                p.setMuted(true)
                            }
                        },
                        modifier = Modifier
                            .size(28.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            imageVector = when {
                                player == null || player.isMuted || player.volume == 0 -> Icons.AutoMirrored.Filled.VolumeMute
                                player.volume < 50 -> Icons.AutoMirrored.Filled.VolumeDown
                                else -> Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = "Volume",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    SeriesSlickVolumeSlider(
                        volume = if (player == null || player.isMuted) 0 else player.volume,
                        onVolumeChange = { player?.setVolumeLevel(it) }
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                IsolatedSeriesPlayerTimecode(
                    timeMsProvider = timeMsProvider,
                    durationMsProvider = durationMsProvider,
                    formatTime = ::formatDuration
                )
            }

            // Right Controls: Subtitles, Audio, Speed, Fullscreen
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FluidTransportIconButton(
                    onClick = { onTogglePopup(SeriesPlayerPopupType.SUBTITLES) },
                    icon = Icons.Default.ClosedCaption,
                    contentDescription = "Subtitles",
                    active = activePopup == SeriesPlayerPopupType.SUBTITLES,
                    size = 30.dp,
                    iconSize = 20.dp
                )

                FluidTransportIconButton(
                    onClick = { onTogglePopup(SeriesPlayerPopupType.AUDIO) },
                    icon = Icons.Default.Audiotrack,
                    contentDescription = "Audio Tracks",
                    active = activePopup == SeriesPlayerPopupType.AUDIO,
                    size = 30.dp,
                    iconSize = 20.dp
                )

                val seriesSpeedLabel = if (currentSpeed % 1.0f == 0f) {
                    "${currentSpeed.toInt()}.0x"
                } else {
                    "${currentSpeed}x"
                }
                val speedInteraction = remember { MutableInteractionSource() }
                val isSpeedHovered by speedInteraction.collectIsHoveredAsState()
                val isSpeedPressed by speedInteraction.collectIsPressedAsState()
                val speedScale by animateFloatAsState(
                    targetValue = when {
                        isSpeedPressed -> 0.92f
                        isSpeedHovered -> 1.08f
                        else -> 1.0f
                    },
                    animationSpec = if (isSpeedPressed) FluidMotionKit.TactilePress
                                    else FluidMotionKit.SilkSpring,
                    label = "speed_badge_scale"
                )

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF141924),
                    border = BorderStroke(1.dp, Color(0xFF222B3D)),
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = speedScale
                            scaleY = speedScale
                        }
                        .clickable(
                            interactionSource = speedInteraction,
                            indication = null
                        ) { onTogglePopup(SeriesPlayerPopupType.SPEED) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 2.dp)
                ) {
                    Text(
                        text = seriesSpeedLabel,
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                FluidTransportIconButton(
                    onClick = onToggleFullscreen,
                    icon = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = "Toggle Fullscreen",
                    size = 30.dp,
                    iconSize = 20.dp
                )
            }
        }
    }
}

/**
 * High-performance GPU-scaled transport icon button with silk spring physics.
 */
@Composable
private fun FluidTransportIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    active: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 28.dp,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            !enabled -> 1.0f
            isPressed -> 0.90f
            isHovered -> 1.14f
            else -> 1.0f
        },
        animationSpec = if (isPressed) FluidMotionKit.TactilePress
                        else FluidMotionKit.SilkSpring,
        label = "transport_btn_scale"
    )

    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> Color(0xFF475569)
                active -> Color(0xFF60A5FA)
                isHovered -> Color(0xFFF1F5F9)
                else -> Color(0xFF94A3B8)
            },
            modifier = Modifier.size(iconSize)
        )
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
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isDragging = true
                    val width = size.width.toFloat()
                    if (width > 0f) {
                        val frac = (down.position.x / width).coerceIn(0f, 1f)
                        onVolumeChange((frac * 100).roundToInt())
                    }

                    val pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed) {
                            change.consume()
                            if (width > 0f) {
                                val frac = (change.position.x / width).coerceIn(0f, 1f)
                                onVolumeChange((frac * 100).roundToInt())
                            }
                            isDragging = false
                            break
                        } else {
                            change.consume()
                            if (width > 0f) {
                                val frac = (change.position.x / width).coerceIn(0f, 1f)
                                onVolumeChange((frac * 100).roundToInt())
                            }
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
                        colors = listOf(Color(0xFF1D4ED8), Color(0xFF60A5FA))
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
                .background(Color.White)
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
