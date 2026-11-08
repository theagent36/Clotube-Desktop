package com.clotube.desktop.data

import com.clotube.desktop.model.DesktopEpisode
import com.clotube.desktop.model.DesktopMediaItem
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

typealias DesktopSeriesEpisode = DesktopEpisode

data class CachedLibrary(
    val userEmail: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val movies: List<DesktopMediaItem> = emptyList(),
    val series: List<DesktopMediaItem> = emptyList()
)

object DesktopLibraryCache {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val cacheFile: File by lazy {
        val appData = System.getenv("APPDATA")
        val base = if (!appData.isNullOrBlank()) {
            File(appData, "Clotube/cache")
        } else {
            File(System.getProperty("user.home"), ".clotube/cache")
        }
        if (!base.exists()) base.mkdirs()
        File(base, "clotube_library_cache.json")
    }

    fun loadSync(): CachedLibrary? {
        return try {
            if (cacheFile.exists() && cacheFile.length() > 0) {
                val json = cacheFile.readText(Charsets.UTF_8)
                val type = object : TypeToken<CachedLibrary>() {}.type
                gson.fromJson<CachedLibrary>(json, type)
            } else {
                null
            }
        } catch (e: Exception) {
            System.err.println("[DesktopLibraryCache] Failed to load library cache: ${e.message}")
            null
        }
    }

    fun loadSync(userEmail: String): CachedLibrary? {
        if (userEmail.isNotBlank()) {
            try {
                val profileFile = DesktopProfileManager.getLibraryFile(userEmail)
                if (profileFile.exists() && profileFile.length() > 0) {
                    val json = profileFile.readText(Charsets.UTF_8)
                    val type = object : TypeToken<CachedLibrary>() {}.type
                    val cached: CachedLibrary? = gson.fromJson(json, type)
                    if (cached != null && (cached.movies.isNotEmpty() || cached.series.isNotEmpty())) {
                        return cached
                    }
                }
            } catch (_: Exception) {}
        }
        return loadSync()
    }

    suspend fun save(movies: List<DesktopMediaItem>, series: List<DesktopMediaItem>) = withContext(Dispatchers.IO) {
        try {
            val cached = CachedLibrary(
                timestamp = System.currentTimeMillis(),
                movies = movies,
                series = series
            )
            val json = gson.toJson(cached)
            cacheFile.writeText(json, Charsets.UTF_8)
            println("[DesktopLibraryCache] Persisted library cache to: ${cacheFile.absolutePath}")
        } catch (e: Exception) {
            System.err.println("[DesktopLibraryCache] Failed to save library cache: ${e.message}")
        }
    }

    suspend fun save(
        userEmail: String,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ) = withContext(Dispatchers.IO) {
        save(movies, series)
        if (userEmail.isNotBlank()) {
            try {
                val file = DesktopProfileManager.getLibraryFile(userEmail)
                val cached = CachedLibrary(
                    userEmail = userEmail,
                    timestamp = System.currentTimeMillis(),
                    movies = movies,
                    series = series
                )
                file.writeText(gson.toJson(cached), Charsets.UTF_8)
            } catch (_: Exception) {}
        }
    }

    fun clearMemory(userEmail: String = "") {
        // Memory cache cleared if applicable
    }
}
