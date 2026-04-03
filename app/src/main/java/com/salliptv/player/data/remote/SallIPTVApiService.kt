package com.salliptv.player.data.remote

import com.salliptv.player.data.remote.models.PlaylistUploadRequest
import com.salliptv.player.data.remote.models.PlaylistUploadResponse
import com.salliptv.player.data.remote.models.JobStatusResponse
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

interface SallIPTVApiService {

    @GET("health")
    suspend fun healthCheck(): Response<HealthResponse>

    @POST("playlist/upload")
    suspend fun uploadPlaylist(
        @Header("X-Device-ID") deviceId: String,
        @Body request: PlaylistUploadRequest
    ): Response<PlaylistUploadResponse>

    @Multipart
    @POST("playlist/upload/file")
    suspend fun uploadPlaylistFile(
        @Part file: MultipartBody.Part,
        @Part("content_type") contentType: RequestBody,
        @Part("device_id") deviceId: RequestBody
    ): Response<PlaylistUploadResponse>

    @GET("playlist/status/{job_id}")
    suspend fun getJobStatus(
        @Path("job_id") jobId: String,
        @Header("X-Device-ID") deviceId: String
    ): Response<JobStatusResponse>

    data class HealthResponse(
        val status: String,
        val service: String? = null,
        val version: String? = null
    )

    companion object {
        const val BASE_URL = "https://salliptv.com/api/"

        fun create(): SallIPTVApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            
            val client = OkHttpClient.Builder()
                .addInterceptor(GzipRequestInterceptor())
                .addInterceptor(logging)
                .build()
                
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SallIPTVApiService::class.java)
        }
    }
}
