package com.clotube.desktop.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.clotube.desktop.data.DesktopProfileManager
import kotlinx.coroutines.Dispatchers
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

private val thumbnailMemoryCache = ConcurrentHashMap<String, ImageBitmap>()
private val failedUrlCache = ConcurrentHashMap.newKeySet<String>()
private val imageFetchDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

enum class ThumbnailSize(val maxDimension: Int) {
    CARD(480),
    HERO(1280)
}

/**
 * Completely purges decoded image bitmaps from memory on sign-out/account switch.
 */
fun clearThumbnailMemoryCache() {
    thumbnailMemoryCache.clear()
    failedUrlCache.clear()
    println("[DesktopThumbnailLoader] In-memory thumbnail cache purged.")
}

@Composable
fun rememberThumbnailBitmap(
    primaryUrl: String?,
    fallbackUrl: String? = null,
    accessToken: String = "",
    userEmail: String = "",
    size: ThumbnailSize = ThumbnailSize.CARD
): ImageBitmap? {
    val effectiveKey = primaryUrl?.ifBlank { null } ?: fallbackUrl?.ifBlank { null } ?: return null
    val memoryKey = "${size.name}:${effectiveKey}"

    thumbnailMemoryCache[memoryKey]?.let { return it }

    var bitmapState by remember(memoryKey, userEmail) { mutableStateOf(thumbnailMemoryCache[memoryKey]) }

    LaunchedEffect(memoryKey, accessToken, userEmail) {
        if (bitmapState != null || failedUrlCache.contains(memoryKey)) return@LaunchedEffect

        val loaded = withContext(imageFetchDispatcher) {
            val diskImage = loadFromDiskCache(effectiveKey, userEmail)
            if (diskImage != null) {
                return@withContext diskImage
            }

            var result: ImageBitmap? = null

            if (!primaryUrl.isNullOrBlank() && !failedUrlCache.contains(primaryUrl)) {
                result = fetchAndProcessImage(primaryUrl, accessToken, userEmail, size)
                if (result == null) {
                    failedUrlCache.add(primaryUrl)
                }
            }

            if (result == null && !fallbackUrl.isNullOrBlank() && fallbackUrl != primaryUrl && !failedUrlCache.contains(fallbackUrl)) {
                result = fetchAndProcessImage(fallbackUrl, accessToken, userEmail, size)
                if (result == null) {
                    failedUrlCache.add(fallbackUrl)
                }
            }

            if (result == null) {
                failedUrlCache.add(memoryKey)
            }

            result
        }

        if (loaded != null) {
            thumbnailMemoryCache[memoryKey] = loaded
            bitmapState = loaded
        }
    }

    return bitmapState
}

private fun getDiskCacheFile(key: String, userEmail: String): File {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(key.toByteArray(Charsets.UTF_8))
    val hash = digest.joinToString("") { "%02x".format(it) }
    val dir = if (userEmail.isNotBlank()) {
        DesktopProfileManager.getThumbnailsDir(userEmail)
    } else {
        File("cache", "thumbnails").apply { if (!exists()) mkdirs() }
    }
    return File(dir, "$hash.png")
}

private fun loadFromDiskCache(key: String, userEmail: String): ImageBitmap? {
    return try {
        val file = getDiskCacheFile(key, userEmail)
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

private fun saveToDiskCache(key: String, userEmail: String, bytes: ByteArray) {
    try {
        val file = getDiskCacheFile(key, userEmail)
        file.writeBytes(bytes)
    } catch (_: Exception) {}
}

private fun fetchAndProcessImage(
    urlString: String,
    accessToken: String,
    userEmail: String,
    size: ThumbnailSize
): ImageBitmap? {
    val rawBytes = downloadBytesWithRedirects(urlString, accessToken) ?: return null
    return try {
        val originalImage = Image.makeFromEncoded(rawBytes)
        val origW = originalImage.width
        val origH = originalImage.height

        val maxDimension = size.maxDimension
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

        val pngData = downsampledImage.encodeToData(EncodedImageFormat.PNG, 90)
        if (pngData != null) {
            saveToDiskCache(urlString, userEmail, pngData.bytes)
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
            val url = URI.create(currentUrl).toURL()
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
