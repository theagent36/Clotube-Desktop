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
import com.clotube.desktop.data.DesktopWatchHistorySync
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.components.clearThumbnailMemoryCache
import com.clotube.desktop.ui.screens.DesktopHomeScreen
import com.clotube.desktop.ui.screens.DesktopLoginScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private val isScanningActive = AtomicBoolean(false)

@Composable
fun App(
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {}
) {
    var authState by remember { mutableStateOf(DesktopAuthManager.getSavedAuthState()) }
    var isAuthenticating by remember { mutableStateOf(false) }
    var rescanTrigger by remember { mutableIntStateOf(0) }

    val userEmail = authState.userEmail
    val accessToken = authState.accessToken

    // 1. FRAME-1 SYNCHRONOUS PROFILE CACHE LOAD (Keyed to active account email)
    val cachedProfile = remember(userEmail) {
        if (!userEmail.isNullOrBlank()) {
            DesktopLibraryCache.loadSync(userEmail)
        } else {
            null
        }
    }

    var movies by remember(userEmail) {
        mutableStateOf<List<DesktopMediaItem>>(
            cachedProfile?.movies?.map { rawItem ->
                val item = DesktopLibraryCache.sanitizeMediaItem(rawItem)
                val guaranteedDriveThumb = item.fallbackThumbnailUrl?.takeIf { !it.isNullOrBlank() }
                    ?: if (!item.id.isNullOrBlank()) "https://drive.google.com/thumbnail?id=${item.id}&sz=w800" else ""
                val existingPoster = item.thumbnailUrl?.takeIf { !it.isNullOrBlank() && !it.contains("drive.google.com/thumbnail") }
                val resolvedPoster = existingPoster
                    ?: item.thumbnailUrl?.takeIf { !it.isNullOrBlank() }
                    ?: guaranteedDriveThumb
                item.copy(
                    thumbnailUrl = resolvedPoster,
                    fallbackThumbnailUrl = guaranteedDriveThumb
                )
            } ?: emptyList()
        )
    }

    var series by remember(userEmail) {
        mutableStateOf<List<DesktopMediaItem>>(
            cachedProfile?.series?.map { DesktopLibraryCache.sanitizeMediaItem(it) } ?: emptyList()
        )
    }

    var isInitialLoading by remember(userEmail) {
        mutableStateOf<Boolean>(
            !userEmail.isNullOrBlank() && (cachedProfile == null || (cachedProfile.movies.isEmpty() && cachedProfile.series.isEmpty()))
        )
    }

    var continueWatching by remember(userEmail, movies, series) {
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

    // Synchronize Android/Drive Watch History in Background
    LaunchedEffect(userEmail, accessToken) {
        if (!userEmail.isNullOrBlank() && !accessToken.isNullOrBlank()) {
            // Start offline safety net worker for this profile
            DesktopWatchHistorySync.startAutoRecoveryWorker(userEmail) {
                DesktopAuthManager.getCredential()?.accessToken ?: accessToken
            }

            withContext(Dispatchers.IO) {
                DesktopWatchHistorySync.pullAndMerge(userEmail, accessToken)
            }
        }
    }

    // App shutdown flush
    DisposableEffect(userEmail, accessToken) {
        val hook = Thread {
            if (!userEmail.isNullOrBlank() && !accessToken.isNullOrBlank()) {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    DesktopWatchHistorySync.pushLocalHistory(userEmail, accessToken, force = true)
                }
            }
        }
        Runtime.getRuntime().addShutdownHook(hook)
        onDispose {
            DesktopWatchHistorySync.stopAutoRecoveryWorker()
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (_: Throwable) {}
        }
    }

    // 2. Library Ingestion & Quiet Background Revalidation
    LaunchedEffect(authState.accessToken, authState.userEmail, rescanTrigger) {
        val token = authState.accessToken
        val email = authState.userEmail
        if (token.isNullOrBlank() || email.isNullOrBlank()) return@LaunchedEffect
        if (!isScanningActive.compareAndSet(false, true)) return@LaunchedEffect

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val credential = DesktopAuthManager.getCredential()
                val scanner = DesktopDriveScanner(accessToken = token, credential = credential)

                // CASE A: User has cached media already loaded on screen
                if (movies.isNotEmpty() || series.isNotEmpty()) {
                    println("[App] Cached media active (${movies.size} movies, ${series.size} series). Running quiet background revalidation...")

                    // Perform one-shot scans in parallel without touching the UI
                    val freshMoviesDeferred = async { scanner.loadMovies() }
                    val freshSeriesDeferred = async { scanner.loadSeriesFolders() }

                    val freshMovies = freshMoviesDeferred.await()
                    val freshSeries = freshSeriesDeferred.await()

                    // Check for actual differences
                    val moviesChanged = freshMovies.isNotEmpty() && (freshMovies.map { it.id } != movies.map { it.id })
                    val seriesChanged = freshSeries.isNotEmpty() && (freshSeries.map { it.id } != series.map { it.id })

                    if (moviesChanged || seriesChanged) {
                        println("[App] Changes detected in Google Drive library. Updating UI smoothly.")
                        if (moviesChanged) movies = freshMovies
                        if (seriesChanged) series = freshSeries
                        DesktopLibraryCache.save(email, if (moviesChanged) freshMovies else movies, if (seriesChanged) freshSeries else series)
                    } else {
                        println("[App] Drive library matches local cache. Zero UI re-renders needed.")
                    }
                }
                // CASE B: Cold start with 0 cached media (First-time user or empty cache)
                else {
                    println("[App] Empty local cache. Using fast-paint progressive streaming...")
                    launch {
                        scanner.streamMovies().collect { batch ->
                            if (batch.isNotEmpty()) {
                                movies = batch
                                isInitialLoading = false
                                DesktopLibraryCache.save(email, batch, series)
                            }
                        }
                    }
                    launch {
                        scanner.streamSeriesFolders().collect { batch ->
                            if (batch.isNotEmpty()) {
                                series = batch
                                isInitialLoading = false
                                DesktopLibraryCache.save(email, movies, batch)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("[App] Background library revalidation error: ${e.message}")
            } finally {
                isInitialLoading = false
                isScanningActive.set(false)
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
                    clearThumbnailMemoryCache()
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
            // 3. KEY BY USER EMAIL: Destroys previous account's composition tree immediately on account switch
            key(userEmail) {
                DesktopHomeScreen(
                    userEmail = userEmail,
                    userName = authState.userName,
                    accessToken = accessToken,
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
                        val loggingOutEmail = userEmail
                        val currentToken = accessToken
                        println("[App] User signing out from: $loggingOutEmail")
                        DesktopWatchHistorySync.stopAutoRecoveryWorker()
                        if (!loggingOutEmail.isNullOrBlank() && !currentToken.isNullOrBlank()) {
                            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                            GlobalScope.launch(Dispatchers.IO) {
                                DesktopWatchHistorySync.pushLocalHistory(loggingOutEmail, currentToken, force = true)
                            }
                        }
                        clearThumbnailMemoryCache()
                        DesktopLibraryCache.clearMemory(loggingOutEmail)
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
            !existing.language.isNullOrBlank()) {
            existing
        } else {
            freshItem
        }
    }
}
