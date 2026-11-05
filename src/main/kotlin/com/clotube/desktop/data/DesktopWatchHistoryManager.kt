package com.clotube.desktop.data

import androidx.compose.runtime.mutableIntStateOf
import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
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

data class TypedHistoryEntry(
    val type: String,
    val value: Any
)

object DesktopWatchHistoryManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val memoryCache = ConcurrentHashMap<String, MutableMap<String, TypedHistoryEntry>>()

    val historyRevision = mutableIntStateOf(0)

    fun notifyMutation() {
        historyRevision.intValue++
    }

    private fun getFile(email: String): File {
        return DesktopProfileManager.getWatchHistoryFile(email)
    }

    fun loadLocalSync(email: String): MutableMap<String, TypedHistoryEntry> {
        if (email.isBlank()) return mutableMapOf()
        val sanitized = DesktopProfileManager.sanitizeEmail(email)
        memoryCache[sanitized]?.let { return it }

        val file = getFile(email)
        val entries = mutableMapOf<String, TypedHistoryEntry>()

        if (file.exists() && file.length() > 0) {
            try {
                val json = file.readText(Charsets.UTF_8)
                val root = JsonParser.parseString(json).asJsonObject
                val entriesObj = root.getAsJsonObject("entries")
                if (entriesObj != null) {
                    for ((k, v) in entriesObj.entrySet()) {
                        val entryObj = v.asJsonObject
                        val type = entryObj.get("type")?.asString ?: "string"
                        val valueElem = entryObj.get("value")
                        val value: Any = when (type) {
                            "long" -> valueElem?.asLong ?: 0L
                            "int" -> valueElem?.asInt ?: 0
                            "float" -> valueElem?.asFloat ?: 0f
                            "boolean" -> valueElem?.asBoolean ?: false
                            else -> valueElem?.asString ?: ""
                        }
                        entries[k] = TypedHistoryEntry(type, value)
                    }
                }
            } catch (e: Exception) {
                System.err.println("[DesktopWatchHistoryManager] Error reading local history: ${e.message}")
            }
        }

        memoryCache[sanitized] = entries
        return entries
    }

    fun saveLocal(email: String, entries: Map<String, TypedHistoryEntry>) {
        if (email.isBlank()) return
        try {
            val root = JsonObject().apply {
                addProperty("version", 1)
                addProperty("updatedAt", System.currentTimeMillis())
                val entriesObj = JsonObject()
                for ((k, v) in entries) {
                    val entryObj = JsonObject()
                    entryObj.addProperty("type", v.type)
                    when (v.value) {
                        is Number -> entryObj.addProperty("value", v.value)
                        is Boolean -> entryObj.addProperty("value", v.value)
                        else -> entryObj.addProperty("value", v.value.toString())
                    }
                    entriesObj.add(k, entryObj)
                }
                add("entries", entriesObj)
            }

            val file = getFile(email)
            file.writeText(gson.toJson(root), Charsets.UTF_8)
            notifyMutation()
        } catch (e: Exception) {
            System.err.println("[DesktopWatchHistoryManager] Error saving local history: ${e.message}")
        }
    }

    fun getLocalPayloadJson(email: String): String {
        val entries = loadLocalSync(email)
        val root = JsonObject().apply {
            addProperty("version", 1)
            addProperty("updatedAt", System.currentTimeMillis())
            val entriesObj = JsonObject()
            for ((k, v) in entries) {
                val entryObj = JsonObject()
                entryObj.addProperty("type", v.type)
                when (v.value) {
                    is Number -> entryObj.addProperty("value", v.value)
                    is Boolean -> entryObj.addProperty("value", v.value)
                    else -> entryObj.addProperty("value", v.value.toString())
                }
                entriesObj.add(k, entryObj)
            }
            add("entries", entriesObj)
        }
        return gson.toJson(root)
    }

    private fun resolveTimestampKey(key: String): String {
        return if (key.startsWith("series_resume_")) {
            val suffix = when {
                key.endsWith("_episode_id") -> "_episode_id"
                key.endsWith("_season_num") -> "_season_num"
                key.endsWith("_episode_num") -> "_episode_num"
                key.endsWith("_pos_ms") -> "_pos_ms"
                key.endsWith("_dur_ms") -> "_dur_ms"
                key.endsWith("_timestamp") -> "_timestamp"
                else -> ""
            }
            if (suffix.isNotEmpty()) {
                val seriesId = key.removePrefix("series_resume_").removeSuffix(suffix)
                "series_resume_${seriesId}_timestamp"
            } else {
                "${key.substringBeforeLast('_')}_timestamp"
            }
        } else {
            val suffix = when {
                key.endsWith("_title") -> "_title"
                key.endsWith("_pos") -> "_pos"
                key.endsWith("_dur") -> "_dur"
                key.endsWith("_fraction") -> "_fraction"
                key.endsWith("_timestamp") -> "_timestamp"
                key.endsWith("_is_series") -> "_is_series"
                key.endsWith("_is_watched") -> "_is_watched"
                key.endsWith("_series_id") -> "_series_id"
                key.endsWith("_season_num") -> "_season_num"
                key.endsWith("_episode_num") -> "_episode_num"
                else -> ""
            }
            if (suffix.isNotEmpty()) {
                val fileId = key.removeSuffix(suffix)
                "${fileId}_timestamp"
            } else {
                "${key.substringBeforeLast('_')}_timestamp"
            }
        }
    }

    fun mergeRemotePayload(email: String, remoteJson: String): Boolean {
        if (email.isBlank() || remoteJson.isBlank()) return false
        val local = loadLocalSync(email)
        var changed = false

        try {
            val root = JsonParser.parseString(remoteJson).asJsonObject
            val entriesObj = root.getAsJsonObject("entries") ?: return false

            for ((key, valueElem) in entriesObj.entrySet()) {
                val entryObj = valueElem.asJsonObject
                val type = entryObj.get("type")?.asString ?: "string"
                val v = entryObj.get("value")
                val remoteValue: Any = when (type) {
                    "long" -> v?.asLong ?: 0L
                    "int" -> v?.asInt ?: 0
                    "float" -> v?.asFloat ?: 0f
                    "boolean" -> v?.asBoolean ?: false
                    else -> v?.asString ?: ""
                }

                val localEntry = local[key]
                if (localEntry == null) {
                    local[key] = TypedHistoryEntry(type, remoteValue)
                    changed = true
                } else {
                    val tsKey = resolveTimestampKey(key)
                    val remoteTs = entriesObj.getAsJsonObject(tsKey)?.get("value")?.asLong ?: 0L
                    val localTs = (local[tsKey]?.value as? Long) ?: 0L

                    if (remoteTs > localTs) {
                        local[key] = TypedHistoryEntry(type, remoteValue)
                        changed = true
                    }
                }
            }

            if (changed) {
                saveLocal(email, local)
            }
        } catch (e: Exception) {
            System.err.println("[DesktopWatchHistoryManager] Error merging remote history: ${e.message}")
        }
        return changed
    }

    suspend fun recordPlaybackProgress(
        email: String,
        fileId: String,
        title: String,
        positionMs: Long,
        durationMs: Long,
        isSeries: Boolean,
        seriesId: String = "",
        seasonNumber: Int = 1,
        episodeNumber: Int = 1
    ) = withContext(Dispatchers.IO) {
        if (email.isBlank() || fileId.isBlank()) return@withContext
        val local = loadLocalSync(email)
        val now = System.currentTimeMillis()
        val fraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

        synchronized(local) {
            // Check if episode is already watched so replaying from 00:00 preserves watched status
            val alreadyWatched = isEpisodeWatched(email, fileId)
            val isCompletedNow = alreadyWatched || fraction >= 0.90f || (durationMs > 0L && positionMs >= (durationMs * 0.90f))

            local["${fileId}_title"] = TypedHistoryEntry("string", title)
            local["${fileId}_pos"] = TypedHistoryEntry("long", positionMs)
            local["${fileId}_dur"] = TypedHistoryEntry("long", durationMs)
            local["${fileId}_fraction"] = TypedHistoryEntry("float", fraction)
            local["${fileId}_timestamp"] = TypedHistoryEntry("long", now)
            local["${fileId}_is_series"] = TypedHistoryEntry("boolean", isSeries)

            if (isCompletedNow) {
                local["${fileId}_is_watched"] = TypedHistoryEntry("boolean", true)
            }

            if (isSeries && seriesId.isNotBlank()) {
                val sNum = seasonNumber.coerceAtLeast(1)
                val eNum = episodeNumber.coerceAtLeast(1)

                local["${fileId}_series_id"] = TypedHistoryEntry("string", seriesId)
                local["${fileId}_season_num"] = TypedHistoryEntry("int", sNum)
                local["${fileId}_episode_num"] = TypedHistoryEntry("int", eNum)

                local["series_resume_${seriesId}_episode_id"] = TypedHistoryEntry("string", fileId)
                local["series_resume_${seriesId}_season_num"] = TypedHistoryEntry("int", sNum)
                local["series_resume_${seriesId}_episode_num"] = TypedHistoryEntry("int", eNum)
                local["series_resume_${seriesId}_pos_ms"] = TypedHistoryEntry("long", positionMs)
                local["series_resume_${seriesId}_dur_ms"] = TypedHistoryEntry("long", durationMs)
                local["series_resume_${seriesId}_timestamp"] = TypedHistoryEntry("long", now)
            }
        }

        saveLocal(email, local)
    }

    fun getPlaybackPosition(email: String, fileId: String): Long {
        if (email.isBlank() || fileId.isBlank()) return 0L
        val local = loadLocalSync(email)
        val pos = (local["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
        val dur = (local["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
        val fraction = (local["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f

        if (fraction >= 0.95f || (dur > 0L && pos >= (dur * 0.95f))) return 0L
        return if (pos > 1500L) pos else 0L
    }

    fun isEpisodeWatched(email: String, fileId: String): Boolean {
        if (email.isBlank() || fileId.isBlank()) return false
        val local = loadLocalSync(email)
        val explicit = local["${fileId}_is_watched"]?.value
        if (explicit is Boolean) return explicit
        if (explicit is String) return explicit.toBoolean()

        // Fallback for legacy records
        val fraction = (local["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f
        return fraction >= 0.90f
    }

    suspend fun setEpisodeWatched(
        email: String,
        fileId: String,
        isWatched: Boolean,
        seriesId: String = "",
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        title: String = "",
        durationMs: Long = 0L
    ) = withContext(Dispatchers.IO) {
        if (email.isBlank() || fileId.isBlank()) return@withContext
        val local = loadLocalSync(email)
        val now = System.currentTimeMillis()

        synchronized(local) {
            val fraction = if (isWatched) 1.0f else 0.0f
            val pos = if (isWatched) durationMs else 0L

            local["${fileId}_is_watched"] = TypedHistoryEntry("boolean", isWatched)
            local["${fileId}_title"] = TypedHistoryEntry("string", title)
            local["${fileId}_pos"] = TypedHistoryEntry("long", pos)
            local["${fileId}_dur"] = TypedHistoryEntry("long", durationMs)
            local["${fileId}_fraction"] = TypedHistoryEntry("float", fraction)
            local["${fileId}_timestamp"] = TypedHistoryEntry("long", now)
            local["${fileId}_is_series"] = TypedHistoryEntry("boolean", seriesId.isNotBlank())

            if (seriesId.isNotBlank()) {
                local["${fileId}_series_id"] = TypedHistoryEntry("string", seriesId)
                local["${fileId}_season_num"] = TypedHistoryEntry("int", seasonNumber)
                local["${fileId}_episode_num"] = TypedHistoryEntry("int", episodeNumber)

                if (!isWatched) {
                    val currentResumeEpId = local["series_resume_${seriesId}_episode_id"]?.value as? String
                    if (currentResumeEpId == fileId) {
                        local["series_resume_${seriesId}_pos_ms"] = TypedHistoryEntry("long", 0L)
                        local["series_resume_${seriesId}_timestamp"] = TypedHistoryEntry("long", now)
                    }
                }
            }
        }

        saveLocal(email, local)
    }

    suspend fun setSeasonEpisodesWatched(
        email: String,
        episodes: List<DesktopEpisode>,
        isWatched: Boolean,
        seriesId: String = ""
    ) = withContext(Dispatchers.IO) {
        if (email.isBlank() || episodes.isEmpty()) return@withContext
        val local = loadLocalSync(email)
        val now = System.currentTimeMillis()
        val fraction = if (isWatched) 1.0f else 0.0f

        synchronized(local) {
            for (ep in episodes) {
                val pos = if (isWatched) ep.durationMs else 0L
                local["${ep.id}_is_watched"] = TypedHistoryEntry("boolean", isWatched)
                local["${ep.id}_title"] = TypedHistoryEntry("string", ep.title)
                local["${ep.id}_pos"] = TypedHistoryEntry("long", pos)
                local["${ep.id}_dur"] = TypedHistoryEntry("long", ep.durationMs)
                local["${ep.id}_fraction"] = TypedHistoryEntry("float", fraction)
                local["${ep.id}_timestamp"] = TypedHistoryEntry("long", now)
                local["${ep.id}_is_series"] = TypedHistoryEntry("boolean", true)

                if (seriesId.isNotBlank()) {
                    local["${ep.id}_series_id"] = TypedHistoryEntry("string", seriesId)
                    local["${ep.id}_season_num"] = TypedHistoryEntry("int", ep.seasonNumber)
                    local["${ep.id}_episode_num"] = TypedHistoryEntry("int", ep.episodeNumber)
                }
            }
        }

        saveLocal(email, local)
    }

    suspend fun setEpisodesWatched(
        email: String,
        episodeIds: Collection<String>,
        isWatched: Boolean
    ) = withContext(Dispatchers.IO) {
        if (email.isBlank() || episodeIds.isEmpty()) return@withContext
        val local = loadLocalSync(email)
        val now = System.currentTimeMillis()
        val fraction = if (isWatched) 1.0f else 0.0f

        synchronized(local) {
            for (id in episodeIds) {
                local["${id}_is_watched"] = TypedHistoryEntry("boolean", isWatched)
                local["${id}_fraction"] = TypedHistoryEntry("float", fraction)
                local["${id}_timestamp"] = TypedHistoryEntry("long", now)
                local["${id}_is_series"] = TypedHistoryEntry("boolean", true)
            }
        }

        saveLocal(email, local)
    }

    fun getWatchedEpisodeIds(email: String): Set<String> {
        val local = loadLocalSync(email)
        val watched = mutableSetOf<String>()
        for ((k, v) in local) {
            if (k.endsWith("_is_watched")) {
                if (v.value == true || v.value == "true") {
                    watched.add(k.removeSuffix("_is_watched"))
                }
            }
        }
        for ((k, v) in local) {
            if (k.endsWith("_fraction")) {
                val id = k.removeSuffix("_fraction")
                if (!local.containsKey("${id}_is_watched")) {
                    val frac = (v.value as? Number)?.toFloat() ?: 0f
                    if (frac >= 0.90f) {
                        watched.add(id)
                    }
                }
            }
        }
        return watched
    }

    fun getSeriesResumeState(email: String, seriesId: String): SeriesResumeState? {
        if (email.isBlank() || seriesId.isBlank()) return null
        val local = loadLocalSync(email)
        val epId = local["series_resume_${seriesId}_episode_id"]?.value as? String ?: return null
        val sNum = (local["series_resume_${seriesId}_season_num"]?.value as? Number)?.toInt() ?: 1
        val eNum = (local["series_resume_${seriesId}_episode_num"]?.value as? Number)?.toInt() ?: 1
        val pos = (local["series_resume_${seriesId}_pos_ms"]?.value as? Number)?.toLong() ?: 0L
        val dur = (local["series_resume_${seriesId}_dur_ms"]?.value as? Number)?.toLong() ?: 0L
        val ts = (local["series_resume_${seriesId}_timestamp"]?.value as? Number)?.toLong() ?: 0L

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
        if (email.isBlank()) return emptyList()
        val local = loadLocalSync(email)
        val items = mutableListOf<DesktopContinueWatchingItem>()
        val movieIds = movies.map { it.id }.toSet()
        var hasPurgedCorruptedKeys = false

        for (movie in movies) {
            val pos = (local["${movie.id}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = (local["${movie.id}_dur"]?.value as? Number)?.toLong() ?: movie.durationMs
            val fraction = (local["${movie.id}_fraction"]?.value as? Number)?.toFloat()
                ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()) else 0f
            val ts = (local["${movie.id}_timestamp"]?.value as? Number)?.toLong() ?: 0L

            if (pos >= 5000L && fraction in 0.01f..0.92f && ts > 0L) {
                items.add(
                    DesktopContinueWatchingItem(
                        mediaId = movie.id,
                        title = movie.title,
                        subtitle = movie.genre.ifBlank { "Movie" },
                        thumbnailUrl = movie.thumbnailUrl ?: "",
                        fallbackThumbnailUrl = movie.fallbackThumbnailUrl ?: "",
                        positionMs = pos,
                        durationMs = dur,
                        progressFraction = fraction.coerceIn(0.02f, 1f),
                        isSeries = false,
                        timestamp = ts
                    )
                )
            }
        }

        for (s in series) {
            val epId = local["series_resume_${s.id}_episode_id"]?.value as? String ?: continue
            val isMarkedMovie = local["${epId}_is_series"]?.value == false
            if (movieIds.contains(epId) || isMarkedMovie) continue

            val rawSNum = (local["series_resume_${s.id}_season_num"]?.value as? Number)?.toInt() ?: 1
            val rawENum = (local["series_resume_${s.id}_episode_num"]?.value as? Number)?.toInt() ?: 1
            val pos = (local["series_resume_${s.id}_pos_ms"]?.value as? Number)?.toLong() ?: 0L
            val dur = (local["series_resume_${s.id}_dur_ms"]?.value as? Number)?.toLong() ?: 0L
            val ts = (local["series_resume_${s.id}_timestamp"]?.value as? Number)?.toLong() ?: 0L

            val cachedSeries = DesktopSeriesCache.get(email, s.id)
            val allEpisodes = cachedSeries?.seasons?.flatMap { it.episodes } ?: emptyList()
            val targetEp = allEpisodes.find { it.id == epId }

            if (allEpisodes.isNotEmpty() && targetEp == null) continue

            val sNum = if (rawSNum >= 1) rawSNum else targetEp?.seasonNumber ?: 1
            val eNum = if (rawENum >= 1) rawENum else targetEp?.episodeNumber ?: 1
            val epTitle = targetEp?.title ?: local["${epId}_title"]?.value as? String ?: "Episode $eNum"
            val epThumb = targetEp?.thumbnailUrl ?: s.thumbnailUrl

            val effectiveDur = when {
                dur > 0L -> dur
                (targetEp?.durationMs ?: 0L) > 0L -> targetEp!!.durationMs
                else -> 45L * 60000L
            }
            val fraction = (pos.toFloat() / effectiveDur.toFloat()).coerceIn(0f, 1f)

            val code = "S${sNum}E${eNum}"
            val subtitle = if (epTitle.isNotBlank()) "$code • $epTitle" else code

            val isOngoing = fraction in 0.005f..0.95f || pos >= 5000L
            val isQueuedNext = (pos == 0L && fraction == 0f)
            if (ts > 0L && (isOngoing || isQueuedNext)) {
                items.add(
                    DesktopContinueWatchingItem(
                        mediaId = epId,
                        title = s.title,
                        subtitle = subtitle,
                        thumbnailUrl = (epThumb?.ifBlank { s.thumbnailUrl } ?: s.thumbnailUrl).orEmpty(),
                        fallbackThumbnailUrl = s.fallbackThumbnailUrl ?: "",
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

        if (hasPurgedCorruptedKeys) {
            saveLocal(email, local)
        }

        return items.sortedByDescending { it.timestamp }.take(10)
    }

    fun getAllWatchHistory(
        email: String,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ): List<DesktopContinueWatchingItem> {
        if (email.isBlank()) return emptyList()
        val local = loadLocalSync(email)
        val items = mutableListOf<DesktopContinueWatchingItem>()

        val fileIds = local.keys
            .filter { it.endsWith("_timestamp") && !it.startsWith("series_resume_") }
            .map { it.removeSuffix("_timestamp") }
            .distinct()

        for (fileId in fileIds) {
            val title = local["${fileId}_title"]?.value as? String ?: ""
            val pos = (local["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = (local["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
            val rawFraction = (local["${fileId}_fraction"]?.value as? Number)?.toFloat()
                ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()) else 0f
            val ts = (local["${fileId}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            val isSeries = (local["${fileId}_is_series"]?.value as? Boolean) == true
            val seriesId = local["${fileId}_series_id"]?.value as? String
            val sNum = (local["${fileId}_season_num"]?.value as? Number)?.toInt()
            val eNum = (local["${fileId}_episode_num"]?.value as? Number)?.toInt()

            if (ts <= 0L) continue

            val fraction = rawFraction.coerceIn(0f, 1f)

            if (isSeries) {
                val matchedSeries = series.find { it.id == seriesId }
                val seriesTitle = matchedSeries?.title ?: title.ifBlank { "Series" }
                val cachedSeries = seriesId?.let { DesktopSeriesCache.get(email, it) }
                val targetEp = cachedSeries?.seasons?.flatMap { it.episodes }?.find { it.id == fileId }

                val epTitle = targetEp?.title ?: title.ifBlank { "Episode ${eNum ?: 1}" }
                val epThumb = targetEp?.thumbnailUrl ?: matchedSeries?.thumbnailUrl ?: ""
                val effectiveDur = if (dur > 0L) dur else targetEp?.durationMs ?: 0L

                val code = if (sNum != null && eNum != null) "S${sNum}E${eNum}" else ""
                val subtitle = when {
                    code.isNotBlank() && epTitle.isNotBlank() -> "$code • $epTitle"
                    code.isNotBlank() -> code
                    else -> epTitle
                }

                items.add(
                    DesktopContinueWatchingItem(
                        mediaId = fileId,
                        title = seriesTitle,
                        subtitle = subtitle,
                        thumbnailUrl = epThumb,
                        fallbackThumbnailUrl = matchedSeries?.fallbackThumbnailUrl ?: "",
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
                )
            } else {
                val matchedMovie = movies.find { it.id == fileId }
                val movieTitle = matchedMovie?.title ?: title.ifBlank { "Movie" }
                val movieThumb = matchedMovie?.thumbnailUrl ?: ""
                val effectiveDur = if (dur > 0L) dur else matchedMovie?.durationMs ?: 0L
                val subtitle = matchedMovie?.genre?.ifBlank { "Movie" } ?: "Movie"

                items.add(
                    DesktopContinueWatchingItem(
                        mediaId = fileId,
                        title = movieTitle,
                        subtitle = subtitle,
                        thumbnailUrl = movieThumb,
                        fallbackThumbnailUrl = matchedMovie?.fallbackThumbnailUrl ?: "",
                        positionMs = pos,
                        durationMs = effectiveDur,
                        progressFraction = fraction,
                        isSeries = false,
                        timestamp = ts
                    )
                )
            }
        }

        return items.sortedByDescending { it.timestamp }
    }

    /**
     * Retrieves the most recently watched movie with its saved playback position.
     * Returns null if no movie has been watched yet.
     */
    fun getLastWatchedMovie(
        email: String,
        movies: List<DesktopMediaItem>
    ): DesktopContinueWatchingItem? {
        if (email.isBlank() || movies.isEmpty()) return null
        val local = loadLocalSync(email)
        var latestItem: DesktopContinueWatchingItem? = null
        var maxTs = -1L

        for (movie in movies) {
            val pos = (local["${movie.id}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = (local["${movie.id}_dur"]?.value as? Number)?.toLong() ?: movie.durationMs
            val fraction = (local["${movie.id}_fraction"]?.value as? Number)?.toFloat()
                ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()) else 0f
            val ts = (local["${movie.id}_timestamp"]?.value as? Number)?.toLong() ?: 0L

            if (ts > maxTs && pos > 0L && fraction in 0.01f..0.98f) {
                maxTs = ts
                val remainingMs = (dur - pos).coerceAtLeast(0L)
                val remainingStr = formatRemainingTime(remainingMs)

                latestItem = DesktopContinueWatchingItem(
                    mediaId = movie.id,
                    title = movie.title,
                    subtitle = if (remainingStr.isNotBlank()) "$remainingStr left" else movie.genre,
                    thumbnailUrl = movie.thumbnailUrl ?: "",
                    fallbackThumbnailUrl = movie.fallbackThumbnailUrl ?: "",
                    positionMs = pos,
                    durationMs = dur,
                    progressFraction = fraction.coerceIn(0.01f, 1f),
                    isSeries = false,
                    timestamp = ts
                )
            }
        }
        return latestItem
    }

    /**
     * Retrieves the most recently watched series and its exact episode with saved playback position.
     * Returns null if no series has been watched yet.
     */
    fun getLastWatchedSeries(
        email: String,
        series: List<DesktopMediaItem>
    ): DesktopContinueWatchingItem? {
        if (email.isBlank() || series.isEmpty()) return null
        val local = loadLocalSync(email)
        var latestItem: DesktopContinueWatchingItem? = null
        var maxTs = -1L

        for (s in series) {
            val epId = local["series_resume_${s.id}_episode_id"]?.value as? String ?: continue
            val ts = (local["series_resume_${s.id}_timestamp"]?.value as? Number)?.toLong() ?: 0L

            if (local["${epId}_is_series"]?.value == false) continue

            if (ts > maxTs) {
                val rawSNum = (local["series_resume_${s.id}_season_num"]?.value as? Number)?.toInt() ?: 1
                val rawENum = (local["series_resume_${s.id}_episode_num"]?.value as? Number)?.toInt() ?: 1
                val pos = (local["series_resume_${s.id}_pos_ms"]?.value as? Number)?.toLong() ?: 0L
                val dur = (local["series_resume_${s.id}_dur_ms"]?.value as? Number)?.toLong() ?: 0L

                val cachedSeries = DesktopSeriesCache.get(email, s.id)
                val allEpisodes = cachedSeries?.seasons?.flatMap { it.episodes } ?: emptyList()
                val targetEp = allEpisodes.find { it.id == epId }

                if (allEpisodes.isNotEmpty() && targetEp == null) continue

                val sNum = if (rawSNum >= 1) rawSNum else targetEp?.seasonNumber ?: 1
                val eNum = if (rawENum >= 1) rawENum else targetEp?.episodeNumber ?: 1
                val epTitle = targetEp?.title ?: local["${epId}_title"]?.value as? String ?: "Episode $eNum"
                val epThumb = targetEp?.thumbnailUrl ?: s.thumbnailUrl
                val effectiveDur = if (dur > 0L) dur else targetEp?.durationMs ?: 0L
                val fraction = if (effectiveDur > 0L) (pos.toFloat() / effectiveDur.toFloat()).coerceIn(0f, 1f) else 0f

                val code = "S${sNum}E${eNum}"
                val subtitle = if (epTitle.isNotBlank()) "$code • $epTitle" else code

                maxTs = ts
                latestItem = DesktopContinueWatchingItem(
                    mediaId = epId,
                    title = s.title,
                    subtitle = subtitle,
                    thumbnailUrl = epThumb?.ifBlank { s.thumbnailUrl } ?: s.thumbnailUrl ?: "",
                    fallbackThumbnailUrl = s.fallbackThumbnailUrl ?: "",
                    positionMs = pos,
                    durationMs = effectiveDur,
                    progressFraction = if (pos > 0L) fraction.coerceIn(0.02f, 1f) else 0.02f,
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

    /**
     * Removes a single item and its associated keys from watch history.
     */
    suspend fun removeHistoryItem(email: String, fileId: String) = withContext(Dispatchers.IO) {
        if (email.isBlank() || fileId.isBlank()) return@withContext
        val local = loadLocalSync(email)
        synchronized(local) {
            val keysToRemove = local.keys.filter { it.startsWith("${fileId}_") }.toList()
            keysToRemove.forEach { local.remove(it) }

            // If a series resume pointer pointed to this file, clean it up
            val resumePointersToRemove = local.entries
                .filter { it.key.startsWith("series_resume_") && it.key.endsWith("_episode_id") && it.value.value == fileId }
                .map { it.key.removeSuffix("_episode_id") }
                .toList()

            for (prefix in resumePointersToRemove) {
                local.remove("${prefix}_episode_id")
                local.remove("${prefix}_season_num")
                local.remove("${prefix}_episode_num")
                local.remove("${prefix}_pos_ms")
                local.remove("${prefix}_dur_ms")
                local.remove("${prefix}_timestamp")
            }
        }
        saveLocal(email, local)
    }

    /**
     * Clears all watch history records for the user.
     */
    suspend fun clearAllHistory(email: String) = withContext(Dispatchers.IO) {
        clearLocalHistory(email)
    }

    /**
     * Synchronously clears local watch history cache and disk file for the given user.
     */
    fun clearLocalHistory(email: String) {
        if (email.isBlank()) return
        val local = loadLocalSync(email)
        synchronized(local) {
            local.clear()
        }
        saveLocal(email, local)
    }
}
