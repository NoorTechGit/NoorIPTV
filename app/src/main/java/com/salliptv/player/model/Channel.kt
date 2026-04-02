package com.salliptv.player.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["type"]),
        Index(value = ["groupTitle"]),
        Index(value = ["cleanName"]), // NOUVEAU: Index pour regroupement rapide
        Index(value = ["playlistId", "type", "cleanName"]) // Index composite pour requêtes de regroupement
    ]
)
data class Channel(
    @PrimaryKey(autoGenerate = true) var id: Int = 0,
    var streamId: Int = 0,
    var name: String? = null,
    
    // NOUVEAU: Champs pour le nettoyage et regroupement
    var cleanName: String? = null,        // Nom pur sans pays/codecs (ex: "TF1")
    var qualityBadge: String? = null,     // Badge qualité (SD, HD, FHD, 4K, 8K)
    var countryPrefix: String? = null,    // Pays extrait (FR, AR, etc.)
    var codecInfo: String? = null,        // Codec détecté (HEVC, H265, etc.)
    var groupId: String? = null,          // ID de groupe pour versions multiples (SD/HD/4K)
    
    var streamUrl: String? = null,
    var logoUrl: String? = null,
    var groupTitle: String? = null,
    var type: String? = null, // LIVE, VOD, SERIES
    var playlistId: Int = 0,
    var isFavorite: Boolean = false,
    var channelNumber: Int = 0,
    var tvgId: String? = null,
    var tvgName: String? = null,
    var lastWatched: Long = 0,
    var addedDate: Long = System.currentTimeMillis(),
    
    // Pour VOD/Séries
    var plot: String? = null,
    var cast: String? = null,
    var director: String? = null,
    var genre: String? = null,
    var releaseDate: String? = null,
    var rating: String? = null,
    var posterUrl: String? = null,
    var backdropUrl: String? = null,
    var duration: String? = null,
    var episodeNumber: Int = 0,
    var seasonNumber: Int = 0,
    var seriesId: String? = null,
    
    // Smart Filter: hidden flag for filtering
    var hidden: Boolean = false
) {
    // Helper pour afficher le nom complet avec badge
    fun getDisplayName(): String {
        return cleanName ?: name ?: "Unknown"
    }
    
    // Helper pour vérifier si c'est une version alternative
    fun isAlternativeVersion(): Boolean {
        return groupId != null && groupId != id.toString()
    }
}

// Data class pour le retour du cleaner
data class CleanedChannelData(
    val originalName: String,
    val cleanName: String,           // Nom pur (TF1)
    val qualityBadge: String?,       // FHD, HD, 4K...
    val countryPrefix: String?,      // FR, AR...
    val codecInfo: String?,          // HEVC, H264...
    val groupKey: String             // Clé unique pour regrouper les versions
)
