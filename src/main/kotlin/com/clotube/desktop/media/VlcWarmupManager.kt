package com.clotube.desktop.media

import com.sun.jna.NativeLibrary
import com.sun.jna.platform.win32.Kernel32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import java.io.File
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

object VlcWarmupManager {

    private var bundledVlcDir: File? = null
    private val warmupMutex = Mutex()

    @Volatile
    private var completedThisProcess = false

    @Volatile
    private var verifiedReady = false

    fun configure(vlcDir: File?) {
        bundledVlcDir = vlcDir
    }

    /**
     * Returns true if this is the first boot after an app install/update
     * OR the first boot after a Windows restart (cold Standby RAM & Defender cache).
     */
    fun needsFirstBootSetup(): Boolean {
        if (completedThisProcess && verifiedReady) return false
        val vlcDir = bundledVlcDir ?: return false
        val libVlcFile = File(vlcDir, "libvlc.dll")
        if (!vlcDir.exists() || !libVlcFile.exists()) return false

        val stateFile = getWarmupStateFile()
        if (!stateFile.exists()) return true

        return try {
            val props = Properties().apply {
                stateFile.inputStream().use { load(it) }
            }
            val savedPath = props.getProperty("vlcPath", "")
            val savedLibMtime = props.getProperty("libVlcMtime", "0").toLongOrNull() ?: 0L
            val savedBootEpochSec = props.getProperty("osBootEpochSec", "0").toLongOrNull() ?: 0L
            val savedVerified = props.getProperty("verified", "false").toBoolean()

            val currentLibMtime = libVlcFile.lastModified()
            val currentBootEpochSec = getWindowsBootEpochSeconds()

            val installChanged = savedPath != vlcDir.absolutePath || savedLibMtime != currentLibMtime
            val osRebooted = currentBootEpochSec > 0L && abs(currentBootEpochSec - savedBootEpochSec) > 30L

            !savedVerified || installChanged || osRebooted
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Caches all VLC DLLs and plugins into Windows Standby RAM, updates plugins.dat if stale,
     * pre-warms LibVLC's module bank, and verifies readiness before returning true.
     */
    suspend fun ensureWarmedUpAndVerified(): Boolean {
        if (completedThisProcess && verifiedReady) return true

        return warmupMutex.withLock {
            if (completedThisProcess && verifiedReady) return@withLock true

            val vlcDir = bundledVlcDir
            if (vlcDir == null || !vlcDir.exists()) {
                completedThisProcess = true
                verifiedReady = true
                return@withLock true
            }

            val startMs = System.currentTimeMillis()

            // Phase A (Dispatchers.IO): Regenerate plugins.dat if stale & page all DLLs into OS Standby RAM
            val (cachedCount, totalTargets, nativeLibsMapped) = withContext(Dispatchers.IO) {
                ensureValidPluginCache(vlcDir)

                val allBinaries = vlcDir.walkTopDown()
                    .filter { file ->
                        file.isFile && (
                            file.name.endsWith(".dll", ignoreCase = true) ||
                            file.name.equals("plugins.dat", ignoreCase = true)
                        )
                    }
                    .toList()

                val cachedCounter = AtomicInteger(0)
                val ioPool = Executors.newFixedThreadPool(4) { r ->
                    Thread(r, "Clotube-VLC-CacheWorker").apply { isDaemon = true }
                }

                try {
                    val tasks = allBinaries.map { file ->
                        Callable {
                            try {
                                val buffer = ByteArray(128 * 1024)
                                file.inputStream().use { input ->
                                    while (input.read(buffer) != -1) {
                                        // Pages file blocks into Windows Standby RAM & triggers Defender scan
                                    }
                                }
                                cachedCounter.incrementAndGet()
                            } catch (_: Throwable) {}
                        }
                    }
                    ioPool.invokeAll(tasks)
                } finally {
                    ioPool.shutdownNow()
                }

                val mapped = try {
                    val coreLib = NativeLibrary.getInstance(RuntimeUtil.getLibVlcCoreLibraryName())
                    val vlcLib = NativeLibrary.getInstance(RuntimeUtil.getLibVlcLibraryName())
                    coreLib != null && vlcLib != null
                } catch (_: Throwable) {
                    false
                }

                Triple(cachedCounter.get(), allBinaries.size, mapped)
            }

            // Phase B (Dispatchers.Main): Run a one-time LibVLC engine probe so libvlc_new()
            // warms up plugins.dat & internal module tables before the user clicks a movie/series
            val engineProbed = withContext(Dispatchers.Main) {
                try {
                    val probeFactory = MediaPlayerFactory(
                        "--no-video-title-show",
                        "--no-sub-autodetect-file",
                        "--quiet"
                    )
                    probeFactory.release()
                    true
                } catch (t: Throwable) {
                    System.err.println("[VlcWarmupManager] Engine probe warning: ${t.message}")
                    false
                }
            }

            // Phase C: Strict Verification Check
            val pluginsDat = File(vlcDir, "plugins/plugins.dat")
            val pluginsCacheOk = !File(vlcDir, "plugins").exists() ||
                (pluginsDat.exists() && pluginsDat.length() > 0L)
            val allFilesCached = totalTargets == 0 || cachedCount >= totalTargets

            val allReady = pluginsCacheOk && allFilesCached && nativeLibsMapped && engineProbed
            verifiedReady = allReady
            completedThisProcess = true

            if (allReady) {
                saveWarmupState(vlcDir)
            }

            println(
                "[VlcWarmupManager] Warm-up & verification finished in ${System.currentTimeMillis() - startMs} ms " +
                "(binaries=$cachedCount/$totalTargets, pluginsDat=$pluginsCacheOk, jna=$nativeLibsMapped, engine=$engineProbed)"
            )
            allReady
        }
    }

    private fun ensureValidPluginCache(vlcDir: File) {
        val cacheGenExe = File(vlcDir, "vlc-cache-gen.exe")
        val pluginsDir = File(vlcDir, "plugins")
        val pluginsDat = File(pluginsDir, "plugins.dat")

        if (!cacheGenExe.exists() || !pluginsDir.exists()) return

        val newestDllMtime = pluginsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".dll", ignoreCase = true) }
            .maxOfOrNull { it.lastModified() } ?: 0L

        val isStale = !pluginsDat.exists() ||
            pluginsDat.length() == 0L ||
            pluginsDat.lastModified() < newestDllMtime

        if (isStale && pluginsDir.canWrite()) {
            try {
                val process = ProcessBuilder(cacheGenExe.absolutePath, pluginsDir.absolutePath)
                    .directory(vlcDir)
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor(20, TimeUnit.SECONDS)
                println("[VlcWarmupManager] Regenerated post-install plugins.dat cache.")
            } catch (t: Throwable) {
                System.err.println("[VlcWarmupManager] plugins.dat regeneration skipped: ${t.message}")
            }
        }
    }

    private fun getWindowsBootEpochSeconds(): Long {
        return try {
            val uptimeMs = Kernel32.INSTANCE.GetTickCount64()
            (System.currentTimeMillis() - uptimeMs) / 1000L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun getWarmupStateFile(): File {
        val appData = System.getenv("APPDATA")
        val cacheDir = if (!appData.isNullOrBlank()) {
            File(appData, "Clotube/cache")
        } else {
            File(System.getProperty("user.home"), ".clotube/cache")
        }
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File(cacheDir, "vlc_warmup_state.properties")
    }

    private fun saveWarmupState(vlcDir: File) {
        try {
            val props = Properties().apply {
                setProperty("vlcPath", vlcDir.absolutePath)
                setProperty("libVlcMtime", File(vlcDir, "libvlc.dll").lastModified().toString())
                setProperty("osBootEpochSec", getWindowsBootEpochSeconds().toString())
                setProperty("verified", "true")
            }
            getWarmupStateFile().outputStream().use { out ->
                props.store(out, "Clotube LibVLC Cold-Boot Cache State")
            }
        } catch (_: Throwable) {}
    }
}