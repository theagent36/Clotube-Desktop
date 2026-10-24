package com.clotube.desktop.data

import com.clotube.desktop.model.DesktopMediaItem
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CachedLibrary(
    val userEmail: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val movies: List<DesktopMediaItem> = emptyList(),
    val series: List<DesktopMediaItem> = emptyList()
)

object DesktopLibraryCache {
    private val cacheDirectory = File("cache").apply {
        if (!exists()) mkdirs()
    }
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun getAccountCacheFile(userEmail: String): File {
        val sanitized = if (userEmail.isNotBlank()) {
            userEmail.lowercase().trim().replace(Regex("[^a-z0-9_]"), "_")
        } else {
            "anonymous_guest"
        }
        return File(cacheDirectory, "library_${sanitized}.json")
    }

    /**
     * Loads the cached library specifically for the given user email.
     * Returns null if no cache exists for this account.
     */
    fun loadSync(userEmail: String): CachedLibrary? {
        if (userEmail.isBlank()) return null
        return try {
            val file = getAccountCacheFile(userEmail)
            if (file.exists() && file.length() > 0) {
                val json = file.readText(Charsets.UTF_8)
                val type = object : TypeToken<CachedLibrary>() {}.type
                val cached: CachedLibrary? = gson.fromJson(json, type)
                if (cached != null && cached.userEmail.equals(userEmail, ignoreCase = true)) {
                    if (cached.movies.any { DesktopOnlineMetadataResolver.isTitleDirty(it.title) }) {
                        val sanitizedMovies = cached.movies.map { item ->
                            if (DesktopOnlineMetadataResolver.isTitleDirty(item.title)) {
                                val syncMeta = DesktopOnlineMetadataResolver.resolveMovieSync(item.title)
                                item.copy(
                                    title = syncMeta.officialTitle.ifBlank { DesktopOnlineMetadataResolver.cleanTitleQuick(item.title) },
                                    thumbnailUrl = if (syncMeta.posterUrl.isNotBlank() && (item.thumbnailUrl.isNullOrBlank() || item.thumbnailUrl.contains("googleusercontent.com"))) {
                                        syncMeta.posterUrl
                                    } else item.thumbnailUrl,
                                    genre = if (item.genre == "Action • Drama" || item.genre.isBlank()) syncMeta.genre else item.genre,
                                    language = if (item.language.isBlank()) syncMeta.language else item.language
                                )
                            } else {
                                item
                            }
                        }
                        cached.copy(movies = sanitizedMovies)
                    } else {
                        cached
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            System.err.println("[DesktopLibraryCache] Failed to load cache for $userEmail: ${e.message}")
            null
        }
    }

    /**
     * Persists the media library partition for the specific user account.
     */
    suspend fun save(
        userEmail: String,
        movies: List<DesktopMediaItem>,
        series: List<DesktopMediaItem>
    ) = withContext(Dispatchers.IO) {
        if (userEmail.isBlank()) return@withContext
        try {
            val file = getAccountCacheFile(userEmail)
            val cached = CachedLibrary(
                userEmail = userEmail,
                timestamp = System.currentTimeMillis(),
                movies = movies,
                series = series
            )
            val json = gson.toJson(cached)
            file.writeText(json, Charsets.UTF_8)
            println("[DesktopLibraryCache] Saved ${movies.size} movies and ${series.size} series for account: $userEmail")
        } catch (e: Exception) {
            System.err.println("[DesktopLibraryCache] Failed to save cache for $userEmail: ${e.message}")
        }
    }

    /**
     * Clears local cache for a specific account.
     */
    fun clearCacheForUser(userEmail: String) {
        try {
            val file = getAccountCacheFile(userEmail)
            if (file.exists()) {
                file.delete()
                println("[DesktopLibraryCache] Deleted cache file for: $userEmail")
            }
        } catch (_: Exception) {}
    }
}
