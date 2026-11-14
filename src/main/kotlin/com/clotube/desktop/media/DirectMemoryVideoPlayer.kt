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
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.PointerType
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private fun MediaPlayer.subtitles() = this.subpictures()

class DirectMemoryVideoPlayer(
    val mediaUrl: String,
    val initialSeekMs: Long = 0L,
    val boundMediaId: String = "",
    val onFirstFrame: () -> Unit = {}
) {
    val settings = DesktopAppSettings.state

    var playbackSpeed by mutableFloatStateOf(settings.defaultSpeed)
        private set

    var externalSubtitleName by mutableStateOf<String?>(null)
        private set
    var externalSubtitlePath: String? = null
        private set
    var externalSubtitleCues by mutableStateOf<List<com.clotube.desktop.ui.components.SubtitleCue>>(emptyList())
        private set

    var currentSubtitleScalePercent by mutableIntStateOf(settings.subtitleScalePercent.coerceIn(25, 300))
        private set

    private val factory: MediaPlayerFactory by lazy {
        val isHwEnabled = settings.hardwareAcceleration
        val hwFlag = if (isHwEnabled) "--avcodec-hw=any" else "--avcodec-hw=none"

        println("[DirectMemoryVideoPlayer] Initializing LibVLC Engine: HW = $isHwEnabled ($hwFlag), Subtitle Scale = ${currentSubtitleScalePercent}%")

        MediaPlayerFactory(
            "--no-video-title-show",
            "--no-sub-autodetect-file",
            "--drop-late-frames",
            "--skip-frames",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--quiet",
            "--sub-text-scale=$currentSubtitleScalePercent",
            hwFlag
        )
    }

    val mediaPlayer: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()

    var currentFrame by mutableStateOf<ImageBitmap?>(null)
        private set
    var isPlaying by mutableStateOf(false)
    var isBuffering by mutableStateOf(true)
    var isFirstFrameRendered by mutableStateOf(false)
    var currentTimeMs by mutableLongStateOf(initialSeekMs.coerceAtLeast(0L))
    var totalDurationMs by mutableLongStateOf(0L)
    var positionFraction by mutableFloatStateOf(0f)

    private val targetResumeMs: Long = initialSeekMs.coerceAtLeast(0L)
    var isResumeSeekSettled by mutableStateOf(targetResumeMs <= 1500L)
        private set
    private var postSeekFramePainted by mutableStateOf(false)

    /**
     * Strictly false while connecting, seeking to initialSeekMs, or buffering the initial frame.
     * Becomes true ONLY after:
     * 1. Native player is active (!isDisposed)
     * 2. Any initial resume seek (targetResumeMs > 1500ms) has settled at the target timestamp
     * 3. At least one decoded video frame (currentFrame != null) has painted at/after that timestamp
     * 4. LibVLC is not actively buffering the initial stream (!isBuffering || hasEverReachedReady)
     */
    private var hasEverReachedReady by mutableStateOf(false)

    val isTargetFrameReady: Boolean
        get() {
            if (isDisposed) return false
            if (hasEverReachedReady) return true
            val readyNow = isFirstFrameRendered &&
                isResumeSeekSettled &&
                postSeekFramePainted &&
                currentFrame != null &&
                !isBuffering
            if (readyNow) {
                hasEverReachedReady = true
            }
            return readyNow
        }

    val isPlaybackFrameReady: Boolean
        get() = isTargetFrameReady

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

    private val nativeLifecycleLock = Any()
    private val disposedFlag = AtomicBoolean(false)

    val isDisposed: Boolean
        get() = disposedFlag.get()
    private val hasAppliedPreferences = AtomicBoolean(false)
    private val hasAppliedInitialSeek = AtomicBoolean(false)
    private val asyncExecutor = Executors.newSingleThreadScheduledExecutor()

    private val pendingSubtitleScalePercent = java.util.concurrent.atomic.AtomicInteger(currentSubtitleScalePercent)
    private var pendingScaleFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private val subScaleExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Clotube-SubScale").apply { isDaemon = true }
    }

    private fun updatePlayingState(playing: Boolean) {
        isPlaying = playing
        WindowsScreenWakeLock.setPlaybackActive(this, playing && !isDisposed)
    }

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
                println("[DirectMemoryVideoPlayer] Engine-level resume applied successfully at ${initialSeekMs}ms")
            } catch (t: Throwable) {
                System.err.println("[DirectMemoryVideoPlayer] Resume seek error: ${t.message}")
            }
        }, 150, TimeUnit.MILLISECONDS)
    }

    private val watchdogStarted = AtomicBoolean(false)
    private fun ensureWatchdogStarted() {
        if (targetResumeMs <= 1500L || isResumeSeekSettled) return
        if (!watchdogStarted.compareAndSet(false, true)) return

        asyncExecutor.schedule({
            try {
                var attempts = 0
                val startTime = System.currentTimeMillis()
                while (!isDisposed && !isResumeSeekSettled) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val currentNativeTime = try { mediaPlayer.status().time() } catch (_: Throwable) { -1L }
                    val minAcceptable = (targetResumeMs - 3000L).coerceAtLeast(1500L)

                    if (currentNativeTime >= minAcceptable) {
                        isResumeSeekSettled = true
                        postSeekFramePainted = true
                        currentTimeMs = currentNativeTime
                        break
                    }

                    if (attempts < 3 && mediaPlayer.status().isPlayable) {
                        try {
                            mediaPlayer.controls().setTime(targetResumeMs)
                            attempts++
                        } catch (_: Throwable) {}
                    }

                    if (elapsed >= 3500L) {
                        println("[DirectMemoryVideoPlayer] Watchdog latch released after 3.5s timeout (target: ${targetResumeMs}ms, actual: ${currentNativeTime}ms)")
                        isResumeSeekSettled = true
                        postSeekFramePainted = true
                        break
                    }
                    Thread.sleep(250)
                }
            } catch (t: Throwable) {
                isResumeSeekSettled = true
                postSeekFramePainted = true
            }
        }, 200, TimeUnit.MILLISECONDS)
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

                    if (isResumeSeekSettled && currentFrame != null) {
                        postSeekFramePainted = true
                        if (!isBuffering) {
                            hasEverReachedReady = true
                        }
                    } else {
                        ensureWatchdogStarted()
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
                updatePlayingState(true)
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

                LibVlcLiveSubScale.applyLiveScale(mp, currentSubtitleScalePercent)

                checkAndApplyInitialSeek(mp)
            }

            override fun videoOutput(mp: MediaPlayer, newCount: Int) {
                if (newCount > 0) {
                    LibVlcLiveSubScale.applyLiveScale(mp, currentSubtitleScalePercent)
                }
            }

            override fun buffering(mp: MediaPlayer, newCache: Float) {
                isBuffering = newCache < 100.0f
            }

            override fun paused(mp: MediaPlayer) {
                updatePlayingState(false)
            }

            override fun stopped(mp: MediaPlayer) {
                updatePlayingState(false)
            }

            override fun finished(mp: MediaPlayer) {
                updatePlayingState(false)
            }

            override fun error(mp: MediaPlayer) {
                updatePlayingState(false)
            }

            override fun timeChanged(mp: MediaPlayer, newTime: Long) {
                if (!isResumeSeekSettled && targetResumeMs > 1500L) {
                    val minAcceptableMs = (targetResumeMs - 3000L).coerceAtLeast(1500L)
                    if (newTime >= minAcceptableMs) {
                        isResumeSeekSettled = true
                        currentTimeMs = newTime
                    } else {
                        // Keep currentTimeMs pinned to targetResumeMs until seek completes; never flash 00:00
                        currentTimeMs = targetResumeMs
                    }
                } else {
                    currentTimeMs = newTime
                }
                if (newTime in 0L..1500L && targetResumeMs > 2000L && !isResumeSeekSettled) {
                    checkAndApplyInitialSeek(mp)
                }
            }

            override fun lengthChanged(mp: MediaPlayer, newLength: Long) {
                if (newLength > 0L) {
                    totalDurationMs = newLength
                    if (!isResumeSeekSettled && targetResumeMs > 1500L) {
                        positionFraction = (targetResumeMs.toFloat() / newLength.toFloat()).coerceIn(0f, 1f)
                    }
                    checkAndApplyInitialSeek(mp)
                }
            }

            override fun positionChanged(mp: MediaPlayer, newPosition: Float) {
                if (!isResumeSeekSettled && targetResumeMs > 1500L) {
                    if (totalDurationMs > 0L) {
                        positionFraction = (targetResumeMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                    }
                } else {
                    if (kotlin.math.abs(newPosition - positionFraction) >= 0.001f) {
                        positionFraction = newPosition
                    }
                }
            }
        })

        val bufferMs = settings.bufferDepthMs
        val fileCacheMs = (bufferMs * 0.6).toInt()
        val mediaArgs = mutableListOf(
            ":network-caching=$bufferMs",
            ":file-caching=$fileCacheMs",
            ":sub-text-scale=$currentSubtitleScalePercent"
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

    fun seekToFraction(fraction: Float, isUserScrub: Boolean = true) {
        if (isDisposed || !isFirstFrameRendered) return
        if (isUserScrub) {
            isResumeSeekSettled = true
            postSeekFramePainted = true
            hasEverReachedReady = true
        }
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

    fun seekToTime(timeMs: Long, isUserScrub: Boolean = true) {
        if (isDisposed || !isFirstFrameRendered) return
        if (isUserScrub) {
            isResumeSeekSettled = true
            postSeekFramePainted = true
            hasEverReachedReady = true
        }
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
            updatePlayingState(true)
        } catch (_: Throwable) {}
    }

    fun pause() {
        if (isDisposed) return
        try {
            mediaPlayer.controls().pause()
            updatePlayingState(false)
        } catch (_: Throwable) {}
    }

    fun togglePlayPause() {
        if (isDisposed) return
        try {
            if (mediaPlayer.status().isPlaying) {
                mediaPlayer.controls().pause()
                updatePlayingState(false)
            } else {
                mediaPlayer.controls().play()
                updatePlayingState(true)
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
            externalSubtitleCues = emptyList()
            externalSubtitleName = null
            mediaPlayer.subtitles().setTrack(trackId)
            DesktopTrackPreferences.saveSubtitleTrack(description, isOff)
        } catch (_: Throwable) {}
    }

    fun loadExternalSubtitle(filePath: String): Boolean {
        if (isDisposed) return false
        return try {
            val file = java.io.File(filePath)
            if (!file.exists()) return false

            val parsedCues = com.clotube.desktop.ui.components.SubtitleParser.parseFile(file)
            if (parsedCues.isNotEmpty()) {
                externalSubtitleCues = parsedCues
                externalSubtitlePath = file.absolutePath
                externalSubtitleName = file.name
                try {
                    mediaPlayer.subpictures().setTrack(-1)
                } catch (_: Throwable) {}
                println("[DirectMemoryVideoPlayer] Loaded external subtitle (Compose canvas overlay): ${file.name} (${parsedCues.size} cues)")
                true
            } else {
                val success = mediaPlayer.subpictures().setSubTitleFile(file.absolutePath)
                externalSubtitlePath = file.absolutePath
                externalSubtitleName = file.name
                externalSubtitleCues = emptyList()
                println("[DirectMemoryVideoPlayer] Loaded external subtitle (LibVLC fallback): ${file.name} (success=$success)")
                success
            }
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

    fun getActiveAudioTrackId(): Int = try {
        mediaPlayer?.audio()?.track() ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun getActiveSubtitleTrackId(): Int = try {
        mediaPlayer?.subtitles()?.track() ?: -1
    } catch (_: Throwable) {
        -1
    }

    fun applySubtitleScale(newScalePercent: Int) {
        val clamped = newScalePercent.coerceIn(25, 300)
        currentSubtitleScalePercent = clamped
        pendingSubtitleScalePercent.set(clamped)
        if (isDisposed) return

        pendingScaleFuture?.cancel(false)
        pendingScaleFuture = try {
            subScaleExecutor.schedule({
                val targetScale = pendingSubtitleScalePercent.get()
                if (!isDisposed) {
                    LibVlcLiveSubScale.applyLiveScale(mediaPlayer, targetScale)
                }
            }, 30L, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            null
        }
    }

    fun addSafeEventListener(listener: uk.co.caprica.vlcj.player.base.MediaPlayerEventListener): Boolean {
        synchronized(nativeLifecycleLock) {
            if (disposedFlag.get()) return false
            return try {
                mediaPlayer.events().addMediaPlayerEventListener(listener)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun removeSafeEventListener(listener: uk.co.caprica.vlcj.player.base.MediaPlayerEventListener) {
        synchronized(nativeLifecycleLock) {
            if (disposedFlag.get()) return
            try {
                mediaPlayer.events().removeMediaPlayerEventListener(listener)
            } catch (_: Throwable) {}
        }
    }

    fun release() {
        WindowsScreenWakeLock.setPlaybackActive(this, false)
        if (!disposedFlag.compareAndSet(false, true)) return
        pendingScaleFuture?.cancel(false)
        subScaleExecutor.shutdownNow()
        asyncExecutor.shutdownNow()
        externalSubtitleName = null
        externalSubtitlePath = null
        externalSubtitleCues = emptyList()

        Thread({
            synchronized(nativeLifecycleLock) {
                try {
                    mediaPlayer.controls().stop()
                } catch (_: Throwable) {}
                try {
                    mediaPlayer.release()
                } catch (_: Throwable) {}
                try {
                    factory.release()
                } catch (_: Throwable) {}
            }

            synchronized(frameLock) {
                currentFrame = null
                composeBitmapA = null
                composeBitmapB = null
                skiaBitmapA = null
                skiaBitmapB = null
                pixelBufferA = null
                pixelBufferB = null
            }
        }, "Clotube-VLC-Release").apply {
            isDaemon = true
            start()
        }
    }

    private object LibVlcLiveSubScale {
        private const val VLC_VAR_TYPE_MASK = 0x00F0
        private const val VLC_VAR_INTEGER = 0x0030

        private val vlcCore: NativeLibrary? by lazy {
            try {
                NativeLibrary.getInstance("libvlccore")
            } catch (_: Throwable) {
                null
            }
        }

        private val isBadReadPtrFn: com.sun.jna.Function? by lazy {
            try {
                NativeLibrary.getInstance("kernel32").getFunction("IsBadReadPtr")
            } catch (_: Throwable) {
                null
            }
        }

        private fun isReadable(ptr: Pointer?, bytes: Long = 16L): Boolean {
            if (ptr == null || ptr == Pointer.NULL) return false
            val addr = Pointer.nativeValue(ptr)
            if (addr < 0x10000L || (addr and 0x7L) != 0L) return false
            val fn = isBadReadPtrFn ?: return true
            return try {
                fn.invokeInt(arrayOf<Any>(ptr, com.sun.jna.platform.win32.BaseTSD.SIZE_T(bytes))) == 0
            } catch (_: Throwable) {
                false
            }
        }

        fun applyLiveScale(mediaPlayer: MediaPlayer, scalePercent: Int) {
            val core = vlcCore ?: return
            val clamped = scalePercent.coerceIn(25, 300)

            try {
                val configPutInt = core.getFunction("config_PutInt")
                configPutInt.invokeVoid(arrayOf<Any>("sub-text-scale", clamped.toLong()))
            } catch (_: Throwable) {}

            val varTypeFn = try {
                core.getFunction("var_Type")
            } catch (_: Throwable) {
                return
            }

            val varSetChecked = try {
                core.getFunction("var_SetChecked")
            } catch (_: Throwable) {
                return
            }

            val mpPtr = try {
                mediaPlayer.mediaPlayerInstance()?.pointer
            } catch (_: Throwable) {
                null
            } ?: return
            if (!isReadable(mpPtr, 160L)) return

            var updatedCount = 0

            fun getObjectType(obj: Pointer?): String? {
                if (!isReadable(obj, 40L)) return null
                return try {
                    val typeStrPtr = obj!!.getPointer(0L)
                    if (!isReadable(typeStrPtr, 16L)) null else typeStrPtr.getString(0L)
                } catch (_: Throwable) {
                    null
                }
            }

            fun applyToObjectIfHasVar(obj: Pointer?) {
                if (!isReadable(obj, 40L)) return
                val target = obj ?: return
                try {
                    val existingType = varTypeFn.invokeInt(arrayOf<Any>(target, "sub-text-scale"))
                    if ((existingType and VLC_VAR_TYPE_MASK) == VLC_VAR_INTEGER) {
                        varSetChecked.invokeInt(
                            arrayOf<Any>(target, "sub-text-scale", VLC_VAR_INTEGER, clamped.toLong())
                        )
                        updatedCount++
                    }
                } catch (_: Throwable) {}
            }

            try {
                val findNameFn = core.getFunction("vlc_object_find_name")
                val releaseFn = core.getFunction("vlc_object_release")
                val vmemPtr = findNameFn.invokePointer(arrayOf<Any>(mpPtr, "vmem"))
                if (isReadable(vmemPtr, 40L) && vmemPtr != null) {
                    try {
                        applyToObjectIfHasVar(vmemPtr)
                        val parentVout = vmemPtr.getPointer(24L)
                        if (isReadable(parentVout, 40L) && parentVout != null) {
                            applyToObjectIfHasVar(parentVout)
                            val grandParent = parentVout.getPointer(24L)
                            applyToObjectIfHasVar(grandParent)
                        }
                    } finally {
                        releaseFn.invokeVoid(arrayOf<Any>(vmemPtr))
                    }
                }
            } catch (_: Throwable) {}

            if (updatedCount == 0) {
                try {
                    for (mpOffset in 40L..224L step 8L) {
                        val resPtr = mpPtr.getPointer(mpOffset)
                        if (!isReadable(resPtr, 224L)) continue
                        if (resPtr.getPointer(8L) != mpPtr) continue

                        for (resOffset in 104L..224L step 8L) {
                            val entryPtr = resPtr.getPointer(resOffset)
                            if (!isReadable(entryPtr, 40L)) continue

                            if (getObjectType(entryPtr) == "video output") {
                                applyToObjectIfHasVar(entryPtr)
                            } else {
                                val firstVout = entryPtr.getPointer(0L)
                                if (getObjectType(firstVout) == "video output") {
                                    applyToObjectIfHasVar(firstVout)
                                }
                            }
                        }
                        break
                    }
                } catch (_: Throwable) {}
            }
        }
    }
}
