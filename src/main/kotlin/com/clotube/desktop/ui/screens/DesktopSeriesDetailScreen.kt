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
import com.clotube.desktop.data.DesktopDriveScanner
import com.clotube.desktop.media.LocalStreamServer
import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.model.DesktopSeason
import com.clotube.desktop.model.DesktopSeries
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
fun DesktopSeriesDetailScreen(
    seriesItem: DesktopMediaItem,
    accessToken: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var seriesDetails by remember { mutableStateOf<DesktopSeries?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedSeasonNumber by remember { mutableIntStateOf(1) }
    var activeEpisode by remember { mutableStateOf<DesktopEpisode?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var currentTimeMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(1L) }
    var volume by remember { mutableFloatStateOf(1f) }
    var isMuted by remember { mutableStateOf(false) }

    var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    val isReleased = remember { AtomicBoolean(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(seriesItem.id) {
        val scanner = DesktopDriveScanner(accessToken)
        val loaded = scanner.loadSeriesDetails(seriesItem.id, seriesItem.title)
        seriesDetails = loaded
        val initialSeason = loaded.seasons.firstOrNull()
        if (initialSeason != null) {
            selectedSeasonNumber = initialSeason.seasonNumber
            activeEpisode = initialSeason.episodes.firstOrNull()
        }
        isLoading = false
    }

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
            System.err.println("[SeriesPlayer] LibVLC error: ${e.message}")
            null
        }
    }

    val player = mediaPlayerComponent?.mediaPlayer()

    LaunchedEffect(activeEpisode?.id) {
        val ep = activeEpisode ?: return@LaunchedEffect
        val comp = mediaPlayerComponent ?: return@LaunchedEffect
        val p = comp.mediaPlayer() ?: return@LaunchedEffect
        val surface = comp.videoSurfaceComponent() ?: return@LaunchedEffect

        if (isReleased.get()) return@LaunchedEffect

        val port = LocalStreamServer.start()
        val streamUrl = "http://127.0.0.1:$port/stream?id=${ep.id}&token=$accessToken"
        val hasStarted = AtomicBoolean(false)

        fun startStream() {
            if (hasStarted.compareAndSet(false, true)) {
                SwingUtilities.invokeLater {
                    try {
                        if (!isReleased.get()) {
                            p.media().play(
                                streamUrl,
                                ":network-caching=2500",
                                ":file-caching=1500"
                            )
                        }
                    } catch (t: Throwable) {
                        System.err.println("[SeriesPlayer] Playback error: ${t.message}")
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
                    System.err.println("[SeriesPlayer] Safe teardown: ${t.message}")
                }
            }
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading) {
            delay(50)
            runCatching { focusRequester.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090E))
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
        if (isLoading || seriesDetails == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF00E5FF))
            }
        } else {
            val series = seriesDetails!!
            val currentSeason = series.seasons.find { it.seasonNumber == selectedSeasonNumber }
                ?: series.seasons.firstOrNull() ?: DesktopSeason(1, "Season 1", emptyList())
            val episodes = currentSeason.episodes

            val currentEpisodeIndex = episodes.indexOfFirst { it.id == activeEpisode?.id }
            val hasPrevious = currentEpisodeIndex > 0
            val hasNext = currentEpisodeIndex in 0 until episodes.size - 1
            val prevEpisode = if (hasPrevious) episodes[currentEpisodeIndex - 1] else null
            val nextEpisode = if (hasNext) episodes[currentEpisodeIndex + 1] else null

            var seasonDropdownExpanded by remember { mutableStateOf(false) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item(key = "breadcrumb") {
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
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Back to Series",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                item(key = "series_header_block") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text(
                                text = "CLOTUBE ORIGINAL SERIES",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.4.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = series.title,
                                color = Color.White,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.5).sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${series.releaseYear}  •  ${series.ageRating}  •  ${currentSeason.title}  •  ${series.genres}",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { }
                                    .pointerHoverIcon(PointerIcon.Hand),
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF131824),
                                border = BorderStroke(1.dp, Color(0xFF263346))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "My list",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Surface(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { }
                                    .pointerHoverIcon(PointerIcon.Hand),
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF131824),
                                border = BorderStroke(1.dp, Color(0xFF263346)),
                                contentColor = Color.White
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreHoriz,
                                        contentDescription = "More",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "main_split_view") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(580.dp),
                        horizontalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.67f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
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
                                            CircularProgressIndicator(color = Color(0xFF00E5FF), modifier = Modifier.size(46.dp))
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .background(Color(0xFF0C1018))
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = { prevEpisode?.let { activeEpisode = it } },
                                        enabled = hasPrevious,
                                        modifier = Modifier.size(28.dp).pointerHoverIcon(if (hasPrevious) PointerIcon.Hand else PointerIcon.Default)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.SkipPrevious,
                                            contentDescription = "Previous Episode",
                                            tint = if (hasPrevious) Color.White else Color(0x33FFFFFF),
                                            modifier = Modifier.size(20.dp)
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

                                    IconButton(
                                        onClick = { nextEpisode?.let { activeEpisode = it } },
                                        enabled = hasNext,
                                        modifier = Modifier.size(28.dp).pointerHoverIcon(if (hasNext) PointerIcon.Hand else PointerIcon.Default)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.SkipNext,
                                            contentDescription = "Next Episode",
                                            tint = if (hasNext) Color.White else Color(0x33FFFFFF),
                                            modifier = Modifier.size(20.dp)
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
                                        text = "${formatTime(currentTimeMs)} / ${formatTime(totalDurationMs)}",
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
                                        modifier = Modifier.width(70.dp).pointerHoverIcon(PointerIcon.Hand)
                                    )
                                }
                            }

                            activeEpisode?.let { ep ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "EPISODE ${ep.episodeNumber}",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = ep.title,
                                            color = Color.White,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = ep.durationFormatted,
                                            color = Color(0xFF94A3B8),
                                            fontSize = 13.sp
                                        )
                                    }
                                    Text(
                                        text = ep.synopsis,
                                        color = Color(0xFF94A3B8),
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .weight(0.33f)
                                .fillMaxHeight(),
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF0C1018),
                            border = BorderStroke(1.dp, Color(0xFF18202F))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(18.dp)
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
                                            letterSpacing = 1.2.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${episodes.size} episodes",
                                            color = Color.White,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }

                                    Box {
                                        Surface(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { seasonDropdownExpanded = true }
                                                .pointerHoverIcon(PointerIcon.Hand),
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFF131824),
                                            border = BorderStroke(1.dp, Color(0xFF263346))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = currentSeason.title,
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Icon(
                                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                                    contentDescription = null,
                                                    tint = Color(0xFF94A3B8),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = seasonDropdownExpanded,
                                            onDismissRequest = { seasonDropdownExpanded = false },
                                            modifier = Modifier.background(Color(0xFF131824))
                                        ) {
                                            series.seasons.forEach { season ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = "${season.title} (${season.episodes.size} eps)",
                                                            color = if (season.seasonNumber == selectedSeasonNumber) Color(0xFF00E5FF) else Color.White,
                                                            fontSize = 12.sp
                                                        )
                                                    },
                                                    onClick = {
                                                        selectedSeasonNumber = season.seasonNumber
                                                        seasonDropdownExpanded = false
                                                        activeEpisode = season.episodes.firstOrNull()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF00E5FF))
                                        )
                                        Text(
                                            text = "Direct Stream Ready",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.sp
                                        )
                                    }
                                    Text(
                                        text = "Mark all watched",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clickable { }
                                            .pointerHoverIcon(PointerIcon.Hand)
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = Color(0xFF18202F), thickness = 1.dp)
                                Spacer(modifier = Modifier.height(12.dp))

                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(episodes, key = { it.id }) { ep ->
                                        val isSelected = activeEpisode?.id == ep.id
                                        val thumb = rememberDriveThumbnail(ep.thumbnailUrl)

                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { activeEpisode = ep }
                                                .pointerHoverIcon(PointerIcon.Hand),
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) Color(0xFF121B2B) else Color(0xFF0F141F),
                                            border = if (isSelected) BorderStroke(1.dp, Color(0xFF2563EB)) else BorderStroke(1.dp, Color(0xFF18202F))
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(96.dp)
                                                        .height(54.dp)
                                                        .clip(RoundedCornerShape(6.dp))
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
                                                                .size(24.dp)
                                                                .clip(CircleShape)
                                                                .background(Color(0xFF2563EB)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.PlayArrow,
                                                                contentDescription = null,
                                                                tint = Color.White,
                                                                modifier = Modifier.size(15.dp)
                                                            )
                                                        }
                                                    } else {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(22.dp)
                                                                .clip(CircleShape)
                                                                .background(Color(0xCC000000)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Text(
                                                                text = "${ep.episodeNumber}",
                                                                color = Color(0xFF94A3B8),
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
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
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = ep.title,
                                                        color = Color.White,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = "${ep.durationFormatted} • Direct Stream",
                                                        color = Color(0xFF64748B),
                                                        fontSize = 10.sp
                                                    )
                                                }

                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.GraphicEq,
                                                        contentDescription = "Playing",
                                                        tint = Color(0xFF00E5FF),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
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
                                        text = "Progress synced to cloud",
                                        color = Color(0xFF64748B),
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "about_the_series") {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFF141A26), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(0.6f)) {
                            Text(
                                text = "ABOUT THE SERIES",
                                color = Color(0xFF38BDF8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.4.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = series.tagline,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = series.synopsis,
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                        }

                        Column(
                            modifier = Modifier.weight(0.35f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            MetadataLine(label = "Creators", value = series.creators)
                            MetadataLine(label = "Cast", value = series.cast)
                            MetadataLine(label = "Genres", value = series.genres)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFF64748B), fontSize = 12.sp)
        Text(text = value, color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
