package com.clotube.desktop.data

import com.clotube.desktop.model.DesktopSeries
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object DesktopSeriesCache {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val cacheDir: File by lazy {
        val appData = System.getenv("APPDATA")
        val base = if (!appData.isNullOrBlank()) {
            File(appData, "Clotube/cache/series")
        } else {
            File(System.getProperty("user.home"), ".clotube/cache/series")
        }
        if (!base.exists()) {
            base.mkdirs()
        }
        base
    }

    private fun getFile(seriesId: String): File {
        val safeName = seriesId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(cacheDir, "series_$safeName.json")
    }

    fun get(seriesId: String): DesktopSeries? {
        return try {
            val file = getFile(seriesId)
            if (file.exists() && file.length() > 0) {
                val json = file.readText(Charsets.UTF_8)
                gson.fromJson(json, DesktopSeries::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            System.err.println("[DesktopSeriesCache] Failed to read series cache: ${e.message}")
            null
        }
    }

    fun get(email: String, seriesId: String): DesktopSeries? {
        if (email.isNotBlank()) {
            try {
                val profileFile = File(DesktopProfileManager.getSeriesDir(email), "series_${seriesId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.json")
                if (profileFile.exists() && profileFile.length() > 0) {
                    val json = profileFile.readText(Charsets.UTF_8)
                    val cached = gson.fromJson(json, DesktopSeries::class.java)
                    if (cached != null) return cached
                }
            } catch (_: Exception) {}
        }
        return get(seriesId)
    }

    fun getSync(seriesId: String): DesktopSeries? = get(seriesId)

    fun save(series: DesktopSeries) {
        try {
            val file = getFile(series.id)
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val json = gson.toJson(series)
            file.writeText(json, Charsets.UTF_8)
            println("[DesktopSeriesCache] Persisted cache for ${series.title} to: ${file.absolutePath}")
        } catch (e: Exception) {
            System.err.println("[DesktopSeriesCache] Failed to save series cache: ${e.message}")
        }
    }

    suspend fun save(email: String, series: DesktopSeries) = withContext(Dispatchers.IO) {
        save(series)
        if (email.isNotBlank()) {
            try {
                val profileDir = DesktopProfileManager.getSeriesDir(email)
                if (!profileDir.exists()) profileDir.mkdirs()
                val profileFile = File(profileDir, "series_${series.id.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.json")
                profileFile.writeText(gson.toJson(series), Charsets.UTF_8)
            } catch (_: Exception) {}
        }
    }

    fun saveSync(seriesId: String, series: DesktopSeries) {
        save(series)
    }

    fun evict(email: String, seriesId: String) {
        try {
            val file = getFile(seriesId)
            if (file.exists()) file.delete()
            if (email.isNotBlank()) {
                val profileFile = File(DesktopProfileManager.getSeriesDir(email), "series_${seriesId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.json")
                if (profileFile.exists()) profileFile.delete()
            }
        } catch (_: Exception) {}
    }
}
