package com.clotube.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.media.LocalStreamServer
import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.screens.rememberDriveThumbnail
import kotlinx.coroutines.delay
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

@Composable
fun DesktopInlinePlayerPanel(
    media: DesktopMediaItem,
    accessToken: String,
    seriesEpisodes: List<DesktopEpisode> = emptyList(),
    activeEpisodeId: String = "",
    onEpisodeSelected: (DesktopEpisode) -> Unit = {},
    onClosePlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentTimeMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(media.durationMs.coerceAtLeast(1L)) }
    var volume by remember { mutableFloatStateOf(1f) }
    var isMuted by remember { mutableStateOf(false) }

    // Dynamic aspect ratio: initialized to 16:9, updates to exact stream aspect ratio (e.g. 2.39:1)
    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    val isReleased = remember { AtomicBoolean(false) }
    val focusRequester = remember { FocusRequester() }

    val mediaPlayerComponent = remember {
        try {
            NativeDiscovery().discover()
            val factory = MediaPlayerFactory(
                "--no-video-title-show",
                "--avcodec-hw=any",
                "--vout=direct3d11",
                "--drop-late-frames",
                "--skip-frames",
                "--network-caching=2000"
            )
            EmbeddedMediaPlayerComponent(factory, null, null, null, null).apply {
                background = java.awt.Color.BLACK
                videoSurfaceComponent().background = java.awt.Color.BLACK
            }
        } catch (e: Throwable) {
            System.err.println("[InlinePlayer] LibVLC initialization error: ${e.message}")
            null
        }
    }

    LaunchedEffect(media.id) {
        val comp = mediaPlayerComponent ?: return@LaunchedEffect
        val player = comp.mediaPlayer() ?: return@LaunchedEffect
        val surface = comp.videoSurfaceComponent() ?: return@LaunchedEffect

        if (isReleased.get()) return@LaunchedEffect

        surface.background = java.awt.Color.BLACK
        comp.background = java.awt.Color.BLACK

        val port = LocalStreamServer.start()
        val streamUrl = "http://127.0.0.1:$port/stream?id=${media.id}&token=$accessToken"
        val hasStarted = AtomicBoolean(false)

        fun startStream() {
            if (hasStarted.compareAndSet(false, true)) {
                println("[DesktopInlinePlayerPanel] Starting stream for ${media.title} (ID: ${media.id})")
                SwingUtilities.invokeLater {
                    try {
                        if (!isReleased.get()) {
                            player.media().play(
                                streamUrl,
                                ":network-caching=2500",
                                ":file-caching=1500"
                            )
                        }
                    } catch (t: Throwable) {
                        System.err.println("[InlinePlayer] Playback start error: ${t.message}")
                    }
                }
            }
        }

        if (surface.isDisplayable) {
            startStream()
        } else {
            val hierarchyListener = object : HierarchyListener {
                override fun hierarchyChanged(e: HierarchyEvent) {
                    if ((e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L && surface.isDisplayable) {
                        surface.removeHierarchyListener(this)
                        startStream()
                    }
                }
            }
            surface.addHierarchyListener(hierarchyListener)
        }

        for (i in 0 until 10) {
            if (hasStarted.get()) break
            delay(100)
            if (surface.isDisplayable) {
                startStream()
                break
            }
        }
    }

    val player = mediaPlayerComponent?.mediaPlayer()

    // Query native stream aspect ratio once playback begins
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            for (i in 0 until 20) {
                val dim = player?.video()?.videoDimension()
                if (dim != null && dim.width > 0 && dim.height > 0) {
                    val ratio = dim.width.toFloat() / dim.height.toFloat()
                    if (ratio in 0.5f..3.5f) {
                        videoAspectRatio = ratio
                        println("[InlinePlayer] Video dimension detected: ${dim.width}x${dim.height} (ratio: $ratio)")
                        break
                    }
                }
                delay(150)
            }
        }
    }

    DisposableEffect(Unit) {
        val comp = mediaPlayerComponent

        fun checkDimension(mp: MediaPlayer?) {
            val dim = mp?.video()?.videoDimension()
            if (dim != null && dim.width > 0 && dim.height > 0) {
                val ratio = dim.width.toFloat() / dim.height.toFloat()
                if (ratio in 0.5f..3.5f) {
                    videoAspectRatio = ratio
                }
            }
        }

        val listener = object : MediaPlayerEventAdapter() {
            override fun timeChanged(mediaPlayer: MediaPlayer?, newTime: Long) {
                currentTimeMs = newTime
                if (videoAspectRatio == (16f / 9f)) {
                    checkDimension(mediaPlayer)
                }
            }
            override fun lengthChanged(mediaPlayer: MediaPlayer?, newLength: Long) {
                if (newLength > 0L) totalDurationMs = newLength
            }
            override fun playing(mediaPlayer: MediaPlayer?) {
                isPlaying = true
                isBuffering = false
                checkDimension(mediaPlayer)
            }
            override fun videoOutput(mediaPlayer: MediaPlayer?, newCount: Int) {
                if (newCount > 0) {
                    checkDimension(mediaPlayer)
                }
            }
            override fun paused(mediaPlayer: MediaPlayer?) {
                isPlaying = false
            }
            override fun buffering(mediaPlayer: MediaPlayer?, newCache: Float) {
                isBuffering = newCache < 100f
            }
            override fun finished(mediaPlayer: MediaPlayer?) {
                isPlaying = false
                isBuffering = false
            }
            override fun error(mediaPlayer: MediaPlayer?) {
                System.err.println("[InlinePlayer] LibVLC playback error")
                isBuffering = false
            }
        }

        player?.events()?.addMediaPlayerEventListener(listener)

        onDispose {
            if (!isReleased.getAndSet(true)) {
                try {
                    player?.events()?.removeMediaPlayerEventListener(listener)
                    player?.controls()?.stop()
                    comp?.release()
                } catch (t: Throwable) {
                    System.err.println("[InlinePlayer] Safe teardown: ${t.message}")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.Spacebar -> {
                            if (isPlaying) player?.controls()?.pause() else player?.controls()?.play()
                            true
                        }
                        Key.DirectionLeft -> {
                            player?.controls()?.skipTime(-10000)
                            true
                        }
                        Key.DirectionRight -> {
                            player?.controls()?.skipTime(10000)
                            true
                        }
                        Key.Escape -> {
                            onClosePlayer()
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = "NOW PLAYING",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = media.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Text(
                text = "Close player",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onClosePlayer() }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(440.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // LEFT COLUMN: Video Viewport + Transport Bar
            Column(
                modifier = Modifier
                    .weight(0.67f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
                    .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(14.dp))
            ) {
                // Adaptive Aspect-Ratio Viewport: Skia fills the letterbox padding in pure black
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    val boxWidth = maxWidth.value
                    val boxHeight = maxHeight.value
                    val boxRatio = if (boxHeight > 0f) boxWidth / boxHeight else (16f / 9f)

                    // If video is wider than the container (e.g. 2.39:1), fit to width and let height shrink
                    // If video is taller than the container, fit to height and let width shrink
                    val videoModifier = if (videoAspectRatio > boxRatio) {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(videoAspectRatio)
                    } else {
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(videoAspectRatio)
                    }

                    if (mediaPlayerComponent != null) {
                        SwingPanel(
                            background = Color.Black,
                            factory = { mediaPlayerComponent },
                            modifier = videoModifier
                        )
                    }

                    if (isBuffering) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF00E5FF), modifier = Modifier.size(42.dp))
                        }
                    }
                }

                InlineTransportBar(
                    isPlaying = isPlaying,
                    currentTimeMs = currentTimeMs,
                    totalDurationMs = totalDurationMs,
                    volume = volume,
                    isMuted = isMuted,
                    onPlayPause = {
                        if (isPlaying) player?.controls()?.pause() else player?.controls()?.play()
                    },
                    onSkip = { deltaMs -> player?.controls()?.skipTime(deltaMs) },
                    onSeek = { targetMs ->
                        player?.controls()?.setTime(targetMs)
                        currentTimeMs = targetMs
                    },
                    onToggleMute = {
                        isMuted = !isMuted
                        player?.audio()?.isMute = isMuted
                    },
                    onVolumeChange = { newVol ->
                        volume = newVol
                        isMuted = false
                        player?.audio()?.setVolume((newVol * 100).toInt())
                    }
                )
            }

            // RIGHT COLUMN: Episode List or Movie Metadata Card
            Surface(
                modifier = Modifier
                    .weight(0.33f)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF0C1018),
                border = BorderStroke(1.dp, Color(0xFF18202F))
            ) {
                if (seriesEpisodes.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "EPISODE LIST",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "${seriesEpisodes.size} episodes",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF131824),
                                border = BorderStroke(1.dp, Color(0xFF263346))
                            ) {
                                Text(
                                    text = "Season 1 ⌄",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Color(0xFF161E2E), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(10.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seriesEpisodes, key = { it.id }) { ep ->
                                val isSelected = ep.id == (activeEpisodeId.ifBlank { media.id })
                                val thumb = rememberDriveThumbnail(ep.thumbnailUrl)

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onEpisodeSelected(ep) }
                                        .pointerHoverIcon(PointerIcon.Hand),
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) Color(0xFF131E30) else Color(0xFF0F141F),
                                    border = if (isSelected) BorderStroke(1.dp, Color(0xFF2563EB)) else BorderStroke(1.dp, Color(0xFF161E2E))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(76.dp)
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF161E2E)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (thumb != null) {
                                                Image(
                                                    bitmap = thumb,
                                                    contentDescription = ep.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF2563EB)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(13.dp))
                                                }
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "EPISODE ${ep.episodeNumber}",
                                                color = Color(0xFF38BDF8),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = ep.title,
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${ep.durationFormatted} • Direct Stream",
                                                color = Color(0xFF64748B),
                                                fontSize = 9.sp
                                            )
                                        }

                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Rounded.GraphicEq,
                                                contentDescription = null,
                                                tint = Color(0xFF00E5FF),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "NOW PLAYING",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = media.title,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = media.genre,
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Streaming directly from Google Drive in master quality via hardware-accelerated playback.",
                                color = Color(0xFF64748B),
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF101622),
                            border = BorderStroke(1.dp, Color(0xFF1A2333))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Rounded.CloudDone, null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                                    Text("Direct Stream", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                                Text("Active", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineTransportBar(
    isPlaying: Boolean,
    currentTimeMs: Long,
    totalDurationMs: Long,
    volume: Float,
    isMuted: Boolean,
    onPlayPause: () -> Unit,
    onSkip: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(Color(0xFF0C1018))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        IconButton(
            onClick = { onSkip(-10000) },
            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(
                imageVector = Icons.Rounded.Replay10,
                contentDescription = "Rewind 10s",
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(19.dp)
            )
        }

        IconButton(
            onClick = { onSkip(10000) },
            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(
                imageVector = Icons.Rounded.Forward10,
                contentDescription = "Forward 10s",
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(19.dp)
            )
        }

        Slider(
            value = if (totalDurationMs > 0L) (currentTimeMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) else 0f,
            onValueChange = { fraction ->
                val target = (fraction * totalDurationMs).toLong()
                onSeek(target)
            },
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF00E5FF),
                activeTrackColor = Color(0xFF2563EB),
                inactiveTrackColor = Color(0xFF1E2838)
            ),
            modifier = Modifier.weight(1f).height(18.dp).pointerHoverIcon(PointerIcon.Hand)
        )

        Text(
            text = "${formatTimecode(currentTimeMs)} / ${formatTimecode(totalDurationMs)}",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )

        IconButton(
            onClick = onToggleMute,
            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(
                imageVector = if (isMuted || volume == 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = "Mute",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }

        Slider(
            value = if (isMuted) 0f else volume,
            onValueChange = onVolumeChange,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF00E5FF),
                inactiveTrackColor = Color(0xFF1E2838)
            ),
            modifier = Modifier.width(76.dp).pointerHoverIcon(PointerIcon.Hand)
        )
    }
}

private fun formatTimecode(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
