package com.clotube.desktop.update

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

object DesktopUpdateManager {
    private const val GITHUB_LATEST_RELEASE_API =
        "https://api.github.com/repos/theagent36/Clotube-Desktop/releases/latest"

    // Fallback version used only when running via `.\gradlew.bat run` (where jpackage.app-version is null)
    private const val DEV_FALLBACK_VERSION = "2.0.3"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeDownloadJob: Job? = null

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private val _isCheckingManually = MutableStateFlow(false)
    val isCheckingManually: StateFlow<Boolean> = _isCheckingManually.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

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
     * 1. Cleans up obsolete `.msi` installers if the app is already running the upgraded version.
     * 2. Surfaces an already-downloaded pending `.msi` if one is waiting on disk.
     * 3. Queries GitHub Releases while honoring Skip Version and 24-hour Remind Me Later rules.
     */
    fun checkForUpdatesOnStartup() {
        scope.launch {
            val currentVersion = getCurrentAppVersion()
            val saved = DesktopUpdateStore.load()

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
                            pendingReleaseNotes = null
                        )
                    )
                } else if (pendingFile.exists() && pendingFile.length() > 0L) {
                    // Valid downloaded MSI is already waiting locally
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

            // If exact file already exists on disk with matching byte size, skip re-downloading
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

            _uiState.value = UpdateUiState.Downloading(
                currentVersion = currentVersion,
                release = release,
                installImmediatelyAfter = installImmediately,
                bytesDownloaded = 0L,
                totalBytes = release.msiSizeBytes,
                progressFraction = 0f
            )

            try {
                if (tempFile.exists()) tempFile.delete()
                downloadFileWithRedirects(
                    initialUrl = release.msiDownloadUrl,
                    destination = tempFile,
                    expectedTotalBytes = release.msiSizeBytes
                ) { downloaded, total ->
                    val fraction = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                    _uiState.value = UpdateUiState.Downloading(
                        currentVersion = currentVersion,
                        release = release,
                        installImmediatelyAfter = installImmediately,
                        bytesDownloaded = downloaded,
                        totalBytes = total,
                        progressFraction = fraction
                    )
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
                if (tempFile.exists()) tempFile.delete()
                _uiState.value = UpdateUiState.Hidden
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                System.err.println("[DesktopUpdateManager] Download failed: ${e.message}")
                _uiState.value = UpdateUiState.Error(
                    message = "Download failed: ${e.message ?: "Network error"}",
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
     * Schedules the downloaded `.msi` to install silently on the user's next Windows boot/login
     * via HKCU\Software\Microsoft\Windows\CurrentVersion\RunOnce (requires zero UAC admin rights).
     */
    fun scheduleInstallOnNextBoot(installerFile: File, version: String) {
        scope.launch {
            try {
                val bootScript = File(DesktopUpdateStore.updatesDir, "clotube_next_boot_update.cmd")
                val scriptContent = buildString {
                    appendLine("@echo off")
                    appendLine("timeout /t 4 /nobreak >nul")
                    appendLine("msiexec.exe /i \"${installerFile.absolutePath}\" /passive /norestart")
                    appendLine("del \"%~f0\"")
                }
                bootScript.writeText(scriptContent, Charsets.UTF_8)

                val regProcess = ProcessBuilder(
                    "reg.exe",
                    "add",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce",
                    "/v",
                    "ClotubePendingUpdate",
                    "/t",
                    "REG_SZ",
                    "/d",
                    "\"${bootScript.absolutePath}\"",
                    "/f"
                ).redirectErrorStream(true).start()
                regProcess.waitFor()

                val currentState = _uiState.value
                if (currentState is UpdateUiState.ReadyToInstall) {
                    _uiState.value = currentState.copy(scheduledForNextBoot = true)
                } else {
                    _uiState.value = UpdateUiState.Hidden
                }
                println("[DesktopUpdateManager] Scheduled v$version for next Windows boot via HKCU RunOnce.")
            } catch (e: Exception) {
                System.err.println("[DesktopUpdateManager] Failed to register RunOnce update: ${e.message}")
                _uiState.value = UpdateUiState.Error(
                    message = "Could not schedule boot installer: ${e.message}"
                )
            }
        }
    }

    /**
     * Executes an atomic in-place `.msi` upgrade via a detached background script and relaunches Clotube.
     */
    fun installNowAndRelaunch(installerFile: File, version: String) {
        scope.launch {
            try {
                _uiState.value = UpdateUiState.Installing(version = version)

                // Remove any RunOnce entry so it doesn't re-run after we install right now
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

                val localAppData = System.getenv("LOCALAPPDATA")
                    ?: File(System.getProperty("user.home"), "AppData\\Local").absolutePath
                val installedExePath = File(localAppData, "Clotube\\Clotube.exe").absolutePath

                val installScript = File(DesktopUpdateStore.updatesDir, "clotube_install_now.cmd")
                val scriptContent = buildString {
                    appendLine("@echo off")
                    appendLine("timeout /t 2 /nobreak >nul")
                    appendLine("taskkill /F /IM Clotube.exe >nul 2>&1")
                    appendLine("timeout /t 1 /nobreak >nul")
                    appendLine("msiexec.exe /i \"${installerFile.absolutePath}\" /passive /norestart")
                    appendLine("if exist \"$installedExePath\" (")
                    appendLine("    start \"\" \"$installedExePath\"")
                    appendLine(")")
                    appendLine("del \"%~f0\"")
                }
                installScript.writeText(scriptContent, Charsets.UTF_8)

                // Spawn completely detached outside JVM process tree
                ProcessBuilder(
                    "cmd.exe",
                    "/c",
                    "start",
                    "\"Clotube Updater\"",
                    "/min",
                    installScript.absolutePath
                ).start()

                delay(600L)
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
                pendingReleaseNotes = release.releaseNotes
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

            GithubReleaseInfo(
                version = version,
                tagName = tagName,
                releaseName = releaseName,
                releaseNotes = releaseNotes,
                msiDownloadUrl = msiAssetUrl,
                msiFileName = msiFileName,
                msiSizeBytes = msiSize
            )
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

        while (redirects < 6) {
            currentCoroutineContext().ensureActive()
            var connection: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 15000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Clotube-Desktop-Updater")
                    setRequestProperty("Accept", "application/octet-stream")
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

                if (code != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP $code while downloading update")
                }

                val contentLength = connection.contentLengthLong.takeIf { it > 0L } ?: expectedTotalBytes
                var downloaded = 0L
                var lastEmitTime = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= 80L || downloaded == contentLength) {
                                lastEmitTime = now
                                onProgress(downloaded, contentLength)
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
        throw IllegalStateException("Too many redirects while downloading update")
    }
}

