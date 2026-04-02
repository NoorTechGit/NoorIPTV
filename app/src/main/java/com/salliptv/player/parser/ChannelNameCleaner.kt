package com.salliptv.player.parser

import com.salliptv.player.model.CleanedChannelData
import java.util.Locale
import java.util.regex.Pattern

/**
 * Nettoyeur intelligent de noms de chaînes IPTV
 * Supporte les formats les plus courants des fournisseurs IPTV
 */
object ChannelNameCleaner {

    // ============ PATTERNS DE PAYS ============
    private val COUNTRY_PATTERNS = listOf(
        // Format |FR|, |AR|, |UK| etc.
        Pattern.compile("^\\|([A-Z]{2})\\|\\s*"),
        // Format [FR], [AR], [UK] etc.
        Pattern.compile("^\\[([A-Z]{2})\\]\\s*"),
        // Format (FR), (AR), (UK) etc.
        Pattern.compile("^\\(([A-Z]{2})\\)\\s*"),
        // Format FR:, AR:, UK: au début
        Pattern.compile("^([A-Z]{2}):\\s*"),
        // Format FR - , AR - , UK - 
        Pattern.compile("^([A-Z]{2})\\s*[-|]\\s*"),
        // Format FRANCE, BELGIQUE, SUISSE etc. suivis de |
        Pattern.compile("^(FRANCE|BELGIQUE|SUISSE|CANADA|USA|UK|ESPAGNE|PORTUGAL|ITALIE|GERMANY)\\s*[\\||\\-\\:]\\s*", Pattern.CASE_INSENSITIVE)
    )

    // ============ PATTERNS DE QUALITÉ ============
    private val QUALITY_PATTERNS = listOf(
        // 8K variants
        Pattern.compile("\\b(8K|UHD8|8KUHD|8K\\s*UHD)\\b", Pattern.CASE_INSENSITIVE),
        // 4K variants
        Pattern.compile("\\b(4K|UHD4|4KUHD|4K\\s*UHD|ULTRA\\s*HD|ULTRAHD)\\b", Pattern.CASE_INSENSITIVE),
        // FHD variants
        Pattern.compile("\\b(FHD|FULL\\s*HD|FULLHD|1920|1080P|1080)\\b", Pattern.CASE_INSENSITIVE),
        // HD variants  
        Pattern.compile("\\b(HD|720P|720|HQ|HIGH\\s*QUALITY)\\b", Pattern.CASE_INSENSITIVE),
        // SD variants
        Pattern.compile("\\b(SD|480P|480|480I|360P|360|LOW\\s*QUALITY|LQ)\\b", Pattern.CASE_INSENSITIVE)
    )

    // ============ PATTERNS DE CODECS ============
    private val CODEC_PATTERNS = listOf(
        // HEVC/H265
        Pattern.compile("""\b(HEVC|H265|H\.265|X265|X\.265)\b""", Pattern.CASE_INSENSITIVE),
        // H264/AVC
        Pattern.compile("""\b(H264|H\.264|X264|X\.264|AVC|MPEG4)\b""", Pattern.CASE_INSENSITIVE),
        // AV1
        Pattern.compile("\\b(AV1|AV01)\\b", Pattern.CASE_INSENSITIVE),
        // VP9
        Pattern.compile("\\b(VP9|VP09)\\b", Pattern.CASE_INSENSITIVE),
        // MPEG2
        Pattern.compile("\\b(MPEG2|MPEG\\s*2)\\b", Pattern.CASE_INSENSITIVE)
    )

    // ============ PATTERNS DE DÉLIMITEURS ============
    private val DELIMITER_PATTERNS = listOf(
        // *** ou ** ou *
        Pattern.compile("\\*+"),
        // Multiple espaces
        Pattern.compile("\\s+"),
        // | en trop
        Pattern.compile("\\|+"),
        // - en trop au début ou fin
        Pattern.compile("(^\\s*[-|]\\s*|\\s*[-|]\\s*$)"),
        // Parenthèses vides ou avec seulement espace
        Pattern.compile("\\(\\s*\\)"),
        // Crochets vides
        Pattern.compile("\\[\\s*\\]")
    )

    // ============ PATTERNS DE DÉTECTION DE GROUPES ============
    // Pour identifier si plusieurs chaînes sont des versions de la même chaîne
    private val VERSION_PATTERNS = listOf(
        // Chaîne avec numéro (TF1 HD 1, TF1 HD 2...)
        Pattern.compile("\\s+\\d+\\s*$"),
        // Backup/Alternatif
        Pattern.compile("\\b(BACKUP|ALT|ALTERNATIVE|SD|HD|FHD|4K|BACK)\\b", Pattern.CASE_INSENSITIVE)
    )

    // ============ MAPPING QUALITÉ STANDARDISÉ ============
    private fun normalizeQuality(quality: String?): String? {
        if (quality == null) return null
        return when {
            quality.contains("8K", ignoreCase = true) -> "8K"
            quality.contains("4K", ignoreCase = true) || 
            quality.contains("UHD", ignoreCase = true) -> "4K"
            quality.contains("FHD", ignoreCase = true) || 
            quality.contains("FULL", ignoreCase = true) ||
            quality.contains("1080", ignoreCase = true) -> "FHD"
            quality.contains("HD", ignoreCase = true) || 
            quality.contains("720", ignoreCase = true) ||
            quality.contains("HQ", ignoreCase = true) -> "HD"
            quality.contains("SD", ignoreCase = true) || 
            quality.contains("480", ignoreCase = true) ||
            quality.contains("360", ignoreCase = true) ||
            quality.contains("LQ", ignoreCase = true) -> "SD"
            else -> quality.uppercase(Locale.getDefault())
        }
    }

    private fun normalizeCodec(codec: String?): String? {
        if (codec == null) return null
        return when {
            codec.contains("HEVC", ignoreCase = true) || 
            codec.contains("265", ignoreCase = true) ||
            codec.contains("X265", ignoreCase = true) -> "HEVC"
            codec.contains("264", ignoreCase = true) || 
            codec.contains("AVC", ignoreCase = true) ||
            codec.contains("X264", ignoreCase = true) -> "H.264"
            codec.contains("AV1", ignoreCase = true) -> "AV1"
            codec.contains("VP9", ignoreCase = true) -> "VP9"
            else -> codec.uppercase(Locale.getDefault())
        }
    }

    /**
     * Nettoie un nom de chaîne et extrait les métadonnées
     */
    fun clean(originalName: String?): CleanedChannelData {
        if (originalName.isNullOrBlank()) {
            return CleanedChannelData(
                originalName = originalName ?: "",
                cleanName = "Unknown",
                qualityBadge = null,
                countryPrefix = null,
                codecInfo = null,
                groupKey = "unknown"
            )
        }

        var workingName = originalName.trim()
        
        // Étape 1: Extraire le pays
        var countryPrefix: String? = null
        for (pattern in COUNTRY_PATTERNS) {
            val matcher = pattern.matcher(workingName)
            if (matcher.find()) {
                countryPrefix = matcher.group(1)?.uppercase(Locale.getDefault())
                workingName = matcher.replaceFirst("").trim()
                break
            }
        }

        // Étape 2: Extraire la qualité
        var qualityBadge: String? = null
        for (pattern in QUALITY_PATTERNS) {
            val matcher = pattern.matcher(workingName)
            if (matcher.find()) {
                qualityBadge = matcher.group(1)
                workingName = matcher.replaceFirst("").trim()
                break // Prendre la première qualité trouvée
            }
        }

        // Étape 3: Extraire le codec
        var codecInfo: String? = null
        for (pattern in CODEC_PATTERNS) {
            val matcher = pattern.matcher(workingName)
            if (matcher.find()) {
                codecInfo = matcher.group(1)
                workingName = matcher.replaceFirst("").trim()
                break
            }
        }

        // Étape 4: Nettoyer les délimiteurs restants
        for (pattern in DELIMITER_PATTERNS) {
            workingName = pattern.matcher(workingName).replaceAll(" ").trim()
        }

        // Étape 5: Normaliser les espaces multiples
        workingName = workingName.replace(Regex("\\s+"), " ").trim()

        // Étape 6: Nettoyer les caractères spéciaux en début/fin
        workingName = workingName.trim { it in "|-:[](){}" || it.isWhitespace() }

        // Étape 7: Créer le nom pur pour le regroupement
        var cleanName = workingName
        
        // Retirer les numéros de version (ex: "TF1 1", "TF1 2")
        cleanName = cleanName.replace(Regex("\\s+\\d+\\s*$"), "").trim()
        
        // Retirer les suffixes de backup
        cleanName = cleanName.replace(Regex("\\s+(BACKUP|ALT|BACK)$", RegexOption.IGNORE_CASE), "").trim()

        // Normaliser les qualités et codecs
        val normalizedQuality = normalizeQuality(qualityBadge)
        val normalizedCodec = normalizeCodec(codecInfo)

        // Étape 8: Générer la clé de groupe unique
        // Cette clé permet de regrouper TF1 SD, TF1 HD, TF1 FHD...
        val groupKey = generateGroupKey(cleanName, countryPrefix)

        return CleanedChannelData(
            originalName = originalName,
            cleanName = cleanName.ifEmpty { "Unknown" },
            qualityBadge = normalizedQuality,
            countryPrefix = countryPrefix,
            codecInfo = normalizedCodec,
            groupKey = groupKey
        )
    }

    /**
     * Génère une clé unique pour regrouper les versions d'une même chaîne
     */
    private fun generateGroupKey(cleanName: String, countryPrefix: String?): String {
        val normalized = cleanName
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9]"), "") // Retirer tous les caractères spéciaux
            .trim()
        
        return if (countryPrefix != null) {
            "${countryPrefix.lowercase()}_$normalized"
        } else {
            normalized
        }
    }

    /**
     * Compare deux chaînes pour déterminer la meilleure qualité
     * Retourne: -1 si ch1 < ch2, 0 si égal, 1 si ch1 > ch2
     */
    fun compareQuality(ch1: CleanedChannelData, ch2: CleanedChannelData): Int {
        val qualityOrder = mapOf(
            "SD" to 1,
            "HD" to 2,
            "FHD" to 3,
            "4K" to 4,
            "8K" to 5
        )
        
        val q1 = qualityOrder[ch1.qualityBadge] ?: 0
        val q2 = qualityOrder[ch2.qualityBadge] ?: 0
        
        return when {
            q1 < q2 -> -1
            q1 > q2 -> 1
            else -> 0
        }
    }

    /**
     * Sélectionne la meilleure qualité parmi une liste de chaînes groupées
     */
    fun selectBestQuality(channels: List<CleanedChannelData>): CleanedChannelData {
        return channels.maxWithOrNull { c1, c2 ->
            when (val cmp = compareQuality(c1, c2)) {
                0 -> {
                    // Si même qualité, préférer HEVC/H.265 (meilleure compression)
                    val codec1 = if (c1.codecInfo == "HEVC") 1 else 0
                    val codec2 = if (c2.codecInfo == "HEVC") 1 else 0
                    codec1 - codec2
                }
                else -> cmp
            }
        } ?: channels.first()
    }

    /**
     * Regroupe une liste de chaînes par groupeKey
     * Retourne: Map<groupKey, liste des versions>
     */
    fun groupChannels(channels: List<CleanedChannelData>): Map<String, List<CleanedChannelData>> {
        return channels.groupBy { it.groupKey }
    }
}

// Extension pour faciliter l'usage
fun String?.cleanChannelName(): CleanedChannelData {
    return ChannelNameCleaner.clean(this)
}
