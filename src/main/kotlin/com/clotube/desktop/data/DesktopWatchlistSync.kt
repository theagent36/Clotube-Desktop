package com.clotube.desktop.data

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object DesktopWatchlistSync {
    private const val WATCHLIST_FILENAME = "clotube_watchlist.json"
    private val syncMutex = Mutex()
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + NonCancellable)
    private val httpTransport by lazy { GoogleNetHttpTransport.newTrustedTransport() }
    private val jsonFactory by lazy { GsonFactory.getDefaultInstance() }

    private fun buildDriveService(accessToken: String): Drive {
        val credentials = GoogleCredentials.create(AccessToken(accessToken, null))
        return Drive.Builder(httpTransport, jsonFactory, HttpCredentialsAdapter(credentials))
            .setApplicationName("Clotube Desktop")
            .build()
    }

    /**
     * Immediately launches a non-cancellable cloud push to `clotube/History/clotube_watchlist.json`
     * so Watchlist mutations survive modal dismissal or app exit.
     */
    fun pushLocalWatchlistNonCancellable(email: String, accessToken: String) {
        if (email.isBlank() || accessToken.isBlank()) return
        syncScope.launch(Dispatchers.IO + NonCancellable) {
            pushLocalWatchlist(email, accessToken)
        }
    }

    suspend fun pullRemoteWatchlist(email: String, accessToken: String): Boolean = withContext(Dispatchers.IO) {
        if (email.isBlank() || accessToken.isBlank()) return@withContext false
        syncMutex.withLock {
            try {
                val drive = buildDriveService(accessToken)
                val hierarchy = DesktopDriveDirectoryManager.resolveHierarchy(drive)
                val historyFolderId = hierarchy.historyFolderId
                if (historyFolderId.isBlank()) return@withLock false

                val query = "name = '$WATCHLIST_FILENAME' and '$historyFolderId' in parents and trashed = false"
                val result = drive.files().list()
                    .setQ(query)
                    .setFields("files(id, name, modifiedTime)")
                    .setPageSize(1)
                    .execute()

                val remoteFile = result.files?.firstOrNull() ?: return@withLock false
                val out = ByteArrayOutputStream()
                drive.files().get(remoteFile.id).executeMediaAndDownloadTo(out)
                val remoteJson = out.toString(Charsets.UTF_8.name())

                DesktopWatchlistManager.mergeRemotePayload(email, remoteJson)
            } catch (e: Exception) {
                System.err.println("[DesktopWatchlistSync] Pull failed: ${e.message}")
                false
            }
        }
    }

    suspend fun pushLocalWatchlist(email: String, accessToken: String) = withContext(Dispatchers.IO + NonCancellable) {
        if (email.isBlank() || accessToken.isBlank()) return@withContext
        syncMutex.withLock {
            try {
                val drive = buildDriveService(accessToken)
                val hierarchy = DesktopDriveDirectoryManager.resolveHierarchy(drive)
                val historyFolderId = hierarchy.historyFolderId
                if (historyFolderId.isBlank()) return@withLock

                val query = "name = '$WATCHLIST_FILENAME' and '$historyFolderId' in parents and trashed = false"
                val result = drive.files().list()
                    .setQ(query)
                    .setFields("files(id, name)")
                    .setPageSize(1)
                    .execute()

                val existingFile = result.files?.firstOrNull()

                // Pre-pull to merge any concurrent cross-device changes before uploading
                if (existingFile != null) {
                    val out = ByteArrayOutputStream()
                    drive.files().get(existingFile.id).executeMediaAndDownloadTo(out)
                    DesktopWatchlistManager.mergeRemotePayload(email, out.toString(Charsets.UTF_8.name()))
                }

                val payloadBytes = DesktopWatchlistManager.getLocalPayloadJson(email).toByteArray(Charsets.UTF_8)
                val content = ByteArrayContent("application/json", payloadBytes)

                if (existingFile != null) {
                    drive.files().update(existingFile.id, null, content).execute()
                } else {
                    val metadata = DriveFile().apply {
                        name = WATCHLIST_FILENAME
                        parents = listOf(historyFolderId)
                        mimeType = "application/json"
                    }
                    drive.files().create(metadata, content).setFields("id").execute()
                }
            } catch (e: Exception) {
                System.err.println("[DesktopWatchlistSync] Push failed: ${e.message}")
            }
        }
    }
}
