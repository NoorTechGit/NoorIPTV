package com.salliptv.player.repository

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import android.content.Context
import com.salliptv.player.PremiumManager
import com.salliptv.player.data.ChannelDao
import com.salliptv.player.data.remote.SallIPTVApiService
import com.salliptv.player.model.Channel
import com.salliptv.player.parser.M3uParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import okio.GzipSink
import okio.buffer
import okio.sink
import java.io.File
import java.io.InputStreamReader
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Playlist Manager - Gère l'ajout et le parsing des playlists
 *
 * Architecture:
 * 1. Essaie d'abord le backend (upload gzip, parsing serveur rapide)
 * 2. Télécharge le résultat JSON gzip depuis le backend et insère en base
 * 3. Fallback sur parsing local streaming si backend down ou échec
 *
 * IMPORTANT: Ne jamais charger un M3U ou JSON entier en RAM (OOM sur TV/Fire Stick)
 */
class PlaylistManager(
    private val context: Context,
    private val channelDao: ChannelDao
) {
    companion object {
        private const val TAG = "PlaylistManager"
        private const val POLL_INTERVAL_MS = 500L
        private const val MAX_POLL_ATTEMPTS = 120
        private const val MAX_DOWNLOAD_SIZE = 200L * 1024 * 1024 // 200MB max
        private const val BATCH_SIZE = 5000
    }

    private val apiService: SallIPTVApiService by lazy {
        SallIPTVApiService.create()
    }

    private val premiumManager: PremiumManager by lazy {
        PremiumManager(context)
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    sealed class State {
        object Idle : State()
        data class Downloading(val progress: Int = 10) : State()
        data class Uploading(val progress: Int) : State()
        data class Processing(val progress: Int, val message: String) : State()
        data class Saving(val count: Int) : State()
        data class Success(val totalChannels: Int) : State()
        data class Error(val message: String) : State()
    }

    fun addPlaylist(m3uUrl: String, playlistId: Int): Flow<State> = channelFlow {
        try {
            send(State.Processing(5, "Connecting…"))
            val backendAvailable = checkBackendHealth()

            if (backendAvailable) {
                Log.d(TAG, "Backend available")
                send(State.Downloading(10))
                val jobId = uploadToBackend(m3uUrl)

                if (jobId != null) {
                    // Poll for completion
                    send(State.Processing(40, "Server parsing…"))
                    val completed = pollForCompletion(jobId)

                    if (completed) {
                        // Download result and insert
                        send(State.Processing(60, "Downloading channels…"))
                        val count = downloadAndInsertResult(jobId, playlistId)
                        if (count > 0) {
                            send(State.Success(count))
                            return@channelFlow
                        }
                    }
                }
                Log.w(TAG, "Backend flow failed, falling back to local")
            }

            // Fallback: local streaming parse
            localStreamingParse(m3uUrl, playlistId, this)
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            send(State.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Gets or creates gzip from cache, uploads via multipart.
     * Cache: avoids re-downloading from IPTV provider (risk of ban).
     * Returns job_id or null on failure.
     */
    private suspend fun uploadToBackend(m3uUrl: String): String? {
        return try {
            // Use cached gzip if available (same URL)
            val cacheKey = m3uUrl.hashCode().toString(16)
            val cachedGzip = File(context.cacheDir, "playlist_$cacheKey.gz")

            val gzipFile: File
            if (cachedGzip.exists() && cachedGzip.length() > 0) {
                Log.d(TAG, "Using cached gzip: ${cachedGzip.length()} bytes")
                gzipFile = cachedGzip
            } else {
                // Download and gzip (first time only)
                val tempFile = downloadToTempFile(m3uUrl)
                if (tempFile == null) {
                    Log.e(TAG, "Download to temp file failed")
                    return null
                }
                Log.d(TAG, "Downloaded to temp: ${tempFile.length()} bytes")

                val newGzip = gzipToFile(tempFile)
                tempFile.delete()

                // Move to cache
                newGzip.copyTo(cachedGzip, overwrite = true)
                newGzip.delete()
                Log.d(TAG, "Cached gzip: ${cachedGzip.length()} bytes")
                gzipFile = cachedGzip
            }

            // Upload
            val deviceId = premiumManager.getDeviceId()
            val filePart = MultipartBody.Part.createFormData(
                "file", "playlist.m3u.gz",
                gzipFile.asRequestBody("application/gzip".toMediaType())
            )
            val typePart = "m3u".toRequestBody("text/plain".toMediaType())
            val idPart = deviceId.toRequestBody("text/plain".toMediaType())

            val response = apiService.uploadPlaylistFile(filePart, typePart, idPart)
            // Don't delete gzipFile — it's the cache

            if (!response.isSuccessful) {
                Log.e(TAG, "Upload failed: ${response.code()}")
                return null
            }

            val jobId = response.body()?.job_id
            Log.d(TAG, "Upload OK, job: $jobId")
            jobId
        } catch (e: Exception) {
            Log.e(TAG, "uploadToBackend error", e)
            null
        }
    }

    /**
     * Polls /playlist/status/{job_id} every 500ms, max 120 attempts.
     * Returns true when status is "completed".
     */
    private suspend fun pollForCompletion(jobId: String): Boolean {
        val deviceId = premiumManager.getDeviceId()
        var attempts = 0
        while (attempts < MAX_POLL_ATTEMPTS) {
            try {
                val statusResponse = apiService.getJobStatus(jobId, deviceId)
                if (statusResponse.isSuccessful) {
                    val status = statusResponse.body()
                    when (status?.status) {
                        "completed" -> {
                            Log.d(TAG, "Server parsing completed")
                            return true
                        }
                        "failed" -> {
                            Log.e(TAG, "Server parsing failed: ${status.error}")
                            return false
                        }
                        else -> {
                            val p = status?.progress ?: 0
                            Log.d(TAG, "Poll attempt $attempts: ${status?.status}, progress=$p")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll attempt $attempts failed: ${e.message}")
            }
            delay(POLL_INTERVAL_MS)
            attempts++
        }
        Log.e(TAG, "Polling timed out after $MAX_POLL_ATTEMPTS attempts")
        return false
    }

    /**
     * Downloads gzip JSON from backend, streams through GzipInputStream + JsonReader,
     * creates Channel objects, inserts in batches of 5000 into Room.
     * Returns total inserted count.
     */
    private suspend fun downloadAndInsertResult(jobId: String, playlistId: Int): Int {
        return try {
            val baseUrl = SallIPTVApiService.BASE_URL
            val url = "${baseUrl}playlist/result/$jobId"
            Log.d(TAG, "Downloading result from $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Result download failed: ${response.code}")
                return 0
            }

            val responseBody = response.body ?: run {
                Log.e(TAG, "Result response body is null")
                return 0
            }

            val reader = JsonReader(InputStreamReader(GZIPInputStream(responseBody.byteStream())))
            reader.beginArray()

            val batch = mutableListOf<Channel>()
            var count = 0

            while (reader.hasNext()) {
                reader.beginObject()
                val ch = Channel()
                ch.playlistId = playlistId
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (reader.peek() == JsonToken.NULL) {
                        reader.skipValue()
                    } else when (key) {
                        "name" -> ch.name = reader.nextString()
                        "clean_name" -> ch.cleanName = reader.nextString()
                        "logo" -> ch.logoUrl = reader.nextString()
                        "category" -> ch.groupTitle = reader.nextString()
                        "url" -> ch.streamUrl = reader.nextString()
                        "type" -> ch.type = reader.nextString()
                        "lang" -> ch.countryPrefix = reader.nextString()
                        "quality" -> ch.qualityBadge = reader.nextString()
                        "poster_hd" -> ch.posterUrl = reader.nextString()
                        "backdrop" -> ch.backdropUrl = reader.nextString()
                        "overview" -> ch.plot = reader.nextString()
                        "rating" -> ch.rating = reader.nextDouble().toString()
                        "year" -> ch.releaseDate = reader.nextInt().toString()
                        "genres" -> ch.genre = reader.nextString()
                        "cast" -> ch.cast = reader.nextString()
                        "season" -> ch.seasonNumber = reader.nextInt()
                        "episode" -> ch.episodeNumber = reader.nextInt()
                        "group_id" -> ch.groupId = reader.nextString()
                        "stream_id" -> ch.streamId = reader.nextInt()
                        "is_primary" -> { if (!reader.nextBoolean()) ch.hidden = true }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                batch.add(ch)
                count++

                if (batch.size >= BATCH_SIZE) {
                    channelDao.insertAll(batch.toList())
                    batch.clear()
                    Log.d(TAG, "Inserted $count channels...")
                }
            }

            reader.endArray()
            reader.close()

            if (batch.isNotEmpty()) {
                channelDao.insertAll(batch)
            }

            Log.d(TAG, "Inserted $count channels from backend result")
            count
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndInsertResult error", e)
            0
        }
    }

    /**
     * Local streaming parse - never loads full file in RAM
     * Uses M3uParser.parse() which reads line by line
     */
    private suspend fun localStreamingParse(
        url: String,
        playlistId: Int,
        emitter: ProducerScope<State>
    ) {
        emitter.send(State.Processing(20, "Parsing locally…"))
        var totalInserted = 0

        M3uParser.parse(
            m3uUrl = url,
            playlistId = playlistId,
            onProgress = { count ->
                totalInserted = count
            },
            onBatch = { batch ->
                kotlinx.coroutines.runBlocking { channelDao.insertAll(batch) }
                emitter.trySend(State.Saving(totalInserted))
            }
        )

        emitter.send(State.Success(totalInserted))
    }

    /**
     * Stream download to temp file - never loads in RAM
     */
    private fun downloadToTempFile(url: String): File? {
        return try {
            val tempFile = File.createTempFile("m3u_", ".tmp", context.cacheDir)
            val connection = URL(url).openConnection().apply {
                setRequestProperty("User-Agent", "VLC/3.0.18")
                connectTimeout = 30000
                readTimeout = 60000
            }

            connection.getInputStream().use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var totalBytes = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        if (totalBytes > MAX_DOWNLOAD_SIZE) {
                            Log.w(TAG, "File too large, aborting download at $totalBytes bytes")
                            tempFile.delete()
                            return null
                        }
                    }
                }
            }
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Download to temp failed", e)
            null
        }
    }

    /**
     * Gzip a file to another file in streaming mode (no RAM)
     */
    private fun gzipToFile(file: File): File {
        val gzFile = File.createTempFile("m3u_", ".gz", context.cacheDir)
        GzipSink(gzFile.sink()).buffer().use { sink ->
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    sink.write(buffer, 0, bytesRead)
                }
            }
        }
        return gzFile
    }

    private suspend fun checkBackendHealth(): Boolean {
        return try {
            val response = apiService.healthCheck()
            response.isSuccessful && response.body()?.status == "healthy"
        } catch (e: Exception) {
            Log.d(TAG, "Health check failed: ${e.message}")
            false
        }
    }
}
