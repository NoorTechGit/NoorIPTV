package com.salliptv.player.data.model

import com.google.gson.annotations.SerializedName

/**
 * Models for API communication with backend
 */

data class PlaylistUploadResponse(
    @SerializedName("job_id")
    val jobId: String,
    @SerializedName("status")
    val status: String,
    @SerializedName("estimated_seconds")
    val estimatedSeconds: Int
)

data class PlaylistStatusResponse(
    @SerializedName("job_id")
    val jobId: String,
    @SerializedName("status")
    val status: String,
    @SerializedName("progress")
    val progress: Int? = null,
    @SerializedName("result")
    val result: PlaylistResult? = null,
    @SerializedName("error")
    val error: String? = null
)

data class PlaylistResult(
    @SerializedName("fingerprint")
    val fingerprint: String,
    @SerializedName("channels")
    val channels: List<ChannelData>,
    @SerializedName("vod")
    val vod: List<VodData>,
    @SerializedName("series")
    val series: List<SeriesData>,
    @SerializedName("total_channels")
    val totalChannels: Int,
    @SerializedName("total_vod")
    val totalVod: Int,
    @SerializedName("total_series")
    val totalSeries: Int
)

data class ChannelData(
    @SerializedName("id")
    val id: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("logo")
    val logo: String? = null,
    @SerializedName("category")
    val category: String? = null,
    @SerializedName("tmdb_id")
    val tmdbId: Int? = null,
    @SerializedName("stream_url_encrypted")
    val streamUrlEncrypted: String
)

data class VodData(
    @SerializedName("name")
    val name: String,
    @SerializedName("stream_url_encrypted")
    val streamUrlEncrypted: String
)

data class SeriesData(
    @SerializedName("name")
    val name: String,
    @SerializedName("stream_url_encrypted")
    val streamUrlEncrypted: String
)

data class HealthResponse(
    @SerializedName("status")
    val status: String,
    @SerializedName("service")
    val service: String,
    @SerializedName("version")
    val version: String
)
