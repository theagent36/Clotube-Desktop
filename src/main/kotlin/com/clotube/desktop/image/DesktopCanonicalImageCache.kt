package com.clotube.desktop.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.clotube.desktop.data.DesktopProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

enum class ArtworkProvenance(val rank: Int) {
    NONE(0),
    DRIVE_FRAME_CAPTURE(1),
    OFFICIAL_CATALOG(2)
}

data class CanonicalArtworkEntry(
    val canonicalKey: String,
    val sourceUrl: String,
    val provenance: ArtworkProvenance,
    val bitmap: ImageBitmap?
)

/**
 * Canonical content-ID image cache with upgrade-only artwork provenance lock.
 * - Keys memory & disk strictly by `cw_hero_<mediaId>`, `movie_poster_<movieId>`, `series_poster_<seriesId>`.
 * - Rotating Google Drive signed URLs NEVER invalidate cached posters across cold boots or tab switches.
 * - Official TMDB/TVMaze/Cinemeta/IMDb artwork can upgrade a Drive frame capture, but Drive frame captures
 *   can NEVER overwrite official catalog posters or backdrops.
 */
object DesktopCanonicalImageCache {
    private const val MAX_MEMORY_ENTRIES = 320

    private val entries = ConcurrentHashMap<String, CanonicalArtworkEntry>()
    private val inFlightMutexes = ConcurrentHashMap<String, Mutex>()

    fun heroKey(mediaId: String): String = "cw_hero_${mediaId.trim()}"
    fun moviePosterKey(movieId: String): String = "movie_poster_${movieId.trim()}"
    fun seriesPosterKey(seriesId: String): String = "series_poster_${seriesId.trim()}"

    fun classifyProvenance(url: String?): ArtworkProvenance {
        if (url.isNullOrBlank()) return ArtworkProvenance.NONE
        val lower = url.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return ArtworkProvenance.NONE
        if (lower.startsWith("http://127.0.0.1") || lower.startsWith("http://localhost")) return ArtworkProvenance.NONE

        val isDriveFrame = lower.contains("googleusercontent.com") ||
            lower.contains("drive.google.com") ||
            lower.contains("docs.google.com") ||
            lower.contains("lh3.google.com")
        return if (isDriveFrame) ArtworkProvenance.DRIVE_FRAME_CAPTURE else ArtworkProvenance.OFFICIAL_CATALOG
    }

    fun getCachedBitmap(canonicalKey: String): ImageBitmap? {
        return entries[canonicalKey]?.bitmap
    }

    /**
     * Checks [primaryKey] first, falling back to [secondaryKey] (both memory and disk cache)
     * and linking the decoded bitmap onto [primaryKey] when [primaryKey] has no higher-provenance conflict.
     */
    fun getBestAvailableBitmap(primaryKey: String, secondaryKey: String? = null): ImageBitmap? {
        if (primaryKey.isBlank()) return null
        getCachedOrDiskBitmapSync(primaryKey)?.let { return it }

        val secKey = secondaryKey?.takeIf { it.isNotBlank() } ?: return null
        val secBitmap = getCachedOrDiskBitmapSync(secKey) ?: return null
        val secEntry = entries[secKey]
        val existingPrimary = entries[primaryKey]

        if (existingPrimary == null || (secEntry != null && secEntry.provenance.rank >= existingPrimary.provenance.rank)) {
            putBounded(
                CanonicalArtworkEntry(
                    canonicalKey = primaryKey,
                    sourceUrl = existingPrimary?.sourceUrl?.takeIf { it.isNotBlank() }
                        ?: secEntry?.sourceUrl.orEmpty(),
                    provenance = maxOf(
                        existingPrimary?.provenance ?: ArtworkProvenance.NONE,
                        secEntry?.provenance ?: ArtworkProvenance.NONE,
                        compareBy { it.rank }
                    ),
                    bitmap = secBitmap
                )
            )
        }
        return secBitmap
    }

    /**
     * Frame-1 synchronous lookup: hits RAM first, then checks canonical disk cache on SSD
     * if disk provenance satisfies the incoming candidate URL's provenance rank.
     */
    fun getCachedOrDiskBitmapSync(canonicalKey: String, candidateUrl: String = ""): ImageBitmap? {
        if (canonicalKey.isBlank()) return null
        entries[canonicalKey]?.bitmap?.let { return it }

        val incomingProv = classifyProvenance(candidateUrl)
        val diskFile = getDiskCacheFile(canonicalKey)
        if (!diskFile.exists() || diskFile.length() <= 0L) return null

        val diskProv = readDiskProvenance(getDiskMetaFile(canonicalKey))
        if (diskProv.rank < incomingProv.rank && incomingProv == ArtworkProvenance.OFFICIAL_CATALOG) {
            return null
        }

        val maxDim = if (canonicalKey.startsWith("cw_hero_")) 1920 else 900
        val decoded = decodeBytesSafely(runCatching { diskFile.readBytes() }.getOrNull(), maxDim) ?: return null
        val existing = entries[canonicalKey]
        val finalUrl = existing?.sourceUrl?.takeIf { it.isNotBlank() } ?: candidateUrl
        val finalProv = when {
            existing != null && existing.provenance.rank >= diskProv.rank -> existing.provenance
            diskProv != ArtworkProvenance.NONE -> diskProv
            else -> incomingProv
        }
        putBounded(
            CanonicalArtworkEntry(
                canonicalKey = canonicalKey,
                sourceUrl = finalUrl,
                provenance = finalProv,
                bitmap = decoded
            )
        )
        return decoded
    }

    fun getCanonicalUrl(canonicalKey: String): String? {
        return entries[canonicalKey]?.takeIf { it.provenance == ArtworkProvenance.OFFICIAL_CATALOG }?.sourceUrl
    }

    /**
     * Cross-pollinates official artwork between poster keys and `cw_hero_<mediaId>`
     * while strictly enforcing the upgrade-only provenance lock.
     */
    fun registerOfficialArtworkUrl(
        canonicalKey: String,
        officialUrl: String,
        linkedMediaId: String? = null
    ) {
        val prov = classifyProvenance(officialUrl)
        if (prov != ArtworkProvenance.OFFICIAL_CATALOG) return

        val existing = entries[canonicalKey]
        if (existing == null || existing.provenance.rank < prov.rank || existing.sourceUrl != officialUrl) {
            // Clear bitmap when upgrading from Drive frame capture or changing source URL so loadOrGet fetches official artwork
            val preserveBitmap = if (existing != null && existing.provenance.rank >= prov.rank && existing.sourceUrl == officialUrl) {
                existing.bitmap
            } else {
                null
            }
            entries[canonicalKey] = CanonicalArtworkEntry(
                canonicalKey = canonicalKey,
                sourceUrl = officialUrl,
                provenance = prov,
                bitmap = preserveBitmap
            )
        }

        if (!linkedMediaId.isNullOrBlank()) {
            val heroK = heroKey(linkedMediaId)
            val existingHero = entries[heroK]
            if (existingHero == null || existingHero.provenance.rank < prov.rank) {
                entries[heroK] = CanonicalArtworkEntry(
                    canonicalKey = heroK,
                    sourceUrl = officialUrl,
                    provenance = prov,
                    bitmap = null
                )
            }
        }
    }

    suspend fun loadOrGet(
        canonicalKey: String,
        candidateUrl: String,
        accessToken: String? = null,
        fallbackUrl: String? = null
    ): ImageBitmap? {
        if (canonicalKey.isBlank()) return null

        val incomingProv = classifyProvenance(candidateUrl)
        val current = entries[canonicalKey]

        // 1. Upgrade-only provenance lock: reject Drive frame URLs if official artwork is already present
        if (current != null && incomingProv.rank < current.provenance.rank) {
            if (current.bitmap != null) return current.bitmap
            if (current.sourceUrl.isNotBlank()) {
                return loadOrGet(canonicalKey, current.sourceUrl, accessToken, fallbackUrl)
            }
        }

        // 2. Fast RAM hit: return pinned bitmap immediately regardless of signed URL rotation
        if (current?.bitmap != null && incomingProv.rank <= current.provenance.rank &&
            (candidateUrl.isBlank() || current.sourceUrl == candidateUrl || incomingProv == ArtworkProvenance.DRIVE_FRAME_CAPTURE)
        ) {
            return current.bitmap
        }

        val mutex = inFlightMutexes.computeIfAbsent(canonicalKey) { Mutex() }
        return mutex.withLock {
            val rechecked = entries[canonicalKey]
            val effectiveUrl = when {
                rechecked != null && rechecked.provenance.rank > incomingProv.rank && rechecked.sourceUrl.isNotBlank() -> rechecked.sourceUrl
                candidateUrl.isNotBlank() -> candidateUrl
                rechecked?.sourceUrl?.isNotBlank() == true -> rechecked.sourceUrl
                else -> ""
            }
            val effectiveProv = classifyProvenance(effectiveUrl)

            if (rechecked?.bitmap != null && effectiveProv.rank <= rechecked.provenance.rank &&
                (effectiveUrl.isBlank() || rechecked.sourceUrl == effectiveUrl || effectiveProv == ArtworkProvenance.DRIVE_FRAME_CAPTURE)
            ) {
                return@withLock rechecked.bitmap
            }

            withContext(Dispatchers.IO) {
                val maxDim = if (canonicalKey.startsWith("cw_hero_")) 1920 else 900
                val diskFile = getDiskCacheFile(canonicalKey)
                val diskMetaFile = getDiskMetaFile(canonicalKey)
                val diskProv = readDiskProvenance(diskMetaFile)

                // 3. Disk hit: reuse cached bytes if disk provenance >= effective provenance
                if (diskFile.exists() && diskFile.length() > 0L && diskProv.rank >= effectiveProv.rank) {
                    val decoded = decodeBytesSafely(runCatching { diskFile.readBytes() }.getOrNull(), maxDim)
                    if (decoded != null) {
                        val finalUrl = if (rechecked?.sourceUrl?.isNotBlank() == true) rechecked.sourceUrl else effectiveUrl
                        putBounded(
                            CanonicalArtworkEntry(
                                canonicalKey = canonicalKey,
                                sourceUrl = finalUrl,
                                provenance = if (diskProv != ArtworkProvenance.NONE) diskProv else effectiveProv,
                                bitmap = decoded
                            )
                        )
                        return@withContext decoded
                    }
                }

                // 4. Network fetch (only when missing from RAM/disk or upgrading provenance)
                if (effectiveUrl.isNotBlank() && effectiveProv != ArtworkProvenance.NONE) {
                    val downloadedBytes = fetchImageBytes(effectiveUrl, accessToken)
                    if (downloadedBytes != null && downloadedBytes.isNotEmpty()) {
                        val decoded = decodeBytesSafely(downloadedBytes, maxDim)
                        if (decoded != null) {
                            // Persist to disk only if it does not downgrade existing disk provenance
                            if (effectiveProv.rank >= diskProv.rank) {
                                writeDiskAtomically(diskFile, diskMetaFile, downloadedBytes, effectiveProv)
                            }
                            putBounded(
                                CanonicalArtworkEntry(
                                    canonicalKey = canonicalKey,
                                    sourceUrl = effectiveUrl,
                                    provenance = effectiveProv,
                                    bitmap = decoded
                                )
                            )
                            return@withContext decoded
                        }
                    }
                }

                // 5. Optional fallback ONLY when no official artwork exists yet on disk or in RAM
                val safeFallback = fallbackUrl?.trim().orEmpty()
                if (safeFallback.isNotBlank() &&
                    safeFallback != effectiveUrl &&
                    diskProv == ArtworkProvenance.NONE &&
                    rechecked?.bitmap == null &&
                    rechecked?.provenance != ArtworkProvenance.OFFICIAL_CATALOG
                ) {
                    val fallbackProv = classifyProvenance(safeFallback)
                    if (fallbackProv != ArtworkProvenance.NONE) {
                        val fbBytes = fetchImageBytes(safeFallback, accessToken)
                        if (fbBytes != null && fbBytes.isNotEmpty()) {
                            val decoded = decodeBytesSafely(fbBytes, maxDim)
                            if (decoded != null) {
                                writeDiskAtomically(diskFile, diskMetaFile, fbBytes, fallbackProv)
                                putBounded(
                                    CanonicalArtworkEntry(
                                        canonicalKey = canonicalKey,
                                        sourceUrl = safeFallback,
                                        provenance = fallbackProv,
                                        bitmap = decoded
                                    )
                                )
                                return@withContext decoded
                            }
                        }
                    }
                }

                rechecked?.bitmap
            }
        }
    }

    fun clearMemory() {
        entries.clear()
    }

    fun calculateDiskCacheBytes(): Long {
        return try {
            val dir = getCacheDir()
            if (!dir.exists()) 0L
            else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Throwable) {
            0L
        }
    }

    fun clearDiskCache() {
        entries.clear()
        try {
            val dir = getCacheDir()
            if (dir.exists()) {
                dir.deleteRecursively()
                dir.mkdirs()
            }
        } catch (_: Throwable) {
        }
    }

    private fun putBounded(entry: CanonicalArtworkEntry) {
        if (entries.size >= MAX_MEMORY_ENTRIES && !entries.containsKey(entry.canonicalKey)) {
            val evictCandidate = entries.keys.firstOrNull { !it.startsWith("cw_hero_") }
            if (evictCandidate != null) {
                entries.remove(evictCandidate)
            }
        }
        entries[entry.canonicalKey] = entry
    }

    private fun getCacheDir(): File {
        return File(File(DesktopProfileManager.appDataRoot, "cache"), "canonical_art").apply { mkdirs() }
    }

    private fun safeFileKey(canonicalKey: String): String {
        return canonicalKey.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun getDiskCacheFile(canonicalKey: String): File {
        return File(getCacheDir(), "${safeFileKey(canonicalKey)}.bin")
    }

    private fun getDiskMetaFile(canonicalKey: String): File {
        return File(getCacheDir(), "${safeFileKey(canonicalKey)}.prov")
    }

    private fun readDiskProvenance(metaFile: File): ArtworkProvenance {
        if (!metaFile.exists()) return ArtworkProvenance.NONE
        return runCatching {
            ArtworkProvenance.valueOf(metaFile.readText(Charsets.UTF_8).trim())
        }.getOrDefault(ArtworkProvenance.NONE)
    }

    private fun writeDiskAtomically(
        dataFile: File,
        metaFile: File,
        bytes: ByteArray,
        provenance: ArtworkProvenance
    ) {
        try {
            dataFile.parentFile?.mkdirs()
            val tmpData = File(dataFile.parentFile, "${dataFile.name}.tmp")
            tmpData.writeBytes(bytes)
            try {
                Files.move(
                    tmpData.toPath(),
                    dataFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Throwable) {
                Files.move(tmpData.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            val tmpMeta = File(metaFile.parentFile, "${metaFile.name}.tmp")
            tmpMeta.writeText(provenance.name, Charsets.UTF_8)
            try {
                Files.move(
                    tmpMeta.toPath(),
                    metaFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Throwable) {
                Files.move(tmpMeta.toPath(), metaFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Throwable) {
        }
    }

    private fun decodeBytesSafely(bytes: ByteArray?, maxDimension: Int): ImageBitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val originalImage = SkiaImage.makeFromEncoded(bytes)
            val origW = originalImage.width
            val origH = originalImage.height
            val finalImage = if (origW > maxDimension || origH > maxDimension) {
                val scale = min(maxDimension.toFloat() / origW, maxDimension.toFloat() / origH)
                val targetW = (origW * scale).toInt().coerceAtLeast(1)
                val targetH = (origH * scale).toInt().coerceAtLeast(1)
                val surface = Surface.makeRasterN32Premul(targetW, targetH)
                surface.canvas.drawImageRect(
                    originalImage,
                    Rect.makeWH(origW.toFloat(), origH.toFloat()),
                    Rect.makeWH(targetW.toFloat(), targetH.toFloat())
                )
                surface.makeImageSnapshot()
            } else {
                originalImage
            }
            finalImage.toComposeImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchImageBytes(urlString: String, accessToken: String?): ByteArray? {
        var currentUrl = urlString
        var redirects = 0
        while (redirects < 5) {
            var conn: HttpURLConnection? = null
            try {
                val url = URI.create(currentUrl).toURL()
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    instanceFollowRedirects = false
                    doInput = true
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                    setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    val host = url.host?.lowercase().orEmpty()
                    if (!accessToken.isNullOrBlank() &&
                        (host.contains("googleapis.com") || host.contains("googleusercontent.com") || host.contains("drive.google.com"))
                    ) {
                        setRequestProperty("Authorization", "Bearer $accessToken")
                    }
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank()) return null
                    currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        URI.create(currentUrl).resolve(location).toString()
                    }
                    redirects++
                    continue
                }
                if (code in 200..299) {
                    return BufferedInputStream(conn.inputStream, 32768).use { it.readBytes() }
                }
                return null
            } catch (_: Throwable) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }
}

@Composable
fun rememberCanonicalArtwork(
    canonicalKey: String,
    candidateUrl: String,
    accessToken: String? = null,
    fallbackUrl: String? = null,
    fallbackCanonicalKey: String? = null
): ImageBitmap? {
    var bitmap by remember(canonicalKey, fallbackCanonicalKey) {
        mutableStateOf(
            DesktopCanonicalImageCache.getCachedOrDiskBitmapSync(canonicalKey, candidateUrl)
                ?: DesktopCanonicalImageCache.getBestAvailableBitmap(canonicalKey, fallbackCanonicalKey)
        )
    }

    LaunchedEffect(canonicalKey, candidateUrl, fallbackUrl, fallbackCanonicalKey, accessToken) {
        val secondaryUrl = fallbackCanonicalKey?.let { DesktopCanonicalImageCache.getCanonicalUrl(it) }
        val effectiveUrl = candidateUrl.ifBlank { secondaryUrl.orEmpty() }
        val effectiveFallback = fallbackUrl ?: secondaryUrl
        val loaded = DesktopCanonicalImageCache.loadOrGet(
            canonicalKey = canonicalKey,
            candidateUrl = effectiveUrl,
            accessToken = accessToken,
            fallbackUrl = effectiveFallback
        )
        if (loaded != null) {
            bitmap = loaded
        } else if (bitmap == null) {
            DesktopCanonicalImageCache.getBestAvailableBitmap(canonicalKey, fallbackCanonicalKey)?.let {
                bitmap = it
            }
        }
    }
    return bitmap
}
