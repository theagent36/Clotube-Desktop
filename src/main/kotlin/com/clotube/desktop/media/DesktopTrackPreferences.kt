package com.clotube.desktop.media

import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.TrackDescription

private fun MediaPlayer.subtitles() = this.subpictures()

object DesktopTrackPreferences {
    var preferredAudioLanguage: String? = null
        private set
    var preferredSubtitleLanguage: String? = null
        private set
    var isSubtitleExplicitlyDisabled: Boolean = false
        private set

    /**
     * Extracts the primary language or keyword from track descriptions (e.g. "English [eng]", "Hindi - 5.1", "Japanese").
     */
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
        println("[DesktopTrackPreferences] Saved preferred audio language: $preferredAudioLanguage (from \"$description\")")
    }

    fun saveSubtitleTrack(description: String, isOff: Boolean) {
        if (isOff || description.isBlank() || description.equals("Disable", ignoreCase = true) || description.equals("Off", ignoreCase = true)) {
            isSubtitleExplicitlyDisabled = true
            preferredSubtitleLanguage = null
            println("[DesktopTrackPreferences] Saved preferred subtitles: OFF")
        } else {
            isSubtitleExplicitlyDisabled = false
            preferredSubtitleLanguage = extractLanguageToken(description)
            println("[DesktopTrackPreferences] Saved preferred subtitle language: $preferredSubtitleLanguage (from \"$description\")")
        }
    }

    /**
     * Applies saved audio and subtitle preferences to an active LibVLC player instance.
     */
    fun applyStickyPreferences(mediaPlayer: MediaPlayer) {
        try {
            // 1. Apply Subtitle Preference
            if (isSubtitleExplicitlyDisabled) {
                if (mediaPlayer.subtitles().track() != -1) {
                    mediaPlayer.subtitles().setTrack(-1)
                    println("[DesktopTrackPreferences] Enforced subtitle track: OFF (-1)")
                }
            } else if (!preferredSubtitleLanguage.isNullOrBlank()) {
                val subTracks: List<TrackDescription> = mediaPlayer.subtitles().trackDescriptions() ?: emptyList()
                val targetLang = preferredSubtitleLanguage!!.lowercase()

                val matched = subTracks.firstOrNull { track ->
                    track.id() != -1 && track.description().lowercase().contains(targetLang)
                }

                if (matched != null && mediaPlayer.subtitles().track() != matched.id()) {
                    mediaPlayer.subtitles().setTrack(matched.id())
                    println("[DesktopTrackPreferences] Auto-selected sticky subtitle track: ${matched.description()} (ID:${matched.id()})")
                }
            }

            // 2. Apply Audio Preference
            if (!preferredAudioLanguage.isNullOrBlank()) {
                val audioTracks: List<TrackDescription> = mediaPlayer.audio().trackDescriptions() ?: emptyList()
                val targetAudio = preferredAudioLanguage!!.lowercase()

                val matched = audioTracks.firstOrNull { track ->
                    track.id() != -1 && track.description().lowercase().contains(targetAudio)
                }

                if (matched != null && mediaPlayer.audio().track() != matched.id()) {
                    mediaPlayer.audio().setTrack(matched.id())
                    println("[DesktopTrackPreferences] Auto-selected sticky audio track: ${matched.description()} (ID:${matched.id()})")
                }
            }
        } catch (e: Exception) {
            System.err.println("[DesktopTrackPreferences] Error applying sticky track preferences: ${e.message}")
        }
    }
}
