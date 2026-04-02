package com.salliptv.player.parser

import android.util.Log
import com.salliptv.player.model.Channel
import com.salliptv.player.model.CleanedChannelData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern
import java.util.concurrent.TimeUnit

object M3uParser {

    private const val TAG = "M3uParser"
    private val ATTR_PATTERN: Pattern = Pattern.compile("""([a-zA-Z-]+)="([^"]*)""")
    
    // Client OkHttp réutilisable avec timeouts augmentés
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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
        onProgress: ((count: Int) -> Unit)? = null,
        onBatch: ((List<Channel>) -> Unit)? = null  // Callback pour insertion par lots
    ): ParseResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(m3uUrl)
            .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9,fr;q=0.8")
            .header("Accept-Encoding", "identity")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: ${response.message}")
        }
        
        val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: throw Exception("Empty response"), "UTF-8"))
        val batch = mutableListOf<Channel>()
        var epgUrl: String? = null
        var channelNum = 0
        val BATCH_SIZE = 1000  // Insérer par lots de 1000

        // First line — must be #EXTM3U
        val header = reader.readLine()
        if (header != null && header.startsWith("#EXTM3U")) {
            val m = Pattern.compile("""url-tvg="([^"]*)""").matcher(header)
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
                        batch.add(ch)
                        
                        // Insérer par lots pour libérer la mémoire
                        if (batch.size >= BATCH_SIZE) {
                            onBatch?.invoke(batch.toList())
                            batch.clear()
                        }
                        
                        if (channelNum % 500 == 0) {
                            onProgress?.invoke(channelNum)
                        }
                    }
                    extinf = null
                }
            }
        }

        reader.close()
        
        // Insérer le dernier lot
        if (batch.isNotEmpty()) {
            onBatch?.invoke(batch.toList())
            batch.clear()
        }

        Log.i(TAG, "Parsed $channelNum channels from M3U")
        // Retourner une liste vide car tout a été inséré par lots
        // Le nombre total est dans le log et le onProgress
        ParseResult(emptyList(), epgUrl)
    }

    private fun parseExtinf(extinf: String, url: String, playlistId: Int): Channel? {
        val ch = Channel()
        ch.streamUrl = url
        ch.playlistId = playlistId
        ch.type = "LIVE"

        // Extract display name (after last comma)
        val lastComma = extinf.lastIndexOf(',')
        val rawName = if (lastComma > 0 && lastComma < extinf.length - 1) {
            extinf.substring(lastComma + 1).trim()
        } else {
            "Unknown"
        }
        ch.name = rawName

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

        // Detect type from URL or group-title
        val lowerUrl = url.lowercase()
        val lowerGroup = ch.groupTitle?.lowercase() ?: ""
        when {
            // From URL patterns (Xtream Codes style)
            lowerUrl.contains("/movie/") || lowerUrl.contains("/vod/") -> ch.type = "VOD"
            lowerUrl.contains("/series/") -> ch.type = "SERIES"
            // From group-title patterns (most common IPTV style)
            lowerGroup.startsWith("vod") || lowerGroup.startsWith("movie") || 
            lowerGroup.startsWith("film") || lowerGroup.contains("[4k]") && !lowerGroup.contains("live") -> ch.type = "VOD"
            lowerGroup.startsWith("srs") || lowerGroup.startsWith("series") || 
            lowerGroup.contains("tv+") || lowerGroup.startsWith("hbo") || 
            lowerGroup.startsWith("disney+") || lowerGroup.startsWith("apple tv") ||
            lowerGroup.startsWith("amazon prime") || lowerGroup.startsWith("netflix") ||
            lowerGroup.contains("english series") || lowerGroup.contains("france series") -> ch.type = "SERIES"
        }

        // ============================================================================
        // NOUVEAU: Nettoyage intelligent du nom de chaîne
        // ============================================================================
        val cleanedData: CleanedChannelData = ChannelNameCleaner.clean(ch.name)
        
        ch.cleanName = cleanedData.cleanName
        ch.qualityBadge = cleanedData.qualityBadge
        ch.countryPrefix = cleanedData.countryPrefix
        ch.codecInfo = cleanedData.codecInfo
        ch.groupId = cleanedData.groupKey  // Sera mis à jour dans postProcessChannels

        // Log.v(TAG, "Cleaned: '${ch.name}' -> '${ch.cleanName}' [${ch.qualityBadge ?: "SD"}]")

        return ch
    }

    /**
     * Parse une playlist de démonstration avec des données fictives.
     * Utile pour tester l'interface sans connexion à un serveur.
     */
    suspend fun parseDemo(playlistId: Int): ParseResult = withContext(Dispatchers.IO) {
        val demoChannels = listOf(
            // FR - Sports
            Triple("FR: RMC Sport 1 FHD", "FR", "Sports"),
            Triple("FR: RMC Sport 2 HD", "FR", "Sports"),
            Triple("FR: Canal+ Sport FHD", "FR", "Sports"),
            Triple("FR: beIN SPORTS 1 HD", "FR", "Sports"),
            Triple("FR: beIN SPORTS 2 FHD", "FR", "Sports"),
            Triple("FR: Eurosport 1 HD", "FR", "Sports"),
            
            // FR - Information
            Triple("FR: BFM TV HD", "FR", "Information"),
            Triple("FR: CNEWS FHD", "FR", "Information"),
            Triple("FR: France Info HD", "FR", "Information"),
            Triple("FR: LCI HD", "FR", "Information"),
            
            // FR - Divertissement
            Triple("FR: Canal+ Cinema FHD", "FR", "Divertissement"),
            Triple("FR: Canal+ Series HD", "FR", "Divertissement"),
            Triple("FR: OCS Max FHD", "FR", "Divertissement"),
            Triple("FR: OCS City HD", "FR", "Divertissement"),
            Triple("FR: TF1 FHD", "FR", "Divertissement"),
            Triple("FR: France 2 HD", "FR", "Divertissement"),
            Triple("FR: M6 FHD", "FR", "Divertissement"),
            
            // UK - Sports
            Triple("UK: Sky Sports Main Event FHD", "UK", "Sports"),
            Triple("UK: Sky Sports Football HD", "UK", "Sports"),
            Triple("UK: BT Sport 1 FHD", "UK", "Sports"),
            Triple("UK: BT Sport 2 HD", "UK", "Sports"),
            
            // UK - Entertainment
            Triple("UK: BBC One FHD", "UK", "Entertainment"),
            Triple("UK: BBC Two HD", "UK", "Entertainment"),
            Triple("UK: ITV FHD", "UK", "Entertainment"),
            Triple("UK: Channel 4 HD", "UK", "Entertainment"),
            
            // USA - Sports
            Triple("US: ESPN FHD", "US", "Sports"),
            Triple("US: ESPN 2 HD", "US", "Sports"),
            Triple("US: Fox Sports 1 FHD", "US", "Sports"),
            Triple("US: NBC Sports HD", "US", "Sports"),
            
            // USA - Entertainment
            Triple("US: HBO FHD", "US", "Entertainment"),
            Triple("US: HBO 2 HD", "US", "Entertainment"),
            Triple("US: Showtime FHD", "US", "Entertainment"),
            Triple("US: FX HD", "US", "Entertainment"),
            Triple("US: AMC FHD", "US", "Entertainment"),
            
            // AR - Sports
            Triple("AR: beIN Sports 1 FHD", "AR", "Sports"),
            Triple("AR: beIN Sports 2 HD", "AR", "Sports"),
            
            // ES - Sports
            Triple("ES: Movistar Deportes FHD", "ES", "Sports"),
            Triple("ES: DAZN 1 HD", "ES", "Sports"),
            
            // Versions multiples pour tester le regroupement
            Triple("FR: Canal+ 4K UHD", "FR", "Premium"),
            Triple("FR: Canal+ FHD", "FR", "Premium"),
            Triple("FR: Canal+ HD", "FR", "Premium"),
            Triple("FR: Canal+ SD", "FR", "Premium"),
        )
        
        val channels = demoChannels.mapIndexed { index, (name, country, group) ->
            Channel().apply {
                this.name = name
                this.streamUrl = "http://demo.salliptv.tv/stream/$index"
                this.playlistId = playlistId
                this.type = "LIVE"
                this.groupTitle = group
                this.logoUrl = "https://logo.salliptv.tv/${name.hashCode()}.png"
                
                val cleanedData = ChannelNameCleaner.clean(name)
                this.cleanName = cleanedData.cleanName
                this.qualityBadge = cleanedData.qualityBadge
                this.countryPrefix = cleanedData.countryPrefix
                this.codecInfo = cleanedData.codecInfo
                this.groupId = cleanedData.groupKey
                this.channelNumber = index + 1
            }
        }
        
        Log.i(TAG, "Generated ${channels.size} demo channels")
        ParseResult(channels, null)
    }

    /**
     * NOUVEAU: Post-traitement pour regrouper les chaînes par groupe
     * Assigne des groupId cohérents et filtre les doublons si nécessaire
     */
    private fun postProcessChannels(channels: List<Channel>): List<Channel> {
        // Groupement temporaire pour détecter les versions multiples
        val grouped = channels.groupBy { it.groupId ?: it.cleanName ?: it.name ?: "" }
        
        val result = mutableListOf<Channel>()
        
        grouped.forEach { (groupKey, versions) ->
            if (versions.size == 1) {
                // Une seule version, on garde telle quelle
                val ch = versions[0]
                ch.groupId = groupKey
                result.add(ch)
            } else {
                // Plusieurs versions (SD, HD, FHD, 4K...)
                // On les garde toutes mais on marque la meilleure comme primaire
                val sortedVersions = versions.sortedWith { c1, c2 ->
                    ChannelNameCleaner.compareQuality(
                        CleanedChannelData(c1.name ?: "", c1.cleanName ?: "", c1.qualityBadge, null, null, ""),
                        CleanedChannelData(c2.name ?: "", c2.cleanName ?: "", c2.qualityBadge, null, null, "")
                    )
                }.reversed() // Meilleure qualité en premier

                // La première est la meilleure qualité
                sortedVersions.forEachIndexed { index, ch ->
                    ch.groupId = groupKey
                    // Optionnel: marquer comme alternative si index > 0
                    // ch.isAlternative = index > 0
                }

                // Par défaut, on garde toutes les versions dans la liste
                // L'UI décidera d'afficher un seul élément avec sélecteur de qualité
                result.addAll(sortedVersions)
                
                Log.d(TAG, "Groupe '$groupKey': ${versions.size} versions trouvées")
            }
        }

        return result
    }
}
