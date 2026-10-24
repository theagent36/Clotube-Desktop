package com.clotube.desktop.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.skia.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

// In-memory cache for instant 0ms access to decoded thumbnails
private val thumbnailMemoryCache = ConcurrentHashMap<String, ImageBitmap>()

// Negative cache: prevents infinite network retries for dead/invalid URLs
private val failedUrlCache = ConcurrentHashMap.newKeySet<String>()

// Throttled dispatcher: prevents network flooding and UI thread starvation
private val imageFetchDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

private val diskCacheDir = File("cache", "thumbnails").apply {
    if (!exists()) mkdirs()
}

/**
 * High-performance, downsampled, disk-cached image loader for Compose Desktop:
 * 1. Checks in-memory cache (0ms).
 * 2. Checks local disk cache (`cache/thumbnails/{hash}.png`) (<1ms).
 * 3. Downloads from network, downsamples via Skia Surface to max 480px, and caches to disk.
 * 4. Eliminates VRAM bloat and Skia real-time downsampling lag.
 */
@Composable
fun rememberThumbnailBitmap(
    primaryUrl: String?,
    fallbackUrl: String? = null,
    accessToken: String = ""
): ImageBitmap? {
    val effectiveKey = primaryUrl?.ifBlank { null } ?: fallbackUrl?.ifBlank { null } ?: return null

    thumbnailMemoryCache[effectiveKey]?.let { return it }

    var bitmapState by remember(effectiveKey) { mutableStateOf(thumbnailMemoryCache[effectiveKey]) }

    LaunchedEffect(effectiveKey, accessToken) {
        if (bitmapState != null || failedUrlCache.contains(effectiveKey)) return@LaunchedEffect

        val loaded = withContext(imageFetchDispatcher) {
            // Check persistent disk cache first
            val diskImage = loadFromDiskCache(effectiveKey)
            if (diskImage != null) {
                return@withContext diskImage
            }

            var result: ImageBitmap? = null

            // Step 1: Attempt Primary URL
            if (!primaryUrl.isNullOrBlank() && !failedUrlCache.contains(primaryUrl)) {
                result = fetchAndProcessImage(primaryUrl, effectiveKey, accessToken)
                if (result == null) {
                    failedUrlCache.add(primaryUrl)
                }
            }

            // Step 2: Attempt Fallback URL
            if (result == null && !fallbackUrl.isNullOrBlank() && fallbackUrl != primaryUrl && !failedUrlCache.contains(fallbackUrl)) {
                result = fetchAndProcessImage(fallbackUrl, effectiveKey, accessToken)
                if (result == null) {
                    failedUrlCache.add(fallbackUrl)
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

private fun getDiskCacheFile(key: String): File {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(key.toByteArray(Charsets.UTF_8))
    val hash = digest.joinToString("") { "%02x".format(it) }
    return File(diskCacheDir, "$hash.png")
}

private fun loadFromDiskCache(key: String): ImageBitmap? {
    return try {
        val file = getDiskCacheFile(key)
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

private fun saveToDiskCache(key: String, bytes: ByteArray) {
    try {
        val file = getDiskCacheFile(key)
        file.writeBytes(bytes)
    } catch (_: Exception) {}
}

private fun fetchAndProcessImage(urlString: String, effectiveKey: String, accessToken: String): ImageBitmap? {
    val rawBytes = downloadBytesWithRedirects(urlString, accessToken) ?: return null
    return try {
        val originalImage = Image.makeFromEncoded(rawBytes)
        val origW = originalImage.width
        val origH = originalImage.height

        // Downsample master-quality posters (3000x4500) to card resolution (max 480px width)
        val maxDimension = 480
        val downsampledImage: Image = if (origW > maxDimension || origH > maxDimension) {
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

        // Cache downsampled PNG bytes to disk
        val pngData = downsampledImage.encodeToData(EncodedImageFormat.PNG, 85)
        if (pngData != null) {
            val bytes = pngData.bytes
            saveToDiskCache(urlString, bytes)
            if (effectiveKey != urlString) {
                saveToDiskCache(effectiveKey, bytes)
            }
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
            val url = URI(currentUrl).toURL()
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

                val host = url.host?.lowercase() ?: ""
                if ((host.contains("googleusercontent.com") || host.contains("drive.google.com") || host.contains("googleapis.com") || host.contains("google.com")) && accessToken.isNotBlank()) {
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
                    URI(currentUrl).resolve(location).toString()
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
