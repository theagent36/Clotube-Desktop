package com.clotube.desktop.data

import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.components.ThumbnailSize
import com.clotube.desktop.ui.components.downloadToDiskSilently
import com.clotube.desktop.ui.components.isThumbnailCachedOnDisk
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight, non-intrusive thumbnail prefetching engine:
 * 1. Sequential execution on a single background worker thread (zero thread contention).
 * 2. 350ms delay between items prevents bandwidth spikes or Drive rate limits.
 * 3. 0ms skip for items already present on SSD disk.
 * 4. Zero VRAM / Compose memory usage: Writes directly to disk cache files without holding decoded textures.
 */
object DesktopThumbnailPrefetcher {

    private data class PrefetchTask(
        val primaryUrl: String?,
        val fallbackUrl: String?,
        val accessToken: String,
        val size: ThumbnailSize
    )

    private val taskQueue = ConcurrentLinkedQueue<PrefetchTask>()
    private val enqueuedKeys = ConcurrentHashMap.newKeySet<String>()

    // Single-threaded worker strictly isolated from UI and video rendering
    private val prefetchDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val prefetchScope = CoroutineScope(prefetchDispatcher + SupervisorJob())

    private val isRunning = AtomicBoolean(false)

    /**
     * Enqueues movies, series, and all known season episodes for silent background caching.
     */
    fun enqueueAll(
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>,
        accessToken: String,
        userEmail: String
    ) {
        if (accessToken.isBlank()) return

        var addedCount = 0

        // 1. Enqueue Movies
        for (movie in movies) {
            if (enqueueItem(movie.thumbnailUrl, movie.fallbackThumbnailUrl, accessToken, ThumbnailSize.CARD)) {
                addedCount++
            }
        }

        // 2. Enqueue Series Posters
        for (show in series) {
            if (enqueueItem(show.thumbnailUrl, show.fallbackThumbnailUrl, accessToken, ThumbnailSize.CARD)) {
                addedCount++
            }
        }

        // 3. Enqueue Cached Episodes for all Series
        if (userEmail.isNotBlank()) {
            try {
                val library = DesktopLibraryCache.loadSync(userEmail)
                library?.seriesEpisodes?.values?.forEach { episodeList ->
                    for (ep in episodeList) {
                        val driveThumb = "https://drive.google.com/thumbnail?id=${ep.id}&sz=w800"
                        if (enqueueItem(ep.thumbnailUrl, driveThumb, accessToken, ThumbnailSize.CARD)) {
                            addedCount++
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Also check DesktopSeriesCache for any series saved by DesktopSeriesCache
        for (show in series) {
            try {
                val cachedSeries = if (userEmail.isNotBlank()) {
                    DesktopSeriesCache.get(userEmail, show.id) ?: DesktopSeriesCache.get(show.id)
                } else {
                    DesktopSeriesCache.get(show.id)
                }
                cachedSeries?.seasons?.forEach { season ->
                    for (ep in season.episodes) {
                        val driveThumb = "https://drive.google.com/thumbnail?id=${ep.id}&sz=w800"
                        if (enqueueItem(ep.thumbnailUrl, driveThumb, accessToken, ThumbnailSize.CARD)) {
                            addedCount++
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (addedCount > 0) {
            startWorkerIfNeeded()
        }
    }

    /**
     * Enqueues a single media item (e.g., when a series episode list finishes scanning).
     */
    fun enqueueSingle(primaryUrl: String?, fallbackUrl: String?, accessToken: String) {
        if (enqueueItem(primaryUrl, fallbackUrl, accessToken, ThumbnailSize.CARD)) {
            startWorkerIfNeeded()
        }
    }

    private fun enqueueItem(
        primaryUrl: String?,
        fallbackUrl: String?,
        accessToken: String,
        size: ThumbnailSize
    ): Boolean {
        val effectiveUrl = primaryUrl?.ifBlank { null } ?: fallbackUrl?.ifBlank { null } ?: return false
        val key = "${effectiveUrl}_${size.name}"

        // Deduplication & Disk-check: Skip if already enqueued or already cached on disk
        if (enqueuedKeys.contains(key) || isThumbnailCachedOnDisk(effectiveUrl, size)) {
            return false
        }

        enqueuedKeys.add(key)
        taskQueue.add(PrefetchTask(primaryUrl, fallbackUrl, accessToken, size))
        return true
    }

    private fun startWorkerIfNeeded() {
        if (isRunning.compareAndSet(false, true)) {
            prefetchScope.launch {
                try {
                    while (isActive) {
                        val task = taskQueue.poll()
                        if (task == null) {
                            // Queue exhausted: pause engine until new items are enqueued
                            break
                        }

                        val effectiveUrl = task.primaryUrl?.ifBlank { null } ?: task.fallbackUrl?.ifBlank { null }
                        if (effectiveUrl != null && !isThumbnailCachedOnDisk(effectiveUrl, task.size)) {
                            // Silently write downsampled JPEG directly to disk
                            downloadToDiskSilently(
                                primaryUrl = task.primaryUrl,
                                fallbackUrl = task.fallbackUrl,
                                accessToken = task.accessToken,
                                size = task.size
                            )

                            // Polite delay between downloads to preserve bandwidth and eliminate CPU spikes
                            delay(350)
                        }
                    }
                } finally {
                    isRunning.set(false)
                    // If items were enqueued while exiting, re-check
                    if (!taskQueue.isEmpty()) {
                        startWorkerIfNeeded()
                    }
                }
            }
        }
    }
}

