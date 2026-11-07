package com.clotube.desktop.auth

import com.clotube.desktop.data.DesktopProfileManager
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
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

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
        "https://www.googleapis.com/auth/drive",
        "openid",
        "email",
        "profile"
    )

    private val flowMap = ConcurrentHashMap<String, GoogleAuthorizationCodeFlow>()
    private var cachedAuthState: DesktopAuthState? = null

    fun getClotubeDataDir(): File = DesktopProfileManager.appDataRoot
    fun getTokensDirectory(): File = File(getClotubeDataDir(), "tokens")
    fun getAuthStateFile(): File = File(getClotubeDataDir(), "auth_state.json")

    fun getClientSecretsFile(emailHint: String? = null): File {
        if (!emailHint.isNullOrBlank()) {
            val f = DesktopProfileManager.getClientSecretsFile(emailHint)
            if (f.exists() && f.length() > 0) return f
        }
        val activeEmail = DesktopProfileManager.getActiveEmail()
        if (!activeEmail.isNullOrBlank()) {
            val f = DesktopProfileManager.getClientSecretsFile(activeEmail)
            if (f.exists() && f.length() > 0) return f
        }
        val staged = DesktopProfileManager.getStagedClientSecretsFile()
        if (staged.exists() && staged.length() > 0) return staged

        val rootLegacy = File(getClotubeDataDir(), "client_secrets.json")
        if (rootLegacy.exists() && rootLegacy.length() > 0) return rootLegacy

        return staged
    }

    fun getClientSecretsPath(emailHint: String? = null): String = getClientSecretsFile(emailHint).absolutePath

    fun hasClientSecrets(emailHint: String? = null): Boolean {
        if (!emailHint.isNullOrBlank()) {
            val f = DesktopProfileManager.getClientSecretsFile(emailHint)
            if (f.exists() && f.length() > 0) return true
        }
        val activeEmail = DesktopProfileManager.getActiveEmail()
        if (!activeEmail.isNullOrBlank()) {
            val f = DesktopProfileManager.getClientSecretsFile(activeEmail)
            if (f.exists() && f.length() > 0) return true
        }
        val staged = DesktopProfileManager.getStagedClientSecretsFile()
        if (staged.exists() && staged.length() > 0) return true

        // Check if any profile has client_secrets.json
        val anyProfileSecrets = DesktopProfileManager.profilesRoot.listFiles()?.any { dir ->
            dir.isDirectory && File(dir, "client_secrets.json").let { it.exists() && it.length() > 0 }
        } ?: false
        if (anyProfileSecrets) return true

        val rootLegacy = File(getClotubeDataDir(), "client_secrets.json")
        if (rootLegacy.exists() && rootLegacy.length() > 0) return true

        return false
    }

    fun getMaskedClientId(emailHint: String? = null): String? {
        return try {
            val providers = mutableListOf<() -> InputStream?>()

            if (!emailHint.isNullOrBlank()) {
                providers.add {
                    val f = DesktopProfileManager.getClientSecretsFile(emailHint)
                    if (f.exists() && f.length() > 0) f.inputStream() else null
                }
            }

            val activeEmail = DesktopProfileManager.getActiveEmail()
            if (!activeEmail.isNullOrBlank()) {
                providers.add {
                    val f = DesktopProfileManager.getClientSecretsFile(activeEmail)
                    if (f.exists() && f.length() > 0) f.inputStream() else null
                }
            }

            providers.add {
                val f = DesktopProfileManager.getStagedClientSecretsFile()
                if (f.exists() && f.length() > 0) f.inputStream() else null
            }

            providers.add {
                val f = File(getClotubeDataDir(), "client_secrets.json")
                if (f.exists() && f.length() > 0) f.inputStream() else null
            }

            for (provider in providers) {
                try {
                    val stream = provider()
                    if (stream != null) {
                        val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val json = JsonParser.parseString(content).asJsonObject
                        val clientBlock = json.getAsJsonObject("installed") ?: json.getAsJsonObject("web")
                        val clientId = clientBlock?.get("client_id")?.asString
                        if (!clientId.isNullOrBlank()) {
                            return if (clientId.length > 8) "..." + clientId.takeLast(8) else clientId
                        }
                    }
                } catch (_: Throwable) {}
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun saveClientSecrets(sourceFile: File, emailHint: String? = null): Result<Unit> {
        return try {
            if (!sourceFile.exists()) {
                return Result.failure(IllegalArgumentException("Selected credentials file does not exist."))
            }
            val content = sourceFile.readText(Charsets.UTF_8)
            val json = JsonParser.parseString(content).asJsonObject
            val clientBlock = json.getAsJsonObject("installed") ?: json.getAsJsonObject("web")
                ?: return Result.failure(IllegalArgumentException("Invalid JSON: Missing 'installed' or 'web' root block."))

            val clientId = clientBlock.get("client_id")?.asString
            val clientSecret = clientBlock.get("client_secret")?.asString

            if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("Invalid credentials: 'client_id' or 'client_secret' is empty."))
            }

            // Always write to staged file for onboarding
            val stagedFile = DesktopProfileManager.getStagedClientSecretsFile()
            stagedFile.writeText(content, Charsets.UTF_8)

            // If an email hint was provided, write directly into that profile
            if (!emailHint.isNullOrBlank()) {
                val targetFile = DesktopProfileManager.getClientSecretsFile(emailHint)
                targetFile.parentFile?.mkdirs()
                targetFile.writeText(content, Charsets.UTF_8)
                flowMap.remove(emailHint.lowercase().trim())
            }

            flowMap.remove("staged")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun loadClientSecrets(email: String?): GoogleClientSecrets {
        val providers = mutableListOf<() -> InputStream?>()

        if (!email.isNullOrBlank()) {
            providers.add {
                val f = DesktopProfileManager.getClientSecretsFile(email)
                if (f.exists() && f.length() > 0) f.inputStream() else null
            }
        }

        providers.add {
            val f = DesktopProfileManager.getStagedClientSecretsFile()
            if (f.exists() && f.length() > 0) f.inputStream() else null
        }

        providers.add {
            val f = File(getClotubeDataDir(), "client_secrets.json")
            if (f.exists() && f.length() > 0) f.inputStream() else null
        }

        for (provider in providers) {
            try {
                val stream = provider()
                if (stream != null) {
                    InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                        return GoogleClientSecrets.load(JSON_FACTORY, reader)
                    }
                }
            } catch (_: Throwable) {}
        }

        throw IllegalStateException("Unable to locate 'client_secrets.json'. Please complete the Google Cloud setup.")
    }

    @Synchronized
    private fun getOrCreateFlow(email: String?, forceRefresh: Boolean = false): GoogleAuthorizationCodeFlow {
        val key = email?.lowercase()?.trim() ?: "staged"
        if (forceRefresh || !flowMap.containsKey(key)) {
            val tokensDir = if (!email.isNullOrBlank()) {
                DesktopProfileManager.getTokensDir(email)
            } else {
                DesktopProfileManager.getStagedTokensDir()
            }
            if (!tokensDir.exists()) tokensDir.mkdirs()

            val dataStoreFactory = FileDataStoreFactory(tokensDir)
            val clientSecrets = loadClientSecrets(email)

            val flow = GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT,
                JSON_FACTORY,
                clientSecrets,
                SCOPES
            )
                .setDataStoreFactory(dataStoreFactory)
                .setAccessType("offline")
                .build()

            flowMap[key] = flow
        }
        return flowMap[key]!!
    }

    @Synchronized
    fun getCredential(email: String? = null): Credential? {
        val targetEmail = email?.ifBlank { null } ?: DesktopProfileManager.getActiveEmail() ?: return null
        return try {
            val flow = getOrCreateFlow(targetEmail)
            val credential = flow.loadCredential("user") ?: return null

            val expiresIn = credential.expiresInSeconds
            if (expiresIn == null || expiresIn <= 300) {
                val refreshed = credential.refreshToken()
                if (refreshed && credential.accessToken != null) {
                    val current = cachedAuthState
                    if (current != null && current.isSignedIn && current.userEmail.equals(targetEmail, ignoreCase = true)) {
                        val updated = current.copy(accessToken = credential.accessToken)
                        cachedAuthState = updated
                        DesktopProfileManager.getAuthStateFile(targetEmail).writeText(gson.toJson(updated), Charsets.UTF_8)
                    }
                }
            }
            credential
        } catch (e: Exception) {
            System.err.println("[DesktopAuthManager] Error refreshing credential for $targetEmail: ${e.message}")
            null
        }
    }

    fun getSavedAuthState(): DesktopAuthState {
        cachedAuthState?.let { return it }

        val activeEmail = DesktopProfileManager.getActiveEmail()
        if (activeEmail.isNullOrBlank()) {
            val unauth = DesktopAuthState(isSignedIn = false)
            cachedAuthState = unauth
            return unauth
        }

        val authFile = DesktopProfileManager.getAuthStateFile(activeEmail)
        if (!authFile.exists() || authFile.length() == 0L) {
            val unauth = DesktopAuthState(isSignedIn = false)
            cachedAuthState = unauth
            return unauth
        }

        return try {
            val json = authFile.readText(Charsets.UTF_8)
            val state = gson.fromJson(json, DesktopAuthState::class.java)
            if (state != null && state.isSignedIn && state.accessToken.isNotBlank()) {
                val credential = getCredential(activeEmail)
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
        } catch (_: Exception) {
            DesktopAuthState(isSignedIn = false)
        }
    }

    suspend fun startDesktopOAuthFlow(expectedEmail: String? = null): DesktopAuthState = withContext(Dispatchers.IO) {
        try {
            val flow = getOrCreateFlow(expectedEmail, forceRefresh = true)
            val receiver = LocalServerReceiver.Builder().setPort(-1).build()

            val installedApp = object : AuthorizationCodeInstalledApp(flow, receiver) {
                override fun onAuthorization(authorizationUrl: AuthorizationCodeRequestUrl) {
                    authorizationUrl.set("prompt", "select_account")
                    super.onAuthorization(authorizationUrl)
                }
            }

            val credential: Credential = installedApp.authorize("user")

            val oauth2 = Oauth2.Builder(
                HTTP_TRANSPORT,
                JSON_FACTORY,
                credential
            ).setApplicationName("Clotube Desktop").build()

            val userInfo: Userinfo = oauth2.userinfo().get().execute()
            val email = userInfo.email?.trim()?.lowercase() ?: ""
            val name = userInfo.name ?: userInfo.givenName ?: email.substringBefore('@')
            val photoUrl = userInfo.picture
            val token = credential.accessToken ?: ""

            if (email.isBlank()) {
                throw IllegalStateException("Authenticated user email is missing from Google userinfo.")
            }

            // 1. Move/save client_secrets.json directly into this user's profile
            val stagedSecrets = DesktopProfileManager.getStagedClientSecretsFile()
            val targetSecrets = DesktopProfileManager.getClientSecretsFile(email)
            if (stagedSecrets.exists() && stagedSecrets.length() > 0) {
                stagedSecrets.copyTo(targetSecrets, overwrite = true)
            }

            // 2. Move tokens from staging directory into profile tokens if necessary
            val profileTokensDir = DesktopProfileManager.getTokensDir(email)
            if (expectedEmail.isNullOrBlank()) {
                val stagedTokens = DesktopProfileManager.getStagedTokensDir()
                stagedTokens.listFiles()?.forEach { file ->
                    file.copyTo(File(profileTokensDir, file.name), overwrite = true)
                }
                stagedTokens.deleteRecursively()
            }

            // 3. Persist auth state into user's profile
            val state = DesktopAuthState(
                isSignedIn = true,
                accessToken = token,
                userEmail = email,
                userName = name,
                userPhotoUrl = photoUrl
            )
            DesktopProfileManager.getAuthStateFile(email).writeText(gson.toJson(state), Charsets.UTF_8)
            DesktopProfileManager.setActiveEmail(email)

            cachedAuthState = state
            flowMap.remove("staged")
            println("[DesktopAuthManager] Successfully registered & scoped credentials for profile: $email")
            state
        } catch (e: Exception) {
            System.err.println("[DesktopAuthManager] OAuth flow failed: ${e.message}")
            DesktopAuthState(isSignedIn = false)
        }
    }

    /**
     * Instantly switches the active session to a saved profile without re-authenticating.
     */
    fun switchProfile(email: String): DesktopAuthState {
        val authFile = DesktopProfileManager.getAuthStateFile(email)
        if (!authFile.exists() || authFile.length() == 0L) {
            return DesktopAuthState(isSignedIn = false)
        }

        return try {
            val json = authFile.readText(Charsets.UTF_8)
            val state = gson.fromJson(json, DesktopAuthState::class.java)
            DesktopProfileManager.setActiveEmail(email)

            val cred = getCredential(email)
            val activeState = if (cred != null && cred.accessToken != null) {
                state.copy(accessToken = cred.accessToken, isSignedIn = true)
            } else {
                state.copy(isSignedIn = true)
            }

            cachedAuthState = activeState
            println("[DesktopAuthManager] Switched active profile to: $email")
            activeState
        } catch (e: Exception) {
            System.err.println("[DesktopAuthManager] Failed to switch profile to $email: ${e.message}")
            DesktopAuthState(isSignedIn = false)
        }
    }

    /**
     * Signs out of the current active session without destroying stored offline credentials.
     */
    fun signOut() {
        val activeEmail = DesktopProfileManager.getActiveEmail()
        println("[DesktopAuthManager] Signing out active session: $activeEmail")

        if (!activeEmail.isNullOrBlank()) {
            flowMap[activeEmail]?.credentialDataStore?.clear()
            flowMap.remove(activeEmail)
        }
        flowMap.clear()

        DesktopProfileManager.setActiveEmail(null)
        cachedAuthState = DesktopAuthState(isSignedIn = false)
    }
}
