package com.clotube.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.clotube.desktop.data.DesktopWatchHistoryManager
import com.clotube.desktop.data.DesktopWatchHistorySync
import com.clotube.desktop.media.DirectMemoryVideoPlayer
import com.clotube.desktop.media.LocalStreamServer
import com.clotube.desktop.model.DesktopMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Cursor
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

data class DesktopMovieItem(
    val id: String,
    val title: String,
    val durationFormatted: String = "2h 19m",
    val genres: String = "Action • Thriller",
    val thumbnailUrl: String? = null,
    val streamUrl: String = ""
)

enum class PlayerPopupType {
    NONE, SUBTITLES, AUDIO, SPEED
}

enum class SeekFeedbackDirection {
    REWIND, FORWARD
}

@Composable
fun DesktopMoviePlayerScreen(
    initialMovie: DesktopMovieItem,
    allMovies: List<DesktopMovieItem> = emptyList(),
    userEmail: String = "",
    accessToken: String = "",
    initialSeekMs: Long = 0L,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onBack: () -> Unit
) {
    var currentPlayingMovie by remember(initialMovie.id) { mutableStateOf(initialMovie) }

    val effectiveInitialSeekMs = remember(currentPlayingMovie.id, userEmail) {
        if (currentPlayingMovie.id == initialMovie.id) {
            initialSeekMs
        } else if (userEmail.isNotBlank()) {
            DesktopWatchHistoryManager.getPlaybackPosition(userEmail, currentPlayingMovie.id)
        } else {
            0L
        }
    }

    val player = remember(currentPlayingMovie.id, currentPlayingMovie.streamUrl) {
        DirectMemoryVideoPlayer(
            currentPlayingMovie.streamUrl.ifBlank {
                "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
            }
        )
    }

    val hasAppliedInitialSeek = remember(currentPlayingMovie.id) { AtomicBoolean(false) }

    LaunchedEffect(player.isPlaying, player.isBuffering, effectiveInitialSeekMs) {
        if (player.currentTimeMs >= 2500L) {
            hasAppliedInitialSeek.set(true)
            return@LaunchedEffect
        }

        if (player.isPlaying && !player.isBuffering && effectiveInitialSeekMs > 1000L && hasAppliedInitialSeek.compareAndSet(false, true)) {
            delay(150L)
            if (player.currentTimeMs < 2500L) {
                player.seekToTime(effectiveInitialSeekMs)
                println("[DesktopMoviePlayerScreen] Resumed ${currentPlayingMovie.title} at ${effectiveInitialSeekMs}ms")
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()

    val saveMovieProgress = {
        val curTime = player.currentTimeMs
        val totalDur = player.totalDurationMs
        val currentMovie = currentPlayingMovie
        if (curTime > 0L && userEmail.isNotBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    DesktopWatchHistoryManager.recordPlaybackProgress(
                        email = userEmail,
                        fileId = currentMovie.id,
                        title = currentMovie.title,
                        positionMs = curTime,
                        durationMs = totalDur,
                        isSeries = false
                    )
                    if (accessToken.isNotBlank()) {
                        DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken)
                    }
                } catch (t: Throwable) {
                    System.err.println("[DesktopMoviePlayerScreen] Failed to save watch progress: ${t.message}")
                }
            }
        }
    }

    LaunchedEffect(player.isPlaying, userEmail, currentPlayingMovie.id) {
        if (player.isPlaying && userEmail.isNotBlank()) {
            while (isActive) {
                delay(5000L)
                saveMovieProgress()
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            saveMovieProgress()
            player.release()
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var activePopup by remember { mutableStateOf(PlayerPopupType.NONE) }
    var seekFeedbackDirection by remember { mutableStateOf<SeekFeedbackDirection?>(null) }
    var seekFeedbackTrigger by remember { mutableIntStateOf(0) }

    // Auto-dismiss seek feedback badge after 650ms
    LaunchedEffect(seekFeedbackTrigger) {
        if (seekFeedbackDirection != null) {
            delay(650L)
            seekFeedbackDirection = null
        }
    }

    // Active track state management
    var currentSubtitleId by remember { mutableIntStateOf(-1) }
    var currentAudioId by remember { mutableIntStateOf(-1) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }

    // Synchronize LibVLC track selection
    LaunchedEffect(player.isFirstFrameRendered) {
        if (player.isFirstFrameRendered) {
            currentSubtitleId = player.mediaPlayer.subpictures().track()
            currentAudioId = player.mediaPlayer.audio().track()
        }
    }

    // Auto-hide controls timer (paused if a menu is open or scrubbing)
    LaunchedEffect(controlsVisible, player.isPlaying, isScrubbing, activePopup) {
        if (controlsVisible && player.isPlaying && !isScrubbing && activePopup == PlayerPopupType.NONE) {
            delay(3500L)
            controlsVisible = false
        }
    }

    // =========================================================================
    // INVISIBLE CURSOR ARCHITECTURE
    // =========================================================================
    val invisibleCursor = remember {
        val transparentImg = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        Toolkit.getDefaultToolkit().createCustomCursor(transparentImg, Point(0, 0), "invisibleCursor")
    }
    val defaultCursor = remember { Cursor.getDefaultCursor() }
    val hiddenPointerIcon = remember(invisibleCursor) { PointerIcon(invisibleCursor) }

    val isCursorHidden = !controlsVisible && player.isPlaying && !isScrubbing && activePopup == PlayerPopupType.NONE

    // Instantly sync the AWT window cursor when controls toggle
    LaunchedEffect(isCursorHidden) {
        try {
            val activeWin = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            activeWin?.cursor = if (isCursorHidden) invisibleCursor else defaultCursor
        } catch (_: Throwable) {}
    }

    // Ensure cursor is restored when leaving the video player screen
    DisposableEffect(Unit) {
        onDispose {
            try {
                val activeWin = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                activeWin?.cursor = defaultCursor
            } catch (_: Throwable) {}
        }
    }

    // =========================================================================
    // BULLETPROOF AWT KEY EVENT DISPATCHER (Immune to Compose focus loss)
    // =========================================================================
    DisposableEffect(player, isFullscreen, activePopup) {
        val dispatcher = KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_PRESSED) {
                // Any key interaction makes controls and cursor visible
                controlsVisible = true
                try {
                    val activeWin = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                    activeWin?.cursor = defaultCursor
                } catch (_: Throwable) {}

                when (e.keyCode) {
                    KeyEvent.VK_LEFT -> {
                        val newTime = (player.currentTimeMs - 10000L).coerceAtLeast(0L)
                        player.seekToTime(newTime)
                        seekFeedbackDirection = SeekFeedbackDirection.REWIND
                        seekFeedbackTrigger++
                        true
                    }
                    KeyEvent.VK_RIGHT -> {
                        val newTime = (player.currentTimeMs + 10000L).coerceAtMost(player.totalDurationMs)
                        player.seekToTime(newTime)
                        seekFeedbackDirection = SeekFeedbackDirection.FORWARD
                        seekFeedbackTrigger++
                        true
                    }
                    KeyEvent.VK_SPACE -> {
                        player.togglePlayPause()
                        true
                    }
                    KeyEvent.VK_F -> {
                        onToggleFullscreen()
                        true
                    }
                    KeyEvent.VK_ESCAPE -> {
                        if (activePopup != PlayerPopupType.NONE) {
                            activePopup = PlayerPopupType.NONE
                            true
                        } else if (isFullscreen) {
                            onToggleFullscreen()
                            true
                        } else {
                            saveMovieProgress()
                            player.release()
                            onBack()
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

    val activeFraction = if (isScrubbing) {
        scrubFraction
    } else if (player.totalDurationMs > 0L) {
        (player.currentTimeMs.toFloat() / player.totalDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val displayTimeMs = if (isScrubbing) (scrubFraction * player.totalDurationMs).toLong() else player.currentTimeMs

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07090E))
            .pointerHoverIcon(if (isCursorHidden) hiddenPointerIcon else PointerIcon.Default)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Press) {
                            controlsVisible = true
                            try {
                                val activeWin = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                                activeWin?.cursor = defaultCursor
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
    ) {
        if (isFullscreen) {
            // ========================================================
            // FULLSCREEN CINEMA VIEW
            // ========================================================
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                val tick = player.frameTick
                val frame = tick.let { player.currentFrame }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { onToggleFullscreen() },
                                onTap = {
                                    if (activePopup != PlayerPopupType.NONE) {
                                        activePopup = PlayerPopupType.NONE
                                    } else {
                                        controlsVisible = !controlsVisible
                                    }
                                }
                            )
                        }
                ) {
                    if (frame != null) {
                        val scale = min(
                            size.width / frame.width.toFloat(),
                            size.height / frame.height.toFloat()
                        )
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

                if (!player.isFirstFrameRendered || player.isBuffering) {
                    CircularProgressIndicator(
                        color = Color(0xFF42A5F5),
                        modifier = Modifier.size(52.dp).align(Alignment.Center)
                    )
                }

                // Left (-10) Indicator
                SeekFeedbackIndicator(
                    direction = SeekFeedbackDirection.REWIND,
                    visible = seekFeedbackDirection == SeekFeedbackDirection.REWIND,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 48.dp)
                )

                // Right (+10) Indicator
                SeekFeedbackIndicator(
                    direction = SeekFeedbackDirection.FORWARD,
                    visible = seekFeedbackDirection == SeekFeedbackDirection.FORWARD,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 48.dp)
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
                                        Color.Black.copy(alpha = 0.7f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.85f)
                                    )
                                )
                            )
                    ) {
                        // Top Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 20.dp)
                                .align(Alignment.TopCenter),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onToggleFullscreen,
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    .pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit Fullscreen", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = currentPlayingMovie.title,
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Bottom Transport Bar
                        BottomTransportBar(
                            player = player,
                            activeFraction = activeFraction,
                            displayTimeMs = displayTimeMs,
                            isScrubbing = isScrubbing,
                            isFullscreen = true,
                            currentSpeed = currentSpeed,
                            activePopup = activePopup,
                            onTogglePopup = { popup ->
                                if (popup == PlayerPopupType.SUBTITLES) {
                                    currentSubtitleId = player.mediaPlayer.subpictures().track()
                                } else if (popup == PlayerPopupType.AUDIO) {
                                    currentAudioId = player.mediaPlayer.audio().track()
                                }
                                activePopup = if (activePopup == popup) PlayerPopupType.NONE else popup
                            },
                            onScrubStart = { isScrubbing = true; scrubFraction = it },
                            onScrubbing = { scrubFraction = it },
                            onScrubEnd = { frac ->
                                val totalDur = if (player.totalDurationMs > 0L) player.totalDurationMs else player.mediaPlayer.status().length()
                                val targetMs = (frac * totalDur).toLong()
                                player.seekToTime(targetMs)
                                isScrubbing = false
                            },
                            onToggleFullscreen = onToggleFullscreen,
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 24.dp, vertical = 18.dp)
                        )
                    }
                }

                // In-Player Floating Settings Panel (Inside Viewport, Highest Z-Order)
                InPlayerSettingsPopup(
                    activePopup = activePopup,
                    player = player,
                    currentSubtitleId = currentSubtitleId,
                    currentAudioId = currentAudioId,
                    currentSpeed = currentSpeed,
                    onSelectSubtitle = { id ->
                        currentSubtitleId = id
                        player.mediaPlayer.subpictures().setTrack(id)
                        activePopup = PlayerPopupType.NONE
                    },
                    onSelectAudio = { id ->
                        currentAudioId = id
                        player.mediaPlayer.audio().setTrack(id)
                        activePopup = PlayerPopupType.NONE
                    },
                    onSelectSpeed = { speed ->
                        currentSpeed = speed
                        player.mediaPlayer.controls().setRate(speed)
                        activePopup = PlayerPopupType.NONE
                    },
                    onDismiss = { activePopup = PlayerPopupType.NONE },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 68.dp, end = 24.dp)
                )
            }
        } else {
            // ========================================================
            // NORMAL VIEW (2-COLUMN SPLIT CINEMA LAYOUT)
            // ========================================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 18.dp)
            ) {
                // Top "Back to Collection" Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                saveMovieProgress()
                                player.release()
                                onBack()
                            }
                        )
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(bottom = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Back to Collection",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    // LEFT COLUMN: Viewport + Controls + Metadata
                    Column(
                        modifier = Modifier
                            .weight(0.68f)
                            .fillMaxHeight()
                    ) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            backgroundColor = Color.Black,
                            elevation = 12.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9.5f)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                val tick = player.frameTick
                                val frame = tick.let { player.currentFrame }

                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onDoubleTap = { onToggleFullscreen() },
                                                onTap = {
                                                    if (activePopup != PlayerPopupType.NONE) {
                                                        activePopup = PlayerPopupType.NONE
                                                    }
                                                }
                                            )
                                        }
                                ) {
                                    if (frame != null) {
                                        val scale = min(
                                            size.width / frame.width.toFloat(),
                                            size.height / frame.height.toFloat()
                                        )
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

                                if (!player.isFirstFrameRendered || player.isBuffering) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF42A5F5),
                                        modifier = Modifier.size(46.dp).align(Alignment.Center)
                                    )
                                }

                                // Left (-10) Indicator
                                SeekFeedbackIndicator(
                                    direction = SeekFeedbackDirection.REWIND,
                                    visible = seekFeedbackDirection == SeekFeedbackDirection.REWIND,
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .padding(start = 36.dp)
                                )

                                // Right (+10) Indicator
                                SeekFeedbackIndicator(
                                    direction = SeekFeedbackDirection.FORWARD,
                                    visible = seekFeedbackDirection == SeekFeedbackDirection.FORWARD,
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(end = 36.dp)
                                )

                                // Docked Bottom Transport Controls
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.BottomCenter)
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(Color.Transparent, Color(0xCC05070B), Color(0xF205070B))
                                            )
                                        )
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    BottomTransportBar(
                                        player = player,
                                        activeFraction = activeFraction,
                                        displayTimeMs = displayTimeMs,
                                        isScrubbing = isScrubbing,
                                        isFullscreen = false,
                                        currentSpeed = currentSpeed,
                                        activePopup = activePopup,
                                        onTogglePopup = { popup ->
                                            if (popup == PlayerPopupType.SUBTITLES) {
                                                currentSubtitleId = player.mediaPlayer.subpictures().track()
                                            } else if (popup == PlayerPopupType.AUDIO) {
                                                currentAudioId = player.mediaPlayer.audio().track()
                                            }
                                            activePopup = if (activePopup == popup) PlayerPopupType.NONE else popup
                                        },
                                        onScrubStart = { isScrubbing = true; scrubFraction = it },
                                        onScrubbing = { scrubFraction = it },
                                        onScrubEnd = { frac ->
                                            val totalDur = if (player.totalDurationMs > 0L) player.totalDurationMs else player.mediaPlayer.status().length()
                                            val targetMs = (frac * totalDur).toLong()
                                            player.seekToTime(targetMs)
                                            isScrubbing = false
                                        },
                                        onToggleFullscreen = onToggleFullscreen,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                // IN-PLAYER FLOATING SETTINGS MENU (Renders strictly inside the 16:9.5 viewport above transport bar)
                                InPlayerSettingsPopup(
                                    activePopup = activePopup,
                                    player = player,
                                    currentSubtitleId = currentSubtitleId,
                                    currentAudioId = currentAudioId,
                                    currentSpeed = currentSpeed,
                                    onSelectSubtitle = { id ->
                                        currentSubtitleId = id
                                        player.mediaPlayer.subpictures().setTrack(id)
                                        activePopup = PlayerPopupType.NONE
                                    },
                                    onSelectAudio = { id ->
                                        currentAudioId = id
                                        player.mediaPlayer.audio().setTrack(id)
                                        activePopup = PlayerPopupType.NONE
                                    },
                                    onSelectSpeed = { speed ->
                                        currentSpeed = speed
                                        player.mediaPlayer.controls().setRate(speed)
                                        activePopup = PlayerPopupType.NONE
                                    },
                                    onDismiss = { activePopup = PlayerPopupType.NONE },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(bottom = 58.dp, end = 16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // ========================================================
                        // CLEAN MOVIE DETAILS SECTION (BUTTONS REMOVED)
                        // ========================================================
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "NOW PLAYING",
                                color = Color(0xFF42A5F5),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = currentPlayingMovie.title,
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${currentPlayingMovie.durationFormatted}  •  ${currentPlayingMovie.genres}",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // RIGHT COLUMN: "Movies in the Drive" Playlist
                    Column(
                        modifier = Modifier
                            .weight(0.32f)
                            .fillMaxHeight()
                    ) {
                        Text(
                            text = "COLLECTION",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Movies in the Drive",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${allMovies.size} titles",
                                color = Color(0xFF64748B),
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(allMovies) { movie ->
                                val isSelected = movie.id == currentPlayingMovie.id
                                Card(
                                    shape = RoundedCornerShape(8.dp),
                                    backgroundColor = if (isSelected) Color(0xFF132238) else Color(0xFF0E131E),
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (isSelected) Color(0xFF42A5F5) else Color(0xFF1E2638)
                                    ),
                                    elevation = if (isSelected) 4.dp else 0.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (movie.id != currentPlayingMovie.id) {
                                                currentPlayingMovie = movie
                                            }
                                        }
                                        .pointerHoverIcon(PointerIcon.Hand)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Card(
                                            shape = RoundedCornerShape(6.dp),
                                            backgroundColor = Color.Black,
                                            modifier = Modifier
                                                .width(72.dp)
                                                .height(46.dp)
                                        ) {
                                            if (!movie.thumbnailUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = movie.thumbnailUrl,
                                                    contentDescription = movie.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Movie,
                                                        contentDescription = null,
                                                        tint = Color(0xFF475569),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = movie.title,
                                                color = if (isSelected) Color(0xFF42A5F5) else Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${movie.genres}  • ${movie.durationFormatted}",
                                                color = Color(0xFF94A3B8),
                                                fontSize = 11.sp,
                                                maxLines = 1
                                            )
                                        }

                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Playing",
                                                tint = Color(0xFF42A5F5),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ========================================================
// SEEK FEEDBACK BADGE (-10s / +10s CINEMA GLASS OVERLAY)
// ========================================================
@Composable
private fun SeekFeedbackIndicator(
    direction: SeekFeedbackDirection,
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
            border = BorderStroke(1.dp, Color(0x6600E5FF)),
            elevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .size(76.dp)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (direction == SeekFeedbackDirection.REWIND) Icons.Default.FastRewind else Icons.Default.FastForward,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (direction == SeekFeedbackDirection.REWIND) "-10" else "+10",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ========================================================
// IN-PLAYER FLOATING SETTINGS MENU (UPWARD POPUP REPLACEMENT)
// ========================================================
@Composable
private fun InPlayerSettingsPopup(
    activePopup: PlayerPopupType,
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
    // 1. Content buffer to ensure full layout renders throughout the funnel-down animation
    var cachedPopupType by remember { mutableStateOf(PlayerPopupType.SUBTITLES) }
    if (activePopup != PlayerPopupType.NONE) {
        cachedPopupType = activePopup
    }
    val displayType = if (activePopup != PlayerPopupType.NONE) activePopup else cachedPopupType

    // 2. Dynamic horizontal anchor matching the physical location of each transport button
    val transformOrigin = remember(displayType) {
        when (displayType) {
            PlayerPopupType.SUBTITLES -> TransformOrigin(0.32f, 1.0f)
            PlayerPopupType.AUDIO     -> TransformOrigin(0.58f, 1.0f)
            PlayerPopupType.SPEED     -> TransformOrigin(0.82f, 1.0f)
            PlayerPopupType.NONE      -> TransformOrigin(0.82f, 1.0f)
        }
    }

    // 3. Fluid Pop-Up & Funnel-Down Animation
    AnimatedVisibility(
        visible = activePopup != PlayerPopupType.NONE,
        enter = scaleIn(
            initialScale = 0.12f,
            transformOrigin = transformOrigin,
            animationSpec = tween(
                durationMillis = 240,
                easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
            )
        ) + fadeIn(
            animationSpec = tween(durationMillis = 160)
        ),
        exit = scaleOut(
            targetScale = 0.08f,
            transformOrigin = transformOrigin,
            animationSpec = tween(
                durationMillis = 190,
                easing = FastOutLinearInEasing
            )
        ) + fadeOut(
            animationSpec = tween(durationMillis = 150)
        ),
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
                ) { /* Consume clicks to prevent dismissing overlay */ }
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                when (displayType) {
                    PlayerPopupType.SUBTITLES -> {
                        Text(
                            text = "SUBTITLES",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )

                        MenuOptionItem(
                            label = "Subtitles Off",
                            isSelected = currentSubtitleId == -1,
                            onClick = { onSelectSubtitle(-1) }
                        )

                        val spuTracks = player.mediaPlayer.subpictures().trackDescriptions() ?: emptyList()
                        spuTracks.filter { it.id() != -1 }.forEach { track ->
                            MenuOptionItem(
                                label = track.description(),
                                isSelected = track.id() == currentSubtitleId,
                                onClick = { onSelectSubtitle(track.id()) }
                            )
                        }
                    }

                    PlayerPopupType.AUDIO -> {
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
                            MenuOptionItem(
                                label = "Default Audio",
                                isSelected = true,
                                onClick = onDismiss
                            )
                        } else {
                            audioTracks.forEach { track ->
                                MenuOptionItem(
                                    label = track.description(),
                                    isSelected = track.id() == currentAudioId,
                                    onClick = { onSelectAudio(track.id()) }
                                )
                            }
                        }
                    }

                    PlayerPopupType.SPEED -> {
                        Text(
                            text = "PLAYBACK SPEED",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )

                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                            MenuOptionItem(
                                label = "${speed}x" + if (speed == 1.0f) " (Normal)" else "",
                                isSelected = currentSpeed == speed,
                                onClick = { onSelectSpeed(speed) }
                            )
                        }
                    }

                    PlayerPopupType.NONE -> {}
                }
            }
        }
    }
}

@Composable
private fun MenuOptionItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
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

// ========================================================
// SLICK SLIDERS & TRANSPORT BAR
// ========================================================
@Composable
fun SlickVolumeSlider(
    volume: Int,
    onVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    val volumeFraction = (volume / 100f).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .width(80.dp)
            .height(26.dp)
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

        if (volumeFraction > 0f) {
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
        }

        val thumbSize = if (isDragging) 12.dp else 10.dp
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

@Composable
private fun BottomTransportBar(
    player: DirectMemoryVideoPlayer,
    activeFraction: Float,
    displayTimeMs: Long,
    isScrubbing: Boolean,
    isFullscreen: Boolean,
    currentSpeed: Float,
    activePopup: PlayerPopupType,
    onTogglePopup: (PlayerPopupType) -> Unit,
    onScrubStart: (Float) -> Unit,
    onScrubbing: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Scrubber Bar
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val containerWidthPx = with(density) { maxWidth.toPx() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val width = size.width.toFloat()
                            if (width <= 0f) return@awaitEachGesture

                            var lastFrac = (down.position.x / width).coerceIn(0f, 1f)
                            onScrubStart(lastFrac)

                            val pointerId = down.id
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    onScrubEnd(lastFrac)
                                    break
                                } else {
                                    change.consume()
                                    lastFrac = (change.position.x / width).coerceIn(0f, 1f)
                                    onScrubbing(lastFrac)
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

                if (activeFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(activeFraction)
                            .height(3.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF0D47A1), Color(0xFF42A5F5))
                                )
                            )
                    )
                }

                val thumbSize = if (isScrubbing) 15.dp else 12.dp
                Box(
                    modifier = Modifier
                        .offset {
                            val xPos = (activeFraction * containerWidthPx) - with(density) { (thumbSize / 2).toPx() }
                            IntOffset(xPos.roundToInt().coerceAtLeast(0), 0)
                        }
                        .size(thumbSize)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color(0xFF42A5F5))
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Transport Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Controls: [Play/Pause] -> [Volume Group] -> [Timer Block]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(
                    onClick = { player.togglePlayPause() },
                    modifier = Modifier
                        .size(32.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (player.isMuted || player.volume == 0) {
                                player.setMuted(false)
                                if (player.volume == 0) player.setVolumeLevel(100)
                            } else {
                                player.setMuted(true)
                            }
                        },
                        modifier = Modifier
                            .size(28.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            imageVector = when {
                                player.isMuted || player.volume == 0 -> Icons.AutoMirrored.Filled.VolumeMute
                                player.volume < 50 -> Icons.AutoMirrored.Filled.VolumeDown
                                else -> Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = "Volume",
                            tint = Color(0xFFCBD5E1),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    SlickVolumeSlider(
                        volume = if (player.isMuted) 0 else player.volume,
                        onVolumeChange = { newVol -> player.setVolumeLevel(newVol) },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "${formatDuration(displayTimeMs)} / ${formatDuration(player.totalDurationMs)}",
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }

            // Right Controls: [Caption] -> [Audio Track] -> [Speed] -> [Fullscreen]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Caption / Subtitles Button
                IconButton(
                    onClick = { onTogglePopup(PlayerPopupType.SUBTITLES) },
                    modifier = Modifier
                        .size(30.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Default.ClosedCaption,
                        contentDescription = "Subtitles",
                        tint = if (activePopup == PlayerPopupType.SUBTITLES) Color(0xFF42A5F5) else Color(0xFFCBD5E1),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 2. Audio Track Button
                IconButton(
                    onClick = { onTogglePopup(PlayerPopupType.AUDIO) },
                    modifier = Modifier
                        .size(30.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Default.Audiotrack,
                        contentDescription = "Audio Tracks",
                        tint = if (activePopup == PlayerPopupType.AUDIO) Color(0xFF42A5F5) else Color(0xFFCBD5E1),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 3. Playback Speed Button / Chip
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (activePopup == PlayerPopupType.SPEED) Color(0xFF1E293B) else Color(0x33FFFFFF),
                    modifier = Modifier
                        .clickable { onTogglePopup(PlayerPopupType.SPEED) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 2.dp)
                ) {
                    Text(
                        text = "${currentSpeed}x",
                        color = if (activePopup == PlayerPopupType.SPEED) Color(0xFF42A5F5) else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                // 4. Fullscreen Toggle
                IconButton(
                    onClick = onToggleFullscreen,
                    modifier = Modifier
                        .size(30.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
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

// ========================================================
// DESKTOP MEDIA ITEM OVERLOAD (FOR HOMESCREEN & NAVIGATION)
// ========================================================
@Composable
fun DesktopMoviePlayerScreen(
    initialMovie: DesktopMediaItem,
    allMovies: List<DesktopMediaItem> = emptyList(),
    accessToken: String,
    userEmail: String = "",
    initialSeekMs: Long = 0L,
    onBack: () -> Unit,
    isFullscreen: Boolean = false,
    onFullscreenToggle: ((Boolean) -> Unit)? = null,
    onToggleFullscreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val port = remember { LocalStreamServer.start() }

    fun DesktopMediaItem.toMovieItem(): DesktopMovieItem {
        val totalSec = (durationMs / 1000).coerceAtLeast(0)
        val hrs = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val durFormatted = if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
        val genresFormatted = genre.ifBlank { "Movie • Cinema" }
        val streamUrl = "http://127.0.0.1:$port/stream?id=$id&token=$accessToken"
        return DesktopMovieItem(
            id = id,
            title = title,
            durationFormatted = durFormatted,
            genres = genresFormatted,
            thumbnailUrl = thumbnailUrl,
            streamUrl = streamUrl
        )
    }

    val convertedInitial = remember(initialMovie.id, accessToken, port) {
        initialMovie.toMovieItem()
    }
    val convertedAll = remember(allMovies, accessToken, port) {
        allMovies.map { it.toMovieItem() }
    }

    DesktopMoviePlayerScreen(
        initialMovie = convertedInitial,
        allMovies = convertedAll,
        userEmail = userEmail,
        accessToken = accessToken,
        initialSeekMs = initialSeekMs,
        isFullscreen = isFullscreen,
        onToggleFullscreen = onToggleFullscreen ?: {
            if (onFullscreenToggle != null) {
                onFullscreenToggle(!isFullscreen)
            }
        },
        onBack = onBack
    )
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
