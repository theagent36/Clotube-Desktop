package com.clotube.desktop.data

import androidx.compose.runtime.mutableIntStateOf
import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class DesktopContinueWatchingItem(
    val mediaId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String,
    val fallbackThumbnailUrl: String = "",
    val positionMs: Long,
    val durationMs: Long,
    val progressFraction: Float,
    val isSeries: Boolean,
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

typealias DesktopWatchHistoryItem = DesktopContinueWatchingItem

data class SeriesResumeState(
    val seriesId: String,
    val lastEpisodeId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedTimestamp: Long
)

data class DesktopTypedEntry(
    val type: String,
    val value: Any
)

data class DesktopArchivedHistoryEntry(
    val id: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val timestamp: Long,
    val isWatched: Boolean,
    val seriesId: String? = null,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0
)

/**
 * Pure volatile RAM-only watch history state holder for Clotube Desktop.
 * Zero local disk persistence: never reads from or writes watch history to %APPDATA%.
 */
object DesktopWatchHistoryManager {
    private const val MAX_ACTIVE_WINDOW = 50

    private val volatileEntries = ConcurrentHashMap<String, DesktopTypedEntry>()
    private val archivedWatchedIds = ConcurrentHashMap.newKeySet<String>()
    private val archivedItems = ConcurrentHashMap<String, DesktopArchivedHistoryEntry>()

    @Volatile
    private var activeProfileSanitized: String = ""

    // Observable Compose state that notifies Home, Movies, Series, and Recents screens immediately
    val historyRevision = mutableIntStateOf(0)

    fun notifyMutation() {
        historyRevision.intValue++
    }

    fun bindProfile(email: String) {
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        if (sanitized != activeProfileSanitized) {
            activeProfileSanitized = sanitized
            clearInMemory()
        }
    }

    /**
     * One-time cleanup of all legacy local watch history JSON files under
     * %APPDATA%\Clotube\profiles\ so stale cached history can never resurrect
     * or conflict with Google Drive. Preserves local_recent_searches.json,
     * watchlist.json, library.json, series/, thumbnails/, and OAuth tokens.
     */
    fun purgeAllLocalDiskCaches() {
        try {
            val appData = System.getenv("APPDATA")
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it, "Clotube") }
                ?: File(System.getProperty("user.home"), ".clotube")

            val legacyHistoryTargets = setOf(
                "watch_history.json",
                "watch_history_archive.json",
                "clotube_watch_history.json",
                "clotube_active_sync.json",
                "clotube_archive.json",
                "clotube_history.json"
            )

            if (appData.exists() && appData.isDirectory) {
                appData.walkTopDown().forEach { file ->
                    if (file.isFile && file.name in legacyHistoryTargets) {
                        file.delete()
                    }
                }
            }
            println("[ClotubeSync] Purged all legacy local watch history disk caches")
        } catch (e: Exception) {
            println("[ClotubeSync] Warning purging legacy local history files: ${e.message}")
        }
    }

    fun snapshotEntries(): Map<String, DesktopTypedEntry> = HashMap(volatileEntries)

    fun snapshotArchivedIds(): Set<String> = HashSet(archivedWatchedIds)

    fun snapshotArchivedItems(): Map<String, DesktopArchivedHistoryEntry> = HashMap(archivedItems)

    fun replaceAllFromDrive(
        activeEntries: Map<String, DesktopTypedEntry>,
        archiveIds: Set<String>,
        archiveMetadata: Map<String, DesktopArchivedHistoryEntry> = emptyMap()
    ) {
        volatileEntries.clear()
        volatileEntries.putAll(activeEntries)

        archivedWatchedIds.clear()
        archivedWatchedIds.addAll(archiveIds)

        archivedItems.clear()
        archivedItems.putAll(archiveMetadata)

        notifyMutation()
    }

    fun updateEntries(mutations: MutableMap<String, DesktopTypedEntry?>.() -> Unit) {
        val working = HashMap(volatileEntries)
        working.mutations()
        pruneOverflowToVolatileArchive(working)
        volatileEntries.clear()
        working.forEach { (k, v) ->
            if (v != null) volatileEntries[k] = v
        }
        notifyMutation()
    }

    private fun pruneOverflowToVolatileArchive(working: MutableMap<String, DesktopTypedEntry?>) {
        val itemIds = working.keys
            .filter { it.endsWith("_timestamp") && !it.startsWith("series_resume_") && !it.startsWith("global_") }
            .map { it.removeSuffix("_timestamp") }
            .distinct()

        if (itemIds.size <= MAX_ACTIVE_WINDOW) return

        val sortedByTs = itemIds.map { id ->
            val ts = (working["${id}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            id to ts
        }.sortedByDescending { it.second }

        val overflowIds = sortedByTs.drop(MAX_ACTIVE_WINDOW).map { it.first }
        for (id in overflowIds) {
            val title = (working["${id}_title"]?.value as? String).orEmpty()
            val pos = (working["${id}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = (working["${id}_dur"]?.value as? Number)?.toLong() ?: 0L
            val frac = (working["${id}_fraction"]?.value as? Number)?.toFloat() ?: 0f
            val ts = (working["${id}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            val explicitWatched = working["${id}_is_watched"]?.value as? Boolean
            val isWatched = explicitWatched ?: (frac >= 0.90f)
            val seriesId = (working["${id}_series_id"]?.value as? String)?.ifBlank { null }
            val sNum = (working["${id}_season_num"]?.value as? Number)?.toInt() ?: 0
            val eNum = (working["${id}_episode_num"]?.value as? Number)?.toInt() ?: 0

            if (isWatched) {
                archivedWatchedIds.add(id)
            }
            archivedItems[id] = DesktopArchivedHistoryEntry(
                id = id,
                title = title,
                positionMs = pos,
                durationMs = dur,
                timestamp = ts,
                isWatched = isWatched,
                seriesId = seriesId,
                seasonNumber = sNum,
                episodeNumber = eNum
            )

            val keysToEvict = working.keys.filter { it.startsWith("${id}_") }
            keysToEvict.forEach { working.remove(it) }
        }
    }

    fun clearInMemory(email: String = "") {
        volatileEntries.clear()
        archivedWatchedIds.clear()
        archivedItems.clear()
        notifyMutation()
    }

    fun recordPlaybackProgress(
        email: String,
        fileId: String,
        title: String,
        positionMs: Long,
        durationMs: Long,
        isSeries: Boolean,
        seriesId: String = "",
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        allowZeroOverwrite: Boolean = false,
        accessToken: String = ""
    ) {
        if (fileId.isBlank()) return

        // Guard: Never overwrite an active resume position (>5s, <90%) with an un-seeked <3s position
        // unless explicitly requested by a user scrubber gesture or episode completion.
        if (!allowZeroOverwrite && positionMs < 3000L) {
            val existingPos = (volatileEntries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
            val existingFrac = (volatileEntries["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f
            if (existingPos > 5000L && existingFrac < 0.90f) {
                return
            }
        }

        val now = System.currentTimeMillis()
        val effectiveDur = if (durationMs > 0L) durationMs else 45L * 60000L
        val fraction = (positionMs.toFloat() / effectiveDur.toFloat()).coerceIn(0f, 1f)
        val isWatched = fraction >= 0.90f
        val wasArchived = archivedWatchedIds.remove(fileId) || (archivedItems.remove(fileId) != null)

        updateEntries {
            this["${fileId}_title"] = DesktopTypedEntry("string", title)
            this["${fileId}_pos"] = DesktopTypedEntry("long", positionMs)
            this["${fileId}_dur"] = DesktopTypedEntry("long", effectiveDur)
            this["${fileId}_fraction"] = DesktopTypedEntry("float", fraction)
            this["${fileId}_timestamp"] = DesktopTypedEntry("long", now)
            this["${fileId}_is_watched"] = DesktopTypedEntry("boolean", isWatched)
            this["${fileId}_is_series"] = DesktopTypedEntry("boolean", isSeries)

            if (isSeries && seriesId.isNotBlank()) {
                val sNum = seasonNumber.coerceAtLeast(1)
                val eNum = episodeNumber.coerceAtLeast(1)

                this["${fileId}_series_id"] = DesktopTypedEntry("string", seriesId)
                this["${fileId}_season_num"] = DesktopTypedEntry("int", sNum)
                this["${fileId}_episode_num"] = DesktopTypedEntry("int", eNum)

                val existingResumeEp = this["series_resume_${seriesId}_episode_id"]?.value as? String
                val isMeaningfulProgress = positionMs >= 2000L
                val isSameEp = existingResumeEp == fileId
                val hasNoExistingPointer = existingResumeEp.isNullOrBlank()

                if (isMeaningfulProgress || isSameEp || (hasNoExistingPointer && positionMs > 0L) || allowZeroOverwrite) {
                    this["series_resume_${seriesId}_episode_id"] = DesktopTypedEntry("string", fileId)
                    this["series_resume_${seriesId}_season_num"] = DesktopTypedEntry("int", sNum)
                    this["series_resume_${seriesId}_episode_num"] = DesktopTypedEntry("int", eNum)
                    this["series_resume_${seriesId}_pos_ms"] = DesktopTypedEntry("long", positionMs)
                    this["series_resume_${seriesId}_dur_ms"] = DesktopTypedEntry("long", effectiveDur)
                    this["series_resume_${seriesId}_timestamp"] = DesktopTypedEntry("long", now)
                }
            }
        }

        if (isWatched && email.isNotBlank() && title.isNotBlank()) {
            runCatching {
                DesktopWatchHistorySync.syncScope.launch {
                    DesktopWatchlistManager.autoMarkWatchedIfEligible(
                        email = email,
                        title = title,
                        mediaType = if (isSeries) WatchlistMediaType.SERIES else WatchlistMediaType.MOVIE,
                        driveId = if (isSeries && seriesId.isNotBlank()) seriesId else fileId
                    )
                }
            }
        }

        DesktopWatchHistorySync.pushHistoryAsync(
            force = isWatched,
            updateArchive = wasArchived
        )
    }

    fun getPlaybackPosition(email: String, fileId: String): Long = getPlaybackPosition(fileId)

    fun getPlaybackPosition(fileId: String): Long {
        if (fileId.isBlank()) return 0L
        val pos = (volatileEntries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
        val dur = (volatileEntries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
        val fraction = (volatileEntries["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f

        if (fraction >= 0.90f || (dur > 0L && pos >= (dur * 0.90f))) return 0L
        return if (pos > 1500L) pos else 0L
    }

    fun getPlaybackDuration(email: String, fileId: String): Long = getPlaybackDuration(fileId)

    fun getPlaybackDuration(fileId: String): Long {
        if (fileId.isBlank()) return 0L
        val dur = (volatileEntries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
        if (dur > 0L) return dur
        return archivedItems[fileId]?.durationMs?.coerceAtLeast(0L) ?: 0L
    }

    fun formatRemainingTime(remainingMs: Long): String {
        if (remainingMs <= 0L) return ""
        val totalSec = remainingMs / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L
        return when {
            hours > 0L -> "${hours}h ${minutes}m left"
            minutes > 0L -> "${minutes}m left"
            else -> "< 1m left"
        }
    }

    fun hasSavedWatchProgress(email: String, fileId: String): Boolean {
        if (fileId.isBlank()) return false
        val pos = (volatileEntries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
        val dur = (volatileEntries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
        val fraction = (volatileEntries["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f
        if (fraction >= 0.90f || (dur > 0L && pos >= (dur * 0.90f))) return false
        return pos > 1500L
    }

    fun isEpisodeWatched(email: String, fileId: String): Boolean = isEpisodeWatched(fileId)

    fun isEpisodeWatched(fileId: String): Boolean {
        if (fileId.isBlank()) return false
        val explicit = volatileEntries["${fileId}_is_watched"]?.value as? Boolean
        if (explicit != null) return explicit
        if (archivedWatchedIds.contains(fileId)) return true
        val fraction = (volatileEntries["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f
        return fraction >= 0.90f
    }

    fun isMediaWatched(email: String, mediaId: String): Boolean = isEpisodeWatched(mediaId)

    fun getWatchedEpisodeIds(email: String = ""): Set<String> {
        val watched = HashSet(archivedWatchedIds)
        for ((key, entry) in volatileEntries) {
            if (key.endsWith("_is_watched")) {
                val id = key.removeSuffix("_is_watched")
                if ((entry.value as? Boolean) == true) {
                    watched.add(id)
                } else {
                    watched.remove(id)
                }
            } else if (key.endsWith("_fraction")) {
                val id = key.removeSuffix("_fraction")
                val explicit = volatileEntries["${id}_is_watched"]?.value as? Boolean
                val frac = (entry.value as? Number)?.toFloat() ?: 0f
                if (explicit == null && frac >= 0.90f) {
                    watched.add(id)
                }
            }
        }
        return watched
    }

    fun setEpisodeWatched(
        email: String,
        fileId: String,
        isWatched: Boolean,
        seriesId: String = "",
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        title: String = "",
        durationMs: Long = 0L
    ) {
        setEpisodeWatched(
            fileId = fileId,
            isWatched = isWatched,
            title = title,
            durationMs = durationMs,
            isSeries = seriesId.isNotBlank(),
            seriesId = seriesId.ifBlank { null },
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber
        )
    }

    fun setEpisodeWatched(
        fileId: String,
        isWatched: Boolean,
        title: String = "",
        durationMs: Long = 0L,
        isSeries: Boolean = false,
        seriesId: String? = null,
        seasonNumber: Int = 0,
        episodeNumber: Int = 0
    ) {
        if (fileId.isBlank()) return
        val now = System.currentTimeMillis()
        val wasArchived = archivedWatchedIds.remove(fileId) || (archivedItems.remove(fileId) != null)

        updateEntries {
            this["${fileId}_is_watched"] = DesktopTypedEntry("boolean", isWatched)
            this["${fileId}_timestamp"] = DesktopTypedEntry("long", now)
            if (title.isNotBlank()) {
                this["${fileId}_title"] = DesktopTypedEntry("string", title)
            }
            if (isWatched) {
                if (durationMs > 0L) {
                    this["${fileId}_pos"] = DesktopTypedEntry("long", durationMs)
                    this["${fileId}_dur"] = DesktopTypedEntry("long", durationMs)
                }
                this["${fileId}_fraction"] = DesktopTypedEntry("float", 1.0f)
            } else {
                this["${fileId}_pos"] = DesktopTypedEntry("long", 0L)
                this["${fileId}_fraction"] = DesktopTypedEntry("float", 0.0f)
            }
            if (isSeries && !seriesId.isNullOrBlank()) {
                this["${fileId}_is_series"] = DesktopTypedEntry("boolean", true)
                this["${fileId}_series_id"] = DesktopTypedEntry("string", seriesId)
                if (seasonNumber > 0) this["${fileId}_season_num"] = DesktopTypedEntry("int", seasonNumber)
                if (episodeNumber > 0) this["${fileId}_episode_num"] = DesktopTypedEntry("int", episodeNumber)
            }
        }

        DesktopWatchHistorySync.pushHistoryAsync(
            force = true,
            updateArchive = wasArchived
        )
    }

    fun setSeasonEpisodesWatched(
        email: String,
        episodes: List<DesktopEpisode>,
        isWatched: Boolean,
        seriesId: String
    ) {
        if (episodes.isEmpty()) return
        val now = System.currentTimeMillis()
        var touchedArchive = false

        updateEntries {
            for (ep in episodes) {
                if (archivedWatchedIds.remove(ep.id)) touchedArchive = true
                if (archivedItems.remove(ep.id) != null) touchedArchive = true

                this["${ep.id}_is_watched"] = DesktopTypedEntry("boolean", isWatched)
                this["${ep.id}_timestamp"] = DesktopTypedEntry("long", now)
                if (ep.title.isNotBlank()) {
                    this["${ep.id}_title"] = DesktopTypedEntry("string", ep.title)
                }
                if (isWatched) {
                    if (ep.durationMs > 0L) {
                        this["${ep.id}_pos"] = DesktopTypedEntry("long", ep.durationMs)
                        this["${ep.id}_dur"] = DesktopTypedEntry("long", ep.durationMs)
                    }
                    this["${ep.id}_fraction"] = DesktopTypedEntry("float", 1.0f)
                } else {
                    this["${ep.id}_pos"] = DesktopTypedEntry("long", 0L)
                    this["${ep.id}_fraction"] = DesktopTypedEntry("float", 0.0f)
                }
                if (seriesId.isNotBlank()) {
                    this["${ep.id}_is_series"] = DesktopTypedEntry("boolean", true)
                    this["${ep.id}_series_id"] = DesktopTypedEntry("string", seriesId)
                    if (ep.seasonNumber > 0) this["${ep.id}_season_num"] = DesktopTypedEntry("int", ep.seasonNumber)
                    if (ep.episodeNumber > 0) this["${ep.id}_episode_num"] = DesktopTypedEntry("int", ep.episodeNumber)
                }
            }
        }

        DesktopWatchHistorySync.pushHistoryAsync(
            force = true,
            updateArchive = touchedArchive
        )
    }

    fun getSeriesResumeState(email: String, seriesId: String): SeriesResumeState? {
        if (seriesId.isBlank()) return null
        var epId = volatileEntries["series_resume_${seriesId}_episode_id"]?.value as? String
        var pos = (volatileEntries["series_resume_${seriesId}_pos_ms"]?.value as? Number)?.toLong() ?: 0L

        if (epId.isNullOrBlank() || pos < 2000L) {
            val candidateEps = volatileEntries.keys
                .filter { it.endsWith("_series_id") && volatileEntries[it]?.value == seriesId }
                .map { it.removeSuffix("_series_id") }

            var bestEpId: String? = null
            var bestTs = -1L
            var bestPos = 0L

            for (cId in candidateEps) {
                val cPos = (volatileEntries["${cId}_pos"]?.value as? Number)?.toLong() ?: 0L
                val cTs = (volatileEntries["${cId}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                if (cPos >= 2000L && cTs > bestTs) {
                    bestTs = cTs
                    bestEpId = cId
                    bestPos = cPos
                }
            }

            if (bestEpId != null) {
                epId = bestEpId
                pos = bestPos
                val sNum = (volatileEntries["${bestEpId}_season_num"]?.value as? Number)?.toInt() ?: 1
                val eNum = (volatileEntries["${bestEpId}_episode_num"]?.value as? Number)?.toInt() ?: 1
                val dur = (volatileEntries["${bestEpId}_dur"]?.value as? Number)?.toLong() ?: 0L

                updateEntries {
                    this["series_resume_${seriesId}_episode_id"] = DesktopTypedEntry("string", bestEpId)
                    this["series_resume_${seriesId}_season_num"] = DesktopTypedEntry("int", sNum)
                    this["series_resume_${seriesId}_episode_num"] = DesktopTypedEntry("int", eNum)
                    this["series_resume_${seriesId}_pos_ms"] = DesktopTypedEntry("long", bestPos)
                    this["series_resume_${seriesId}_dur_ms"] = DesktopTypedEntry("long", dur)
                    this["series_resume_${seriesId}_timestamp"] = DesktopTypedEntry("long", bestTs)
                }
            }
        }

        if (epId.isNullOrBlank()) return null
        val sNum = (volatileEntries["series_resume_${seriesId}_season_num"]?.value as? Number)?.toInt() ?: 1
        val eNum = (volatileEntries["series_resume_${seriesId}_episode_num"]?.value as? Number)?.toInt() ?: 1
        val dur = (volatileEntries["series_resume_${seriesId}_dur_ms"]?.value as? Number)?.toLong() ?: 0L
        val ts = (volatileEntries["series_resume_${seriesId}_timestamp"]?.value as? Number)?.toLong() ?: 0L

        return SeriesResumeState(
            seriesId = seriesId,
            lastEpisodeId = epId,
            seasonNumber = sNum,
            episodeNumber = eNum,
            positionMs = pos,
            durationMs = dur,
            lastWatchedTimestamp = ts
        )
    }

    fun getContinueWatching(
        email: String,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ): List<DesktopContinueWatchingItem> {
        val items = mutableListOf<DesktopContinueWatchingItem>()
        val movieIds = movies.map { it.id }.toSet()

        // 1. In-Progress Movies from volatile RAM (zero fallback to unwatched catalog items)
        for (movie in movies) {
            val pos = (volatileEntries["${movie.id}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = (volatileEntries["${movie.id}_dur"]?.value as? Number)?.toLong() ?: movie.durationMs
            val fraction = (volatileEntries["${movie.id}_fraction"]?.value as? Number)?.toFloat()
                ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()) else 0f
            val ts = (volatileEntries["${movie.id}_timestamp"]?.value as? Number)?.toLong() ?: 0L

            if (pos >= 3000L && fraction in 0.01f..0.92f && ts > 0L) {
                items.add(
                    DesktopContinueWatchingItem(
                        mediaId = movie.id,
                        title = movie.title,
                        subtitle = movie.genre.ifBlank { "Movie" },
                        thumbnailUrl = movie.thumbnailUrl.orEmpty(),
                        fallbackThumbnailUrl = movie.fallbackThumbnailUrl.orEmpty(),
                        positionMs = pos,
                        durationMs = dur,
                        progressFraction = fraction.coerceIn(0.02f, 1f),
                        isSeries = false,
                        timestamp = ts
                    )
                )
            }
        }

        // 2. In-Progress / Up-Next TV Series from validated series_resume pointers
        for (s in series) {
            val resume = getSeriesResumeState(email, s.id) ?: continue
            val epId = resume.lastEpisodeId
            if (epId.isBlank()) continue

            val isMarkedMovie = volatileEntries["${epId}_is_series"]?.value == false
            if (movieIds.contains(epId) || isMarkedMovie) continue

            val pos = resume.positionMs
            val dur = resume.durationMs
            val ts = resume.lastWatchedTimestamp

            val cachedSeries = DesktopSeriesCache.get(email, s.id)
            val allEpisodes = cachedSeries?.seasons?.flatMap { it.episodes } ?: emptyList()
            val targetEp = allEpisodes.find { it.id == epId }

            val sNum = resume.seasonNumber
            val eNum = resume.episodeNumber
            val epTitle = targetEp?.title ?: volatileEntries["${epId}_title"]?.value as? String ?: "Episode $eNum"
            val epThumb = targetEp?.thumbnailUrl ?: s.thumbnailUrl
            val effectiveDur = when {
                dur > 0L -> dur
                (targetEp?.durationMs ?: 0L) > 0L -> targetEp!!.durationMs
                else -> 45L * 60000L
            }
            val fraction = (pos.toFloat() / effectiveDur.toFloat()).coerceIn(0f, 1f)

            val code = "S${sNum}E${eNum}"
            val subtitle = if (epTitle.isNotBlank()) "$code • $epTitle" else code

            val isOngoing = fraction in 0.005f..0.95f || pos >= 3000L
            val isQueuedNext = (pos == 0L && fraction == 0f)
            if (ts > 0L && (isOngoing || isQueuedNext)) {
                items.add(
                    DesktopContinueWatchingItem(
                        mediaId = epId,
                        title = s.title,
                        subtitle = subtitle,
                        thumbnailUrl = (epThumb?.ifBlank { s.thumbnailUrl } ?: s.thumbnailUrl).orEmpty(),
                        fallbackThumbnailUrl = s.fallbackThumbnailUrl.orEmpty(),
                        positionMs = pos,
                        durationMs = effectiveDur,
                        progressFraction = if (pos > 0L) fraction.coerceIn(0.04f, 1f) else 0.02f,
                        isSeries = true,
                        seriesId = s.id,
                        seasonNumber = sNum,
                        episodeNumber = eNum,
                        episodeTitle = epTitle,
                        timestamp = ts
                    )
                )
            }
        }

        return items.sortedByDescending { it.timestamp }.take(10)
    }

    fun getLastWatchedMovie(
        email: String,
        movies: List<DesktopMediaItem>
    ): DesktopContinueWatchingItem? {
        return getContinueWatching(email, movies, emptyList()).firstOrNull()
    }

    fun getLastWatchedSeries(
        email: String,
        series: List<DesktopMediaItem>
    ): DesktopContinueWatchingItem? {
        return getContinueWatching(email, emptyList(), series).firstOrNull()
    }

    fun getAllHistory(email: String): List<DesktopContinueWatchingItem> {
        return getAllWatchHistory(email, emptyList(), emptyList())
    }

    fun getAllWatchHistory(
        email: String,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ): List<DesktopContinueWatchingItem> {
        val itemsById = LinkedHashMap<String, DesktopContinueWatchingItem>()

        // 1. Active RAM items from clotube_active_sync.json
        val fileIds = volatileEntries.keys
            .filter { it.endsWith("_timestamp") && !it.startsWith("series_resume_") && !it.startsWith("global_") }
            .map { it.removeSuffix("_timestamp") }
            .distinct()

        for (fileId in fileIds) {
            val title = volatileEntries["${fileId}_title"]?.value as? String ?: ""
            val pos = (volatileEntries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = (volatileEntries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
            val rawFraction = (volatileEntries["${fileId}_fraction"]?.value as? Number)?.toFloat()
                ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()) else 0f
            val ts = (volatileEntries["${fileId}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            val isWatched = (volatileEntries["${fileId}_is_watched"]?.value as? Boolean) == true
            val isSeries = (volatileEntries["${fileId}_is_series"]?.value as? Boolean) == true
            val seriesId = volatileEntries["${fileId}_series_id"]?.value as? String
            val sNum = (volatileEntries["${fileId}_season_num"]?.value as? Number)?.toInt()
            val eNum = (volatileEntries["${fileId}_episode_num"]?.value as? Number)?.toInt()

            if (ts <= 0L) continue
            if (!isWatched && pos < 2000L && rawFraction < 0.01f) continue

            itemsById[fileId] = buildHistoryCardItem(
                email = email,
                fileId = fileId,
                rawTitle = title,
                pos = pos,
                dur = dur,
                rawFraction = if (isWatched) 1.0f else rawFraction,
                ts = ts,
                isSeries = isSeries,
                seriesId = seriesId,
                sNum = sNum,
                eNum = eNum,
                movies = movies,
                series = series
            )
        }

        // 2. Archived RAM items from clotube_archive.json (beyond the 50-item active window)
        for ((fileId, arch) in archivedItems) {
            if (itemsById.containsKey(fileId)) continue
            val isSeries = !arch.seriesId.isNullOrBlank() || arch.seasonNumber > 0 || arch.episodeNumber > 0
            val rawFraction = if (arch.isWatched) 1.0f
            else if (arch.durationMs > 0L) (arch.positionMs.toFloat() / arch.durationMs.toFloat())
            else 1.0f

            itemsById[fileId] = buildHistoryCardItem(
                email = email,
                fileId = fileId,
                rawTitle = arch.title,
                pos = arch.positionMs,
                dur = arch.durationMs,
                rawFraction = rawFraction,
                ts = arch.timestamp,
                isSeries = isSeries,
                seriesId = arch.seriesId,
                sNum = arch.seasonNumber.takeIf { it > 0 },
                eNum = arch.episodeNumber.takeIf { it > 0 },
                movies = movies,
                series = series
            )
        }

        return itemsById.values.sortedByDescending { it.timestamp }
    }

    private fun buildHistoryCardItem(
        email: String,
        fileId: String,
        rawTitle: String,
        pos: Long,
        dur: Long,
        rawFraction: Float,
        ts: Long,
        isSeries: Boolean,
        seriesId: String?,
        sNum: Int?,
        eNum: Int?,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ): DesktopContinueWatchingItem {
        val fraction = rawFraction.coerceIn(0f, 1f)
        return if (isSeries) {
            val matchedSeries = series.find { it.id == seriesId }
            val seriesTitle = matchedSeries?.title ?: rawTitle.ifBlank { "Series" }
            val cachedSeries = if (email.isNotBlank() && !seriesId.isNullOrBlank()) {
                DesktopSeriesCache.get(email, seriesId)
            } else {
                null
            }
            val targetEp = cachedSeries?.seasons?.flatMap { it.episodes }?.find { it.id == fileId }

            val epTitle = targetEp?.title ?: rawTitle.ifBlank { "Episode ${eNum ?: 1}" }
            val epThumb = targetEp?.thumbnailUrl ?: matchedSeries?.thumbnailUrl.orEmpty()
            val effectiveDur = if (dur > 0L) dur else targetEp?.durationMs ?: 0L

            val code = if (sNum != null && eNum != null) "S${sNum}E${eNum}" else ""
            val subtitle = when {
                code.isNotBlank() && epTitle.isNotBlank() -> "$code • $epTitle"
                code.isNotBlank() -> code
                else -> epTitle
            }

            DesktopContinueWatchingItem(
                mediaId = fileId,
                title = seriesTitle,
                subtitle = subtitle,
                thumbnailUrl = epThumb,
                fallbackThumbnailUrl = matchedSeries?.fallbackThumbnailUrl.orEmpty(),
                positionMs = pos,
                durationMs = effectiveDur,
                progressFraction = fraction,
                isSeries = true,
                seriesId = seriesId,
                seasonNumber = sNum,
                episodeNumber = eNum,
                episodeTitle = epTitle,
                timestamp = ts
            )
        } else {
            val matchedMovie = movies.find { it.id == fileId }
            val movieTitle = matchedMovie?.title ?: rawTitle.ifBlank { "Movie" }
            val movieThumb = matchedMovie?.thumbnailUrl.orEmpty()
            val effectiveDur = if (dur > 0L) dur else matchedMovie?.durationMs ?: 0L
            val subtitle = matchedMovie?.genre?.ifBlank { "Movie" } ?: "Movie"

            DesktopContinueWatchingItem(
                mediaId = fileId,
                title = movieTitle,
                subtitle = subtitle,
                thumbnailUrl = movieThumb,
                fallbackThumbnailUrl = matchedMovie?.fallbackThumbnailUrl.orEmpty(),
                positionMs = pos,
                durationMs = effectiveDur,
                progressFraction = fraction,
                isSeries = false,
                timestamp = ts
            )
        }
    }

    fun removeEntriesForMedia(mediaId: String, seriesId: String?): Boolean {
        val removedFromArchive = archivedWatchedIds.remove(mediaId) || (archivedItems.remove(mediaId) != null)
        updateEntries {
            val toDelete = keys.filter { key ->
                key.startsWith("${mediaId}_") ||
                    (!seriesId.isNullOrBlank() && key.startsWith("series_resume_${seriesId}_"))
            }
            toDelete.forEach { remove(it) }
        }
        return removedFromArchive
    }

    fun removeHistoryItem(email: String, mediaId: String) {
        if (mediaId.isBlank()) return
        val seriesId = volatileEntries["${mediaId}_series_id"]?.value as? String
            ?: archivedItems[mediaId]?.seriesId
        val archiveChanged = removeEntriesForMedia(mediaId, seriesId)
        DesktopWatchHistorySync.pushHistoryAsync(force = true, updateArchive = archiveChanged)
    }

    fun clearMediaWatchedFlag(email: String, mediaId: String) {
        if (mediaId.isBlank()) return
        val archiveChanged = archivedWatchedIds.remove(mediaId) || (archivedItems.remove(mediaId) != null)
        updateEntries {
            this["${mediaId}_is_watched"] = DesktopTypedEntry("boolean", false)
            this["${mediaId}_fraction"] = DesktopTypedEntry("float", 0.0f)
            this["${mediaId}_pos"] = DesktopTypedEntry("long", 0L)
            this["${mediaId}_timestamp"] = DesktopTypedEntry("long", System.currentTimeMillis())
        }
        DesktopWatchHistorySync.pushHistoryAsync(force = true, updateArchive = archiveChanged)
    }

    suspend fun clearAllHistory(email: String = "") {
        clearInMemory(email)
        DesktopWatchHistorySync.deleteRemoteHistoryFiles()
    }

    fun clearLocalHistory(email: String = "") {
        clearInMemory(email)
        purgeAllLocalDiskCaches()
        DesktopWatchHistorySync.pushHistoryAsync(force = true, updateArchive = true)
    }
}
