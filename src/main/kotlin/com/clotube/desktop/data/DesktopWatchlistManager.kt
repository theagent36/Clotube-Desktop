package com.clotube.desktop.data

import androidx.compose.runtime.mutableIntStateOf
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class WatchlistStatus(val label: String) {
    WATCHED("Watched"),
    WATCH_LATER("Watch Later"),
    REWATCH("Rewatch")
}

enum class WatchlistMediaType(val label: String) {
    MOVIE("Movie"),
    SERIES("Series")
}

data class WatchlistEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val normalizedTitle: String = "",
    val mediaType: WatchlistMediaType = WatchlistMediaType.MOVIE,
    val status: WatchlistStatus = WatchlistStatus.WATCHED,
    val driveId: String? = null,
    val posterUrl: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)

data class WatchlistEnvelope(
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val entries: MutableList<WatchlistEntry> = mutableListOf()
)

object DesktopWatchlistManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val memoryCache = ConcurrentHashMap<String, MutableList<WatchlistEntry>>()
    private val attemptedPosterKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Observable Compose revision counter to recompose search results, cards, and Watchlist tab
    val watchlistRevision = mutableIntStateOf(0)

    fun notifyMutation() {
        watchlistRevision.intValue++
    }

    fun normalizeTitle(rawTitle: String): String {
        return rawTitle
            .substringBeforeLast('.')
            .replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ")
            .replace(Regex("""\b(1080p|720p|2160p|4k|hdr|bluray|webrip|web-dl|x264|x265|hevc|aac|dual audio)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""[^a-zA-Z0-9\s]"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase()
    }

    private fun getFile(email: String): File {
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Clotube/profiles/$sanitized")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "watchlist.json")
    }

    fun loadLocalSync(email: String): MutableList<WatchlistEntry> {
        if (email.isBlank()) return mutableListOf()
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        memoryCache[sanitized]?.let { return it }

        val file = getFile(email)
        val loaded = mutableListOf<WatchlistEntry>()
        if (file.exists() && file.length() > 0L) {
            try {
                val json = file.readText(Charsets.UTF_8)
                val sanitizedJson = json.replace("\"UNWATCHED\"", "\"REWATCH\"")
                val type = object : TypeToken<WatchlistEnvelope>() {}.type
                val envelope: WatchlistEnvelope? = gson.fromJson(sanitizedJson, type)
                if (envelope?.entries != null) {
                    // Prune deleted tombstones older than 30 days
                    val cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                    loaded.addAll(envelope.entries.filter { !it.isDeleted || it.updatedAt > cutoff })
                }
            } catch (e: Exception) {
                System.err.println("[DesktopWatchlistManager] Failed to read watchlist.json: ${e.message}")
            }
        }
        memoryCache[sanitized] = loaded
        return loaded
    }

    private fun saveLocal(email: String, entries: List<WatchlistEntry>) {
        if (email.isBlank()) return
        try {
            val envelope = WatchlistEnvelope(
                version = 1,
                updatedAt = System.currentTimeMillis(),
                entries = entries.toMutableList()
            )
            getFile(email).writeText(gson.toJson(envelope), Charsets.UTF_8)
            notifyMutation()
        } catch (e: Exception) {
            System.err.println("[DesktopWatchlistManager] Failed to save watchlist.json: ${e.message}")
        }
    }

    fun getLocalPayloadJson(email: String): String {
        val entries = loadLocalSync(email)
        val envelope = WatchlistEnvelope(
            version = 1,
            updatedAt = System.currentTimeMillis(),
            entries = entries.toMutableList()
        )
        return gson.toJson(envelope)
    }

    fun getVisibleEntries(email: String): List<WatchlistEntry> {
        if (email.isBlank()) return emptyList()
        return loadLocalSync(email)
            .filter { !it.isDeleted }
            .sortedByDescending { it.updatedAt }
    }

    /**
     * Resolves status for a Drive item or title query.
     * Matches by driveId first, falling back to normalizedTitle so deleted-and-readded
     * Drive files or custom entries retain their status.
     */
    fun getStatusForMedia(email: String, title: String, driveId: String? = null): WatchlistStatus? {
        if (email.isBlank()) return null
        val list = loadLocalSync(email)
        val norm = normalizeTitle(title)

        val match = synchronized(list) {
            list.firstOrNull { !it.isDeleted && !driveId.isNullOrBlank() && it.driveId == driveId }
                ?: list.firstOrNull { !it.isDeleted && norm.isNotBlank() && it.normalizedTitle == norm }
        }
        return match?.status
    }

    fun isWatched(email: String, title: String, driveId: String? = null): Boolean {
        return getStatusForMedia(email, title, driveId) == WatchlistStatus.WATCHED
    }

    /**
     * Adds a new custom or Drive-linked entry, or updates an existing match by normalizedTitle/driveId.
     */
    suspend fun upsertEntry(
        email: String,
        title: String,
        mediaType: WatchlistMediaType,
        status: WatchlistStatus,
        driveId: String? = null,
        posterUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        if (email.isBlank() || title.isBlank()) return@withContext
        val list = loadLocalSync(email)
        val cleanTitle = title.trim()
        val norm = normalizeTitle(cleanTitle)
        val now = System.currentTimeMillis()

        synchronized(list) {
            val existingIdx = list.indexOfFirst {
                (!driveId.isNullOrBlank() && it.driveId == driveId) ||
                    (norm.isNotBlank() && it.normalizedTitle == norm)
            }

            if (existingIdx >= 0) {
                val existing = list[existingIdx]
                val resolvedPoster = posterUrl?.takeIf { it.isNotBlank() } ?: existing.posterUrl
                list[existingIdx] = existing.copy(
                    title = cleanTitle,
                    normalizedTitle = norm,
                    mediaType = mediaType,
                    status = status,
                    driveId = driveId ?: existing.driveId,
                    posterUrl = resolvedPoster,
                    updatedAt = now,
                    isDeleted = false
                )
            } else {
                list.add(
                    WatchlistEntry(
                        id = UUID.randomUUID().toString(),
                        title = cleanTitle,
                        normalizedTitle = norm,
                        mediaType = mediaType,
                        status = status,
                        driveId = driveId,
                        posterUrl = posterUrl?.takeIf { it.isNotBlank() },
                        updatedAt = now,
                        isDeleted = false
                    )
                )
            }
        }
        saveLocal(email, list)
    }

    /**
     * Updates an existing watchlist item by its permanent UUID.
     */
    suspend fun updateEntryById(
        email: String,
        entryId: String,
        newTitle: String? = null,
        newStatus: WatchlistStatus? = null,
        newMediaType: WatchlistMediaType? = null
    ) = withContext(Dispatchers.IO) {
        if (email.isBlank() || entryId.isBlank()) return@withContext
        val list = loadLocalSync(email)
        val now = System.currentTimeMillis()
        var modified = false

        synchronized(list) {
            val idx = list.indexOfFirst { it.id == entryId }
            if (idx >= 0) {
                val current = list[idx]
                val updatedTitle = newTitle?.trim()?.takeIf { it.isNotBlank() } ?: current.title
                val updatedNorm = normalizeTitle(updatedTitle)
                val updatedType = newMediaType ?: current.mediaType
                val titleOrTypeChanged = updatedNorm != current.normalizedTitle || updatedType != current.mediaType

                if (titleOrTypeChanged) {
                    attemptedPosterKeys.remove("${updatedType.name}:$updatedNorm")
                }

                list[idx] = current.copy(
                    title = updatedTitle,
                    normalizedTitle = updatedNorm,
                    status = newStatus ?: current.status,
                    mediaType = updatedType,
                    posterUrl = if (titleOrTypeChanged) null else current.posterUrl,
                    updatedAt = now,
                    isDeleted = false
                )
                modified = true
            }
        }
        if (modified) saveLocal(email, list)
    }

    /**
     * Marks an entry as deleted via tombstone so it stays removed across Google Drive syncs.
     */
    suspend fun deleteEntryById(email: String, entryId: String) = withContext(Dispatchers.IO) {
        if (email.isBlank() || entryId.isBlank()) return@withContext
        val list = loadLocalSync(email)
        val now = System.currentTimeMillis()
        var modified = false

        synchronized(list) {
            val idx = list.indexOfFirst { it.id == entryId }
            if (idx >= 0) {
                list[idx] = list[idx].copy(
                    isDeleted = true,
                    updatedAt = now
                )
                modified = true
            }
        }
        if (modified) saveLocal(email, list)
    }

    /**
     * Called automatically when playback reaches >= 90% completion.
     * Respects explicit UNWATCHED overrides if the user manually marked it unwatched afterward.
     */
    suspend fun autoMarkWatchedIfEligible(
        email: String,
        title: String,
        mediaType: WatchlistMediaType,
        driveId: String?
    ) = withContext(Dispatchers.IO) {
        if (email.isBlank() || title.isBlank()) return@withContext
        val list = loadLocalSync(email)
        val norm = normalizeTitle(title)
        val existing = synchronized(list) {
            list.firstOrNull { !it.isDeleted && ((!driveId.isNullOrBlank() && it.driveId == driveId) || it.normalizedTitle == norm) }
        }
        if (existing?.status == WatchlistStatus.WATCHED) return@withContext
        upsertEntry(email, title, mediaType, WatchlistStatus.WATCHED, driveId)
    }

    /**
     * Merges remote Google Drive watchlist entries using Last-Write-Wins per entry UUID/normalizedTitle.
     */
    fun mergeRemotePayload(email: String, remoteJson: String): Boolean {
        if (email.isBlank() || remoteJson.isBlank()) return false
        val local = loadLocalSync(email)
        var changed = false

        try {
            val sanitizedJson = remoteJson.replace("\"UNWATCHED\"", "\"REWATCH\"")
            val type = object : TypeToken<WatchlistEnvelope>() {}.type
            val remoteEnvelope: WatchlistEnvelope = gson.fromJson(sanitizedJson, type) ?: return false

            synchronized(local) {
                for (remoteEntry in remoteEnvelope.entries) {
                    val idx = local.indexOfFirst {
                        it.id == remoteEntry.id ||
                            (remoteEntry.normalizedTitle.isNotBlank() && it.normalizedTitle == remoteEntry.normalizedTitle)
                    }
                    if (idx < 0) {
                        local.add(remoteEntry)
                        changed = true
                    } else {
                        val localEntry = local[idx]
                        if (remoteEntry.updatedAt > localEntry.updatedAt) {
                            val mergedPoster = remoteEntry.posterUrl?.takeIf { it.isNotBlank() } ?: localEntry.posterUrl
                            local[idx] = remoteEntry.copy(id = localEntry.id, posterUrl = mergedPoster)
                            changed = true
                        } else if (localEntry.posterUrl.isNullOrBlank() && !remoteEntry.posterUrl.isNullOrBlank()) {
                            local[idx] = localEntry.copy(posterUrl = remoteEntry.posterUrl)
                            changed = true
                        }
                    }
                }
            }

            if (changed) {
                saveLocal(email, local)
            }
        } catch (e: Exception) {
            System.err.println("[DesktopWatchlistManager] Error merging remote watchlist: ${e.message}")
        }
        return changed
    }

    suspend fun updateEntryPoster(
        email: String,
        entryId: String,
        posterUrl: String
    ) = withContext(Dispatchers.IO) {
        if (email.isBlank() || entryId.isBlank() || posterUrl.isBlank()) return@withContext
        val list = loadLocalSync(email)
        var modified = false

        synchronized(list) {
            val idx = list.indexOfFirst { it.id == entryId }
            if (idx >= 0 && list[idx].posterUrl != posterUrl) {
                list[idx] = list[idx].copy(
                    posterUrl = posterUrl,
                    updatedAt = System.currentTimeMillis()
                )
                modified = true
            }
        }
        if (modified) {
            saveLocal(email, list)
        }
    }

    /**
     * Derives official poster thumbnails from the movie/series name for all off-Drive
     * Watchlist entries that do not yet have a cached posterUrl.
     */
    suspend fun resolveMissingOffDrivePosters(
        email: String,
        accessToken: String,
        driveMovies: List<com.clotube.desktop.model.DesktopMediaItem> = emptyList(),
        driveSeries: List<com.clotube.desktop.model.DesktopMediaItem> = emptyList()
    ) = withContext(Dispatchers.IO) {
        if (email.isBlank()) return@withContext

        val driveMovieByNorm = driveMovies.associateBy { normalizeTitle(it.title) }
        val driveSeriesByNorm = driveSeries.associateBy { normalizeTitle(it.title) }

        val candidates = getVisibleEntries(email).filter { entry ->
            entry.posterUrl.isNullOrBlank() &&
                entry.title.isNotBlank() &&
                !attemptedPosterKeys.contains("${entry.mediaType.name}:${entry.normalizedTitle}")
        }

        if (candidates.isEmpty()) return@withContext

        var anyUpdated = false
        for (entry in candidates) {
            val attemptKey = "${entry.mediaType.name}:${entry.normalizedTitle}"
            attemptedPosterKeys.add(attemptKey)

            // Fast path: if an active Drive item matches by title, reuse its resolved poster
            val matchedDrivePoster = when (entry.mediaType) {
                WatchlistMediaType.MOVIE -> driveMovieByNorm[entry.normalizedTitle]?.thumbnailUrl
                WatchlistMediaType.SERIES -> driveSeriesByNorm[entry.normalizedTitle]?.thumbnailUrl
            }?.takeIf { it.isNotBlank() && !it.contains("drive.google.com/thumbnail") }

            val derivedPoster = matchedDrivePoster ?: when (entry.mediaType) {
                WatchlistMediaType.MOVIE -> {
                    DesktopOnlineMetadataResolver.resolveMovie(entry.title).posterUrl
                }
                WatchlistMediaType.SERIES -> {
                    DesktopOnlineMetadataResolver.resolveSeries(entry.title).posterUrl.ifBlank {
                        DesktopOnlineMetadataResolver.resolveMovie(entry.title).posterUrl
                    }
                }
            }

            if (derivedPoster.isNotBlank()) {
                updateEntryPoster(email, entry.id, derivedPoster)
                if (accessToken.isNotBlank()) {
                    DesktopThumbnailPrefetcher.enqueueSingle(derivedPoster, null, accessToken)
                }
                anyUpdated = true
            }
        }

        if (anyUpdated && accessToken.isNotBlank()) {
            try {
                DesktopWatchlistSync.pushLocalWatchlist(email, accessToken)
            } catch (_: Exception) {}
        }
    }
}