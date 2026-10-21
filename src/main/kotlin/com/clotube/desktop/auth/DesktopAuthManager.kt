package com.clotube.desktop.auth

import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.GenericJson
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader

sealed class AuthState {
    data object Checking : AuthState()
    data class Unauthenticated(val errorMessage: String? = null) : AuthState()
    data object Authenticating : AuthState()
    data class Authenticated(
        val accessToken: String,
        val userEmail: String,
        val userName: String,
        val userPhotoUrl: String?
    ) : AuthState()
}

class DesktopAuthManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Checking)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val httpTransport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()

    private var currentReceiver: LocalServerReceiver? = null
    private var authJob: Job? = null

    private val tokensDir: File by lazy {
        val baseDir = System.getenv("APPDATA")
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        File(baseDir, "Clotube" + File.separator + "tokens").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    private val dataStoreFactory: FileDataStoreFactory by lazy {
        FileDataStoreFactory(tokensDir)
    }

    companion object {
        val SCOPES: List<String> = listOf(
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/drive.file",
            "openid",
            "email",
            "profile"
        )

        private const val FALLBACK_CLIENT_ID = "YOUR_CLIENT_ID.apps.googleusercontent.com"
        private const val FALLBACK_CLIENT_SECRET = "YOUR_CLIENT_SECRET"
    }

    init {
        checkExistingSession()
    }

    /**
     * Resolves the client_secrets.json InputStream using a multi-source discovery strategy:
     * 1. Thread.currentThread().contextClassLoader.getResourceAsStream("client_secrets.json")
     * 2. DesktopAuthManager::class.java.getResourceAsStream("/client_secrets.json")
     * 3. %APPDATA%\Clotube\client_secrets.json
     * 4. Relative paths: File("src/main/resources/client_secrets.json") and File("client_secrets.json")
     */
    private fun loadClientSecretsStream(): InputStream? {
        // 1. Thread Context ClassLoader
        try {
            val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream("client_secrets.json")
            if (stream != null) {
                println("[DesktopAuthManager] Loaded client_secrets.json from Thread.currentThread().contextClassLoader")
                return stream
            }
        } catch (e: Exception) {
            System.err.println("[DesktopAuthManager] ContextClassLoader resource check failed: ${e.message}")
        }

        // 2. Class-Relative Root Stream
        try {
            val stream = DesktopAuthManager::class.java.getResourceAsStream("/client_secrets.json")
            if (stream != null) {
                println("[DesktopAuthManager] Loaded client_secrets.json from DesktopAuthManager::class.java.getResourceAsStream")
                return stream
            }
        } catch (e: Exception) {
            System.err.println("[DesktopAuthManager] Class.getResourceAsStream check failed: ${e.message}")
        }

        // 3. %APPDATA%\Clotube\client_secrets.json
        try {
            val appDataBase = System.getenv("APPDATA")
                ?.takeIf { it.isNotBlank() }
                ?: System.getProperty("user.home")
            val appDataFile = File(appDataBase, "Clotube" + File.separator + "client_secrets.json")
            if (appDataFile.exists() && appDataFile.length() > 0) {
                println("[DesktopAuthManager] Loaded client_secrets.json from APPDATA: ${appDataFile.absolutePath}")
                return FileInputStream(appDataFile)
            }
        } catch (e: Exception) {
            System.err.println("[DesktopAuthManager] APPDATA file check failed: ${e.message}")
        }

        // 4. Relative File Paths: src/main/resources/client_secrets.json and client_secrets.json
        val candidateFiles = listOf(
            File("src/main/resources/client_secrets.json"),
            File("client_secrets.json")
        )

        for (candidate in candidateFiles) {
            try {
                if (candidate.exists() && candidate.length() > 0) {
                    println("[DesktopAuthManager] Loaded client_secrets.json from filesystem path: ${candidate.absolutePath}")
                    return FileInputStream(candidate)
                }
            } catch (e: Exception) {
                System.err.println("[DesktopAuthManager] Relative file check failed for ${candidate.path}: ${e.message}")
            }
        }

        System.err.println("[DesktopAuthManager] Warning: client_secrets.json could not be found across any source (ClassLoader, APPDATA, or relative filesystem paths).")
        return null
    }

    private fun loadClientSecrets(): GoogleClientSecrets {
        val stream = loadClientSecretsStream()
        if (stream != null) {
            try {
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    val secrets = GoogleClientSecrets.load(jsonFactory, reader)
                    val clientId = secrets.installed?.clientId ?: secrets.web?.clientId
                    if (!clientId.isNullOrBlank()) {
                        println("[DesktopAuthManager] client_secrets.json parsed successfully. Client ID: ${clientId.take(16)}...")
                        return secrets
                    } else {
                        System.err.println("[DesktopAuthManager] client_secrets.json was parsed, but clientId is null or blank.")
                    }
                }
            } catch (e: Exception) {
                System.err.println("[DesktopAuthManager] Error parsing client_secrets.json: ${e.message}")
                e.printStackTrace(System.err)
            }
        }

        // 5. Fallback configuration
        println("[DesktopAuthManager] Falling back to default placeholder client secrets.")
        val fallbackJson = """
            {
              "installed": {
                "client_id": "$FALLBACK_CLIENT_ID",
                "client_secret": "$FALLBACK_CLIENT_SECRET",
                "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                "token_uri": "https://oauth2.googleapis.com/token",
                "redirect_uris": ["http://localhost"]
              }
            }
        """.trimIndent()

        return GoogleClientSecrets.load(jsonFactory, StringReader(fallbackJson))
    }

    private fun buildFlow(): GoogleAuthorizationCodeFlow {
        val clientSecrets = loadClientSecrets()
        return GoogleAuthorizationCodeFlow.Builder(
            httpTransport,
            jsonFactory,
            clientSecrets,
            SCOPES
        )
            .setDataStoreFactory(dataStoreFactory)
            .setAccessType("offline")
            .build()
    }

    fun checkExistingSession() {
        scope.launch(Dispatchers.IO) {
            _authState.value = AuthState.Checking
            try {
                val flow = buildFlow()
                val credential = flow.loadCredential("user")

                if (credential != null && credential.refreshToken != null) {
                    val isValid = try {
                        val refreshed = credential.refreshToken()
                        refreshed || (credential.expiresInSeconds != null && credential.expiresInSeconds > 30)
                    } catch (e: Exception) {
                        System.err.println("[DesktopAuthManager] Token refresh error during session check: ${e.message}")
                        false
                    }

                    if (isValid && credential.accessToken != null) {
                        val profile = fetchUserProfile(credential)
                        _authState.value = AuthState.Authenticated(
                            accessToken = credential.accessToken,
                            userEmail = profile.email,
                            userName = profile.name,
                            userPhotoUrl = profile.photoUrl
                        )
                        return@launch
                    }
                }

                _authState.value = AuthState.Unauthenticated()
            } catch (e: Exception) {
                System.err.println("[DesktopAuthManager] Exception during checkExistingSession: ${e.message}")
                e.printStackTrace(System.err)
                _authState.value = AuthState.Unauthenticated(
                    errorMessage = e.localizedMessage ?: "Failed to verify stored credentials"
                )
            }
        }
    }

    fun signIn() {
        authJob?.cancel()
        authJob = scope.launch(Dispatchers.IO) {
            _authState.value = AuthState.Authenticating
            try {
                val clientSecrets = loadClientSecrets()
                val clientId = clientSecrets.installed?.clientId ?: clientSecrets.web?.clientId
                if (clientId.isNullOrBlank() || clientId.contains("YOUR_CLIENT_ID")) {
                    val errorMsg = "Please configure your Google OAuth Client ID in 'client_secrets.json' (located in %APPDATA%\\Clotube or src/main/resources)."
                    System.err.println("[DesktopAuthManager] $errorMsg")
                    _authState.value = AuthState.Unauthenticated(errorMessage = errorMsg)
                    return@launch
                }

                val flow = buildFlow()

                // Ephemeral free port on localhost to avoid port conflicts
                val receiver = LocalServerReceiver.Builder()
                    .setPort(-1)
                    .build()
                currentReceiver = receiver

                val app = AuthorizationCodeInstalledApp(flow, receiver)
                val credential = app.authorize("user")

                if (credential?.accessToken != null) {
                    val profile = fetchUserProfile(credential)
                    _authState.value = AuthState.Authenticated(
                        accessToken = credential.accessToken,
                        userEmail = profile.email,
                        userName = profile.name,
                        userPhotoUrl = profile.photoUrl
                    )
                } else {
                    val errorMsg = "Sign in failed: No access token received from loopback handler."
                    System.err.println("[DesktopAuthManager] $errorMsg")
                    _authState.value = AuthState.Unauthenticated(errorMsg)
                }
            } catch (e: CancellationException) {
                println("[DesktopAuthManager] Sign-in coroutine was cancelled.")
                _authState.value = AuthState.Unauthenticated()
            } catch (e: Exception) {
                System.err.println("[DesktopAuthManager] Sign-in failure: ${e.message}")
                e.printStackTrace(System.err)
                _authState.value = AuthState.Unauthenticated(
                    errorMessage = e.localizedMessage ?: "Google sign in error occurred"
                )
            } finally {
                stopReceiver()
            }
        }
    }

    fun cancelSignIn() {
        authJob?.cancel()
        stopReceiver()
        _authState.value = AuthState.Unauthenticated()
    }

    private fun stopReceiver() {
        try {
            currentReceiver?.stop()
        } catch (e: Exception) {
            System.err.println("[DesktopAuthManager] Error stopping LocalServerReceiver: ${e.message}")
        }
        currentReceiver = null
    }

    fun signOut() {
        scope.launch(Dispatchers.IO) {
            try {
                stopReceiver()
                if (tokensDir.exists()) {
                    tokensDir.listFiles()?.forEach { file ->
                        file.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                System.err.println("[DesktopAuthManager] Error during signOut token cleanup: ${e.message}")
            } finally {
                _authState.value = AuthState.Unauthenticated()
            }
        }
    }

    private fun fetchUserProfile(credential: Credential): UserProfile {
        return try {
            val requestFactory = httpTransport.createRequestFactory { req ->
                credential.initialize(req)
                req.parser = com.google.api.client.json.JsonObjectParser(jsonFactory)
            }
            val url = GenericUrl("https://www.googleapis.com/oauth2/v3/userinfo")
            val request = requestFactory.buildGetRequest(url)
            val response = request.execute()
            val json = response.parseAs(GenericJson::class.java)

            val email = json["email"] as? String ?: "user@gmail.com"
            val name = json["name"] as? String ?: email.substringBefore("@")
            val picture = json["picture"] as? String

            UserProfile(email = email, name = name, photoUrl = picture)
        } catch (e: Exception) {
            System.err.println("[DesktopAuthManager] Warning: Failed to fetch Google user profile: ${e.message}")
            UserProfile(email = "cloud.user@gmail.com", name = "Cloud Cinema User", photoUrl = null)
        }
    }

    private data class UserProfile(
        val email: String,
        val name: String,
        val photoUrl: String?
    )
}
