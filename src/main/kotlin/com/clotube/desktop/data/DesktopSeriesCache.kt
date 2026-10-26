package com.clotube.desktop.data

import com.clotube.desktop.model.DesktopSeries
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object DesktopSeriesCache {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val memoryCache = ConcurrentHashMap<String, DesktopSeries>()

    private fun cacheKey(email: String, seriesId: String): String {
        return "${DesktopProfileManager.sanitizeEmail(email)}:$seriesId"
    }

    private fun getSeriesCacheFile(email: String, seriesId: String): File {
        val sanitized = seriesId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(DesktopProfileManager.getSeriesDir(email), "series_${sanitized}.json")
    }

    /**
     * Retrieves cached series metadata for a specific Google account profile.
     */
    fun get(email: String, seriesId: String): DesktopSeries? {
        if (email.isBlank() || seriesId.isBlank()) return null
        val key = cacheKey(email, seriesId)

        // 1. In-memory profile check
        memoryCache[key]?.let { cached ->
            if (isCacheClean(cached)) return cached else memoryCache.remove(key)
        }

        // 2. Profile disk check
        return try {
            val file = getSeriesCacheFile(email, seriesId)
            if (file.exists() && file.length() > 0) {
                val json = file.readText(Charsets.UTF_8)
                val type = object : TypeToken<DesktopSeries>() {}.type
                val cached: DesktopSeries? = gson.fromJson(json, type)
                if (cached != null && cached.seasons.isNotEmpty() && isCacheClean(cached)) {
                    memoryCache[key] = cached
                    println("[DesktopSeriesCache] Profile hit for [${DesktopProfileManager.sanitizeEmail(email)}]: ${cached.title} (${cached.seasons.sumOf { it.episodes.size }} eps)")
                    cached
                } else {
                    println("[DesktopSeriesCache] Invalidating dirty/outdated series cache for: ${cached?.title ?: seriesId}")
                    file.delete()
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            System.err.println("[DesktopSeriesCache] Error reading profile cache for $seriesId: ${e.message}")
            null
        }
    }

    private fun isCacheClean(series: DesktopSeries): Boolean {
        // If any episode contains raw torrent artifacts, treat as dirty so it re-resolves
        val badKeywords = listOf("MoviesMod", "Esubs", "Hindi", "WEB-DL", "x264", "HEVC", "1080p")
        val allTitles = series.seasons.flatMap { it.episodes }.map { it.title }
        val hasGarbage = allTitles.any { title -> badKeywords.any { kw -> title.contains(kw, ignoreCase = true) } }
        return !hasGarbage
    }

    /**
     * Persists series metadata into the user's isolated profile.
     */
    suspend fun save(email: String, series: DesktopSeries) = withContext(Dispatchers.IO) {
        if (email.isBlank() || series.id.isBlank() || series.seasons.isEmpty()) return@withContext
        val key = cacheKey(email, series.id)
        try {
            memoryCache[key] = series
            val file = getSeriesCacheFile(email, series.id)
            val json = gson.toJson(series)
            file.writeText(json, Charsets.UTF_8)
            println("[DesktopSeriesCache] Saved [${DesktopProfileManager.sanitizeEmail(email)}] series: ${series.title}")
        } catch (e: Exception) {
            System.err.println("[DesktopSeriesCache] Error saving cache for ${series.title}: ${e.message}")
        }
    }

    /**
     * Clears profile cache for a specific series.
     */
    fun evict(email: String, seriesId: String) {
        memoryCache.remove(cacheKey(email, seriesId))
        try {
            val file = getSeriesCacheFile(email, seriesId)
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }
}
