package com.clotube.desktop.model

data class DesktopMediaItem(
    val id: String,
    val title: String,
    val durationMs: Long = 0L,
    val progressMs: Long = 0L,
    val genre: String = "Action • Drama",
    val thumbnailUrl: String? = null,
    val fallbackThumbnailUrl: String? = null,
    val streamUrl: String = "",
    val language: String = ""
)
