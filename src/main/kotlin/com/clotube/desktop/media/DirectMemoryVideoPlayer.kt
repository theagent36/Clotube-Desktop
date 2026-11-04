package com.clotube.desktop.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.clotube.desktop.data.DesktopAppSettings
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private fun MediaPlayer.subtitles() = this.subpictures()

class DirectMemoryVideoPlayer(
    val mediaUrl: String,
    val initialSeekMs: Long = 0L,
    val onFirstFrame: () -> Unit = {}
) {
    val settings = DesktopAppSettings.state

    var playbackSpeed by mutableFloatStateOf(settings.defaultSpeed)
        private set

    var externalSubtitleName by mutableStateOf<String?>(null)
        private set
    var externalSubtitlePath: String? = null
        private set

    // Divisor of video height: 25 -> Small, 18 -> Normal, 12 -> Large
    private val relFontSize: Int = when (settings.subtitleFontSizeSp) {
        14 -> 25
        18 -> 18
        24 -> 12
        else -> 18
    }

    private val factory: MediaPlayerFactory by lazy {
        val isHwEnabled = settings.hardwareAcceleration
        val hwFlag = if (isHwEnabled) "--avcodec-hw=any" else "--avcodec-hw=none"

        println("[DirectMemoryVideoPlayer] Initializing LibVLC Engine: HW = $isHwEnabled ($hwFlag), Subtitle Rel-Divisor = $relFontSize (${settings.subtitleFontSizeSp}sp)")

        MediaPlayerFactory(
            "--no-video-title-show",
            "--no-sub-autodetect-file",
            "--drop-late-frames",
            "--skip-frames",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--quiet",
            "--freetype-rel-fontsize=$relFontSize",
            hwFlag
        )
    }

    val mediaPlayer: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    var currentFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(true)
    var isFirstFrameRendered by mutableStateOf(false)
    var currentTimeMs by mutableLongStateOf(0L)
    var totalDurationMs by mutableLongStateOf(0L)
    var positionFraction by mutableFloatStateOf(0f)

    var volume by mutableIntStateOf(
        if (settings.rememberVolume) DesktopTrackPreferences.stickyVolume else 100
    )

    var isMuted by mutableStateOf(false)

    var frameTick by mutableIntStateOf(0)
        private set

    private val frameLock = Any()
    private var pixelBufferA: ByteArray? = null
    private var pixelBufferB: ByteArray? = null
    private var skiaBitmapA: Bitmap? = null
    private var skiaBitmapB: Bitmap? = null
    private var composeBitmapA: ImageBitmap? = null
    private var composeBitmapB: ImageBitmap? = null
    private var useBufferA: Boolean = true
    private var imageInfo: ImageInfo? = null
    private var nativePitch: Int = 0

    @Volatile
    private var isDisposed: Boolean = false
    private val hasAppliedPreferences = AtomicBoolean(false)
    private val hasAppliedInitialSeek = AtomicBoolean(false)
    private val asyncExecutor = Executors.newSingleThreadScheduledExecutor()

    private fun checkAndApplyInitialSeek(mp: MediaPlayer) {
        if (initialSeekMs <= 1500L) return
        if (!hasAppliedInitialSeek.compareAndSet(false, true)) return

        asyncExecutor.schedule({
            try {
                if (isDisposed) return@schedule
                var attempts = 0
                while (attempts < 35 && (!mp.status().isPlayable || !mp.status().isSeekable)) {
                    Thread.sleep(100)
                    attempts++
                }
                if (isDisposed) return@schedule

                mp.controls().setTime(initialSeekMs)
                currentTimeMs = initialSeekMs
                println("[DirectMemoryVideoPlayer] Engine-level resume applied successfully at ${initialSeekMs}ms")
            } catch (t: Throwable) {
                System.err.println("[DirectMemoryVideoPlayer] Resume seek error: ${t.message}")
            }
        }, 150, TimeUnit.MILLISECONDS)
    }

    init {
        val callbackSurface = factory.videoSurfaces().newVideoSurface(
            object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    val pitch = sourceWidth * 4
                    val byteCount = pitch * sourceHeight

                    // Enforce OPAQUE to prevent alpha dilution of video colors
                    val info = ImageInfo(
                        width = sourceWidth,
                        height = sourceHeight,
                        colorType = ColorType.BGRA_8888,
                        alphaType = ColorAlphaType.OPAQUE
                    )

                    synchronized(frameLock) {
                        nativePitch = pitch
                        imageInfo = info

                        pixelBufferA = ByteArray(byteCount)
                        pixelBufferB = ByteArray(byteCount)

                        skiaBitmapA?.close()
                        skiaBitmapB?.close()

                        val bmpA = Bitmap().apply { allocPixels(info) }
                        val bmpB = Bitmap().apply { allocPixels(info) }

                        skiaBitmapA = bmpA
                        skiaBitmapB = bmpB
                        composeBitmapA = bmpA.asComposeImageBitmap()
                        composeBitmapB = bmpB.asComposeImageBitmap()
                    }

                    return BufferFormat(
                        "RV32",
                        sourceWidth,
                        sourceHeight,
                        intArrayOf(pitch),
                        intArrayOf(sourceHeight)
                    )
                }

                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
            },
            object : RenderCallback {
                override fun display(mp: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
                    if (isDisposed || nativeBuffers.isEmpty()) return
                    val directBuf = nativeBuffers[0]

                    synchronized(frameLock) {
                        if (isDisposed) return
                        val info = imageInfo ?: return
                        val writeBytes = if (useBufferA) pixelBufferA else pixelBufferB
                        val writeBmp = if (useBufferA) skiaBitmapA else skiaBitmapB
                        val targetComposeBmp = if (useBufferA) composeBitmapA else composeBitmapB

                        if (writeBytes == null || writeBmp == null || targetComposeBmp == null) return

                        directBuf.rewind()
                        val remaining = directBuf.remaining().coerceAtMost(writeBytes.size)
                        directBuf.get(writeBytes, 0, remaining)

                        writeBmp.installPixels(info, writeBytes, nativePitch)
                        writeBmp.notifyPixelsChanged()

                        currentFrame = targetComposeBmp
                        useBufferA = !useBufferA
                    }

                    if (!isFirstFrameRendered) {
                        isFirstFrameRendered = true
                        isBuffering = false
                        onFirstFrame()
                    }

                    frameTick++
                }
            },
            true
        )

        mediaPlayer.videoSurface().set(callbackSurface)

        try {
            mediaPlayer.audio().setMute(false)
            mediaPlayer.audio().setVolume(volume)
        } catch (_: Throwable) {}

        mediaPlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) {
                isPlaying = true
                isBuffering = false

                val startupRate = settings.defaultSpeed
                playbackSpeed = startupRate
                try {
                    if (mp.status().rate() != startupRate) {
                        mp.controls().setRate(startupRate)
                    }
                } catch (_: Throwable) {}

                if (hasAppliedPreferences.compareAndSet(false, true)) {
                    Executors.newSingleThreadScheduledExecutor().schedule({
                        DesktopTrackPreferences.applyStickyPreferences(mp)
                    }, 400, TimeUnit.MILLISECONDS)
                }

                checkAndApplyInitialSeek(mp)
            }

            override fun buffering(mp: MediaPlayer, newCache: Float) {
                isBuffering = newCache < 100.0f
            }

            override fun paused(mp: MediaPlayer) {
                isPlaying = false
            }

            override fun stopped(mp: MediaPlayer) {
                isPlaying = false
            }

            override fun finished(mp: MediaPlayer) {
                isPlaying = false
            }

            override fun timeChanged(mp: MediaPlayer, newTime: Long) {
                if (kotlin.math.abs(newTime - currentTimeMs) >= 250L || newTime == 0L) {
                    currentTimeMs = newTime
                }
                if (newTime in 0L..1500L && initialSeekMs > 2000L) {
                    checkAndApplyInitialSeek(mp)
                }
            }

            override fun lengthChanged(mp: MediaPlayer, newLength: Long) {
                if (newLength > 0L) {
                    totalDurationMs = newLength
                    checkAndApplyInitialSeek(mp)
                }
            }

            override fun positionChanged(mp: MediaPlayer, newPosition: Float) {
                if (kotlin.math.abs(newPosition - positionFraction) >= 0.001f) {
                    positionFraction = newPosition
                }
            }
        })

        val bufferMs = settings.bufferDepthMs
        val fileCacheMs = (bufferMs * 0.6).toInt()
        val mediaArgs = mutableListOf(
            ":network-caching=$bufferMs",
            ":file-caching=$fileCacheMs",
            ":freetype-rel-fontsize=$relFontSize"
        )
        if (initialSeekMs > 1500L) {
            mediaArgs.add(":start-time=${initialSeekMs / 1000L}")
        }

        mediaPlayer.media().play(mediaUrl, *mediaArgs.toTypedArray())
    }

    fun setPlaybackRate(rate: Float) {
        if (isDisposed) return
        playbackSpeed = rate
        try {
            mediaPlayer.controls().setRate(rate)
        } catch (_: Throwable) {}
    }

    fun seekToFraction(fraction: Float) {
        if (isDisposed || !isFirstFrameRendered) return
        try {
            if (!mediaPlayer.status().isPlayable) return
            val totalMs = if (totalDurationMs > 0L) totalDurationMs else mediaPlayer.status().length()
            if (totalMs > 0L) {
                val targetMs = (fraction.coerceIn(0f, 1f) * totalMs).toLong()
                mediaPlayer.controls().setTime(targetMs)
                currentTimeMs = targetMs
            }
        } catch (_: Throwable) {}
    }

    fun seekToTime(timeMs: Long) {
        if (isDisposed || !isFirstFrameRendered) return
        try {
            if (!mediaPlayer.status().isPlayable) return
            val totalMs = if (totalDurationMs > 0L) totalDurationMs else mediaPlayer.status().length()
            val clamped = if (totalMs > 0L) timeMs.coerceIn(0L, totalMs) else timeMs.coerceAtLeast(0L)
            mediaPlayer.controls().setTime(clamped)
            currentTimeMs = clamped
        } catch (_: Throwable) {}
    }

    fun play() {
        if (isDisposed) return
        try {
            mediaPlayer.controls().play()
            isPlaying = true
        } catch (_: Throwable) {}
    }

    fun pause() {
        if (isDisposed) return
        try {
            mediaPlayer.controls().pause()
            isPlaying = false
        } catch (_: Throwable) {}
    }

    fun togglePlayPause() {
        if (isDisposed) return
        try {
            if (mediaPlayer.status().isPlaying) {
                mediaPlayer.controls().pause()
                isPlaying = false
            } else {
                mediaPlayer.controls().play()
                isPlaying = true
            }
        } catch (_: Throwable) {}
    }

    @JvmName("changeMuted")
    fun setMuted(muted: Boolean) {
        if (isDisposed) return
        isMuted = muted
        try {
            mediaPlayer.audio().setMute(muted)
        } catch (_: Throwable) {}
    }

    fun setVolumeLevel(vol: Int) {
        if (isDisposed) return
        volume = vol.coerceIn(0, 100)
        DesktopTrackPreferences.saveVolume(volume)
        try {
            mediaPlayer.audio().setVolume(volume)
            if (volume > 0) {
                mediaPlayer.audio().setMute(false)
                isMuted = false
            } else {
                mediaPlayer.audio().setMute(true)
                isMuted = true
            }
        } catch (_: Throwable) {}
    }

    fun getAudioTracks(): List<Pair<Int, String>> {
        if (isDisposed) return emptyList()
        return try {
            mediaPlayer.audio().trackDescriptions()?.map { it.id() to it.description() } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun getSubtitleTracks(): List<Pair<Int, String>> {
        if (isDisposed) return emptyList()
        return try {
            mediaPlayer.subtitles().trackDescriptions()?.map { it.id() to it.description() } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun setAudioTrack(trackId: Int, description: String = "") {
        if (isDisposed) return
        try {
            mediaPlayer.audio().setTrack(trackId)
            if (description.isNotBlank()) {
                DesktopTrackPreferences.saveAudioTrack(description)
            }
        } catch (_: Throwable) {}
    }

    fun setSubtitleTrack(trackId: Int, description: String = "", isOff: Boolean = (trackId == -1)) {
        if (isDisposed) return
        try {
            mediaPlayer.subtitles().setTrack(trackId)
            DesktopTrackPreferences.saveSubtitleTrack(description, isOff)
        } catch (_: Throwable) {}
    }

    fun loadExternalSubtitle(filePath: String): Boolean {
        if (isDisposed) return false
        return try {
            val file = java.io.File(filePath)
            if (!file.exists()) return false

            val success = mediaPlayer.subpictures().setSubTitleFile(file.absolutePath)
            externalSubtitlePath = file.absolutePath
            externalSubtitleName = file.name
            println("[DirectMemoryVideoPlayer] Loaded external subtitle: ${file.name} (success=$success)")
            true
        } catch (_: Throwable) {
            System.err.println("[DirectMemoryVideoPlayer] Failed to load subtitle file: $filePath")
            false
        }
    }

    fun reactivateExternalSubtitle(): Boolean {
        if (isDisposed) return false
        val path = externalSubtitlePath ?: return false
        return loadExternalSubtitle(path)
    }

    fun getActiveAudioTrackId(): Int {
        if (isDisposed) return -1
        return try { mediaPlayer.audio().track() } catch (_: Throwable) { -1 }
    }

    fun getActiveSubtitleTrackId(): Int {
        if (isDisposed) return -1
        return try { mediaPlayer.subtitles().track() } catch (_: Throwable) { -1 }
    }

    fun release() {
        if (isDisposed) return
        isDisposed = true
        asyncExecutor.shutdownNow()
        externalSubtitleName = null
        externalSubtitlePath = null
        try {
            mediaPlayer.controls().stop()
            mediaPlayer.release()
            factory.release()
        } catch (_: Throwable) {}

        synchronized(frameLock) {
            currentFrame = null
            composeBitmapA = null
            composeBitmapB = null
            try {
                skiaBitmapA?.close()
                skiaBitmapB?.close()
            } catch (_: Throwable) {}
            skiaBitmapA = null
            skiaBitmapB = null
            pixelBufferA = null
            pixelBufferB = null
        }
    }
}
