package com.clotube.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.clotube.desktop.auth.DesktopAuthManager
import com.clotube.desktop.data.DesktopContinueWatchingSnapshotStore
import com.clotube.desktop.data.DesktopDriveScanner
import com.clotube.desktop.data.DesktopLibraryCache
import com.clotube.desktop.data.DesktopOnlineMetadataResolver
import com.clotube.desktop.data.DesktopThumbnailPrefetcher
import com.clotube.desktop.data.DesktopWatchHistoryManager
import com.clotube.desktop.data.DesktopWatchHistorySync
import com.clotube.desktop.data.DesktopWatchlistSync
import com.clotube.desktop.image.ArtworkProvenance
import com.clotube.desktop.image.DesktopCanonicalImageCache
import com.clotube.desktop.lifecycle.AppLifecycleManager
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.network.DesktopNetworkMonitor
import com.clotube.desktop.ui.components.OfflineConnectionPopup
import com.clotube.desktop.ui.components.clearThumbnailMemoryCache
import com.clotube.desktop.ui.components.invalidateFailedUrl
import com.clotube.desktop.ui.screens.DesktopHomeScreen
import com.clotube.desktop.ui.screens.DesktopLoginScreen
import com.clotube.desktop.ui.screens.DesktopSetupWizardScreen
import com.clotube.desktop.ui.state.HeroSlotStateMachine
import com.clotube.desktop.update.DesktopUpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        mutableStateOf<List<DesktopMediaItem>>(initialCache?.movies ?: emptyList())
    }
    var series by remember(activeEmail) {
        mutableStateOf<List<DesktopMediaItem>>(
            initialCache?.series?.map { if (it.isSeries) it else it.copy(isSeries = true) } ?: emptyList()
        )
    }

    // Frame-1 cold-boot hydration from profile-scoped cw_hero_snapshot.json & cw_shelf_snapshot.json before first render
    remember(activeEmail, authState.isSignedIn) {
        if (activeEmail.isNotBlank() && authState.isSignedIn) {
            DesktopWatchHistoryManager.bindProfile(activeEmail)
            HeroSlotStateMachine.hydrateFromLocalSnapshot(activeEmail)
            DesktopContinueWatchingSnapshotStore.readShelfSnapshot(activeEmail)
            val cachedMovies = initialCache?.movies.orEmpty()
            val cachedSeries = initialCache?.series?.map { if (it.isSeries) it else it.copy(isSeries = true) }.orEmpty()
            registerCatalogOfficialArtwork(cachedMovies, cachedSeries)
            HeroSlotStateMachine.onCatalogFeaturedAvailable(
                selectFeaturedCatalogItem(cachedMovies, cachedSeries)
            )
        } else {
            HeroSlotStateMachine.resetForProfileSwitch("")
        }
        activeEmail
    }

    // Active scan flag: True on cold start when cache is empty, or during manual rescan
    var isScanning by remember(activeEmail) {
        mutableStateOf(activeEmail.isNotBlank() && initialCache == null)
    }

    var rescanTrigger by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    val isOnline by DesktopNetworkMonitor.isOnline.collectAsState()
    val showOfflinePopup by DesktopNetworkMonitor.showOfflinePopup.collectAsState()
    val isCheckingNow by DesktopNetworkMonitor.isCheckingNow.collectAsState()

    var wasOffline by remember { mutableStateOf(!isOnline) }
    LaunchedEffect(isOnline) {
        if (isOnline && wasOffline) {
            println("[App] Internet connection restored. Triggering silent library rescan...")
            rescanTrigger++
        }
        wasOffline = !isOnline
    }

    // Background validation of movie poster URLs & language classifications:
    // If blank/unreachable posters or outdated classifications (e.g. Deadpool & Wolverine), re-resolve via IMDb/TMDB
    LaunchedEffect(activeEmail, movies.isNotEmpty()) {
        val email = activeEmail
        if (email.isNotBlank() && movies.isNotEmpty() && DesktopNetworkMonitor.isOnline.value) {
            withContext(Dispatchers.IO) {
                var cacheModified = false
                val updatedMovies = movies.map { movie ->
                    val poster = movie.thumbnailUrl ?: ""
                    val needsPosterRecheck = poster.isBlank() || (poster.contains("m.media-amazon.com") && !DesktopOnlineMetadataResolver.isPosterUrlReachable(poster))
                    val titleLower = movie.title.lowercase()
                    val isPotentiallyMisclassified = movie.language.isBlank() ||
                            movie.language == "Unknown" ||
                            (movie.language.equals("Hindi", ignoreCase = true) && (titleLower.contains("deadpool") || titleLower.contains("dune") || titleLower.contains("top gun"))) ||
                            (movie.language.equals("Bengali", ignoreCase = true) && titleLower.contains("deadpool")) ||
                            (movie.language.equals("English", ignoreCase = true) && DesktopOnlineMetadataResolver.deduceLanguageFromTitle(movie.title) != "English")

                    if (needsPosterRecheck || isPotentiallyMisclassified) {
                        if (needsPosterRecheck) {
                            invalidateFailedUrl(poster)
                        }
                        val meta = DesktopOnlineMetadataResolver.resolveMovie(movie.title)
                        var changed = false
                        var newTitle = movie.title
                        var newPoster = movie.thumbnailUrl
                        var newYear = movie.releaseYear
                        var newGenre = movie.genre
                        var newLanguage = movie.language

                        if (meta.posterUrl.isNotBlank() && meta.posterUrl != poster) {
                            newPoster = meta.posterUrl
                            changed = true
                        }
                        if (meta.officialTitle.isNotBlank() && meta.officialTitle != movie.title) {
                            newTitle = meta.officialTitle
                            changed = true
                        }
                        if (meta.releaseYear.isNotBlank() && movie.releaseYear.isBlank()) {
                            newYear = meta.releaseYear
                            changed = true
                        }
                        if (meta.genre.isNotBlank() && movie.genre == "Drama • Cinema") {
                            newGenre = meta.genre
                            changed = true
                        }
                        if (meta.language.isNotBlank() && meta.language != movie.language) {
                            newLanguage = meta.language
                            changed = true
                        }

                        if (changed) {
                            cacheModified = true
                            movie.copy(
                                title = newTitle,
                                releaseYear = newYear,
                                genre = newGenre,
                                thumbnailUrl = newPoster,
                                language = newLanguage
                            )
                        } else {
                            movie
                        }
                    } else {
                        movie
                    }
                }
                if (cacheModified) {
                    movies = updatedMovies
                    registerCatalogOfficialArtwork(updatedMovies, series)
                    HeroSlotStateMachine.onCatalogFeaturedAvailable(
                        selectFeaturedCatalogItem(updatedMovies, series)
                    )
                    DesktopLibraryCache.save(email, updatedMovies, series)
                }
            }
        }
    }

    // 1. Startup & Profile Switch Boot-Gating: Configure RAM state holder, purge legacy disk cache, and pull latest V2 history
    LaunchedEffect(activeEmail, authState.accessToken) {
        val email = activeEmail
        val token = authState.accessToken
        if (email.isNotBlank() && token.isNotBlank() && authState.isSignedIn) {
            try {
                DesktopWatchHistorySync.configure(email, token)
                if (DesktopNetworkMonitor.isOnline.value) {
                    withContext(Dispatchers.IO) {
                        DesktopWatchHistorySync.syncFromCloud(force = true)
                        DesktopWatchlistSync.pullRemoteWatchlist(email, token)
                    }
                    DesktopWatchHistorySync.startAutoRecoveryWorker(email) {
                        DesktopAuthManager.getCredential(email)?.accessToken ?: token
                    }
                } else if (DesktopContinueWatchingSnapshotStore.readSnapshot(email) == null) {
                    HeroSlotStateMachine.onContinueWatchingResolved(null, explicitClear = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("[App] Background sync/scan skipped due to network error: ${e.message}")
            }
        }
    }

    // 2. Foreground Window-Focus Sync: Update watch progress immediately when returning from Android
    LaunchedEffect(AppLifecycleManager.isAppActive, activeEmail, authState.accessToken) {
        val email = activeEmail
        val token = authState.accessToken
        if (AppLifecycleManager.isAppActive && email.isNotBlank() && token.isNotBlank() && authState.isSignedIn) {
            if (DesktopNetworkMonitor.isOnline.value) {
                withContext(Dispatchers.IO) {
                    DesktopWatchHistorySync.syncFromCloud(force = false)
                }
            }
        }
    }

    // App shutdown flush (persist cw_hero_snapshot.json synchronously + flush Drive V2)
    DisposableEffect(activeEmail, authState.accessToken) {
        val email = activeEmail
        val token = authState.accessToken
        val hook = Thread {
            if (email.isNotBlank() && token.isNotBlank()) {
                DesktopWatchHistorySync.flushBlockingOnWindowClose()
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

            try {
                if (!DesktopNetworkMonitor.isOnline.value) {
                    System.err.println("[App] Device is offline. Skipping library scan.")
                    isScanning = false
                    return@LaunchedEffect
                }

                val scanner = withContext(Dispatchers.IO) {
                    DesktopDriveScanner(token)
                }

                // Stream movies in 4-item chunks
                val moviesJob = launch {
                    scanner.streamMovies().collect { chunk ->
                        if (authState.userEmail.equals(email, ignoreCase = true)) {
                            val nextMovies = if (hasExistingMedia) {
                                mergeLibraryItems(movies, chunk)
                            } else {
                                if (chunk.isNotEmpty()) {
                                    isScanning = false
                                }
                                chunk
                            }
                            movies = nextMovies
                            registerCatalogOfficialArtwork(nextMovies, series)
                            HeroSlotStateMachine.onCatalogFeaturedAvailable(
                                selectFeaturedCatalogItem(nextMovies, series)
                            )
                        }
                    }
                }

                // Stream series in 4-item chunks
                val seriesJob = launch {
                    scanner.streamSeriesFolders().collect { chunk ->
                        if (authState.userEmail.equals(email, ignoreCase = true)) {
                            val normalizedChunk = chunk.map { if (it.isSeries) it else it.copy(isSeries = true) }
                            val nextSeries = if (hasExistingMedia) {
                                mergeLibraryItems(series, normalizedChunk)
                            } else {
                                if (normalizedChunk.isNotEmpty()) {
                                    isScanning = false
                                }
                                normalizedChunk
                            }
                            series = nextSeries
                            registerCatalogOfficialArtwork(movies, nextSeries)
                            HeroSlotStateMachine.onCatalogFeaturedAvailable(
                                selectFeaturedCatalogItem(movies, nextSeries)
                            )
                        }
                    }
                }

                // Await full completion of both streams
                joinAll(moviesJob, seriesJob)

                // Write full library to persistent disk cache
                if (authState.userEmail.equals(email, ignoreCase = true) &&
                    (movies.isNotEmpty() || series.isNotEmpty())
                ) {
                    DesktopLibraryCache.save(email, movies, series)
                    println("[App] Progressive sync completed and cached: ${movies.size} movies, ${series.size} series")
                }
                isScanning = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("[App] Progressive scan error for $email: ${e.message}")
                isScanning = false
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                            DesktopCanonicalImageCache.clearMemory()
                            DesktopContinueWatchingSnapshotStore.clearMemoryForProfileSwitch()
                            HeroSlotStateMachine.resetForProfileSwitch(newState.userEmail)
                            authState = newState
                            isAuthenticating = false
                        }
                    },
                    onSelectProfile = { selectedEmail ->
                        clearThumbnailMemoryCache()
                        DesktopCanonicalImageCache.clearMemory()
                        DesktopContinueWatchingSnapshotStore.clearMemoryForProfileSwitch()
                        HeroSlotStateMachine.resetForProfileSwitch(selectedEmail)
                        val switchedState = DesktopAuthManager.switchProfile(selectedEmail)
                        authState = switchedState
                    },
                    onAddNewAccountClick = {
                        isAuthenticating = true
                        coroutineScope.launch {
                            val newState = DesktopAuthManager.startDesktopOAuthFlow()
                            clearThumbnailMemoryCache()
                            DesktopCanonicalImageCache.clearMemory()
                            DesktopContinueWatchingSnapshotStore.clearMemoryForProfileSwitch()
                            HeroSlotStateMachine.resetForProfileSwitch(newState.userEmail)
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
                LaunchedEffect(activeEmail) {
                    kotlinx.coroutines.delay(500L)
                    DesktopUpdateManager.onHomeScreenEntered()
                }
                DisposableEffect(activeEmail) {
                    onDispose {
                        DesktopUpdateManager.onHomeScreenExited()
                    }
                }
                // 3. Isolated Home Screen keyed strictly to the active email
                key(activeEmail) {
                    DesktopHomeScreen(
                        userEmail = authState.userEmail,
                        userName = authState.userName,
                        accessToken = authState.accessToken,
                        userAvatarUrl = authState.userPhotoUrl ?: "",
                        movies = movies,
                        series = series,
                        continueWatching = emptyList(),
                        onMediaClick = {},
                        onClearHistory = {},
                        onRemoveHistoryItem = {},
                        isFullscreen = isFullscreen,
                        onToggleFullscreen = onToggleFullscreen,
                        isAppFullscreen = isFullscreen,
                        onFullscreenToggle = onFullscreenToggle,
                        isSyncing = isScanning,
                        onRescan = {
                            println("[App] Manual cloud rescan requested for: $activeEmail")
                            isScanning = true
                            rescanTrigger++
                            coroutineScope.launch(Dispatchers.IO) {
                                DesktopWatchHistorySync.syncFromCloud(force = true)
                            }
                        },
                        onSignOut = {
                            println("[App] Signing out active session: $activeEmail")
                            DesktopUpdateManager.onHomeScreenExited()
                            DesktopWatchHistorySync.flushBlockingOnWindowClose()
                            DesktopWatchHistorySync.stopAutoRecoveryWorker()
                            DesktopWatchHistoryManager.clearInMemory()
                            DesktopContinueWatchingSnapshotStore.clearMemoryForProfileSwitch(activeEmail)
                            HeroSlotStateMachine.resetForProfileSwitch("")
                            DesktopCanonicalImageCache.clearMemory()
                            DesktopWatchHistorySync.invalidateCache()
                            clearThumbnailMemoryCache()
                            DesktopLibraryCache.clearMemory(activeEmail)
                            DesktopAuthManager.signOut()
                            // Immediately flush in-memory states
                            movies = emptyList()
                            series = emptyList()
                            isScanning = false
                            authState = DesktopAuthManager.getSavedAuthState()
                        }
                    )
                }
            }
        }
    }

    OfflineConnectionPopup(
        visible = showOfflinePopup,
        isCheckingNow = isCheckingNow,
        onRetryNow = { DesktopNetworkMonitor.retryConnectionNow() },
        onDismiss = { DesktopNetworkMonitor.dismissOfflinePopup() }
    )
}
}

private fun selectFeaturedCatalogItem(
    movies: List<DesktopMediaItem>,
    series: List<DesktopMediaItem>
): DesktopMediaItem? {
    return movies.firstOrNull {
        DesktopCanonicalImageCache.classifyProvenance(it.thumbnailUrl) == ArtworkProvenance.OFFICIAL_CATALOG
    } ?: movies.firstOrNull()
        ?: series.firstOrNull {
            DesktopCanonicalImageCache.classifyProvenance(it.thumbnailUrl) == ArtworkProvenance.OFFICIAL_CATALOG
        }?.let { if (it.isSeries) it else it.copy(isSeries = true) }
        ?: series.firstOrNull()?.let { if (it.isSeries) it else it.copy(isSeries = true) }
}

private fun registerCatalogOfficialArtwork(
    movies: List<DesktopMediaItem>,
    series: List<DesktopMediaItem>
) {
    for (movie in movies) {
        val url = movie.thumbnailUrl?.takeIf {
            DesktopCanonicalImageCache.classifyProvenance(it) == ArtworkProvenance.OFFICIAL_CATALOG
        }
        if (url != null) {
            DesktopCanonicalImageCache.registerOfficialArtworkUrl(
                canonicalKey = DesktopCanonicalImageCache.moviePosterKey(movie.id),
                officialUrl = url,
                linkedMediaId = movie.id
            )
            HeroSlotStateMachine.enrichContinueWatchingInPlace(
                mediaId = movie.id,
                officialArtworkUrl = url,
                resolvedTitle = movie.title
            )
        }
    }
    for (show in series) {
        val url = show.thumbnailUrl?.takeIf {
            DesktopCanonicalImageCache.classifyProvenance(it) == ArtworkProvenance.OFFICIAL_CATALOG
        }
        if (url != null) {
            DesktopCanonicalImageCache.registerOfficialArtworkUrl(
                canonicalKey = DesktopCanonicalImageCache.seriesPosterKey(show.id),
                officialUrl = url
            )
            HeroSlotStateMachine.enrichContinueWatchingInPlace(
                mediaId = "",
                seriesId = show.id,
                officialArtworkUrl = url,
                resolvedSeriesTitle = show.title
            )
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
    val currentById = current.associateBy { it.id }
    val freshById = fresh.associateBy { it.id }
    val updatedExisting = current.map { existing ->
        val incoming = freshById[existing.id]
        if (incoming != null &&
            DesktopCanonicalImageCache.classifyProvenance(incoming.thumbnailUrl) == ArtworkProvenance.OFFICIAL_CATALOG &&
            incoming.thumbnailUrl != existing.thumbnailUrl
        ) {
            incoming
        } else {
            existing
        }
    }
    val newAdditions = fresh.filterNot { currentById.containsKey(it.id) }
    return if (newAdditions.isEmpty()) updatedExisting else updatedExisting + newAdditions
}
