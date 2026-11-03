package com.clotube.desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ClotubeSettings(
    // 1. Account & Drive
    val autoSyncHistory: Boolean = true,
    val deltaScanOnStartup: Boolean = true,

    // 2. Playback Engine
    val bufferDepthMs: Int = 3000,
    val autoPlayNextEpisode: Boolean = true,
    val resumePlayback: Boolean = true,
    val seekStepSeconds: Int = 10,
    val defaultSpeed: Float = 1.0f,

    // 3. Display & Performance
    val hardwareAcceleration: Boolean = true,
    val cinemaColorEnhancement: Boolean = true, // Expands limited range to full 0-255 & enriches saturation
    val batterySaver60Fps: Boolean = false,
    val backgroundEcoMode: Boolean = true,
    val launchMaximized: Boolean = true,

    // 4. Audio & Subtitles
    val rememberVolume: Boolean = true,
    val defaultSubtitleState: String = "Off",
    val subtitleFontSizeSp: Int = 18,
    val preferredAudioLanguage: String = "Original"
)

object DesktopAppSettings {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var saveJob: Job? = null
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val settingsFile: File by lazy {
        File(DesktopProfileManager.appDataRoot, "settings.json")
    }

    var state by mutableStateOf(load())
        private set

    private fun load(): ClotubeSettings {
        return try {
            if (settingsFile.exists() && settingsFile.length() > 0) {
                val json = settingsFile.readText(Charsets.UTF_8)
                gson.fromJson(json, ClotubeSettings::class.java) ?: ClotubeSettings()
            } else {
                ClotubeSettings()
            }
        } catch (_: Exception) {
            ClotubeSettings()
        }
    }

    fun update(transform: ClotubeSettings.() -> ClotubeSettings) {
        state = state.transform()
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400L) // Debounce writes
            try {
                settingsFile.writeText(gson.toJson(state), Charsets.UTF_8)
            } catch (e: Exception) {
                System.err.println("[DesktopAppSettings] Failed to write settings: ${e.message}")
            }
        }
    }

    suspend fun calculateCacheSizeBytes(userEmail: String): Long = withContext(Dispatchers.IO) {
        var total = 0L
        try {
            val thumbDir = DesktopProfileManager.getThumbnailsDir(userEmail)
            if (thumbDir.exists()) {
                thumbDir.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
            }
            val seriesDir = DesktopProfileManager.getSeriesDir(userEmail)
            if (seriesDir.exists()) {
                seriesDir.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
            }
        } catch (_: Exception) {}
        total
    }

    suspend fun clearThumbnailCache(userEmail: String) = withContext(Dispatchers.IO) {
        try {
            val thumbDir = DesktopProfileManager.getThumbnailsDir(userEmail)
            if (thumbDir.exists()) {
                thumbDir.deleteRecursively()
                thumbDir.mkdirs()
            }
        } catch (_: Exception) {}
    }
}

