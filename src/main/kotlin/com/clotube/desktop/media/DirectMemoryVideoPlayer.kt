package com.clotube.desktop.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared singleton LibVLC factory.
 * Reusing a single instance ensures core audio/video modules are not prematurely destroyed.
 */
object SharedVlcFactory {
    val instance: MediaPlayerFactory by lazy {
        MediaPlayerFactory(
            "--no-video-title-show",
            "--no-sub-autodetect-file",
            "--no-video-on-top",
            "--network-caching=3000",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--drop-late-frames",
            "--skip-frames",
            "--quiet"
        )
    }
}

class DirectMemoryVideoPlayer(
    val mediaUrl: String,
    val onFirstFrame: () -> Unit = {}
) {
    private val isReleased = AtomicBoolean(false)
    private val releaseExecutor = Executors.newSingleThreadExecutor()

    val mediaPlayer = SharedVlcFactory.instance.mediaPlayers().newEmbeddedMediaPlayer()

    var currentFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(true)
    var isFirstFrameRendered by mutableStateOf(false)
    var currentTimeMs by mutableLongStateOf(0L)
    var totalDurationMs by mutableLongStateOf(0L)
    var positionFraction by mutableFloatStateOf(0f)
    var volume by mutableIntStateOf(100)

    var frameTick by mutableIntStateOf(0)
        private set

    private val frameLock = Any()
    private var pixelBuffer: ByteArray? = null
    private var imageInfo: ImageInfo? = null
    private var nativePitch: Int = 0

    private val eventListener = object : MediaPlayerEventAdapter() {
        override fun playing(mp: MediaPlayer) {
            if (isReleased.get()) return
            isPlaying = true
            isBuffering = false
        }

        override fun buffering(mp: MediaPlayer, newCache: Float) {
            if (isReleased.get()) return
            isBuffering = newCache < 100.0f
        }

        override fun paused(mp: MediaPlayer) {
            if (isReleased.get()) return
            isPlaying = false
        }

        override fun stopped(mp: MediaPlayer) {
            if (isReleased.get()) return
            isPlaying = false
        }

        override fun finished(mp: MediaPlayer) {
            if (isReleased.get()) return
            isPlaying = false
        }

        override fun timeChanged(mp: MediaPlayer, newTime: Long) {
            if (isReleased.get()) return
            currentTimeMs = newTime
        }

        override fun lengthChanged(mp: MediaPlayer, newLength: Long) {
            if (isReleased.get()) return
            totalDurationMs = newLength
        }

        override fun positionChanged(mp: MediaPlayer, newPosition: Float) {
            if (isReleased.get()) return
            positionFraction = newPosition
        }
    }

    init {
        val callbackSurface = SharedVlcFactory.instance.videoSurfaces().newVideoSurface(
            object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    val pitch = sourceWidth * 4
                    nativePitch = pitch

                    imageInfo = ImageInfo(
                        width = sourceWidth,
                        height = sourceHeight,
                        colorType = ColorType.BGRA_8888,
                        alphaType = ColorAlphaType.PREMUL
                    )

                    synchronized(frameLock) {
                        pixelBuffer = ByteArray(pitch * sourceHeight)
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
                    if (isReleased.get()) return
                    val directBuf = nativeBuffers.firstOrNull() ?: return
                    val info = imageInfo ?: return

                    synchronized(frameLock) {
                        if (isReleased.get()) return
                        val bytes = pixelBuffer ?: return
                        directBuf.rewind()
                        directBuf.get(bytes)

                        try {
                            val skiaImage = Image.makeRaster(info, bytes, nativePitch)
                            val composeBitmap = skiaImage.toComposeImageBitmap()
                            skiaImage.close()
                            currentFrame = composeBitmap
                        } catch (_: Throwable) {
                            return
                        }
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
        mediaPlayer.events().addMediaPlayerEventListener(eventListener)

        // Force Windows Volume Mixer session to unmute and sync volume
        try {
            mediaPlayer.audio().setMute(false)
            mediaPlayer.audio().setVolume(volume)
        } catch (_: Throwable) {}

        mediaPlayer.media().play(mediaUrl)
    }

    fun seekToFraction(fraction: Float) {
        if (isReleased.get()) return
        val totalMs = if (totalDurationMs > 0L) totalDurationMs else mediaPlayer.status().length()
        if (totalMs > 0L) {
            val targetMs = (fraction.coerceIn(0f, 1f) * totalMs).toLong()
            mediaPlayer.controls().setTime(targetMs)
            currentTimeMs = targetMs
        }
    }

    fun seekToTime(timeMs: Long) {
        if (isReleased.get()) return
        val totalMs = if (totalDurationMs > 0L) totalDurationMs else mediaPlayer.status().length()
        val clampedTime = if (totalMs > 0L) timeMs.coerceIn(0L, totalMs) else timeMs.coerceAtLeast(0L)
        mediaPlayer.controls().setTime(clampedTime)
        currentTimeMs = clampedTime
    }

    fun togglePlayPause() {
        if (isReleased.get()) return
        if (mediaPlayer.status().isPlaying) {
            mediaPlayer.controls().pause()
        } else {
            mediaPlayer.controls().play()
        }
    }

    fun setVolumeLevel(vol: Int) {
        if (isReleased.get()) return
        volume = vol.coerceIn(0, 100)
        mediaPlayer.audio().setVolume(volume)
        if (volume > 0) {
            try {
                mediaPlayer.audio().setMute(false)
            } catch (_: Throwable) {}
        }
    }

    /**
     * Graceful teardown protocol preventing LibVLC decoder/clock crashes on exit.
     */
    fun release() {
        if (isReleased.getAndSet(true)) return

        // 1. Immediately unlink listener so no callbacks reach Compose
        try {
            mediaPlayer.events().removeMediaPlayerEventListener(eventListener)
        } catch (_: Throwable) {}

        // 2. Clear visual frame
        synchronized(frameLock) {
            currentFrame = null
        }

        // 3. Dispatch native shutdown to background thread so UI never hangs
        releaseExecutor.submit {
            try {
                // Signal LibVLC to stop demuxing
                mediaPlayer.controls().stop()

                // Allow HEVC/H.264 worker threads a brief moment to exit cleanly
                var count = 0
                while (mediaPlayer.status().isPlaying && count < 10) {
                    Thread.sleep(10)
                    count++
                }
                Thread.sleep(70)

                // Detach video surface and release player instance
                mediaPlayer.videoSurface().set(null)
                mediaPlayer.release()

                synchronized(frameLock) {
                    pixelBuffer = null
                    imageInfo = null
                }
            } catch (_: Throwable) {
                // Suppress native teardown noise
            } finally {
                releaseExecutor.shutdown()
            }
        }
    }
}
