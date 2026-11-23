package com.clotube.desktop.data

import com.clotube.desktop.auth.DesktopAuthManager
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class DesktopSyncPriority(val level: Int) {
    CRITICAL_WATCH_STATE(1),
    TIMESTAMPS_AND_SEEK(2),
    BACKGROUND_SYNC(3)
}

object DesktopWatchHistorySync {
    private const val ACTIVE_SYNC_FILE = "clotube_active_sync.json"
    private const val ARCHIVE_SYNC_FILE = "clotube_archive.json"
    private const val CLOUD_PULL_COOLDOWN_MS = 3_000L
    private const val PROGRESS_UPLOAD_INTERVAL_MS = 8_000L
    private const val MAX_V2_PAYLOAD_BYTES = 28 * 1024

    private val sharedHttpTransport by lazy { GoogleNetHttpTransport.newTrustedTransport() }
    private val sharedJsonFactory by lazy { GsonFactory.getDefaultInstance() }
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cachedActiveFileId = AtomicReference<String?>(null)
    private val cachedArchiveFileId = AtomicReference<String?>(null)
    private val cachedHistoryFolderId = AtomicReference<String?>(null)
    private val uploadMutex = Mutex()
    private val lastUploadTime = AtomicLong(0L)
    private val isPullingFromCloud = AtomicBoolean(false)
    private val lastCloudPullTimeMs = AtomicLong(0L)
    private var debouncedProgressJob: Job? = null
    private var backgroundPullJob: Job? = null

    @Volatile
    private var activeAccessToken: String = ""

    @Volatile
    private var activeUserEmail: String = ""

    fun configure(userEmail: String, accessToken: String) {
        val normalizedEmail = userEmail.trim()
        if (normalizedEmail != activeUserEmail) {
            invalidateCache()
            DesktopWatchHistoryManager.bindProfile(normalizedEmail)
        }
        activeUserEmail = normalizedEmail
        if (accessToken.isNotBlank()) {
            activeAccessToken = accessToken
        }
        DesktopWatchHistoryManager.purgeAllLocalDiskCaches()
    }

    fun invalidateCache() {
        cachedActiveFileId.set(null)
        cachedArchiveFileId.set(null)
        cachedHistoryFolderId.set(null)
        lastCloudPullTimeMs.set(0L)
    }

    fun isInternetAvailable(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("8.8.8.8", 53), 1200)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun normalizeTimestampMs(raw: Long): Long {
        if (raw <= 0L) return 0L
        return if (raw < 10_000_000_000L) raw * 1000L else raw
    }

    private fun buildDriveService(overrideToken: String = ""): Drive? {
        return try {
            val credential = DesktopAuthManager.getCredential()
            val token = overrideToken.ifBlank { activeAccessToken }
            when {
                credential != null -> {
                    Drive.Builder(sharedHttpTransport, sharedJsonFactory, credential)
                        .setApplicationName("Clotube-Desktop")
                        .build()
                }
                token.isNotBlank() -> {
                    Drive.Builder(sharedHttpTransport, sharedJsonFactory) { request ->
                        request.headers.authorization = "Bearer $token"
                    }.setApplicationName("Clotube-Desktop").build()
                }
                else -> null
            }
        } catch (e: Exception) {
            println("[ClotubeSync] Failed building Drive client: ${e.message}")
            null
        }
    }

    private suspend fun resolveHistoryFolderId(drive: Drive): String {
        cachedHistoryFolderId.get()?.takeIf { it.isNotBlank() }?.let { return it }

        val hierarchyId = runCatching {
            DesktopDriveDirectoryManager.resolveHierarchy(drive).historyFolderId
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val resolved = hierarchyId ?: findOrCreateHistoryFolderFallback(drive)
        cachedHistoryFolderId.set(resolved)
        return resolved
    }

    private fun findOrCreateHistoryFolderFallback(drive: Drive): String {
        val rootRes = drive.files().list()
            .setQ("mimeType = 'application/vnd.google-apps.folder' and (name = 'clotube' or name = 'Clotube') and trashed = false")
            .setFields("files(id)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(5)
            .execute()
        val rootId = rootRes.files?.firstOrNull()?.id ?: run {
            val meta = com.google.api.services.drive.model.File().apply {
                name = "clotube"
                mimeType = "application/vnd.google-apps.folder"
            }
            drive.files().create(meta).setFields("id").setSupportsAllDrives(true).execute().id
        }

        val histRes = drive.files().list()
            .setQ("'$rootId' in parents and mimeType = 'application/vnd.google-apps.folder' and (name = 'History' or name = 'history') and trashed = false")
            .setFields("files(id)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(5)
            .execute()
        return histRes.files?.firstOrNull()?.id ?: run {
            val meta = com.google.api.services.drive.model.File().apply {
                name = "History"
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(rootId)
            }
            drive.files().create(meta).setFields("id").setSupportsAllDrives(true).execute().id
        }
    }

    private fun findFileInHistoryFolder(drive: Drive, historyFolderId: String, fileName: String): String? {
        val scopedRes = drive.files().list()
            .setQ("'$historyFolderId' in parents and name = '$fileName' and trashed = false")
            .setOrderBy("modifiedTime desc")
            .setFields("files(id, parents, modifiedTime)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(5)
            .execute()

        val scopedId = scopedRes.files?.firstOrNull()?.id
        if (!scopedId.isNullOrBlank()) return scopedId

        val globalRes = drive.files().list()
            .setQ("name = '$fileName' and trashed = false")
            .setOrderBy("modifiedTime desc")
            .setFields("files(id, parents, modifiedTime)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(5)
            .execute()

        val globalMatch = globalRes.files?.firstOrNull()
        if (globalMatch != null && !globalMatch.id.isNullOrBlank()) {
            globalMatch.parents?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                cachedHistoryFolderId.set(it)
            }
            return globalMatch.id
        }
        return null
    }

    private fun downloadJsonFile(drive: Drive, fileId: String): String? {
        return try {
            val out = ByteArrayOutputStream()
            drive.files().get(fileId)
                .setSupportsAllDrives(true)
                .executeMediaAndDownloadTo(out)
            out.toString("UTF-8").takeIf { it.isNotBlank() }
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 404) null else throw e
        }
    }

    /**
     * Compatibility alias for existing callers (DesktopMoviePlayerScreen, DesktopSeriesDetailScreen, App).
     */
    suspend fun pullAndMerge(email: String, accessToken: String, force: Boolean = true): Boolean {
        if (email.isNotBlank() || accessToken.isNotBlank()) {
            configure(email, accessToken)
        }
        return syncFromCloud(force = force)
    }

    /**
     * Authoritative pull from clotube/History/clotube_active_sync.json and clotube_archive.json.
     * Replaces volatile RAM state completely—zero local disk caching or stale merging.
     */
    suspend fun syncFromCloud(force: Boolean = false, overrideToken: String = ""): Boolean = withContext(Dispatchers.IO) {
        if (overrideToken.isNotBlank()) {
            activeAccessToken = overrideToken
        }
        val now = System.currentTimeMillis()
        if (!force && (now - lastCloudPullTimeMs.get() < CLOUD_PULL_COOLDOWN_MS)) {
            return@withContext true
        }
        if (!isPullingFromCloud.compareAndSet(false, true)) {
            return@withContext false
        }

        try {
            val drive = buildDriveService(overrideToken) ?: return@withContext false
            val historyFolderId = resolveHistoryFolderId(drive)

            val activeFileId = cachedActiveFileId.get()
                ?: findFileInHistoryFolder(drive, historyFolderId, ACTIVE_SYNC_FILE)?.also {
                    cachedActiveFileId.set(it)
                }

            val archiveFileId = cachedArchiveFileId.get()
                ?: findFileInHistoryFolder(drive, historyFolderId, ARCHIVE_SYNC_FILE)?.also {
                    cachedArchiveFileId.set(it)
                }

            val activeJson = activeFileId?.let { downloadJsonFile(drive, it) }
            val archiveJson = archiveFileId?.let { downloadJsonFile(drive, it) }

            val activeEntries = if (activeJson != null) parseActiveSyncJson(activeJson) else emptyMap()
            val (archivedIds, archivedMetadata) = if (archiveJson != null) {
                parseArchiveJson(archiveJson)
            } else {
                emptySet<String>() to emptyMap()
            }

            DesktopWatchHistoryManager.replaceAllFromDrive(activeEntries, archivedIds, archivedMetadata)
            lastCloudPullTimeMs.set(System.currentTimeMillis())
            println("[ClotubeSync] Pulled directly from Drive: activeEntries=${activeEntries.size}, archivedIds=${archivedIds.size}")
            true
        } catch (e: Exception) {
            println("[ClotubeSync] Direct Drive pull failed: ${e.message}")
            false
        } finally {
            isPullingFromCloud.set(false)
        }
    }

    private fun parseActiveSyncJson(remoteJson: String): Map<String, DesktopTypedEntry> {
        val result = mutableMapOf<String, DesktopTypedEntry>()
        val root = JsonParser.parseString(remoteJson).asJsonObject
        val updatedAt = normalizeTimestampMs(root.get("updatedAt")?.asLong ?: 0L)
        if (updatedAt > 0L) {
            result["global_latest_timestamp"] = DesktopTypedEntry("long", updatedAt)
        }

        // 1. Parse watchedIds
        root.getAsJsonArray("watchedIds")?.forEach { el ->
            val wid = el.asString.trim()
            if (wid.isNotBlank()) {
                result["${wid}_is_watched"] = DesktopTypedEntry("boolean", true)
                result["${wid}_fraction"] = DesktopTypedEntry("float", 1.0f)
            }
        }

        // 2. Parse active items (supports both compact keys t/p/d/ts/w/sid/sn/en and verbose keys)
        val activeTitlesById = mutableMapOf<String, String>()
        root.getAsJsonArray("active")?.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val obj = el.asJsonObject
            val id = obj.get("id")?.asString?.trim().orEmpty()
            if (id.isBlank()) return@forEach

            val title = obj.get("t")?.asString ?: obj.get("title")?.asString ?: ""
            val pos = obj.get("p")?.asLong ?: obj.get("positionMs")?.asLong ?: 0L
            val dur = obj.get("d")?.asLong ?: obj.get("durationMs")?.asLong ?: 0L
            val rawTs = obj.get("ts")?.asLong ?: obj.get("timestamp")?.asLong ?: 0L
            val ts = normalizeTimestampMs(rawTs)
            val isWatched = obj.get("w")?.asBoolean ?: obj.get("isWatched")?.asBoolean ?: false
            val seriesId = (obj.get("sid")?.asString ?: obj.get("seriesId")?.asString)?.trim()?.ifBlank { null }
            val sNum = obj.get("sn")?.asInt ?: obj.get("s")?.asInt ?: obj.get("seasonNumber")?.asInt ?: 0
            val eNum = obj.get("en")?.asInt ?: obj.get("e")?.asInt ?: obj.get("episodeNumber")?.asInt ?: 0
            val isSeries = !seriesId.isNullOrBlank() || sNum > 0 || eNum > 0
            val frac = if (isWatched) 1.0f else if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f

            if (title.isNotBlank()) activeTitlesById[id] = title

            result["${id}_title"] = DesktopTypedEntry("string", title)
            result["${id}_pos"] = DesktopTypedEntry("long", pos)
            result["${id}_dur"] = DesktopTypedEntry("long", dur)
            result["${id}_fraction"] = DesktopTypedEntry("float", frac)
            result["${id}_timestamp"] = DesktopTypedEntry("long", ts)
            result["${id}_is_watched"] = DesktopTypedEntry("boolean", isWatched)
            result["${id}_is_series"] = DesktopTypedEntry("boolean", isSeries)
            if (!seriesId.isNullOrBlank()) result["${id}_series_id"] = DesktopTypedEntry("string", seriesId)
            if (sNum > 0) result["${id}_season_num"] = DesktopTypedEntry("int", sNum)
            if (eNum > 0) result["${id}_episode_num"] = DesktopTypedEntry("int", eNum)
        }

        // 3. Parse resume pointers ("resume" map) and link directly onto target episodeId
        root.getAsJsonObject("resume")?.entrySet()?.forEach { (seriesId, valEl) ->
            if (seriesId.isBlank() || !valEl.isJsonObject) return@forEach
            val obj = valEl.asJsonObject
            val epId = (obj.get("id")?.asString ?: obj.get("episodeId")?.asString)?.trim().orEmpty()
            if (epId.isBlank()) return@forEach

            val sNum = obj.get("s")?.asInt ?: obj.get("seasonNumber")?.asInt ?: 1
            val eNum = obj.get("e")?.asInt ?: obj.get("episodeNumber")?.asInt ?: 1
            val pos = obj.get("p")?.asLong ?: obj.get("positionMs")?.asLong ?: 0L
            val dur = obj.get("d")?.asLong ?: obj.get("durationMs")?.asLong ?: 0L
            val ts = normalizeTimestampMs(obj.get("ts")?.asLong ?: obj.get("timestamp")?.asLong ?: 0L)
            val frac = if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
            val epTitle = activeTitlesById[epId].orEmpty()

            val p = "series_resume_${seriesId}"
            result["${p}_episode_id"] = DesktopTypedEntry("string", epId)
            result["${p}_season_num"] = DesktopTypedEntry("int", sNum)
            result["${p}_episode_num"] = DesktopTypedEntry("int", eNum)
            result["${p}_pos_ms"] = DesktopTypedEntry("long", pos)
            result["${p}_dur_ms"] = DesktopTypedEntry("long", dur)
            result["${p}_fraction"] = DesktopTypedEntry("float", frac)
            result["${p}_timestamp"] = DesktopTypedEntry("long", ts)
            if (epTitle.isNotBlank()) result["${p}_episode_title"] = DesktopTypedEntry("string", epTitle)

            val existingEpTs = (result["${epId}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            if (ts >= existingEpTs) {
                result["${epId}_timestamp"] = DesktopTypedEntry("long", ts)
                result["${epId}_pos"] = DesktopTypedEntry("long", pos)
                result["${epId}_dur"] = DesktopTypedEntry("long", dur)
                result["${epId}_fraction"] = DesktopTypedEntry("float", frac)
                val isCompleted = dur > 0L && pos >= (dur * 0.90f)
                result["${epId}_is_watched"] = DesktopTypedEntry("boolean", isCompleted)
            }
            result["${epId}_is_series"] = DesktopTypedEntry("boolean", true)
            result["${epId}_series_id"] = DesktopTypedEntry("string", seriesId)
            if (sNum > 0) result["${epId}_season_num"] = DesktopTypedEntry("int", sNum)
            if (eNum > 0) result["${epId}_episode_num"] = DesktopTypedEntry("int", eNum)
            if (epTitle.isNotBlank() && !result.containsKey("${epId}_title")) {
                result["${epId}_title"] = DesktopTypedEntry("string", epTitle)
            }
        }

        return result
    }

    private fun parseArchiveJson(archiveJson: String): Pair<Set<String>, Map<String, DesktopArchivedHistoryEntry>> {
        val ids = mutableSetOf<String>()
        val items = mutableMapOf<String, DesktopArchivedHistoryEntry>()
        try {
            val root = JsonParser.parseString(archiveJson).asJsonObject
            root.getAsJsonArray("watchedIds")?.forEach { el ->
                val id = el.asString.trim()
                if (id.isNotBlank()) ids.add(id)
            }
            root.getAsJsonArray("items")?.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val obj = el.asJsonObject
                val id = obj.get("id")?.asString?.trim().orEmpty()
                if (id.isBlank()) return@forEach

                val title = obj.get("t")?.asString ?: obj.get("title")?.asString ?: ""
                val pos = obj.get("p")?.asLong ?: obj.get("positionMs")?.asLong ?: 0L
                val dur = obj.get("d")?.asLong ?: obj.get("durationMs")?.asLong ?: 0L
                val ts = normalizeTimestampMs(obj.get("ts")?.asLong ?: obj.get("timestamp")?.asLong ?: 0L)
                val isWatched = obj.get("w")?.asBoolean ?: obj.get("isWatched")?.asBoolean ?: true
                val seriesId = (obj.get("sid")?.asString ?: obj.get("seriesId")?.asString)?.trim()?.ifBlank { null }
                val sNum = obj.get("sn")?.asInt ?: obj.get("s")?.asInt ?: obj.get("seasonNumber")?.asInt ?: 0
                val eNum = obj.get("en")?.asInt ?: obj.get("e")?.asInt ?: obj.get("episodeNumber")?.asInt ?: 0

                if (isWatched) ids.add(id)
                items[id] = DesktopArchivedHistoryEntry(
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
            }
        } catch (e: Exception) {
            println("[ClotubeSync] Could not parse $ARCHIVE_SYNC_FILE:${e.message}")
        }
        return ids to items
    }

    private fun buildActiveSyncJsonString(entries: Map<String, DesktopTypedEntry>): String {
        val itemSuffixes = listOf(
            "_title", "_pos", "_dur", "_fraction", "_timestamp",
            "_is_watched", "_is_series", "_series_id", "_season_num", "_episode_num"
        )
        val itemIds = linkedSetOf<String>()
        val seriesResumeIds = linkedSetOf<String>()

        for (key in entries.keys) {
            if (key.startsWith("series_resume_") && key.endsWith("_episode_id")) {
                val sid = key.removePrefix("series_resume_").removeSuffix("_episode_id")
                if (sid.isNotBlank()) seriesResumeIds.add(sid)
                continue
            }
            if (key.startsWith("series_resume_") || key.startsWith("global_")) continue
            val suffix = itemSuffixes.firstOrNull { key.endsWith(it) } ?: continue
            val id = key.removeSuffix(suffix)
            if (id.isNotBlank()) itemIds.add(id)
        }

        val watchedArr = JsonArray()
        val activeArr = JsonArray()

        val sortedItems = itemIds.mapNotNull { id ->
            val title = (entries["${id}_title"]?.value as? String).orEmpty()
            val pos = (entries["${id}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = (entries["${id}_dur"]?.value as? Number)?.toLong() ?: 0L
            val frac = (entries["${id}_fraction"]?.value as? Number)?.toFloat() ?: 0f
            val ts = (entries["${id}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            val explicitWatched = entries["${id}_is_watched"]?.value as? Boolean
            val isWatched = explicitWatched ?: (frac >= 0.90f)
            val seriesId = (entries["${id}_series_id"]?.value as? String)?.ifBlank { null }
            val sNum = (entries["${id}_season_num"]?.value as? Number)?.toInt()
            val eNum = (entries["${id}_episode_num"]?.value as? Number)?.toInt()

            if (isWatched) watchedArr.add(id)
            if (ts <= 0L && pos <= 0L && dur <= 0L && !isWatched) return@mapNotNull null

            ts to JsonObject().apply {
                addProperty("id", id)
                addProperty("t", title)
                addProperty("p", pos)
                addProperty("d", dur)
                addProperty("ts", ts)
                addProperty("w", isWatched)
                if (!seriesId.isNullOrBlank()) addProperty("sid", seriesId)
                if (sNum != null && sNum > 0) addProperty("sn", sNum)
                if (eNum != null && eNum > 0) addProperty("en", eNum)
            }
        }.sortedByDescending { it.first }

        for ((_, obj) in sortedItems) {
            activeArr.add(obj)
            if (gson.toJson(activeArr).toByteArray(Charsets.UTF_8).size > MAX_V2_PAYLOAD_BYTES) {
                activeArr.remove(activeArr.size() - 1)
                break
            }
        }

        val resumeObj = JsonObject()
        for (sid in seriesResumeIds) {
            val prefix = "series_resume_$sid"
            val epId = (entries["${prefix}_episode_id"]?.value as? String)?.trim().orEmpty()
            if (epId.isBlank()) continue
            val sNum = (entries["${prefix}_season_num"]?.value as? Number)?.toInt() ?: 1
            val eNum = (entries["${prefix}_episode_num"]?.value as? Number)?.toInt() ?: 1
            val posMs = (entries["${prefix}_pos_ms"]?.value as? Number)?.toLong() ?: 0L
            val durMs = (entries["${prefix}_dur_ms"]?.value as? Number)?.toLong() ?: 0L
            val ts = (entries["${prefix}_timestamp"]?.value as? Number)?.toLong() ?: 0L

            resumeObj.add(sid, JsonObject().apply {
                addProperty("id", epId)
                addProperty("s", sNum)
                addProperty("e", eNum)
                addProperty("p", posMs)
                addProperty("d", durMs)
                addProperty("ts", ts)
            })
        }

        val root = JsonObject().apply {
            addProperty("v", 2)
            addProperty("updatedAt", System.currentTimeMillis())
            add("active", activeArr)
            add("resume", resumeObj)
            add("watchedIds", watchedArr)
        }
        return gson.toJson(root)
    }

    private fun buildArchiveJsonString(
        archiveIds: Set<String>,
        archivedItems: Map<String, DesktopArchivedHistoryEntry>
    ): String {
        val arr = JsonArray()
        archiveIds.sorted().forEach { arr.add(it) }

        val itemsArr = JsonArray()
        archivedItems.values.sortedByDescending { it.timestamp }.forEach { item ->
            itemsArr.add(JsonObject().apply {
                addProperty("id", item.id)
                addProperty("t", item.title)
                addProperty("p", item.positionMs)
                addProperty("d", item.durationMs)
                addProperty("ts", item.timestamp)
                addProperty("w", item.isWatched)
                if (!item.seriesId.isNullOrBlank()) addProperty("sid", item.seriesId)
                if (item.seasonNumber > 0) addProperty("sn", item.seasonNumber)
                if (item.episodeNumber > 0) addProperty("en", item.episodeNumber)
            })
        }

        val root = JsonObject().apply {
            addProperty("v", 2)
            addProperty("updatedAt", System.currentTimeMillis())
            add("watchedIds", arr)
            add("items", itemsArr)
        }
        return gson.toJson(root)
    }

    suspend fun executeDirectUpload(
        priority: DesktopSyncPriority = DesktopSyncPriority.CRITICAL_WATCH_STATE,
        updateArchive: Boolean = false,
        overrideToken: String = ""
    ): Boolean = uploadMutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val drive = buildDriveService(overrideToken) ?: return@withContext false
                val historyFolderId = resolveHistoryFolderId(drive)

                val activeSnapshot = DesktopWatchHistoryManager.snapshotEntries()
                val activeJson = buildActiveSyncJsonString(activeSnapshot)
                val activeMedia = ByteArrayContent.fromString("application/json", activeJson)

                val activeOk = upsertHistoryFile(
                    drive = drive,
                    historyFolderId = historyFolderId,
                    fileName = ACTIVE_SYNC_FILE,
                    cachedIdRef = cachedActiveFileId,
                    mediaContent = activeMedia,
                    priority = priority
                )

                if (updateArchive) {
                    val archiveJson = buildArchiveJsonString(
                        archiveIds = DesktopWatchHistoryManager.snapshotArchivedIds(),
                        archivedItems = DesktopWatchHistoryManager.snapshotArchivedItems()
                    )
                    val archiveMedia = ByteArrayContent.fromString("application/json", archiveJson)
                    upsertHistoryFile(
                        drive = drive,
                        historyFolderId = historyFolderId,
                        fileName = ARCHIVE_SYNC_FILE,
                        cachedIdRef = cachedArchiveFileId,
                        mediaContent = archiveMedia,
                        priority = priority
                    )
                }

                lastUploadTime.set(System.currentTimeMillis())
                activeOk
            } catch (e: Exception) {
                println("[ClotubeSync] Direct Drive upload failed: ${e.message}")
                false
            }
        }
    }

    private fun upsertHistoryFile(
        drive: Drive,
        historyFolderId: String,
        fileName: String,
        cachedIdRef: AtomicReference<String?>,
        mediaContent: ByteArrayContent,
        priority: DesktopSyncPriority
    ): Boolean {
        var fileId = cachedIdRef.get() ?: findFileInHistoryFolder(drive, historyFolderId, fileName)
        if (fileId != null) {
            try {
                drive.files().update(fileId, null, mediaContent)
                    .setSupportsAllDrives(true)
                    .execute()
                cachedIdRef.set(fileId)
                println("[ClotubeSync] SUCCESS! [${priority.name}] Updated $fileName directly on Drive ($fileId)")
                return true
            } catch (e: GoogleJsonResponseException) {
                if (e.statusCode == 404 || e.statusCode == 403) {
                    cachedIdRef.set(null)
                    fileId = null
                } else {
                    throw e
                }
            }
        }

        val metadata = com.google.api.services.drive.model.File().apply {
            name = fileName
            mimeType = "application/json"
            parents = listOf(historyFolderId)
        }
        val created = drive.files().create(metadata, mediaContent)
            .setSupportsAllDrives(true)
            .execute()
        cachedIdRef.set(created.id)
        println("[ClotubeSync] SUCCESS! [${priority.name}] Created $fileName directly on Drive (${created.id})")
        return true
    }

    /**
     * Compatibility alias for existing Desktop callers.
     */
    suspend fun pushLocalHistory(email: String, accessToken: String, force: Boolean = false): Boolean {
        if (email.isNotBlank() || accessToken.isNotBlank()) {
            configure(email, accessToken)
        }
        return executeDirectUpload(
            priority = if (force) DesktopSyncPriority.CRITICAL_WATCH_STATE else DesktopSyncPriority.TIMESTAMPS_AND_SEEK,
            updateArchive = false,
            overrideToken = accessToken
        )
    }

    fun pushHistoryAsync(force: Boolean = false, updateArchive: Boolean = false) {
        if (force) {
            debouncedProgressJob?.cancel()
            syncScope.launch {
                executeDirectUpload(DesktopSyncPriority.CRITICAL_WATCH_STATE, updateArchive = updateArchive)
            }
        } else {
            val now = System.currentTimeMillis()
            if (now - lastUploadTime.get() >= PROGRESS_UPLOAD_INTERVAL_MS) {
                debouncedProgressJob?.cancel()
                syncScope.launch {
                    executeDirectUpload(DesktopSyncPriority.TIMESTAMPS_AND_SEEK, updateArchive = false)
                }
                return
            }
            if (debouncedProgressJob?.isActive == true) return
            debouncedProgressJob = syncScope.launch {
                delay(PROGRESS_UPLOAD_INTERVAL_MS)
                executeDirectUpload(DesktopSyncPriority.TIMESTAMPS_AND_SEEK, updateArchive = false)
            }
        }
    }

    fun flushBlockingOnWindowClose() {
        debouncedProgressJob?.cancel()
        if (DesktopWatchHistoryManager.snapshotEntries().isEmpty()) return
        runCatching {
            runBlocking(Dispatchers.IO) {
                executeDirectUpload(DesktopSyncPriority.CRITICAL_WATCH_STATE, updateArchive = false)
            }
        }
    }

    fun startAutoRecoveryWorker(
        email: String = "",
        accessToken: String = "",
        tokenProvider: (() -> String)? = null
    ) {
        val effectiveToken = if (accessToken.isNotBlank()) accessToken else tokenProvider?.invoke().orEmpty()
        if (email.isNotBlank() || effectiveToken.isNotBlank()) {
            configure(email, effectiveToken)
        }
        if (backgroundPullJob?.isActive == true) return
        backgroundPullJob = syncScope.launch {
            while (isActive) {
                delay(30_000L)
                val currentToken = tokenProvider?.invoke().orEmpty()
                if (currentToken.isNotBlank()) {
                    activeAccessToken = currentToken
                }
                if (activeUserEmail.isNotBlank() && isInternetAvailable()) {
                    syncFromCloud(force = false)
                }
            }
        }
    }

    fun stopAutoRecoveryWorker() {
        backgroundPullJob?.cancel()
        backgroundPullJob = null
    }

    suspend fun deleteRemoteHistoryFiles(): Boolean = uploadMutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val drive = buildDriveService() ?: return@withContext false
                val historyFolderId = resolveHistoryFolderId(drive)
                listOf(
                    ACTIVE_SYNC_FILE to cachedActiveFileId,
                    ARCHIVE_SYNC_FILE to cachedArchiveFileId
                ).forEach { (fileName, ref) ->
                    val id = ref.get() ?: findFileInHistoryFolder(drive, historyFolderId, fileName)
                    if (id != null) {
                        runCatching {
                            drive.files().delete(id).setSupportsAllDrives(true).execute()
                            println("[ClotubeSync] Deleted $fileName from Drive ($id)")
                        }
                    }
                }
                invalidateCache()
                true
            } catch (e: Exception) {
                println("[ClotubeSync] Failed deleting Drive history files: ${e.message}")
                false
            }
        }
    }
}
