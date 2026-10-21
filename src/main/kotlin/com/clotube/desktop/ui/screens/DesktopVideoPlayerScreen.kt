package com.clotube.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.media.LocalStreamServer
import com.clotube.desktop.model.DesktopMediaItem
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.SwingUtilities

@Composable
fun DesktopVideoPlayerScreen(
    media: DesktopMediaItem,
    accessToken: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLibVlcAvailable by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentTimeMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(media.durationMs.coerceAtLeast(1L)) }
    var volume by remember { mutableFloatStateOf(1f) }
    var isMuted by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val mediaPlayerComponent = remember {
        try {
            NativeDiscovery().discover()
            EmbeddedMediaPlayerComponent()
        } catch (e: Throwable) {
            System.err.println("LibVLC Component error: ${e.message}")
            isLibVlcAvailable = false
            null
        }
    }

    DisposableEffect(media.id) {
        val component = mediaPlayerComponent
        val player = component?.mediaPlayer()
        val videoSurface = component?.videoSurfaceComponent()

        if (component != null && player != null && videoSurface != null) {
            val listener = object : MediaPlayerEventAdapter() {
                override fun timeChanged(mediaPlayer: MediaPlayer?, newTime: Long) {
                    currentTimeMs = newTime
                }

                override fun lengthChanged(mediaPlayer: MediaPlayer?, newLength: Long) {
                    if (newLength > 0L) totalDurationMs = newLength
                }

                override fun playing(mediaPlayer: MediaPlayer?) {
                    isPlaying = true
                    isBuffering = false
                }

                override fun paused(mediaPlayer: MediaPlayer?) {
                    isPlaying = false
                }

                override fun buffering(mediaPlayer: MediaPlayer?, newCache: Float) {
                    isBuffering = newCache < 100f
                }

                override fun finished(mediaPlayer: MediaPlayer?) {
                    isPlaying = false
                }

                override fun error(mediaPlayer: MediaPlayer?) {
                    isBuffering = false
                    System.err.println("LibVLC playback error for file: ${media.id}")
                }
            }

            player.events().addMediaPlayerEventListener(listener)

            val port = LocalStreamServer.start()
            val streamUrl = "http://127.0.0.1:$port/stream?id=${media.id}&token=$accessToken"

            fun startPlayback() {
                SwingUtilities.invokeLater {
                    try {
                        player.media().play(
                            streamUrl,
                            ":network-caching=3000",
                            ":file-caching=2000"
                        )
                    } catch (t: Throwable) {
                        System.err.println("Error initiating playback: ${t.message}")
                    }
                }
            }

            var hierarchyListener: HierarchyListener? = null

            if (videoSurface.isDisplayable) {
                startPlayback()
            } else {
                hierarchyListener = object : HierarchyListener {
                    override fun hierarchyChanged(e: HierarchyEvent) {
                        if ((e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L && videoSurface.isDisplayable) {
                            videoSurface.removeHierarchyListener(this)
                            startPlayback()
                        }
                    }
                }
                videoSurface.addHierarchyListener(hierarchyListener)
            }

            onDispose {
                try {
                    hierarchyListener?.let { videoSurface.removeHierarchyListener(it) }
                    player.events().removeMediaPlayerEventListener(listener)
                    component.release()
                } catch (t: Throwable) {
                    System.err.println("Safe LibVLC teardown: ${t.message}")
                }
            }
        } else {
            onDispose { }
        }
    }

    if (!isLibVlcAvailable || mediaPlayerComponent == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF07090E)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "LibVLC Runtime Not Found",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Please install 64-bit VLC Media Player from videolan.org to enable hardware video acceleration.",
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Return to Library", color = Color.White)
                }
            }
        }
        return
    }

    val player = mediaPlayerComponent.mediaPlayer()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF080B11))
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.Spacebar -> {
                            player?.controls()?.pause()
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
                            onClose()
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // ========================================================
        // 1. TOP CINEMA HEADER BAR
        // ========================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(Color(0xFF0D121C))
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E2838))
                        .pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = media.title,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Direct Stream • 64-bit Hardware Decoding",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF131D2D),
                border = BorderStroke(1.dp, Color(0xFF1E3A5F))
            ) {
                Text(
                    text = "● 4K HEVC RAW",
                    color = Color(0xFF00E5FF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }

        // ========================================================
        // 2. VIDEO SURFACE CONTAINER
        // ========================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
        ) {
            SwingPanel(
                factory = { mediaPlayerComponent },
                modifier = Modifier.fillMaxSize()
            )

            if (isBuffering) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF00E5FF),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(54.dp)
                    )
                }
            }
        }

        // ========================================================
        // 3. BOTTOM CINEMA TRANSPORT BAR
        // ========================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D121C))
                .padding(horizontal = 24.dp, vertical = 14.dp)
        ) {
            // Timeline Scrubber
            Slider(
                value = if (totalDurationMs > 0L) (currentTimeMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction ->
                    val targetMs = (fraction * totalDurationMs).toLong()
                    player?.controls()?.setTime(targetMs)
                    currentTimeMs = targetMs
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF00E5FF),
                    activeTrackColor = Color(0xFF2563EB),
                    inactiveTrackColor = Color(0xFF222B3D)
                ),
                modifier = Modifier.fillMaxWidth().height(20.dp).pointerHoverIcon(PointerIcon.Hand)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Timecodes
                Text(
                    text = "${formatTimecode(currentTimeMs)} / ${formatTimecode(totalDurationMs)}",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )

                // Playback Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    IconButton(
                        onClick = { player?.controls()?.skipTime(-10000) },
                        modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Replay10,
                            contentDescription = "-10s",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2563EB))
                            .clickable { player?.controls()?.pause() }
                            .pointerHoverIcon(PointerIcon.Hand),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(
                        onClick = { player?.controls()?.skipTime(10000) },
                        modifier = Modifier.size(36.dp).pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Forward10,
                            contentDescription = "+10s",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Volume Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            isMuted = !isMuted
                            player?.audio()?.isMute = isMuted
                        },
                        modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand)
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
                        onValueChange = { newVol ->
                            volume = newVol
                            isMuted = false
                            player?.audio()?.setVolume((newVol * 100).toInt())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFF00E5FF),
                            inactiveTrackColor = Color(0xFF222B3D)
                        ),
                        modifier = Modifier.width(90.dp).pointerHoverIcon(PointerIcon.Hand)
                    )
                }
            }
        }
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
