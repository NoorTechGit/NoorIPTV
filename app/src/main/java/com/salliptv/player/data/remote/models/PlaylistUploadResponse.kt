package com.salliptv.player.data.remote.models

/**
 * Response from playlist upload
 */
data class PlaylistUploadResponse(
    val job_id: String,
    val status: String,  // "pending", "processing", "completed", "failed"
    val message: String? = null
)
