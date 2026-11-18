package com.clotube.desktop.model

enum class SearchMediaType {
    MOVIE,
    TV_SERIES,
    EPISODE
}

data class GlobalSearchResult(
    val id: String,
    val title: String,
    val originalTitle: String = "",
    val mediaType: SearchMediaType,
    val isInDrive: Boolean,
    val year: String = "",
    val overview: String = "",
    val rating: Double = 0.0,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val genre: String = "",
    val castSubtitle: String = "",
    val imdbId: String = "",
    val driveMediaItem: DesktopMediaItem? = null,
    val parentSeriesItem: DesktopMediaItem? = null,
    val episodeId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val externalSource: String = "",
    val externalId: String = "",
    val relevanceScore: Int = 0
)
