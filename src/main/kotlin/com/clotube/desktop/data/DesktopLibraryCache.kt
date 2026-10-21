package com.clotube.desktop.data

import com.clotube.desktop.model.DesktopMediaItem
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CachedLibrary(
    val timestamp: Long = System.currentTimeMillis(),
    val movies: List<DesktopMediaItem> = emptyList(),
    val series: List<DesktopMediaItem> = emptyList()
)

object DesktopLibraryCache {
    private val cacheFile = File("clotube_library_cache.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

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
            System.err.println("[DesktopLibraryCache] Failed to load cache: ${e.message}")
            null
        }
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
            println("[DesktopLibraryCache] Saved ${movies.size} movies and ${series.size} series to local disk cache.")
        } catch (e: Exception) {
            System.err.println("[DesktopLibraryCache] Failed to save cache: ${e.message}")
        }
    }
}
