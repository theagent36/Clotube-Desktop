package com.clotube.desktop.update

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import kotlin.system.exitProcess

object DesktopUpdateManager {
    private const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/theagent36/Clotube-Desktop/releases/latest"

    // Fallback version used only when running via `.\gradlew.bat run` (where jpackage.app-version is null)
    private const val DEV_FALLBACK_VERSION = "2.0.7"

    private const val MAX_DOWNLOAD_ATTEMPTS = 5
    private const val CONNECT_TIMEOUT_MS = 25_000
    private const val READ_TIMEOUT_MS = 30_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeDownloadJob: Job? = null

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private val _isCheckingManually = MutableStateFlow(false)
    val isCheckingManually: StateFlow<Boolean> = _isCheckingManually.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    data class ReleaseNotesDialogState(
        val isVisible: Boolean = false,
        val isAutomaticPostUpdate: Boolean = false,
        val currentAppVersion: String = "",
        val release: CachedReleaseNoteEntry? = null,
        val isLoading: Boolean = false
    )

    private val _releaseNotesState = MutableStateFlow(ReleaseNotesDialogState())
    val releaseNotesState: StateFlow<ReleaseNotesDialogState> = _releaseNotesState.asStateFlow()

    private val _isHomeScreenActive = MutableStateFlow(false)
    val isHomeScreenActive: StateFlow<Boolean> = _isHomeScreenActive.asStateFlow()

    private var pendingAutomaticReleaseNotes = false

    fun onHomeScreenEntered() {
        _isHomeScreenActive.value = true
        if (pendingAutomaticReleaseNotes) {
            pendingAutomaticReleaseNotes = false
            triggerAutomaticReleaseNotesPopup()
        }
    }

    fun onHomeScreenExited() {
        _isHomeScreenActive.value = false
    }

    private fun getEmbeddedLatestRelease(version: String): CachedReleaseNoteEntry {
        val body = """
            ### Resilient In-App Updater & Seamless Installation
            - **IPv4 Connection Priority & Auto-Retry:** Resolves dual-stack connection stalls and connects reliably over IPv4 with up to 5 progressive retries.
            - **HTTP Range Resumption:** Automatically resumes partial `.msi` downloads instead of restarting from zero.
            - **Silent GUI Installation:** Background VBScript runner via `wscript.exe` eliminates command prompt window popups and batch file errors during updates.
            - **Next App Boot Scheduling:** Schedules pending updates cleanly to install on the next Clotube launch.

            ### First-Boot Optimization & Playback Readiness
            - **First-Boot Setup Indicator:** Added a setup spinner below the boot logo on first launch after install, update, or Windows restart.
            - **Full Parallel LibVLC Caching:** Pre-warms bundled LibVLC DLLs and initializes module bank before entering the Home screen.

            ### Playback Stability & Watchlist Integration
            - **Watchlist & Off-Drive Artwork:** Automatic poster thumbnail derivation and caching for custom movies and TV series not in Google Drive.
            - **Display Sleep Prevention:** Keeps your Windows laptop screen awake automatically while video playback is active.
            - **Instant Scrubber Sync:** Play Next (`N`) scrubber instant reset and robust watch history synchronization.
        """.trimIndent()

        return CachedReleaseNoteEntry(
            version = version,
            tagName = "v$version",
            title = "What's New in Clotube v$version",
            body = body
        )
    }

    fun getLatestReleaseNotes(targetVersion: String = getCurrentAppVersion()): CachedReleaseNoteEntry {
        val current = DesktopUpdateStore.load()
        val cached = current.cachedLatestRelease
        if (cached != null && DesktopUpdateStore.normalizeVersion(cached.version) == DesktopUpdateStore.normalizeVersion(targetVersion)) {
            return cached
        }
        if (cached != null && cached.version.isNotBlank()) {
            return cached
        }
        return getEmbeddedLatestRelease(targetVersion)
    }

    private fun cacheLatestRelease(entry: CachedReleaseNoteEntry) {
        val current = DesktopUpdateStore.load()
        DesktopUpdateStore.save(current.copy(cachedLatestRelease = entry))
    }

    fun openReleaseNotes(targetVersion: String = getCurrentAppVersion()) {
        val currentVersion = getCurrentAppVersion()
        val initialRelease = getLatestReleaseNotes(targetVersion)

        _releaseNotesState.value = ReleaseNotesDialogState(
            isVisible = true,
            isAutomaticPostUpdate = false,
            currentAppVersion = currentVersion,
            release = initialRelease,
            isLoading = true
        )

        scope.launch {
            val latestGithub = fetchLatestGithubRelease()
            if (latestGithub != null) {
                val entry = CachedReleaseNoteEntry(
                    version = latestGithub.version,
                    tagName = latestGithub.tagName,
                    title = latestGithub.releaseName,
                    body = latestGithub.releaseNotes,
                    publishedAt = ""
                )
                cacheLatestRelease(entry)
                if (_releaseNotesState.value.isVisible) {
                    _releaseNotesState.value = _releaseNotesState.value.copy(
                        release = entry,
                        isLoading = false
                    )
                }
            } else {
                if (_releaseNotesState.value.isVisible) {
                    _releaseNotesState.value = _releaseNotesState.value.copy(isLoading = false)
                }
            }
        }
    }

    fun triggerAutomaticReleaseNotesPopup() {
        val currentVersion = getCurrentAppVersion()
        val initialRelease = getLatestReleaseNotes(currentVersion)

        _releaseNotesState.value = ReleaseNotesDialogState(
            isVisible = true,
            isAutomaticPostUpdate = true,
            currentAppVersion = currentVersion,
            release = initialRelease,
            isLoading = true
        )

        scope.launch {
            val latestGithub = fetchLatestGithubRelease()
            if (latestGithub != null) {
                val entry = CachedReleaseNoteEntry(
                    version = latestGithub.version,
                    tagName = latestGithub.tagName,
                    title = latestGithub.releaseName,
                    body = latestGithub.releaseNotes,
                    publishedAt = ""
                )
                cacheLatestRelease(entry)
                if (_releaseNotesState.value.isVisible) {
                    _releaseNotesState.value = _releaseNotesState.value.copy(
                        release = entry,
                        isLoading = false
                    )
                }
            } else {
                if (_releaseNotesState.value.isVisible) {
                    _releaseNotesState.value = _releaseNotesState.value.copy(isLoading = false)
                }
            }
        }
    }

    fun dismissReleaseNotes() {
        val currentVersion = getCurrentAppVersion()
        val saved = DesktopUpdateStore.load()
        DesktopUpdateStore.save(saved.copy(lastSeenReleaseNotesVersion = currentVersion))
        _releaseNotesState.value = _releaseNotesState.value.copy(isVisible = false)
    }

    fun getCurrentAppVersion(): String {
        val packagedVersion = System.getProperty("jpackage.app-version")
        return if (!packagedVersion.isNullOrBlank()) {
            DesktopUpdateStore.normalizeVersion(packagedVersion)
        } else {
            DEV_FALLBACK_VERSION
        }
    }

    fun isPackagedBuild(): Boolean {
        return !System.getProperty("jpackage.app-version").isNullOrBlank()
    }

    /**
     * Called automatically on app startup (after a brief delay).
     * 1. Detects post-update state and displays Release Notes modal if newly upgraded (gated to Home screen).
     * 2. Cleans up obsolete `.msi` installers if the app is already running the upgraded version.
     * 3. Surfaces an already-downloaded pending `.msi` if one is waiting on disk.
     * 4. Queries GitHub Releases while honoring Skip Version and 24-hour Remind Me Later rules.
     */
    fun checkForUpdatesOnStartup() {
        scope.launch {
            val currentVersion = getCurrentAppVersion()
            var saved = DesktopUpdateStore.load()

            // Detect if the app was just updated to a newer version
            val isPostUpdate = when {
                saved.lastSeenReleaseNotesVersion != null ->
                    DesktopUpdateStore.compareVersions(currentVersion, saved.lastSeenReleaseNotesVersion) > 0
                saved.pendingVersion != null ->
                    DesktopUpdateStore.compareVersions(currentVersion, saved.pendingVersion) >= 0
                else -> false
            }

            if (saved.lastSeenReleaseNotesVersion == null && saved.pendingVersion == null) {
                // First-time fresh install: save silently without popping up release notes
                saved = saved.copy(lastSeenReleaseNotesVersion = currentVersion)
                DesktopUpdateStore.save(saved)
            }

            if (isPostUpdate) {
                if (_isHomeScreenActive.value) {
                    triggerAutomaticReleaseNotesPopup()
                } else {
                    pendingAutomaticReleaseNotes = true
                }
            }

            // 1. Clean up pending installer if we have already upgraded to or past it
            val pendingPath = saved.pendingInstallerPath
            val pendingVer = saved.pendingVersion
            if (!pendingPath.isNullOrBlank() && !pendingVer.isNullOrBlank()) {
                val pendingFile = File(pendingPath)
                if (DesktopUpdateStore.compareVersions(pendingVer, currentVersion) <= 0) {
                    cleanupOldInstallers(keepFileName = null)
                    DesktopUpdateStore.save(
                        saved.copy(
                            pendingInstallerPath = null,
                            pendingVersion = null,
                            pendingReleaseNotes = null,
                            installOnNextAppBoot = false
                        )
                    )
                } else if (pendingFile.exists() && pendingFile.length() > 0L) {
                    if (saved.installOnNextAppBoot) {
                        // User chose "Install on Next Boot" -> execute silently now on app boot
                        DesktopUpdateStore.save(saved.copy(installOnNextAppBoot = false))
                        installNowAndRelaunch(pendingFile, pendingVer)
                        return@launch
                    } else {
                        // Downloaded earlier without scheduling auto-install -> prompt ready state
                        _uiState.value = UpdateUiState.ReadyToInstall(
                            currentVersion = currentVersion,
                            version = pendingVer,
                            releaseNotes = saved.pendingReleaseNotes.orEmpty(),
                            installerFile = pendingFile,
                            scheduledForNextBoot = false
                        )
                        return@launch
                    }
                }
            }

            // 2. Query GitHub Releases API
            val latest = fetchLatestGithubRelease() ?: return@launch
            if (DesktopUpdateStore.compareVersions(latest.version, currentVersion) <= 0) {
                return@launch
            }

            // Rule 1: Skip this version (stay silent until a newer version > skippedVersion releases)
            val skipped = saved.skippedVersion
            if (!skipped.isNullOrBlank() && DesktopUpdateStore.compareVersions(latest.version, skipped) <= 0) {
                println("[DesktopUpdateManager] Version ${latest.version} is skipped by user preference.")
                return@launch
            }

            // Rule 2: Remind me Later (stay silent for exactly 24 hours for this version)
            if (saved.remindLaterVersion == latest.version && saved.remindLaterTimestamp > 0L) {
                val elapsedMs = System.currentTimeMillis() - saved.remindLaterTimestamp
                if (elapsedMs < DesktopUpdateStore.REMIND_LATER_INTERVAL_MS) {
                    val remainingHours = (DesktopUpdateStore.REMIND_LATER_INTERVAL_MS - elapsedMs) / 3_600_000L
                    println("[DesktopUpdateManager] Remind Me Later active for ${latest.version} (~${remainingHours}h remaining).")
                    return@launch
                }
            }

            _uiState.value = UpdateUiState.Available(
                currentVersion = currentVersion,
                release = latest,
                showDownloadOptions = false
            )
        }
    }

    /**
     * Triggered from Settings -> "Check for Updates".
     * Bypasses Skip and Remind Me Later filters so the user can always pull an update manually.
     */
    fun checkForUpdatesManually() {
        if (_isCheckingManually.value) return
        scope.launch {
            _isCheckingManually.value = true
            _statusBannerMessage.value = null
            try {
                val currentVersion = getCurrentAppVersion()
                val latest = fetchLatestGithubRelease()
                if (latest == null) {
                    _statusBannerMessage.value = "Unable to reach GitHub Releases. Check your internet connection."
                    return@launch
                }

                if (DesktopUpdateStore.compareVersions(latest.version, currentVersion) <= 0) {
                    _statusBannerMessage.value = "You're up to date (v$currentVersion)."
                } else {
                    _uiState.value = UpdateUiState.Available(
                        currentVersion = currentVersion,
                        release = latest,
                        showDownloadOptions = false
                    )
                }
            } finally {
                _isCheckingManually.value = false
            }
        }
    }

    fun clearStatusBanner() {
        _statusBannerMessage.value = null
    }

    /**
     * Option 1: Skip this version permanently until a strictly newer version is released.
     */
    fun onSkipThisVersion(release: GithubReleaseInfo) {
        val current = DesktopUpdateStore.load()
        DesktopUpdateStore.save(
            current.copy(
                skippedVersion = release.version,
                remindLaterVersion = null,
                remindLaterTimestamp = 0L
            )
        )
        _uiState.value = UpdateUiState.Hidden
    }

    /**
     * Option 2: Record exact timestamp and re-prompt on next app launch >= 24 hours later.
     */
    fun onRemindMeLater(release: GithubReleaseInfo) {
        val current = DesktopUpdateStore.load()
        DesktopUpdateStore.save(
            current.copy(
                remindLaterVersion = release.version,
                remindLaterTimestamp = System.currentTimeMillis()
            )
        )
        _uiState.value = UpdateUiState.Hidden
    }

    /**
     * Option 3 (Step A): Expands the "Download" choice into "Download" vs "Download & Install".
     */
    fun onExpandDownloadOptions() {
        val current = _uiState.value
        if (current is UpdateUiState.Available) {
            _uiState.value = current.copy(showDownloadOptions = true)
        }
    }

    /**
     * Option 3 (Step B): Downloads the `.msi` installer from GitHub Releases.
     * - If `installImmediately == false`: Transitions to ReadyToInstall ("Install Now" vs "Install on Next Boot").
     * - If `installImmediately == true`: Automatically runs `msiexec` and relaunches the app.
     */
    fun startDownload(release: GithubReleaseInfo, installImmediately: Boolean) {
        if (activeDownloadJob?.isActive == true) return
        val currentVersion = getCurrentAppVersion()

        activeDownloadJob = scope.launch {
            val targetFile = File(DesktopUpdateStore.updatesDir, release.msiFileName)
            val tempFile = File(DesktopUpdateStore.updatesDir, "${release.msiFileName}.tmp")

            if (targetFile.exists() && release.msiSizeBytes > 0L && targetFile.length() == release.msiSizeBytes) {
                persistPendingInstaller(release, targetFile)
                if (installImmediately) {
                    installNowAndRelaunch(targetFile, release.version)
                } else {
                    _uiState.value = UpdateUiState.ReadyToInstall(
                        currentVersion = currentVersion,
                        version = release.version,
                        releaseNotes = release.releaseNotes,
                        installerFile = targetFile
                    )
                }
                return@launch
            }

            val initialExistingBytes = if (tempFile.exists()) tempFile.length() else 0L
            val initialFraction = if (release.msiSizeBytes > 0L && initialExistingBytes > 0L) {
                (initialExistingBytes.toFloat() / release.msiSizeBytes.toFloat()).coerceIn(0f, 0.99f)
            } else {
                0f
            }

            _uiState.value = UpdateUiState.Downloading(
                currentVersion = currentVersion,
                release = release,
                installImmediatelyAfter = installImmediately,
                bytesDownloaded = initialExistingBytes,
                totalBytes = release.msiSizeBytes,
                progressFraction = initialFraction,
                statusDetail = "Connecting to GitHub release server…"
            )

            try {
                downloadWithAutoRetry(
                    initialUrl = release.msiDownloadUrl,
                    destination = tempFile,
                    expectedTotalBytes = release.msiSizeBytes,
                    onStatus = { statusText, downloaded, total ->
                        val fraction = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                        _uiState.value = UpdateUiState.Downloading(
                            currentVersion = currentVersion,
                            release = release,
                            installImmediatelyAfter = installImmediately,
                            bytesDownloaded = downloaded,
                            totalBytes = total,
                            progressFraction = fraction,
                            statusDetail = statusText
                        )
                    },
                    onProgress = { downloaded, total ->
                        val fraction = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                        _uiState.value = UpdateUiState.Downloading(
                            currentVersion = currentVersion,
                            release = release,
                            installImmediatelyAfter = installImmediately,
                            bytesDownloaded = downloaded,
                            totalBytes = total,
                            progressFraction = fraction,
                            statusDetail = null
                        )
                    }
                )

                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }

                cleanupOldInstallers(keepFileName = targetFile.name)
                persistPendingInstaller(release, targetFile)

                if (installImmediately) {
                    installNowAndRelaunch(targetFile, release.version)
                } else {
                    _uiState.value = UpdateUiState.ReadyToInstall(
                        currentVersion = currentVersion,
                        version = release.version,
                        releaseNotes = release.releaseNotes,
                        installerFile = targetFile
                    )
                }
            } catch (e: CancellationException) {
                // Keep partial .tmp file on cancel so future resume can reuse downloaded bytes
                _uiState.value = UpdateUiState.Hidden
            } catch (e: Exception) {
                System.err.println("[DesktopUpdateManager] Download failed after $MAX_DOWNLOAD_ATTEMPTS attempts: ${e.message}")
                _uiState.value = UpdateUiState.Error(
                    message = "Download failed after $MAX_DOWNLOAD_ATTEMPTS attempts: ${e.message ?: "Network timeout"}",
                    retryRelease = release
                )
            }
        }
    }

    private suspend fun downloadWithAutoRetry(
        initialUrl: String,
        destination: File,
        expectedTotalBytes: Long,
        onStatus: (statusText: String, downloaded: Long, total: Long) -> Unit,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val backoffDelaysMs = longArrayOf(1200L, 2500L, 4000L, 6000L)
        var lastError: Exception? = null

        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            val existingBytes = if (destination.exists()) destination.length() else 0L

            if (attempt == 1) {
                onStatus("Connecting to GitHub release server…", existingBytes, expectedTotalBytes)
            } else {
                onStatus(
                    "Connection slow — retrying automatically (attempt $attempt of $MAX_DOWNLOAD_ATTEMPTS)…",
                    existingBytes,
                    expectedTotalBytes
                )
            }

            try {
                downloadFileWithRedirects(
                    initialUrl = initialUrl,
                    destination = destination,
                    expectedTotalBytes = expectedTotalBytes,
                    onProgress = onProgress
                )
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                System.err.println("[DesktopUpdateManager] Attempt $attempt/$MAX_DOWNLOAD_ATTEMPTS failed: ${e.message}")
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    val waitMs = backoffDelaysMs[(attempt - 1).coerceAtMost(backoffDelaysMs.lastIndex)]
                    val currentBytes = if (destination.exists()) destination.length() else 0L
                    onStatus(
                        "Timed out — retrying in ${waitMs / 1000}s (attempt ${attempt + 1} of $MAX_DOWNLOAD_ATTEMPTS)…",
                        currentBytes,
                        expectedTotalBytes
                    )
                    delay(waitMs)
                }
            }
        }

        throw lastError ?: IOException("Connect timed out")
    }

    fun cancelDownload() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        _uiState.value = UpdateUiState.Hidden
    }

    fun dismissDialog() {
        _uiState.value = UpdateUiState.Hidden
    }

    /**
     * Schedules the downloaded `.msi` to install automatically on the next app boot
     * (the next time Clotube is launched), without touching Windows OS startup.
     */
    fun scheduleInstallOnNextBoot(installerFile: File, version: String) {
        scope.launch {
            try {
                // Clean up any legacy HKCU RunOnce entry or old .cmd scripts if present
                runCatching {
                    ProcessBuilder(
                        "reg.exe",
                        "delete",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce",
                        "/v",
                        "ClotubePendingUpdate",
                        "/f"
                    ).start().waitFor()
                }
                File(DesktopUpdateStore.updatesDir, "clotube_next_boot_update.cmd").delete()

                val saved = DesktopUpdateStore.load()
                DesktopUpdateStore.save(
                    saved.copy(
                        pendingInstallerPath = installerFile.absolutePath,
                        pendingVersion = version,
                        installOnNextAppBoot = true
                    )
                )

                val currentState = _uiState.value
                if (currentState is UpdateUiState.ReadyToInstall) {
                    _uiState.value = currentState.copy(scheduledForNextBoot = true)
                } else {
                    _uiState.value = UpdateUiState.Hidden
                }
                println("[DesktopUpdateManager] Scheduled v$version for next app boot.")
            } catch (e: Exception) {
                System.err.println("[DesktopUpdateManager] Failed to schedule next-app-boot update: ${e.message}")
                _uiState.value = UpdateUiState.Error(
                    message = "Could not schedule update for next app boot: ${e.message}"
                )
            }
        }
    }

    /**
     * Executes an atomic in-place `.msi` upgrade via a completely hidden Windows Script Host
     * (wscript.exe, GUI subsystem -> zero console windows) and relaunches Clotube.
     */
    fun installNowAndRelaunch(installerFile: File, version: String) {
        scope.launch {
            try {
                _uiState.value = UpdateUiState.Installing(version = version)

                // Clear installOnNextAppBoot flag and any legacy RunOnce key
                val saved = DesktopUpdateStore.load()
                DesktopUpdateStore.save(saved.copy(installOnNextAppBoot = false))
                runCatching {
                    ProcessBuilder(
                        "reg.exe",
                        "delete",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce",
                        "/v",
                        "ClotubePendingUpdate",
                        "/f"
                    ).start().waitFor()
                }
                File(DesktopUpdateStore.updatesDir, "clotube_install_now.cmd").delete()

                // Resolve exact running Clotube.exe path (supports custom install directories)
                val runningCmd = runCatching { ProcessHandle.current().info().command().orElse(null) }.getOrNull()
                val localAppData = System.getenv("LOCALAPPDATA")
                    ?: File(System.getProperty("user.home"), "AppData\\Local").absolutePath
                val installedExePath = if (!runningCmd.isNullOrBlank() && runningCmd.endsWith("Clotube.exe", ignoreCase = true)) {
                    runningCmd
                } else {
                    File(localAppData, "Clotube\\Clotube.exe").absolutePath
                }

                val escapedMsi = installerFile.absolutePath.replace("\"", "\"\"")
                val escapedExe = installedExePath.replace("\"", "\"\"")

                val vbsScript = File(DesktopUpdateStore.updatesDir, "clotube_install.vbs")
                val vbsContent = buildString {
                    appendLine("On Error Resume Next")
                    appendLine("Set WshShell = CreateObject(\"WScript.Shell\")")
                    appendLine("Set fso = CreateObject(\"Scripting.FileSystemObject\")")
                    appendLine("WScript.Sleep 2000")
                    appendLine("WshShell.Run \"taskkill /F /IM Clotube.exe\", 0, True")
                    appendLine("WScript.Sleep 800")
                    appendLine("msiCmd = \"msiexec.exe /i \"\"$escapedMsi\"\" /passive /norestart\"")
                    appendLine("WshShell.Run msiCmd, 1, True")
                    appendLine("If fso.FileExists(\"$escapedExe\") Then")
                    appendLine("    WshShell.Run \"\"\"$escapedExe\"\"\", 1, False")
                    appendLine("End If")
                    appendLine("fso.DeleteFile WScript.ScriptFullName, True")
                }
                vbsScript.writeText(vbsContent, Charsets.UTF_8)

                // Launch via GUI-subsystem wscript.exe -> never creates a cmd.exe or Terminal window
                ProcessBuilder(
                    "wscript.exe",
                    "//B",
                    "//Nologo",
                    vbsScript.absolutePath
                ).start()

                delay(500L)
                exitProcess(0)
            } catch (e: Exception) {
                System.err.println("[DesktopUpdateManager] Failed to launch installer: ${e.message}")
                _uiState.value = UpdateUiState.Error(
                    message = "Failed to start installer: ${e.message}"
                )
            }
        }
    }

    private fun persistPendingInstaller(release: GithubReleaseInfo, targetFile: File) {
        val current = DesktopUpdateStore.load()
        DesktopUpdateStore.save(
            current.copy(
                remindLaterVersion = null,
                remindLaterTimestamp = 0L,
                pendingInstallerPath = targetFile.absolutePath,
                pendingVersion = release.version,
                pendingReleaseNotes = release.releaseNotes,
                installOnNextAppBoot = false
            )
        )
    }

    private fun cleanupOldInstallers(keepFileName: String?) {
        try {
            DesktopUpdateStore.updatesDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".msi", ignoreCase = true) && file.name != keepFileName) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun fetchLatestGithubRelease(): GithubReleaseInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(GITHUB_LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Clotube-Desktop-Updater")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                System.err.println("[DesktopUpdateManager] GitHub API returned HTTP ${connection.responseCode}")
                return null
            }

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JsonParser.parseString(body).asJsonObject
            if (json.get("draft")?.asBoolean == true) return null

            val tagName = json.get("tag_name")?.asString ?: return null
            val version = DesktopUpdateStore.normalizeVersion(tagName)
            val releaseName = json.get("name")?.asString?.ifBlank { "Clotube v$version" } ?: "Clotube v$version"
            val releaseNotes = json.get("body")?.asString ?: "Performance improvements and bug fixes."

            val assets = json.getAsJsonArray("assets") ?: return null
            var msiAssetUrl: String? = null
            var msiFileName = "Clotube-$version.msi"
            var msiSize = 0L

            for (elem in assets) {
                val assetObj = elem.asJsonObject
                val name = assetObj.get("name")?.asString ?: continue
                if (name.endsWith(".msi", ignoreCase = true)) {
                    msiAssetUrl = assetObj.get("browser_download_url")?.asString
                    msiFileName = name
                    msiSize = assetObj.get("size")?.asLong ?: 0L
                    break
                }
            }

            if (msiAssetUrl.isNullOrBlank()) {
                System.err.println("[DesktopUpdateManager] Release $tagName has no .msi asset attached.")
                return null
            }

            val releaseInfo = GithubReleaseInfo(
                version = version,
                tagName = tagName,
                releaseName = releaseName,
                releaseNotes = releaseNotes,
                msiDownloadUrl = msiAssetUrl,
                msiFileName = msiFileName,
                msiSizeBytes = msiSize
            )

            // Cache latest release note entry into update_state.json
            val cachedEntry = CachedReleaseNoteEntry(
                version = version,
                tagName = tagName,
                title = releaseName,
                body = releaseNotes,
                publishedAt = ""
            )
            cacheLatestRelease(cachedEntry)

            releaseInfo
        } catch (e: Exception) {
            System.err.println("[DesktopUpdateManager] Error querying GitHub Releases: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun downloadFileWithRedirects(
        initialUrl: String,
        destination: File,
        expectedTotalBytes: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        var currentUrl = initialUrl
        var redirects = 0
        var resumeOffset = if (destination.exists()) destination.length() else 0L

        if (expectedTotalBytes > 0L && resumeOffset >= expectedTotalBytes) {
            destination.delete()
            resumeOffset = 0L
        }

        while (redirects < 8) {
            currentCoroutineContext().ensureActive()
            var connection: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    if (this is HttpsURLConnection) {
                        sslSocketFactory = Ipv4PreferringSslSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory())
                    }
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("User-Agent", "Clotube-Desktop-Updater/2.0")
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("Connection", "keep-alive")
                    if (resumeOffset > 0L) {
                        setRequestProperty("Range", "bytes=$resumeOffset-")
                    }
                }

                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect without Location header")
                    currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        URL(url, location).toString()
                    }
                    redirects++
                    continue
                }

                if (code == 416) {
                    // Range Not Satisfiable -> delete stale partial file and restart from 0
                    if (destination.exists()) destination.delete()
                    resumeOffset = 0L
                    redirects++
                    continue
                }

                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("HTTP $code while downloading update")
                }

                val isPartialAppend = (code == HttpURLConnection.HTTP_PARTIAL && resumeOffset > 0L)
                if (!isPartialAppend && resumeOffset > 0L) {
                    // Server ignored Range header and returned full 200 stream -> restart from 0
                    if (destination.exists()) destination.delete()
                    resumeOffset = 0L
                }

                val responseLength = connection.contentLengthLong
                val totalBytes = when {
                    isPartialAppend && responseLength > 0L -> resumeOffset + responseLength
                    responseLength > 0L -> responseLength
                    else -> expectedTotalBytes
                }

                var downloaded = resumeOffset
                var lastEmitTime = 0L

                onProgress(downloaded, totalBytes)

                connection.inputStream.use { input ->
                    FileOutputStream(destination, isPartialAppend).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= 80L || (totalBytes > 0L && downloaded >= totalBytes)) {
                                lastEmitTime = now
                                onProgress(downloaded, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }
                return
            } finally {
                connection?.disconnect()
            }
        }
        throw IOException("Too many redirects while downloading update")
    }

    /**
     * Connects to IPv4 addresses first before IPv6 so dual-stack ISPs with broken IPv6 routes
     * do not stall at 0.0 MB when connecting to objects.githubusercontent.com.
     */
    private class Ipv4PreferringSslSocketFactory(
        private val delegate: SSLSocketFactory
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
            delegate.createSocket(s, host, port, autoClose)

        override fun createSocket(host: String, port: Int): Socket =
            connectIpv4First(host, port)

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            delegate.createSocket(host, port, localHost, localPort)

        override fun createSocket(address: InetAddress, port: Int): Socket =
            delegate.createSocket(address, port)

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            delegate.createSocket(address, port, localAddress, localPort)

        private fun connectIpv4First(host: String, port: Int): Socket {
            val allAddresses = InetAddress.getAllByName(host)
            val ordered = allAddresses.sortedBy { if (it is Inet4Address) 0 else 1 }
            var lastEx: IOException? = null

            for (addr in ordered) {
                val rawSocket = Socket()
                try {
                    rawSocket.connect(InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS)
                    return delegate.createSocket(rawSocket, host, port, true)
                } catch (e: IOException) {
                    lastEx = e
                    runCatching { rawSocket.close() }
                }
            }
            throw lastEx ?: IOException("Unable to connect to $host:$port")
        }
    }
}

