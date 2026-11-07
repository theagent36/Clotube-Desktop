package com.clotube.desktop.data

import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

typealias DesktopSeriesEpisode = DesktopEpisode

data class CachedLibrary(
    val userEmail: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val movies: List<DesktopMediaItem> = emptyList(),
    val series: List<DesktopMediaItem> = emptyList()
)

object DesktopLibraryCache {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val memoryCache = ConcurrentHashMap<String, CachedLibrary>()

    fun sanitizeMediaItem(raw: DesktopMediaItem?): DesktopMediaItem {
        if (raw == null) return DesktopMediaItem()
        val safeId = raw.id ?: ""
        val safeTitle = raw.title ?: "Untitled"
        val safeThumb = raw.thumbnailUrl ?: ""
        val safeFallback = raw.fallbackThumbnailUrl ?: if (safeId.isNotBlank()) "https://drive.google.com/thumbnail?id=$safeId&sz=w800" else ""
        val safeStream = raw.streamUrl ?: ""
        val safeGenre = raw.genre ?: "Movie"
        val safeYear = raw.releaseYear ?: ""
        val safeLang = raw.language ?: "English"

        return DesktopMediaItem(
            id = safeId,
            title = safeTitle,
            thumbnailUrl = if (safeThumb.isNotBlank()) safeThumb else safeFallback,
            fallbackThumbnailUrl = safeFallback,
            streamUrl = safeStream,
            durationMs = raw.durationMs,
            genre = safeGenre,
            releaseYear = safeYear,
            language = safeLang,
            isSeries = raw.isSeries
        )
    }

    fun loadSync(userEmail: String): CachedLibrary? {
        if (userEmail.isBlank()) return null
        val sanitized = DesktopProfileManager.sanitizeEmail(userEmail)
        memoryCache[sanitized]?.let { return it }

        return try {
            val file = DesktopProfileManager.getLibraryFile(userEmail)
            if (file.exists() && file.length() > 0) {
                val json = file.readText(Charsets.UTF_8)
                val type = object : TypeToken<CachedLibrary>() {}.type
                val cached: CachedLibrary? = gson.fromJson(json, type)
                if (cached != null && cached.userEmail.equals(userEmail, ignoreCase = true)) {
                    val cleanMovies = (cached.movies ?: emptyList()).map { sanitizeMediaItem(it) }
                    val cleanSeries = (cached.series ?: emptyList()).map { sanitizeMediaItem(it) }
                    val sanitizedCached = cached.copy(movies = cleanMovies, series = cleanSeries)
                    memoryCache[sanitized] = sanitizedCached
                    sanitizedCached
                } else null
            } else null
        } catch (e: Exception) {
            System.err.println("[DesktopLibraryCache] Failed to load cache for $userEmail: ${e.message}")
            null
        }
    }

    suspend fun save(
        userEmail: String,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ) = withContext(Dispatchers.IO) {
        if (userEmail.isBlank()) return@withContext
        val sanitized = DesktopProfileManager.sanitizeEmail(userEmail)
        val cleanMovies = movies.map { sanitizeMediaItem(it) }
        val cleanSeries = series.map { sanitizeMediaItem(it) }
        try {
            val file = DesktopProfileManager.getLibraryFile(userEmail)
            val cached = CachedLibrary(
                userEmail = userEmail,
                timestamp = System.currentTimeMillis(),
                movies = cleanMovies,
                series = cleanSeries
            )
            memoryCache[sanitized] = cached
            file.writeText(gson.toJson(cached), Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("[DesktopLibraryCache] Failed to save cache for $userEmail: ${e.message}")
        }
    }

    fun clearCacheForUser(userEmail: String) {
        clearMemory(userEmail)
        try {
            val file = DesktopProfileManager.getLibraryFile(userEmail)
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    fun clearMemory(userEmail: String) {
        if (userEmail.isBlank()) return
        memoryCache.remove(DesktopProfileManager.sanitizeEmail(userEmail))
    }
}
