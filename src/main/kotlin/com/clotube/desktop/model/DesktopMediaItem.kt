package com.clotube.desktop.model

data class DesktopMediaItem(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val genre: String = "Movie • Cinema",
    val durationMs: Long = 0L,
    val progressMs: Long = 0L
)
