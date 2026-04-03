package com.salliptv.player.data.remote.models

/**
 * Response from job status polling
 */
data class JobStatusResponse(
    val status: String,  // "pending", "processing", "completed", "failed"
    val progress: Int,   // 0-100
    val result: ParsedPlaylistResult? = null,
    val error: String? = null
)

data class ParsedPlaylistResult(
    val channels: List<ParsedChannel> = emptyList(),
    val vod: List<ParsedChannel> = emptyList(),
    val series: List<ParsedChannel> = emptyList(),
    val total_count: Int = 0,
    val categories: List<String> = emptyList()
)

data class ParsedChannel(
    val id: String,
    val name: String,
    val tmdb_id: Int? = null,
    val poster_url: String? = null,
    val category: String? = null,
    val stream_url: String? = null  // Will be null from backend (we keep local)
)
