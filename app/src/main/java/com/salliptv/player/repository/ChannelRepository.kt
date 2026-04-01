package com.salliptv.player.repository

import com.salliptv.player.data.CategoryDao
import com.salliptv.player.data.ChannelDao
import com.salliptv.player.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository wrapping [ChannelDao] and [CategoryDao].
 * Provides a clean API for ViewModels — no DAO or Room types leak upward.
 *
 * All blocking reads are wrapped in [withContext](Dispatchers.IO) so callers
 * can safely invoke them from a coroutine on any dispatcher.
 */
class ChannelRepository(
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao
) {

    // ──────────────────────────────────────────
    // Reactive Flow queries (observed by ViewModels via collectAsState / LiveData)
    // ──────────────────────────────────────────

    /**
     * Reactive stream of visible channels for a given content [type] ("LIVE", "VOD", "SERIES")
     * belonging to [playlistId].
     */
    fun getChannelsByType(type: String, playlistId: Int): Flow<List<Channel>> =
        channelDao.getByTypeFlow(playlistId, type)

    /**
     * Reactive stream of visible channels filtered by [type] and [group] title.
     */
    fun getChannelsByGroup(type: String, group: String, playlistId: Int): Flow<List<Channel>> =
        channelDao.getByGroupFlow(playlistId, group, type)

    /**
     * Reactive stream of all channels marked as favourite across every playlist.
     * Passing [playlistId] is accepted for API symmetry but the underlying DAO
     * returns favourites globally; callers may filter further if needed.
     */
    fun getFavorites(playlistId: Int): Flow<List<Channel>> =
        channelDao.getFavoritesFlow(playlistId)

    /**
     * Reactive stream of the 50 most-recently watched channels across every playlist.
     */
    fun getRecent(playlistId: Int): Flow<List<Channel>> =
        channelDao.getRecentFlow(playlistId)

    // ──────────────────────────────────────────
    // Suspend mutations
    // ──────────────────────────────────────────

    /**
     * Toggle the favourite flag for a single channel.
     */
    suspend fun toggleFavorite(channelId: Int, isFavorite: Boolean) {
        channelDao.updateFavorite(channelId, isFavorite)
    }

    /**
     * Record the current time (epoch ms) as [Channel.lastWatched] for [channelId].
     */
    suspend fun updateLastWatched(channelId: Int) {
        channelDao.updateLastWatched(channelId, System.currentTimeMillis())
    }

    /**
     * Delete every channel that belongs to [playlistId].
     * Typically called before re-importing a playlist.
     */
    suspend fun deleteByPlaylist(playlistId: Int) {
        channelDao.deleteByPlaylist(playlistId)
    }

    // ──────────────────────────────────────────
    // One-shot reads (returned directly, not as Flow)
    // ──────────────────────────────────────────

    /**
     * Full-text search on channel name within [playlistId].
     * Runs on the IO dispatcher; safe to call from any coroutine.
     */
    suspend fun search(query: String, playlistId: Int): List<Channel> =
        withContext(Dispatchers.IO) {
            channelDao.search(query, playlistId)
        }

    /**
     * Ordered list of distinct group titles for [type] in [playlistId].
     */
    suspend fun getGroups(type: String, playlistId: Int): List<String> =
        withContext(Dispatchers.IO) {
            channelDao.getGroups(playlistId, type)
        }

    /**
     * Ordered list of distinct country prefixes detected in [playlistId].
     */
    suspend fun getCountryPrefixes(playlistId: Int): List<String> =
        withContext(Dispatchers.IO) {
            channelDao.getCountryPrefixes(playlistId)
        }

    // ──────────────────────────────────────────
    // Smart Filter mutations
    // ──────────────────────────────────────────

    /**
     * Hide or show all LIVE channels whose [Channel.countryPrefix] matches [prefix].
     */
    suspend fun setHiddenByPrefix(playlistId: Int, prefix: String, hidden: Boolean) {
        channelDao.setHiddenByPrefix(playlistId, prefix, hidden)
    }

    /**
     * Hide or show all LIVE channels that belong to [group].
     */
    suspend fun setHiddenByGroup(playlistId: Int, group: String, hidden: Boolean) {
        channelDao.setHiddenByGroup(playlistId, group, hidden)
    }

    /**
     * Hide or show LIVE channels that match both [prefix] and [group].
     */
    suspend fun setHiddenByPrefixAndGroup(
        playlistId: Int,
        prefix: String,
        group: String,
        hidden: Boolean
    ) {
        channelDao.setHiddenByPrefixAndGroup(playlistId, prefix, group, hidden)
    }

    /**
     * Make every hidden LIVE channel in [playlistId] visible again.
     */
    suspend fun showAll(playlistId: Int) {
        channelDao.showAll(playlistId)
    }

    /**
     * Hide every LIVE channel in [playlistId].
     */
    suspend fun hideAll(playlistId: Int) {
        channelDao.hideAll(playlistId)
    }
}
