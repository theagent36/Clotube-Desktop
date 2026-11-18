package com.clotube.desktop.data

import androidx.compose.runtime.mutableIntStateOf
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

data class RecentSearchEntry(
    val query: String = "",
    val timestamp: Long = 0L
)

private data class RecentSearchesEnvelope(
    val version: Int = 1,
    val queries: List<RecentSearchEntry> = emptyList()
)

/**
 * Strictly local-only per-profile recent search history store.
 *
 * Persists at `%APPDATA%\Clotube\profiles\<sanitized_email>\local_recent_searches.json`
 * via atomic temp-file replacement and is NEVER uploaded or synced to Google Drive.
 */
object DesktopSearchHistoryManager {

    private const val FILE_NAME = "local_recent_searches.json"
    private const val TEMP_FILE_NAME = "local_recent_searches.json.tmp"
    private const val SCHEMA_VERSION = 1
    private const val MAX_RECENT_SEARCHES = 20
    private const val MIN_QUERY_LENGTH = 2

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Keyed by sanitized profile email; stores immutable snapshots sorted newest-first
    private val memoryCache = ConcurrentHashMap<String, List<RecentSearchEntry>>()
    private val profileMutationLocks = ConcurrentHashMap<String, Any>()
    private val profileDiskLocks = ConcurrentHashMap<String, Any>()

    /**
     * Observable Compose revision counter incremented on every insert, delete, or clear
     * so search dropdowns and history rows recompose immediately.
     */
    val recentSearchesRevision = mutableIntStateOf(0)

    /**
     * Returns up to 20 recent search entries for [email], ordered newest-first.
     */
    fun getRecentSearches(email: String): List<RecentSearchEntry> {
        val key = profileKey(email)
        memoryCache[key]?.let { return it }

        synchronized(mutationLock(key)) {
            memoryCache[key]?.let { return it }
            val loaded = loadFromDisk(email)
            memoryCache[key] = loaded
            return loaded
        }
    }

    /**
     * Records a search query for [email]:
     * - Trims whitespace and ignores blank queries or queries shorter than 2 characters.
     * - Deduplicates case-insensitively, prepends the fresh entry at index 0, caps at 20 items.
     * - Updates in-memory cache immediately and writes atomically on [Dispatchers.IO].
     */
    fun recordSearchQuery(email: String, rawQuery: String) {
        val cleanQuery = rawQuery.trim()
        if (cleanQuery.length < MIN_QUERY_LENGTH) return

        val key = profileKey(email)
        val normalizedNew = cleanQuery.lowercase()

        synchronized(mutationLock(key)) {
            val current = memoryCache[key] ?: loadFromDisk(email)
            val updated = ArrayList<RecentSearchEntry>(MAX_RECENT_SEARCHES).apply {
                add(RecentSearchEntry(query = cleanQuery, timestamp = System.currentTimeMillis()))
                for (entry in current) {
                    if (size >= MAX_RECENT_SEARCHES) break
                    if (entry.query.trim().lowercase() != normalizedNew) {
                        add(entry)
                    }
                }
            }
            memoryCache[key] = updated
            recentSearchesRevision.intValue++
        }

        persistAsync(email, key)
    }

    /**
     * Removes any entry matching [query] (case-insensitive) for [email] and persists atomically.
     */
    fun removeSearchQuery(email: String, query: String) {
        val targetNormalized = query.trim().lowercase()
        if (targetNormalized.isBlank()) return

        val key = profileKey(email)
        var changed = false

        synchronized(mutationLock(key)) {
            val current = memoryCache[key] ?: loadFromDisk(email)
            val updated = current.filterNot { it.query.trim().lowercase() == targetNormalized }
            if (updated.size != current.size) {
                memoryCache[key] = updated
                recentSearchesRevision.intValue++
                changed = true
            }
        }

        if (changed) {
            persistAsync(email, key)
        }
    }

    /**
     * Clears all recent search entries for [email] and persists the empty state atomically.
     */
    fun clearRecentSearches(email: String) {
        val key = profileKey(email)

        synchronized(mutationLock(key)) {
            memoryCache[key] = emptyList()
            recentSearchesRevision.intValue++
        }

        persistAsync(email, key)
    }

    /**
     * Drops cached in-memory entries (e.g. on sign-out/profile switch) without deleting local disk data.
     */
    fun clearMemoryCache(email: String? = null) {
        if (email.isNullOrBlank()) {
            memoryCache.clear()
        } else {
            memoryCache.remove(profileKey(email))
        }
    }

    // -------------------------------------------------------------------------
    // LOCAL FILESYSTEM PERSISTENCE (STRICTLY LOCAL — NEVER UPLOADED TO DRIVE)
    // -------------------------------------------------------------------------

    private fun profileKey(email: String): String =
        DesktopProfileManager.sanitizeEmail(email).ifBlank { "default_user" }

    private fun mutationLock(key: String): Any =
        profileMutationLocks.computeIfAbsent(key) { Any() }

    private fun diskLock(key: String): Any =
        profileDiskLocks.computeIfAbsent(key) { Any() }

    private fun resolveTargetFile(email: String): File =
        File(DesktopProfileManager.getProfileDir(email), FILE_NAME)

    private fun resolveTempFile(email: String): File =
        File(DesktopProfileManager.getProfileDir(email), TEMP_FILE_NAME)

    private fun loadFromDisk(email: String): List<RecentSearchEntry> {
        val file = resolveTargetFile(email)
        if (!file.exists() || file.length() <= 0L) return emptyList()

        return try {
            val json = file.readText(Charsets.UTF_8)
            val envelope = gson.fromJson(json, RecentSearchesEnvelope::class.java)
            sanitizeEntries(envelope?.queries.orEmpty())
        } catch (e: Exception) {
            System.err.println("[DesktopSearchHistoryManager] Failed to read $FILE_NAME: ${e.message}")
            emptyList()
        }
    }

    private fun sanitizeEntries(raw: List<RecentSearchEntry>): List<RecentSearchEntry> {
        if (raw.isEmpty()) return emptyList()

        val sorted = raw
            .mapNotNull { entry ->
                val clean = entry.query.trim()
                if (clean.length >= MIN_QUERY_LENGTH) {
                    RecentSearchEntry(query = clean, timestamp = entry.timestamp)
                } else {
                    null
                }
            }
            .sortedByDescending { it.timestamp }

        val seen = HashSet<String>(MAX_RECENT_SEARCHES)
        val deduped = ArrayList<RecentSearchEntry>(MAX_RECENT_SEARCHES)
        for (entry in sorted) {
            if (seen.add(entry.query.lowercase())) {
                deduped.add(entry)
                if (deduped.size >= MAX_RECENT_SEARCHES) break
            }
        }
        return deduped
    }

    private fun persistAsync(email: String, key: String) {
        ioScope.launch {
            persistLatestAtomically(email, key)
        }
    }

    private fun persistLatestAtomically(email: String, key: String) {
        synchronized(diskLock(key)) {
            val snapshot = memoryCache[key] ?: emptyList()
            val targetFile = resolveTargetFile(email)
            val tempFile = resolveTempFile(email)

            try {
                val parentDir = targetFile.parentFile
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs()
                }

                val payload = gson.toJson(
                    RecentSearchesEnvelope(
                        version = SCHEMA_VERSION,
                        queries = snapshot
                    )
                )

                // Write UTF-8 payload to sibling temp file, flush, and sync descriptor before closing
                FileOutputStream(tempFile).use { fos ->
                    OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                        writer.write(payload)
                        writer.flush()
                        try {
                            fos.fd.sync()
                        } catch (_: IOException) {
                            // Best-effort fsync; proceed to atomic move
                        }
                    }
                }

                val tempPath = tempFile.toPath()
                val targetPath = targetFile.toPath()

                try {
                    Files.move(
                        tempPath,
                        targetPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: IOException) {
                    // Fallback when Windows briefly prevents ATOMIC_MOVE across handles/indexers
                    Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                System.err.println("[DesktopSearchHistoryManager] Failed to persist $FILE_NAME atomically: ${e.message}")
                try {
                    if (tempFile.exists()) tempFile.delete()
                } catch (_: Exception) {}
            }
        }
    }
}
