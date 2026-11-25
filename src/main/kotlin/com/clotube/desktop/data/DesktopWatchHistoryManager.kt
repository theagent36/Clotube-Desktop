package com.clotube.desktop.data

import androidx.compose.runtime.mutableIntStateOf
import com.clotube.desktop.image.ArtworkProvenance
import com.clotube.desktop.image.DesktopCanonicalImageCache
import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.state.HeroSlotStateMachine
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
 * Watch history RAM state holder paired with Layer 1 profile-scoped `cw_hero_snapshot.json`
 * and Layer 2 `HeroSlotStateMachine`.
 * Never writes full watch history to %APPDATA%; only maintains `cw_hero_snapshot.json`
 * for Frame-1 Continue Watching hydration and syncs full state with Google Drive V2.
 */
object DesktopWatchHistoryManager {
    private const val MAX_ACTIVE_WINDOW = 50
    private const val MAX_PROTECTED_IN_PROGRESS = 20

    private val stateLock = Any()
    private val volatileEntries = ConcurrentHashMap<String, DesktopTypedEntry>()
    private val archivedWatchedIds = ConcurrentHashMap.newKeySet<String>()
    private val archivedItems = ConcurrentHashMap<String, DesktopArchivedHistoryEntry>()

    @Volatile
    private var activeProfileSanitized: String = ""

    @Volatile
    private var activeProfileRawEmail: String = ""

    // Observable Compose state that notifies Home, Movies, Series, and Recents screens immediately
    val historyRevision = mutableIntStateOf(0)

    fun notifyMutation() {
        historyRevision.intValue++
    }

    fun bindProfile(email: String) {
        val trimmed = email.trim()
        if (trimmed.isNotBlank()) {
            activeProfileRawEmail = trimmed
        }
        val sanitized = DesktopProfileManager.sanitizeEmail(trimmed)
        if (sanitized != activeProfileSanitized) {
            activeProfileSanitized = sanitized
            clearInMemory(trimmed)
        }
    }

    private fun resolveEffectiveEmail(email: String = ""): String {
        return email.trim()
            .ifBlank { activeProfileRawEmail }
            .ifBlank { DesktopProfileManager.getActiveEmail().orEmpty() }
    }

    /**
     * One-time cleanup of all legacy local watch history JSON files under
     * %APPDATA%\Clotube\profiles\ so stale cached history can never resurrect
     * or conflict with Google Drive.
     *
     * Explicitly spares:
     * - `cw_hero_snapshot.json`
     * - `cw_shelf_snapshot.json`
     * - `local_recent_searches.json`
     * - `clotube_watchlist.json` and `watchlist.json`
     * - `tokens/`, `staged_tokens/`, `auth_state.json`, `client_secrets.json`
     */
    fun purgeAllLocalDiskCaches() {
        try {
            val appData = DesktopProfileManager.appDataRoot

            val legacyHistoryTargets = setOf(
                "watch_history.json",
                "watch_history_archive.json",
                "clotube_watch_history.json",
                "clotube_active_sync.json",
                "clotube_archive.json",
                "clotube_history.json"
            )

            val protectedDirs = setOf("tokens", "staged_tokens")
            val protectedFiles = setOf(
                DesktopContinueWatchingSnapshotStore.SNAPSHOT_FILE_NAME,
                "${DesktopContinueWatchingSnapshotStore.SNAPSHOT_FILE_NAME}.tmp",
                DesktopContinueWatchingSnapshotStore.SHELF_SNAPSHOT_FILE_NAME,
                "${DesktopContinueWatchingSnapshotStore.SHELF_SNAPSHOT_FILE_NAME}.tmp",
                "local_recent_searches.json",
                "local_recent_searches.json.tmp",
                "clotube_watchlist.json",
                "watchlist.json",
                "auth_state.json",
                "client_secrets.json",
                "staged_client_secrets.json"
            )

            if (appData.exists() && appData.isDirectory) {
                appData.walkTopDown()
                    .onEnter { dir -> dir.name !in protectedDirs }
                    .forEach { file ->
                        if (file.isFile &&
                            file.name in legacyHistoryTargets &&
                            file.name !in protectedFiles
                        ) {
                            file.delete()
                        }
                    }
            }
            println("[ClotubeSync] Purged legacy full watch history caches (protected files preserved)")
        } catch (e: Exception) {
            println("[ClotubeSync] Warning purging legacy local history files: ${e.message}")
        }
    }

    fun snapshotEntries(): Map<String, DesktopTypedEntry> = synchronized(stateLock) {
        HashMap(volatileEntries)
    }

    fun snapshotArchivedIds(): Set<String> = synchronized(stateLock) {
        HashSet(archivedWatchedIds)
    }

    fun snapshotArchivedItems(): Map<String, DesktopArchivedHistoryEntry> = synchronized(stateLock) {
        HashMap(archivedItems)
    }

    fun getLatestTimestampMs(): Long = synchronized(stateLock) {
        var latest = 0L
        val globalTs = (volatileEntries["global_latest_timestamp"]?.value as? Number)?.toLong() ?: 0L
        latest = maxOf(latest, DesktopContinueWatchingSnapshotStore.normalizeEpochMs(globalTs))

        for ((k, v) in volatileEntries) {
            if (k.endsWith("_timestamp")) {
                val ts = (v.value as? Number)?.toLong() ?: 0L
                latest = maxOf(latest, DesktopContinueWatchingSnapshotStore.normalizeEpochMs(ts))
            }
        }
        for (arch in archivedItems.values) {
            latest = maxOf(latest, DesktopContinueWatchingSnapshotStore.normalizeEpochMs(arch.timestamp))
        }
        latest
    }

    fun replaceAllFromDrive(
        activeEntries: Map<String, DesktopTypedEntry>,
        archiveIds: Set<String>,
        archiveMetadata: Map<String, DesktopArchivedHistoryEntry> = emptyMap(),
        remoteLatestTs: Long = 0L
    ): Boolean {
        return synchronized(stateLock) {
            volatileEntries.clear()
            volatileEntries.putAll(activeEntries)

            val normalizedRemoteTs = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(remoteLatestTs)
            if (normalizedRemoteTs > 0L) {
                val existingGlobal = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(
                    (volatileEntries["global_latest_timestamp"]?.value as? Number)?.toLong() ?: 0L
                )
                if (normalizedRemoteTs >= existingGlobal) {
                    volatileEntries["global_latest_timestamp"] = DesktopTypedEntry("long", normalizedRemoteTs)
                }
            }

            archivedWatchedIds.clear()
            archivedWatchedIds.addAll(archiveIds)

            archivedItems.clear()
            archivedItems.putAll(archiveMetadata)

            // Self-healing promotion: restore any genuinely in-progress items that were previously evicted to archive
            var promotedAny = false
            val toPromote = archivedItems.values.filter { arch ->
                !arch.isWatched &&
                    arch.positionMs >= 3000L &&
                    (arch.durationMs <= 0L || arch.positionMs < arch.durationMs * 0.90f)
            }
            if (toPromote.isNotEmpty()) {
                val working = HashMap<String, DesktopTypedEntry?>(volatileEntries)
                for (arch in toPromote) {
                    val effDur = arch.durationMs.coerceAtLeast(0L)
                    val frac = if (effDur > 0L) (arch.positionMs.toFloat() / effDur.toFloat()).coerceIn(0f, 1f) else 0f
                    val ts = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(arch.timestamp)
                        .takeIf { it > 0L } ?: System.currentTimeMillis()
                    if (arch.title.isNotBlank()) {
                        working["${arch.id}_title"] = DesktopTypedEntry("string", arch.title)
                    }
                    working["${arch.id}_pos"] = DesktopTypedEntry("long", arch.positionMs)
                    working["${arch.id}_dur"] = DesktopTypedEntry("long", effDur)
                    working["${arch.id}_fraction"] = DesktopTypedEntry("float", frac)
                    working["${arch.id}_timestamp"] = DesktopTypedEntry("long", ts)
                    working["${arch.id}_is_watched"] = DesktopTypedEntry("boolean", false)
                    val isSeries = !arch.seriesId.isNullOrBlank()
                    working["${arch.id}_is_series"] = DesktopTypedEntry("boolean", isSeries)
                    if (isSeries) {
                        working["${arch.id}_series_id"] = DesktopTypedEntry("string", arch.seriesId!!)
                        if (arch.seasonNumber > 0) working["${arch.id}_season_num"] = DesktopTypedEntry("int", arch.seasonNumber)
                        if (arch.episodeNumber > 0) working["${arch.id}_episode_num"] = DesktopTypedEntry("int", arch.episodeNumber)
                    }
                    archivedItems.remove(arch.id)
                    archivedWatchedIds.remove(arch.id)
                    promotedAny = true
                }
                pruneOverflowToVolatileArchive(working)
                volatileEntries.clear()
                working.forEach { (k, v) ->
                    if (v != null) volatileEntries[k] = v
                }
            }

            notifyMutation()
            val effectiveEmail = resolveEffectiveEmail()
            if (effectiveEmail.isNotBlank()) {
                val cachedLib = DesktopLibraryCache.loadSync(effectiveEmail)
                val shelfItems = getContinueWatching(
                    effectiveEmail,
                    cachedLib?.movies.orEmpty(),
                    cachedLib?.series.orEmpty()
                ).let { preservePriorShelfArtwork(effectiveEmail, it) }
                val effectiveTs = getLatestTimestampMs().takeIf { it > 0L } ?: normalizedRemoteTs
                val hydrated = hasHydratedMediaInRam()
                if (shelfItems.isNotEmpty() || hydrated) {
                    DesktopContinueWatchingSnapshotStore.writeShelfSnapshotAsync(
                        userEmail = effectiveEmail,
                        items = shelfItems,
                        updatedAt = effectiveTs,
                        confirmedEmpty = shelfItems.isEmpty() && hydrated
                    )
                }
            }
            promotedAny
        }
    }

    /**
     * Cold-boot recovery helper when `cw_hero_snapshot.json` has a newer local timestamp than Drive.
     * Overlays the newer local Continue Watching snapshot onto Drive's historical entries so neither
     * older watched items nor the newest local playback position is lost.
     */
    fun applySnapshotRecovery(
        email: String,
        snapshot: ContinueWatchingDisplayData,
        persistShelfSnapshot: Boolean = true
    ) {
        val effectiveEmail = resolveEffectiveEmail(email)
        val nowTs = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(snapshot.updatedAt)
            .takeIf { it > 0L } ?: System.currentTimeMillis()

        if (snapshot.confirmedEmpty && snapshot.mediaId.isBlank()) {
            synchronized(stateLock) {
                volatileEntries.clear()
                volatileEntries["global_latest_timestamp"] = DesktopTypedEntry("long", nowTs)
                notifyMutation()
            }
            if (persistShelfSnapshot && effectiveEmail.isNotBlank()) {
                DesktopContinueWatchingSnapshotStore.writeShelfSnapshotAsync(
                    userEmail = effectiveEmail,
                    items = emptyList(),
                    updatedAt = nowTs,
                    confirmedEmpty = true
                )
            }
            return
        }

        if (snapshot.mediaId.isBlank() || snapshot.confirmedEmpty) return

        val effectiveDur = if (snapshot.durationMs > 0L) snapshot.durationMs else 45L * 60000L
        val effectiveFrac = if (effectiveDur > 0L) {
            (snapshot.currentPositionMs.toFloat() / effectiveDur.toFloat()).coerceIn(0f, 1f)
        } else {
            snapshot.progressFraction.coerceIn(0f, 1f)
        }

        synchronized(stateLock) {
            archivedWatchedIds.remove(snapshot.mediaId)
            archivedItems.remove(snapshot.mediaId)
        }

        updateEntries {
            val id = snapshot.mediaId
            val effectiveTitle = snapshot.episodeTitle?.takeIf { it.isNotBlank() }
                ?: snapshot.displayTitle.ifBlank { snapshot.seriesTitle.orEmpty() }
            this["${id}_title"] = DesktopTypedEntry("string", effectiveTitle)
            this["${id}_pos"] = DesktopTypedEntry("long", snapshot.currentPositionMs)
            this["${id}_dur"] = DesktopTypedEntry("long", effectiveDur)
            this["${id}_fraction"] = DesktopTypedEntry("float", effectiveFrac)
            this["${id}_timestamp"] = DesktopTypedEntry("long", nowTs)
            this["${id}_is_watched"] = DesktopTypedEntry("boolean", false)
            this["${id}_is_series"] = DesktopTypedEntry("boolean", snapshot.isSeries)

            if (snapshot.isSeries && !snapshot.seriesId.isNullOrBlank()) {
                val sid = snapshot.seriesId
                val sNum = (snapshot.seasonNumber ?: 1).coerceAtLeast(1)
                val eNum = (snapshot.episodeNumber ?: 1).coerceAtLeast(1)

                this["${id}_series_id"] = DesktopTypedEntry("string", sid)
                this["${id}_season_num"] = DesktopTypedEntry("int", sNum)
                this["${id}_episode_num"] = DesktopTypedEntry("int", eNum)

                this["series_resume_${sid}_episode_id"] = DesktopTypedEntry("string", id)
                this["series_resume_${sid}_season_num"] = DesktopTypedEntry("int", sNum)
                this["series_resume_${sid}_episode_num"] = DesktopTypedEntry("int", eNum)
                this["series_resume_${sid}_pos_ms"] = DesktopTypedEntry("long", snapshot.currentPositionMs)
                this["series_resume_${sid}_dur_ms"] = DesktopTypedEntry("long", effectiveDur)
                this["series_resume_${sid}_fraction"] = DesktopTypedEntry("float", effectiveFrac)
                this["series_resume_${sid}_timestamp"] = DesktopTypedEntry("long", nowTs)
                if (!snapshot.episodeTitle.isNullOrBlank()) {
                    this["series_resume_${sid}_episode_title"] = DesktopTypedEntry("string", snapshot.episodeTitle)
                }
            }

            val existingGlobal = (this["global_latest_timestamp"]?.value as? Number)?.toLong() ?: 0L
            if (nowTs >= existingGlobal) {
                this["global_latest_timestamp"] = DesktopTypedEntry("long", nowTs)
            }
        }

        if (persistShelfSnapshot && effectiveEmail.isNotBlank()) {
            val cachedLib = DesktopLibraryCache.loadSync(effectiveEmail)
            val shelfItems = getContinueWatching(
                effectiveEmail,
                cachedLib?.movies.orEmpty(),
                cachedLib?.series.orEmpty()
            )
            DesktopContinueWatchingSnapshotStore.writeShelfSnapshotAsync(
                userEmail = effectiveEmail,
                items = shelfItems,
                updatedAt = getLatestTimestampMs().takeIf { it > 0L } ?: nowTs,
                confirmedEmpty = shelfItems.isEmpty()
            )
        }
    }

    fun updateEntries(mutations: MutableMap<String, DesktopTypedEntry?>.() -> Unit) {
        synchronized(stateLock) {
            val working = HashMap(volatileEntries)
            working.mutations()
            pruneOverflowToVolatileArchive(working)
            volatileEntries.clear()
            working.forEach { (k, v) ->
                if (v != null) volatileEntries[k] = v
            }
            notifyMutation()
        }
    }

    private fun pruneOverflowToVolatileArchive(working: MutableMap<String, DesktopTypedEntry?>) {
        val itemIds = working.keys
            .filter { it.endsWith("_timestamp") && !it.startsWith("series_resume_") && !it.startsWith("global_") }
            .map { it.removeSuffix("_timestamp") }
            .distinct()

        if (itemIds.size <= MAX_ACTIVE_WINDOW) return

        data class CandidateMeta(
            val id: String,
            val title: String,
            val pos: Long,
            val dur: Long,
            val frac: Float,
            val ts: Long,
            val isWatched: Boolean,
            val isInProgress: Boolean,
            val seriesId: String?,
            val sNum: Int,
            val eNum: Int
        )

        val candidates = itemIds.map { id ->
            val title = (working["${id}_title"]?.value as? String).orEmpty()
            val pos = (working["${id}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = (working["${id}_dur"]?.value as? Number)?.toLong() ?: 0L
            val frac = (working["${id}_fraction"]?.value as? Number)?.toFloat()
                ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
            val ts = (working["${id}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            val explicitWatched = working["${id}_is_watched"]?.value as? Boolean
            val isWatched = explicitWatched ?: (frac >= 0.90f)
            val isInProgress = !isWatched && pos >= 3000L && frac < 0.90f && (dur <= 0L || pos < dur * 0.90f)
            val seriesId = (working["${id}_series_id"]?.value as? String)?.ifBlank { null }
            val sNum = (working["${id}_season_num"]?.value as? Number)?.toInt() ?: 0
            val eNum = (working["${id}_episode_num"]?.value as? Number)?.toInt() ?: 0

            CandidateMeta(id, title, pos, dur, frac, ts, isWatched, isInProgress, seriesId, sNum, eNum)
        }

        // 1. Protect active in-progress movies/episodes so marking 50+ episodes watched never evicts resume state
        val pinnedInProgress = candidates
            .filter { it.isInProgress }
            .sortedByDescending { it.ts }
            .take(MAX_PROTECTED_IN_PROGRESS)
            .map { it.id }
            .toSet()

        // 2. Fill remaining active window slots with the most recent entries
        val remainingSlots = (MAX_ACTIVE_WINDOW - pinnedInProgress.size).coerceAtLeast(0)
        val recentOther = candidates
            .filter { it.id !in pinnedInProgress }
            .sortedByDescending { it.ts }
            .take(remainingSlots)
            .map { it.id }
            .toSet()

        val keepSet = pinnedInProgress + recentOther

        for (meta in candidates) {
            if (meta.id in keepSet) continue
            if (meta.isWatched) {
                archivedWatchedIds.add(meta.id)
            }
            archivedItems[meta.id] = DesktopArchivedHistoryEntry(
                id = meta.id,
                title = meta.title,
                positionMs = meta.pos,
                durationMs = meta.dur,
                timestamp = meta.ts,
                isWatched = meta.isWatched,
                seriesId = meta.seriesId,
                seasonNumber = meta.sNum,
                episodeNumber = meta.eNum
            )
            working.keys.filter { it.startsWith("${meta.id}_") }.forEach { working.remove(it) }
        }
    }

    fun clearInMemory(email: String = "") {
        synchronized(stateLock) {
            volatileEntries.clear()
            archivedWatchedIds.clear()
            archivedItems.clear()
            notifyMutation()
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
        if (fileId.isBlank()) return
        val effectiveEmail = resolveEffectiveEmail(email)

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
        val wasArchived = synchronized(stateLock) {
            archivedWatchedIds.remove(fileId) || (archivedItems.remove(fileId) != null)
        }

        updateEntries {
            this["${fileId}_title"] = DesktopTypedEntry("string", title)
            this["${fileId}_pos"] = DesktopTypedEntry("long", positionMs)
            this["${fileId}_dur"] = DesktopTypedEntry("long", effectiveDur)
            this["${fileId}_fraction"] = DesktopTypedEntry("float", fraction)
            this["${fileId}_timestamp"] = DesktopTypedEntry("long", now)
            this["${fileId}_is_watched"] = DesktopTypedEntry("boolean", isWatched)
            this["${fileId}_is_series"] = DesktopTypedEntry("boolean", isSeries)
            this["global_latest_timestamp"] = DesktopTypedEntry("long", now)

            if (isSeries && seriesId.isNotBlank()) {
                val sNum = seasonNumber.coerceAtLeast(1)
                val eNum = episodeNumber.coerceAtLeast(1)

                this["${fileId}_series_id"] = DesktopTypedEntry("string", seriesId)
                this["${fileId}_season_num"] = DesktopTypedEntry("int", sNum)
                this["${fileId}_episode_num"] = DesktopTypedEntry("int", eNum)

                if (isWatched) {
                    if (this["series_resume_${seriesId}_episode_id"]?.value == fileId) {
                        removeSeriesResumeKeys(this, seriesId)
                    }
                } else {
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
                        this["series_resume_${seriesId}_fraction"] = DesktopTypedEntry("float", fraction)
                        this["series_resume_${seriesId}_timestamp"] = DesktopTypedEntry("long", now)
                        if (title.isNotBlank()) {
                            this["series_resume_${seriesId}_episode_title"] = DesktopTypedEntry("string", title)
                        }
                    }
                }
            }
        }

        if (isWatched && effectiveEmail.isNotBlank() && title.isNotBlank()) {
            runCatching {
                DesktopWatchHistorySync.syncScope.launch {
                    DesktopWatchlistManager.autoMarkWatchedIfEligible(
                        email = effectiveEmail,
                        title = title,
                        mediaType = if (isSeries) WatchlistMediaType.SERIES else WatchlistMediaType.MOVIE,
                        driveId = if (isSeries && seriesId.isNotBlank()) seriesId else fileId
                    )
                }
            }
        }

        syncContinueWatchingSnapshotAndHero(effectiveEmail, mutationTs = now)

        DesktopWatchHistorySync.pushHistoryAsync(
            force = isWatched,
            updateArchive = wasArchived
        )
    }

    fun getPlaybackPosition(email: String, fileId: String): Long = getPlaybackPosition(fileId)

    fun getPlaybackPosition(fileId: String): Long {
        if (fileId.isBlank()) return 0L

        val hasVolatile = volatileEntries.containsKey("${fileId}_pos") ||
            volatileEntries.containsKey("${fileId}_fraction")

        if (hasVolatile) {
            val pos = (volatileEntries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = (volatileEntries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
            val fraction = (volatileEntries["${fileId}_fraction"]?.value as? Number)?.toFloat()
                ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()) else 0f

            if (fraction >= 0.90f || (dur > 0L && pos >= (dur * 0.90f))) return 0L
            return if (pos > 1500L) pos else 0L
        }

        val arch = archivedItems[fileId] ?: return 0L
        if (arch.isWatched) return 0L
        val archFraction = if (arch.durationMs > 0L) {
            (arch.positionMs.toFloat() / arch.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        if (archFraction >= 0.90f || (arch.durationMs > 0L && arch.positionMs >= (arch.durationMs * 0.90f))) {
            return 0L
        }
        return if (arch.positionMs > 1500L) arch.positionMs else 0L
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
        return getPlaybackPosition(email, fileId) > 1500L
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
        if (email.isNotBlank()) activeProfileRawEmail = email.trim()
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
        val wasArchived = synchronized(stateLock) {
            archivedWatchedIds.remove(fileId) || (archivedItems.remove(fileId) != null)
        }
        val effectiveSeriesId = seriesId?.takeIf { it.isNotBlank() }
            ?: (volatileEntries["${fileId}_series_id"]?.value as? String)?.takeIf { it.isNotBlank() }

        updateEntries {
            this["${fileId}_is_watched"] = DesktopTypedEntry("boolean", isWatched)
            this["${fileId}_timestamp"] = DesktopTypedEntry("long", now)
            this["global_latest_timestamp"] = DesktopTypedEntry("long", now)
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
            if (isSeries && !effectiveSeriesId.isNullOrBlank()) {
                this["${fileId}_is_series"] = DesktopTypedEntry("boolean", true)
                this["${fileId}_series_id"] = DesktopTypedEntry("string", effectiveSeriesId)
                if (seasonNumber > 0) this["${fileId}_season_num"] = DesktopTypedEntry("int", seasonNumber)
                if (episodeNumber > 0) this["${fileId}_episode_num"] = DesktopTypedEntry("int", episodeNumber)
            }
            if (!effectiveSeriesId.isNullOrBlank() &&
                this["series_resume_${effectiveSeriesId}_episode_id"]?.value == fileId
            ) {
                removeSeriesResumeKeys(this, effectiveSeriesId)
            }
        }

        syncContinueWatchingSnapshotAndHero(resolveEffectiveEmail(), mutationTs = now)

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
        val effectiveEmail = resolveEffectiveEmail(email)
        val now = System.currentTimeMillis()
        var touchedArchive = false
        val epIdSet = episodes.map { it.id }.toSet()

        updateEntries {
            this["global_latest_timestamp"] = DesktopTypedEntry("long", now)
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
            if (seriesId.isNotBlank()) {
                val resumedEp = this["series_resume_${seriesId}_episode_id"]?.value as? String
                if (resumedEp != null && resumedEp in epIdSet) {
                    removeSeriesResumeKeys(this, seriesId)
                }
            }
        }

        syncContinueWatchingSnapshotAndHero(effectiveEmail, mutationTs = now)

        DesktopWatchHistorySync.pushHistoryAsync(
            force = true,
            updateArchive = touchedArchive
        )
    }

    fun getSeriesResumeState(email: String, seriesId: String): SeriesResumeState? {
        if (seriesId.isBlank()) return null
        var epId = volatileEntries["series_resume_${seriesId}_episode_id"]?.value as? String
        var pos = (volatileEntries["series_resume_${seriesId}_pos_ms"]?.value as? Number)?.toLong() ?: 0L

        if (epId.isNullOrBlank() || isEpisodeWatched(epId)) {
            val candidateEps = volatileEntries.keys
                .filter { it.endsWith("_series_id") && volatileEntries[it]?.value == seriesId }
                .map { it.removeSuffix("_series_id") }

            var bestEpId: String? = null
            var bestTs = -1L
            var bestPos = 0L

            for (cId in candidateEps) {
                if (isEpisodeWatched(cId)) continue
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
            } else if (!epId.isNullOrBlank() && isEpisodeWatched(epId)) {
                return null
            }
        }

        if (epId.isNullOrBlank() || isEpisodeWatched(epId)) return null
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

    private fun hasHydratedMediaInRam(): Boolean = synchronized(stateLock) {
        archivedItems.isNotEmpty() ||
            volatileEntries.keys.any { key ->
                (key.endsWith("_timestamp") && !key.startsWith("series_resume_") && !key.startsWith("global_")) ||
                    (key.startsWith("series_resume_") && key.endsWith("_episode_id"))
            }
    }

    private fun heroSnapshotToContinueWatchingItem(heroSnap: ContinueWatchingDisplayData): DesktopContinueWatchingItem {
        return DesktopContinueWatchingItem(
            mediaId = heroSnap.mediaId,
            title = heroSnap.seriesTitle?.takeIf { it.isNotBlank() && heroSnap.isSeries }
                ?: heroSnap.displayTitle.ifBlank { heroSnap.episodeTitle ?: "Resume Watching" },
            subtitle = if (heroSnap.isSeries && heroSnap.seasonNumber != null && heroSnap.episodeNumber != null) {
                val code = "S${heroSnap.seasonNumber}E${heroSnap.episodeNumber}"
                val epName = heroSnap.episodeTitle?.takeIf { it.isNotBlank() }
                if (epName != null) "$code • $epName" else code
            } else {
                "Movie"
            },
            thumbnailUrl = heroSnap.canonicalArtworkUrl,
            fallbackThumbnailUrl = heroSnap.canonicalArtworkUrl,
            positionMs = heroSnap.currentPositionMs,
            durationMs = heroSnap.durationMs,
            progressFraction = heroSnap.progressFraction.coerceIn(0.02f, 1f),
            isSeries = heroSnap.isSeries,
            seriesId = heroSnap.seriesId,
            seasonNumber = heroSnap.seasonNumber,
            episodeNumber = heroSnap.episodeNumber,
            episodeTitle = heroSnap.episodeTitle.orEmpty(),
            timestamp = heroSnap.updatedAt
        )
    }

    private fun preservePriorShelfArtwork(
        email: String,
        shelfItems: List<DesktopContinueWatchingItem>
    ): List<DesktopContinueWatchingItem> {
        if (shelfItems.isEmpty() || email.isBlank()) return shelfItems
        val prior = DesktopContinueWatchingSnapshotStore.readShelfSnapshot(email) ?: return shelfItems
        val byMediaId = prior.items.associateBy { it.mediaId }
        val bySeriesId = prior.items.filter { !it.seriesId.isNullOrBlank() }.associateBy { it.seriesId!! }
        return shelfItems.map { item ->
            val prev = byMediaId[item.mediaId] ?: item.seriesId?.let { bySeriesId[it] }
            val mergedThumb = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                candidateUrl = item.thumbnailUrl,
                existingUrl = prev?.thumbnailUrl
            )
            val mergedFallback = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                candidateUrl = item.fallbackThumbnailUrl,
                existingUrl = prev?.fallbackThumbnailUrl
            )
            item.copy(thumbnailUrl = mergedThumb, fallbackThumbnailUrl = mergedFallback)
        }
    }

    fun getContinueWatching(
        email: String,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ): List<DesktopContinueWatchingItem> {
        // Cold-boot Frame-1 fallback: only treat RAM as hydrated when actual media/resume keys exist
        if (!hasHydratedMediaInRam() && email.isNotBlank()) {
            val shelfSnap = DesktopContinueWatchingSnapshotStore.readShelfSnapshot(email)
            val heroSnap = DesktopContinueWatchingSnapshotStore.readSnapshot(email)
            if (shelfSnap != null && shelfSnap.items.isNotEmpty()) {
                val filteredItems = shelfSnap.items.filterNot { item ->
                    if (item.progressFraction >= 0.90f) return@filterNot true
                    val watchlistLookupId = if (!item.isSeries) (item.seriesId ?: item.mediaId) else item.mediaId
                    val isExplicitWatchedInList = DesktopWatchlistManager.getStatusForMedia(
                        email = email,
                        title = item.title,
                        driveId = watchlistLookupId
                    ) == WatchlistStatus.WATCHED
                    val isActiveHeroRewatch = heroSnap != null &&
                        !heroSnap.confirmedEmpty &&
                        heroSnap.mediaId == item.mediaId &&
                        heroSnap.progressFraction < 0.90f
                    isExplicitWatchedInList && !isActiveHeroRewatch
                }
                if (filteredItems.isNotEmpty()) {
                    return enrichShelfSnapshotItems(email, filteredItems, movies, series)
                }
            }
            if (heroSnap != null && !heroSnap.confirmedEmpty && heroSnap.mediaId.isNotBlank()) {
                val singleItem = heroSnapshotToContinueWatchingItem(heroSnap)
                return enrichShelfSnapshotItems(email, listOf(singleItem), movies, series)
            }
            if (shelfSnap?.confirmedEmpty == true) {
                return emptyList()
            }
        }

        val items = mutableListOf<DesktopContinueWatchingItem>()
        val movieIds = movies.map { it.id }.toSet()

        // 1. In-Progress Movies from volatile RAM or archivedItems fallback (zero fallback to unwatched catalog items)
        for (movie in movies) {
            val volatileWatched = volatileEntries["${movie.id}_is_watched"]?.value as? Boolean
            if (volatileWatched == true || isEpisodeWatched(movie.id)) continue
            val hasVolatile = volatileEntries.containsKey("${movie.id}_pos") ||
                volatileEntries.containsKey("${movie.id}_fraction")
            val pos: Long
            val dur: Long
            val fraction: Float
            val ts: Long
            if (hasVolatile) {
                pos = (volatileEntries["${movie.id}_pos"]?.value as? Number)?.toLong() ?: 0L
                dur = (volatileEntries["${movie.id}_dur"]?.value as? Number)?.toLong() ?: movie.durationMs
                fraction = (volatileEntries["${movie.id}_fraction"]?.value as? Number)?.toFloat()
                    ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()) else 0f
                ts = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(
                    (volatileEntries["${movie.id}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                )
            } else {
                val arch = archivedItems[movie.id]
                if (arch == null || arch.isWatched) continue
                pos = arch.positionMs
                dur = if (arch.durationMs > 0L) arch.durationMs else movie.durationMs
                fraction = if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
                ts = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(arch.timestamp)
            }

            if (pos >= 3000L && fraction in 0.01f..0.90f && ts > 0L) {
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
            if (epId.isBlank() || isEpisodeWatched(epId)) continue

            val isMarkedMovie = volatileEntries["${epId}_is_series"]?.value == false
            if (movieIds.contains(epId) || isMarkedMovie) continue

            val pos = resume.positionMs
            val dur = resume.durationMs
            val ts = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(resume.lastWatchedTimestamp)

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

            val isOngoing = fraction in 0.005f..0.899f || (pos >= 3000L && fraction < 0.90f)
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

        // 3. Catalog-independent RAM/archive fallback for in-progress items not yet in movies/series
        val coveredMediaIds = items.map { it.mediaId }.toMutableSet()
        val coveredSeriesIds = items.mapNotNull { it.seriesId }.toMutableSet()
        val priorShelf = if (email.isNotBlank()) DesktopContinueWatchingSnapshotStore.readShelfSnapshot(email) else null
        val priorByMediaId = priorShelf?.items?.associateBy { it.mediaId }.orEmpty()
        val priorBySeriesId = priorShelf?.items?.filter { !it.seriesId.isNullOrBlank() }?.associateBy { it.seriesId!! }.orEmpty()

        val entries = snapshotEntries()
        val rawSeriesIds = entries.keys
            .filter { it.startsWith("series_resume_") && it.endsWith("_episode_id") }
            .map { it.removePrefix("series_resume_").removeSuffix("_episode_id") }
            .distinct()

        for (sid in rawSeriesIds) {
            if (sid in coveredSeriesIds) continue
            val prefix = "series_resume_$sid"
            val epId = (entries["${prefix}_episode_id"]?.value as? String)?.trim().orEmpty()
            if (epId.isBlank() || epId in coveredMediaIds || isEpisodeWatched(epId)) continue

            val sNum = (entries["${prefix}_season_num"]?.value as? Number)?.toInt() ?: 1
            val eNum = (entries["${prefix}_episode_num"]?.value as? Number)?.toInt() ?: 1
            val pos = (entries["${prefix}_pos_ms"]?.value as? Number)?.toLong() ?: 0L
            val dur = (entries["${prefix}_dur_ms"]?.value as? Number)?.toLong() ?: 0L
            val ts = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(
                (entries["${prefix}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            )
            val effDur = if (dur > 0L) dur else 45L * 60000L
            val frac = (pos.toFloat() / effDur.toFloat()).coerceIn(0f, 1f)
            val isOngoing = frac in 0.005f..0.899f || (pos >= 3000L && frac < 0.90f)
            val isQueuedNext = (pos == 0L && frac == 0f)
            if (ts <= 0L || (!isOngoing && !isQueuedNext)) continue

            val cachedShow = if (email.isNotBlank()) DesktopSeriesCache.get(email, sid) else null
            val epObj = cachedShow?.seasons?.flatMap { it.episodes }?.find { it.id == epId }
            val epTitle = epObj?.title
                ?: (entries["${prefix}_episode_title"]?.value as? String)
                ?: (entries["${epId}_title"]?.value as? String)
                ?: "Episode $eNum"
            val showTitle = cachedShow?.title?.takeIf { it.isNotBlank() } ?: priorBySeriesId[sid]?.title ?: epTitle
            val prior = priorByMediaId[epId] ?: priorBySeriesId[sid]
            val officialArt = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                candidateUrl = epObj?.thumbnailUrl
                    ?: DesktopCanonicalImageCache.getCanonicalUrl(DesktopCanonicalImageCache.heroKey(epId))
                    ?: cachedShow?.backdropUrl
                    ?: cachedShow?.posterUrl
                    ?: DesktopCanonicalImageCache.getCanonicalUrl(DesktopCanonicalImageCache.seriesPosterKey(sid)),
                existingUrl = prior?.thumbnailUrl
            )
            val officialFallback = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                candidateUrl = cachedShow?.posterUrl
                    ?: DesktopCanonicalImageCache.getCanonicalUrl(DesktopCanonicalImageCache.seriesPosterKey(sid)),
                existingUrl = prior?.fallbackThumbnailUrl
            )
            coveredMediaIds.add(epId)
            coveredSeriesIds.add(sid)
            items.add(
                DesktopContinueWatchingItem(
                    mediaId = epId,
                    title = showTitle,
                    subtitle = "S${sNum}E${eNum} • $epTitle",
                    thumbnailUrl = officialArt,
                    fallbackThumbnailUrl = officialFallback,
                    positionMs = pos,
                    durationMs = effDur,
                    progressFraction = if (pos > 0L) frac.coerceIn(0.04f, 1f) else 0.02f,
                    isSeries = true,
                    seriesId = sid,
                    seasonNumber = sNum,
                    episodeNumber = eNum,
                    episodeTitle = epTitle,
                    timestamp = ts
                )
            )
        }

        val rawFileIds = entries.keys
            .filter { it.endsWith("_timestamp") && !it.startsWith("series_resume_") && !it.startsWith("global_") }
            .map { it.removeSuffix("_timestamp") }
            .distinct()

        for (fileId in rawFileIds) {
            if (fileId in coveredMediaIds || isEpisodeWatched(fileId)) continue
            val isSeries = (entries["${fileId}_is_series"]?.value as? Boolean) == true
            val sid = (entries["${fileId}_series_id"]?.value as? String)?.trim()?.ifBlank { null }
            if (isSeries && !sid.isNullOrBlank() && sid in coveredSeriesIds) continue

            val pos = (entries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = (entries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
            val frac = (entries["${fileId}_fraction"]?.value as? Number)?.toFloat()
                ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()) else 0f
            val ts = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(
                (entries["${fileId}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            )
            if (pos < 3000L || frac !in 0.01f..0.90f || ts <= 0L) continue

            val sNum = (entries["${fileId}_season_num"]?.value as? Number)?.toInt() ?: 1
            val eNum = (entries["${fileId}_episode_num"]?.value as? Number)?.toInt() ?: 1
            val rawTitle = (entries["${fileId}_title"]?.value as? String).orEmpty().ifBlank { "Resume Watching" }
            val prior = priorByMediaId[fileId] ?: sid?.let { priorBySeriesId[it] }
            val officialArt = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                candidateUrl = DesktopCanonicalImageCache.getCanonicalUrl(DesktopCanonicalImageCache.heroKey(fileId))
                    ?: DesktopCanonicalImageCache.getCanonicalUrl(DesktopCanonicalImageCache.moviePosterKey(fileId))
                    ?: sid?.let { DesktopCanonicalImageCache.getCanonicalUrl(DesktopCanonicalImageCache.seriesPosterKey(it)) },
                existingUrl = prior?.thumbnailUrl
            )
            coveredMediaIds.add(fileId)
            if (sid != null) coveredSeriesIds.add(sid)
            items.add(
                DesktopContinueWatchingItem(
                    mediaId = fileId,
                    title = if (isSeries) (prior?.title ?: rawTitle) else rawTitle,
                    subtitle = if (isSeries) "S${sNum}E${eNum} • $rawTitle" else "Movie",
                    thumbnailUrl = officialArt,
                    fallbackThumbnailUrl = prior?.fallbackThumbnailUrl.orEmpty(),
                    positionMs = pos,
                    durationMs = dur,
                    progressFraction = frac.coerceIn(0.02f, 1f),
                    isSeries = isSeries,
                    seriesId = sid,
                    seasonNumber = sNum.takeIf { isSeries },
                    episodeNumber = eNum.takeIf { isSeries },
                    episodeTitle = if (isSeries) rawTitle else "",
                    timestamp = ts
                )
            )
        }

        for (arch in snapshotArchivedItems().values) {
            if (arch.id in coveredMediaIds || arch.isWatched || isEpisodeWatched(arch.id)) continue
            if (!arch.seriesId.isNullOrBlank() && arch.seriesId in coveredSeriesIds) continue
            val effDur = arch.durationMs.coerceAtLeast(0L)
            val frac = if (effDur > 0L) (arch.positionMs.toFloat() / effDur.toFloat()).coerceIn(0f, 1f) else 0f
            val ts = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(arch.timestamp)
            if (arch.positionMs < 3000L || frac >= 0.90f || ts <= 0L) continue

            val isSeries = !arch.seriesId.isNullOrBlank()
            val prior = priorByMediaId[arch.id] ?: arch.seriesId?.let { priorBySeriesId[it] }
            val officialArt = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                candidateUrl = DesktopCanonicalImageCache.getCanonicalUrl(DesktopCanonicalImageCache.heroKey(arch.id))
                    ?: DesktopCanonicalImageCache.getCanonicalUrl(DesktopCanonicalImageCache.moviePosterKey(arch.id)),
                existingUrl = prior?.thumbnailUrl
            )
            coveredMediaIds.add(arch.id)
            if (arch.seriesId != null) coveredSeriesIds.add(arch.seriesId)
            items.add(
                DesktopContinueWatchingItem(
                    mediaId = arch.id,
                    title = arch.title.ifBlank { prior?.title ?: "Resume Watching" },
                    subtitle = if (isSeries) "S${arch.seasonNumber.coerceAtLeast(1)}E${arch.episodeNumber.coerceAtLeast(1)}" else "Movie",
                    thumbnailUrl = officialArt,
                    fallbackThumbnailUrl = prior?.fallbackThumbnailUrl.orEmpty(),
                    positionMs = arch.positionMs,
                    durationMs = effDur,
                    progressFraction = frac.coerceIn(0.02f, 1f),
                    isSeries = isSeries,
                    seriesId = arch.seriesId,
                    seasonNumber = arch.seasonNumber.takeIf { it > 0 },
                    episodeNumber = arch.episodeNumber.takeIf { it > 0 },
                    timestamp = ts
                )
            )
        }

        return items.sortedByDescending { it.timestamp }.take(10)
    }

    private fun enrichShelfSnapshotItems(
        email: String,
        snapshotItems: List<DesktopContinueWatchingItem>,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ): List<DesktopContinueWatchingItem> {
        return snapshotItems.map { item ->
            if (item.isSeries) {
                val matchedShow = series.find { it.id == item.seriesId }
                val cachedShow = if (!item.seriesId.isNullOrBlank()) DesktopSeriesCache.get(email, item.seriesId) else null
                val targetEp = cachedShow?.seasons?.flatMap { it.episodes }?.find { it.id == item.mediaId }

                val upgradedThumb = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                    candidateUrl = targetEp?.thumbnailUrl ?: matchedShow?.thumbnailUrl ?: cachedShow?.backdropUrl ?: cachedShow?.posterUrl,
                    existingUrl = item.thumbnailUrl
                )
                val upgradedFallback = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                    candidateUrl = matchedShow?.fallbackThumbnailUrl ?: matchedShow?.thumbnailUrl ?: cachedShow?.posterUrl,
                    existingUrl = item.fallbackThumbnailUrl.ifBlank { item.thumbnailUrl }
                )
                val officialHeroUrl = upgradedThumb.ifBlank { upgradedFallback }
                if (DesktopCanonicalImageCache.classifyProvenance(officialHeroUrl) == ArtworkProvenance.OFFICIAL_CATALOG) {
                    DesktopCanonicalImageCache.registerOfficialArtworkUrl(
                        canonicalKey = DesktopCanonicalImageCache.heroKey(item.mediaId),
                        officialUrl = officialHeroUrl,
                        linkedMediaId = item.mediaId
                    )
                    if (!item.seriesId.isNullOrBlank()) {
                        val seriesUrl = upgradedFallback.ifBlank { officialHeroUrl }
                        if (DesktopCanonicalImageCache.classifyProvenance(seriesUrl) == ArtworkProvenance.OFFICIAL_CATALOG) {
                            DesktopCanonicalImageCache.registerOfficialArtworkUrl(
                                canonicalKey = DesktopCanonicalImageCache.seriesPosterKey(item.seriesId),
                                officialUrl = seriesUrl,
                                linkedMediaId = item.seriesId
                            )
                        }
                    }
                }
                item.copy(
                    title = matchedShow?.title?.takeIf { it.isNotBlank() } ?: item.title,
                    thumbnailUrl = upgradedThumb,
                    fallbackThumbnailUrl = upgradedFallback
                )
            } else {
                val matchedMovie = movies.find { it.id == item.mediaId }
                val upgradedThumb = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                    candidateUrl = matchedMovie?.thumbnailUrl,
                    existingUrl = item.thumbnailUrl
                )
                val upgradedFallback = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                    candidateUrl = matchedMovie?.fallbackThumbnailUrl,
                    existingUrl = item.fallbackThumbnailUrl.ifBlank { upgradedThumb }
                )
                val officialUrl = upgradedThumb.ifBlank { upgradedFallback }
                if (DesktopCanonicalImageCache.classifyProvenance(officialUrl) == ArtworkProvenance.OFFICIAL_CATALOG) {
                    DesktopCanonicalImageCache.registerOfficialArtworkUrl(
                        canonicalKey = DesktopCanonicalImageCache.heroKey(item.mediaId),
                        officialUrl = officialUrl,
                        linkedMediaId = item.mediaId
                    )
                    DesktopCanonicalImageCache.registerOfficialArtworkUrl(
                        canonicalKey = DesktopCanonicalImageCache.moviePosterKey(item.mediaId),
                        officialUrl = officialUrl,
                        linkedMediaId = item.mediaId
                    )
                }
                item.copy(
                    title = matchedMovie?.title?.takeIf { it.isNotBlank() } ?: item.title,
                    thumbnailUrl = upgradedThumb,
                    fallbackThumbnailUrl = upgradedFallback
                )
            }
        }.sortedByDescending { it.timestamp }
    }

    /**
     * Resolves the authoritative Hero [ContinueWatchingDisplayData] from current RAM state,
     * falling back to `DesktopLibraryCache`, `DesktopSeriesCache`, and raw `volatileEntries`
     * so Continue Watching still renders even before the Drive library scan finishes.
     *
     * Strictly guarantees [ContinueWatchingDisplayData.canonicalArtworkUrl] contains ONLY
     * official catalog artwork (or blank) — NEVER raw Drive frame URLs.
     */
    fun resolveLatestContinueWatching(
        email: String = "",
        movies: List<DesktopMediaItem> = emptyList(),
        series: List<DesktopMediaItem> = emptyList()
    ): ContinueWatchingDisplayData? {
        val effectiveEmail = resolveEffectiveEmail(email)
        val cachedLibrary = if ((movies.isEmpty() || series.isEmpty()) && effectiveEmail.isNotBlank()) {
            DesktopLibraryCache.loadSync(effectiveEmail)
        } else null

        val effectiveMovies = if (movies.isNotEmpty()) movies else cachedLibrary?.movies.orEmpty()
        val effectiveSeries = if (series.isNotEmpty()) series else cachedLibrary?.series.orEmpty()

        val catalogMatch = getContinueWatching(effectiveEmail, effectiveMovies, effectiveSeries).firstOrNull()
        val rawFallbackMatch = findNewestRawInProgressItem(effectiveEmail, effectiveMovies, effectiveSeries)

        val winner = when {
            catalogMatch != null && rawFallbackMatch != null ->
                if (rawFallbackMatch.timestamp > catalogMatch.timestamp) rawFallbackMatch else catalogMatch
            catalogMatch != null -> catalogMatch
            else -> rawFallbackMatch
        } ?: return null

        val matchedMovie = if (!winner.isSeries) effectiveMovies.find { it.id == winner.mediaId } else null
        val matchedSeries = if (winner.isSeries && !winner.seriesId.isNullOrBlank()) {
            effectiveSeries.find { it.id == winner.seriesId }
        } else null
        val cachedSeries = if (winner.isSeries && !winner.seriesId.isNullOrBlank() && effectiveEmail.isNotBlank()) {
            DesktopSeriesCache.get(effectiveEmail, winner.seriesId)
        } else null
        val cachedEpisode = cachedSeries?.seasons?.flatMap { it.episodes }?.find { it.id == winner.mediaId }

        val candidateHeroOfficial = DesktopCanonicalImageCache.getCanonicalUrl(
            DesktopCanonicalImageCache.heroKey(winner.mediaId)
        )
        val candidatePosterOfficial = if (winner.isSeries && !winner.seriesId.isNullOrBlank()) {
            DesktopCanonicalImageCache.getCanonicalUrl(DesktopCanonicalImageCache.seriesPosterKey(winner.seriesId))
        } else {
            DesktopCanonicalImageCache.getCanonicalUrl(DesktopCanonicalImageCache.moviePosterKey(winner.mediaId))
        }

        val catalogArtwork = if (winner.isSeries) {
            DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                candidateUrl = candidateHeroOfficial
                    ?: cachedEpisode?.thumbnailUrl
                    ?: winner.thumbnailUrl,
                existingUrl = candidatePosterOfficial
                    ?: cachedSeries?.backdropUrl?.takeIf { it.isNotBlank() }
                    ?: cachedSeries?.posterUrl?.takeIf { it.isNotBlank() }
                    ?: matchedSeries?.thumbnailUrl
            )
        } else {
            DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                candidateUrl = candidateHeroOfficial
                    ?: candidatePosterOfficial
                    ?: matchedMovie?.thumbnailUrl
                    ?: winner.thumbnailUrl
            )
        }

        if (catalogArtwork.isNotBlank() &&
            DesktopCanonicalImageCache.classifyProvenance(catalogArtwork) == ArtworkProvenance.OFFICIAL_CATALOG
        ) {
            DesktopCanonicalImageCache.registerOfficialArtworkUrl(
                canonicalKey = DesktopCanonicalImageCache.heroKey(winner.mediaId),
                officialUrl = catalogArtwork,
                linkedMediaId = winner.mediaId
            )
        }

        val resolvedSeriesTitle = if (winner.isSeries) {
            matchedSeries?.title?.takeIf { it.isNotBlank() }
                ?: cachedSeries?.title?.takeIf { it.isNotBlank() }
                ?: winner.title.takeIf { it.isNotBlank() }
        } else null

        val resolvedEpisodeTitle = if (winner.isSeries) {
            cachedEpisode?.title?.takeIf { it.isNotBlank() }
                ?: winner.episodeTitle.takeIf { it.isNotBlank() }
        } else null

        val resolvedDisplayTitle = if (winner.isSeries) {
            resolvedSeriesTitle ?: winner.title
        } else {
            matchedMovie?.title?.takeIf { it.isNotBlank() } ?: winner.title
        }

        return ContinueWatchingDisplayData(
            mediaId = winner.mediaId,
            seriesId = winner.seriesId,
            isSeries = winner.isSeries,
            displayHeader = "CONTINUE WATCHING",
            displayTitle = resolvedDisplayTitle,
            seriesTitle = resolvedSeriesTitle,
            seasonNumber = winner.seasonNumber,
            episodeNumber = winner.episodeNumber,
            episodeTitle = resolvedEpisodeTitle,
            canonicalArtworkUrl = catalogArtwork,
            progressFraction = winner.progressFraction.coerceIn(0f, 1f),
            currentPositionMs = winner.positionMs,
            durationMs = winner.durationMs,
            updatedAt = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(winner.timestamp),
            confirmedEmpty = false
        )
    }

    private fun findNewestRawInProgressItem(
        email: String,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ): DesktopContinueWatchingItem? {
        val entries = snapshotEntries()
        if (entries.isEmpty()) return null

        val candidates = mutableListOf<DesktopContinueWatchingItem>()

        // 1. Raw series_resume pointers (covers fresh installs before library scan completes)
        val resumeSeriesIds = entries.keys
            .filter { it.startsWith("series_resume_") && it.endsWith("_episode_id") }
            .map { it.removePrefix("series_resume_").removeSuffix("_episode_id") }
            .distinct()

        for (sid in resumeSeriesIds) {
            val prefix = "series_resume_$sid"
            val epId = (entries["${prefix}_episode_id"]?.value as? String)?.trim().orEmpty()
            if (epId.isBlank() || isEpisodeWatched(epId)) continue

            val sNum = (entries["${prefix}_season_num"]?.value as? Number)?.toInt() ?: 1
            val eNum = (entries["${prefix}_episode_num"]?.value as? Number)?.toInt() ?: 1
            val pos = (entries["${prefix}_pos_ms"]?.value as? Number)?.toLong() ?: 0L
            val dur = (entries["${prefix}_dur_ms"]?.value as? Number)?.toLong() ?: 0L
            val ts = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(
                (entries["${prefix}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            )
            val effDur = if (dur > 0L) dur else 45L * 60000L
            val frac = (pos.toFloat() / effDur.toFloat()).coerceIn(0f, 1f)
            val isOngoing = frac in 0.005f..0.899f || (pos >= 3000L && frac < 0.90f)
            val isQueuedNext = (pos == 0L && frac == 0f)
            if (ts <= 0L || (!isOngoing && !isQueuedNext)) continue

            val matchedShow = series.find { it.id == sid }
            val cachedShow = if (email.isNotBlank()) DesktopSeriesCache.get(email, sid) else null
            val epObj = cachedShow?.seasons?.flatMap { it.episodes }?.find { it.id == epId }
            val epTitle = epObj?.title
                ?: (entries["${prefix}_episode_title"]?.value as? String)
                ?: (entries["${epId}_title"]?.value as? String)
                ?: "Episode $eNum"
            val showTitle = matchedShow?.title ?: cachedShow?.title ?: epTitle
            val safeThumb = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                candidateUrl = epObj?.thumbnailUrl ?: matchedShow?.thumbnailUrl,
                existingUrl = cachedShow?.backdropUrl ?: cachedShow?.posterUrl
            )

            candidates.add(
                DesktopContinueWatchingItem(
                    mediaId = epId,
                    title = showTitle,
                    subtitle = "S${sNum}E${eNum} • $epTitle",
                    thumbnailUrl = safeThumb,
                    fallbackThumbnailUrl = "",
                    positionMs = pos,
                    durationMs = effDur,
                    progressFraction = if (pos > 0L) frac.coerceIn(0.04f, 1f) else 0.02f,
                    isSeries = true,
                    seriesId = sid,
                    seasonNumber = sNum,
                    episodeNumber = eNum,
                    episodeTitle = epTitle,
                    timestamp = ts
                )
            )
        }

        // 2. Raw non-series / uncataloged items in volatileEntries
        val rawFileIds = entries.keys
            .filter { it.endsWith("_timestamp") && !it.startsWith("series_resume_") && !it.startsWith("global_") }
            .map { it.removeSuffix("_timestamp") }
            .distinct()

        for (fileId in rawFileIds) {
            if (isEpisodeWatched(fileId)) continue
            val isSeries = (entries["${fileId}_is_series"]?.value as? Boolean) == true
            val sid = (entries["${fileId}_series_id"]?.value as? String)?.takeIf { it.isNotBlank() }
            if (isSeries && !sid.isNullOrBlank()) continue // handled via series_resume or catalog

            val pos = (entries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = (entries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
            val frac = (entries["${fileId}_fraction"]?.value as? Number)?.toFloat()
                ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()) else 0f
            val ts = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(
                (entries["${fileId}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            )
            if (pos < 3000L || frac !in 0.01f..0.90f || ts <= 0L) continue

            val matchedMovie = movies.find { it.id == fileId }
            val title = matchedMovie?.title ?: (entries["${fileId}_title"]?.value as? String).orEmpty().ifBlank { "Resume Watching" }
            val safeThumb = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                matchedMovie?.thumbnailUrl
            )
            candidates.add(
                DesktopContinueWatchingItem(
                    mediaId = fileId,
                    title = title,
                    subtitle = matchedMovie?.genre?.ifBlank { "Movie" } ?: "Movie",
                    thumbnailUrl = safeThumb,
                    fallbackThumbnailUrl = "",
                    positionMs = pos,
                    durationMs = if (dur > 0L) dur else (matchedMovie?.durationMs ?: 0L),
                    progressFraction = frac.coerceIn(0.02f, 1f),
                    isSeries = false,
                    timestamp = ts
                )
            )
        }

        // 3. In-progress fallback from archivedItems (for items evicted before priority protection)
        val seenCandidateIds = candidates.map { it.mediaId }.toSet()
        for (arch in snapshotArchivedItems().values) {
            if (arch.id in seenCandidateIds || arch.isWatched || isEpisodeWatched(arch.id)) continue
            val effDur = if (arch.durationMs > 0L) arch.durationMs else 0L
            val frac = if (effDur > 0L) (arch.positionMs.toFloat() / effDur.toFloat()).coerceIn(0f, 1f) else 0f
            val ts = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(arch.timestamp)
            if (arch.positionMs < 3000L || frac >= 0.90f || ts <= 0L) continue

            val matchedMovie = movies.find { it.id == arch.id }
            val matchedShow = arch.seriesId?.let { sid -> series.find { it.id == sid } }
            val title = matchedMovie?.title
                ?: matchedShow?.title
                ?: arch.title.ifBlank { "Resume Watching" }
            val safeThumb = DesktopContinueWatchingSnapshotStore.sanitizeCanonicalArtworkUrl(
                matchedMovie?.thumbnailUrl ?: matchedShow?.thumbnailUrl
            )
            candidates.add(
                DesktopContinueWatchingItem(
                    mediaId = arch.id,
                    title = title,
                    subtitle = if (!arch.seriesId.isNullOrBlank()) {
                        "S${arch.seasonNumber.coerceAtLeast(1)}E${arch.episodeNumber.coerceAtLeast(1)}"
                    } else {
                        matchedMovie?.genre?.ifBlank { "Movie" } ?: "Movie"
                    },
                    thumbnailUrl = safeThumb,
                    fallbackThumbnailUrl = "",
                    positionMs = arch.positionMs,
                    durationMs = if (effDur > 0L) effDur else (matchedMovie?.durationMs ?: 0L),
                    progressFraction = frac.coerceIn(0.02f, 1f),
                    isSeries = !arch.seriesId.isNullOrBlank(),
                    seriesId = arch.seriesId,
                    seasonNumber = arch.seasonNumber.takeIf { it > 0 },
                    episodeNumber = arch.episodeNumber.takeIf { it > 0 },
                    timestamp = ts
                )
            )
        }

        return candidates.maxByOrNull { it.timestamp }
    }

    private fun removeSeriesResumeKeys(working: MutableMap<String, DesktopTypedEntry?>, seriesId: String) {
        val prefix = "series_resume_${seriesId}_"
        working.keys.filter { it.startsWith(prefix) }.forEach { working.remove(it) }
    }

    private fun syncContinueWatchingSnapshotAndHero(
        email: String,
        mutationTs: Long = System.currentTimeMillis(),
        explicitClearIfEmpty: Boolean = true
    ) {
        val effectiveEmail = resolveEffectiveEmail(email)
        val cachedLibrary = if (effectiveEmail.isNotBlank()) DesktopLibraryCache.loadSync(effectiveEmail) else null
        val cachedMovies = cachedLibrary?.movies.orEmpty()
        val cachedSeries = cachedLibrary?.series.orEmpty()

        val cwData = resolveLatestContinueWatching(effectiveEmail, cachedMovies, cachedSeries)
        if (effectiveEmail.isNotBlank()) {
            val snapshotPayload = cwData ?: ContinueWatchingDisplayData(
                mediaId = "",
                confirmedEmpty = true,
                updatedAt = mutationTs
            )
            DesktopContinueWatchingSnapshotStore.writeSnapshotAsync(effectiveEmail, snapshotPayload)

            val shelfItems = getContinueWatching(effectiveEmail, cachedMovies, cachedSeries)
                .let { preservePriorShelfArtwork(effectiveEmail, it) }
            val shelfTs = getLatestTimestampMs().takeIf { it > 0L } ?: mutationTs
            val hydrated = hasHydratedMediaInRam()
            if (shelfItems.isNotEmpty() || hydrated) {
                DesktopContinueWatchingSnapshotStore.writeShelfSnapshotAsync(
                    userEmail = effectiveEmail,
                    items = shelfItems,
                    updatedAt = shelfTs,
                    confirmedEmpty = shelfItems.isEmpty() && hydrated
                )
            }
        }
        HeroSlotStateMachine.onContinueWatchingResolved(
            item = cwData,
            explicitClear = (cwData == null && explicitClearIfEmpty)
        )
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
            val ts = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(
                (volatileEntries["${fileId}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            )
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
                ts = DesktopContinueWatchingSnapshotStore.normalizeEpochMs(arch.timestamp),
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
        val removedFromArchive = synchronized(stateLock) {
            archivedWatchedIds.remove(mediaId) || (archivedItems.remove(mediaId) != null)
        }
        updateEntries {
            val toDelete = keys.filter { key ->
                key.startsWith("${mediaId}_") ||
                    (!seriesId.isNullOrBlank() && key.startsWith("series_resume_${seriesId}_"))
            }
            toDelete.forEach { remove(it) }
            this["global_latest_timestamp"] = DesktopTypedEntry("long", System.currentTimeMillis())
        }
        return removedFromArchive
    }

    fun removeHistoryItem(email: String, mediaId: String) {
        if (mediaId.isBlank()) return
        val effectiveEmail = resolveEffectiveEmail(email)
        val seriesId = volatileEntries["${mediaId}_series_id"]?.value as? String
            ?: archivedItems[mediaId]?.seriesId
        val archiveChanged = removeEntriesForMedia(mediaId, seriesId)
        syncContinueWatchingSnapshotAndHero(effectiveEmail, mutationTs = System.currentTimeMillis())
        DesktopWatchHistorySync.pushHistoryAsync(force = true, updateArchive = archiveChanged)
    }

    fun clearMediaWatchedFlag(email: String, mediaId: String) {
        if (mediaId.isBlank()) return
        val effectiveEmail = resolveEffectiveEmail(email)
        val now = System.currentTimeMillis()
        val archiveChanged = synchronized(stateLock) {
            archivedWatchedIds.remove(mediaId) || (archivedItems.remove(mediaId) != null)
        }
        val seriesId = volatileEntries["${mediaId}_series_id"]?.value as? String
        updateEntries {
            this["${mediaId}_is_watched"] = DesktopTypedEntry("boolean", false)
            this["${mediaId}_fraction"] = DesktopTypedEntry("float", 0.0f)
            this["${mediaId}_pos"] = DesktopTypedEntry("long", 0L)
            this["${mediaId}_timestamp"] = DesktopTypedEntry("long", now)
            this["global_latest_timestamp"] = DesktopTypedEntry("long", now)
            if (!seriesId.isNullOrBlank() &&
                this["series_resume_${seriesId}_episode_id"]?.value == mediaId
            ) {
                removeSeriesResumeKeys(this, seriesId)
            }
        }
        syncContinueWatchingSnapshotAndHero(effectiveEmail, mutationTs = now)
        DesktopWatchHistorySync.pushHistoryAsync(force = true, updateArchive = archiveChanged)
    }

    suspend fun clearAllHistory(email: String = "") {
        val effectiveEmail = resolveEffectiveEmail(email)
        val now = System.currentTimeMillis()
        clearInMemory(effectiveEmail)
        updateEntries {
            this["global_latest_timestamp"] = DesktopTypedEntry("long", now)
        }
        if (effectiveEmail.isNotBlank()) {
            DesktopContinueWatchingSnapshotStore.writeSnapshot(
                effectiveEmail,
                ContinueWatchingDisplayData(
                    mediaId = "",
                    confirmedEmpty = true,
                    updatedAt = now
                )
            )
            DesktopContinueWatchingSnapshotStore.writeShelfSnapshot(
                userEmail = effectiveEmail,
                items = emptyList(),
                updatedAt = now,
                confirmedEmpty = true
            )
        }
        HeroSlotStateMachine.onContinueWatchingResolved(null, explicitClear = true)
        DesktopWatchHistorySync.deleteRemoteHistoryFiles()
    }

    fun clearLocalHistory(email: String = "") {
        val effectiveEmail = resolveEffectiveEmail(email)
        val now = System.currentTimeMillis()
        clearInMemory(effectiveEmail)
        purgeAllLocalDiskCaches()
        updateEntries {
            this["global_latest_timestamp"] = DesktopTypedEntry("long", now)
        }
        if (effectiveEmail.isNotBlank()) {
            DesktopContinueWatchingSnapshotStore.writeSnapshotAsync(
                effectiveEmail,
                ContinueWatchingDisplayData(
                    mediaId = "",
                    confirmedEmpty = true,
                    updatedAt = now
                )
            )
            DesktopContinueWatchingSnapshotStore.writeShelfSnapshotAsync(
                userEmail = effectiveEmail,
                items = emptyList(),
                updatedAt = now,
                confirmedEmpty = true
            )
        }
        HeroSlotStateMachine.onContinueWatchingResolved(null, explicitClear = true)
        DesktopWatchHistorySync.pushHistoryAsync(force = true, updateArchive = true)
    }
}
