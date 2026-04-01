package com.salliptv.player.parser

import com.salliptv.player.model.Channel
import java.util.regex.Pattern

/**
 * Detects country/region prefixes from channel names and group titles.
 * IPTV playlists typically use patterns like:
 * - "FR: TF1 HD" or "FR | TF1 HD" or "FR - TF1 HD"
 * - Group titles: "FR France", "AR Arabic", "UK United Kingdom"
 */
object CountryDetector {

    // Common country/region codes found in IPTV playlists
    private val COUNTRY_NAMES: Map<String, String> = mapOf(
        "FR" to "France",
        "AR" to "Arabic",
        "US" to "United States",
        "UK" to "United Kingdom",
        "DE" to "Germany",
        "ES" to "Spain",
        "IT" to "Italy",
        "PT" to "Portugal",
        "NL" to "Netherlands",
        "BE" to "Belgium",
        "CH" to "Switzerland",
        "TR" to "Turkey",
        "IN" to "India",
        "PK" to "Pakistan",
        "AF" to "Africa",
        "CA" to "Canada",
        "BR" to "Brazil",
        "RU" to "Russia",
        "PL" to "Poland",
        "RO" to "Romania",
        "GR" to "Greece",
        "SE" to "Sweden",
        "NO" to "Norway",
        "DK" to "Denmark",
        "FI" to "Finland",
        "CZ" to "Czech Republic",
        "HU" to "Hungary",
        "AL" to "Albania",
        "RS" to "Serbia",
        "HR" to "Croatia",
        "BA" to "Bosnia",
        "MK" to "Macedonia",
        "BG" to "Bulgaria",
        "EX" to "Ex-Yugoslavia",
        "LA" to "Latin America",
        "MX" to "Mexico",
        "CO" to "Colombia",
        "JP" to "Japan",
        "KR" to "Korea",
        "CN" to "China",
        "TH" to "Thailand",
        "VN" to "Vietnam",
        "ID" to "Indonesia",
        "PH" to "Philippines",
        "MY" to "Malaysia",
        "SN" to "Senegal",
        "CI" to "Ivory Coast",
        "CM" to "Cameroon",
        "MA" to "Morocco",
        "DZ" to "Algeria",
        "TN" to "Tunisia",
        "EG" to "Egypt",
        "NG" to "Nigeria",
        "GH" to "Ghana",
        "KE" to "Kenya",
        "ZA" to "South Africa",
        "IL" to "Israel",
        "AE" to "UAE",
        "SA" to "Saudi Arabia",
        "QA" to "Qatar",
        "KW" to "Kuwait",
        "IQ" to "Iraq",
        "IR" to "Iran",
        "KU" to "Kurdish",
        "XXX" to "Adult"
    )

    // Pattern: "XX:" or "XX |" or "XX -" at start of name (2-3 letter code)
    private val PREFIX_PATTERN: Pattern = Pattern.compile("""^([A-Z]{2,3})\s*[:|/\-]""")

    // Pattern: group title starts with country code
    private val GROUP_PREFIX_PATTERN: Pattern = Pattern.compile("""^([A-Z]{2,3})\s""")

    /**
     * Detect and assign country prefixes to all channels.
     * Priority: channel name prefix > group title prefix
     */
    @JvmStatic
    fun detectPrefixes(channels: List<Channel>) {
        for (ch in channels) {
            var prefix: String? = null
            val name = ch.name
            val groupTitle = ch.groupTitle

            // Try channel name first: "FR: TF1 HD" → "FR"
            if (name != null) {
                val m = PREFIX_PATTERN.matcher(name.uppercase())
                if (m.find()) {
                    prefix = m.group(1)
                }
            }

            // Fallback: try group title: "FR France" → "FR"
            if (prefix == null && groupTitle != null) {
                val m = GROUP_PREFIX_PATTERN.matcher(groupTitle.uppercase())
                if (m.find()) {
                    val candidate = m.group(1)
                    if (candidate != null && COUNTRY_NAMES.containsKey(candidate)) {
                        prefix = candidate
                    }
                }

                // Also check if group title contains a known country name
                if (prefix == null) {
                    val upper = groupTitle.uppercase()
                    for ((code, _) in COUNTRY_NAMES) {
                        if (upper.contains("$code ") || upper.startsWith("$code/") || upper == code) {
                            prefix = code
                            break
                        }
                    }
                }
            }

            ch.countryPrefix = prefix
        }
    }

    /**
     * Get a human-readable name for a country code.
     */
    @JvmStatic
    fun getCountryName(prefix: String?): String {
        if (prefix == null) return "Other"
        val name = COUNTRY_NAMES[prefix.uppercase()]
        return if (name != null) "$prefix - $name" else prefix
    }
}
