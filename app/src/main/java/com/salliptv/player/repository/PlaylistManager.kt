package com.salliptv.player.repository

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import android.content.Context
import com.google.gson.Gson
import com.salliptv.player.PremiumManager
import com.salliptv.player.data.ChannelDao
import com.salliptv.player.data.remote.SallIPTVApiService
import com.salliptv.player.model.Channel
import com.salliptv.player.parser.M3uParser
import com.salliptv.player.parser.XtreamApi
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
import kotlinx.coroutines.withContext
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
 * 1. Détecte si l'URL M3U est une URL Xtream (contient username= et password=)
 * 2. Si Xtream: utilise XtreamApi pour récupérer live/vod/series déjà groupés,
 *    sérialise en JSON gzip et upload au backend pour enrichissement
 * 3. Sinon (M3U pur): télécharge le M3U, gzip, upload au backend
 * 4. Dans les deux cas: fallback insertion locale si backend indisponible
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

    // ==========================================
    // Xtream detection
    // ==========================================

    private data class XtreamInfo(val server: String, val username: String, val password: String)

    private fun detectXtream(url: String): XtreamInfo? {
        val userMatch = Regex("[?&]username=([^&]+)").find(url) ?: return null
        val passMatch = Regex("[?&]password=([^&]+)").find(url) ?: return null
        val server = url.substringBefore("/get.php").ifEmpty {
            url.substringBefore("/player_api.php")
        }.ifEmpty { return null }
        return XtreamInfo(server, userMatch.groupValues[1], passMatch.groupValues[1])
    }

    // ==========================================
    // Entry point
    // ==========================================

    fun addPlaylist(m3uUrl: String, playlistId: Int): Flow<State> = channelFlow {
        try {
            // Detect Xtream server from M3U URL
            val xtreamInfo = detectXtream(m3uUrl)

            if (xtreamInfo != null) {
                // Use Xtream API — returns grouped series, better data
                Log.d(TAG, "Xtream server detected: ${xtreamInfo.server}")
                xtreamFlow(xtreamInfo, playlistId, this)
            } else {
                // Fallback: M3U flow
                Log.d(TAG, "Standard M3U URL, using M3U flow")
                m3uFlow(m3uUrl, playlistId, this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            send(State.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    // ==========================================
    // Xtream flow
    // ==========================================

    private suspend fun xtreamFlow(
        info: XtreamInfo,
        playlistId: Int,
        scope: ProducerScope<State>
    ) {
        val api = XtreamApi(info.server, info.username, info.password)

        // Step 1: Login to verify credentials
        scope.send(State.Processing(5, "Connecting to server…"))
        val loginResult = withContext(Dispatchers.IO) { api.login() }
        if (loginResult == null) {
            scope.send(State.Error("Login failed — check server URL, username and password"))
            return
        }

        // Step 2: Fetch all data via Xtream API (series come GROUPED, not individual episodes)
        scope.send(State.Downloading(10))

        // Live streams
        scope.send(State.Processing(15, "Loading live channels…"))
        val live = withContext(Dispatchers.IO) { api.getAllLiveStreams(playlistId) }
        scope.send(State.Processing(30, "${live.size} live channels loaded"))

        // VOD
        scope.send(State.Processing(35, "Loading movies…"))
        val vod = withContext(Dispatchers.IO) { api.getAllVodStreams(playlistId) }
        scope.send(State.Processing(50, "${vod.size} movies loaded"))

        // Series (grouped by provider — not individual episodes)
        scope.send(State.Processing(55, "Loading series…"))
        val series = withContext(Dispatchers.IO) { api.getAllSeries(playlistId) }
        scope.send(State.Processing(65, "${series.size} series loaded"))

        // Step 3: Try backend enrichment
        scope.send(State.Uploading(70))
        val backendAvailable = checkBackendHealth()

        if (backendAvailable) {
            Log.d(TAG, "Backend available — serialising Xtream data as JSON")

            // Build a list of plain maps that the backend understands
            val channels = mutableListOf<Map<String, Any?>>()

            for (ch in live) {
                channels.add(mapOf(
                    "name" to ch.name,
                    "logo" to ch.logoUrl,
                    "category" to ch.groupTitle,
                    "url" to ch.streamUrl,
                    "type" to "LIVE",
                    "stream_id" to ch.streamId
                ))
            }
            for (ch in vod) {
                channels.add(mapOf(
                    "name" to ch.name,
                    "logo" to ch.logoUrl,
                    "category" to ch.groupTitle,
                    "url" to ch.streamUrl,
                    "type" to "VOD",
                    "stream_id" to ch.streamId,
                    "plot" to ch.plot,
                    "cast" to ch.cast,
                    "rating" to ch.rating,
                    "poster" to ch.posterUrl,
                    "backdrop" to ch.backdropUrl
                ))
            }
            for (ch in series) {
                channels.add(mapOf(
                    "name" to ch.name,
                    "logo" to ch.logoUrl,
                    "category" to ch.groupTitle,
                    "url" to ch.streamUrl,
                    "type" to "SERIES",
                    "stream_id" to ch.streamId,
                    "plot" to ch.plot,
                    "cast" to ch.cast,
                    "rating" to ch.rating,
                    "poster" to ch.posterUrl,
                    "backdrop" to ch.backdropUrl
                ))
            }

            try {
                val jsonData = Gson().toJson(channels)
                val tempFile = File.createTempFile("xtream_", ".json", context.cacheDir)
                tempFile.writeText(jsonData)

                val gzipFile = gzipToFile(tempFile)
                tempFile.delete()

                val deviceId = premiumManager.getDeviceId()
                val filePart = MultipartBody.Part.createFormData(
                    "file", "xtream.json.gz",
                    gzipFile.asRequestBody("application/gzip".toMediaType())
                )
                val typePart = "xtream".toRequestBody("text/plain".toMediaType())
                val idPart = deviceId.toRequestBody("text/plain".toMediaType())

                val response = apiService.uploadPlaylistFile(filePart, typePart, idPart)
                gzipFile.delete()

                if (response.isSuccessful) {
                    val jobId = response.body()?.job_id
                    if (jobId != null) {
                        scope.send(State.Processing(75, "Server enriching…"))
                        val completed = pollForCompletion(jobId)
                        if (completed) {
                            scope.send(State.Processing(85, "Downloading result…"))
                            val count = downloadAndInsertResult(jobId, playlistId)
                            if (count > 0) {
                                scope.send(State.Success(count))
                                return
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Xtream upload failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Xtream backend upload error, falling back to local insert", e)
            }
        }

        // Fallback: insert Xtream data directly without backend enrichment
        Log.w(TAG, "Backend unavailable or failed — inserting Xtream data locally")
        val allChannels = live + vod + series
        // Delete old channels right before inserting new ones
        channelDao.deleteByPlaylist(playlistId)
        scope.send(State.Saving(0))
        for (i in allChannels.indices step BATCH_SIZE) {
            val batch = allChannels.subList(i, minOf(i + BATCH_SIZE, allChannels.size))
            channelDao.insertAll(batch)
            scope.trySend(State.Saving(minOf(i + BATCH_SIZE, allChannels.size)))
        }
        scope.send(State.Success(allChannels.size))
    }

    // ==========================================
    // M3U flow (existing logic, renamed)
    // ==========================================

    private suspend fun m3uFlow(
        m3uUrl: String,
        playlistId: Int,
        scope: ProducerScope<State>
    ) {
        scope.send(State.Processing(5, "Connecting…"))
        val backendAvailable = checkBackendHealth()

        if (backendAvailable) {
            Log.d(TAG, "Backend available")
            scope.send(State.Downloading(10))
            val jobId = uploadToBackend(m3uUrl)

            if (jobId != null) {
                // Poll for completion
                scope.send(State.Processing(40, "Server parsing…"))
                val completed = pollForCompletion(jobId)

                if (completed) {
                    // Download result and insert
                    scope.send(State.Processing(60, "Downloading channels…"))
                    val count = downloadAndInsertResult(jobId, playlistId)
                    if (count > 0) {
                        scope.send(State.Success(count))
                        return
                    }
                }
            }
            Log.w(TAG, "Backend flow failed, falling back to local")
        }

        // Fallback: local streaming parse
        localStreamingParse(m3uUrl, playlistId, scope)
    }

    // ==========================================
    // Shared helpers
    // ==========================================

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

            // Delete old channels atomically right before inserting the new ones
            channelDao.deleteByPlaylist(playlistId)

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
                        "logo_hd" -> ch.logoUrl = reader.nextString() // HD logo overrides provider logo
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
        // Delete old channels before inserting new ones
        channelDao.deleteByPlaylist(playlistId)
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
