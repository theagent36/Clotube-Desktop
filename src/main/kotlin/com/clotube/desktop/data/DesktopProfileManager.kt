package com.clotube.desktop.data

import com.clotube.desktop.auth.DesktopAuthState
import com.google.gson.Gson
import java.io.File

data class SavedProfileInfo(
    val email: String,
    val displayName: String,
    val photoUrl: String? = null,
    val hasCredentials: Boolean = false,
    val hasTokens: Boolean = false
)

object DesktopProfileManager {
    private val gson = Gson()

    val appDataRoot: File by lazy {
        val appData = System.getenv("APPDATA")
        val base = if (!appData.isNullOrBlank()) {
            File(appData, "Clotube")
        } else {
            File(System.getProperty("user.home"), ".clotube")
        }
        if (!base.exists()) base.mkdirs()
        base
    }

    val profilesRoot: File by lazy {
        val dir = File(appDataRoot, "profiles")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private val activeUserFile: File by lazy {
        File(appDataRoot, "active_user.json")
    }

    init {
        migrateLegacyRootFiles()
    }

    fun sanitizeEmail(email: String): String {
        return email.lowercase().trim().replace(Regex("[^a-z0-9]"), "_")
    }

    fun getProfileDir(email: String): File {
        val sanitized = sanitizeEmail(email).ifBlank { "default_user" }
        val dir = File(profilesRoot, sanitized)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // Account-isolated credentials and session state
    fun getClientSecretsFile(email: String): File = File(getProfileDir(email), "client_secrets.json")
    fun getAuthStateFile(email: String): File = File(getProfileDir(email), "auth_state.json")
    fun getTokensDir(email: String): File {
        val dir = File(getProfileDir(email), "tokens")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // Account-isolated media and cache paths
    fun getLibraryFile(email: String): File = File(getProfileDir(email), "library.json")
    fun getWatchHistoryFile(email: String): File = File(getProfileDir(email), "watch_history.json")
    fun getSeriesDir(email: String): File {
        val dir = File(getProfileDir(email), "series")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    fun getThumbnailsDir(email: String): File {
        val dir = File(getProfileDir(email), "thumbnails")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // Staging paths used prior to discovering the OAuth email
    fun getStagedClientSecretsFile(): File = File(appDataRoot, "staged_client_secrets.json")
    fun getStagedTokensDir(): File {
        val dir = File(appDataRoot, "staged_tokens")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private data class ActiveUserState(val activeEmail: String? = null)

    fun getActiveEmail(): String? {
        return try {
            if (activeUserFile.exists() && activeUserFile.length() > 0) {
                val json = activeUserFile.readText(Charsets.UTF_8)
                val state = gson.fromJson(json, ActiveUserState::class.java)
                state.activeEmail?.trim()?.lowercase()?.ifBlank { null }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun setActiveEmail(email: String?) {
        try {
            if (email.isNullOrBlank()) {
                if (activeUserFile.exists()) activeUserFile.delete()
            } else {
                val json = gson.toJson(ActiveUserState(email.trim().lowercase()))
                activeUserFile.writeText(json, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            System.err.println("[DesktopProfileManager] Error writing active email: ${e.message}")
        }
    }

    fun listSavedProfiles(): List<SavedProfileInfo> {
        if (!profilesRoot.exists()) return emptyList()

        return profilesRoot.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val authFile = File(dir, "auth_state.json")
            val secretsFile = File(dir, "client_secrets.json")
            val tokensDir = File(dir, "tokens")

            if (authFile.exists() && authFile.length() > 0) {
                try {
                    val state = gson.fromJson(authFile.readText(Charsets.UTF_8), DesktopAuthState::class.java)
                    if (state != null && state.userEmail.isNotBlank()) {
                        SavedProfileInfo(
                            email = state.userEmail,
                            displayName = state.userName.ifBlank { state.userEmail.substringBefore('@') },
                            photoUrl = state.userPhotoUrl,
                            hasCredentials = secretsFile.exists() && secretsFile.length() > 0,
                            hasTokens = tokensDir.exists() && (tokensDir.listFiles()?.isNotEmpty() == true)
                        )
                    } else null
                } catch (_: Exception) {
                    null
                }
            } else null
        } ?: emptyList()
    }

    fun deleteProfile(email: String) {
        if (email.isBlank()) return
        try {
            val dir = getProfileDir(email)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            if (getActiveEmail().equals(email.trim(), ignoreCase = true)) {
                setActiveEmail(null)
            }
            println("[DesktopProfileManager] Deleted profile directory for: $email")
        } catch (e: Exception) {
            System.err.println("[DesktopProfileManager] Error deleting profile $email: ${e.message}")
        }
    }

    /**
     * Automatically migrates legacy root credentials and normalizes folder names
     */
    private fun migrateLegacyRootFiles() {
        try {
            // 1. Normalize any legacy folder names containing dots (e.g. name_gmail.com -> name_gmail_com)
            profilesRoot.listFiles()?.filter { it.isDirectory && it.name.contains(".") }?.forEach { oldDir ->
                val newName = oldDir.name.replace(Regex("[^a-z0-9]"), "_")
                val targetDir = File(profilesRoot, newName)
                if (!targetDir.exists()) {
                    oldDir.renameTo(targetDir)
                } else {
                    oldDir.listFiles()?.forEach { file ->
                        val dest = File(targetDir, file.name)
                        if (!dest.exists()) {
                            file.copyRecursively(dest, overwrite = false)
                        }
                    }
                    oldDir.deleteRecursively()
                }
                println("[DesktopProfileManager] Merged legacy dotted directory ${oldDir.name} -> $newName")
            }

            // 2. Migrate any legacy root credentials into user profile
            val legacyAuthFile = File(appDataRoot, "auth_state.json")
            if (legacyAuthFile.exists() && legacyAuthFile.length() > 0) {
                val json = legacyAuthFile.readText(Charsets.UTF_8)
                val state = gson.fromJson(json, DesktopAuthState::class.java)
                val email = state?.userEmail?.trim()?.lowercase()
                if (!email.isNullOrBlank()) {
                    val profileDir = getProfileDir(email)
                    val targetAuth = File(profileDir, "auth_state.json")
                    if (!targetAuth.exists()) {
                        legacyAuthFile.copyTo(targetAuth, overwrite = true)
                    }
                    legacyAuthFile.delete()

                    val legacyTokens = File(appDataRoot, "tokens")
                    if (legacyTokens.exists()) {
                        val targetTokens = File(profileDir, "tokens")
                        if (!targetTokens.exists()) targetTokens.mkdirs()
                        legacyTokens.listFiles()?.forEach { tokenFile ->
                            tokenFile.copyTo(File(targetTokens, tokenFile.name), overwrite = true)
                        }
                        legacyTokens.deleteRecursively()
                    }

                    val legacySecrets = File(appDataRoot, "client_secrets.json")
                    if (legacySecrets.exists()) {
                        val targetSecrets = File(profileDir, "client_secrets.json")
                        if (!targetSecrets.exists()) {
                            legacySecrets.copyTo(targetSecrets, overwrite = true)
                        }
                        legacySecrets.delete()
                    }

                    setActiveEmail(email)
                    println("[DesktopProfileManager] Migrated root credentials into: $email")
                }
            } else {
                // Check if a standalone root client_secrets.json exists without auth_state.json
                val legacySecrets = File(appDataRoot, "client_secrets.json")
                if (legacySecrets.exists() && legacySecrets.length() > 0) {
                    val stagedSecrets = getStagedClientSecretsFile()
                    if (!stagedSecrets.exists() || stagedSecrets.length() == 0L) {
                        legacySecrets.copyTo(stagedSecrets, overwrite = true)
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[DesktopProfileManager] Legacy migration note: ${e.message}")
        }
    }
}
