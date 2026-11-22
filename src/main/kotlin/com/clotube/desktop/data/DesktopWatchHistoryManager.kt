package com.clotube.desktop.data

import androidx.compose.runtime.mutableIntStateOf
import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

data class SeriesResumeState(
    val seriesId: String,
    val lastEpisodeId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedTimestamp: Long
)

object DesktopWatchHistoryManager {
    private const val MAX_ACTIVE_WINDOW = 50
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val loadLock = Any()
    private val historyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val activeItemsCache = ConcurrentHashMap<String, ConcurrentHashMap<String, HistoryItemEntityV2>>()
    private val archiveItemsCache = ConcurrentHashMap<String, ConcurrentHashMap<String, HistoryItemEntityV2>>()
    private val seriesResumeCache = ConcurrentHashMap<String, ConcurrentHashMap<String, SeriesResumeEntityV2>>()
    private val watchedIdsCache = ConcurrentHashMap<String, MutableSet<String>>()
    private val archiveDirty = ConcurrentHashMap<String, Boolean>()
    private val archiveCleared = ConcurrentHashMap<String, Boolean>()

    val historyRevision = mutableIntStateOf(0)

    fun notifyMutation() {
        historyRevision.intValue++
    }

    private fun getFile(email: String): File = DesktopProfileManager.getWatchHistoryFile(email)
    private fun getArchiveFile(email: String): File = DesktopProfileManager.getWatchHistoryArchiveFile(email)

    private fun ensureProfileLoaded(email: String) {
        if (email.isBlank()) return
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        if (activeItemsCache.containsKey(sanitized)) return

        synchronized(loadLock) {
            if (activeItemsCache.containsKey(sanitized)) return

            val file = getFile(email)
            val archiveFile = getArchiveFile(email)
            val itemsMap = ConcurrentHashMap<String, HistoryItemEntityV2>()
            val archiveMap = ConcurrentHashMap<String, HistoryItemEntityV2>()
            val resumeMap = ConcurrentHashMap<String, SeriesResumeEntityV2>()
            val watchedSet = ConcurrentHashMap.newKeySet<String>()

            if (file.exists() && file.length() > 0) {
                try {
                    val json = file.readText(Charsets.UTF_8)
                    val rootObj = JsonParser.parseString(json).asJsonObject

                    if (rootObj.has("v") && rootObj.get("v")?.takeIf { !it.isJsonNull }?.asInt == 2) {
                        val payload = gson.fromJson(json, WatchHistoryPayloadV2::class.java)
                        payload?.active.orEmpty().forEach { itemsMap[it.id] = it }
                        payload?.resume.orEmpty().forEach { (k, v) -> resumeMap[k] = v }
                        watchedSet.addAll(payload?.watchedIds.orEmpty())
                    } else if (rootObj.has("entries") && rootObj.get("entries").isJsonObject) {
                        migrateLegacyV1Object(rootObj.getAsJsonObject("entries"), itemsMap, resumeMap, watchedSet)
                    }
                } catch (e: Exception) {
                    System.err.println("[DesktopWatchHistoryManager] Error reading local history: ${e.message}")
                }
            }

            if (archiveFile.exists() && archiveFile.length() > 0) {
                try {
                    val archiveJson = archiveFile.readText(Charsets.UTF_8)
                    val payload = gson.fromJson(archiveJson, WatchHistoryPayloadV2::class.java)
                    payload?.active.orEmpty().forEach { archiveMap[it.id] = it }
                } catch (e: Exception) {
                    System.err.println("[DesktopWatchHistoryManager] Error reading local history archive: ${e.message}")
                }
            }

            activeItemsCache[sanitized] = itemsMap
            archiveItemsCache[sanitized] = archiveMap
            seriesResumeCache[sanitized] = resumeMap
            watchedIdsCache[sanitized] = watchedSet
            pruneActiveWindow(sanitized)
        }
    }

    private fun JsonObject.v1EntryValue(key: String): JsonElement? =
        this.get(key)?.takeIf { it.isJsonObject }?.asJsonObject?.get("value")?.takeIf { !it.isJsonNull }

    private fun migrateLegacyV1Object(
        entriesObj: JsonObject,
        itemsOut: ConcurrentHashMap<String, HistoryItemEntityV2>,
        resumeOut: ConcurrentHashMap<String, SeriesResumeEntityV2>,
        watchedOut: MutableSet<String>
    ) {
        val uniqueIds = entriesObj.keySet()
            .filter { it.endsWith("_timestamp") && !it.startsWith("series_resume_") }
            .map { it.removeSuffix("_timestamp") }

        for (id in uniqueIds) {
            val title = entriesObj.v1EntryValue("${id}_title")?.asString ?: ""
            val pos = entriesObj.v1EntryValue("${id}_pos")?.asLong ?: 0L
            val dur = entriesObj.v1EntryValue("${id}_dur")?.asLong ?: 0L
            val ts = entriesObj.v1EntryValue("${id}_timestamp")?.asLong ?: 0L
            val isSeries = entriesObj.v1EntryValue("${id}_is_series")?.asBoolean ?: false
            val seriesId = entriesObj.v1EntryValue("${id}_series_id")?.asString
            val seasonNum = entriesObj.v1EntryValue("${id}_season_num")?.asInt
            val epNum = entriesObj.v1EntryValue("${id}_episode_num")?.asInt
            val isWatched = entriesObj.v1EntryValue("${id}_is_watched")?.asBoolean
                ?: if (dur > 0L) (pos.toFloat() / dur.toFloat() >= 0.90f) else false

            if (isWatched) watchedOut.add(id)

            itemsOut[id] = HistoryItemEntityV2(
                id = id,
                title = title,
                positionMs = pos,
                durationMs = dur,
                timestamp = ts,
                isWatched = isWatched,
                seriesId = if (isSeries && !seriesId.isNullOrBlank()) seriesId else null,
                seasonNumber = if (isSeries) seasonNum else null,
                episodeNumber = if (isSeries) epNum else null
            )
        }

        val resumeSeriesIds = entriesObj.keySet()
            .filter { it.startsWith("series_resume_") && it.endsWith("_episode_id") }
            .map { it.removePrefix("series_resume_").removeSuffix("_episode_id") }

        for (sid in resumeSeriesIds) {
            val epId = entriesObj.v1EntryValue("series_resume_${sid}_episode_id")?.asString ?: continue
            val s = entriesObj.v1EntryValue("series_resume_${sid}_season_num")?.asInt ?: 1
            val e = entriesObj.v1EntryValue("series_resume_${sid}_episode_num")?.asInt ?: 1
            val p = entriesObj.v1EntryValue("series_resume_${sid}_pos_ms")?.asLong ?: 0L
            val d = entriesObj.v1EntryValue("series_resume_${sid}_dur_ms")?.asLong ?: 0L
            val ts = entriesObj.v1EntryValue("series_resume_${sid}_timestamp")?.asLong ?: 0L

            resumeOut[sid] = SeriesResumeEntityV2(epId, s, e, p, d, ts)
        }
    }

    private fun pruneActiveWindow(sanitized: String): List<HistoryItemEntityV2> {
        val itemsMap = activeItemsCache[sanitized] ?: return emptyList()
        if (itemsMap.size <= MAX_ACTIVE_WINDOW) {
            return itemsMap.values.sortedByDescending { it.timestamp }
        }

        val sorted = itemsMap.values.sortedByDescending { it.timestamp }
        val activeWindow = sorted.take(MAX_ACTIVE_WINDOW)
        val overflow = sorted.drop(MAX_ACTIVE_WINDOW)

        val keepIds = activeWindow.map { it.id }.toSet()
        itemsMap.keys.filter { !keepIds.contains(it) }.forEach { itemsMap.remove(it) }

        if (overflow.isNotEmpty()) {
            val archiveMap = archiveItemsCache.computeIfAbsent(sanitized) { ConcurrentHashMap() }
            val watchedSet = watchedIdsCache[sanitized] ?: emptySet()
            var archiveChanged = false
            for (item in overflow) {
                val effectiveWatched = item.isWatched || watchedSet.contains(item.id)
                val itemToArchive = if (effectiveWatched != item.isWatched) item.copy(isWatched = true) else item
                val prev = archiveMap[itemToArchive.id]
                if (prev == null || itemToArchive.timestamp >= prev.timestamp) {
                    archiveMap[itemToArchive.id] = itemToArchive
                    archiveChanged = true
                }
            }
            if (archiveChanged) {
                archiveDirty[sanitized] = true
                saveArchiveLocalBySanitized(sanitized)
            }
        }
        return activeWindow
    }

    private fun buildPayload(sanitized: String): WatchHistoryPayloadV2 {
        val activeWindow = pruneActiveWindow(sanitized)
        val resumeMap = seriesResumeCache[sanitized] ?: ConcurrentHashMap()
        val watchedSet = watchedIdsCache[sanitized] ?: emptySet()

        return WatchHistoryPayloadV2(
            version = 2,
            updatedAt = System.currentTimeMillis(),
            active = activeWindow,
            resume = resumeMap.toMap(),
            watchedIds = watchedSet.toList()
        )
    }

    fun saveLocal(email: String) {
        if (email.isBlank()) return
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        if (!activeItemsCache.containsKey(sanitized)) return

        val payload = buildPayload(sanitized)

        try {
            val file = getFile(email)
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(payload), Charsets.UTF_8)
            notifyMutation()
        } catch (e: Exception) {
            System.err.println("[DesktopWatchHistoryManager] Error saving local v2 history: ${e.message}")
        }
    }

    fun saveArchiveLocal(email: String) {
        if (email.isBlank()) return
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        saveArchiveLocalBySanitized(sanitized)
    }

    private fun saveArchiveLocalBySanitized(sanitized: String) {
        val archiveMap = archiveItemsCache[sanitized] ?: return
        val payload = WatchHistoryPayloadV2(
            version = 2,
            updatedAt = System.currentTimeMillis(),
            active = archiveMap.values.sortedByDescending { it.timestamp },
            resume = emptyMap(),
            watchedIds = emptyList()
        )
        try {
            val file = File(DesktopProfileManager.profilesRoot, "$sanitized/watch_history_archive.json")
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(payload), Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("[DesktopWatchHistoryManager] Error saving local history archive: ${e.message}")
        }
    }

    fun getLocalPayloadJson(email: String): String {
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val payload = buildPayload(sanitized)
        val json = gson.toJson(payload)
        val byteSize = json.toByteArray(Charsets.UTF_8).size
        println("[DesktopWatchHistoryManager] Compact v2 payload: ${payload.active.size} active, ${payload.watchedIds.size} watched IDs -> $byteSize bytes (${byteSize / 1024} KB)")
        return json
    }

    fun getArchivePayloadJson(email: String): String {
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val archiveMap = archiveItemsCache[sanitized] ?: ConcurrentHashMap()
        val sortedList = archiveMap.values.sortedByDescending { it.timestamp }
        val payload = WatchHistoryPayloadV2(
            version = 2,
            updatedAt = System.currentTimeMillis(),
            active = sortedList,
            resume = emptyMap(),
            watchedIds = emptyList()
        )
        return gson.toJson(payload)
    }

    fun hasPendingArchiveUpload(email: String): Boolean {
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        return archiveDirty[sanitized] == true
    }

    fun isArchiveDirty(email: String): Boolean = hasPendingArchiveUpload(email)

    fun clearArchiveDirty(email: String) {
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        archiveDirty[sanitized] = false
    }

    fun isArchiveCleared(email: String): Boolean {
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        return archiveCleared[sanitized] == true
    }

    fun clearArchiveCleared(email: String) {
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        archiveCleared[sanitized] = false
    }

    fun hasArchiveItems(email: String): Boolean {
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        return archiveItemsCache[sanitized]?.isNotEmpty() == true
    }

    fun mergeRemoteArchivePayload(email: String, remoteArchiveJson: String): Boolean {
        if (email.isBlank() || remoteArchiveJson.isBlank()) return false
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val archiveMap = archiveItemsCache.computeIfAbsent(sanitized) { ConcurrentHashMap() }
        val activeMap = activeItemsCache[sanitized] ?: ConcurrentHashMap()
        val watchedSet = watchedIdsCache[sanitized] ?: ConcurrentHashMap.newKeySet()
        var changed = false

        try {
            val payload = gson.fromJson(remoteArchiveJson, WatchHistoryPayloadV2::class.java) ?: return false
            for (remoteItem in payload.active.orEmpty()) {
                if (remoteItem.id.isBlank()) continue

                // Skip IDs that already exist with a newer timestamp in activeItemsCache
                val activeItem = activeMap[remoteItem.id]
                if (activeItem != null && activeItem.timestamp >= remoteItem.timestamp) {
                    continue
                }

                val local = archiveMap[remoteItem.id]
                if (local == null || remoteItem.timestamp > local.timestamp) {
                    val isItemWatched = remoteItem.isWatched || (local?.isWatched == true)
                    archiveMap[remoteItem.id] = if (isItemWatched != remoteItem.isWatched) remoteItem.copy(isWatched = true) else remoteItem
                    changed = true

                    // If remoteItem.isWatched is true (and the item is not explicitly marked unwatched in activeItemsCache),
                    // add remoteItem.id to watchedIdsCache
                    val explicitlyUnwatchedInActive = activeItem != null && !activeItem.isWatched && activeItem.timestamp >= remoteItem.timestamp
                    if (isItemWatched && !explicitlyUnwatchedInActive) {
                        watchedSet.add(remoteItem.id)
                    }
                }
            }

            if (changed) {
                saveArchiveLocalBySanitized(sanitized)
                saveLocal(email)
                notifyMutation()
            }
        } catch (e: Exception) {
            System.err.println("[DesktopWatchHistoryManager] Error merging remote archive: ${e.message}")
        }
        return changed
    }

    fun consumeOverflowArchiveJson(email: String): String? {
        if (!hasPendingArchiveUpload(email)) return null
        clearArchiveDirty(email)
        return getArchivePayloadJson(email)
    }

    fun mergeRemotePayload(email: String, remoteJson: String): Boolean {
        if (email.isBlank() || remoteJson.isBlank()) return false
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val itemsMap = activeItemsCache[sanitized] ?: return false
        val resumeMap = seriesResumeCache[sanitized] ?: return false
        val watchedSet = watchedIdsCache[sanitized] ?: return false

        var changed = false
        try {
            val root = JsonParser.parseString(remoteJson).asJsonObject
            if (root.has("v") && root.get("v")?.takeIf { !it.isJsonNull }?.asInt == 2) {
                val remotePayload = gson.fromJson(remoteJson, WatchHistoryPayloadV2::class.java)

                // 1. Build the authoritative remote watched set
                val remoteWatchedSet = remotePayload.watchedIds.toSet()

                // 2. For each active item from remote, reconcile both itemsMap AND watchedSet
                for (remoteItem in remotePayload.active.orEmpty()) {
                    val local = itemsMap[remoteItem.id]
                    if (local == null || remoteItem.timestamp > local.timestamp) {
                        itemsMap[remoteItem.id] = remoteItem
                        changed = true
                        // Honour the item's explicit isWatched flag from the authoritative remote
                        if (remoteItem.isWatched || remoteItem.id in remoteWatchedSet) {
                            if (watchedSet.add(remoteItem.id)) changed = true
                        } else {
                            // Remote explicitly says not watched and it is NOT in watchedIds: remove from local set
                            if (watchedSet.remove(remoteItem.id)) changed = true
                        }
                    }
                }

                // 3. For IDs in remoteWatchedSet that have no active item entry, ensure they are in watchedSet
                val coveredByActive = remotePayload.active.orEmpty().map { it.id }.toSet()
                for (id in remoteWatchedSet) {
                    if (id.isBlank()) continue
                    if (id !in coveredByActive) {
                        if (watchedSet.add(id)) changed = true
                    }
                }

                for ((sid, remoteResume) in remotePayload?.resume.orEmpty()) {
                    val local = resumeMap[sid]
                    if (local == null || remoteResume.timestamp > local.timestamp) {
                        resumeMap[sid] = remoteResume
                        changed = true
                    }
                }
            } else if (root.has("entries") && root.get("entries").isJsonObject) {
                val tempItems = ConcurrentHashMap<String, HistoryItemEntityV2>()
                val tempResume = ConcurrentHashMap<String, SeriesResumeEntityV2>()
                val tempWatched = ConcurrentHashMap.newKeySet<String>()
                migrateLegacyV1Object(root.getAsJsonObject("entries"), tempItems, tempResume, tempWatched)

                for ((id, item) in tempItems) {
                    val local = itemsMap[id]
                    if (local == null || item.timestamp > local.timestamp) {
                        itemsMap[id] = item
                        changed = true
                    }
                }
                for ((sid, res) in tempResume) {
                    val local = resumeMap[sid]
                    if (local == null || res.timestamp > local.timestamp) {
                        resumeMap[sid] = res
                        changed = true
                    }
                }
                if (watchedSet.addAll(tempWatched)) changed = true
            }

            if (changed) {
                saveLocal(email)
                println("[DesktopWatchHistoryManager] Successfully merged v2 payload from Drive.")
            }
        } catch (e: Exception) {
            System.err.println("[DesktopWatchHistoryManager] Error merging v2 payload: ${e.message}")
        }
        return changed
    }

    /**
     * Purges local orphaned test keys and aligns local storage strictly with Google Drive.
     */
    fun resetLocalToRemote(email: String, remoteJson: String) {
        if (email.isBlank() || remoteJson.isBlank()) return
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val freshItems = ConcurrentHashMap<String, HistoryItemEntityV2>()
        val freshResume = ConcurrentHashMap<String, SeriesResumeEntityV2>()
        val freshWatched = ConcurrentHashMap.newKeySet<String>()

        try {
            val root = JsonParser.parseString(remoteJson).asJsonObject
            if (root.has("v") && root.get("v")?.takeIf { !it.isJsonNull }?.asInt == 2) {
                val remotePayload = gson.fromJson(remoteJson, WatchHistoryPayloadV2::class.java)
                remotePayload?.active.orEmpty().forEach { freshItems[it.id] = it }
                remotePayload?.resume.orEmpty().forEach { (k, v) -> freshResume[k] = v }
                freshWatched.addAll(remotePayload?.watchedIds.orEmpty())
            } else if (root.has("entries") && root.get("entries").isJsonObject) {
                migrateLegacyV1Object(root.getAsJsonObject("entries"), freshItems, freshResume, freshWatched)
            } else {
                return
            }

            activeItemsCache[sanitized] = freshItems
            seriesResumeCache[sanitized] = freshResume
            watchedIdsCache[sanitized] = freshWatched
            archiveItemsCache.remove(sanitized)
            archiveDirty.remove(sanitized)
            archiveCleared.remove(sanitized)
            saveLocal(email)
            println("[DesktopWatchHistoryManager] Aligned with Drive truth: ${freshItems.size} active, ${freshWatched.size} watched IDs.")
        } catch (e: Exception) {
            System.err.println("[DesktopWatchHistoryManager] Failed to align local state: ${e.message}")
        }
    }

    /**
     * Clears the watched flag for [fileId] in watch history when the user explicitly removes
     * a movie from their Watchlist, ensuring legacy watched checkmarks do not persist.
     */
    fun clearMediaWatchedFlag(email: String, fileId: String) {
        if (email.isBlank() || fileId.isBlank()) return
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val itemsMap = activeItemsCache[sanitized] ?: return
        val watchedSet = watchedIdsCache[sanitized] ?: return

        var modified = watchedSet.remove(fileId)
        val existing = itemsMap[fileId]
        if (existing != null && (existing.isWatched || existing.progressFraction >= 0.90f)) {
            itemsMap[fileId] = existing.copy(
                isWatched = false,
                positionMs = if (existing.progressFraction >= 0.90f) 0L else existing.positionMs,
                timestamp = System.currentTimeMillis()
            )
            modified = true
        }

        if (modified) {
            saveLocal(email)
        }
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
        if (email.isBlank() || fileId.isBlank()) return
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val itemsMap = activeItemsCache[sanitized] ?: return
        val resumeMap = seriesResumeCache[sanitized] ?: return
        val watchedSet = watchedIdsCache[sanitized] ?: return

        // Guard: Never overwrite an active resume position (>5s, <90%) with an un-seeked <3s position
        // unless explicitly requested by a user scrubber gesture.
        if (!allowZeroOverwrite && positionMs < 3000L) {
            val existing = itemsMap[fileId]
            if (existing != null && existing.positionMs > 5000L && existing.progressFraction < 0.90f) {
                return
            }
        }

        val now = System.currentTimeMillis()
        val effectiveDur = if (durationMs > 0L) durationMs else 45L * 60000L
        val fraction = (positionMs.toFloat() / effectiveDur.toFloat()).coerceIn(0f, 1f)
        val previouslyWatched = watchedSet.contains(fileId) || (itemsMap[fileId]?.isWatched == true)
        val isWatched = previouslyWatched ||
            fraction >= 0.90f ||
            (durationMs > 0L && positionMs >= (durationMs * 0.90f))
        if (isWatched) watchedSet.add(fileId)

        itemsMap[fileId] = HistoryItemEntityV2(
            id = fileId,
            title = title,
            positionMs = positionMs,
            durationMs = effectiveDur,
            timestamp = now,
            isWatched = isWatched,
            seriesId = if (isSeries && seriesId.isNotBlank()) seriesId else null,
            seasonNumber = if (isSeries) seasonNumber.coerceAtLeast(1) else null,
            episodeNumber = if (isSeries) episodeNumber.coerceAtLeast(1) else null
        )

        if (isSeries && seriesId.isNotBlank()) {
            val sNum = seasonNumber.coerceAtLeast(1)
            val eNum = episodeNumber.coerceAtLeast(1)
            val existingResume = resumeMap[seriesId]
            val isSameEp = existingResume?.episodeId == fileId
            val shouldUpdatePointer = existingResume == null ||
                !isSameEp ||
                positionMs >= 2000L ||
                (isSameEp && positionMs > 0L)

            if (shouldUpdatePointer) {
                resumeMap[seriesId] = SeriesResumeEntityV2(
                    episodeId = fileId,
                    seasonNumber = sNum,
                    episodeNumber = eNum,
                    positionMs = positionMs,
                    durationMs = effectiveDur,
                    timestamp = now
                )
            }
        }

        val isMovieCompleted = !isSeries && (
            fraction >= 0.90f ||
            (durationMs > 0L && positionMs >= (durationMs * 0.90f))
        )

        // Auto-mark watched in persistent watchlist on completion (>= 90%)
        if (isMovieCompleted) {
            historyScope.launch {
                val promoted = DesktopWatchlistManager.autoMarkWatchedIfEligible(
                    email = email,
                    title = title,
                    mediaType = WatchlistMediaType.MOVIE,
                    driveId = fileId
                )
                if (promoted && accessToken.isNotBlank()) {
                    DesktopWatchlistSync.pushLocalWatchlist(email, accessToken)
                }
            }
        } else if (isSeries && fraction >= 0.90f && seriesId.isNotBlank()) {
            val cachedSeries = DesktopSeriesCache.get(email, seriesId)
            val allEpisodes = cachedSeries?.seasons?.flatMap { it.episodes } ?: emptyList()
            val allWatched = allEpisodes.isNotEmpty() && allEpisodes.all { ep ->
                if (ep.id == fileId) true
                else {
                    val epItem = itemsMap[ep.id]
                    epItem?.isWatched == true ||
                        (epItem != null && epItem.durationMs > 0L && epItem.positionMs >= (epItem.durationMs * 0.90f)) ||
                        watchedSet.contains(ep.id)
                }
            }
            if (allWatched) {
                val resolvedSeriesTitle = cachedSeries?.title?.ifBlank { title } ?: title
                historyScope.launch {
                    val promoted = DesktopWatchlistManager.autoMarkWatchedIfEligible(
                        email = email,
                        title = resolvedSeriesTitle,
                        mediaType = WatchlistMediaType.SERIES,
                        driveId = seriesId
                    )
                    if (promoted && accessToken.isNotBlank()) {
                        DesktopWatchlistSync.pushLocalWatchlist(email, accessToken)
                    }
                }
            }
        }

        saveLocal(email)
    }

    fun isEpisodeWatched(email: String, fileId: String): Boolean {
        if (email.isBlank() || fileId.isBlank()) return false
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        if (watchedIdsCache[sanitized]?.contains(fileId) == true) return true
        val item = activeItemsCache[sanitized]?.get(fileId) ?: return false
        return item.isWatched || item.progressFraction >= 0.90f
    }

    fun isMediaWatched(email: String, fileId: String): Boolean = isEpisodeWatched(email, fileId)

    fun getWatchedEpisodeIds(email: String): Set<String> {
        if (email.isBlank()) return emptySet()
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        return watchedIdsCache[sanitized]?.toSet() ?: emptySet()
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
        if (email.isBlank() || fileId.isBlank()) return
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val itemsMap = activeItemsCache[sanitized] ?: return
        val resumeMap = seriesResumeCache[sanitized] ?: return
        val watchedSet = watchedIdsCache[sanitized] ?: return

        val now = System.currentTimeMillis()
        if (isWatched) {
            watchedSet.add(fileId)
        } else {
            watchedSet.remove(fileId)
        }

        val existing = itemsMap[fileId]
        val dur = if (durationMs > 0L) durationMs else existing?.durationMs ?: 0L
        val pos = if (isWatched) dur else 0L
        val resolvedSeriesId = seriesId.ifBlank { existing?.seriesId ?: "" }

        itemsMap[fileId] = HistoryItemEntityV2(
            id = fileId,
            title = title.ifBlank { existing?.title ?: "" },
            positionMs = pos,
            durationMs = dur,
            timestamp = now,
            isWatched = isWatched,
            seriesId = resolvedSeriesId.ifBlank { null },
            seasonNumber = if (resolvedSeriesId.isNotBlank()) seasonNumber.coerceAtLeast(1) else existing?.seasonNumber,
            episodeNumber = if (resolvedSeriesId.isNotBlank()) episodeNumber.coerceAtLeast(1) else existing?.episodeNumber
        )

        if (!isWatched && resolvedSeriesId.isNotBlank()) {
            val prev = resumeMap[resolvedSeriesId]
            if (prev != null && prev.episodeId == fileId) {
                resumeMap[resolvedSeriesId] = prev.copy(positionMs = 0L, timestamp = now)
            }
        }

        saveLocal(email)
    }

    suspend fun setSeasonEpisodesWatched(
        email: String,
        episodes: List<DesktopEpisode>,
        isWatched: Boolean,
        seriesId: String = ""
    ) = withContext(Dispatchers.IO) {
        if (email.isBlank() || episodes.isEmpty()) return@withContext
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val itemsMap = activeItemsCache[sanitized] ?: return@withContext
        val watchedSet = watchedIdsCache[sanitized] ?: return@withContext
        val now = System.currentTimeMillis()

        for (ep in episodes) {
            if (isWatched) watchedSet.add(ep.id) else watchedSet.remove(ep.id)
            val pos = if (isWatched) ep.durationMs else 0L
            itemsMap[ep.id] = HistoryItemEntityV2(
                id = ep.id,
                title = ep.title,
                positionMs = pos,
                durationMs = ep.durationMs,
                timestamp = now,
                isWatched = isWatched,
                seriesId = seriesId.ifBlank { null },
                seasonNumber = ep.seasonNumber.coerceAtLeast(1),
                episodeNumber = ep.episodeNumber.coerceAtLeast(1)
            )
        }

        saveLocal(email)
    }

    suspend fun setEpisodesWatched(
        email: String,
        episodeIds: Collection<String>,
        isWatched: Boolean
    ) = withContext(Dispatchers.IO) {
        if (email.isBlank() || episodeIds.isEmpty()) return@withContext
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val itemsMap = activeItemsCache[sanitized] ?: return@withContext
        val watchedSet = watchedIdsCache[sanitized] ?: return@withContext
        val now = System.currentTimeMillis()

        for (id in episodeIds) {
            if (isWatched) watchedSet.add(id) else watchedSet.remove(id)
            val existing = itemsMap[id]
            val dur = existing?.durationMs ?: 0L
            itemsMap[id] = (existing ?: HistoryItemEntityV2(id = id)).copy(
                positionMs = if (isWatched) dur else 0L,
                timestamp = now,
                isWatched = isWatched
            )
        }

        saveLocal(email)
    }

    fun getSeriesResumeState(email: String, seriesId: String): SeriesResumeState? {
        if (email.isBlank() || seriesId.isBlank()) return null
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val resumeMap = seriesResumeCache[sanitized]
        val itemsMap = activeItemsCache[sanitized]

        var resume = resumeMap?.get(seriesId)

        // Self-heal if resume pointer is missing or < 2000ms while an active episode has progress
        if (resume == null || resume.positionMs < 2000L) {
            val bestCandidate = itemsMap?.values
                ?.filter { it.seriesId == seriesId && it.positionMs >= 2000L }
                ?.maxByOrNull { it.timestamp }

            if (bestCandidate != null && (resume == null || bestCandidate.timestamp >= resume.timestamp)) {
                val repaired = SeriesResumeEntityV2(
                    episodeId = bestCandidate.id,
                    seasonNumber = bestCandidate.seasonNumber ?: 1,
                    episodeNumber = bestCandidate.episodeNumber ?: 1,
                    positionMs = bestCandidate.positionMs,
                    durationMs = bestCandidate.durationMs,
                    timestamp = bestCandidate.timestamp
                )
                resumeMap?.put(seriesId, repaired)
                saveLocal(email)
                resume = repaired
            }
        }

        val resolved = resume ?: return null
        return SeriesResumeState(
            seriesId = seriesId,
            lastEpisodeId = resolved.episodeId,
            seasonNumber = resolved.seasonNumber,
            episodeNumber = resolved.episodeNumber,
            positionMs = resolved.positionMs,
            durationMs = resolved.durationMs,
            lastWatchedTimestamp = resolved.timestamp
        )
    }

    fun getPlaybackPosition(email: String, fileId: String): Long {
        if (email.isBlank() || fileId.isBlank()) return 0L
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val item = activeItemsCache[sanitized]?.get(fileId) ?: return 0L

        // Only reset to 00:00 when the current position is at the end (>= 90%).
        // Sticky `isWatched` must not block resumption when rewatching and pausing mid-movie.
        val isAtEnd = item.progressFraction >= 0.90f ||
            (item.durationMs > 0L && item.positionMs >= (item.durationMs * 0.90f))
        if (isAtEnd) return 0L

        return if (item.positionMs > 1500L) item.positionMs else 0L
    }

    fun getPlaybackDuration(email: String, fileId: String): Long {
        if (email.isBlank() || fileId.isBlank()) return 0L
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val item = activeItemsCache[sanitized]?.get(fileId)
        if (item != null && item.durationMs > 0L) {
            return item.durationMs
        }
        val resume = seriesResumeCache[sanitized]?.values?.firstOrNull { it.episodeId == fileId }
        if (resume != null && resume.durationMs > 0L) {
            return resume.durationMs
        }
        return 0L
    }

    /**
     * Returns true only if the user has watched at least 1 second (>= 1000ms) of this media item
     * in watch history. Returns false for new/unwatched episodes so playback can bypass cloud sync.
     */
    fun hasSavedWatchProgress(email: String, fileId: String): Boolean {
        if (email.isBlank() || fileId.isBlank()) return false
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)

        val activePos = activeItemsCache[sanitized]?.get(fileId)?.positionMs ?: 0L
        if (activePos >= 1000L) return true

        val resumePos = seriesResumeCache[sanitized]?.values
            ?.firstOrNull { it.episodeId == fileId }
            ?.positionMs ?: 0L
        if (resumePos >= 1000L) return true

        val archivePos = archiveItemsCache[sanitized]?.get(fileId)?.positionMs ?: 0L
        if (archivePos >= 1000L) return true

        return false
    }

    fun getContinueWatching(
        email: String,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ): List<DesktopContinueWatchingItem> {
        if (email.isBlank()) return emptyList()
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val itemsMap = activeItemsCache[sanitized] ?: return emptyList()
        val result = mutableListOf<DesktopContinueWatchingItem>()
        val movieIds = movies.map { it.id }.toSet()

        for (movie in movies) {
            val item = itemsMap[movie.id] ?: continue
            val effectiveDur = if (item.durationMs > 0L) item.durationMs else movie.durationMs
            val fraction = if (effectiveDur > 0L) (item.positionMs.toFloat() / effectiveDur.toFloat()).coerceIn(0f, 1f) else 0f

            if (item.timestamp > 0L && item.positionMs >= 3000L && fraction >= 0.005f && fraction < 0.90f) {
                result.add(
                    DesktopContinueWatchingItem(
                        mediaId = movie.id,
                        title = movie.title,
                        subtitle = movie.genre.ifBlank { "Movie" },
                        thumbnailUrl = movie.thumbnailUrl ?: "",
                        fallbackThumbnailUrl = movie.fallbackThumbnailUrl ?: "",
                        positionMs = item.positionMs,
                        durationMs = effectiveDur,
                        progressFraction = fraction.coerceIn(0.04f, 1f),
                        isSeries = false,
                        timestamp = item.timestamp
                    )
                )
            }
        }

        for (s in series) {
            val resume = getSeriesResumeState(email, s.id) ?: continue
            val epId = resume.lastEpisodeId
            if (movieIds.contains(epId)) continue

            val cachedSeries = DesktopSeriesCache.get(email, s.id)
            val allEpisodes = cachedSeries?.seasons?.flatMap { it.episodes } ?: emptyList()
            val targetEp = allEpisodes.find { it.id == epId }

            if (allEpisodes.isNotEmpty() && targetEp == null) continue

            val sNum = if (resume.seasonNumber >= 1) resume.seasonNumber else targetEp?.seasonNumber ?: 1
            val eNum = if (resume.episodeNumber >= 1) resume.episodeNumber else targetEp?.episodeNumber ?: 1
            val epTitle = targetEp?.title ?: itemsMap[epId]?.title?.ifBlank { null } ?: "Episode $eNum"
            val epThumb = targetEp?.thumbnailUrl ?: s.thumbnailUrl

            val effectiveDur = when {
                resume.durationMs > 0L -> resume.durationMs
                (targetEp?.durationMs ?: 0L) > 0L -> targetEp!!.durationMs
                else -> 45L * 60000L
            }
            val fraction = (resume.positionMs.toFloat() / effectiveDur.toFloat()).coerceIn(0f, 1f)
            val code = "S${sNum}E${eNum}"
            val subtitle = if (epTitle.isNotBlank()) "$code • $epTitle" else code

            val isOngoing = resume.positionMs >= 3000L && fraction in 0.005f..0.95f
            val isQueuedNext = resume.positionMs == 0L && fraction == 0f

            if (resume.lastWatchedTimestamp > 0L && (isOngoing || isQueuedNext)) {
                result.add(
                    DesktopContinueWatchingItem(
                        mediaId = epId,
                        title = s.title,
                        subtitle = subtitle,
                        thumbnailUrl = (epThumb?.ifBlank { s.thumbnailUrl } ?: s.thumbnailUrl).orEmpty(),
                        fallbackThumbnailUrl = s.fallbackThumbnailUrl ?: "",
                        positionMs = resume.positionMs,
                        durationMs = effectiveDur,
                        progressFraction = if (resume.positionMs > 0L) fraction.coerceIn(0.04f, 1f) else 0.02f,
                        isSeries = true,
                        seriesId = s.id,
                        seasonNumber = sNum,
                        episodeNumber = eNum,
                        episodeTitle = epTitle,
                        timestamp = resume.lastWatchedTimestamp
                    )
                )
            }
        }

        return result.sortedByDescending { it.timestamp }.take(10)
    }

    fun getAllWatchHistory(
        email: String,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ): List<DesktopContinueWatchingItem> {
        if (email.isBlank()) return emptyList()
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val active = activeItemsCache[sanitized]?.values.orEmpty()
        val archive = archiveItemsCache[sanitized]?.values.orEmpty()
        val watchedSet = watchedIdsCache[sanitized] ?: emptySet()
        val watchlistEntries = DesktopWatchlistManager.loadLocalSync(email)

        val combinedMap = mutableMapOf<String, HistoryItemEntityV2>()
        for (item in archive) {
            combinedMap[item.id] = item
        }
        for (item in active) {
            val existing = combinedMap[item.id]
            if (existing == null || item.timestamp >= existing.timestamp) {
                combinedMap[item.id] = item
            }
        }

        // For any id in watchedIdsCache[sanitized] that has no entity in either active or archive,
        // resolve its real metadata from movies, series, and DesktopSeriesCache before falling back
        for (wId in watchedSet) {
            if (wId !in combinedMap) {
                val matchedMovie = movies.find { it.id == wId }
                if (matchedMovie != null) {
                    combinedMap[wId] = HistoryItemEntityV2(
                        id = wId,
                        title = matchedMovie.title,
                        positionMs = matchedMovie.durationMs,
                        durationMs = matchedMovie.durationMs,
                        timestamp = 0L,
                        isWatched = true,
                        seriesId = null,
                        seasonNumber = null,
                        episodeNumber = null
                    )
                } else {
                    var foundEpisode = false
                    for (s in series) {
                        val cachedSeries = DesktopSeriesCache.get(email, s.id)
                        val targetEp = cachedSeries?.seasons?.flatMap { it.episodes }?.find { it.id == wId }
                        if (targetEp != null) {
                            combinedMap[wId] = HistoryItemEntityV2(
                                id = wId,
                                title = targetEp.title.ifBlank { s.title },
                                positionMs = targetEp.durationMs,
                                durationMs = targetEp.durationMs,
                                timestamp = 0L,
                                isWatched = true,
                                seriesId = s.id,
                                seasonNumber = targetEp.seasonNumber,
                                episodeNumber = targetEp.episodeNumber
                            )
                            foundEpisode = true
                            break
                        }
                    }
                    if (!foundEpisode) {
                        combinedMap[wId] = HistoryItemEntityV2(
                            id = wId,
                            title = "",
                            positionMs = 0L,
                            durationMs = 0L,
                            timestamp = 0L,
                            isWatched = true
                        )
                    }
                }
            }
        }

        val result = mutableListOf<DesktopContinueWatchingItem>()
        for (item in combinedMap.values) {
            val isWatched = item.isWatched || watchedSet.contains(item.id)
            if (item.timestamp <= 0L && !isWatched) continue
            val rawProgress = item.progressFraction
            if (item.positionMs < 2000L && rawProgress < 0.01f && !isWatched) continue

            val effectiveFraction = if (isWatched) 1.0f else rawProgress

            val isSeries = item.seriesId != null || run {
                series.any { s ->
                    val cs = DesktopSeriesCache.get(email, s.id)
                    cs?.seasons?.flatMap { it.episodes }?.any { it.id == item.id } == true
                }
            }

            var sortTimestamp = item.timestamp
            if (isSeries && !item.seriesId.isNullOrBlank()) {
                val resume = seriesResumeCache[sanitized]?.get(item.seriesId)
                if (resume?.episodeId == item.id && resume.timestamp > sortTimestamp) {
                    sortTimestamp = resume.timestamp
                }
            }
            if (sortTimestamp <= 0L) {
                val normTitle = DesktopWatchlistManager.normalizeTitle(item.title)
                val matched = watchlistEntries.firstOrNull { entry ->
                    !entry.isDeleted && ((entry.driveId != null && entry.driveId == item.id) ||
                        (entry.normalizedTitle.isNotBlank() && entry.normalizedTitle == normTitle))
                }
                if (matched != null && matched.updatedAt > 0L) {
                    sortTimestamp = matched.updatedAt
                }
            }

            if (isSeries) {
                var matchedSeries = item.seriesId?.let { sid -> series.find { it.id == sid } }
                var targetEp: DesktopEpisode? = null
                if (matchedSeries != null) {
                    val cachedSeries = DesktopSeriesCache.get(email, matchedSeries.id)
                    targetEp = cachedSeries?.seasons?.flatMap { it.episodes }?.find { it.id == item.id }
                } else {
                    for (s in series) {
                        val cs = DesktopSeriesCache.get(email, s.id)
                        val ep = cs?.seasons?.flatMap { it.episodes }?.find { it.id == item.id }
                        if (ep != null) {
                            matchedSeries = s
                            targetEp = ep
                            break
                        }
                    }
                }

                val seriesTitle = matchedSeries?.title ?: item.title.ifBlank { "Series" }
                val sNum = item.seasonNumber ?: targetEp?.seasonNumber
                val eNum = item.episodeNumber ?: targetEp?.episodeNumber
                val epTitle = targetEp?.title ?: item.title.ifBlank { if (eNum != null) "Episode $eNum" else "" }
                val epThumb = targetEp?.thumbnailUrl ?: matchedSeries?.thumbnailUrl ?: ""
                val effectiveDur = when {
                    item.durationMs > 0L -> item.durationMs
                    (targetEp?.durationMs ?: 0L) > 0L -> targetEp!!.durationMs
                    else -> 45L * 60000L
                }
                val effectivePos = if (isWatched && item.positionMs == 0L) effectiveDur else item.positionMs

                val code = if (sNum != null && eNum != null) "S${sNum}E${eNum}" else ""
                val subtitle = when {
                    code.isNotBlank() && epTitle.isNotBlank() -> "$code • $epTitle"
                    code.isNotBlank() -> code
                    epTitle.isNotBlank() -> epTitle
                    else -> "Series Episode"
                }

                result.add(
                    DesktopContinueWatchingItem(
                        mediaId = item.id,
                        title = seriesTitle,
                        subtitle = subtitle,
                        thumbnailUrl = epThumb,
                        fallbackThumbnailUrl = matchedSeries?.fallbackThumbnailUrl ?: "",
                        positionMs = effectivePos,
                        durationMs = effectiveDur,
                        progressFraction = effectiveFraction,
                        isSeries = true,
                        seriesId = matchedSeries?.id ?: item.seriesId,
                        seasonNumber = sNum,
                        episodeNumber = eNum,
                        episodeTitle = epTitle,
                        timestamp = sortTimestamp
                    )
                )
            } else {
                val matchedMovie = movies.find { it.id == item.id }
                val movieTitle = matchedMovie?.title ?: item.title.ifBlank { "Movie" }
                val movieThumb = matchedMovie?.thumbnailUrl ?: ""
                val effectiveDur = if (item.durationMs > 0L) item.durationMs else matchedMovie?.durationMs ?: 0L
                val effectivePos = if (isWatched && item.positionMs == 0L) effectiveDur else item.positionMs

                result.add(
                    DesktopContinueWatchingItem(
                        mediaId = item.id,
                        title = movieTitle,
                        subtitle = matchedMovie?.genre?.ifBlank { "Movie" } ?: "Movie",
                        thumbnailUrl = movieThumb,
                        fallbackThumbnailUrl = matchedMovie?.fallbackThumbnailUrl ?: "",
                        positionMs = effectivePos,
                        durationMs = effectiveDur,
                        progressFraction = effectiveFraction,
                        isSeries = false,
                        timestamp = sortTimestamp
                    )
                )
            }
        }

        return result.sortedWith(
            compareByDescending<DesktopContinueWatchingItem> { it.timestamp }
                .thenByDescending { it.seasonNumber ?: 0 }
                .thenByDescending { it.episodeNumber ?: 0 }
                .thenBy { it.title.lowercase() }
        )
    }

    fun getLastWatchedMovie(
        email: String,
        movies: List<DesktopMediaItem>
    ): DesktopContinueWatchingItem? {
        if (email.isBlank() || movies.isEmpty()) return null
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val itemsMap = activeItemsCache[sanitized] ?: return null
        var latestItem: DesktopContinueWatchingItem? = null
        var maxTs = -1L

        for (movie in movies) {
            val item = itemsMap[movie.id] ?: continue
            val effectiveDur = if (item.durationMs > 0L) item.durationMs else movie.durationMs
            val fraction = if (effectiveDur > 0L) (item.positionMs.toFloat() / effectiveDur.toFloat()).coerceIn(0f, 1f) else 0f

            if (item.timestamp > maxTs && item.positionMs > 0L && fraction >= 0.01f && fraction < 0.90f) {
                maxTs = item.timestamp
                val remainingMs = (effectiveDur - item.positionMs).coerceAtLeast(0L)
                val remainingStr = formatRemainingTime(remainingMs)

                latestItem = DesktopContinueWatchingItem(
                    mediaId = movie.id,
                    title = movie.title,
                    subtitle = if (remainingStr.isNotBlank()) "$remainingStr left" else movie.genre,
                    thumbnailUrl = movie.thumbnailUrl ?: "",
                    fallbackThumbnailUrl = movie.fallbackThumbnailUrl ?: "",
                    positionMs = item.positionMs,
                    durationMs = effectiveDur,
                    progressFraction = fraction.coerceIn(0.01f, 1f),
                    isSeries = false,
                    timestamp = item.timestamp
                )
            }
        }
        return latestItem
    }

    fun getLastWatchedSeries(
        email: String,
        series: List<DesktopMediaItem>
    ): DesktopContinueWatchingItem? {
        if (email.isBlank() || series.isEmpty()) return null
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        val itemsMap = activeItemsCache[sanitized] ?: return null
        var latestItem: DesktopContinueWatchingItem? = null
        var maxTs = -1L

        for (s in series) {
            val resume = getSeriesResumeState(email, s.id) ?: continue
            val epId = resume.lastEpisodeId
            val ts = resume.lastWatchedTimestamp

            if (ts > maxTs) {
                val cachedSeries = DesktopSeriesCache.get(email, s.id)
                val allEpisodes = cachedSeries?.seasons?.flatMap { it.episodes } ?: emptyList()
                val targetEp = allEpisodes.find { it.id == epId }

                if (allEpisodes.isNotEmpty() && targetEp == null) continue

                val sNum = if (resume.seasonNumber >= 1) resume.seasonNumber else targetEp?.seasonNumber ?: 1
                val eNum = if (resume.episodeNumber >= 1) resume.episodeNumber else targetEp?.episodeNumber ?: 1
                val epTitle = targetEp?.title ?: itemsMap[epId]?.title?.ifBlank { null } ?: "Episode $eNum"
                val epThumb = targetEp?.thumbnailUrl ?: s.thumbnailUrl
                val effectiveDur = if (resume.durationMs > 0L) resume.durationMs else targetEp?.durationMs ?: 0L
                val fraction = if (effectiveDur > 0L) (resume.positionMs.toFloat() / effectiveDur.toFloat()).coerceIn(0f, 1f) else 0f

                val code = "S${sNum}E${eNum}"
                val subtitle = if (epTitle.isNotBlank()) "$code • $epTitle" else code

                maxTs = ts
                latestItem = DesktopContinueWatchingItem(
                    mediaId = epId,
                    title = s.title,
                    subtitle = subtitle,
                    thumbnailUrl = (epThumb?.ifBlank { s.thumbnailUrl } ?: s.thumbnailUrl).orEmpty(),
                    fallbackThumbnailUrl = s.fallbackThumbnailUrl ?: "",
                    positionMs = resume.positionMs,
                    durationMs = effectiveDur,
                    progressFraction = if (resume.positionMs > 0L) fraction.coerceIn(0.02f, 1f) else 0.02f,
                    isSeries = true,
                    seriesId = s.id,
                    seasonNumber = sNum,
                    episodeNumber = eNum,
                    episodeTitle = epTitle,
                    timestamp = ts
                )
            }
        }
        return latestItem
    }

    fun formatRemainingTime(ms: Long): String {
        if (ms <= 0L) return ""
        val totalMinutes = ms / 60000L
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0 && minutes > 0 -> "${hours}h${minutes}m"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "< 1m"
        }
    }

    suspend fun removeHistoryItem(email: String, fileId: String) = withContext(Dispatchers.IO) {
        if (email.isBlank() || fileId.isBlank()) return@withContext
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        activeItemsCache[sanitized]?.remove(fileId)
        archiveItemsCache[sanitized]?.remove(fileId)
        watchedIdsCache[sanitized]?.remove(fileId)

        val resumeMap = seriesResumeCache[sanitized]
        if (resumeMap != null) {
            val matchingKeys = resumeMap.filter { it.value.episodeId == fileId }.keys.toList()
            matchingKeys.forEach { resumeMap.remove(it) }
        }
        archiveDirty[sanitized] = true
        saveLocal(email)
        saveArchiveLocalBySanitized(sanitized)
        notifyMutation()
    }

    suspend fun clearAllHistory(email: String) = withContext(Dispatchers.IO) {
        clearLocalHistory(email)
    }

    fun clearLocalHistory(email: String) {
        if (email.isBlank()) return
        ensureProfileLoaded(email)
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        activeItemsCache[sanitized]?.clear()
        archiveItemsCache[sanitized]?.clear()
        seriesResumeCache[sanitized]?.clear()
        watchedIdsCache[sanitized]?.clear()
        archiveDirty[sanitized] = true
        archiveCleared[sanitized] = true
        saveLocal(email)
        saveArchiveLocalBySanitized(sanitized)
        notifyMutation()
    }
}
