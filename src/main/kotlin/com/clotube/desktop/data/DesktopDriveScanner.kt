package com.clotube.desktop.data

import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.model.DesktopSeason
import com.clotube.desktop.model.DesktopSeries
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

class DesktopDriveScanner(accessToken: String) {

    private val driveService: Drive?

    init {
        driveService = if (accessToken.isNotBlank()) {
            try {
                val credentials = GoogleCredentials.create(AccessToken(accessToken, null))
                val transport = GoogleNetHttpTransport.newTrustedTransport()
                Drive.Builder(
                    transport,
                    GsonFactory.getDefaultInstance(),
                    HttpCredentialsAdapter(credentials)
                ).setApplicationName("Clotube-Windows").build()
            } catch (e: Exception) {
                System.err.println("[DesktopDriveScanner] Failed to initialize Google Drive service: ${e.message}")
                e.printStackTrace()
                null
            }
        } else {
            System.err.println("[DesktopDriveScanner] Access token is blank. Drive service cannot be initialized.")
            null
        }
    }

    suspend fun loadMovies(): List<DesktopMediaItem> = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext emptyList()
        println("[DesktopDriveScanner] Resolving Movies folder...")
        val rootId = resolveBestFolder(setOf("movies", "movie", "films", "cinema"))
        if (rootId == null) {
            System.err.println("[DesktopDriveScanner] Could not locate Movies folder in Google Drive.")
            return@withContext emptyList()
        }

        println("[DesktopDriveScanner] Discovered Movies root folder ID: $rootId. Scanning contents...")
        val discoveredMovies = mutableListOf<DesktopMediaItem>()
        val folderQueue = ArrayDeque<String>()
        val visitedFolders = mutableSetOf<String>()

        folderQueue.add(rootId)
        visitedFolders.add(rootId)

        while (folderQueue.isNotEmpty()) {
            val currentFolderId = folderQueue.poll() ?: break
            val items = fetchFolderContents(currentFolderId)

            for (item in items) {
                val resolvedId = resolveTargetId(item)
                val isFolder = item.mimeType == "application/vnd.google-apps.folder" ||
                        (item.mimeType == "application/vnd.google-apps.shortcut" && item.shortcutDetails?.targetMimeType == "application/vnd.google-apps.folder")

                if (isFolder) {
                    if (visitedFolders.add(resolvedId)) {
                        folderQueue.add(resolvedId)
                    }
                } else if (item.mimeType?.startsWith("video/") == true || item.name.endsWith(".mkv", true) || item.name.endsWith(".mp4", true)) {
                    val rawName = item.name ?: "Unknown"
                    val durationMs = item.videoMediaMetadata?.durationMillis ?: 0L
                    val driveThumb = item.thumbnailLink?.replace("s220", "s800") ?: ""

                    val meta = DesktopOnlineMetadataResolver.resolveMovie(rawName)

                    discoveredMovies.add(
                        DesktopMediaItem(
                            id = resolvedId,
                            title = meta.officialTitle,
                            thumbnailUrl = meta.posterUrl.ifBlank { driveThumb },
                            genre = meta.genre,
                            durationMs = durationMs
                        )
                    )
                }
            }
        }

        println("[DesktopDriveScanner] Finished movie scan: ${discoveredMovies.size} titles indexed.")
        discoveredMovies.sortedBy { it.title }
    }

    suspend fun loadSeriesFolders(): List<DesktopMediaItem> = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext emptyList()
        println("[DesktopDriveScanner] Resolving Series folder...")
        val rootId = resolveBestFolder(setOf("series", "tv series", "tv shows", "shows"))
        if (rootId == null) {
            System.err.println("[DesktopDriveScanner] Could not locate Series folder in Google Drive.")
            return@withContext emptyList()
        }

        println("[DesktopDriveScanner] Discovered Series root folder ID: $rootId. Scanning shows...")
        val items = fetchFolderContents(rootId)
        val seriesList = items.filter {
            it.mimeType == "application/vnd.google-apps.folder" ||
                    (it.mimeType == "application/vnd.google-apps.shortcut" && it.shortcutDetails?.targetMimeType == "application/vnd.google-apps.folder")
        }.map { folder ->
            val rawName = folder.name ?: "Unknown"
            val driveThumb = folder.thumbnailLink?.replace("s220", "s800") ?: ""
            val meta = DesktopOnlineMetadataResolver.resolveSeries(rawName)

            DesktopMediaItem(
                id = resolveTargetId(folder),
                title = meta.officialTitle,
                thumbnailUrl = meta.posterUrl.ifBlank { driveThumb },
                genre = meta.genres
            )
        }.sortedBy { it.title }

        println("[DesktopDriveScanner] Finished Series scan: ${seriesList.size} shows indexed.")
        seriesList
    }

    /**
     * Recursively loads and resolves official episode metadata for a given series folder.
     */
    suspend fun loadSeriesEpisodes(
        seriesFolderId: String,
        seriesName: String = "",
        tvMazeSeriesId: Long = -1L
    ): List<DesktopMediaItem> = withContext(Dispatchers.IO) {
        val discoveredEpisodes = mutableListOf<DesktopMediaItem>()
        val folderQueue = ArrayDeque<String>()
        val visitedFolders = mutableSetOf<String>()

        folderQueue.add(seriesFolderId)
        visitedFolders.add(seriesFolderId)

        val resolvedShowId = if (tvMazeSeriesId > 0L) {
            tvMazeSeriesId
        } else if (seriesName.isNotBlank()) {
            DesktopOnlineMetadataResolver.resolveSeries(seriesName).tvMazeId
        } else {
            -1L
        }

        while (folderQueue.isNotEmpty()) {
            val currentFolderId = folderQueue.poll() ?: break
            val items = fetchFolderContents(currentFolderId)

            for (item in items) {
                val resolvedId = resolveTargetId(item)
                val isFolder = item.mimeType == "application/vnd.google-apps.folder" ||
                              (item.mimeType == "application/vnd.google-apps.shortcut" && item.shortcutDetails?.targetMimeType == "application/vnd.google-apps.folder")

                if (isFolder) {
                    if (visitedFolders.add(resolvedId)) {
                        folderQueue.add(resolvedId)
                    }
                } else {
                    val rawName = item.name ?: "Unknown"
                    val isVideo = item.mimeType?.startsWith("video/") == true ||
                                  rawName.endsWith(".mkv", true) ||
                                  rawName.endsWith(".mp4", true)

                    if (isVideo) {
                        val durationMs = item.videoMediaMetadata?.durationMillis ?: 0L
                        val driveThumb = item.thumbnailLink?.replace("s220", "s800") ?: ""

                        val seasonEpisode = DesktopOnlineMetadataResolver.parseSeasonAndEpisodeNumbers(rawName)
                        val seasonNumber = seasonEpisode?.first ?: 1
                        val episodeNumber = seasonEpisode?.second ?: 1

                        val epMeta = DesktopOnlineMetadataResolver.resolveEpisode(
                            seriesName = seriesName,
                            tvMazeSeriesId = resolvedShowId,
                            seasonNumber = seasonNumber,
                            episodeNumber = episodeNumber,
                            rawFileName = rawName
                        )

                        val episodeDurationMs = if (epMeta.durationMinutes > 0L) {
                            epMeta.durationMinutes * 60_000L
                        } else {
                            durationMs
                        }

                        discoveredEpisodes.add(
                            DesktopMediaItem(
                                id = resolvedId,
                                title = epMeta.officialTitle,
                                thumbnailUrl = epMeta.thumbnailUrl.ifBlank { driveThumb },
                                genre = "S${seasonNumber.toString().padStart(2, '0')}E${episodeNumber.toString().padStart(2, '0')}",
                                durationMs = episodeDurationMs
                            )
                        )
                    }
                }
            }
        }

        discoveredEpisodes.sortedBy { it.title }
    }

    private fun fetchFolderContents(folderId: String): List<com.google.api.services.drive.model.File> {
        val service = driveService ?: return emptyList()
        val resultList = mutableListOf<com.google.api.services.drive.model.File>()
        var pageToken: String? = null

        do {
            try {
                val query = "'$folderId' in parents and trashed = false"
                val response = service.files().list()
                    .setQ(query)
                    .setFields("nextPageToken, files(id, name, mimeType, size, thumbnailLink, videoMediaMetadata, shortcutDetails)")
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setPageSize(1000)
                    .setPageToken(pageToken)
                    .execute()

                response.files?.let { resultList.addAll(it) }
                pageToken = response.nextPageToken
            } catch (e: Exception) {
                System.err.println("[DesktopDriveScanner] API error fetching folder contents ($folderId): ${e.message}")
                e.printStackTrace()
                break
            }
        } while (pageToken != null)

        return resultList
    }

    private fun resolveBestFolder(targetNames: Set<String>): String? {
        val service = driveService ?: return null
        val query = "(mimeType = 'application/vnd.google-apps.folder' or mimeType = 'application/vnd.google-apps.shortcut') and trashed = false"
        val candidates = mutableListOf<com.google.api.services.drive.model.File>()

        try {
            var pageToken: String? = null
            do {
                val response = service.files().list()
                    .setQ(query)
                    .setFields("nextPageToken, files(id, name, shortcutDetails)")
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setPageSize(500)
                    .setPageToken(pageToken)
                    .execute()

                response.files?.forEach { file ->
                    val normalized = file.name?.trim()?.lowercase() ?: ""
                    if (targetNames.contains(normalized)) {
                        candidates.add(file)
                    }
                }
                pageToken = response.nextPageToken
            } while (pageToken != null)
        } catch (e: Exception) {
            System.err.println("[DesktopDriveScanner] API error during resolveBestFolder: ${e.message}")
            e.printStackTrace()
            return null
        }

        if (candidates.isEmpty()) {
            System.err.println("[DesktopDriveScanner] No folder matching target names $targetNames was found in Drive root.")
            return null
        }
        if (candidates.size == 1) return resolveTargetId(candidates.first())

        var bestId: String? = null
        var maxCount = -1

        for (candidate in candidates) {
            val candidateId = resolveTargetId(candidate)
            val count = fetchFolderContents(candidateId).size
            if (count > maxCount) {
                maxCount = count
                bestId = candidateId
            }
        }

        return bestId ?: resolveTargetId(candidates.first())
    }

    private fun resolveTargetId(file: com.google.api.services.drive.model.File): String {
        return if (file.mimeType == "application/vnd.google-apps.shortcut" && file.shortcutDetails?.targetId != null) {
            file.shortcutDetails.targetId
        } else {
            file.id
        }
    }

    /**
     * Resolves complete TV Series metadata, season folders, and episode files from Google Drive.
     */
    suspend fun loadSeriesDetails(seriesFolderId: String, rawTitle: String): DesktopSeries = withContext(Dispatchers.IO) {
        val seriesMeta = DesktopOnlineMetadataResolver.resolveSeries(rawTitle)
        val cleanTitle = seriesMeta.officialTitle

        val rootItems = fetchFolderContents(seriesFolderId)
        val seasonFolders = rootItems.filter {
            it.mimeType == "application/vnd.google-apps.folder" ||
            (it.mimeType == "application/vnd.google-apps.shortcut" && it.shortcutDetails?.targetMimeType == "application/vnd.google-apps.folder")
        }

        val seasonsList = mutableListOf<DesktopSeason>()

        if (seasonFolders.isNotEmpty()) {
            for (seasonFolder in seasonFolders) {
                val seasonFolderId = resolveTargetId(seasonFolder)
                val seasonName = seasonFolder.name ?: "Season 1"
                val seasonNumMatcher = java.util.regex.Pattern.compile("\\d+").matcher(seasonName)
                val seasonNumber = if (seasonNumMatcher.find()) seasonNumMatcher.group().toIntOrNull() ?: 1 else 1

                val files = fetchFolderContents(seasonFolderId)
                val videoFiles = files.filter { 
                    it.mimeType?.startsWith("video/") == true || 
                    it.name?.endsWith(".mkv", true) == true || 
                    it.name?.endsWith(".mp4", true) == true 
                }

                val episodes = videoFiles.map { epFile ->
                    val epId = resolveTargetId(epFile)
                    val rawEpName = epFile.name ?: "Episode"
                    val durationMs = epFile.videoMediaMetadata?.durationMillis ?: 0L
                    val parsed = DesktopOnlineMetadataResolver.parseSeasonAndEpisodeNumbers(rawEpName)
                    val epNum = parsed?.second ?: 1
                    val actualSeason = parsed?.first ?: seasonNumber

                    val epMeta = DesktopOnlineMetadataResolver.resolveEpisode(
                        cleanTitle,
                        seriesMeta.tvMazeId,
                        actualSeason,
                        epNum,
                        rawEpName
                    )

                    DesktopEpisode(
                        id = epId,
                        seriesId = seriesFolderId,
                        seriesTitle = cleanTitle,
                        seasonNumber = actualSeason,
                        episodeNumber = epNum,
                        title = epMeta.officialTitle,
                        durationMs = if (durationMs > 0L) durationMs else (epMeta.durationMinutes * 60000L),
                        durationFormatted = if (durationMs > 0L) formatDuration(durationMs) else "${epMeta.durationMinutes} min",
                        thumbnailUrl = epMeta.thumbnailUrl.ifBlank { epFile.thumbnailLink?.replace("s220", "s800") ?: "" },
                        synopsis = "With visibility falling and the route disappearing beneath fresh snow, the team must decide whether to press toward the ridge or risk everything by turning back."
                    )
                }.sortedBy { it.episodeNumber }

                if (episodes.isNotEmpty()) {
                    seasonsList.add(
                        DesktopSeason(
                            seasonNumber = seasonNumber,
                            title = "Season $seasonNumber",
                            episodes = episodes
                        )
                    )
                }
            }
        } else {
            val videoFiles = rootItems.filter { 
                it.mimeType?.startsWith("video/") == true || 
                it.name?.endsWith(".mkv", true) == true || 
                it.name?.endsWith(".mp4", true) == true 
            }
            val episodes = videoFiles.map { epFile ->
                val epId = resolveTargetId(epFile)
                val rawEpName = epFile.name ?: "Episode"
                val durationMs = epFile.videoMediaMetadata?.durationMillis ?: 0L
                val parsed = DesktopOnlineMetadataResolver.parseSeasonAndEpisodeNumbers(rawEpName)
                val seasonNum = parsed?.first ?: 1
                val epNum = parsed?.second ?: 1

                val epMeta = DesktopOnlineMetadataResolver.resolveEpisode(
                    cleanTitle,
                    seriesMeta.tvMazeId,
                    seasonNum,
                    epNum,
                    rawEpName
                )

                DesktopEpisode(
                    id = epId,
                    seriesId = seriesFolderId,
                    seriesTitle = cleanTitle,
                    seasonNumber = seasonNum,
                    episodeNumber = epNum,
                    title = epMeta.officialTitle,
                    durationMs = if (durationMs > 0L) durationMs else (epMeta.durationMinutes * 60000L),
                    durationFormatted = if (durationMs > 0L) formatDuration(durationMs) else "${epMeta.durationMinutes} min",
                    thumbnailUrl = epMeta.thumbnailUrl.ifBlank { epFile.thumbnailLink?.replace("s220", "s800") ?: "" },
                    synopsis = "With visibility falling and the route disappearing beneath fresh snow, the team must decide whether to press toward the ridge or risk everything by turning back."
                )
            }

            val grouped = episodes.groupBy { it.seasonNumber }
            grouped.forEach { (sNum, eps) ->
                seasonsList.add(
                    DesktopSeason(
                        seasonNumber = sNum,
                        title = "Season $sNum",
                        episodes = eps.sortedBy { it.episodeNumber }
                    )
                )
            }
        }

        seasonsList.sortBy { it.seasonNumber }

        DesktopSeries(
            id = seriesFolderId,
            title = cleanTitle,
            backdropUrl = seriesMeta.posterUrl,
            posterUrl = seriesMeta.posterUrl,
            releaseYear = "2025",
            ageRating = "16+",
            genres = seriesMeta.genres,
            tagline = "Beyond the last road, survival becomes a choice.",
            synopsis = "Follow a team of explorers into the untamed Arctic as every step takes them further from home and closer to a discovery that changes the mission.",
            creators = "Maya Ellison, Peter Heid",
            cast = "Eva Lind, Marcus Vale, Theo Renn",
            seasons = seasonsList
        )
    }

    private fun formatDuration(durationMs: Long): String {
        val totalMinutes = durationMs / 60000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes} min"
    }
}
