package com.clotube.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.clotube.desktop.auth.DesktopAuthManager
import com.clotube.desktop.data.DesktopDriveScanner
import com.clotube.desktop.data.DesktopLibraryCache
import com.clotube.desktop.data.DesktopProfileManager
import com.clotube.desktop.data.DesktopThumbnailPrefetcher
import com.clotube.desktop.data.DesktopWatchHistorySync
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.components.clearThumbnailMemoryCache
import com.clotube.desktop.ui.screens.DesktopHomeScreen
import com.clotube.desktop.ui.screens.DesktopLoginScreen
import com.clotube.desktop.ui.screens.DesktopSetupWizardScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun App(
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    onFullscreenToggle: (Boolean) -> Unit = { onToggleFullscreen() }
) {
    var hasSecrets by remember { mutableStateOf(DesktopAuthManager.hasClientSecrets()) }
    var authState by remember { mutableStateOf(DesktopAuthManager.getSavedAuthState()) }
    var isAuthenticating by remember { mutableStateOf(false) }

    val activeEmail = authState.userEmail
    val initialCache = remember(activeEmail) {
        if (activeEmail.isNotBlank()) DesktopLibraryCache.loadSync(activeEmail) else null
    }

    var movies by remember(activeEmail) {
        mutableStateOf(initialCache?.movies ?: emptyList())
    }
    var series by remember(activeEmail) {
        mutableStateOf(initialCache?.series ?: emptyList())
    }
    var continueWatching by remember(activeEmail) {
        mutableStateOf<List<DesktopMediaItem>>(emptyList())
    }

    // Active scan flag: True on cold start when cache is empty, or during manual rescan
    var isScanning by remember(activeEmail) {
        mutableStateOf(activeEmail.isNotBlank() && initialCache == null)
    }

    var rescanTrigger by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    // Synchronize Android/Drive Watch History in Background
    LaunchedEffect(activeEmail, authState.accessToken) {
        val email = activeEmail
        val token = authState.accessToken
        if (email.isNotBlank() && token.isNotBlank() && authState.isSignedIn) {
            DesktopWatchHistorySync.startAutoRecoveryWorker(email) {
                DesktopAuthManager.getCredential(email)?.accessToken ?: token
            }
            withContext(Dispatchers.IO) {
                DesktopWatchHistorySync.pullAndMerge(email, token)
            }
        }
    }

    // App shutdown flush
    DisposableEffect(activeEmail, authState.accessToken) {
        val email = activeEmail
        val token = authState.accessToken
        val hook = Thread {
            if (email.isNotBlank() && token.isNotBlank()) {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    DesktopWatchHistorySync.pushLocalHistory(email, token, force = true)
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

    // Progressive Chunked Background Drive Scan
    LaunchedEffect(activeEmail, authState.accessToken, rescanTrigger) {
        val email = activeEmail
        val token = authState.accessToken

        if (email.isNotBlank() && token.isNotBlank() && authState.isSignedIn) {
            val hasExistingMedia = movies.isNotEmpty() || series.isNotEmpty()
            if (!hasExistingMedia) {
                isScanning = true
            }

            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val scanner = DesktopDriveScanner(token)

                    // Stream movies in 4-item chunks
                    val moviesJob = launch {
                        scanner.streamMovies().collect { chunk ->
                            if (authState.userEmail.equals(email, ignoreCase = true)) {
                                if (hasExistingMedia) {
                                    // Merge without shrinking or jumping existing list
                                    movies = mergeLibraryItems(movies, chunk)
                                } else {
                                    // Cold start: assign chunk and unlock UI on first 4 items!
                                    movies = chunk
                                    if (chunk.isNotEmpty()) {
                                        isScanning = false
                                    }
                                }
                            }
                        }
                    }

                    // Stream series in 4-item chunks
                    val seriesJob = launch {
                        scanner.streamSeriesFolders().collect { chunk ->
                            if (authState.userEmail.equals(email, ignoreCase = true)) {
                                if (hasExistingMedia) {
                                    series = mergeLibraryItems(series, chunk)
                                } else {
                                    series = chunk
                                    if (chunk.isNotEmpty()) {
                                        isScanning = false
                                    }
                                }
                            }
                        }
                    }

                    // Await full completion of both streams
                    joinAll(moviesJob, seriesJob)

                    // Write full library to persistent disk cache
                    if (movies.isNotEmpty() || series.isNotEmpty()) {
                        DesktopLibraryCache.save(email, movies, series)
                        println("[App] Progressive sync completed and cached: ${movies.size} movies, ${series.size} series")
                    }
                } catch (e: Exception) {
                    System.err.println("[App] Progressive scan error for $email: ${e.message}")
                } finally {
                    isScanning = false
                }
            }
        } else {
            isScanning = false
        }
    }

    // Silently pre-cache library thumbnails one by one in the background
    LaunchedEffect(movies, series, authState.accessToken) {
        if (authState.accessToken.isNotBlank() && (movies.isNotEmpty() || series.isNotEmpty())) {
            DesktopThumbnailPrefetcher.enqueueAll(
                movies = movies,
                series = series,
                accessToken = authState.accessToken,
                userEmail = authState.userEmail
            )
        }
    }

    Crossfade(
        targetState = when {
            !hasSecrets -> "wizard"
            !authState.isSignedIn -> "login"
            else -> "home"
        },
        animationSpec = tween(durationMillis = 350),
        label = "AuthSessionCrossfade"
    ) { screen ->
        when (screen) {
            "wizard" -> {
                DesktopSetupWizardScreen(
                    onConfigured = {
                        hasSecrets = true
                    }
                )
            }
            "login" -> {
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
                    },
                    onSelectProfile = { selectedEmail ->
                        clearThumbnailMemoryCache()
                        val switchedState = DesktopAuthManager.switchProfile(selectedEmail)
                        authState = switchedState
                    },
                    onAddNewAccountClick = {
                        isAuthenticating = true
                        coroutineScope.launch {
                            val newState = DesktopAuthManager.startDesktopOAuthFlow()
                            clearThumbnailMemoryCache()
                            authState = newState
                            isAuthenticating = false
                        }
                    },
                    onReconfigureClick = {
                        hasSecrets = false
                    }
                )
            }
            "home" -> {
                // 3. Isolated Home Screen keyed strictly to the active email
                key(activeEmail) {
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
                        onFullscreenToggle = onFullscreenToggle,
                        isSyncing = isScanning,
                        onRescan = {
                            println("[App] Manual cloud rescan requested for: $activeEmail")
                            isScanning = true
                            rescanTrigger++
                        },
                        onSignOut = {
                            println("[App] Signing out active session: $activeEmail")
                            DesktopWatchHistorySync.stopAutoRecoveryWorker()
                            if (activeEmail.isNotBlank() && authState.accessToken.isNotBlank()) {
                                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                                GlobalScope.launch(Dispatchers.IO) {
                                    DesktopWatchHistorySync.pushLocalHistory(activeEmail, authState.accessToken, force = true)
                                }
                            }
                            clearThumbnailMemoryCache()
                            DesktopLibraryCache.clearMemory(activeEmail)
                            DesktopAuthManager.signOut()
                            // Immediately flush in-memory states
                            movies = emptyList()
                            series = emptyList()
                            continueWatching = emptyList()
                            isScanning = false
                            authState = DesktopAuthManager.getSavedAuthState()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Merges fresh items while keeping existing item references and positions stable.
 */
private fun mergeLibraryItems(
    current: List<DesktopMediaItem>,
    fresh: List<DesktopMediaItem>
): List<DesktopMediaItem> {
    if (current.isEmpty()) return fresh
    val currentIds = current.map { it.id }.toSet()
    val newAdditions = fresh.filterNot { currentIds.contains(it.id) }
    return if (newAdditions.isEmpty()) current else current + newAdditions
}
