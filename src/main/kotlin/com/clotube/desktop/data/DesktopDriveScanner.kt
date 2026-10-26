package com.clotube.desktop.data

import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.model.DesktopSeason
import com.clotube.desktop.model.DesktopSeries
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.clotube.desktop.auth.DesktopAuthManager
import com.google.api.client.auth.oauth2.Credential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

private data class FolderQueueNode(
    val folderId: String,
    val folderName: String,
    val inheritedLanguage: String?
)

class DesktopDriveScanner(
    accessToken: String = "",
    credential: Credential? = null
) {

    private val driveService: Drive?

    companion object {
        private const val INITIAL_FAST_PAINT_SIZE = 5
        private const val SUBSEQUENT_PACKET_SIZE = 4

        // [7.2] Reusable singleton HTTP transport and JSON factory across all scanner instances
        private val sharedHttpTransport by lazy { GoogleNetHttpTransport.newTrustedTransport() }
        private val sharedJsonFactory by lazy { GsonFactory.getDefaultInstance() }
    }

    init {
        driveService = try {
            val activeCredential = credential ?: DesktopAuthManager.getCredential()

            if (activeCredential != null) {
                Drive.Builder(sharedHttpTransport, sharedJsonFactory, activeCredential)
                    .setApplicationName("Clotube-Windows")
                    .build()
            } else if (accessToken.isNotBlank()) {
                Drive.Builder(sharedHttpTransport, sharedJsonFactory) { request ->
                    request.headers.authorization = "Bearer $accessToken"
                }.setApplicationName("Clotube-Windows").build()
            } else {
                System.err.println("[DesktopDriveScanner] Neither active Credential nor accessToken is available.")
                null
            }
        } catch (e: Exception) {
            System.err.println("[DesktopDriveScanner] Failed to initialize Google Drive service: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Streams movies in gradual, progressive packets:
     * 1. Emits the first 5 movies immediately for rapid first paint (~1.2s).
     * 2. Continues scanning and emits in packets of 4 movies (1 row) so items trickle in smoothly.
     * 3. Flushes remaining items upon completion without sudden large-batch dumps.
     */
    fun streamMovies(): Flow<List<DesktopMediaItem>> = flow {
        val service = driveService ?: return@flow
        println("[DesktopDriveScanner] Resolving Movies folder...")
        val rootId = resolveBestFolder(setOf("movies", "movie", "films", "cinema"))
        if (rootId == null) {
            System.err.println("[DesktopDriveScanner] Could not locate Movies folder in Google Drive.")
            emit(emptyList())
            return@flow
        }

        println("[DesktopDriveScanner] Discovered Movies root folder ID: $rootId. Starting progressive packetized scan...")
        val discoveredMovies = mutableListOf<DesktopMediaItem>()
        val folderQueue = ArrayDeque<FolderQueueNode>()
        val visitedFolders = mutableSetOf<String>()

        folderQueue.add(FolderQueueNode(rootId, "Movies", null))
        visitedFolders.add(rootId)

        var emittedFirstChunk = false
        var lastEmittedCount = 0

        while (folderQueue.isNotEmpty()) {
            val currentNode = folderQueue.poll() ?: break
            val items = fetchFolderContents(currentNode.folderId)

            for (item in items) {
                val resolvedId = resolveTargetId(item)
                val isFolder = item.mimeType == "application/vnd.google-apps.folder" ||
                        (item.mimeType == "application/vnd.google-apps.shortcut" && item.shortcutDetails?.targetMimeType == "application/vnd.google-apps.folder")

                if (isFolder) {
                    if (visitedFolders.add(resolvedId)) {
                        val folderName = item.name ?: ""
                        val folderLanguage = detectLanguageFromFolderName(folderName) ?: currentNode.inheritedLanguage
                        folderQueue.add(FolderQueueNode(resolvedId, folderName, folderLanguage))
                    }
                } else if (item.mimeType?.startsWith("video/") == true || item.name.endsWith(".mkv", true) || item.name.endsWith(".mp4", true)) {
                    val rawName = item.name ?: "Unknown"
                    val durationMs = item.videoMediaMetadata?.durationMillis ?: 0L
                    val driveThumb = item.thumbnailLink?.replace("s220", "s800")
                        ?: "https://drive.google.com/thumbnail?id=$resolvedId&sz=w800"

                    val meta = DesktopOnlineMetadataResolver.resolveMovie(rawName)

                    val effectiveLanguage = currentNode.inheritedLanguage
                        ?: detectLanguageFromFolderName(currentNode.folderName)
                        ?: meta.language.ifBlank { DesktopOnlineMetadataResolver.deduceLanguageFromTitle(meta.officialTitle) }

                    discoveredMovies.add(
                        DesktopMediaItem(
                            id = resolvedId,
                            title = meta.officialTitle,
                            thumbnailUrl = meta.posterUrl.ifBlank { driveThumb },
                            fallbackThumbnailUrl = driveThumb,
                            genre = meta.genre,
                            durationMs = durationMs,
                            language = effectiveLanguage
                        )
                    )

                    // 1. FAST FIRST PAINT: Emit initial 5 movies immediately
                    if (!emittedFirstChunk && discoveredMovies.size >= INITIAL_FAST_PAINT_SIZE) {
                        emittedFirstChunk = true
                        lastEmittedCount = discoveredMovies.size
                        println("[DesktopDriveScanner] Fast first paint: Emitting initial 5 movies to UI.")
                        emit(discoveredMovies.toList())
                    }
                    // 2. PROGRESSIVE PACKETS: Emit every time 4 new movies are resolved (1 grid row)
                    else if (emittedFirstChunk && (discoveredMovies.size - lastEmittedCount) >= SUBSEQUENT_PACKET_SIZE) {
                        lastEmittedCount = discoveredMovies.size
                        println("[DesktopDriveScanner] Progressive packet: Emitted batch (${discoveredMovies.size} total movies).")
                        emit(discoveredMovies.toList())
                    }
                }
            }
        }

        // 3. FINAL FLUSH: Emit any remaining items that didn't hit an exact multiple of 4
        if (discoveredMovies.size > lastEmittedCount || !emittedFirstChunk) {
            lastEmittedCount = discoveredMovies.size
            println("[DesktopDriveScanner] Full movie scan complete: ${discoveredMovies.size} total movies indexed.")
            emit(discoveredMovies.toList())
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Streams TV series folders in gradual, progressive packets.
     */
    fun streamSeriesFolders(): Flow<List<DesktopMediaItem>> = flow {
        val service = driveService ?: return@flow
        println("[DesktopDriveScanner] Resolving TV Series folder...")
        val rootId = resolveBestFolder(setOf("series", "tv series", "tv shows", "shows"))
        if (rootId == null) {
            System.err.println("[DesktopDriveScanner] Could not locate TV Series folder in Google Drive.")
            emit(emptyList())
            return@flow
        }

        val items = fetchFolderContents(rootId)
        val seriesFolders = items.filter {
            it.mimeType == "application/vnd.google-apps.folder" ||
                    (it.mimeType == "application/vnd.google-apps.shortcut" && it.shortcutDetails?.targetMimeType == "application/vnd.google-apps.folder")
        }

        val discoveredSeries = mutableListOf<DesktopMediaItem>()
        var emittedFirstChunk = false
        var lastEmittedCount = 0

        for (folder in seriesFolders) {
            val rawName = folder.name ?: "Unknown"
            val targetId = resolveTargetId(folder)
            val driveThumb = folder.thumbnailLink?.replace("s220", "s800")
                ?: "https://drive.google.com/thumbnail?id=$targetId&sz=w800"
            val meta = DesktopOnlineMetadataResolver.resolveSeries(rawName)

            discoveredSeries.add(
                DesktopMediaItem(
                    id = targetId,
                    title = meta.officialTitle,
                    thumbnailUrl = meta.posterUrl.ifBlank { driveThumb },
                    fallbackThumbnailUrl = driveThumb,
                    genre = meta.genres
                )
            )

            // Fast first paint for series
            if (!emittedFirstChunk && discoveredSeries.size >= INITIAL_FAST_PAINT_SIZE) {
                emittedFirstChunk = true
                lastEmittedCount = discoveredSeries.size
                println("[DesktopDriveScanner] Fast first paint: Emitting initial 5 series to UI.")
                emit(discoveredSeries.toList())
            }
            // Progressive packet for series (every 4 series)
            else if (emittedFirstChunk && (discoveredSeries.size - lastEmittedCount) >= SUBSEQUENT_PACKET_SIZE) {
                lastEmittedCount = discoveredSeries.size
                println("[DesktopDriveScanner] Progressive packet: Emitted batch (${discoveredSeries.size} total series).")
                emit(discoveredSeries.toList())
            }
        }

        // Final flush for series
        if (discoveredSeries.size > lastEmittedCount || !emittedFirstChunk) {
            lastEmittedCount = discoveredSeries.size
            println("[DesktopDriveScanner] Full series scan complete: ${discoveredSeries.size} total series indexed.")
            emit(discoveredSeries.toList())
        }
    }.flowOn(Dispatchers.IO)

    /**
     * One-shot scan that returns the complete list of movies from Google Drive
     * without emitting intermediate partial packets.
     */
    suspend fun loadMovies(): List<DesktopMediaItem> = withContext(Dispatchers.IO) {
        val rootId = resolveBestFolder(setOf("movies", "movie", "films", "cinema")) ?: return@withContext emptyList()
        val discoveredMovies = mutableListOf<DesktopMediaItem>()
        val folderQueue = ArrayDeque<FolderQueueNode>()
        val visitedFolders = mutableSetOf<String>()

        folderQueue.add(FolderQueueNode(rootId, "Movies", null))
        visitedFolders.add(rootId)

        while (folderQueue.isNotEmpty()) {
            val currentNode = folderQueue.poll() ?: break
            val items = fetchFolderContents(currentNode.folderId)

            for (item in items) {
                val resolvedId = resolveTargetId(item)
                val isFolder = item.mimeType == "application/vnd.google-apps.folder" ||
                        (item.mimeType == "application/vnd.google-apps.shortcut" && item.shortcutDetails?.targetMimeType == "application/vnd.google-apps.folder")

                if (isFolder) {
                    if (visitedFolders.add(resolvedId)) {
                        val folderName = item.name ?: ""
                        val folderLanguage = detectLanguageFromFolderName(folderName) ?: currentNode.inheritedLanguage
                        folderQueue.add(FolderQueueNode(resolvedId, folderName, folderLanguage))
                    }
                } else if (item.mimeType?.startsWith("video/") == true || item.name.endsWith(".mkv", true) || item.name.endsWith(".mp4", true)) {
                    val rawName = item.name ?: "Unknown"
                    val durationMs = item.videoMediaMetadata?.durationMillis ?: 0L
                    val driveThumb = item.thumbnailLink?.replace("s220", "s800") ?: ""

                    val meta = DesktopOnlineMetadataResolver.resolveMovie(rawName)
                    val effectiveLanguage = currentNode.inheritedLanguage
                        ?: detectLanguageFromFolderName(currentNode.folderName)
                        ?: meta.language.ifBlank { DesktopOnlineMetadataResolver.deduceLanguageFromTitle(meta.officialTitle) }

                    discoveredMovies.add(
                        DesktopMediaItem(
                            id = resolvedId,
                            title = meta.officialTitle,
                            thumbnailUrl = meta.posterUrl.ifBlank { driveThumb },
                            fallbackThumbnailUrl = driveThumb,
                            genre = meta.genre,
                            durationMs = durationMs,
                            language = effectiveLanguage
                        )
                    )
                }
            }
        }
        discoveredMovies.sortedBy { it.title }
    }

    /**
     * One-shot scan that returns the complete list of TV series from Google Drive
     * without emitting intermediate partial packets.
     */
    suspend fun loadSeriesFolders(): List<DesktopMediaItem> = withContext(Dispatchers.IO) {
        val rootId = resolveBestFolder(setOf("series", "tv series", "tv shows", "shows")) ?: return@withContext emptyList()
        val items = fetchFolderContents(rootId)
        val seriesFolders = items.filter {
            it.mimeType == "application/vnd.google-apps.folder" ||
                    (it.mimeType == "application/vnd.google-apps.shortcut" && it.shortcutDetails?.targetMimeType == "application/vnd.google-apps.folder")
        }

        val discoveredSeries = mutableListOf<DesktopMediaItem>()
        for (folder in seriesFolders) {
            val rawName = folder.name ?: "Unknown"
            val driveThumb = folder.thumbnailLink?.replace("s220", "s800") ?: ""
            val meta = DesktopOnlineMetadataResolver.resolveSeries(rawName)

            discoveredSeries.add(
                DesktopMediaItem(
                    id = resolveTargetId(folder),
                    title = meta.officialTitle,
                    thumbnailUrl = meta.posterUrl.ifBlank { driveThumb },
                    fallbackThumbnailUrl = driveThumb,
                    genre = meta.genres
                )
            )
        }
        discoveredSeries.sortedBy { it.title }
    }

    private fun detectLanguageFromFolderName(folderName: String): String? {
        val lower = folderName.lowercase().trim()
        return when {
            Regex("(?i)\\b(bengali|bangla|kolkata|tollywood)\\b").containsMatchIn(lower) -> "Bengali"
            Regex("(?i)\\b(hindi|bollywood)\\b").containsMatchIn(lower) -> "Hindi"
            Regex("(?i)\\b(english|hollywood|foreign)\\b").containsMatchIn(lower) -> "English"
            else -> null
        }
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

        if (candidates.isEmpty()) return null
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
                        val driveThumb = item.thumbnailLink?.replace("s220", "s800")
                            ?: "https://drive.google.com/thumbnail?id=$resolvedId&sz=w800"

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
                                fallbackThumbnailUrl = driveThumb,
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

    /**
     * Resolves complete TV Series metadata, season folders, and episode files from Google Drive
     * strictly adhering to the hierarchy: Series -> [Series Name] -> [Season No.] -> [Episode Files]
     */
    suspend fun loadSeriesDetails(seriesFolderId: String, rawTitle: String): DesktopSeries = withContext(Dispatchers.IO) {
        val rootItems = fetchFolderContents(seriesFolderId)
        val seasonFolders = rootItems.filter {
            it.mimeType == "application/vnd.google-apps.folder" ||
            (it.mimeType == "application/vnd.google-apps.shortcut" && it.shortcutDetails?.targetMimeType == "application/vnd.google-apps.folder")
        }

        // Estimate episode count to disambiguate miniseries from full series
        val approxEpisodesCount = if (seasonFolders.isNotEmpty()) {
            val firstSeasonFiles = fetchFolderContents(resolveTargetId(seasonFolders.first()))
            firstSeasonFiles.count { 
                it.mimeType?.startsWith("video/") == true || 
                (it.name ?: "").endsWith(".mkv", true) || 
                (it.name ?: "").endsWith(".mp4", true) 
            }
        } else {
            rootItems.count { 
                it.mimeType?.startsWith("video/") == true || 
                (it.name ?: "").endsWith(".mkv", true) || 
                (it.name ?: "").endsWith(".mp4", true) 
            }
        }

        // Resolve official series metadata with smart episode count hint
        val seriesMeta = DesktopOnlineMetadataResolver.resolveSeries(rawTitle, approxEpisodesCount)
        val cleanTitle = seriesMeta.officialTitle.ifBlank { rawTitle }

        // Bulk fetch all episodes from TVMaze in one single request
        val onlineEpisodesMap = if (seriesMeta.tvMazeId > 0L) {
            DesktopOnlineMetadataResolver.fetchShowEpisodesBulk(seriesMeta.tvMazeId)
        } else {
            emptyMap()
        }

        val seasonsList = mutableListOf<DesktopSeason>()

        if (seasonFolders.isNotEmpty()) {
            for (seasonFolder in seasonFolders) {
                val seasonFolderId = resolveTargetId(seasonFolder)
                val seasonFolderName = seasonFolder.name ?: "Season 1"
                val seasonNumber = DesktopOnlineMetadataResolver.parseSeasonNumberFromFolder(seasonFolderName)

                val files = fetchFolderContents(seasonFolderId)
                val videoFiles = files.filter { 
                    it.mimeType?.startsWith("video/") == true || 
                    (it.name ?: "").endsWith(".mkv", true) || 
                    (it.name ?: "").endsWith(".mp4", true) 
                }

                val episodes = videoFiles.map { epFile ->
                    val epId = resolveTargetId(epFile)
                    val rawEpName = epFile.name ?: "Episode"
                    val durationMs = epFile.videoMediaMetadata?.durationMillis ?: 0L
                    val epNum = DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName(rawEpName, seasonNumber)

                    // Look up official episode metadata
                    val onlineMeta = onlineEpisodesMap[Pair(seasonNumber, epNum)]

                    val officialTitle = onlineMeta?.officialTitle 
                        ?: DesktopOnlineMetadataResolver.cleanEpisodeFallbackTitle(rawEpName, epNum, cleanTitle)

                    val driveThumb = epFile.thumbnailLink?.replace("s220", "s800")
                        ?: "https://drive.google.com/thumbnail?id=$epId&sz=w800"

                    val officialThumb = onlineMeta?.thumbnailUrl?.ifBlank { driveThumb } ?: driveThumb
                    val effectiveDur = if (durationMs > 0L) durationMs else (onlineMeta?.durationMinutes?.times(60000L) ?: 45L * 60000L)
                    val synopsis = onlineMeta?.synopsis?.ifBlank { "Episode $epNum of $cleanTitle." } ?: "Episode $epNum of $cleanTitle."

                    DesktopEpisode(
                        id = epId,
                        seriesId = seriesFolderId,
                        seriesTitle = cleanTitle,
                        seasonNumber = seasonNumber,
                        episodeNumber = epNum,
                        title = officialTitle,
                        durationMs = effectiveDur,
                        durationFormatted = formatDuration(effectiveDur),
                        thumbnailUrl = officialThumb,
                        synopsis = synopsis
                    )
                }.sortedBy { it.episodeNumber }

                if (episodes.isNotEmpty()) {
                    val seasonDisplayTitle = if (seasonNumber == 0) "Specials" else "Season $seasonNumber"
                    seasonsList.add(
                        DesktopSeason(
                            seasonNumber = seasonNumber,
                            title = seasonDisplayTitle,
                            episodes = episodes
                        )
                    )
                }
            }
        } else {
            val videoFiles = rootItems.filter { 
                it.mimeType?.startsWith("video/") == true || 
                (it.name ?: "").endsWith(".mkv", true) || 
                (it.name ?: "").endsWith(".mp4", true) 
            }

            val episodes = videoFiles.map { epFile ->
                val epId = resolveTargetId(epFile)
                val rawEpName = epFile.name ?: "Episode"
                val durationMs = epFile.videoMediaMetadata?.durationMillis ?: 0L
                val parsed = DesktopOnlineMetadataResolver.parseSeasonAndEpisodeNumbers(rawEpName)
                val seasonNum = parsed?.first ?: 1
                val epNum = parsed?.second ?: DesktopOnlineMetadataResolver.parseEpisodeNumberFromFileName(rawEpName, seasonNum)

                val onlineMeta = onlineEpisodesMap[Pair(seasonNum, epNum)]
                val officialTitle = onlineMeta?.officialTitle 
                    ?: DesktopOnlineMetadataResolver.cleanEpisodeFallbackTitle(rawEpName, epNum, cleanTitle)

                val driveThumb = epFile.thumbnailLink?.replace("s220", "s800")
                    ?: "https://drive.google.com/thumbnail?id=$epId&sz=w800"

                val officialThumb = onlineMeta?.thumbnailUrl?.ifBlank { driveThumb } ?: driveThumb
                val effectiveDur = if (durationMs > 0L) durationMs else (onlineMeta?.durationMinutes?.times(60000L) ?: 45L * 60000L)

                DesktopEpisode(
                    id = epId,
                    seriesId = seriesFolderId,
                    seriesTitle = cleanTitle,
                    seasonNumber = seasonNum,
                    episodeNumber = epNum,
                    title = officialTitle,
                    durationMs = effectiveDur,
                    durationFormatted = formatDuration(effectiveDur),
                    thumbnailUrl = officialThumb,
                    synopsis = onlineMeta?.synopsis ?: "Episode $epNum of $cleanTitle."
                )
            }

            val grouped = episodes.groupBy { it.seasonNumber }
            grouped.forEach { (sNum, eps) ->
                seasonsList.add(
                    DesktopSeason(
                        seasonNumber = sNum,
                        title = if (sNum == 0) "Specials" else "Season $sNum",
                        episodes = eps.sortedBy { it.episodeNumber }
                    )
                )
            }
        }

        seasonsList.sortBy { it.seasonNumber }
        println("[DesktopDriveScanner] Indexed ${seasonsList.size} seasons and ${seasonsList.sumOf { it.episodes.size }} total episodes for: $cleanTitle")

        DesktopSeries(
            id = seriesFolderId,
            title = cleanTitle,
            backdropUrl = seriesMeta.backdropUrl.ifBlank { seriesMeta.posterUrl },
            posterUrl = seriesMeta.posterUrl,
            releaseYear = seriesMeta.releaseYear,
            ageRating = seriesMeta.ageRating.ifBlank { "TV-MA" },
            genres = seriesMeta.genres.ifBlank { "Drama • Thriller" },
            tagline = seriesMeta.tagline.ifBlank { "" },
            synopsis = seriesMeta.synopsis.ifBlank { "" },
            creators = seriesMeta.creators.ifBlank { "" },
            cast = seriesMeta.cast.ifBlank { "" },
            seasons = seasonsList
        )
    }

    suspend fun loadEpisodesForSeries(
        seriesFolderId: String,
        seriesTitle: String
    ): List<DesktopSeriesEpisode> = withContext(Dispatchers.IO) {
        val series = loadSeriesDetails(seriesFolderId, seriesTitle)
        series.seasons.flatMap { it.episodes }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalMinutes = durationMs / 60000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes} min"
    }
}
