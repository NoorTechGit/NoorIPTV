package com.salliptv.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.salliptv.player.model.Channel
import kotlinx.coroutines.flow.Flow

data class GroupCount(val groupTitle: String, val cnt: Int)

// NOUVEAU: Data class pour le regroupement intelligent
data class ChannelGroup(
    val groupKey: String,           // Clé unique du groupe
    val cleanName: String,          // Nom pur affiché
    val countryPrefix: String?,     // Pays
    val qualityBadge: String?,      // Meilleure qualité disponible
    val alternativeCount: Int,      // Nombre de versions alternatives
    val channelIds: String          // IDs des chaînes séparés par virgule
)

// NOUVEAU: Data class pour une chaîne avec ses alternatives
data class ChannelWithAlternatives(
    val primaryChannel: Channel,    // Chaîne principale (meilleure qualité)
    val alternatives: List<Channel> // Toutes les versions alternatives
)

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND type = :type AND hidden = 0 ORDER BY channelNumber, name")
    fun getByType(playlistId: Int, type: String): List<Channel>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND type = :type AND hidden = 0 ORDER BY channelNumber, name")
    fun getByTypeFlow(playlistId: Int, type: String): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND groupTitle = :group AND type = :type AND hidden = 0 ORDER BY channelNumber, name")
    fun getByGroup(playlistId: Int, group: String, type: String): List<Channel>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND groupTitle = :group AND type = :type AND hidden = 0 ORDER BY channelNumber, name LIMIT :limit")
    fun getByGroupLimited(playlistId: Int, group: String, type: String, limit: Int): List<Channel>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND groupTitle = :group AND type = :type AND hidden = 0 ORDER BY channelNumber, name")
    fun getByGroupFlow(playlistId: Int, group: String, type: String): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE isFavorite = 1 AND playlistId = :playlistId ORDER BY name")
    fun getFavorites(playlistId: Int): List<Channel>

    @Query("SELECT * FROM channels WHERE isFavorite = 1 AND playlistId = :playlistId ORDER BY name")
    fun getFavoritesFlow(playlistId: Int): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE lastWatched > 0 AND playlistId = :playlistId ORDER BY lastWatched DESC LIMIT 50")
    fun getRecent(playlistId: Int): List<Channel>

    @Query("SELECT * FROM channels WHERE lastWatched > 0 AND playlistId = :playlistId ORDER BY lastWatched DESC LIMIT 50")
    fun getRecentFlow(playlistId: Int): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' AND playlistId = :playlistId")
    fun search(query: String, playlistId: Int): List<Channel>

    // NOUVEAU: Recherche avec nom nettoyé
    @Query("SELECT * FROM channels WHERE (cleanName LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%') AND playlistId = :playlistId")
    fun searchClean(query: String, playlistId: Int): List<Channel>

    @Query("SELECT DISTINCT groupTitle FROM channels WHERE playlistId = :playlistId AND type = :type AND hidden = 0 ORDER BY groupTitle")
    fun getGroups(playlistId: Int, type: String): List<String>

    @Query("SELECT groupTitle, COUNT(*) as cnt FROM channels WHERE playlistId = :playlistId AND type = :type AND hidden = 0 AND groupTitle IS NOT NULL GROUP BY groupTitle ORDER BY groupTitle")
    fun getGroupsWithCounts(playlistId: Int, type: String): List<GroupCount>

    @Query("SELECT groupTitle, COUNT(*) as cnt FROM channels WHERE playlistId = :playlistId AND type = :type AND hidden = 0 AND groupTitle IS NOT NULL GROUP BY groupTitle ORDER BY groupTitle LIMIT :limit")
    fun getGroupsWithCountsLimited(playlistId: Int, type: String, limit: Int): List<GroupCount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<Channel>)

    @Query("UPDATE channels SET isFavorite = :fav WHERE id = :channelId")
    suspend fun updateFavorite(channelId: Int, fav: Boolean)

    @Query("UPDATE channels SET lastWatched = :timestamp WHERE id = :channelId")
    suspend fun updateLastWatched(channelId: Int, timestamp: Long)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteByPlaylist(playlistId: Int)

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND groupTitle = :group AND type = :type AND hidden = 0")
    fun countByGroup(playlistId: Int, group: String, type: String): Int

    // Smart Filter queries
    @Query("SELECT DISTINCT countryPrefix FROM channels WHERE playlistId = :playlistId AND countryPrefix IS NOT NULL ORDER BY countryPrefix")
    fun getCountryPrefixes(playlistId: Int): List<String>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND countryPrefix = :prefix")
    fun countByPrefix(playlistId: Int, prefix: String): Int

    @Query("SELECT DISTINCT groupTitle FROM channels WHERE playlistId = :playlistId AND countryPrefix = :prefix ORDER BY groupTitle")
    fun getGroupsByPrefix(playlistId: Int, prefix: String): List<String>

    @Query("UPDATE channels SET hidden = :hidden WHERE playlistId = :playlistId AND countryPrefix = :prefix AND type = 'LIVE'")
    suspend fun setHiddenByPrefix(playlistId: Int, prefix: String, hidden: Boolean)

    @Query("UPDATE channels SET hidden = :hidden WHERE playlistId = :playlistId AND groupTitle = :group AND type = 'LIVE'")
    suspend fun setHiddenByGroup(playlistId: Int, group: String, hidden: Boolean)

    @Query("UPDATE channels SET hidden = :hidden WHERE playlistId = :playlistId AND countryPrefix = :prefix AND groupTitle = :group AND type = 'LIVE'")
    suspend fun setHiddenByPrefixAndGroup(playlistId: Int, prefix: String, group: String, hidden: Boolean)

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId AND countryPrefix = :prefix AND groupTitle = :group")
    fun countByPrefixAndGroup(playlistId: Int, prefix: String, group: String): Int

    @Query("UPDATE channels SET hidden = 0 WHERE playlistId = :playlistId AND type = 'LIVE'")
    suspend fun showAll(playlistId: Int)

    @Query("UPDATE channels SET hidden = 1 WHERE playlistId = :playlistId AND type = 'LIVE'")
    suspend fun hideAll(playlistId: Int)

    // ============================================================================
    // NOUVEAU: REQUÊTES DE REGROUPEMENT INTELLIGENT
    // ============================================================================

    /**
     * Récupère les chaînes groupées par cleanName (une seule entrée par chaîne, meilleure qualité)
     * 
     * Cette requête sélectionne la meilleure qualité pour chaque groupe
     * Ordre de priorité: 4K > FHD > HD > SD > null
     */
    @Query("""
        SELECT * FROM channels 
        WHERE playlistId = :playlistId 
        AND type = :type 
        AND hidden = 0
        AND cleanName IS NOT NULL
        GROUP BY groupId
        ORDER BY 
            CASE qualityBadge
                WHEN '4K' THEN 4
                WHEN 'FHD' THEN 3
                WHEN 'HD' THEN 2
                WHEN 'SD' THEN 1
                ELSE 0
            END DESC,
            cleanName ASC
    """)
    fun getGroupedChannels(playlistId: Int, type: String): List<Channel>

    /**
     * Récupère toutes les versions alternatives d'une chaîne
     */
    @Query("""
        SELECT * FROM channels 
        WHERE playlistId = :playlistId 
        AND groupId = :groupId 
        AND id != :excludeId
        ORDER BY 
            CASE qualityBadge
                WHEN '4K' THEN 4
                WHEN 'FHD' THEN 3
                WHEN 'HD' THEN 2
                WHEN 'SD' THEN 1
                ELSE 0
            END DESC
    """)
    fun getAlternativeVersions(playlistId: Int, groupId: String, excludeId: Int): List<Channel>

    /**
     * Compte le nombre de versions alternatives pour un groupe
     */
    @Query("""
        SELECT COUNT(*) FROM channels 
        WHERE playlistId = :playlistId 
        AND groupId = :groupId 
        AND hidden = 0
    """)
    fun countAlternatives(playlistId: Int, groupId: String): Int

    /**
     * Requête pour obtenir les chaînes avec indication des alternatives
     * Utilisée pour afficher un badge "+2 versions" sur la carte
     */
    @Query("""
        SELECT 
            c.*,
            (SELECT COUNT(*) FROM channels c2 WHERE c2.groupId = c.groupId AND c2.id != c.id AND c2.hidden = 0) as altCount
        FROM channels c
        WHERE c.playlistId = :playlistId 
        AND c.type = :type 
        AND c.hidden = 0
        AND c.cleanName IS NOT NULL
        GROUP BY c.groupId
        HAVING c.id = (
            SELECT id FROM channels c3 
            WHERE c3.groupId = c.groupId 
            ORDER BY 
                CASE qualityBadge
                    WHEN '4K' THEN 4
                    WHEN 'FHD' THEN 3
                    WHEN 'HD' THEN 2
                    WHEN 'SD' THEN 1
                    ELSE 0
                END DESC
            LIMIT 1
        )
        ORDER BY c.cleanName ASC
    """)
    fun getGroupedChannelsWithAltCount(playlistId: Int, type: String): List<ChannelWithAltCount>

    /**
     * Récupère une chaîne spécifique avec toutes ses alternatives
     */
    @Transaction
    suspend fun getChannelWithAlternatives(channelId: Int, playlistId: Int): ChannelWithAlternatives? {
        val primary = getById(channelId) ?: return null
        val groupId = primary.groupId ?: return ChannelWithAlternatives(primary, emptyList())
        
        val alternatives = getAlternativeVersions(playlistId, groupId, channelId)
        return ChannelWithAlternatives(primary, alternatives)
    }

    @Query("SELECT * FROM channels WHERE id = :channelId LIMIT 1")
    suspend fun getById(channelId: Int): Channel?

    // ============================================================================
    // MÉTHODES UTILITAIRES POUR MIGRATION
    // ============================================================================

    /**
     * Met à jour le groupId pour tous les channels d'un playlist basé sur cleanName
     * À exécuter après l'import
     */
    @Query("UPDATE channels SET groupId = LOWER(countryPrefix || '_' || cleanName) WHERE playlistId = :playlistId AND cleanName IS NOT NULL")
    suspend fun updateGroupIds(playlistId: Int)

    /**
     * Récupère les chaînes qui n'ont pas encore été nettoyées
     */
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND cleanName IS NULL")
    fun getUncleanedChannels(playlistId: Int): List<Channel>
}

// NOUVEAU: Data class pour Room (impossible d'avoir colonne calculée directement)
data class ChannelWithAltCount(
    val id: Int,
    val streamId: Int,
    val name: String?,
    val cleanName: String?,
    val qualityBadge: String?,
    val countryPrefix: String?,
    val codecInfo: String?,
    val groupId: String?,
    val streamUrl: String?,
    val logoUrl: String?,
    val groupTitle: String?,
    val type: String?,
    val playlistId: Int,
    val isFavorite: Boolean,
    val channelNumber: Int,
    val altCount: Int  // Nombre d'alternatives
)
