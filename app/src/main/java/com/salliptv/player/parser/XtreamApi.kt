package com.salliptv.player.parser

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonToken
import com.salliptv.player.model.CleanedChannelData
import com.salliptv.player.model.Category
import com.salliptv.player.model.Channel
import com.salliptv.player.model.EpgProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class XtreamApi(
    server: String,
    val username: String,
    val password: String
) {

    val server: String = if (server.endsWith("/")) server.dropLast(1) else server

    private val retryInterceptor = Interceptor { chain ->
        val request = chain.request()
        var response: Response? = null
        var exception: Exception? = null
        for (attempt in 1..3) {
            try {
                response?.close()
                response = chain.proceed(request)
                if (response.isSuccessful) return@Interceptor response
            } catch (e: Exception) {
                exception = e
            }
            if (attempt < 3) {
                Thread.sleep((attempt * 1000).toLong())
            }
        }
        response ?: throw exception!!
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(retryInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ==========================================
    // AUTH
    // ==========================================

    suspend fun login(): JsonObject? = withContext(Dispatchers.IO) {
        val url = "$server/player_api.php?username=$username&password=$password"
        val json = fetch(url) ?: return@withContext null

        val root = JsonParser.parseString(json).asJsonObject
        if (root.has("user_info")) {
            val userInfo = root.getAsJsonObject("user_info")
            val status = if (userInfo.has("status")) userInfo["status"].asString else ""
            if (status == "Active") {
                Log.i(TAG, "Login successful: $username")
                return@withContext root
            }
        }
        Log.e(TAG, "Login failed for $username")
        null
    }

    // ==========================================
    // CATEGORIES
    // ==========================================

    suspend fun getLiveCategories(playlistId: Int): List<Category> =
        getCategories("get_live_categories", "LIVE", playlistId)

    suspend fun getVodCategories(playlistId: Int): List<Category> =
        getCategories("get_vod_categories", "VOD", playlistId)

    suspend fun getSeriesCategories(playlistId: Int): List<Category> =
        getCategories("get_series_categories", "SERIES", playlistId)

    private suspend fun getCategories(action: String, type: String, playlistId: Int): List<Category> =
        withContext(Dispatchers.IO) {
            val url = "$server/player_api.php?username=$username&password=$password&action=$action"
            val json = fetch(url)
            val categories = mutableListOf<Category>()
            if (json == null) return@withContext categories

            val arr = JsonParser.parseString(json).asJsonArray
            for (el in arr) {
                val obj = el.asJsonObject
                val cat = Category()
                cat.categoryId = getStr(obj, "category_id")
                cat.categoryName = getStr(obj, "category_name")
                cat.type = type
                cat.playlistId = playlistId
                categories.add(cat)
            }
            Log.i(TAG, "Got ${categories.size} $type categories")
            categories
        }

    // ==========================================
    // STREAMS
    // ==========================================

    suspend fun getLiveStreams(categoryId: String, playlistId: Int): List<Channel> =
        getStreams("get_live_streams", categoryId, "LIVE", playlistId)

    suspend fun getVodStreams(categoryId: String, playlistId: Int): List<Channel> =
        getStreams("get_vod_streams", categoryId, "VOD", playlistId)

    suspend fun getSeries(categoryId: String, playlistId: Int): List<Channel> =
        getStreams("get_series", categoryId, "SERIES", playlistId)

    suspend fun getAllLiveStreams(playlistId: Int): List<Channel> = withContext(Dispatchers.IO) {
        val catNameMap = getCategories("get_live_categories", "LIVE", playlistId)
            .associate { it.categoryId to it.categoryName }

        val channels = getStreams("get_live_streams", null, "LIVE", playlistId)
        channels.forEach { ch ->
            if (ch.groupTitle != null && catNameMap.containsKey(ch.groupTitle)) {
                ch.groupTitle = catNameMap[ch.groupTitle]
            }
        }
        channels
    }

    suspend fun getAllVodStreams(playlistId: Int): List<Channel> = withContext(Dispatchers.IO) {
        val catNameMap = getCategories("get_vod_categories", "VOD", playlistId)
            .associate { it.categoryId to it.categoryName }

        val channels = getStreams("get_vod_streams", null, "VOD", playlistId)
        channels.forEach { ch ->
            if (ch.groupTitle != null && catNameMap.containsKey(ch.groupTitle)) {
                ch.groupTitle = catNameMap[ch.groupTitle]
            }
        }
        channels
    }

    suspend fun getAllSeries(playlistId: Int): List<Channel> = withContext(Dispatchers.IO) {
        val catNameMap = getCategories("get_series_categories", "SERIES", playlistId)
            .associate { it.categoryId to it.categoryName }

        val channels = getStreams("get_series", null, "SERIES", playlistId)
        channels.forEach { ch ->
            if (ch.groupTitle != null && catNameMap.containsKey(ch.groupTitle)) {
                ch.groupTitle = catNameMap[ch.groupTitle]
            }
        }
        channels
    }

    private suspend fun getStreams(
        action: String,
        categoryId: String?,
        type: String,
        playlistId: Int
    ): List<Channel> = withContext(Dispatchers.IO) {
        var url = "$server/player_api.php?username=$username&password=$password&action=$action"
        if (categoryId != null) url += "&category_id=$categoryId"

        val channels = mutableListOf<Channel>()

        // Use streaming parser to avoid OOM on large responses (VOD can have 60K+ entries)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SallIPTV/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body == null) {
                Log.e(TAG, "HTTP ${response.code} for $action")
                return@withContext channels
            }

            val reader = com.google.gson.stream.JsonReader(
                InputStreamReader(response.body!!.byteStream(), "UTF-8")
            ).also { it.isLenient = true }

            val gson = Gson()
            var num = 0

            try {
                val firstToken = reader.peek()
                val isObject = firstToken == JsonToken.BEGIN_OBJECT

                if (isObject) reader.beginObject() else reader.beginArray()

                while (reader.hasNext()) {
                    if (isObject) reader.nextName() // skip numeric key

                    try {
                        val obj: JsonObject = gson.fromJson(reader, JsonObject::class.java)
                            ?: continue

                        val ch = Channel()
                        ch.playlistId = playlistId
                        ch.type = type
                        
                        // Récupérer le nom brut
                        val rawName = getStr(obj, "name") ?: "Unknown"
                        ch.name = rawName
                        
                        // ============================================================================
                        // NOUVEAU: Nettoyage intelligent du nom de chaîne
                        // ============================================================================
                        val cleanedData: CleanedChannelData = ChannelNameCleaner.clean(rawName)
                        
                        ch.cleanName = cleanedData.cleanName
                        ch.qualityBadge = cleanedData.qualityBadge
                        ch.countryPrefix = cleanedData.countryPrefix
                        ch.codecInfo = cleanedData.codecInfo
                        ch.groupId = cleanedData.groupKey

                        Log.v(TAG, "Cleaned Xtream: '$rawName' -> '${ch.cleanName}' [${ch.qualityBadge ?: "SD"}]")
                        
                        ch.logoUrl = getStr(obj, "stream_icon")
                        if (ch.logoUrl.isNullOrEmpty() && obj.has("cover")) {
                            ch.logoUrl = getStr(obj, "cover")
                        }
                        ch.groupTitle = getStr(obj, "category_id")

                        var streamId = getInt(obj, "stream_id")
                        if (streamId == 0) streamId = getInt(obj, "series_id")
                        if (streamId == 0) streamId = getInt(obj, "movie_id")
                        ch.streamId = streamId

                        ch.streamUrl = when (type) {
                            "LIVE"   -> "$server/live/$username/$password/$streamId.ts"
                            "VOD"    -> {
                                val ext = getStr(obj, "container_extension")
                                    .takeUnless { it.isNullOrEmpty() } ?: "mp4"
                                "$server/movie/$username/$password/$streamId.$ext"
                            }
                            "SERIES" -> "$server/series/$username/$password/$streamId.mp4"
                            else     -> null
                        }

                        num++
                        ch.channelNumber = getInt(obj, "num").takeIf { it != 0 } ?: num

                        channels.add(ch)
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping malformed entry in $action")
                        try { reader.skipValue() } catch (_: Exception) {}
                    }
                }

                if (isObject) reader.endObject() else reader.endArray()
            } catch (e: Exception) {
                Log.e(TAG, "JSON stream error for $action: ${e.message} (got ${channels.size} entries so far)")
            }
        }

        // NOUVEAU: Post-traitement pour regrouper les versions multiples
        val processedChannels = postProcessChannels(channels)
        
        Log.i(TAG, "Got ${processedChannels.size} $type streams (${channels.size - processedChannels.size} doublons regroupés)")
        processedChannels
    }

    /**
     * NOUVEAU: Post-traitement pour regrouper les chaînes par groupe
     */
    private fun postProcessChannels(channels: List<Channel>): List<Channel> {
        // Groupement temporaire pour détecter les versions multiples
        val grouped = channels.groupBy { it.groupId ?: it.cleanName ?: it.name ?: "" }
        
        val result = mutableListOf<Channel>()
        
        grouped.forEach { (groupKey, versions) ->
            if (versions.size == 1) {
                // Une seule version
                val ch = versions[0]
                ch.groupId = groupKey
                result.add(ch)
            } else {
                // Plusieurs versions (SD, HD, FHD, 4K...)
                val sortedVersions = versions.sortedWith { c1, c2 ->
                    ChannelNameCleaner.compareQuality(
                        CleanedChannelData(c1.name ?: "", c1.cleanName ?: "", c1.qualityBadge, null, null, ""),
                        CleanedChannelData(c2.name ?: "", c2.cleanName ?: "", c2.qualityBadge, null, null, "")
                    )
                }.reversed()

                sortedVersions.forEachIndexed { index, ch ->
                    ch.groupId = groupKey
                }

                result.addAll(sortedVersions)
                
                Log.d(TAG, "Groupe Xtream '$groupKey': ${versions.size} versions")
            }
        }

        return result
    }

    // ==========================================
    // EPG
    // ==========================================

    suspend fun getEpg(streamId: Int): List<EpgProgram> = withContext(Dispatchers.IO) {
        val url = "$server/player_api.php?username=$username&password=$password" +
                "&action=get_short_epg&stream_id=$streamId"
        val json = fetch(url)
        val programs = mutableListOf<EpgProgram>()
        if (json == null) return@withContext programs

        try {
            val root = JsonParser.parseString(json).asJsonObject
            if (root.has("epg_listings")) {
                val arr = root.getAsJsonArray("epg_listings")
                for (el in arr) {
                    val obj = el.asJsonObject
                    val prog = EpgProgram()
                    prog.title = decodeBase64(getStr(obj, "title"))
                    prog.description = decodeBase64(getStr(obj, "description"))
                    prog.channelId = streamId.toString()

                    val start = getStr(obj, "start_timestamp")
                    val end = getStr(obj, "stop_timestamp")
                    if (start != null) prog.startTime = start.toLong()
                    if (end != null) prog.endTime = end.toLong()

                    programs.add(prog)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "EPG parse error: ${e.message}")
        }
        programs
    }

    // ==========================================
    // VOD / SERIES INFO
    // ==========================================

    /**
     * Get VOD movie details (synopsis, cast, rating, duration, etc.)
     * Xtream API: get_vod_info&vod_id=X
     */
    suspend fun getVodInfo(vodId: Int): JsonObject? = withContext(Dispatchers.IO) {
        val url = "$server/player_api.php?username=$username&password=$password" +
                "&action=get_vod_info&vod_id=$vodId"
        val json = fetch(url) ?: return@withContext null
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val info = JsonObject()

            // Movie data is in "info" or "movie_data"
            if (root.has("info")) {
                val movieInfo = root.getAsJsonObject("info")
                info.addProperty("title", getStr(movieInfo, "name"))
                var cover = getStr(movieInfo, "movie_image")
                if (cover.isNullOrEmpty()) cover = getStr(movieInfo, "cover_big")
                info.addProperty("cover", cover)
                info.addProperty("plot", getStr(movieInfo, "plot"))
                info.addProperty("cast", getStr(movieInfo, "cast"))
                info.addProperty("director", getStr(movieInfo, "director"))
                info.addProperty("genre", getStr(movieInfo, "genre"))
                info.addProperty("releaseDate", getStr(movieInfo, "releasedate"))
                info.addProperty("duration", getStr(movieInfo, "duration"))
                info.addProperty("rating", getStr(movieInfo, "rating"))
                info.addProperty("tmdbId", getStr(movieInfo, "tmdb_id"))
                info.addProperty("backdrop", getStr(movieInfo, "backdrop_path"))
                info.addProperty("year", getStr(movieInfo, "year"))
                info.addProperty("country", getStr(movieInfo, "country"))
            }

            // Stream URL info in "movie_data"
            if (root.has("movie_data")) {
                val movieData = root.getAsJsonObject("movie_data")
                val ext = getStr(movieData, "container_extension")
                    .takeUnless { it.isNullOrEmpty() } ?: "mp4"
                val streamId = getInt(movieData, "stream_id")
                info.addProperty("streamUrl", "$server/movie/$username/$password/$streamId.$ext")
                info.addProperty("streamId", streamId)
                info.addProperty("categoryId", getStr(movieData, "category_id"))
            }

            info
        } catch (e: Exception) {
            Log.e(TAG, "VOD info parse error: ${e.message}")
            null
        }
    }

    /**
     * Get Series details (seasons, episodes, info)
     * Xtream API: get_series_info&series_id=X
     */
    suspend fun getSeriesInfo(seriesId: Int): JsonObject? = withContext(Dispatchers.IO) {
        val url = "$server/player_api.php?username=$username&password=$password" +
                "&action=get_series_info&series_id=$seriesId"
        val json = fetch(url) ?: return@withContext null
        try {
            val rootEl = JsonParser.parseString(json)
            if (!rootEl.isJsonObject) {
                Log.e(TAG, "Series info is not a JSON object")
                return@withContext null
            }
            val root = rootEl.asJsonObject
            val info = JsonObject()

            try {
                var seriesInfo: JsonObject? = null
                if (root.has("info")) {
                    val infoEl = root["info"]
                    seriesInfo = when {
                        infoEl.isJsonObject -> infoEl.asJsonObject
                        infoEl.isJsonArray && infoEl.asJsonArray.size() > 0 -> {
                            val first = infoEl.asJsonArray[0]
                            if (first.isJsonObject) first.asJsonObject else null
                        }
                        else -> null
                    }
                }
                if (seriesInfo != null) {
                    info.addProperty("title", getStr(seriesInfo, "name"))
                    info.addProperty("cover", getStr(seriesInfo, "cover"))
                    info.addProperty("plot", getStr(seriesInfo, "plot"))
                    info.addProperty("cast", getStr(seriesInfo, "cast"))
                    info.addProperty("director", getStr(seriesInfo, "director"))
                    info.addProperty("genre", getStr(seriesInfo, "genre"))
                    info.addProperty("releaseDate", getStr(seriesInfo, "releaseDate"))
                    info.addProperty("rating", getStr(seriesInfo, "rating"))
                    info.addProperty("backdrop", getStr(seriesInfo, "backdrop_path"))
                    info.addProperty("year", getStr(seriesInfo, "year"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Series info section parse error: ${e.message}")
            }

            // Episodes grouped by season — can be object {"1":[...],"2":[...]} or empty
            try {
                if (root.has("episodes")) {
                    val epsElement = root["episodes"]
                    val seasonsList = JsonArray()

                    if (epsElement.isJsonObject) {
                        val episodes = epsElement.asJsonObject
                        for ((seasonKey, value) in episodes.entrySet()) {
                            val season = JsonObject()
                            season.addProperty("seasonNumber", seasonKey)
                            val eps = JsonArray()
                            if (value.isJsonArray) {
                                for (epEl in value.asJsonArray) {
                                    try {
                                        val ep = epEl.asJsonObject
                                        val episode = JsonObject()
                                        episode.addProperty("id", getStr(ep, "id"))
                                        episode.addProperty("title", getStr(ep, "title"))
                                        episode.addProperty("episodeNum", getInt(ep, "episode_num"))
                                        episode.addProperty("plot", getStr(ep, "plot"))
                                        episode.addProperty("duration", getStr(ep, "duration"))
                                        episode.addProperty("rating", getStr(ep, "rating"))
                                        val ext = getStr(ep, "container_extension")
                                            .takeUnless { it.isNullOrEmpty() } ?: "mp4"
                                        val epId = getStr(ep, "id")
                                        if (epId != null) {
                                            episode.addProperty("streamUrl",
                                                "$server/series/$username/$password/$epId.$ext")
                                        }
                                        eps.add(episode)
                                    } catch (_: Exception) {}
                                }
                            }
                            season.add("episodes", eps)
                            seasonsList.add(season)
                        }
                    }
                    if (seasonsList.size() > 0) {
                        info.add("seasons", seasonsList)
                        Log.i(TAG, "Parsed ${seasonsList.size()} seasons")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Series episodes parse error: ${e.message}")
            }

            info
        } catch (e: Exception) {
            Log.e(TAG, "Series info parse error: ${e.message}")
            // Log which keys exist in root for debugging
            try {
                val re = JsonParser.parseString(json)
                if (re.isJsonObject) {
                    Log.e(TAG, "  Root keys: ${re.asJsonObject.keySet()}")
                    for (key in re.asJsonObject.keySet()) {
                        val v = re.asJsonObject[key]
                        Log.e(TAG, "  $key type=${when {
                            v.isJsonObject -> "obj"
                            v.isJsonArray  -> "arr[${v.asJsonArray.size()}]"
                            else           -> "prim"
                        }}")
                    }
                }
            } catch (_: Exception) {}
            null
        }
    }

    /**
     * Get similar movies by category (same category_id)
     */
    suspend fun getSimilarVod(categoryId: String, playlistId: Int, excludeStreamId: Int): List<Channel> =
        withContext(Dispatchers.IO) {
            val all = getStreams("get_vod_streams", categoryId, "VOD", playlistId)
            val similar = mutableListOf<Channel>()
            for (ch in all) {
                if (ch.streamId != excludeStreamId) {
                    similar.add(ch)
                    if (similar.size >= 20) break
                }
            }
            similar
        }

    // ==========================================
    // CATCH-UP / TIMESHIFT
    // ==========================================

    /**
     * Check if catch-up/timeshift is enabled for this server.
     * Returns true if the server login response indicates timeshift support.
     */
    fun isCatchupEnabled(loginResult: JsonObject?): Boolean {
        try {
            if (loginResult != null && loginResult.has("server_info")) {
                val serverInfo = loginResult.getAsJsonObject("server_info")
                // Some servers expose timeshift flag
                if (serverInfo.has("timeshift")) {
                    val ts = serverInfo["timeshift"].asString
                    return ts != "0"
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Build a catch-up/timeshift stream URL for a past program.
     * Xtream supports: /streaming/timeshift.php/{username}/{password}/{duration}/{start}/{stream_id}.ts
     * Also supports: /timeshift/{username}/{password}/{duration}/{start_timestamp}/{stream_id}.m3u8
     */
    fun buildCatchupUrl(streamId: Int, startTimestamp: Long, durationMinutes: Int): String =
        "$server/timeshift/$username/$password/$durationMinutes/$startTimestamp/$streamId.m3u8"

    /**
     * Alternative catch-up URL format used by some providers.
     */
    fun buildCatchupUrlAlt(streamId: Int, startTime: String, durationMinutes: Int): String =
        "$server/streaming/timeshift.php?username=$username" +
                "&password=$password" +
                "&stream=$streamId" +
                "&start=$startTime" +
                "&duration=$durationMinutes"

    // ==========================================
    // HTTP
    // ==========================================

    private fun fetch(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SallIPTV/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful || response.body == null) {
                Log.e(TAG, "HTTP ${response.code} for $url")
                return null
            }
            return response.body!!.string()
        }
    }

    // ==========================================
    // HELPERS
    // ==========================================

    companion object {
        private const val TAG = "XtreamApi"

        private fun decodeBase64(encoded: String?): String? {
            if (encoded.isNullOrEmpty()) return encoded
            return try {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                encoded
            }
        }

        fun getStr(obj: JsonObject, key: String): String? {
            if (!obj.has(key) || obj[key].isJsonNull) return null
            val el: JsonElement = obj[key]
            return when {
                el.isJsonPrimitive -> el.asString
                el.isJsonArray     -> el.asJsonArray.joinToString(", ") { item ->
                    if (item.isJsonPrimitive) item.asString else item.toString()
                }
                else -> el.toString()
            }
        }

        fun getInt(obj: JsonObject, key: String): Int {
            return try {
                if (obj.has(key) && !obj[key].isJsonNull) obj[key].asInt else 0
            } catch (_: Exception) {
                0
            }
        }
    }
}
