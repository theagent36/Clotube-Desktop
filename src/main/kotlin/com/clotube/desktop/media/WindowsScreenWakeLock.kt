package com.clotube.desktop.media

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prevents Windows from turning off the laptop display or entering idle sleep
 * while one or more video players are actively playing.
 *
 * Uses a single dedicated daemon thread because Windows `SetThreadExecutionState`
 * is strictly thread-affine: flags must be set and cleared on the same OS thread.
 */
object WindowsScreenWakeLock {
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002
    private const val ES_CONTINUOUS = 0x80000000.toInt()

    private interface Kernel32Power : StdCallLibrary {
        fun SetThreadExecutionState(esFlags: Int): Int
    }

    private val kernel32: Kernel32Power? by lazy {
        try {
            if (System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)) {
                Native.load("kernel32", Kernel32Power::class.java)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private val activePlayers = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val lock = Any()
    private val isWakeLockHeld = AtomicBoolean(false)

    private val wakeExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Clotube-ScreenWakeLock").apply {
            isDaemon = true
        }
    }

    private var heartbeatTask: ScheduledFuture<*>? = null

    init {
        try {
            Runtime.getRuntime().addShutdownHook(Thread({
                clearAll()
            }, "Clotube-WakeLock-Shutdown"))
        } catch (_: Throwable) {}
    }

    /**
     * Updates whether [playerOwner] is currently playing video.
     * Safe to call from any thread (LibVLC native callbacks, Compose UI, or teardown threads).
     */
    fun setPlaybackActive(playerOwner: Any, isPlaying: Boolean) {
        val shouldHoldLock: Boolean
        synchronized(lock) {
            if (isPlaying) {
                activePlayers.add(playerOwner)
            } else {
                activePlayers.remove(playerOwner)
            }
            shouldHoldLock = activePlayers.isNotEmpty()
        }

        if (shouldHoldLock) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }
    }

    fun clearAll() {
        synchronized(lock) {
            activePlayers.clear()
        }
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (!isWakeLockHeld.compareAndSet(false, true)) return

        wakeExecutor.execute {
            applyExecutionState(preventSleep = true)
        }

        synchronized(lock) {
            if (heartbeatTask == null || heartbeatTask?.isCancelled == true) {
                heartbeatTask = wakeExecutor.scheduleAtFixedRate({
                    if (isWakeLockHeld.get()) {
                        applyExecutionState(preventSleep = true)
                    }
                }, 25L, 25L, TimeUnit.SECONDS)
            }
        }
    }

    private fun releaseWakeLock() {
        if (!isWakeLockHeld.compareAndSet(true, false)) return

        synchronized(lock) {
            heartbeatTask?.cancel(false)
            heartbeatTask = null
        }

        wakeExecutor.execute {
            applyExecutionState(preventSleep = false)
        }
    }

    private fun applyExecutionState(preventSleep: Boolean) {
        try {
            val k32 = kernel32 ?: return
            val flags = if (preventSleep) {
                ES_CONTINUOUS or ES_DISPLAY_REQUIRED or ES_SYSTEM_REQUIRED
            } else {
                ES_CONTINUOUS
            }
            k32.SetThreadExecutionState(flags)
        } catch (_: Throwable) {}
    }
}

