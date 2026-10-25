package com.clotube.desktop.model

data class DesktopEpisode(
    val id: String,
    val seriesId: String,
    val seriesTitle: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val durationMs: Long = 0L,
    val durationFormatted: String = "",
    val thumbnailUrl: String = "",
    val streamUrl: String = "",
    val synopsis: String = "",
    val isWatched: Boolean = false,
    val progressFraction: Float = 0f
)

data class DesktopSeason(
    val seasonNumber: Int,
    val title: String,
    val episodes: List<DesktopEpisode>
)

data class DesktopSeries(
    val id: String,
    val title: String,
    val backdropUrl: String = "",
    val posterUrl: String = "",
    val releaseYear: String = "2025",
    val ageRating: String = "16+",
    val genres: String = "Adventure • Drama",
    val tagline: String = "Beyond the last road, survival becomes a choice.",
    val synopsis: String = "Streamed directly from your private Google Drive series collection in uncompressed multi-channel audio and video.",
    val creators: String = "Original Production",
    val cast: String = "Cast Ensemble",
    val seasons: List<DesktopSeason> = emptyList()
)
