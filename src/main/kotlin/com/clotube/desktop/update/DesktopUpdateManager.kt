package com.clotube.desktop.update

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProxySelector
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.exitProcess

object DesktopUpdateManager {
    private const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/theagent36/Clotube-Desktop/releases/latest"

    // Fallback version used only when running via `.\gradlew.bat run` (where jpackage.app-version is null)
    private const val DEV_FALLBACK_VERSION = "2.1.1"

    private const val MAX_DOWNLOAD_ATTEMPTS = 5
    private const val CONNECT_TIMEOUT_SEC = 20L
    private const val STALL_TIMEOUT_MS = 30_000L

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
        val targetVersion = if (version.isBlank() || version == "2.0.9" || version == "2.1.1") "2.1.1" else version
        val title = if (targetVersion == "2.1.1") {
            "Playback Stability, Offline Resilience & UI Polish"
        } else {
            "What's New in Clotube v$targetVersion"
        }

        val body = """
            ### Fullscreen Playback & Episode-Switching Fixes
            - **Fullscreen Next-Episode Transition Fix:** Eliminated the native LibVLC teardown/initialization race and keyboard focus loss when pressing `N` or clicking **Next Episode** / **Play Next** while in fullscreen—ensuring the outgoing player releases sequentially before starting the next episode and automatically re-acquiring keyboard focus.
            - **Dynamic OAuth Token Refresh & Stream Watchdog:** `LocalStreamServer` now resolves fresh Google Drive OAuth credentials dynamically on every `/stream` connection and range-resume retry (preventing HTTP 401 stalls during extended fullscreen binge sessions), paired with a 3.5-second stream-start watchdog (`restartStream`) that automatically recovers transient proxy handoffs.
            - **Immediate Controls, Scrubber & Smooth 320ms Fade-In:** Player HUD controls and the timeline scrubber now render immediately once watch-history sync completes—displaying the exact resume timestamp and duration even while `"Loading video..."` finishes buffering—and smoothly dissolve the pitch-black loading overlay over 320ms into the first decoded frame with zero duplicate spinners.
            - **Synchronous Unwatched Fast-Path & Progress Guard:** Unwatched episodes bypass redundant Drive history sync waits to display `"Loading video..."` on Frame 0, while `boundMediaId` strictly isolates progress tracking so rapid episode switching never falsely marks the incoming episode as watched.
            - **60+ FPS Draw-Scope Rendering & Fullscreen Toggle Stability:** Bound video frame ticks directly inside Compose's `Canvas` draw scope and switched to immutable per-frame bitmaps to prevent native Skiko memory access violations (`0xc0000005`) when entering or exiting fullscreen.

            ### Offline Resilience & Automatic Reconnection
            - **Zero-Crash Offline Handling:** Guarded all Google Drive directory lookups, library scans, and background sync coroutines against `SocketTimeoutException`, `UnknownHostException`, and `IOException`, eliminating uncaught coroutine crashes and native Windows Swing `Error` popups when internet connectivity drops.
            - **10s × 5-Attempt Auto-Reconnect & Animated Offline Modal:** Added `DesktopNetworkMonitor` to probe internet reachability and automatically retry every **10 seconds (up to 5 attempts)** upon disconnection—presenting a spring-animated **Offline Connection** modal if still offline, with one-click **Retry**, **Continue Offline**, and automatic silent library and watch-history resync as soon as connectivity returns.

            ### UI & Navigation Polish
            - **Live Online / Offline Top-Bar Status Badge:** Wired the top-right status capsule to `DesktopNetworkMonitor` so it dynamically transitions between green **Online** and crimson **Offline** indicators in real time.
            - **Cleaner Sidebar & Unified "Series" Naming:** Removed the redundant 3-bar menu icon next to the **Clotube** logo in the sidebar and standardized all remaining `"TV Series"` labels, Watchlist filters, and breadcrumbs to **"Series"**.
        """.trimIndent()

        return CachedReleaseNoteEntry(
            version = targetVersion,
            tagName = if (targetVersion == "2.1.1") "v2.1.1" else "v$targetVersion",
            title = title,
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

                if (release.msiSizeBytes > 0L && tempFile.length() != release.msiSizeBytes) {
                    throw IOException("Incomplete download (${tempFile.length()} / ${release.msiSizeBytes} bytes)")
                }

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

    private suspend fun downloadWithAutoRetry(
        initialUrl: String,
        destination: File,
        expectedTotalBytes: Long,
        onStatus: (statusText: String, downloaded: Long, total: Long) -> Unit,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val backoffDelaysMs = longArrayOf(1200L, 2000L, 3000L, 4500L)
        var lastError: Exception? = null

        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            val existingBytes = if (destination.exists()) destination.length() else 0L
            val useNativeCurl = attempt >= 3 && isWindowsCurlAvailable()

            if (attempt == 1) {
                onStatus("Connecting to GitHub release server…", existingBytes, expectedTotalBytes)
            } else if (useNativeCurl) {
                onStatus(
                    "Switching to Windows native downloader (attempt $attempt of $MAX_DOWNLOAD_ATTEMPTS)…",
                    existingBytes,
                    expectedTotalBytes
                )
            } else {
                onStatus(
                    "Connection slow — retrying automatically (attempt $attempt of $MAX_DOWNLOAD_ATTEMPTS)…",
                    existingBytes,
                    expectedTotalBytes
                )
            }

            try {
                if (useNativeCurl) {
                    downloadWithWindowsCurl(
                        initialUrl = initialUrl,
                        destination = destination,
                        expectedTotalBytes = expectedTotalBytes,
                        onProgress = onProgress
                    )
                } else {
                    downloadWithJdkHttpClient(
                        initialUrl = initialUrl,
                        destination = destination,
                        expectedTotalBytes = expectedTotalBytes,
                        onProgress = onProgress
                    )
                }

                if (!destination.exists() || destination.length() <= 0L) {
                    throw IOException("Downloaded file is empty")
                }
                if (expectedTotalBytes > 0L && destination.length() != expectedTotalBytes) {
                    throw IOException(
                        "Incomplete download (${destination.length()} / $expectedTotalBytes bytes)"
                    )
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val reason = (e.message ?: e.javaClass.simpleName).take(70)
                System.err.println("[DesktopUpdateManager] Attempt $attempt/$MAX_DOWNLOAD_ATTEMPTS failed: $reason")
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    val waitMs = backoffDelaysMs[(attempt - 1).coerceAtMost(backoffDelaysMs.lastIndex)]
                    val currentBytes = if (destination.exists()) destination.length() else 0L
                    onStatus(
                        "$reason — retrying in ${waitMs / 1000}s (attempt ${attempt + 1} of $MAX_DOWNLOAD_ATTEMPTS)…",
                        currentBytes,
                        expectedTotalBytes
                    )
                    delay(waitMs)
                }
            }
        }

        throw lastError ?: IOException("Unable to download update from GitHub")
    }

    private suspend fun downloadWithJdkHttpClient(
        initialUrl: String,
        destination: File,
        expectedTotalBytes: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        var resumeOffset = if (destination.exists()) destination.length() else 0L
        if (expectedTotalBytes > 0L && resumeOffset >= expectedTotalBytes) {
            if (resumeOffset == expectedTotalBytes) return
            destination.delete()
            resumeOffset = 0L
        }

        val client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
            .proxy(ProxySelector.getDefault())
            .build()

        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(initialUrl))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Clotube-Desktop-Updater/2.1")
            .header("Accept", "application/octet-stream")
            .GET()

        if (resumeOffset > 0L) {
            requestBuilder.header("Range", "bytes=$resumeOffset-")
        }

        val response = withContext(Dispatchers.IO) {
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
        }

        val code = response.statusCode()
        if (code == 416) {
            response.body().close()
            if (destination.exists()) destination.delete()
            throw IOException("Range reset required (HTTP 416)")
        }

        if (code != 200 && code != 206) {
            response.body().close()
            throw IOException("HTTP $code from GitHub CDN")
        }

        val isPartialAppend = (code == 206 && resumeOffset > 0L)
        if (!isPartialAppend && resumeOffset > 0L) {
            if (destination.exists()) destination.delete()
            resumeOffset = 0L
        }

        val responseLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        val totalBytes = when {
            isPartialAppend && responseLength > 0L -> resumeOffset + responseLength
            responseLength > 0L -> responseLength
            else -> expectedTotalBytes
        }

        var downloaded = resumeOffset
        var lastEmitTime = 0L
        onProgress(downloaded, totalBytes)

        withContext(Dispatchers.IO) {
            response.body().use { input: InputStream ->
                FileOutputStream(destination, isPartialAppend).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val bytesRead = withTimeoutOrNull(STALL_TIMEOUT_MS) {
                            input.read(buffer)
                        } ?: throw IOException("Stream stalled for ${STALL_TIMEOUT_MS / 1000}s")

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
        }
    }

    private fun resolveWindowsCurlFile(): File {
        val winDir = System.getenv("SystemRoot") ?: "C:\\Windows"
        return File(winDir, "System32\\curl.exe")
    }

    private fun isWindowsCurlAvailable(): Boolean {
        return resolveWindowsCurlFile().exists()
    }

    private suspend fun downloadWithWindowsCurl(
        initialUrl: String,
        destination: File,
        expectedTotalBytes: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        if (expectedTotalBytes > 0L && destination.exists() && destination.length() >= expectedTotalBytes) {
            if (destination.length() == expectedTotalBytes) return
            destination.delete()
        }

        val curlExe = resolveWindowsCurlFile().absolutePath
        val cmd = mutableListOf(
            curlExe,
            "-L",
            "--fail",
            "--silent",
            "--show-error",
            "--connect-timeout", "20",
            "--speed-limit", "8192",
            "--speed-time", "20",
            "-A", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Clotube-Desktop-Updater/2.1"
        )
        if (destination.exists() && destination.length() > 0L) {
            cmd.addAll(listOf("-C", "-"))
        }
        cmd.addAll(listOf("-o", destination.absolutePath, initialUrl))

        val stderrBuffer = StringBuilder()
        val process = withContext(Dispatchers.IO) {
            ProcessBuilder(cmd)
                .redirectErrorStream(false)
                .start()
        }

        val stderrJob = scope.launch(Dispatchers.IO) {
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val text = reader.readText().trim()
                    if (text.isNotEmpty()) stderrBuffer.append(text)
                }
            }
        }

        try {
            while (process.isAlive) {
                currentCoroutineContext().ensureActive()
                val currentBytes = if (destination.exists()) destination.length() else 0L
                onProgress(currentBytes, expectedTotalBytes)
                delay(150L)
            }
            stderrJob.join()
            val exitCode = process.exitValue()
            val finalBytes = if (destination.exists()) destination.length() else 0L
            onProgress(finalBytes, expectedTotalBytes)

            if (exitCode != 0) {
                val errDetail = stderrBuffer.toString().lineSequence().lastOrNull()?.trim()
                    ?: "curl exit code $exitCode"
                throw IOException(errDetail)
            }
        } catch (e: CancellationException) {
            runCatching { process.destroyForcibly() }
            throw e
        }
    }
}

