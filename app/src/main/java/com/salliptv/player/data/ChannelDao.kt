package com.salliptv.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.salliptv.player.model.Channel
import kotlinx.coroutines.flow.Flow

data class GroupCount(val groupTitle: String, val cnt: Int)

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
}
