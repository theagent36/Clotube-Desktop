package com.clotube.desktop.ui.screens

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.desktop.media.LocalStreamServer
import com.clotube.desktop.model.DesktopMediaItem
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
fun DesktopMoviePlayerScreen(
    initialMovie: DesktopMediaItem,
    allMovies: List<DesktopMediaItem> = emptyList(),
    accessToken: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentMovie by remember(initialMovie.id) { mutableStateOf(initialMovie) }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentTimeMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(currentMovie.durationMs.coerceAtLeast(1L)) }
    var volume by remember { mutableFloatStateOf(1f) }
    var isMuted by remember { mutableStateOf(false) }

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
                "--aout=directsound",
                "--audio-resampler=soxr",
                "--network-caching=3000",
                "--file-caching=2000",
                "--drop-late-frames",
                "--skip-frames"
            )
            EmbeddedMediaPlayerComponent(factory, null, null, null, null).apply {
                background = java.awt.Color.BLACK
                videoSurfaceComponent().background = java.awt.Color.BLACK
            }
        } catch (e: Throwable) {
            System.err.println("[MoviePlayer] LibVLC initialization error: ${e.message}")
            null
        }
    }

    val player = mediaPlayerComponent?.mediaPlayer()

    // Triggered whenever the active movie changes
    LaunchedEffect(currentMovie.id) {
        val comp = mediaPlayerComponent ?: return@LaunchedEffect
        val p = comp.mediaPlayer() ?: return@LaunchedEffect
        val surface = comp.videoSurfaceComponent() ?: return@LaunchedEffect

        if (isReleased.get()) return@LaunchedEffect

        isBuffering = true
        isPlaying = false
        currentTimeMs = 0L
        totalDurationMs = currentMovie.durationMs.coerceAtLeast(1L)
        videoAspectRatio = 16f / 9f

        // 1. Synchronously halt existing decoders and yield for D3D11 swapchain cleanup
        p.controls().stop()
        delay(150)

        surface.background = java.awt.Color.BLACK
        comp.background = java.awt.Color.BLACK

        val port = LocalStreamServer.start()
        val streamUrl = "http://127.0.0.1:$port/stream?id=${currentMovie.id}&token=$accessToken"
        val hasStarted = AtomicBoolean(false)

        fun startStream() {
            if (hasStarted.compareAndSet(false, true)) {
                println("[DesktopMoviePlayerScreen] Starting stream for ${currentMovie.title} (ID: ${currentMovie.id})")
                SwingUtilities.invokeLater {
                    try {
                        if (!isReleased.get()) {
                            p.media().play(
                                streamUrl,
                                ":network-caching=3000",
                                ":file-caching=2000"
                            )
                        }
                    } catch (t: Throwable) {
                        System.err.println("[MoviePlayer] Playback start error: ${t.message}")
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

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            for (i in 0 until 20) {
                val dim = player?.video()?.videoDimension()
                if (dim != null && dim.width > 0 && dim.height > 0) {
                    val ratio = dim.width.toFloat() / dim.height.toFloat()
                    if (ratio in 0.5f..3.5f) {
                        videoAspectRatio = ratio
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
                System.err.println("[MoviePlayer] LibVLC playback error")
                isBuffering = false
            }
        }

        player?.events()?.addMediaPlayerEventListener(listener)

        onDispose {
            if (!isReleased.getAndSet(true)) {
                try {
                    player?.events()?.removeMediaPlayerEventListener(listener)
                    // Halt decoding and flush pipeline synchronously before releasing component
                    player?.controls()?.stop()
                    comp?.release()
                } catch (t: Throwable) {
                    System.err.println("[MoviePlayer] Safe teardown: ${t.message}")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090E))
            .padding(horizontal = 24.dp, vertical = 14.dp)
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
                            onBack()
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // TOP BREADCRUMB ROW
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onBack() }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFF94A3B8),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Back to Collection",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF0C1928),
                border = BorderStroke(1.dp, Color(0xFF16324F))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E5FF))
                    )
                    Text(
                        text = "Hardware Direct Stream Active",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // MAIN SPLIT: Zero-Scroll Fixed Height Layout
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // LEFT COLUMN (~65% width): Reduced Height Player + Movie Details Below (NO THUMBNAIL)
            Column(
                modifier = Modifier
                    .weight(0.65f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Video Viewport & Transport Container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                        .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(14.dp))
                ) {
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

                        val videoModifier = if (videoAspectRatio > boxRatio) {
                            Modifier.fillMaxWidth().aspectRatio(videoAspectRatio)
                        } else {
                            Modifier.fillMaxHeight().aspectRatio(videoAspectRatio)
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
                                CircularProgressIndicator(color = Color(0xFF00E5FF), modifier = Modifier.size(46.dp))
                            }
                        }
                    }

                    // Transport Controls Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(Color(0xFF0C1018))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IconButton(
                            onClick = {
                                if (isPlaying) player?.controls()?.pause() else player?.controls()?.play()
                            },
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
                            onClick = { player?.controls()?.skipTime(-10000) },
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
                            onClick = { player?.controls()?.skipTime(10000) },
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
                                player?.controls()?.setTime(target)
                                currentTimeMs = target
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
                            onClick = {
                                isMuted = !isMuted
                                player?.audio()?.isMute = isMuted
                            },
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
                            onValueChange = { newVol ->
                                volume = newVol
                                isMuted = false
                                player?.audio()?.setVolume((newVol * 100).toInt())
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color(0xFF00E5FF),
                                inactiveTrackColor = Color(0xFF1E2838)
                            ),
                            modifier = Modifier.width(76.dp).pointerHoverIcon(PointerIcon.Hand)
                        )
                    }
                }

                // DETAILS OF THE MOVIE BELOW THE PLAYER (NO THUMBNAIL)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "NOW PLAYING",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.3.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = currentMovie.title,
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { }
                                    .pointerHoverIcon(PointerIcon.Hand),
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF131824),
                                border = BorderStroke(1.dp, Color(0xFF263346))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(15.dp))
                                    Text("My list", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Button(
                                onClick = onBack,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2638)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .height(34.dp)
                                    .pointerHoverIcon(PointerIcon.Hand)
                            ) {
                                Text("Exit Player", color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (currentMovie.durationMs > 0L) {
                            Text(
                                text = formatDuration(currentMovie.durationMs),
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(text = "•", color = Color(0xFF64748B), fontSize = 12.sp)
                        }

                        Text(
                            text = currentMovie.genre.ifBlank { "Action • Cinema" },
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    val genreTokens = remember(currentMovie.genre) {
                        currentMovie.genre.split("•", ",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() && !it.equals("Movie", ignoreCase = true) }
                    }

                    if (genreTokens.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            genreTokens.forEach { genreTag ->
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = Color(0x99131D2D),
                                    border = BorderStroke(1.dp, Color(0x661E3A5F))
                                ) {
                                    Text(
                                        text = genreTag,
                                        color = Color(0xFFE2E8F0),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "Streaming master-quality source file directly from Google Drive in master quality via hardware-accelerated Direct3D playback.",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            // RIGHT COLUMN (~35% width): SCROLLABLE LIST OF "Movies in the Drive"
            Surface(
                modifier = Modifier
                    .weight(0.35f)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF0C1018),
                border = BorderStroke(1.dp, Color(0xFF18202F))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "COLLECTION",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Movies in the Drive",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Text(
                            text = "${allMovies.size} titles",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFF161E2E), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(allMovies, key = { it.id }) { item ->
                            val isSelected = item.id == currentMovie.id
                            val thumb = rememberDriveThumbnail(item.thumbnailUrl)

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { currentMovie = item }
                                    .pointerHoverIcon(PointerIcon.Hand),
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color(0xFF131E30) else Color(0xFF0F141F),
                                border = if (isSelected) BorderStroke(1.dp, Color(0xFF2563EB)) else BorderStroke(1.dp, Color(0xFF161E2E))
                            ) {
                                Row(
                                    modifier = Modifier.padding(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Movie Thumbnail
                                    Box(
                                        modifier = Modifier
                                            .width(84.dp)
                                            .height(48.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF161E2E)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (thumb != null) {
                                            Image(
                                                bitmap = thumb,
                                                contentDescription = item.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }

                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF2563EB)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.PlayArrow,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Title & Details
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = item.genre.ifBlank { "Movie" },
                                            color = Color(0xFF38BDF8),
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (item.durationMs > 0L) {
                                            Text(
                                                text = formatDuration(item.durationMs),
                                                color = Color(0xFF64748B),
                                                fontSize = 9.sp
                                            )
                                        }
                                    }

                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.GraphicEq,
                                            contentDescription = "Playing",
                                            tint = Color(0xFF00E5FF),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDone,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Direct Stream Ready",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp
                        )
                    }
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

private fun formatDuration(durationMs: Long): String {
    val totalMinutes = (durationMs / 60000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h %02dm".format(minutes) else "${minutes}m"
}
