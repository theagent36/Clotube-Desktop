package com.clotube.desktop.data

import com.clotube.desktop.auth.DesktopAuthManager
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

object DesktopWatchHistorySync {
    private const val FILE_NAME = "clotube_watch_history.json"
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val httpTransport = GoogleNetHttpTransport.newTrustedTransport()

    private var lastUploadedHash = ""
    private var lastUploadTime = 0L

    // Safety net state
    val hasPendingSync = AtomicBoolean(false)
    var lastSuccessfulSyncTime = 0L
    private var autoRecoveryJob: Job? = null
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Rapid socket ping to Google APIs to verify actual internet connectivity.
     */
    fun isInternetAvailable(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("www.googleapis.com", 443), 2000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getDriveService(): Drive? {
        val credential = DesktopAuthManager.getCredential() ?: return null
        return Drive.Builder(httpTransport, jsonFactory, credential)
            .setApplicationName("Clotube-Windows")
            .build()
    }

    private fun findHistoryFileId(driveService: Drive): String? {
        return try {
            val query = "name = '$FILE_NAME' and trashed = false"
            val response = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setPageSize(5)
                .execute()
            response.files?.firstOrNull()?.id
        } catch (e: Exception) {
            System.err.println("[DesktopWatchHistorySync] Error searching for $FILE_NAME: ${e.message}")
            null
        }
    }

    /**
     * Downloads clotube_watch_history.json from Drive and merges it into the local profile.
     */
    suspend fun pullAndMerge(email: String, accessToken: String): Boolean = withContext(Dispatchers.IO) {
        if (email.isBlank()) return@withContext false
        try {
            val driveService = getDriveService() ?: return@withContext false
            val fileId = findHistoryFileId(driveService) ?: return@withContext false

            val outputStream = ByteArrayOutputStream()
            driveService.files().get(fileId)
                .setSupportsAllDrives(true)
                .executeMediaAndDownloadTo(outputStream)

            val remoteJson = outputStream.toString("UTF-8")
            val merged = DesktopWatchHistoryManager.mergeRemotePayload(email, remoteJson)
            lastUploadedHash = remoteJson.hashCode().toString()
            return@withContext merged
        } catch (e: GoogleJsonResponseException) {
            System.err.println("[DesktopWatchHistorySync] Pull failed (${e.statusCode}): ${e.details?.message ?: e.message}")
        } catch (e: Exception) {
            System.err.println("[DesktopWatchHistorySync] Failed to pull history from Drive: ${e.message}")
        }
        false
    }

    /**
     * Pushes the local profile watch history to Google Drive.
     * Enforces debouncing, catch network drops, and triggers the pending sync latch on failure.
     */
    suspend fun pushLocalHistory(email: String, accessToken: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (email.isBlank()) return@withContext false
        val now = System.currentTimeMillis()
        if (!force && (now - lastUploadTime < 25000L)) return@withContext false

        val payload = DesktopWatchHistoryManager.getLocalPayloadJson(email)
        val payloadHash = payload.hashCode().toString()
        if (!force && payloadHash == lastUploadedHash) return@withContext false

        lastUploadTime = now

        try {
            val driveService = getDriveService()
            if (driveService == null) {
                hasPendingSync.set(true)
                return@withContext false
            }

            val mediaContent = ByteArrayContent.fromString("application/json", payload)
            val fileId = findHistoryFileId(driveService)

            if (fileId != null) {
                driveService.files().update(fileId, null, mediaContent)
                    .setSupportsAllDrives(true)
                    .execute()
            } else {
                val metadata = com.google.api.services.drive.model.File().apply {
                    name = FILE_NAME
                    mimeType = "application/json"
                }
                driveService.files().create(metadata, mediaContent)
                    .setSupportsAllDrives(true)
                    .execute()
            }

            lastUploadedHash = payloadHash
            lastSuccessfulSyncTime = now
            hasPendingSync.set(false)
            println("[DesktopWatchHistorySync] Successfully pushed watch history to Google Drive.")
            return@withContext true
        } catch (e: IOException) {
            // Network dropped mid-upload or DNS unresolvable
            hasPendingSync.set(true)
            System.err.println("[DesktopWatchHistorySync] Network offline during upload. Queued for auto-recovery when connection restores: ${e.message}")
        } catch (e: GoogleJsonResponseException) {
            hasPendingSync.set(true)
            System.err.println("[DesktopWatchHistorySync] Google API error (${e.statusCode}): ${e.details?.message ?: e.message}")
        } catch (e: Exception) {
            hasPendingSync.set(true)
            System.err.println("[DesktopWatchHistorySync] Unexpected error during push: ${e.message}")
        }
        false
    }

    /**
     * Background daemon that detects when internet is restored and flushes pending history uploads.
     */
    fun startAutoRecoveryWorker(userEmail: String, accessTokenProvider: () -> String) {
        if (autoRecoveryJob?.isActive == true) return

        autoRecoveryJob = syncScope.launch {
            println("[DesktopWatchHistorySync] Auto-recovery network monitor started.")
            while (isActive) {
                delay(15000L) // Check every 15 seconds

                if (hasPendingSync.get()) {
                    if (isInternetAvailable()) {
                        val token = accessTokenProvider()
                        if (token.isNotBlank() && userEmail.isNotBlank()) {
                            println("[DesktopWatchHistorySync] Internet restored! Flushing queued watch history to Google Drive...")
                            val success = pushLocalHistory(userEmail, token, force = true)
                            if (success) {
                                println("[DesktopWatchHistorySync] Pending watch history flushed successfully.")
                            }
                        }
                    }
                }
            }
        }
    }

    fun stopAutoRecoveryWorker() {
        autoRecoveryJob?.cancel()
        autoRecoveryJob = null
    }
}
