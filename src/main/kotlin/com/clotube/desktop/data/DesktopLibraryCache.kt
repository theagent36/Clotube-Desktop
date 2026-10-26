package com.clotube.desktop.data

import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

typealias DesktopSeriesEpisode = DesktopEpisode

data class DesktopUserLibraryData(
    val email: String = "",
    val lastUpdated: Long = System.currentTimeMillis(),
    val movies: List<DesktopMediaItem> = emptyList(),
    val series: List<DesktopMediaItem> = emptyList()
) {
    val userEmail: String get() = email
    val timestamp: Long get() = lastUpdated
}

typealias CachedLibrary = DesktopUserLibraryData

object DesktopLibraryCache {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val memoryCache = ConcurrentHashMap<String, DesktopUserLibraryData>()

    /**
     * Defensively sanitizes any DesktopMediaItem deserialized via Gson reflection,
     * guaranteeing all String fields are authentic non-null objects.
     */
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

    /**
     * Loads library cache synchronously for the specified account profile.
     * Sanitizes all legacy items on the fly to eliminate NullPointerExceptions.
     */
    fun loadSync(email: String): DesktopUserLibraryData? {
        if (email.isBlank()) return null
        val sanitizedEmail = DesktopProfileManager.sanitizeEmail(email)

        memoryCache[sanitizedEmail]?.let { return it }

        val profileFile = DesktopProfileManager.getLibraryFile(email)

        // Legacy file migration check
        if (!profileFile.exists()) {
            val legacyFile = File("clotube_library_cache.json")
            if (legacyFile.exists() && legacyFile.length() > 0) {
                try {
                    legacyFile.copyTo(profileFile, overwrite = true)
                    println("[DesktopLibraryCache] Migrated legacy cache to profile: ${profileFile.absolutePath}")
                } catch (_: Exception) {}
            }
        }

        return try {
            if (profileFile.exists() && profileFile.length() > 0) {
                val json = profileFile.readText(Charsets.UTF_8)
                val type = object : TypeToken<DesktopUserLibraryData>() {}.type
                val rawData: DesktopUserLibraryData? = gson.fromJson(json, type)

                if (rawData != null) {
                    val cleanMovies = (rawData.movies ?: emptyList()).map { sanitizeMediaItem(it) }
                    val cleanSeries = (rawData.series ?: emptyList()).map { sanitizeMediaItem(it) }

                    val sanitizedData = DesktopUserLibraryData(
                        email = if (!rawData.email.isNullOrBlank()) rawData.email else email,
                        lastUpdated = rawData.lastUpdated,
                        movies = cleanMovies,
                        series = cleanSeries
                    )

                    if (cleanMovies.isNotEmpty() || cleanSeries.isNotEmpty()) {
                        memoryCache[sanitizedEmail] = sanitizedData
                        println("[DesktopLibraryCache] Loaded and sanitized profile library for $email: ${cleanMovies.size} movies, ${cleanSeries.size} series")
                        sanitizedData
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            System.err.println("[DesktopLibraryCache] Error loading profile for $email: ${e.message}")
            null
        }
    }

    /**
     * Saves library data asynchronously into the user's profile.
     */
    suspend fun save(
        email: String,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ) = withContext(Dispatchers.IO) {
        if (email.isBlank()) return@withContext
        val sanitizedEmail = DesktopProfileManager.sanitizeEmail(email)
        val cleanMovies = movies.map { sanitizeMediaItem(it) }
        val cleanSeries = series.map { sanitizeMediaItem(it) }

        val data = DesktopUserLibraryData(
            email = email,
            lastUpdated = System.currentTimeMillis(),
            movies = cleanMovies,
            series = cleanSeries
        )

        memoryCache[sanitizedEmail] = data

        try {
            val file = DesktopProfileManager.getLibraryFile(email)
            val json = gson.toJson(data)
            file.writeText(json, Charsets.UTF_8)
            println("[DesktopLibraryCache] Persisted profile library for $email: ${cleanMovies.size} movies, ${cleanSeries.size} series")
        } catch (e: Exception) {
            System.err.println("[DesktopLibraryCache] Error saving library for $email: ${e.message}")
        }
    }

    fun clearMemory(email: String) {
        memoryCache.remove(DesktopProfileManager.sanitizeEmail(email))
    }
}
