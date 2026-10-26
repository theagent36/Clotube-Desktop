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
    val releaseYear: String = "",
    val ageRating: String = "",
    val genres: String = "Series",
    val tagline: String = "",
    val synopsis: String = "",
    val creators: String = "",
    val cast: String = "",
    val seasons: List<DesktopSeason> = emptyList()
)
