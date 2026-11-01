package com.clotube.desktop.media

import com.clotube.desktop.data.DesktopAppSettings
import com.clotube.desktop.data.DesktopProfileManager
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.TrackDescription
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors

private fun MediaPlayer.subtitles() = this.subpictures()

object DesktopTrackPreferences {
    private val prefsFile by lazy {
        File(DesktopProfileManager.appDataRoot, "playback_preferences.properties")
    }
    private val ioExecutor = Executors.newSingleThreadExecutor()

    var stickyVolume: Int = 100
        private set

    var preferredAudioLanguage: String? = null
        private set
    var preferredSubtitleLanguage: String? = null
        private set
    var isSubtitleExplicitlyDisabled: Boolean = false
        private set

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        try {
            if (prefsFile.exists() && prefsFile.length() > 0) {
                val props = Properties()
                prefsFile.inputStream().use { props.load(it) }

                stickyVolume = props.getProperty("volume", "100").toIntOrNull()?.coerceIn(0, 100) ?: 100
                preferredAudioLanguage = props.getProperty("preferredAudioLanguage")
                preferredSubtitleLanguage = props.getProperty("preferredSubtitleLanguage")
                isSubtitleExplicitlyDisabled = props.getProperty("isSubtitleExplicitlyDisabled", "false").toBoolean()
            }
        } catch (_: Exception) {}

        // Fall back to general settings if user has not set specific track overrides
        val settings = DesktopAppSettings.state
        if (preferredAudioLanguage == null && settings.preferredAudioLanguage != "Original") {
            preferredAudioLanguage = settings.preferredAudioLanguage
        }
        if (preferredSubtitleLanguage == null && !isSubtitleExplicitlyDisabled) {
            when (settings.defaultSubtitleState) {
                "Off" -> isSubtitleExplicitlyDisabled = true
                "English" -> preferredSubtitleLanguage = "English"
                else -> isSubtitleExplicitlyDisabled = false
            }
        }
    }

    private fun savePreferencesAsync() {
        ioExecutor.submit {
            try {
                val props = Properties()
                props.setProperty("volume", stickyVolume.toString())
                preferredAudioLanguage?.let { props.setProperty("preferredAudioLanguage", it) }
                preferredSubtitleLanguage?.let { props.setProperty("preferredSubtitleLanguage", it) }
                props.setProperty("isSubtitleExplicitlyDisabled", isSubtitleExplicitlyDisabled.toString())

                prefsFile.outputStream().use { props.store(it, "Clotube Playback Preferences") }
            } catch (_: Exception) {}
        }
    }

    fun saveVolume(vol: Int) {
        stickyVolume = vol.coerceIn(0, 100)
        if (DesktopAppSettings.state.rememberVolume) {
            savePreferencesAsync()
        }
    }

    private fun extractLanguageToken(raw: String): String {
        val clean = raw.replace(Regex("[\\[\\]\\(\\)\\-]"), " ")
        val tokens = clean.split(" ").map { it.trim() }.filter { it.isNotBlank() }

        val commonLanguages = listOf(
            "english", "eng", "hindi", "hin", "japanese", "jpn", "spanish", "spa",
            "french", "fre", "fra", "german", "ger", "deu", "korean", "kor",
            "bengali", "ben", "tamil", "tam", "telugu", "tel", "russian", "rus"
        )

        for (token in tokens) {
            val lower = token.lowercase()
            for (lang in commonLanguages) {
                if (lower == lang || lower.startsWith(lang)) {
                    return when (lang) {
                        "eng", "english" -> "English"
                        "hin", "hindi" -> "Hindi"
                        "jpn", "japanese" -> "Japanese"
                        "spa", "spanish" -> "Spanish"
                        "fre", "fra", "french" -> "French"
                        "ger", "deu", "german" -> "German"
                        "kor", "korean" -> "Korean"
                        "ben", "bengali" -> "Bengali"
                        "tam", "tamil" -> "Tamil"
                        "tel", "telugu" -> "Telugu"
                        "rus", "russian" -> "Russian"
                        else -> token
                    }
                }
            }
        }
        return tokens.firstOrNull() ?: raw
    }

    fun saveAudioTrack(description: String) {
        if (description.isBlank() || description.equals("Disable", ignoreCase = true)) return
        preferredAudioLanguage = extractLanguageToken(description)
        savePreferencesAsync()
    }

    fun saveSubtitleTrack(description: String, isOff: Boolean) {
        if (isOff || description.isBlank() || description.equals("Disable", ignoreCase = true) || description.equals("Off", ignoreCase = true)) {
            isSubtitleExplicitlyDisabled = true
            preferredSubtitleLanguage = null
        } else {
            isSubtitleExplicitlyDisabled = false
            preferredSubtitleLanguage = extractLanguageToken(description)
        }
        savePreferencesAsync()
    }

    fun applyStickyPreferences(mediaPlayer: MediaPlayer) {
        try {
            // Apply volume
            val settings = DesktopAppSettings.state
            val targetVolume = if (settings.rememberVolume) stickyVolume else 100
            mediaPlayer.audio().setVolume(targetVolume)

            // Subtitle state check
            val shouldDisableSubtitles = isSubtitleExplicitlyDisabled || settings.defaultSubtitleState == "Off"
            if (shouldDisableSubtitles) {
                if (mediaPlayer.subtitles().track() != -1) {
                    mediaPlayer.subtitles().setTrack(-1)
                }
            } else {
                val targetSub = preferredSubtitleLanguage ?: if (settings.defaultSubtitleState != "Auto-Detect") settings.defaultSubtitleState else null
                if (!targetSub.isNullOrBlank()) {
                    val subTracks: List<TrackDescription> = mediaPlayer.subtitles().trackDescriptions() ?: emptyList()
                    val matched = subTracks.firstOrNull { it.id() != -1 && it.description().lowercase().contains(targetSub.lowercase()) }
                    if (matched != null && mediaPlayer.subtitles().track() != matched.id()) {
                        mediaPlayer.subtitles().setTrack(matched.id())
                    }
                }
            }

            // Audio track preference check
            val targetAudio = preferredAudioLanguage ?: if (settings.preferredAudioLanguage != "Original") settings.preferredAudioLanguage else null
            if (!targetAudio.isNullOrBlank()) {
                val audioTracks: List<TrackDescription> = mediaPlayer.audio().trackDescriptions() ?: emptyList()
                val matched = audioTracks.firstOrNull { it.id() != -1 && it.description().lowercase().contains(targetAudio.lowercase()) }
                if (matched != null && mediaPlayer.audio().track() != matched.id()) {
                    mediaPlayer.audio().setTrack(matched.id())
                }
            }
        } catch (_: Exception) {}
    }
}
