package com.clotube.desktop.data

import com.google.gson.annotations.SerializedName

data class HistoryItemEntityV2(
    @SerializedName("id") val id: String,
    @SerializedName("t") val title: String = "",
    @SerializedName("p") val positionMs: Long = 0L,
    @SerializedName("d") val durationMs: Long = 0L,
    @SerializedName("ts") val timestamp: Long = 0L,
    @SerializedName("w") val isWatched: Boolean = false,
    @SerializedName("sid") val seriesId: String? = null,
    @SerializedName("s") val seasonNumber: Int? = null,
    @SerializedName("e") val episodeNumber: Int? = null
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
}

data class SeriesResumeEntityV2(
    @SerializedName("id") val episodeId: String,
    @SerializedName("s") val seasonNumber: Int = 1,
    @SerializedName("e") val episodeNumber: Int = 1,
    @SerializedName("p") val positionMs: Long = 0L,
    @SerializedName("d") val durationMs: Long = 0L,
    @SerializedName("ts") val timestamp: Long = 0L
)

data class WatchHistoryPayloadV2(
    @SerializedName("v") val version: Int = 2,
    @SerializedName("updatedAt") val updatedAt: Long = System.currentTimeMillis(),
    @SerializedName("active") val active: List<HistoryItemEntityV2> = emptyList(),
    @SerializedName("resume") val resume: Map<String, SeriesResumeEntityV2> = emptyMap(),
    @SerializedName("watchedIds") val watchedIds: List<String> = emptyList()
)

