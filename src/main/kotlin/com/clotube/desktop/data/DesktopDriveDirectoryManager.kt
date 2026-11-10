package com.clotube.desktop.data

import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ResolvedDriveDirectories(
    val clotubeRootId: String,
    val moviesFolderId: String?,
    val seriesFolderId: String?,
    val historyFolderId: String
)

object DesktopDriveDirectoryManager {

    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"

    private val MOVIE_LEGACY_NAMES = setOf("movies", "movie", "films", "cinema")
    private val SERIES_LEGACY_NAMES = setOf("series", "tv series", "tv shows", "shows")

    // Serializes hierarchy resolution so concurrent movie/series/history tasks never create duplicate root folders
    private val resolveMutex = Mutex()

    suspend fun resolveHierarchy(driveService: Drive): ResolvedDriveDirectories = withContext(Dispatchers.IO) {
        resolveMutex.withLock {
            val clotubeRootId = getOrCreateFolder(driveService, "clotube", parentId = null)
            val historyFolderId = getOrCreateFolder(driveService, "History", parentId = clotubeRootId)

            val scopedMoviesId = findChildFolder(driveService, "Movies", clotubeRootId)
            val moviesFolderId = resolveMediaFolder(
                driveService = driveService,
                clotubeRootId = clotubeRootId,
                scopedFolderId = scopedMoviesId,
                defaultFolderName = "Movies",
                legacyNames = MOVIE_LEGACY_NAMES
            )

            val scopedSeriesId = findChildFolder(driveService, "Series", clotubeRootId)
            val seriesFolderId = resolveMediaFolder(
                driveService = driveService,
                clotubeRootId = clotubeRootId,
                scopedFolderId = scopedSeriesId,
                defaultFolderName = "Series",
                legacyNames = SERIES_LEGACY_NAMES
            )

            ResolvedDriveDirectories(
                clotubeRootId = clotubeRootId,
                moviesFolderId = moviesFolderId,
                seriesFolderId = seriesFolderId,
                historyFolderId = historyFolderId
            )
        }
    }

    private fun resolveMediaFolder(
        driveService: Drive,
        clotubeRootId: String,
        scopedFolderId: String?,
        defaultFolderName: String,
        legacyNames: Set<String>
    ): String {
        if (scopedFolderId != null && hasFiles(driveService, scopedFolderId)) {
            return scopedFolderId
        }

        val excludedIds = if (scopedFolderId != null) setOf(scopedFolderId) else emptySet()
        return resolveLegacyFolder(driveService, legacyNames, excludedIds)
            ?: scopedFolderId
            ?: getOrCreateFolder(driveService, defaultFolderName, parentId = clotubeRootId)
    }

    fun getOrCreateFolder(driveService: Drive, folderName: String, parentId: String?): String {
        val existingId = if (parentId != null) {
            findChildFolder(driveService, folderName, parentId)
        } else {
            findRootFolder(driveService, folderName)
        }

        if (existingId != null) return existingId

        val folderMetadata = File().apply {
            name = folderName
            mimeType = FOLDER_MIME
            if (parentId != null) {
                parents = listOf(parentId)
            }
        }

        val created = driveService.files().create(folderMetadata)
            .setFields("id, name")
            .setSupportsAllDrives(true)
            .execute()

        println("[DesktopDriveDirectoryManager] Created directory '$folderName' (ID: ${created.id})")
        return created.id
    }

    private fun findRootFolder(driveService: Drive, name: String): String? {
        val escapedName = escapeDriveQueryLiteral(name)

        // Prefer My Drive root first so nested unrelated folders of the same name are not hijacked
        val rootQuery = "'root' in parents and name = '$escapedName' and (mimeType = '$FOLDER_MIME' or mimeType = '$SHORTCUT_MIME') and trashed = false"
        val rootResponse = driveService.files().list()
            .setQ(rootQuery)
            .setSpaces("drive")
            .setFields("files(id, name, mimeType, shortcutDetails)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(10)
            .execute()

        val rootMatch = rootResponse.files?.firstOrNull { isFolderOrFolderShortcut(it) }
        if (rootMatch != null) return resolveTargetId(rootMatch)

        // Fallback for top-level Shared Drive folders (whose parent is the shared drive ID rather than 'root')
        val sharedQuery = "name = '$escapedName' and (mimeType = '$FOLDER_MIME' or mimeType = '$SHORTCUT_MIME') and trashed = false"
        val sharedResponse = driveService.files().list()
            .setQ(sharedQuery)
            .setSpaces("drive")
            .setFields("files(id, name, mimeType, shortcutDetails, parents, driveId)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(10)
            .execute()

        val sharedTopMatch = sharedResponse.files?.firstOrNull { file ->
            isFolderOrFolderShortcut(file) && file.driveId != null && file.parents?.contains(file.driveId) == true
        }
        return sharedTopMatch?.let { resolveTargetId(it) }
    }

    private fun findChildFolder(driveService: Drive, name: String, parentId: String): String? {
        val escapedName = escapeDriveQueryLiteral(name)
        val query = "'$parentId' in parents and name = '$escapedName' and (mimeType = '$FOLDER_MIME' or mimeType = '$SHORTCUT_MIME') and trashed = false"
        val response = driveService.files().list()
            .setQ(query)
            .setFields("files(id, name, mimeType, shortcutDetails)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(10)
            .execute()

        val file = response.files?.firstOrNull { isFolderOrFolderShortcut(it) } ?: return null
        return resolveTargetId(file)
    }

    private fun resolveLegacyFolder(
        driveService: Drive,
        targetNames: Set<String>,
        excludedIds: Set<String> = emptySet()
    ): String? {
        val query = "(mimeType = '$FOLDER_MIME' or mimeType = '$SHORTCUT_MIME') and trashed = false"
        val candidates = mutableListOf<File>()

        try {
            var pageToken: String? = null
            do {
                val response = driveService.files().list()
                    .setQ(query)
                    .setFields("nextPageToken, files(id, name, mimeType, shortcutDetails)")
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setPageSize(200)
                    .setPageToken(pageToken)
                    .execute()

                response.files?.forEach { file ->
                    if (!isFolderOrFolderShortcut(file)) return@forEach
                    val cleanName = file.name?.trim()?.lowercase() ?: ""
                    val targetId = resolveTargetId(file)
                    if (targetNames.contains(cleanName) && !excludedIds.contains(targetId) && !excludedIds.contains(file.id)) {
                        candidates.add(file)
                    }
                }
                pageToken = response.nextPageToken
            } while (pageToken != null && candidates.size < 10)
        } catch (_: Exception) {
            return null
        }

        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return resolveTargetId(candidates.first())

        var bestId: String? = null
        var maxCount = -1

        for (candidate in candidates) {
            val candidateId = resolveTargetId(candidate)
            val count = countChildren(driveService, candidateId, pageSize = 100)
            if (count > maxCount) {
                maxCount = count
                bestId = candidateId
            }
        }

        return bestId ?: resolveTargetId(candidates.first())
    }

    private fun hasFiles(driveService: Drive, folderId: String): Boolean =
        countChildren(driveService, folderId, pageSize = 1) > 0

    private fun countChildren(driveService: Drive, folderId: String, pageSize: Int = 5): Int {
        return try {
            val response = driveService.files().list()
                .setQ("'$folderId' in parents and trashed = false")
                .setFields("files(id)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setPageSize(pageSize)
                .execute()
            response.files?.size ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun isFolderOrFolderShortcut(file: File): Boolean {
        return file.mimeType == FOLDER_MIME ||
            (file.mimeType == SHORTCUT_MIME &&
                (file.shortcutDetails?.targetMimeType == null || file.shortcutDetails?.targetMimeType == FOLDER_MIME))
    }

    private fun escapeDriveQueryLiteral(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    fun resolveTargetId(file: File): String {
        return if (file.mimeType == SHORTCUT_MIME && file.shortcutDetails?.targetId != null) {
            file.shortcutDetails.targetId
        } else {
            file.id
        }
    }
}

