package com.clotube.desktop.data

import com.clotube.desktop.auth.DesktopAuthManager
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object DesktopWatchHistorySync {
    private const val ACTIVE_SYNC_FILE = "clotube_active_sync.json"
    private const val ARCHIVE_SYNC_FILE = "clotube_archive.json"
    private const val LEGACY_SYNC_FILE = "clotube_watch_history.json"

    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val httpTransport = GoogleNetHttpTransport.newTrustedTransport()

    private var lastUploadedHash = ""
    private var lastUploadTime = 0L

    private val hasPendingSync = AtomicBoolean(false)
    private var autoRecoveryJob: Job? = null
    private val syncMutex = Mutex()
    private val cachedHistoryFolderIds = ConcurrentHashMap<String, String>()
    val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private data class SyncTarget(
        val fileId: String?,
        val parentFolderId: String,
        val isV2: Boolean,
        val isArchive: Boolean = false
    )

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

    private fun getDriveService(email: String = "", accessToken: String = ""): Drive? {
        val credential = DesktopAuthManager.getCredential(email.ifBlank { null })
        return when {
            credential != null -> Drive.Builder(httpTransport, jsonFactory, credential)
                .setApplicationName("Clotube-Windows")
                .build()
            accessToken.isNotBlank() -> Drive.Builder(httpTransport, jsonFactory) { request ->
                request.headers.authorization = "Bearer $accessToken"
            }.setApplicationName("Clotube-Windows").build()
            else -> null
        }
    }

    private suspend fun getOrResolveHistoryFolderId(
        driveService: Drive,
        email: String,
        forceRefresh: Boolean = false
    ): String {
        val emailKey = email.trim().lowercase()
        if (!forceRefresh && emailKey.isNotBlank()) {
            cachedHistoryFolderIds[emailKey]?.let { return it }
        }

        val dirs = DesktopDriveDirectoryManager.resolveHierarchy(driveService)
        if (emailKey.isNotBlank()) {
            cachedHistoryFolderIds[emailKey] = dirs.historyFolderId
        }
        return dirs.historyFolderId
    }

    private fun queryFirstFileId(driveService: Drive, query: String): String? {
        val response = driveService.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name, mimeType, shortcutDetails)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(1)
            .execute()
        return response.files?.firstOrNull()?.let { DesktopDriveDirectoryManager.resolveTargetId(it) }
    }

    private suspend fun resolveSyncTarget(driveService: Drive, email: String): SyncTarget {
        var historyFolderId = getOrResolveHistoryFolderId(driveService, email)

        // 1. Check for clotube_active_sync.json in clotube/History/
        val v2Id = try {
            queryFirstFileId(
                driveService,
                "'$historyFolderId' in parents and name = '$ACTIVE_SYNC_FILE' and trashed = false"
            )
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 404) {
                historyFolderId = getOrResolveHistoryFolderId(driveService, email, forceRefresh = true)
                queryFirstFileId(
                    driveService,
                    "'$historyFolderId' in parents and name = '$ACTIVE_SYNC_FILE' and trashed = false"
                )
            } else {
                throw e
            }
        }
        if (v2Id != null) return SyncTarget(v2Id, historyFolderId, isV2 = true)

        // 2. Check for clotube_archive.json in clotube/History/ as archive fallback
        val archiveId = queryFirstFileId(
            driveService,
            "'$historyFolderId' in parents and name = '$ARCHIVE_SYNC_FILE' and trashed = false"
        )
        if (archiveId != null) return SyncTarget(archiveId, historyFolderId, isV2 = true, isArchive = true)

        // 3. Check for legacy file in clotube/History/
        val legacyScopedId = queryFirstFileId(
            driveService,
            "'$historyFolderId' in parents and name = '$LEGACY_SYNC_FILE' and trashed = false"
        )
        if (legacyScopedId != null) return SyncTarget(legacyScopedId, historyFolderId, isV2 = false)

        // 4. Fallback: Check My Drive root then anywhere in Drive for legacy file
        val rootLegacyId = queryFirstFileId(
            driveService,
            "'root' in parents and name = '$LEGACY_SYNC_FILE' and trashed = false"
        ) ?: queryFirstFileId(
            driveService,
            "name = '$LEGACY_SYNC_FILE' and trashed = false"
        )
        return SyncTarget(rootLegacyId, historyFolderId, isV2 = false)
    }

    private fun downloadJsonFile(driveService: Drive, fileId: String): String {
        val outputStream = ByteArrayOutputStream()
        driveService.files().get(fileId)
            .setSupportsAllDrives(true)
            .executeMediaAndDownloadTo(outputStream)
        return outputStream.toString("UTF-8")
    }

    private fun upsertDriveJsonFile(
        driveService: Drive,
        fileName: String,
        parentFolderId: String,
        existingFileId: String?,
        jsonPayload: String
    ) {
        val mediaContent = ByteArrayContent.fromString("application/json", jsonPayload)
        if (existingFileId != null) {
            driveService.files().update(existingFileId, null, mediaContent)
                .setSupportsAllDrives(true)
                .execute()
        } else {
            val metadata = com.google.api.services.drive.model.File().apply {
                name = fileName
                mimeType = "application/json"
                parents = listOf(parentFolderId)
            }
            driveService.files().create(metadata, mediaContent)
                .setFields("id, name, parents")
                .setSupportsAllDrives(true)
                .execute()
        }
    }

    suspend fun pullAndMerge(email: String, accessToken: String = ""): Boolean = withContext(Dispatchers.IO) {
        if (email.isBlank()) return@withContext false
        try {
            val driveService = getDriveService(email, accessToken) ?: return@withContext false
            val target = resolveSyncTarget(driveService, email)
            val fileId = target.fileId ?: return@withContext false

            val remoteJson = downloadJsonFile(driveService, fileId)
            val merged = DesktopWatchHistoryManager.mergeRemotePayload(email, remoteJson)

            // If pulled from legacy v1 or archive fallback, migrate immediately to clotube_active_sync.json
            if (!target.isV2 || target.isArchive) {
                println("[DesktopWatchHistorySync] Migrating remote history to v2 Active Sync...")
                pushLocalHistory(email, accessToken, force = true)
            } else {
                lastUploadedHash = remoteJson.hashCode().toString()
            }
            // Always trigger UI invalidation after a pull, since mergeRemotePayload may have
            // removed watched IDs from the local cache (deletions from Android).
            DesktopWatchHistoryManager.notifyMutation()
            return@withContext merged
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 404) {
                cachedHistoryFolderIds.remove(email.trim().lowercase())
            }
            System.err.println("[DesktopWatchHistorySync] Pull failed (${e.statusCode}): ${e.details?.message ?: e.message}")
        } catch (e: Exception) {
            System.err.println("[DesktopWatchHistorySync] Pull failed: ${e.message}")
        }
        false
    }

    suspend fun pushLocalHistory(
        email: String,
        accessToken: String = "",
        force: Boolean = false
    ): Boolean = pushLocalHistoryInternal(email, accessToken, force)

    suspend fun pushLocalHistory(email: String, force: Boolean): Boolean =
        pushLocalHistoryInternal(email, accessToken = "", force = force)

    private suspend fun pushLocalHistoryInternal(
        email: String,
        accessToken: String,
        force: Boolean
    ): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        if (email.isBlank()) return@withContext false

        syncMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && (now - lastUploadTime < 15000L)) return@withLock false

            val payload = DesktopWatchHistoryManager.getLocalPayloadJson(email)
            val payloadHash = payload.hashCode().toString()
            if (!force && payloadHash == lastUploadedHash) return@withLock false

            try {
                val driveService = getDriveService(email, accessToken)
                if (driveService == null) {
                    hasPendingSync.set(true)
                    return@withLock false
                }

                val target = resolveSyncTarget(driveService, email)
                val activeFileId = if (target.isV2 && !target.isArchive) target.fileId else null

                lastUploadTime = now
                upsertDriveJsonFile(
                    driveService = driveService,
                    fileName = ACTIVE_SYNC_FILE,
                    parentFolderId = target.parentFolderId,
                    existingFileId = activeFileId,
                    jsonPayload = payload
                )

                // Best-effort flush of entries pruned beyond the 50-item active window into clotube_archive.json
                val archiveJson = DesktopWatchHistoryManager.consumeOverflowArchiveJson(email)
                if (archiveJson != null) {
                    try {
                        val existingArchiveId = queryFirstFileId(
                            driveService,
                            "'${target.parentFolderId}' in parents and name = '$ARCHIVE_SYNC_FILE' and trashed = false"
                        )
                        upsertDriveJsonFile(
                            driveService = driveService,
                            fileName = ARCHIVE_SYNC_FILE,
                            parentFolderId = target.parentFolderId,
                            existingFileId = existingArchiveId,
                            jsonPayload = archiveJson
                        )
                    } catch (e: Exception) {
                        System.err.println("[DesktopWatchHistorySync] Archive write skipped: ${e.message}")
                    }
                }

                lastUploadedHash = payloadHash
                hasPendingSync.set(false)
                println("[DesktopWatchHistorySync] Successfully pushed v2 active sync (<30 KB) to Google Drive.")
                return@withLock true
            } catch (e: IOException) {
                hasPendingSync.set(true)
                System.err.println("[DesktopWatchHistorySync] Offline during push. Queued: ${e.message}")
            } catch (e: GoogleJsonResponseException) {
                if (e.statusCode == 404) {
                    cachedHistoryFolderIds.remove(email.trim().lowercase())
                }
                hasPendingSync.set(true)
                System.err.println("[DesktopWatchHistorySync] Error during push (${e.statusCode}): ${e.details?.message ?: e.message}")
            } catch (e: Exception) {
                hasPendingSync.set(true)
                System.err.println("[DesktopWatchHistorySync] Error during push: ${e.message}")
            }
            false
        }
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
                    val pushed = pushLocalHistory(userEmail, token, force = true)
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
