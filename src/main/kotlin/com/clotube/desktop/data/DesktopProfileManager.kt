package com.clotube.desktop.data

import java.io.File

object DesktopProfileManager {
    val appDataRoot: File by lazy {
        val appData = System.getenv("APPDATA")
        val base = if (!appData.isNullOrBlank()) {
            File(appData, "Clotube")
        } else {
            File(System.getProperty("user.home"), ".clotube")
        }
        if (!base.exists()) base.mkdirs()
        base
    }

    fun sanitizeEmail(email: String): String {
        return email.lowercase().trim().replace(Regex("[^a-z0-9_.-]"), "_")
    }

    fun getProfileDir(email: String): File {
        val sanitized = sanitizeEmail(email).ifBlank { "default_user" }
        val dir = File(File(appDataRoot, "profiles"), sanitized)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getLibraryFile(email: String): File = File(getProfileDir(email), "library.json")

    fun getWatchHistoryFile(email: String): File = File(getProfileDir(email), "watch_history.json")

    fun getSeriesDir(email: String): File {
        val dir = File(getProfileDir(email), "series")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getThumbnailsDir(email: String): File {
        val dir = File(getProfileDir(email), "thumbnails")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
