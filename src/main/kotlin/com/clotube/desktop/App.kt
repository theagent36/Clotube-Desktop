package com.clotube.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import com.clotube.desktop.auth.AuthState
import com.clotube.desktop.auth.DesktopAuthManager
import com.clotube.desktop.data.DesktopDriveScanner
import com.clotube.desktop.data.DesktopLibraryCache
import com.clotube.desktop.model.DesktopMediaItem
import com.clotube.desktop.ui.screens.DesktopLoginScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private val isScanningActive = AtomicBoolean(false)

@Composable
fun App() {
    val authManager = remember { DesktopAuthManager() }
    val authState by authManager.authState.collectAsState()

    // Instant Startup: Populate from local disk cache immediately on frame 1
    val initialCache = remember { DesktopLibraryCache.loadSync() }
    var movies by remember { mutableStateOf(initialCache?.movies ?: emptyList()) }
    var series by remember { mutableStateOf(initialCache?.series ?: emptyList()) }
    var isLoading by remember { mutableStateOf(initialCache == null || (initialCache.movies.isEmpty() && initialCache.series.isEmpty())) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        authManager.checkExistingSession()
    }

    // Background Revalidation with singleton scan guard and coroutine lifecycle safety
    val accessToken = (authState as? AuthState.Authenticated)?.accessToken.orEmpty()
    LaunchedEffect(accessToken) {
        if (accessToken.isNotBlank() && isScanningActive.compareAndSet(false, true)) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val scanner = DesktopDriveScanner(accessToken)
                    var freshMovies: List<DesktopMediaItem> = emptyList()
                    var freshSeries: List<DesktopMediaItem> = emptyList()

                    joinAll(
                        launch { freshMovies = scanner.loadMovies() },
                        launch { freshSeries = scanner.loadSeriesFolders() }
                    )

                    if (freshMovies.isNotEmpty() || freshSeries.isNotEmpty()) {
                        movies = freshMovies
                        series = freshSeries
                        DesktopLibraryCache.save(freshMovies, freshSeries)
                    }
                } catch (e: Exception) {
                    System.err.println("[App] Background library sync error: ${e.message}")
                } finally {
                    isScanningActive.set(false)
                    isLoading = false
                }
            }
        }
    }

    Crossfade(
        targetState = authState,
        animationSpec = tween(durationMillis = 200),
        label = "AuthRootTransition"
    ) { state ->
        when (state) {
            is AuthState.Checking -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF000000))
                )
            }

            is AuthState.Unauthenticated, is AuthState.Authenticating -> {
                // Dedicated connection gate — Sidebar and Header MUST be hidden
                DesktopLoginScreen(
                    authState = state,
                    onConnectClick = { authManager.signIn() },
                    onCancelClick = { authManager.cancelSignIn() }
                )
            }

            is AuthState.Authenticated -> {
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0B0E14)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF2F80ED),
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                } else {
                    MainDesktopLayout(
                        authenticatedState = state,
                        onSignOut = { authManager.signOut() },
                        movies = movies,
                        series = series
                    )
                }
            }
        }
    }
}
