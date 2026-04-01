package com.salliptv.player.parser

import android.util.Log
import com.salliptv.player.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object M3uParser {

    private const val TAG = "M3uParser"
    private val ATTR_PATTERN: Pattern = Pattern.compile("""([a-zA-Z-]+)="([^"]*)"""")

    data class ParseResult(
        val channels: List<Channel>,
        val epgUrl: String?
    )

    /**
     * Parse an M3U playlist from a URL.
     * Returns a [ParseResult] on success.
     * Progress updates are delivered via [onProgress] callback (called on IO thread).
     * Throws on error so the caller can handle it.
     */
    suspend fun parse(
        m3uUrl: String,
        playlistId: Int,
        onProgress: ((count: Int) -> Unit)? = null
    ): ParseResult = withContext(Dispatchers.IO) {
        val conn = (URL(m3uUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "SallIPTV/1.0")
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
        val channels = mutableListOf<Channel>()
        var epgUrl: String? = null
        var channelNum = 0

        // First line — must be #EXTM3U
        val header = reader.readLine()
        if (header != null && header.startsWith("#EXTM3U")) {
            val m = Pattern.compile("""url-tvg="([^"]*)"""").matcher(header)
            if (m.find()) {
                epgUrl = m.group(1)
            }
        }

        var extinf: String? = null
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val trimmed = line?.trim() ?: continue
            if (trimmed.isEmpty() || trimmed.startsWith("#EXTVLCOPT")) continue

            when {
                trimmed.startsWith("#EXTINF") -> extinf = trimmed
                extinf != null && !trimmed.startsWith("#") -> {
                    val ch = parseExtinf(extinf, trimmed, playlistId)
                    if (ch != null) {
                        channelNum++
                        ch.channelNumber = channelNum
                        channels.add(ch)
                        if (channelNum % 500 == 0) {
                            onProgress?.invoke(channelNum)
                        }
                    }
                    extinf = null
                }
            }
        }

        reader.close()
        conn.disconnect()

        Log.i(TAG, "Parsed ${channels.size} channels from M3U")
        ParseResult(channels, epgUrl)
    }

    private fun parseExtinf(extinf: String, url: String, playlistId: Int): Channel? {
        val ch = Channel()
        ch.streamUrl = url
        ch.playlistId = playlistId
        ch.type = "LIVE"

        // Extract display name (after last comma)
        val lastComma = extinf.lastIndexOf(',')
        ch.name = if (lastComma > 0 && lastComma < extinf.length - 1) {
            extinf.substring(lastComma + 1).trim()
        } else {
            "Unknown"
        }

        // Extract attributes
        val m = ATTR_PATTERN.matcher(extinf)
        while (m.find()) {
            val key = m.group(1) ?: continue
            val value = m.group(2) ?: continue

            when (key) {
                "tvg-id"      -> { /* stored for EPG matching later */ }
                "tvg-name"    -> if (ch.name == "Unknown") ch.name = value
                "tvg-logo"    -> ch.logoUrl = value
                "group-title" -> ch.groupTitle = value
                "tvg-chno"    -> try { ch.channelNumber = value.toInt() } catch (_: NumberFormatException) {}
            }
        }

        if (ch.groupTitle.isNullOrEmpty()) {
            ch.groupTitle = "Uncategorized"
        }

        // Detect type from URL
        val lower = url.lowercase()
        when {
            lower.contains("/movie/") || lower.contains("/vod/") -> ch.type = "VOD"
            lower.contains("/series/") -> ch.type = "SERIES"
        }

        return ch
    }
}
