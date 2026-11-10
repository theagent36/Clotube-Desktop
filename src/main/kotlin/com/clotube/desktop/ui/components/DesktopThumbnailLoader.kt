package com.clotube.desktop.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.clotube.desktop.data.DesktopProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.*
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

enum class ThumbnailSize(val maxDimension: Int, val jpegQuality: Int) {
    CARD(maxDimension = 900, jpegQuality = 85),
    HERO(maxDimension = 1920, jpegQuality = 90)
}

// Bounded LRU in-memory cache (Capacity: 200 decoded textures)
private const val MAX_MEMORY_THUMBNAILS = 200
private val thumbnailMemoryCache: MutableMap<String, ImageBitmap> =
    Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
                return size > MAX_MEMORY_THUMBNAILS
            }
        }
    )

private val cacheRootDir: File by lazy {
    File(DesktopProfileManager.appDataRoot, "cache").apply {
        if (!exists()) mkdirs()
    }
}

private val diskCacheDir: File by lazy {
    File(cacheRootDir, "thumbnails").apply {
        if (!exists()) mkdirs()
    }
}

private val failedUrlsFile: File by lazy {
    File(cacheRootDir, "failed_urls.txt")
}
private val failedUrlCache = ConcurrentHashMap.newKeySet<String>().apply {
    try {
        if (failedUrlsFile.exists()) {
            failedUrlsFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) add(trimmed)
            }
        }
    } catch (_: Exception) {}
}

private val urlHashCache = ConcurrentHashMap<String, String>()

// High-throughput 12-worker dispatcher for parallel downloads
private val imageFetchDispatcher = Dispatchers.IO.limitedParallelism(12)
private val isEvictionScheduled = AtomicBoolean(false)

/**
 * Completely purges decoded image bitmaps from memory on sign-out/account switch.
 */
fun clearThumbnailMemoryCache() {
    thumbnailMemoryCache.clear()
    failedUrlCache.clear()
    println("[DesktopThumbnailLoader] In-memory thumbnail cache purged.")
}

/**
 * Checks whether a thumbnail is already stored in the local SSD disk cache.
 */
fun isThumbnailCachedOnDisk(url: String?, size: ThumbnailSize = ThumbnailSize.CARD): Boolean {
    if (url.isNullOrBlank()) return false
    return try {
        val file = getDiskCacheFile(url, size)
        file.exists() && file.length() > 0
    } catch (_: Exception) {
        false
    }
}

/**
 * Silently fetches, downsamples, and writes a thumbnail directly to disk cache.
 * NOTE: Intentionally avoids allocating Compose ImageBitmaps or populating in-memory texture caches
 * so background prefetching incurs zero VRAM or heap footprint.
 */
fun downloadToDiskSilently(
    primaryUrl: String?,
    fallbackUrl: String? = null,
    accessToken: String = "",
    size: ThumbnailSize = ThumbnailSize.CARD
) {
    val targetUrl = when {
        !primaryUrl.isNullOrBlank() && !failedUrlCache.contains("${primaryUrl}_${size.name}") -> primaryUrl
        !fallbackUrl.isNullOrBlank() && !failedUrlCache.contains("${fallbackUrl}_${size.name}") -> fallbackUrl
        else -> return
    }

    if (isThumbnailCachedOnDisk(targetUrl, size)) return

    try {
        val rawBytes = downloadBytesWithRedirects(targetUrl, accessToken)
        if (rawBytes == null) {
            markUrlFailed("${targetUrl}_${size.name}")
            // If primary failed, attempt fallback immediately
            if (targetUrl == primaryUrl && !fallbackUrl.isNullOrBlank() && fallbackUrl != primaryUrl) {
                downloadToDiskSilently(fallbackUrl, null, accessToken, size)
            }
            return
        }

        val originalImage = Image.makeFromEncoded(rawBytes)
        val origW = originalImage.width
        val origH = originalImage.height
        val maxDim = size.maxDimension

        val downsampledImage: Image = if (origW > maxDim || origH > maxDim) {
            val scale = min(maxDim.toFloat() / origW, maxDim.toFloat() / origH)
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

        val jpegData = downsampledImage.encodeToData(EncodedImageFormat.JPEG, size.jpegQuality)
        if (jpegData != null) {
            val bytes = jpegData.bytes
            val targetFile = getDiskCacheFile(targetUrl, size)
            targetFile.writeBytes(bytes)

            // Cross-key cache: link both keys to this file so fallbacks resolve instantly
            if (!primaryUrl.isNullOrBlank() && primaryUrl != targetUrl) {
                getDiskCacheFile(primaryUrl, size).writeBytes(bytes)
            }
            if (!fallbackUrl.isNullOrBlank() && fallbackUrl != targetUrl) {
                getDiskCacheFile(fallbackUrl, size).writeBytes(bytes)
            }
        }
    } catch (_: Exception) {
        markUrlFailed("${targetUrl}_${size.name}")
    }
}

/**
 * High-performance, instant-boot thumbnail loader:
 * 1. Frame 1 Instant Load: Checks memory, then local SSD disk cache synchronously on compose.
 * 2. 12-Worker Parallel Network Pipeline for uncached assets.
 * 3. Cross-key alias saving so fallbacks permanently resolve.
 */
@Composable
fun rememberThumbnailBitmap(
    primaryUrl: String?,
    fallbackUrl: String? = null,
    accessToken: String = "",
    userEmail: String = "",
    size: ThumbnailSize = ThumbnailSize.CARD
): ImageBitmap? {
    val effectiveUrl = primaryUrl?.ifBlank { null } ?: fallbackUrl?.ifBlank { null } ?: return null
    val effectiveKey = "${effectiveUrl}_${size.name}"

    // Schedule one-time startup maintenance
    if (isEvictionScheduled.compareAndSet(false, true)) {
        CoroutineScope(Dispatchers.IO).launch {
            pruneDiskCache()
        }
    }

    // FRAME 1 SYNCHRONOUS RESOLUTION: Check memory, then check disk before rendering
    val initialBitmap = remember(effectiveKey) {
        thumbnailMemoryCache[effectiveKey] ?: run {
            val diskHit = loadFromDiskCache(primaryUrl, size) 
                ?: (if (!fallbackUrl.isNullOrBlank() && fallbackUrl != primaryUrl) loadFromDiskCache(fallbackUrl, size) else null)
            
            if (diskHit != null) {
                thumbnailMemoryCache[effectiveKey] = diskHit
                diskHit
            } else {
                null
            }
        }
    }

    var bitmapState by remember(effectiveKey) { mutableStateOf(initialBitmap) }

    // If image was not on disk, fetch via parallel worker pool
    LaunchedEffect(effectiveKey, accessToken) {
        if (bitmapState != null || failedUrlCache.contains(effectiveKey)) return@LaunchedEffect

        val loaded = withContext(imageFetchDispatcher) {
            // Re-check disk in case another coroutine just finished caching it
            val diskImage = loadFromDiskCache(primaryUrl, size) 
                ?: (if (!fallbackUrl.isNullOrBlank()) loadFromDiskCache(fallbackUrl, size) else null)

            if (diskImage != null) {
                return@withContext diskImage
            }

            var result: ImageBitmap? = null

            // 1. Attempt Primary URL
            if (!primaryUrl.isNullOrBlank() && !failedUrlCache.contains("${primaryUrl}_${size.name}")) {
                result = fetchAndProcessImage(primaryUrl, primaryUrl, fallbackUrl, accessToken, size)
                if (result == null) {
                    markUrlFailed("${primaryUrl}_${size.name}")
                }
            }

            // 2. Attempt Fallback URL
            if (result == null && !fallbackUrl.isNullOrBlank() && fallbackUrl != primaryUrl && !failedUrlCache.contains("${fallbackUrl}_${size.name}")) {
                result = fetchAndProcessImage(fallbackUrl, primaryUrl, fallbackUrl, accessToken, size)
                if (result == null) {
                    markUrlFailed("${fallbackUrl}_${size.name}")
                }
            }

            if (result == null) {
                markUrlFailed(effectiveKey)
            }

            result
        }

        if (loaded != null) {
            thumbnailMemoryCache[effectiveKey] = loaded
            bitmapState = loaded
        }
    }

    return bitmapState
}

private fun markUrlFailed(key: String) {
    if (failedUrlCache.add(key)) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!failedUrlsFile.parentFile.exists()) {
                    failedUrlsFile.parentFile.mkdirs()
                }
                failedUrlsFile.appendText("$key\n")
            } catch (_: Exception) {}
        }
    }
}

private fun getDiskCacheFile(url: String, size: ThumbnailSize): File {
    val hash = urlHashCache.computeIfAbsent(url) { raw ->
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it) }
    }
    return File(diskCacheDir, "$hash-${size.name.lowercase()}.jpg")
}

private fun loadFromDiskCache(url: String?, size: ThumbnailSize): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    return try {
        val file = getDiskCacheFile(url, size)
        if (file.exists() && file.length() > 0) {
            val bytes = file.readBytes()
            val skiaImage = Image.makeFromEncoded(bytes)
            skiaImage.toComposeImageBitmap()
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

private fun saveToDiskCacheAsync(url: String, size: ThumbnailSize, bytes: ByteArray) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val file = getDiskCacheFile(url, size)
            file.writeBytes(bytes)
        } catch (_: Exception) {}
    }
}

private fun fetchAndProcessImage(
    downloadUrl: String,
    primaryUrl: String?,
    fallbackUrl: String?,
    accessToken: String,
    size: ThumbnailSize
): ImageBitmap? {
    val rawBytes = downloadBytesWithRedirects(downloadUrl, accessToken) ?: return null
    return try {
        val originalImage = Image.makeFromEncoded(rawBytes)
        val origW = originalImage.width
        val origH = originalImage.height

        val maxDim = size.maxDimension
        val downsampledImage: Image = if (origW > maxDim || origH > maxDim) {
            val scale = min(maxDim.toFloat() / origW, maxDim.toFloat() / origH)
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

        val composeBitmap = downsampledImage.toComposeImageBitmap()

        // Asynchronous disk write: Return to UI immediately without waiting for disk I/O
        CoroutineScope(Dispatchers.IO).launch {
            val jpegData = downsampledImage.encodeToData(EncodedImageFormat.JPEG, size.jpegQuality)
            if (jpegData != null) {
                val bytes = jpegData.bytes
                saveToDiskCacheAsync(downloadUrl, size, bytes)
                if (!primaryUrl.isNullOrBlank() && primaryUrl != downloadUrl) {
                    saveToDiskCacheAsync(primaryUrl, size, bytes)
                }
                if (!fallbackUrl.isNullOrBlank() && fallbackUrl != downloadUrl) {
                    saveToDiskCacheAsync(fallbackUrl, size, bytes)
                }
            }
        }

        composeBitmap
    } catch (e: Exception) {
        null
    }
}

private fun downloadBytesWithRedirects(initialUrl: String, accessToken: String): ByteArray? {
    var currentUrl = initialUrl
    var redirects = 0

    while (redirects < 5) {
        var connection: HttpURLConnection? = null
        try {
            val url = URI.create(currentUrl).toURL()
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = false

                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")

                val host = url.host?.lowercase() ?: ""
                if ((host.contains("googleusercontent.com") || host.contains("drive.google.com") || host.contains("googleapis.com")) && accessToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $accessToken")
                }
            }

            val statusCode = connection.responseCode

            if (statusCode in 300..399) {
                val location = connection.getHeaderField("Location")
                if (location.isNullOrBlank()) return null
                currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    URI.create(currentUrl).resolve(location).toString()
                }
                redirects++
                continue
            }

            if (statusCode == HttpURLConnection.HTTP_OK) {
                return BufferedInputStream(connection.inputStream, 32768).use { it.readBytes() }
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            connection?.disconnect()
        }
    }
    return null
}

private fun pruneDiskCache() {
    try {
        if (!diskCacheDir.exists()) return
        val files = diskCacheDir.listFiles() ?: return
        val now = System.currentTimeMillis()
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000

        for (file in files) {
            if (now - file.lastModified() > thirtyDaysMs) {
                file.delete()
            }
        }

        val remainingFiles = diskCacheDir.listFiles() ?: return
        val totalBytes = remainingFiles.sumOf { it.length() }
        val maxCapBytes = 500L * 1024 * 1024
        val targetBytes = 400L * 1024 * 1024

        if (totalBytes > maxCapBytes) {
            val sortedByOldest = remainingFiles.sortedBy { it.lastModified() }
            var currentSize = totalBytes
            for (oldFile in sortedByOldest) {
                if (currentSize <= targetBytes) break
                val size = oldFile.length()
                if (oldFile.delete()) {
                    currentSize -= size
                }
            }
        }
    } catch (_: Exception) {}
}
