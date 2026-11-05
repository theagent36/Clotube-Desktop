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

    private val hasPendingSync = AtomicBoolean(false)
    private var autoRecoveryJob: Job? = null
    val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isInternetAvailable(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("8.8.8.8", 53), 2000)
                true
            }
        } catch (_: Exception) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("www.google.com", 443), 2500)
                    true
                }
            } catch (_: Exception) {
                false
            }
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

    suspend fun pullAndMerge(email: String, accessToken: String = ""): Boolean = withContext(Dispatchers.IO) {
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

    suspend fun syncAndPush(email: String, force: Boolean = false): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        if (email.isBlank()) return@withContext false
        val now = System.currentTimeMillis()
        if (!force && (now - lastUploadTime < 15000L)) return@withContext false

        try {
            val driveService = getDriveService()
            if (driveService == null) {
                hasPendingSync.set(true)
                return@withContext false
            }

            val fileId = findHistoryFileId(driveService)

            if (fileId != null) {
                try {
                    val outputStream = ByteArrayOutputStream()
                    driveService.files().get(fileId)
                        .setSupportsAllDrives(true)
                        .executeMediaAndDownloadTo(outputStream)
                    val remoteJson = outputStream.toString("UTF-8")
                    DesktopWatchHistoryManager.mergeRemotePayload(email, remoteJson)
                } catch (e: Exception) {
                    System.err.println("[DesktopWatchHistorySync] Pre-push remote pull failed: ${e.message}")
                }
            }

            val payload = DesktopWatchHistoryManager.getLocalPayloadJson(email)
            val payloadHash = payload.hashCode().toString()
            if (!force && payloadHash == lastUploadedHash) return@withContext false

            lastUploadTime = now
            val mediaContent = ByteArrayContent.fromString("application/json", payload)

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
            hasPendingSync.set(false)
            println("[DesktopWatchHistorySync] Successfully synced watch history with Google Drive.")
            return@withContext true
        } catch (e: IOException) {
            hasPendingSync.set(true)
            System.err.println("[DesktopWatchHistorySync] Network offline during sync. Queued for auto-recovery: ${e.message}")
        } catch (e: GoogleJsonResponseException) {
            hasPendingSync.set(true)
            System.err.println("[DesktopWatchHistorySync] Google API error (${e.statusCode}): ${e.details?.message ?: e.message}")
        } catch (e: Exception) {
            hasPendingSync.set(true)
            System.err.println("[DesktopWatchHistorySync] Unexpected error during sync: ${e.message}")
        }
        false
    }

    suspend fun pushLocalHistory(email: String, accessToken: String = "", force: Boolean = false): Boolean {
        return syncAndPush(email, force)
    }

    fun startAutoRecoveryWorker(userEmail: String, accessTokenProvider: () -> String) {
        if (autoRecoveryJob?.isActive == true) return

        autoRecoveryJob = syncScope.launch {
            var loopCount = 0
            while (isActive) {
                delay(12000L)
                loopCount++

                val token = accessTokenProvider()
                if (token.isBlank() || userEmail.isBlank() || !isInternetAvailable()) continue

                if (hasPendingSync.get()) {
                    val pushed = syncAndPush(userEmail, force = true)
                    if (pushed) hasPendingSync.set(false)
                }

                if (loopCount % 2 == 0) {
                    pullAndMerge(userEmail, token)
                }
            }
        }
    }

    fun stopAutoRecoveryWorker() {
        autoRecoveryJob?.cancel()
        autoRecoveryJob = null
    }
}
