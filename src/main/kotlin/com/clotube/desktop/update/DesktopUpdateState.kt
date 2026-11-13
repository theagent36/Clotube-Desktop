package com.clotube.desktop.update

import com.google.gson.GsonBuilder
import java.io.File

data class PersistedUpdateState(
    val skippedVersion: String? = null,
    val remindLaterVersion: String? = null,
    val remindLaterTimestamp: Long = 0L,
    val pendingInstallerPath: String? = null,
    val pendingVersion: String? = null,
    val pendingReleaseNotes: String? = null
)

data class GithubReleaseInfo(
    val version: String,
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val msiDownloadUrl: String,
    val msiFileName: String,
    val msiSizeBytes: Long
)

sealed class UpdateUiState {
    data object Hidden : UpdateUiState()
    data class Available(
        val currentVersion: String,
        val release: GithubReleaseInfo,
        val showDownloadOptions: Boolean = false
    ) : UpdateUiState()
    data class Downloading(
        val currentVersion: String,
        val release: GithubReleaseInfo,
        val installImmediatelyAfter: Boolean,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressFraction: Float
    ) : UpdateUiState()
    data class ReadyToInstall(
        val currentVersion: String,
        val version: String,
        val releaseNotes: String,
        val installerFile: File,
        val scheduledForNextBoot: Boolean = false
    ) : UpdateUiState()
    data class Installing(
        val version: String
    ) : UpdateUiState()
    data class Error(
        val message: String,
        val retryRelease: GithubReleaseInfo? = null
    ) : UpdateUiState()
}

object DesktopUpdateStore {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    const val REMIND_LATER_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L // Exactly 24 hours

    val appDataRoot: File by lazy {
        val appData = System.getenv("APPDATA")
        val base = if (!appData.isNullOrBlank()) {
            File(appData, "Clotube")
        } else {
            File(System.getProperty("user.home"), ".clotube")
        }
        if (!base.exists()) base.mkdirs()
        base
    }

    val updatesDir: File by lazy {
        File(appDataRoot, "updates").apply {
            if (!exists()) mkdirs()
        }
    }

    private val stateFile: File by lazy {
        File(appDataRoot, "update_state.json")
    }

    @Synchronized
    fun load(): PersistedUpdateState {
        return try {
            if (stateFile.exists() && stateFile.length() > 0L) {
                gson.fromJson(stateFile.readText(Charsets.UTF_8), PersistedUpdateState::class.java)
                    ?: PersistedUpdateState()
            } else {
                PersistedUpdateState()
            }
        } catch (e: Exception) {
            System.err.println("[DesktopUpdateStore] Failed to read update_state.json: ${e.message}")
            PersistedUpdateState()
        }
    }

    @Synchronized
    fun save(state: PersistedUpdateState) {
        try {
            stateFile.writeText(gson.toJson(state), Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("[DesktopUpdateStore] Failed to save update_state.json: ${e.message}")
        }
    }

    /**
     * Normalizes tags such as "v2.0.2" or "2.0.2-beta" into clean numeric parts [2, 0, 2].
     */
    fun normalizeVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V").substringBefore("-").trim()
    }

    /**
     * Compares two semantic version strings (e.g. "2.0.10" > "2.0.2").
     * Returns > 0 if remote > local, 0 if equal, < 0 if remote < local.
     */
    fun compareVersions(remoteRaw: String, localRaw: String): Int {
        val remoteParts = normalizeVersion(remoteRaw).split(".").map { it.toIntOrNull() ?: 0 }
        val localParts = normalizeVersion(localRaw).split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(remoteParts.size, localParts.size)

        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r.compareTo(l)
        }
        return 0
    }
}

