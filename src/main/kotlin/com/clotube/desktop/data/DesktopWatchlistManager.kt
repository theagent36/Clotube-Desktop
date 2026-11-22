package com.clotube.desktop.data

import androidx.compose.runtime.mutableIntStateOf
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.model.GlobalSearchResult
import com.clotube.desktop.model.SearchMediaType
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class WatchlistStatus(val label: String) {
    WATCHED("Watched"),
    WATCH_LATER("Watch Later"),
    REWATCH("Rewatch"),
    UNWATCHED("Unwatched");

    companion object {
        val selectableStatuses: List<WatchlistStatus> = listOf(WATCHED, WATCH_LATER, REWATCH)

        fun fromRaw(raw: String?): WatchlistStatus {
            if (raw.isNullOrBlank()) return UNWATCHED
            val normalized = raw.trim().lowercase().replace(" ", "_").replace("-", "_")
            return when (normalized) {
                "watched" -> WATCHED
                "watch_later", "watchlater" -> WATCH_LATER
                "rewatch" -> REWATCH
                "unwatched" -> UNWATCHED
                else -> entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: UNWATCHED
            }
        }
    }
}

enum class WatchlistMediaType(val label: String) {
    MOVIE("Movie"),
    SERIES("Series");

    companion object {
        fun fromRaw(raw: String?): WatchlistMediaType {
            if (raw.isNullOrBlank()) return MOVIE
            return when (raw.trim().lowercase()) {
                "series", "tv", "show", "tv_series" -> SERIES
                else -> MOVIE
            }
        }
    }
}

data class WatchlistEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val normalizedTitle: String = "",
    val mediaType: WatchlistMediaType = WatchlistMediaType.MOVIE,
    val status: WatchlistStatus = WatchlistStatus.WATCHED,
    val driveId: String? = null,
    val externalId: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: String = "",
    val genre: String = "",
    val addedAt: Long = System.currentTimeMillis(),
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
    private val attemptedPosterKeys = ConcurrentHashMap.newKeySet<String>()

    private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

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

    /**
     * Returns true when [id] is a canonical external catalog identifier (TMDB, Cinemeta, or IMDb)
     * rather than a Google Drive file/folder ID.
     */
    fun isExternalCanonicalId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val lower = id.trim().lowercase()
        return lower.startsWith("tmdb_movie_") ||
            lower.startsWith("tmdb_tv_") ||
            lower.startsWith("cinemeta_") ||
            lower.startsWith("imdb_") ||
            (lower.startsWith("tt") && lower.length >= 7 && lower.drop(2).all { it.isDigit() })
    }

    private fun getFile(email: String): File {
        val dir = DesktopProfileManager.getProfileDir(email)
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
                val parsed = parseEntriesFromJson(json)
                val cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                loaded.addAll(parsed.filter { !it.isDeleted || it.updatedAt > cutoff })
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
            val payload = serializePayloadJson(entries)
            getFile(email).writeText(payload, Charsets.UTF_8)
            notifyMutation()
        } catch (e: Exception) {
            System.err.println("[DesktopWatchlistManager] Failed to save watchlist.json: ${e.message}")
        }
    }

    fun getLocalPayloadJson(email: String): String {
        val entries = loadLocalSync(email)
        val snapshot = synchronized(entries) { entries.toList() }
        return serializePayloadJson(snapshot)
    }

    fun getVisibleEntries(email: String): List<WatchlistEntry> {
        if (email.isBlank()) return emptyList()
        val list = loadLocalSync(email)
        return synchronized(list) {
            list.filter { !it.isDeleted }
                .sortedByDescending { it.updatedAt }
        }
    }

    /**
     * Resolves status for a Drive item or title query.
     * Matches by driveId/externalId/id first, falling back to normalizedTitle so deleted-and-readded
     * Drive files or cross-device external entries retain their status.
     */
    fun getStatusForMedia(email: String, title: String, driveId: String? = null): WatchlistStatus? {
        if (email.isBlank()) return null
        val list = loadLocalSync(email)
        val norm = normalizeTitle(title)
        val catalogNorm = DesktopDriveCatalogMatcher.normalizeTitle(title)

        val match = synchronized(list) {
            list.firstOrNull {
                !it.isDeleted && !driveId.isNullOrBlank() &&
                    (it.driveId == driveId || it.externalId == driveId || it.id == driveId)
            } ?: list.firstOrNull {
                !it.isDeleted && (
                    (norm.isNotBlank() && it.normalizedTitle == norm) ||
                        (catalogNorm.isNotBlank() && DesktopDriveCatalogMatcher.normalizeTitle(it.title) == catalogNorm)
                    )
            }
        }
        return match?.status
    }

    fun isWatched(email: String, title: String, driveId: String? = null): Boolean {
        return getStatusForMedia(email, title, driveId) == WatchlistStatus.WATCHED
    }

    fun hasActiveEntryForMedia(email: String, title: String, driveId: String? = null): Boolean {
        return getStatusForMedia(email, title, driveId) != null
    }

    /**
     * Returns true if the user has explicitly deleted/removed this title from their Watchlist
     * (an active `isDeleted == true` tombstone exists with no newer active entry).
     */
    fun isExplicitlyRemovedFromWatchlist(email: String, title: String, driveId: String? = null): Boolean {
        if (email.isBlank()) return false
        val list = loadLocalSync(email)
        val norm = normalizeTitle(title)
        val catalogNorm = DesktopDriveCatalogMatcher.normalizeTitle(title)

        val latestMatch = synchronized(list) {
            list.filter { entry ->
                (!driveId.isNullOrBlank() &&
                    (entry.driveId == driveId || entry.externalId == driveId || entry.id == driveId)) ||
                (norm.isNotBlank() && entry.normalizedTitle == norm) ||
                (catalogNorm.isNotBlank() && DesktopDriveCatalogMatcher.normalizeTitle(entry.title) == catalogNorm)
            }.maxByOrNull { it.updatedAt }
        }
        return latestMatch?.isDeleted == true
    }

    /**
     * Marks the active Watchlist entry matching [driveId] or normalized [title] as a deleted
     * tombstone (`isDeleted = true` with fresh `updatedAt`) so it stays removed across Google Drive syncs.
     */
    suspend fun removeMediaFromWatchlist(
        email: String,
        title: String,
        driveId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (email.isBlank() || (title.isBlank() && driveId.isNullOrBlank())) return@withContext false
        val list = loadLocalSync(email)
        val norm = normalizeTitle(title)
        val catalogNorm = DesktopDriveCatalogMatcher.normalizeTitle(title)
        val now = System.currentTimeMillis()
        var modified = false
        var removedEntry: WatchlistEntry? = null

        synchronized(list) {
            val idx = list.indexOfFirst { entry ->
                !entry.isDeleted && (
                    (!driveId.isNullOrBlank() &&
                        (entry.driveId == driveId || entry.externalId == driveId || entry.id == driveId)) ||
                    (norm.isNotBlank() && entry.normalizedTitle == norm) ||
                    (catalogNorm.isNotBlank() && DesktopDriveCatalogMatcher.normalizeTitle(entry.title) == catalogNorm)
                )
            }
            if (idx >= 0) {
                removedEntry = list[idx]
                list[idx] = list[idx].copy(
                    isDeleted = true,
                    updatedAt = now
                )
                modified = true
            }
        }

        if (modified) {
            saveLocal(email, list)
            if (removedEntry?.mediaType == WatchlistMediaType.MOVIE) {
                DesktopWatchHistoryManager.clearMediaWatchedFlag(email, driveId ?: removedEntry?.driveId ?: removedEntry?.id ?: "")
            }
        }
        modified
    }

    // -------------------------------------------------------------------------
    // CROSS-DEVICE GLOBAL SEARCH WATCHLIST HELPERS
    // -------------------------------------------------------------------------

    /**
     * Finds the active (non-deleted) [WatchlistEntry] matching [result] using canonical cross-device keying:
     * 1. Canonical ID match (`driveId`, `externalId`, or `id`) checking both `result.id` and `result.externalId`.
     * 2. Fallback match by `normalizedTitle` + `mediaType` so an external title marked Watch Later / Rewatch
     *    retains its status automatically after being uploaded to Google Drive.
     */
    fun findEntryForSearchResult(email: String, result: GlobalSearchResult): WatchlistEntry? {
        if (email.isBlank()) return null
        val list = loadLocalSync(email)
        return synchronized(list) {
            findEntryIndexLocked(list, result, includeDeleted = false)?.let { list[it] }
        }
    }

    /**
     * Resolves the Watchlist status for any [GlobalSearchResult] (In-Drive or Not-in-Drive).
     */
    fun getStatusForSearchResult(email: String, result: GlobalSearchResult): WatchlistStatus? {
        return findEntryForSearchResult(email, result)?.status
    }

    /**
     * Immediately persists a Watchlist status mutation for a [GlobalSearchResult] locally (< 5 ms),
     * increments [watchlistRevision], and dispatches a non-cancellable cloud sync to
     * `clotube/History/clotube_watchlist.json`.
     *
     * - When `result.isInDrive == true`: stores the Google Drive `fileId` (movie file ID or series folder ID)
     *   in `driveId` (and attaches `result.externalId` if enriched).
     * - When `result.isInDrive == false`: stores the canonical external ID (`tmdb_movie_<id>`, `tmdb_tv_<id>`,
     *   `cinemeta_*`, or `imdb_*`) in both `driveId` and `externalId` alongside `title`, `normalizedTitle`,
     *   `mediaType`, `posterUrl`, `backdropUrl`, `year`, and `genre`.
     */
    fun setSearchResultWatchlistStatus(
        email: String,
        accessToken: String,
        result: GlobalSearchResult,
        status: WatchlistStatus
    ) {
        if (email.isBlank()) return

        val effectiveTitle = resolveSearchResultTitle(result)
        if (effectiveTitle.isBlank()) return

        val mediaType = resolveSearchResultMediaType(result)
        val canonicalKey = resolveCanonicalDriveIdKey(result)
        val externalKey = resolveCanonicalExternalKey(result)
        val normTitle = normalizeTitle(effectiveTitle)
        val now = System.currentTimeMillis()
        val list = loadLocalSync(email)

        synchronized(list) {
            val existingIdx = findEntryIndexLocked(list, result, includeDeleted = true)
            if (existingIdx != null) {
                val existing = list[existingIdx]
                list[existingIdx] = existing.copy(
                    title = effectiveTitle,
                    normalizedTitle = normTitle,
                    mediaType = mediaType,
                    status = status,
                    driveId = canonicalKey ?: existing.driveId,
                    externalId = externalKey ?: existing.externalId,
                    posterUrl = result.posterUrl.takeIf { it.isNotBlank() } ?: existing.posterUrl,
                    backdropUrl = result.backdropUrl.takeIf { it.isNotBlank() } ?: existing.backdropUrl,
                    year = result.year.ifBlank { existing.year },
                    genre = result.genre.ifBlank { existing.genre },
                    updatedAt = now,
                    isDeleted = false
                )
            } else {
                val entryId = if (!canonicalKey.isNullOrBlank()) canonicalKey else UUID.randomUUID().toString()
                list.add(
                    WatchlistEntry(
                        id = entryId,
                        title = effectiveTitle,
                        normalizedTitle = normTitle,
                        mediaType = mediaType,
                        status = status,
                        driveId = canonicalKey,
                        externalId = externalKey,
                        posterUrl = result.posterUrl.takeIf { it.isNotBlank() },
                        backdropUrl = result.backdropUrl.takeIf { it.isNotBlank() },
                        year = result.year,
                        genre = result.genre,
                        addedAt = now,
                        updatedAt = now,
                        isDeleted = false
                    )
                )
            }
        }

        saveLocal(email, list)
        DesktopWatchlistSync.pushLocalWatchlistNonCancellable(email, accessToken)
    }

    /**
     * Marks the Watchlist entry matching [result] as a deleted tombstone (`isDeleted = true` with fresh
     * `updatedAt`) locally (< 5 ms) and launches non-cancellable cloud sync so Google Drive propagates
     * the deletion across Desktop and Android instead of resurrecting it on the next pull.
     */
    fun removeSearchResultFromWatchlist(
        email: String,
        accessToken: String,
        result: GlobalSearchResult
    ) {
        if (email.isBlank()) return
        val list = loadLocalSync(email)
        val now = System.currentTimeMillis()
        var modified = false

        synchronized(list) {
            val idx = findEntryIndexLocked(list, result, includeDeleted = false)
            if (idx != null) {
                list[idx] = list[idx].copy(
                    isDeleted = true,
                    updatedAt = now
                )
                modified = true
            }
        }

        if (modified) {
            saveLocal(email, list)
            DesktopWatchlistSync.pushLocalWatchlistNonCancellable(email, accessToken)
            if (result.mediaType == SearchMediaType.MOVIE) {
                val targetId = result.driveMediaItem?.id?.takeIf { it.isNotBlank() } ?: result.id
                DesktopWatchHistoryManager.clearMediaWatchedFlag(email, targetId)
                if (accessToken.isNotBlank()) {
                    CoroutineScope(Dispatchers.IO + NonCancellable).launch {
                        DesktopWatchHistorySync.pushLocalHistory(email, accessToken, force = true)
                    }
                }
            }
        }
    }

    private fun resolveSearchResultTitle(result: GlobalSearchResult): String {
        return if (result.mediaType == SearchMediaType.EPISODE) {
            result.parentSeriesItem?.title?.takeIf { it.isNotBlank() }
                ?: result.originalTitle.ifBlank { result.title }
        } else {
            result.title
        }.trim()
    }

    private fun resolveSearchResultMediaType(result: GlobalSearchResult): WatchlistMediaType {
        return when (result.mediaType) {
            SearchMediaType.MOVIE -> WatchlistMediaType.MOVIE
            SearchMediaType.TV_SERIES, SearchMediaType.EPISODE -> WatchlistMediaType.SERIES
        }
    }

    private fun resolveCanonicalDriveIdKey(result: GlobalSearchResult): String? {
        return if (result.isInDrive) {
            when (result.mediaType) {
                SearchMediaType.EPISODE -> result.parentSeriesItem?.id?.takeIf { it.isNotBlank() } ?: result.id.takeIf { it.isNotBlank() }
                else -> result.driveMediaItem?.id?.takeIf { it.isNotBlank() } ?: result.id.takeIf { it.isNotBlank() }
            }
        } else {
            resolveCanonicalExternalKey(result)
        }
    }

    private fun resolveCanonicalExternalKey(result: GlobalSearchResult): String? {
        val rawExternal = result.externalId.trim()
        if (rawExternal.isNotBlank()) {
            if (!result.isInDrive) return normalizeFallbackExternalId(rawExternal, result.externalSource)
            if (rawExternal != result.id) return normalizeFallbackExternalId(rawExternal, result.externalSource)
        }
        val rawImdb = result.imdbId.trim()
        if (rawImdb.startsWith("tt")) {
            return rawImdb
        }
        return if (!result.isInDrive && result.id.isNotBlank()) {
            normalizeFallbackExternalId(result.id.trim(), result.externalSource)
        } else {
            null
        }
    }

    private fun normalizeFallbackExternalId(rawId: String, source: String): String {
        val lower = rawId.lowercase()
        if (lower.startsWith("tmdb_movie_") ||
            lower.startsWith("tmdb_tv_") ||
            lower.startsWith("cinemeta_") ||
            lower.startsWith("imdb_") ||
            (lower.startsWith("tt") && lower.length >= 7 && lower.drop(2).all { it.isDigit() })
        ) {
            return rawId
        }
        return when (source.uppercase()) {
            "CINEMETA" -> "cinemeta_$rawId"
            "IMDB" -> "imdb_$rawId"
            else -> rawId
        }
    }

    private fun findEntryIndexLocked(
        list: List<WatchlistEntry>,
        result: GlobalSearchResult,
        includeDeleted: Boolean
    ): Int? {
        val candidateKeys = LinkedHashSet<String>()
        if (result.mediaType == SearchMediaType.EPISODE) {
            result.parentSeriesItem?.id?.takeIf { it.isNotBlank() }?.let { candidateKeys.add(it) }
        } else if (result.id.isNotBlank()) {
            candidateKeys.add(result.id.trim())
        }
        if (result.externalId.isNotBlank()) {
            val ext = result.externalId.trim()
            candidateKeys.add(ext)
            candidateKeys.add(normalizeFallbackExternalId(ext, result.externalSource))
            if (ext.startsWith("tt")) {
                candidateKeys.add("imdb_$ext")
                candidateKeys.add("cinemeta_$ext")
            }
        }
        if (result.imdbId.isNotBlank()) {
            val imdb = result.imdbId.trim()
            candidateKeys.add(imdb)
            candidateKeys.add("imdb_$imdb")
        }
        resolveCanonicalDriveIdKey(result)?.let { candidateKeys.add(it) }
        resolveCanonicalExternalKey(result)?.let { candidateKeys.add(it) }

        // 1. Canonical ID match first (driveId / externalId / id)
        if (candidateKeys.isNotEmpty()) {
            val idIdx = list.indexOfFirst { entry ->
                (includeDeleted || !entry.isDeleted) && (
                    (!entry.driveId.isNullOrBlank() && entry.driveId in candidateKeys) ||
                        (!entry.externalId.isNullOrBlank() && entry.externalId in candidateKeys) ||
                        (entry.id.isNotBlank() && entry.id in candidateKeys)
                    )
            }
            if (idIdx >= 0) return idIdx
        }

        // 2. Fallback: normalizedTitle + mediaType match
        val targetTitle = resolveSearchResultTitle(result)
        val targetMediaType = resolveSearchResultMediaType(result)
        val targetNorm = normalizeTitle(targetTitle)
        val targetCatalogNorm = DesktopDriveCatalogMatcher.normalizeTitle(targetTitle)
        val origCatalogNorm = if (result.originalTitle.isNotBlank()) {
            DesktopDriveCatalogMatcher.normalizeTitle(result.originalTitle)
        } else {
            ""
        }

        val titleIdx = list.indexOfFirst { entry ->
            if (!includeDeleted && entry.isDeleted) return@indexOfFirst false
            if (entry.mediaType != targetMediaType) return@indexOfFirst false
            val entryCatalogNorm = DesktopDriveCatalogMatcher.normalizeTitle(entry.title)
            (targetNorm.isNotBlank() && entry.normalizedTitle == targetNorm) ||
                (targetCatalogNorm.isNotBlank() && entryCatalogNorm == targetCatalogNorm) ||
                (origCatalogNorm.isNotBlank() && entryCatalogNorm == origCatalogNorm)
        }
        return if (titleIdx >= 0) titleIdx else null
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
                (!driveId.isNullOrBlank() && (it.driveId == driveId || it.externalId == driveId || it.id == driveId)) ||
                    (norm.isNotBlank() && it.normalizedTitle == norm && it.mediaType == mediaType) ||
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
                        id = if (!driveId.isNullOrBlank()) driveId else UUID.randomUUID().toString(),
                        title = cleanTitle,
                        normalizedTitle = norm,
                        mediaType = mediaType,
                        status = status,
                        driveId = driveId,
                        externalId = if (isExternalCanonicalId(driveId)) driveId else null,
                        posterUrl = posterUrl?.takeIf { it.isNotBlank() },
                        addedAt = now,
                        updatedAt = now,
                        isDeleted = false
                    )
                )
            }
        }
        saveLocal(email, list)
    }

    /**
     * Updates an existing watchlist item by its permanent UUID/ID.
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
        var removedEntry: WatchlistEntry? = null

        synchronized(list) {
            val idx = list.indexOfFirst { it.id == entryId }
            if (idx >= 0) {
                removedEntry = list[idx]
                list[idx] = list[idx].copy(
                    isDeleted = true,
                    updatedAt = now
                )
                modified = true
            }
        }
        if (modified) {
            saveLocal(email, list)
            if (removedEntry?.mediaType == WatchlistMediaType.MOVIE) {
                DesktopWatchHistoryManager.clearMediaWatchedFlag(email, removedEntry?.driveId ?: removedEntry?.id ?: "")
            }
        }
    }

    /**
     * Called automatically when playback reaches >= 90% completion.
     * Promotes Watch Later / Rewatch / unmarked titles to WATCHED and links [driveId].
     * Returns true if the Watchlist was mutated and should be pushed to Google Drive.
     */
    suspend fun autoMarkWatchedIfEligible(
        email: String,
        title: String,
        mediaType: WatchlistMediaType,
        driveId: String?
    ): Boolean = withContext(Dispatchers.IO) {
        if (email.isBlank() || title.isBlank()) return@withContext false
        val list = loadLocalSync(email)
        val norm = normalizeTitle(title)
        val catalogNorm = DesktopDriveCatalogMatcher.normalizeTitle(title)

        val existing = synchronized(list) {
            list.firstOrNull { entry ->
                !entry.isDeleted && (
                    (!driveId.isNullOrBlank() &&
                        (entry.driveId == driveId || entry.externalId == driveId || entry.id == driveId)) ||
                    (norm.isNotBlank() && entry.normalizedTitle == norm) ||
                    (catalogNorm.isNotBlank() && DesktopDriveCatalogMatcher.normalizeTitle(entry.title) == catalogNorm)
                )
            }
        }

        if (existing?.status == WatchlistStatus.WATCHED) {
            return@withContext false
        }

        val canonicalTitle = existing?.title?.takeIf { it.isNotBlank() } ?: title
        upsertEntry(
            email = email,
            title = canonicalTitle,
            mediaType = mediaType,
            status = WatchlistStatus.WATCHED,
            driveId = driveId ?: existing?.driveId,
            posterUrl = existing?.posterUrl
        )
        true
    }

    /**
     * Merges remote Google Drive `clotube_watchlist.json` entries (from either Desktop or Android)
     * using Last-Write-Wins per canonical ID / normalizedTitle + mediaType, preserving deletion tombstones.
     */
    fun mergeRemotePayload(email: String, remoteJson: String): Boolean {
        if (email.isBlank() || remoteJson.isBlank()) return false
        val local = loadLocalSync(email)
        var changed = false

        try {
            val remoteEntries = parseEntriesFromJson(remoteJson)
            if (remoteEntries.isEmpty()) return false

            synchronized(local) {
                for (remoteEntry in remoteEntries) {
                    val idx = local.indexOfFirst { localEntry ->
                        localEntry.id == remoteEntry.id ||
                            (!remoteEntry.driveId.isNullOrBlank() &&
                                (localEntry.driveId == remoteEntry.driveId || localEntry.id == remoteEntry.driveId)) ||
                            (!remoteEntry.externalId.isNullOrBlank() &&
                                (localEntry.externalId == remoteEntry.externalId || localEntry.driveId == remoteEntry.externalId)) ||
                            (remoteEntry.normalizedTitle.isNotBlank() &&
                                localEntry.normalizedTitle == remoteEntry.normalizedTitle &&
                                localEntry.mediaType == remoteEntry.mediaType)
                    }
                    if (idx < 0) {
                        local.add(remoteEntry)
                        changed = true
                    } else {
                        val localEntry = local[idx]
                        if (remoteEntry.updatedAt > localEntry.updatedAt) {
                            val mergedDriveId = resolvePreferredDriveId(remoteEntry.driveId, localEntry.driveId)
                            val mergedExternalId = remoteEntry.externalId?.takeIf { it.isNotBlank() } ?: localEntry.externalId
                            val mergedPoster = remoteEntry.posterUrl?.takeIf { it.isNotBlank() } ?: localEntry.posterUrl
                            val mergedBackdrop = remoteEntry.backdropUrl?.takeIf { it.isNotBlank() } ?: localEntry.backdropUrl
                            local[idx] = remoteEntry.copy(
                                id = localEntry.id,
                                driveId = mergedDriveId,
                                externalId = mergedExternalId,
                                posterUrl = mergedPoster,
                                backdropUrl = mergedBackdrop,
                                year = remoteEntry.year.ifBlank { localEntry.year },
                                genre = remoteEntry.genre.ifBlank { localEntry.genre },
                                addedAt = minOf(localEntry.addedAt, remoteEntry.addedAt)
                            )
                            changed = true
                        } else {
                            // Backfill missing metadata even when local write is newer
                            val patched = localEntry.copy(
                                driveId = resolvePreferredDriveId(localEntry.driveId, remoteEntry.driveId),
                                externalId = localEntry.externalId?.takeIf { it.isNotBlank() } ?: remoteEntry.externalId,
                                posterUrl = localEntry.posterUrl?.takeIf { it.isNotBlank() } ?: remoteEntry.posterUrl,
                                backdropUrl = localEntry.backdropUrl?.takeIf { it.isNotBlank() } ?: remoteEntry.backdropUrl,
                                year = localEntry.year.ifBlank { remoteEntry.year },
                                genre = localEntry.genre.ifBlank { remoteEntry.genre }
                            )
                            if (patched != localEntry) {
                                local[idx] = patched
                                changed = true
                            }
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

    private fun resolvePreferredDriveId(primary: String?, secondary: String?): String? {
        val p = primary?.takeIf { it.isNotBlank() }
        val s = secondary?.takeIf { it.isNotBlank() }
        return when {
            p != null && !isExternalCanonicalId(p) -> p
            s != null && !isExternalCanonicalId(s) -> s
            else -> p ?: s
        }
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
        driveMovies: List<DesktopMediaItem> = emptyList(),
        driveSeries: List<DesktopMediaItem> = emptyList()
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

    // -------------------------------------------------------------------------
    // CROSS-DEVICE JSON SERIALIZATION / DESERIALIZATION (DESKTOP <-> ANDROID)
    // -------------------------------------------------------------------------

    private fun serializePayloadJson(entries: List<WatchlistEntry>): String {
        val now = System.currentTimeMillis()
        val root = JsonObject().apply {
            addProperty("version", 1)
            addProperty("updatedAt", now)
        }

        val entriesArray = JsonArray()
        entries.sortedWith(compareBy<WatchlistEntry> { it.normalizedTitle }.thenBy { it.id })
            .forEach { entry ->
                val obj = JsonObject().apply {
                    addProperty("id", entry.id)
                    addProperty("title", entry.title)
                    addProperty("normalizedTitle", entry.normalizedTitle)
                    addProperty("mediaType", entry.mediaType.name)
                    addProperty("status", entry.status.name)
                    if (!entry.driveId.isNullOrBlank()) addProperty("driveId", entry.driveId)
                    if (!entry.externalId.isNullOrBlank()) addProperty("externalId", entry.externalId)
                    if (!entry.posterUrl.isNullOrBlank()) addProperty("posterUrl", entry.posterUrl)
                    if (!entry.backdropUrl.isNullOrBlank()) addProperty("backdropUrl", entry.backdropUrl)
                    if (entry.year.isNotBlank()) addProperty("year", entry.year)
                    if (entry.genre.isNotBlank()) addProperty("genre", entry.genre)
                    addProperty("addedAt", entry.addedAt)
                    addProperty("updatedAt", entry.updatedAt)
                    addProperty("isDeleted", entry.isDeleted)
                }
                entriesArray.add(obj)
            }

        // Emit both `entries` (Desktop legacy) and `items` (Android WatchlistPayload) for seamless two-way sync
        root.add("entries", entriesArray)
        root.add("items", entriesArray)
        return gson.toJson(root)
    }

    private fun parseEntriesFromJson(rawJson: String): List<WatchlistEntry> {
        if (rawJson.isBlank()) return emptyList()
        val parsed = try {
            JsonParser.parseString(rawJson.trim())
        } catch (_: Exception) {
            return emptyList()
        }

        val array: JsonArray? = when {
            parsed.isJsonArray -> parsed.asJsonArray
            parsed.isJsonObject -> {
                val root = parsed.asJsonObject
                root.get("items")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: root.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray
            }
            else -> null
        }

        if (array == null) return emptyList()

        val result = ArrayList<WatchlistEntry>(array.size())
        for (i in 0 until array.size()) {
            val elem = array.get(i)
            if (elem == null || !elem.isJsonObject) continue
            parseEntryObject(elem.asJsonObject)?.let { result.add(it) }
        }
        return result
    }

    private fun parseEntryObject(obj: JsonObject): WatchlistEntry? {
        val rawId = safeString(obj, "id").trim()
        val rawTitle = safeString(obj, "title").trim()
        val rawNorm = safeString(obj, "normalizedTitle").ifBlank { safeString(obj, "normalized_title") }.trim()

        val title = rawTitle.ifBlank { rawNorm }
        if (title.isBlank() && rawId.isBlank()) return null

        val effectiveId = rawId.ifBlank { UUID.randomUUID().toString() }
        val effectiveTitle = title.ifBlank { effectiveId }
        val effectiveNorm = rawNorm.ifBlank { normalizeTitle(effectiveTitle) }

        val rawMediaType = safeString(obj, "mediaType").ifBlank { safeString(obj, "type") }
        val mediaType = WatchlistMediaType.fromRaw(rawMediaType)

        val rawStatus = safeString(obj, "status")
        val status = WatchlistStatus.fromRaw(rawStatus.ifBlank { "WATCHED" })

        val rawDriveId = safeString(obj, "driveId").ifBlank { safeString(obj, "drive_id") }.trim().ifBlank { null }
        val rawExternalId = safeString(obj, "externalId").ifBlank { safeString(obj, "external_id") }.trim().ifBlank { null }

        // Interop with Android entries where canonical Drive/TMDB ID is stored directly in `id`
        val inferredDriveId = rawDriveId ?: if (!UUID_REGEX.matches(effectiveId)) effectiveId else null
        val inferredExternalId = rawExternalId ?: when {
            isExternalCanonicalId(inferredDriveId) -> inferredDriveId
            isExternalCanonicalId(effectiveId) -> effectiveId
            else -> null
        }

        val posterUrl = safeString(obj, "posterUrl").ifBlank { safeString(obj, "poster_url") }.trim().ifBlank { null }
        val backdropUrl = safeString(obj, "backdropUrl").ifBlank { safeString(obj, "backdrop_url") }.trim().ifBlank { null }
        val year = safeString(obj, "year").trim()
        val genre = safeString(obj, "genre").trim()

        val now = System.currentTimeMillis()
        val updatedAt = normalizeTimestampMs(
            safeLong(obj, "updatedAt") ?: safeLong(obj, "updated_at") ?: now
        )
        val addedAt = normalizeTimestampMs(
            safeLong(obj, "addedAt") ?: safeLong(obj, "added_at") ?: updatedAt
        )
        val isDeleted = safeBoolean(obj, "isDeleted") ?: safeBoolean(obj, "is_deleted") ?: false

        return WatchlistEntry(
            id = effectiveId,
            title = effectiveTitle,
            normalizedTitle = effectiveNorm,
            mediaType = mediaType,
            status = status,
            driveId = inferredDriveId,
            externalId = inferredExternalId,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            year = year,
            genre = genre,
            addedAt = addedAt,
            updatedAt = updatedAt,
            isDeleted = isDeleted
        )
    }

    private fun normalizeTimestampMs(value: Long): Long {
        return if (value in 1_000_000_000L..9_999_999_999L) value * 1000L else value
    }

    private fun safeString(obj: JsonObject, key: String): String {
        val elem: JsonElement = obj.get(key) ?: return ""
        return if (!elem.isJsonNull && elem.isJsonPrimitive) {
            try { elem.asString ?: "" } catch (_: Exception) { "" }
        } else ""
    }

    private fun safeLong(obj: JsonObject, key: String): Long? {
        val elem: JsonElement = obj.get(key) ?: return null
        if (elem.isJsonNull || !elem.isJsonPrimitive) return null
        return try { elem.asLong } catch (_: Exception) { null }
    }

    private fun safeBoolean(obj: JsonObject, key: String): Boolean? {
        val elem: JsonElement = obj.get(key) ?: return null
        if (elem.isJsonNull || !elem.isJsonPrimitive) return null
        return try { elem.asBoolean } catch (_: Exception) { null }
    }
}
