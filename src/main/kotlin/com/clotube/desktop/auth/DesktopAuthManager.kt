package com.clotube.desktop.auth

import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.oauth2.Oauth2
import com.google.api.services.oauth2.model.Userinfo
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

data class DesktopAuthState(
    val isSignedIn: Boolean = false,
    val accessToken: String = "",
    val userEmail: String = "",
    val userName: String = "",
    val userPhotoUrl: String? = null
)

// Legacy state model for composables observing sealed AuthState
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

object DesktopAuthManager {

    private val JSON_FACTORY = GsonFactory.getDefaultInstance()
    private val HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport()
    private val gson = Gson()

    private val SCOPES = listOf(
        "https://www.googleapis.com/auth/drive.readonly",
        "openid",
        "email",
        "profile"
    )

    private var flowInstance: GoogleAuthorizationCodeFlow? = null
    private var dataStoreFactoryInstance: FileDataStoreFactory? = null
    private var cachedAuthState: DesktopAuthState? = null

    private fun getClotubeDataDir(): File {
        val appData = System.getenv("APPDATA")
        val baseDir = if (!appData.isNullOrBlank()) {
            File(appData, "Clotube")
        } else {
            File(System.getProperty("user.home"), ".clotube")
        }
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        return baseDir
    }

    private fun getTokensDirectory(): File = File(getClotubeDataDir(), "tokens")
    private fun getAuthStateFile(): File = File(getClotubeDataDir(), "auth_state.json")

    private fun loadClientSecrets(): GoogleClientSecrets {
        val searchStreams = listOf<() -> InputStream?>(
            { Thread.currentThread().contextClassLoader.getResourceAsStream("client_secrets.json") },
            { DesktopAuthManager::class.java.getResourceAsStream("/client_secrets.json") },
            {
                val f = File(getClotubeDataDir(), "client_secrets.json")
                if (f.exists()) f.inputStream() else null
            },
            {
                val f = File("src/main/resources/client_secrets.json")
                if (f.exists()) f.inputStream() else null
            },
            {
                val f = File("client_secrets.json")
                if (f.exists()) f.inputStream() else null
            }
        )

        for (provider in searchStreams) {
            try {
                val stream = provider()
                if (stream != null) {
                    InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        return GoogleClientSecrets.load(JSON_FACTORY, reader)
                    }
                }
            } catch (_: Throwable) {}
        }

        throw IllegalStateException("Unable to locate 'client_secrets.json'. Place it in src/main/resources/ or %APPDATA%/Clotube/")
    }

    @Synchronized
    private fun getOrCreateFlow(forceRefresh: Boolean = false): GoogleAuthorizationCodeFlow {
        if (forceRefresh || flowInstance == null) {
            val tokensDir = getTokensDirectory()
            if (!tokensDir.exists()) tokensDir.mkdirs()

            val dataStoreFactory = FileDataStoreFactory(tokensDir)
            dataStoreFactoryInstance = dataStoreFactory

            val clientSecrets = loadClientSecrets()

            flowInstance = GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT,
                JSON_FACTORY,
                clientSecrets,
                SCOPES
            )
                .setDataStoreFactory(dataStoreFactory)
                .setAccessType("offline")
                .build()
        }
        return flowInstance!!
    }

    /**
     * Retrieves the active OAuth Credential with auto-refresh capability.
     * If the access token is expired or within 60s of expiration, it refreshes it automatically.
     */
    @Synchronized
    fun getCredential(): Credential? {
        return try {
            val flow = getOrCreateFlow()
            val credential = flow.loadCredential("user") ?: return null

            val expiresIn = credential.expiresInSeconds
            if (expiresIn == null || expiresIn <= 60) {
                println("[DesktopAuthManager] Access token expired or expiring soon. Refreshing via OAuth endpoint...")
                val refreshed = credential.refreshToken()
                if (refreshed && credential.accessToken != null) {
                    println("[DesktopAuthManager] Access token successfully refreshed!")
                    val current = cachedAuthState
                    if (current != null && current.isSignedIn) {
                        val updated = current.copy(accessToken = credential.accessToken)
                        cachedAuthState = updated
                        val authFile = getAuthStateFile()
                        authFile.writeText(gson.toJson(updated), Charsets.UTF_8)
                    }
                }
            }
            credential
        } catch (e: Exception) {
            System.err.println("[DesktopAuthManager] Error loading or refreshing credential: ${e.message}")
            null
        }
    }

    fun getSavedAuthState(): DesktopAuthState {
        cachedAuthState?.let { return it }

        val authFile = getAuthStateFile()
        if (!authFile.exists() || authFile.length() == 0L) {
            val unauth = DesktopAuthState(isSignedIn = false)
            cachedAuthState = unauth
            return unauth
        }

        return try {
            val json = authFile.readText(Charsets.UTF_8)
            val state = gson.fromJson(json, DesktopAuthState::class.java)
            if (state != null && state.isSignedIn) {
                val credential = getCredential()
                if (credential != null && credential.accessToken != null) {
                    val validState = state.copy(accessToken = credential.accessToken)
                    cachedAuthState = validState
                    validState
                } else {
                    DesktopAuthState(isSignedIn = false)
                }
            } else {
                DesktopAuthState(isSignedIn = false)
            }
        } catch (e: Exception) {
            DesktopAuthState(isSignedIn = false)
        }
    }

    suspend fun startDesktopOAuthFlow(): DesktopAuthState = withContext(Dispatchers.IO) {
        try {
            // Build a completely fresh flow instance for the login session
            val flow = getOrCreateFlow(forceRefresh = true)
            val receiver = LocalServerReceiver.Builder().setPort(-1).build()

            // Custom installer injecting prompt=select_account into Google OAuth URL
            val installedApp = object : AuthorizationCodeInstalledApp(flow, receiver) {
                override fun onAuthorization(authorizationUrl: AuthorizationCodeRequestUrl) {
                    authorizationUrl.set("prompt", "select_account")
                    super.onAuthorization(authorizationUrl)
                }
            }

            val credential: Credential = installedApp.authorize("user")

            // Retrieve authenticated Google profile metadata
            val oauth2 = Oauth2.Builder(
                HTTP_TRANSPORT,
                JSON_FACTORY,
                credential
            ).setApplicationName("Clotube Desktop").build()

            val userInfo: Userinfo = oauth2.userinfo().get().execute()
            val email = userInfo.email ?: ""
            val name = userInfo.name ?: userInfo.givenName ?: email.substringBefore('@')
            val photoUrl = userInfo.picture
            val token = credential.accessToken ?: ""

            val state = DesktopAuthState(
                isSignedIn = true,
                accessToken = token,
                userEmail = email,
                userName = name,
                userPhotoUrl = photoUrl
            )

            // Persist to disk and update cache
            val authFile = getAuthStateFile()
            authFile.writeText(gson.toJson(state), Charsets.UTF_8)
            cachedAuthState = state

            println("[DesktopAuthManager] OAuth successfully authenticated: $email")
            state
        } catch (e: Exception) {
            System.err.println("[DesktopAuthManager] OAuth flow aborted/failed: ${e.message}")
            e.printStackTrace()
            DesktopAuthState(isSignedIn = false)
        }
    }

    /**
     * Completely purges in-memory caches, active credentials, and disk tokens.
     */
    fun signOut() {
        println("[DesktopAuthManager] Signing out: Purging in-memory maps and disk tokens...")

        // 1. Invalidate Google's in-memory credential data store map
        try {
            flowInstance?.credentialDataStore?.clear()
        } catch (e: Exception) {
            System.err.println("[DesktopAuthManager] Warning clearing credential store: ${e.message}")
        }

        // 2. Tear down in-memory references
        flowInstance = null
        dataStoreFactoryInstance = null
        cachedAuthState = DesktopAuthState(isSignedIn = false)

        // 3. Delete disk token files and auth state cache
        try {
            val tokensDir = getTokensDirectory()
            if (tokensDir.exists()) {
                tokensDir.deleteRecursively()
            }
            val authFile = getAuthStateFile()
            if (authFile.exists()) {
                authFile.delete()
            }
            println("[DesktopAuthManager] Token storage and auth state wiped successfully.")
        } catch (e: Exception) {
            System.err.println("[DesktopAuthManager] Error deleting disk cache: ${e.message}")
        }
    }
}
