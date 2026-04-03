package com.salliptv.player.data.remote.models

/**
 * Request body for playlist upload
 */
data class PlaylistUploadRequest(
    val device_id: String,
    val content_type: String,  // "m3u" or "xtream"
    val raw_content: String    // base64 encoded gzip content
)
