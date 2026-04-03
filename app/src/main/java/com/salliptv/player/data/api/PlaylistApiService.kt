package com.salliptv.player.data.api

import com.salliptv.player.data.model.*
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit API service for SallIPTV backend
 */
interface PlaylistApiService {

    @GET("health")
    suspend fun checkHealth(): Response<HealthResponse>

    @POST("playlist/upload")
    suspend fun uploadPlaylist(
        @Header("X-Device-ID") deviceId: String,
        @Header("X-Content-Type") contentType: String, // "m3u" or "xtream"
        @Body content: RequestBody
    ): Response<PlaylistUploadResponse>

    @GET("playlist/status/{jobId}")
    suspend fun getPlaylistStatus(
        @Header("X-Device-ID") deviceId: String,
        @Path("jobId") jobId: String
    ): Response<PlaylistStatusResponse>

    @GET("playlist/test")
    suspend fun testEndpoint(): Response<Map<String, String>>
}
