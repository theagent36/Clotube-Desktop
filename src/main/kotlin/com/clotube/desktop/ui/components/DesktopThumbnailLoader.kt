package com.clotube.desktop.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Context-aware image sizing:
 * - CARD: 900px downsampling with quality 88 delivers razor-sharp text on 1080p/1440p/4K displays.
 * - HERO: 1920px downsampling with quality 90 delivers native full-HD widescreen billboard art.
 */
enum class ThumbnailSize(val maxDimension: Int, val jpegQuality: Int) {
    CARD(maxDimension = 900, jpegQuality = 88),
    HERO(maxDimension = 1920, jpegQuality = 90)
}

// Bounded LRU in-memory cache (Capacity: 90 active textures)
private const val MEMORY_CACHE_CAPACITY = 90
private val thumbnailMemoryCache: MutableMap<String, ImageBitmap> =
    Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(MEMORY_CACHE_CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
                return size > MEMORY_CACHE_CAPACITY
            }
        }
    )

private val failedUrlCache = ConcurrentHashMap.newKeySet<String>()
private val urlHashCache = ConcurrentHashMap<String, String>()
private val imageFetchDispatcher = Dispatchers.IO.limitedParallelism(4)
private val isEvictionScheduled = AtomicBoolean(false)

private val diskCacheDir = File("cache", "thumbnails").apply {
    if (!exists()) mkdirs()
}

@Composable
fun rememberThumbnailBitmap(
    primaryUrl: String?,
    fallbackUrl: String? = null,
    accessToken: String = "",
    size: ThumbnailSize = ThumbnailSize.CARD
): ImageBitmap? {
    val effectiveUrl = primaryUrl?.ifBlank { null } ?: fallbackUrl?.ifBlank { null } ?: return null
    val effectiveKey = "${effectiveUrl}_${size.name}"

    // Run one-time asynchronous startup disk maintenance
    if (isEvictionScheduled.compareAndSet(false, true)) {
        CoroutineScope(Dispatchers.IO).launch {
            pruneDiskCache()
        }
    }

    thumbnailMemoryCache[effectiveKey]?.let { return it }

    var bitmapState by remember(effectiveKey) { mutableStateOf(thumbnailMemoryCache[effectiveKey]) }

    LaunchedEffect(effectiveKey, accessToken) {
        if (bitmapState != null || failedUrlCache.contains(effectiveKey)) return@LaunchedEffect

        val loaded = withContext(imageFetchDispatcher) {
            val diskImage = loadFromDiskCache(effectiveUrl, size)
            if (diskImage != null) {
                return@withContext diskImage
            }

            var result: ImageBitmap? = null

            if (!primaryUrl.isNullOrBlank() && !failedUrlCache.contains("${primaryUrl}_${size.name}")) {
                result = fetchAndProcessImage(primaryUrl, accessToken, size)
                if (result == null) {
                    failedUrlCache.add("${primaryUrl}_${size.name}")
                }
            }

            if (result == null && !fallbackUrl.isNullOrBlank() && fallbackUrl != primaryUrl && !failedUrlCache.contains("${fallbackUrl}_${size.name}")) {
                result = fetchAndProcessImage(fallbackUrl, accessToken, size)
                if (result == null) {
                    failedUrlCache.add("${fallbackUrl}_${size.name}")
                }
            }

            if (result == null) {
                failedUrlCache.add(effectiveKey)
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

private fun getDiskCacheFile(url: String, size: ThumbnailSize): File {
    val hash = urlHashCache.computeIfAbsent(url) { raw ->
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it) }
    }
    return File(diskCacheDir, "$hash-${size.name.lowercase()}.jpg")
}

private fun loadFromDiskCache(url: String, size: ThumbnailSize): ImageBitmap? {
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

private fun saveToDiskCache(url: String, size: ThumbnailSize, bytes: ByteArray) {
    try {
        val file = getDiskCacheFile(url, size)
        file.writeBytes(bytes)
    } catch (_: Exception) {}
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

private fun fetchAndProcessImage(urlString: String, accessToken: String, size: ThumbnailSize): ImageBitmap? {
    val rawBytes = downloadBytesWithRedirects(urlString, accessToken) ?: return null
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

        val jpegData = downsampledImage.encodeToData(EncodedImageFormat.JPEG, size.jpegQuality)
        if (jpegData != null) {
            saveToDiskCache(urlString, size, jpegData.bytes)
        }

        downsampledImage.toComposeImageBitmap()
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
            val url = URL(currentUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = false

                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")

                val host = url.host.lowercase()
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
                    URL(URL(currentUrl), location).toString()
                }
                redirects++
                continue
            }

            if (statusCode == HttpURLConnection.HTTP_OK) {
                return connection.inputStream.use { it.readBytes() }
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
