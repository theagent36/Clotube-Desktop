package com.clotube.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clotube.desktop.auth.DesktopAuthManager
import com.clotube.desktop.data.DesktopDriveScanner
import com.clotube.desktop.data.DesktopLibraryCache
import com.clotube.desktop.data.DesktopOnlineMetadataResolver
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.screens.DesktopHomeScreen
import com.clotube.desktop.ui.screens.DesktopLoginScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private val isScanningActive = AtomicBoolean(false)

@Composable
fun App(
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {}
) {
    var authState by remember { mutableStateOf(DesktopAuthManager.getSavedAuthState()) }
    var isAuthenticating by remember { mutableStateOf(false) }

    var movies by remember { mutableStateOf<List<DesktopMediaItem>>(emptyList()) }
    var series by remember { mutableStateOf<List<DesktopMediaItem>>(emptyList()) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var rescanTrigger by remember { mutableIntStateOf(0) }

    var continueWatching by remember(movies, series) {
        mutableStateOf<List<DesktopMediaItem>>(
            if (movies.isNotEmpty() || series.isNotEmpty()) {
                val pool = (movies + series).take(4)
                pool.mapIndexed { index, item ->
                    val simDuration = if (item.durationMs > 0L) item.durationMs else (7200000L - index * 600000L)
                    val fraction = when (index) {
                        0 -> 0.62f
                        1 -> 0.28f
                        2 -> 0.34f
                        else -> 0.45f
                    }
                    item.copy(
                        durationMs = simDuration,
                        progressMs = (simDuration * fraction).toLong()
                    )
                }
            } else emptyList()
        )
    }

    val coroutineScope = rememberCoroutineScope()

    // 1. Account Change Observer with Automatic Dirty Title Self-Healing
    LaunchedEffect(authState.userEmail) {
        val email = authState.userEmail
        if (email.isNotBlank()) {
            val userCache = DesktopLibraryCache.loadSync(email)
            if (userCache != null && (userCache.movies.isNotEmpty() || userCache.series.isNotEmpty())) {
                // Instantly sanitize any dirty titles and heal broken URLs in the cache on startup
                val sanitizedMovies = userCache.movies.map { item ->
                    val isBrokenUrl = item.thumbnailUrl?.contains("m.media-amazon.com") == true
                    val guaranteedDriveThumb = item.fallbackThumbnailUrl?.ifBlank { null }
                        ?: "https://drive.google.com/thumbnail?id=${item.id}&sz=w800"

                    val meta = DesktopOnlineMetadataResolver.resolveMovieSync(item.title)
                    item.copy(
                        title = meta.officialTitle,
                        thumbnailUrl = if (isBrokenUrl) guaranteedDriveThumb else (meta.posterUrl.ifBlank { item.thumbnailUrl ?: guaranteedDriveThumb }),
                        fallbackThumbnailUrl = guaranteedDriveThumb,
                        genre = if (item.genre == "Action • Drama") meta.genre else item.genre,
                        language = if (item.language.isBlank()) meta.language else item.language
                    )
                }

                val sanitizedSeries = userCache.series.map { item ->
                    val isBrokenUrl = item.thumbnailUrl?.contains("m.media-amazon.com") == true
                    val guaranteedDriveThumb = item.fallbackThumbnailUrl?.ifBlank { null }
                        ?: "https://drive.google.com/thumbnail?id=${item.id}&sz=w800"
                    item.copy(
                        thumbnailUrl = if (isBrokenUrl) guaranteedDriveThumb else (item.thumbnailUrl ?: guaranteedDriveThumb),
                        fallbackThumbnailUrl = guaranteedDriveThumb
                    )
                }

                movies = sanitizedMovies
                series = sanitizedSeries
                isInitialLoading = false
                println("[App] Loaded and sanitized cached library for account: $email (${movies.size} movies, ${series.size} series)")
            } else {
                movies = emptyList()
                series = emptyList()
                isInitialLoading = true
                println("[App] No cache found for $email. Preparing progressive fast-paint scan...")
            }
        } else {
            movies = emptyList()
            series = emptyList()
            isInitialLoading = false
        }
    }

    // 2. Progressive Flow Ingestion with Self-Healing OAuth Refresh
    LaunchedEffect(authState.accessToken, authState.userEmail, rescanTrigger) {
        val email = authState.userEmail

        if (email.isNotBlank() && isScanningActive.compareAndSet(false, true)) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val credential = DesktopAuthManager.getCredential()
                    val scanner = DesktopDriveScanner(credential = credential)

                    var freshMovies: List<DesktopMediaItem> = emptyList()
                    var freshSeries: List<DesktopMediaItem> = emptyList()

                    // Collect movies stream progressively
                    val moviesJob = launch {
                        scanner.streamMovies().collect { incomingMovies ->
                            if (authState.userEmail.equals(email, ignoreCase = true)) {
                                freshMovies = incomingMovies
                                movies = incomingMovies
                                // Unlock the screen as soon as the first 5 movies arrive
                                if (incomingMovies.isNotEmpty()) {
                                    isInitialLoading = false
                                }
                            }
                        }
                    }

                    // Collect series stream progressively
                    val seriesJob = launch {
                        scanner.streamSeriesFolders().collect { incomingSeries ->
                            if (authState.userEmail.equals(email, ignoreCase = true)) {
                                freshSeries = incomingSeries
                                series = incomingSeries
                                if (incomingSeries.isNotEmpty()) {
                                    isInitialLoading = false
                                }
                            }
                        }
                    }

                    // Wait for both background streams to complete indexing the full library
                    joinAll(moviesJob, seriesJob)

                    if (authState.userEmail.equals(email, ignoreCase = true)) {
                        val hasDirtyTitles = movies.any { DesktopOnlineMetadataResolver.isTitleDirty(it.title) }
                        val hasMissingLanguages = movies.any { it.language.isBlank() }
                        val moviesChanged = hasMediaLibraryChanged(movies, freshMovies)
                        val seriesChanged = hasMediaLibraryChanged(series, freshSeries)

                        if (moviesChanged || seriesChanged || hasDirtyTitles || hasMissingLanguages || isInitialLoading) {
                            println("[App] Folder language change or updates detected. Refreshing library metadata...")

                            movies = if (moviesChanged || hasDirtyTitles || hasMissingLanguages) {
                                freshMovies
                            } else {
                                mergeLibraryItems(movies, freshMovies)
                            }

                            if (seriesChanged) {
                                series = mergeLibraryItems(series, freshSeries)
                            }

                            DesktopLibraryCache.save(email, movies, series)
                        } else {
                            println("[App] Library clean and matches disk cache for $email.")
                            DesktopLibraryCache.save(email, movies, series)
                        }
                        println("[App] Full background sync finished. Persisted ${movies.size} movies and ${series.size} series for $email.")
                    }
                } catch (e: Exception) {
                    System.err.println("[App] Progressive sync error for $email: ${e.message}")
                } finally {
                    isInitialLoading = false
                    isScanningActive.set(false)
                }
            }
        }
    }

    if (!authState.isSignedIn) {
        DesktopLoginScreen(
            isLoading = isAuthenticating,
            onSignInClick = {
                isAuthenticating = true
                coroutineScope.launch {
                    val newState = DesktopAuthManager.startDesktopOAuthFlow()
                    authState = newState
                    isAuthenticating = false
                }
            }
        )
    } else {
        if (isInitialLoading && movies.isEmpty() && series.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF07090E)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF42A5F5),
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(36.dp)
                )
            }
        } else {
            DesktopHomeScreen(
                userEmail = authState.userEmail,
                userName = authState.userName,
                accessToken = authState.accessToken,
                userAvatarUrl = authState.userPhotoUrl ?: "",
                movies = movies,
                series = series,
                continueWatching = continueWatching,
                onMediaClick = { clickedItem ->
                    val simDuration = if (clickedItem.durationMs > 0L) clickedItem.durationMs else 7200000L
                    val existing = continueWatching.find { it.id == clickedItem.id }
                    val updated = existing?.copy(progressMs = (simDuration * 0.5f).toLong())
                        ?: clickedItem.copy(durationMs = simDuration, progressMs = (simDuration * 0.2f).toLong())
                    continueWatching = listOf(updated) + continueWatching.filter { it.id != clickedItem.id }
                },
                onClearHistory = {
                    continueWatching = emptyList()
                },
                onRemoveHistoryItem = { item ->
                    continueWatching = continueWatching.filter { it.id != item.id }
                },
                isFullscreen = isFullscreen,
                onToggleFullscreen = onToggleFullscreen,
                isAppFullscreen = isFullscreen,
                onFullscreenToggle = { onToggleFullscreen() },
                onRescan = {
                    println("[App] Manual cloud rescan requested.")
                    isScanningActive.set(false)
                    rescanTrigger++
                },
                onSignOut = {
                    val loggingOutEmail = authState.userEmail
                    println("[App] Signing out account: $loggingOutEmail")
                    DesktopAuthManager.signOut()
                    // Instantly flush in-memory media and reset auth state
                    movies = emptyList()
                    series = emptyList()
                    continueWatching = emptyList()
                    authState = DesktopAuthManager.getSavedAuthState()
                }
            )
        }
    }
}

private fun hasMediaLibraryChanged(
    current: List<DesktopMediaItem>,
    fresh: List<DesktopMediaItem>
): Boolean {
    if (current.size != fresh.size) return true
    val currentMap = current.associateBy { it.id }
    for (item in fresh) {
        val existing = currentMap[item.id] ?: return true
        if (existing.title != item.title || 
            existing.durationMs != item.durationMs || 
            existing.genre != item.genre || 
            existing.language != item.language) {
            return true
        }
    }
    return false
}

private fun mergeLibraryItems(
    current: List<DesktopMediaItem>,
    fresh: List<DesktopMediaItem>
): List<DesktopMediaItem> {
    val currentMap = current.associateBy { it.id }
    return fresh.map { freshItem ->
        val existing = currentMap[freshItem.id]
        if (existing != null &&
            !DesktopOnlineMetadataResolver.isTitleDirty(existing.title) &&
            existing.language.isNotBlank()) {
            existing
        } else {
            freshItem
        }
    }
}
