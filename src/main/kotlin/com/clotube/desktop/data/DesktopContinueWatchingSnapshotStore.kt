package com.clotube.desktop.data

import com.clotube.desktop.image.ArtworkProvenance
import com.clotube.desktop.image.DesktopCanonicalImageCache
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ContinueWatchingDisplayData(
    val mediaId: String,
    val seriesId: String? = null,
    val isSeries: Boolean = false,
    val displayHeader: String = "",
    val displayTitle: String = "",
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val canonicalArtworkUrl: String = "",
    val progressFraction: Float = 0f,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L,
    val confirmedEmpty: Boolean = false
)

data class ContinueWatchingShelfSnapshot(
    val items: List<DesktopContinueWatchingItem> = emptyList(),
    val updatedAt: Long = 0L,
    val confirmedEmpty: Boolean = false
)

object DesktopContinueWatchingSnapshotStore {
    const val SNAPSHOT_FILE_NAME = "cw_hero_snapshot.json"
    const val SHELF_SNAPSHOT_FILE_NAME = "cw_shelf_snapshot.json"
    private const val SCHEMA_VERSION = 1
    private const val MAX_SHELF_SNAPSHOT_ITEMS = 20

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val snapshotDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val ioScope = CoroutineScope(SupervisorJob() + snapshotDispatcher)
    private val profileLocks = ConcurrentHashMap<String, Any>()
    private val profileWriteSeq = ConcurrentHashMap<String, AtomicLong>()
    private val profileShelfWriteSeq = ConcurrentHashMap<String, AtomicLong>()
    private val memoryCache = ConcurrentHashMap<String, ContinueWatchingDisplayData>()
    private val shelfMemoryCache = ConcurrentHashMap<String, ContinueWatchingShelfSnapshot>()

    fun normalizeEpochMs(rawTs: Long): Long {
        return if (rawTs in 1L..9_999_999_999L) rawTs * 1000L else rawTs
    }

    fun sanitizeEmail(email: String): String {
        return DesktopProfileManager.sanitizeEmail(email).ifBlank { "default_user" }
    }

    fun resolveProfileDir(userEmail: String): File {
        return DesktopProfileManager.getProfileDir(userEmail)
    }

    fun getSnapshotFile(userEmail: String): File {
        return File(resolveProfileDir(userEmail), SNAPSHOT_FILE_NAME)
    }

    fun getShelfSnapshotFile(userEmail: String): File {
        return File(resolveProfileDir(userEmail), SHELF_SNAPSHOT_FILE_NAME)
    }

    fun isPoisonedDriveFrameUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.trim().lowercase()
        return lower.contains("googleusercontent.com") ||
            lower.contains("drive.google.com") ||
            lower.contains("docs.google.com") ||
            lower.contains("lh3.google.com") ||
            lower.startsWith("http://127.0.0.1") ||
            lower.startsWith("http://localhost")
    }

    fun sanitizeCanonicalArtworkUrl(
        candidateUrl: String?,
        existingUrl: String? = null
    ): String {
        val trimmedCandidate = candidateUrl?.trim().orEmpty()
        if (trimmedCandidate.isNotEmpty() && !isPoisonedDriveFrameUrl(trimmedCandidate)) {
            return trimmedCandidate
        }
        val trimmedExisting = existingUrl?.trim().orEmpty()
        if (trimmedExisting.isNotEmpty() && !isPoisonedDriveFrameUrl(trimmedExisting)) {
            return trimmedExisting
        }
        return ""
    }

    /**
     * Synchronous fast-path read (0-15 ms) executed on cold launch before Frame 1.
     */
    fun readSnapshot(userEmail: String): ContinueWatchingDisplayData? {
        if (userEmail.isBlank()) return null
        val key = sanitizeEmail(userEmail)
        memoryCache[key]?.let { return it }

        val lock = profileLocks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            memoryCache[key]?.let { return it }
            val file = getSnapshotFile(userEmail)
            if (!file.exists() || file.length() <= 0L) return null

            return try {
                val raw = file.readText(Charsets.UTF_8)
                val parsed = parseSnapshotJson(raw) ?: return null
                val sanitized = parsed.copy(
                    canonicalArtworkUrl = sanitizeCanonicalArtworkUrl(parsed.canonicalArtworkUrl),
                    updatedAt = normalizeEpochMs(parsed.updatedAt),
                    progressFraction = parsed.progressFraction.coerceIn(0f, 1f)
                )
                memoryCache[key] = sanitized
                sanitized
            } catch (e: Exception) {
                System.err.println("[CW-Snapshot] Failed to read snapshot for $key: ${e.message}")
                null
            }
        }
    }

    fun writeSnapshotAsync(userEmail: String, data: ContinueWatchingDisplayData) {
        if (userEmail.isBlank()) return
        val key = sanitizeEmail(userEmail)
        val seq = profileWriteSeq.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
        val prepared = prepareSanitizedRecord(userEmail, data)
        memoryCache[key] = prepared
        ioScope.launch {
            writeSnapshotBlockingInternal(userEmail, prepared, expectedSeq = seq)
        }
    }

    suspend fun writeSnapshot(userEmail: String, data: ContinueWatchingDisplayData) {
        if (userEmail.isBlank()) return
        withContext(Dispatchers.IO) {
            writeSnapshotBlocking(userEmail, data)
        }
    }

    /**
     * Atomic .tmp write-and-rename on disk. Safe to call on Dispatchers.IO or shutdown hook.
     */
    fun writeSnapshotBlocking(userEmail: String, data: ContinueWatchingDisplayData) {
        if (userEmail.isBlank()) return
        val key = sanitizeEmail(userEmail)
        val seq = profileWriteSeq.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
        writeSnapshotBlockingInternal(userEmail, data, expectedSeq = seq)
    }

    private fun writeSnapshotBlockingInternal(
        userEmail: String,
        data: ContinueWatchingDisplayData,
        expectedSeq: Long
    ) {
        val key = sanitizeEmail(userEmail)
        val lock = profileLocks.computeIfAbsent(key) { Any() }
        val seqCounter = profileWriteSeq.computeIfAbsent(key) { AtomicLong(0L) }
        synchronized(lock) {
            if (seqCounter.get() != expectedSeq) {
                return
            }

            val prepared = prepareSanitizedRecord(userEmail, data)
            memoryCache[key] = prepared

            val targetFile = getSnapshotFile(userEmail)
            targetFile.parentFile?.mkdirs()
            val tmpFile = File(targetFile.parentFile, "${SNAPSHOT_FILE_NAME}.tmp")

            try {
                val json = serializeSnapshotJson(prepared)
                FileOutputStream(tmpFile).use { fos ->
                    fos.write(json.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    try {
                        fos.fd.sync()
                    } catch (_: Throwable) {
                    }
                }
                try {
                    Files.move(
                        tmpFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: Throwable) {
                    Files.move(
                        tmpFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } catch (e: Exception) {
                System.err.println("[CW-Snapshot] Atomic write failed for $key: ${e.message}")
                try {
                    if (tmpFile.exists()) tmpFile.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun clearMemoryForProfileSwitch(userEmail: String? = null) {
        if (userEmail.isNullOrBlank()) {
            memoryCache.clear()
            shelfMemoryCache.clear()
        } else {
            val key = sanitizeEmail(userEmail)
            memoryCache.remove(key)
            shelfMemoryCache.remove(key)
        }
    }

    /**
     * Synchronous fast-path read (0-10 ms) for the Continue Watching shelf on cold launch.
     * Cross-reconciles against `cw_hero_snapshot.json`, sanitizes artwork URLs, and
     * pre-registers valid official URLs in [DesktopCanonicalImageCache].
     */
    fun readShelfSnapshot(userEmail: String): ContinueWatchingShelfSnapshot? {
        if (userEmail.isBlank()) return null
        val key = sanitizeEmail(userEmail)
        val heroSnap = readSnapshot(userEmail)

        shelfMemoryCache[key]?.let { cached ->
            val reconciled = reconcileShelfSnapshotWithHero(key, cached, heroSnap) ?: cached
            shelfMemoryCache[key] = reconciled
            registerShelfArtworkInCache(reconciled.items)
            return reconciled
        }

        val lock = profileLocks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            shelfMemoryCache[key]?.let { cached ->
                val reconciled = reconcileShelfSnapshotWithHero(key, cached, heroSnap) ?: cached
                shelfMemoryCache[key] = reconciled
                registerShelfArtworkInCache(reconciled.items)
                return reconciled
            }
            val file = getShelfSnapshotFile(userEmail)
            val loaded = if (file.exists() && file.length() > 0L) {
                try {
                    val raw = file.readText(Charsets.UTF_8)
                    val parsed = parseShelfSnapshotJson(raw)
                    if (parsed != null) {
                        val sanitizedItems = parsed.items
                            .take(MAX_SHELF_SNAPSHOT_ITEMS)
                            .map { sanitizeShelfItem(it, existingItem = null) }
                            .sortedByDescending { it.timestamp }
                        parsed.copy(
                            items = sanitizedItems,
                            updatedAt = normalizeEpochMs(parsed.updatedAt),
                            confirmedEmpty = parsed.confirmedEmpty && sanitizedItems.isEmpty()
                        )
                    } else null
                } catch (e: Exception) {
                    System.err.println("[CW-ShelfSnapshot] Failed to read shelf snapshot for $key: ${e.message}")
                    null
                }
            } else null

            val reconciled = reconcileShelfSnapshotWithHero(key, loaded, heroSnap) ?: return null
            registerShelfArtworkInCache(reconciled.items)
            shelfMemoryCache[key] = reconciled
            return reconciled
        }
    }

    private fun reconcileShelfSnapshotWithHero(
        profileKey: String,
        shelfSnap: ContinueWatchingShelfSnapshot?,
        heroSnap: ContinueWatchingDisplayData?
    ): ContinueWatchingShelfSnapshot? {
        if (heroSnap == null) return shelfSnap
        val heroTs = normalizeEpochMs(heroSnap.updatedAt)
        val shelfTs = normalizeEpochMs(shelfSnap?.updatedAt ?: 0L)

        if (heroSnap.confirmedEmpty && heroTs >= shelfTs) {
            return ContinueWatchingShelfSnapshot(
                items = emptyList(),
                updatedAt = heroTs,
                confirmedEmpty = true
            )
        }

        if (!heroSnap.confirmedEmpty && heroSnap.mediaId.isNotBlank()) {
            val matchingExisting = shelfSnap?.items?.firstOrNull {
                it.mediaId == heroSnap.mediaId ||
                    (!heroSnap.seriesId.isNullOrBlank() && it.seriesId == heroSnap.seriesId)
            }
            val needsHeroMerge = shelfSnap == null ||
                heroTs > shelfTs ||
                matchingExisting == null

            if (needsHeroMerge) {
                val safeArtwork = sanitizeCanonicalArtworkUrl(
                    candidateUrl = heroSnap.canonicalArtworkUrl,
                    existingUrl = matchingExisting?.thumbnailUrl?.ifBlank { matchingExisting.fallbackThumbnailUrl }
                )
                val safeFallback = sanitizeCanonicalArtworkUrl(
                    candidateUrl = matchingExisting?.fallbackThumbnailUrl,
                    existingUrl = safeArtwork
                )
                val epLabel = if (heroSnap.isSeries && heroSnap.seasonNumber != null && heroSnap.episodeNumber != null) {
                    val code = "S${heroSnap.seasonNumber}E${heroSnap.episodeNumber}"
                    val epName = heroSnap.episodeTitle?.takeIf { it.isNotBlank() }
                    if (epName != null) "$code • $epName" else code
                } else {
                    "Movie"
                }
                val effDur = heroSnap.durationMs.coerceAtLeast(0L)
                val effFrac = if (effDur > 0L) {
                    (heroSnap.currentPositionMs.toFloat() / effDur.toFloat()).coerceIn(0f, 1f)
                } else {
                    heroSnap.progressFraction.coerceIn(0f, 1f)
                }
                val heroAsShelfItem = DesktopContinueWatchingItem(
                    mediaId = heroSnap.mediaId,
                    title = heroSnap.seriesTitle?.takeIf { it.isNotBlank() && heroSnap.isSeries }
                        ?: heroSnap.displayTitle.ifBlank { heroSnap.episodeTitle ?: "Resume Watching" },
                    subtitle = epLabel,
                    thumbnailUrl = safeArtwork,
                    fallbackThumbnailUrl = safeFallback,
                    positionMs = heroSnap.currentPositionMs,
                    durationMs = effDur,
                    progressFraction = effFrac,
                    isSeries = heroSnap.isSeries,
                    seriesId = heroSnap.seriesId,
                    seasonNumber = heroSnap.seasonNumber,
                    episodeNumber = heroSnap.episodeNumber,
                    episodeTitle = heroSnap.episodeTitle.orEmpty(),
                    timestamp = heroTs
                )

                val remaining = shelfSnap?.items.orEmpty().filterNot { existing ->
                    existing.mediaId == heroSnap.mediaId ||
                        (!heroSnap.seriesId.isNullOrBlank() && existing.seriesId == heroSnap.seriesId)
                }
                val mergedItems = (listOf(heroAsShelfItem) + remaining).take(MAX_SHELF_SNAPSHOT_ITEMS)
                return ContinueWatchingShelfSnapshot(
                    items = mergedItems,
                    updatedAt = maxOf(shelfTs, heroTs),
                    confirmedEmpty = false
                )
            }
        }

        return shelfSnap
    }

    fun writeShelfSnapshotAsync(
        userEmail: String,
        items: List<DesktopContinueWatchingItem>,
        updatedAt: Long = System.currentTimeMillis(),
        confirmedEmpty: Boolean = items.isEmpty()
    ) {
        if (userEmail.isBlank()) return
        val key = sanitizeEmail(userEmail)
        val seq = profileShelfWriteSeq.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
        val prepared = prepareSanitizedShelfSnapshot(userEmail, items, updatedAt, confirmedEmpty)
        registerShelfArtworkInCache(prepared.items)
        shelfMemoryCache[key] = prepared
        ioScope.launch {
            writeShelfSnapshotBlockingInternal(userEmail, prepared, expectedSeq = seq)
        }
    }

    suspend fun writeShelfSnapshot(
        userEmail: String,
        items: List<DesktopContinueWatchingItem>,
        updatedAt: Long = System.currentTimeMillis(),
        confirmedEmpty: Boolean = items.isEmpty()
    ) {
        if (userEmail.isBlank()) return
        withContext(Dispatchers.IO) {
            writeShelfSnapshotBlocking(userEmail, items, updatedAt, confirmedEmpty)
        }
    }

    fun writeShelfSnapshotBlocking(
        userEmail: String,
        items: List<DesktopContinueWatchingItem>,
        updatedAt: Long = System.currentTimeMillis(),
        confirmedEmpty: Boolean = items.isEmpty()
    ) {
        if (userEmail.isBlank()) return
        val key = sanitizeEmail(userEmail)
        val seq = profileShelfWriteSeq.computeIfAbsent(key) { AtomicLong(0L) }.incrementAndGet()
        val prepared = prepareSanitizedShelfSnapshot(userEmail, items, updatedAt, confirmedEmpty)
        registerShelfArtworkInCache(prepared.items)
        writeShelfSnapshotBlockingInternal(userEmail, prepared, expectedSeq = seq)
    }

    private fun writeShelfSnapshotBlockingInternal(
        userEmail: String,
        snapshot: ContinueWatchingShelfSnapshot,
        expectedSeq: Long
    ) {
        val key = sanitizeEmail(userEmail)
        val lock = profileLocks.computeIfAbsent(key) { Any() }
        val seqCounter = profileShelfWriteSeq.computeIfAbsent(key) { AtomicLong(0L) }
        synchronized(lock) {
            if (seqCounter.get() != expectedSeq) {
                return
            }

            shelfMemoryCache[key] = snapshot
            val targetFile = getShelfSnapshotFile(userEmail)
            targetFile.parentFile?.mkdirs()
            val tmpFile = File(targetFile.parentFile, "${SHELF_SNAPSHOT_FILE_NAME}.tmp")

            try {
                val json = serializeShelfSnapshotJson(snapshot)
                FileOutputStream(tmpFile).use { fos ->
                    fos.write(json.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    try {
                        fos.fd.sync()
                    } catch (_: Throwable) {
                    }
                }
                try {
                    Files.move(
                        tmpFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: Throwable) {
                    Files.move(
                        tmpFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } catch (e: Exception) {
                System.err.println("[CW-ShelfSnapshot] Atomic write failed for $key: ${e.message}")
                try {
                    if (tmpFile.exists()) tmpFile.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun prepareSanitizedShelfSnapshot(
        userEmail: String,
        items: List<DesktopContinueWatchingItem>,
        updatedAt: Long,
        confirmedEmpty: Boolean
    ): ContinueWatchingShelfSnapshot {
        val key = sanitizeEmail(userEmail)
        val previous = shelfMemoryCache[key] ?: run {
            val file = getShelfSnapshotFile(userEmail)
            if (file.exists() && file.length() > 0L) {
                runCatching { parseShelfSnapshotJson(file.readText(Charsets.UTF_8)) }.getOrNull()
            } else null
        }

        val prevByMediaId = previous?.items?.associateBy { it.mediaId }.orEmpty()
        val prevBySeriesId = previous?.items
            ?.filter { !it.seriesId.isNullOrBlank() }
            ?.associateBy { it.seriesId!! }
            .orEmpty()

        val sanitizedItems = items
            .asSequence()
            .filter { it.mediaId.isNotBlank() }
            .distinctBy { it.mediaId }
            .map { item ->
                val prior = prevByMediaId[item.mediaId]
                    ?: item.seriesId?.takeIf { it.isNotBlank() }?.let { prevBySeriesId[it] }
                sanitizeShelfItem(item, prior)
            }
            .sortedByDescending { it.timestamp }
            .take(MAX_SHELF_SNAPSHOT_ITEMS)
            .toList()

        val finalItems = if (sanitizedItems.isEmpty() && confirmedEmpty) {
            val heroSnap = readSnapshot(userEmail)
            if (heroSnap != null &&
                !heroSnap.confirmedEmpty &&
                heroSnap.mediaId.isNotBlank() &&
                normalizeEpochMs(heroSnap.updatedAt) >= normalizeEpochMs(updatedAt)
            ) {
                val safeHeroUrl = sanitizeCanonicalArtworkUrl(heroSnap.canonicalArtworkUrl)
                val epLabel = if (heroSnap.isSeries && heroSnap.seasonNumber != null && heroSnap.episodeNumber != null) {
                    val code = "S${heroSnap.seasonNumber}E${heroSnap.episodeNumber}"
                    val epName = heroSnap.episodeTitle?.takeIf { it.isNotBlank() }
                    if (epName != null) "$code • $epName" else code
                } else {
                    "Movie"
                }
                listOf(
                    DesktopContinueWatchingItem(
                        mediaId = heroSnap.mediaId,
                        title = heroSnap.seriesTitle?.takeIf { it.isNotBlank() && heroSnap.isSeries }
                            ?: heroSnap.displayTitle.ifBlank { heroSnap.episodeTitle ?: "Resume Watching" },
                        subtitle = epLabel,
                        thumbnailUrl = safeHeroUrl,
                        fallbackThumbnailUrl = safeHeroUrl,
                        positionMs = heroSnap.currentPositionMs,
                        durationMs = heroSnap.durationMs,
                        progressFraction = heroSnap.progressFraction.coerceIn(0f, 1f),
                        isSeries = heroSnap.isSeries,
                        seriesId = heroSnap.seriesId,
                        seasonNumber = heroSnap.seasonNumber,
                        episodeNumber = heroSnap.episodeNumber,
                        episodeTitle = heroSnap.episodeTitle.orEmpty(),
                        timestamp = normalizeEpochMs(heroSnap.updatedAt)
                    )
                )
            } else {
                emptyList()
            }
        } else {
            sanitizedItems
        }

        val normalizedTs = normalizeEpochMs(
            when {
                updatedAt > 0L -> updatedAt
                confirmedEmpty && finalItems.isEmpty() -> 0L
                else -> finalItems.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
            }
        )

        return ContinueWatchingShelfSnapshot(
            items = finalItems,
            updatedAt = normalizedTs,
            confirmedEmpty = confirmedEmpty && finalItems.isEmpty()
        )
    }

    private fun sanitizeShelfItem(
        item: DesktopContinueWatchingItem,
        existingItem: DesktopContinueWatchingItem?
    ): DesktopContinueWatchingItem {
        val safeThumb = sanitizeCanonicalArtworkUrl(
            candidateUrl = item.thumbnailUrl,
            existingUrl = existingItem?.thumbnailUrl
        )
        val safeFallback = sanitizeCanonicalArtworkUrl(
            candidateUrl = item.fallbackThumbnailUrl,
            existingUrl = existingItem?.fallbackThumbnailUrl
        )
        val effDur = item.durationMs.coerceAtLeast(0L)
        val frac = if (effDur > 0L) {
            (item.positionMs.toFloat() / effDur.toFloat()).coerceIn(0f, 1f)
        } else {
            item.progressFraction.coerceIn(0f, 1f)
        }
        return item.copy(
            thumbnailUrl = safeThumb,
            fallbackThumbnailUrl = safeFallback,
            durationMs = effDur,
            progressFraction = frac,
            timestamp = normalizeEpochMs(item.timestamp)
        )
    }

    private fun registerShelfArtworkInCache(items: List<DesktopContinueWatchingItem>) {
        for (item in items) {
            val bestUrl = sanitizeCanonicalArtworkUrl(item.thumbnailUrl, item.fallbackThumbnailUrl)
            if (DesktopCanonicalImageCache.classifyProvenance(bestUrl) != ArtworkProvenance.OFFICIAL_CATALOG) {
                continue
            }

            DesktopCanonicalImageCache.registerOfficialArtworkUrl(
                canonicalKey = DesktopCanonicalImageCache.heroKey(item.mediaId),
                officialUrl = bestUrl,
                linkedMediaId = item.mediaId
            )
            if (item.isSeries && !item.seriesId.isNullOrBlank()) {
                val seriesUrl = sanitizeCanonicalArtworkUrl(item.fallbackThumbnailUrl, bestUrl).ifBlank { bestUrl }
                DesktopCanonicalImageCache.registerOfficialArtworkUrl(
                    canonicalKey = DesktopCanonicalImageCache.seriesPosterKey(item.seriesId),
                    officialUrl = seriesUrl,
                    linkedMediaId = item.seriesId
                )
            } else {
                DesktopCanonicalImageCache.registerOfficialArtworkUrl(
                    canonicalKey = DesktopCanonicalImageCache.moviePosterKey(item.mediaId),
                    officialUrl = bestUrl,
                    linkedMediaId = item.mediaId
                )
            }
        }
    }

    private fun serializeShelfSnapshotJson(snapshot: ContinueWatchingShelfSnapshot): String {
        val itemsArr = JsonArray()
        for (item in snapshot.items) {
            itemsArr.add(JsonObject().apply {
                addProperty("mediaId", item.mediaId)
                addProperty("title", item.title)
                addProperty("subtitle", item.subtitle)
                addProperty("thumbnailUrl", item.thumbnailUrl)
                addProperty("fallbackThumbnailUrl", item.fallbackThumbnailUrl)
                addProperty("positionMs", item.positionMs)
                addProperty("durationMs", item.durationMs)
                addProperty("progressFraction", item.progressFraction)
                addProperty("isSeries", item.isSeries)
                if (!item.seriesId.isNullOrBlank()) addProperty("seriesId", item.seriesId)
                if (item.seasonNumber != null) addProperty("seasonNumber", item.seasonNumber)
                if (item.episodeNumber != null) addProperty("episodeNumber", item.episodeNumber)
                addProperty("episodeTitle", item.episodeTitle)
                addProperty("timestamp", item.timestamp)
            })
        }
        val root = JsonObject().apply {
            addProperty("schemaVersion", SCHEMA_VERSION)
            addProperty("updatedAt", snapshot.updatedAt)
            addProperty("confirmedEmpty", snapshot.confirmedEmpty)
            add("items", itemsArr)
        }
        return gson.toJson(root)
    }

    private fun parseShelfSnapshotJson(raw: String): ContinueWatchingShelfSnapshot? {
        fun optStr(obj: JsonObject, key: String): String? {
            val el: JsonElement = obj.get(key) ?: return null
            if (el.isJsonNull || !el.isJsonPrimitive) return null
            return runCatching { el.asString }.getOrNull()
        }
        fun optLng(obj: JsonObject, key: String): Long? {
            val el: JsonElement = obj.get(key) ?: return null
            if (el.isJsonNull || !el.isJsonPrimitive) return null
            return runCatching { el.asLong }.getOrNull()
        }
        fun optFlt(obj: JsonObject, key: String): Float? {
            val el: JsonElement = obj.get(key) ?: return null
            if (el.isJsonNull || !el.isJsonPrimitive) return null
            return runCatching { el.asFloat }.getOrNull()
        }
        fun optIntVal(obj: JsonObject, key: String): Int? {
            val el: JsonElement = obj.get(key) ?: return null
            if (el.isJsonNull || !el.isJsonPrimitive) return null
            return runCatching { el.asInt }.getOrNull()
        }
        fun optBoolVal(obj: JsonObject, key: String): Boolean? {
            val el: JsonElement = obj.get(key) ?: return null
            if (el.isJsonNull || !el.isJsonPrimitive) return null
            return runCatching { el.asBoolean }.getOrNull()
        }

        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val updatedAt = optLng(root, "updatedAt") ?: 0L
        val confirmedEmpty = optBoolVal(root, "confirmedEmpty") ?: false
        val items = mutableListOf<DesktopContinueWatchingItem>()

        root.get("items")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
            val obj = el?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val mediaId = optStr(obj, "mediaId")?.trim().orEmpty()
            if (mediaId.isBlank()) return@forEach

            items.add(
                DesktopContinueWatchingItem(
                    mediaId = mediaId,
                    title = optStr(obj, "title").orEmpty(),
                    subtitle = optStr(obj, "subtitle").orEmpty(),
                    thumbnailUrl = optStr(obj, "thumbnailUrl").orEmpty(),
                    fallbackThumbnailUrl = optStr(obj, "fallbackThumbnailUrl").orEmpty(),
                    positionMs = optLng(obj, "positionMs") ?: 0L,
                    durationMs = optLng(obj, "durationMs") ?: 0L,
                    progressFraction = optFlt(obj, "progressFraction") ?: 0f,
                    isSeries = optBoolVal(obj, "isSeries") ?: false,
                    seriesId = optStr(obj, "seriesId")?.trim()?.ifBlank { null },
                    seasonNumber = optIntVal(obj, "seasonNumber"),
                    episodeNumber = optIntVal(obj, "episodeNumber"),
                    episodeTitle = optStr(obj, "episodeTitle").orEmpty(),
                    timestamp = optLng(obj, "timestamp") ?: 0L
                )
            )
        }
        return ContinueWatchingShelfSnapshot(
            items = items,
            updatedAt = updatedAt,
            confirmedEmpty = confirmedEmpty
        )
    }

    private fun prepareSanitizedRecord(
        userEmail: String,
        incoming: ContinueWatchingDisplayData
    ): ContinueWatchingDisplayData {
        val key = sanitizeEmail(userEmail)
        val previous = memoryCache[key] ?: run {
            val file = getSnapshotFile(userEmail)
            if (file.exists() && file.length() > 0L) {
                runCatching { parseSnapshotJson(file.readText(Charsets.UTF_8)) }.getOrNull()
            } else null
        }

        val sameEntity = previous != null && (
            (incoming.mediaId.isNotBlank() && previous.mediaId == incoming.mediaId) ||
                (!incoming.seriesId.isNullOrBlank() && previous.seriesId == incoming.seriesId)
            )

        val preservedExistingUrl = if (sameEntity) previous?.canonicalArtworkUrl else null
        val safeArtwork = sanitizeCanonicalArtworkUrl(
            candidateUrl = incoming.canonicalArtworkUrl,
            existingUrl = preservedExistingUrl
        )

        val rawNormalizedTs = when {
            incoming.updatedAt > 0L -> incoming.updatedAt
            incoming.confirmedEmpty -> 0L
            else -> System.currentTimeMillis()
        }
        val normalizedTs = normalizeEpochMs(rawNormalizedTs)

        val computedFraction = if (incoming.durationMs > 0L) {
            (incoming.currentPositionMs.toFloat() / incoming.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            incoming.progressFraction.coerceIn(0f, 1f)
        }

        return incoming.copy(
            canonicalArtworkUrl = safeArtwork,
            progressFraction = computedFraction,
            updatedAt = normalizedTs
        )
    }

    private fun serializeSnapshotJson(data: ContinueWatchingDisplayData): String {
        fun esc(s: String?): String {
            if (s == null) return "null"
            val sb = StringBuilder("\"")
            for (ch in s) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
                }
            }
            sb.append('"')
            return sb.toString()
        }

        return buildString {
            append("{\n")
            append("  \"schemaVersion\": $SCHEMA_VERSION,\n")
            append("  \"mediaId\": ${esc(data.mediaId)},\n")
            append("  \"seriesId\": ${esc(data.seriesId)},\n")
            append("  \"isSeries\": ${data.isSeries},\n")
            append("  \"displayHeader\": ${esc(data.displayHeader)},\n")
            append("  \"displayTitle\": ${esc(data.displayTitle)},\n")
            append("  \"seriesTitle\": ${esc(data.seriesTitle)},\n")
            append("  \"seasonNumber\": ${data.seasonNumber ?: "null"},\n")
            append("  \"episodeNumber\": ${data.episodeNumber ?: "null"},\n")
            append("  \"episodeTitle\": ${esc(data.episodeTitle)},\n")
            append("  \"canonicalArtworkUrl\": ${esc(data.canonicalArtworkUrl)},\n")
            append("  \"progressFraction\": ${data.progressFraction},\n")
            append("  \"currentPositionMs\": ${data.currentPositionMs},\n")
            append("  \"durationMs\": ${data.durationMs},\n")
            append("  \"updatedAt\": ${data.updatedAt},\n")
            append("  \"confirmedEmpty\": ${data.confirmedEmpty}\n")
            append("}")
        }
    }

    private fun parseSnapshotJson(json: String): ContinueWatchingDisplayData? {
        val map = SimpleJsonObjectParser.parse(json) ?: return null
        val mediaId = map["mediaId"] as? String ?: ""
        val confirmedEmpty = map["confirmedEmpty"] as? Boolean ?: false
        if (mediaId.isBlank() && !confirmedEmpty) return null

        return ContinueWatchingDisplayData(
            mediaId = mediaId,
            seriesId = map["seriesId"] as? String,
            isSeries = map["isSeries"] as? Boolean ?: false,
            displayHeader = map["displayHeader"] as? String ?: "",
            displayTitle = map["displayTitle"] as? String ?: "",
            seriesTitle = map["seriesTitle"] as? String,
            seasonNumber = (map["seasonNumber"] as? Number)?.toInt(),
            episodeNumber = (map["episodeNumber"] as? Number)?.toInt(),
            episodeTitle = map["episodeTitle"] as? String,
            canonicalArtworkUrl = map["canonicalArtworkUrl"] as? String ?: "",
            progressFraction = (map["progressFraction"] as? Number)?.toFloat() ?: 0f,
            currentPositionMs = (map["currentPositionMs"] as? Number)?.toLong() ?: 0L,
            durationMs = (map["durationMs"] as? Number)?.toLong() ?: 0L,
            updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L,
            confirmedEmpty = confirmedEmpty
        )
    }

    private object SimpleJsonObjectParser {
        fun parse(src: String): Map<String, Any?>? {
            var idx = 0
            val n = src.length
            fun skipWs() {
                while (idx < n && src[idx].isWhitespace()) idx++
            }
            fun parseString(): String {
                idx++ // skip '"'
                val sb = StringBuilder()
                while (idx < n) {
                    val c = src[idx++]
                    if (c == '"') return sb.toString()
                    if (c == '\\' && idx < n) {
                        when (val esc = src[idx++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> if (idx + 4 <= n) {
                                sb.append(src.substring(idx, idx + 4).toInt(16).toChar())
                                idx += 4
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(c)
                    }
                }
                return sb.toString()
            }
            fun parseValue(): Any? {
                skipWs()
                if (idx >= n) return null
                return when (src[idx]) {
                    '"' -> parseString()
                    't' -> { idx += 4; true }
                    'f' -> { idx += 5; false }
                    'n' -> { idx += 4; null }
                    else -> {
                        val start = idx
                        while (idx < n && (src[idx].isDigit() || src[idx] in listOf('-', '+', '.', 'e', 'E'))) idx++
                        val token = src.substring(start, idx)
                        if (token.contains('.') || token.contains('e', true)) token.toDoubleOrNull() else token.toLongOrNull()
                    }
                }
            }
            skipWs()
            if (idx >= n || src[idx] != '{') return null
            idx++
            val result = LinkedHashMap<String, Any?>()
            while (idx < n) {
                skipWs()
                if (idx < n && src[idx] == '}') { idx++; return result }
                if (idx >= n || src[idx] != '"') return null
                val key = parseString()
                skipWs()
                if (idx >= n || src[idx] != ':') return null
                idx++
                result[key] = parseValue()
                skipWs()
                if (idx < n && src[idx] == ',') idx++
            }
            return result
        }
    }
}
