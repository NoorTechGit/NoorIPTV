package com.salliptv.player.repository

import android.util.Log
import com.salliptv.player.data.CategoryDao
import com.salliptv.player.data.ChannelDao
import com.salliptv.player.data.PlaylistDao
import com.salliptv.player.model.Playlist
import com.salliptv.player.parser.CountryDetector
import com.salliptv.player.parser.M3uParser
import com.salliptv.player.parser.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

private const val TAG = "PlaylistRepository"

/**
 * Repository wrapping [PlaylistDao], [ChannelDao], and [CategoryDao].
 *
 * [loadPlaylist] is the key entry point: it delegates to [M3uParser] or [XtreamApi]
 * depending on [Playlist.type], then persists the result and returns the channel count.
 */
class PlaylistRepository(
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao
) {

    // ──────────────────────────────────────────
    // Reactive Flow queries
    // ──────────────────────────────────────────

    /** Reactive stream of every saved playlist, ordered by name. */
    fun getAllPlaylists(): Flow<List<Playlist>> = playlistDao.getAllFlow()

    // ──────────────────────────────────────────
    // One-shot reads
    // ──────────────────────────────────────────

    /** Returns the playlist with the given [id], or null if it does not exist. */
    suspend fun getById(id: Int): Playlist? = withContext(Dispatchers.IO) {
        playlistDao.getById(id)
    }

    // ──────────────────────────────────────────
    // Mutations
    // ──────────────────────────────────────────

    /**
     * Persist a new [playlist] and return its auto-generated row ID.
     */
    suspend fun insert(playlist: Playlist): Long = playlistDao.insert(playlist)

    /**
     * Permanently delete [playlist] and all channels/categories that belong to it.
     */
    suspend fun delete(playlist: Playlist) {
        channelDao.deleteByPlaylist(playlist.id)
        categoryDao.deleteByPlaylist(playlist.id)
        playlistDao.delete(playlist)
    }

    /**
     * Update the [Playlist.lastUpdated] field to [timestamp] (epoch ms).
     */
    suspend fun updateTimestamp(id: Int, timestamp: Long) {
        playlistDao.updateTimestamp(id, timestamp)
    }

    // ──────────────────────────────────────────
    // Core loading logic
    // ──────────────────────────────────────────

    /**
     * Load (or refresh) channels for [playlist].
     *
     * - If [Playlist.type] is `"M3U"`, [M3uParser] fetches and parses the remote file.
     * - If [Playlist.type] is `"XTREAM"`, [XtreamApi] fetches all live, VOD, and series
     *   streams along with their categories.
     *
     * In both cases the existing data for this playlist is replaced, country prefixes are
     * detected via [CountryDetector], and [Playlist.lastUpdated] is stamped.
     *
     * [onProgress] is an optional callback invoked on the IO thread with the number of
     * channels parsed so far (useful for updating a progress bar).
     *
     * Returns a [Result] containing the total channel count on success, or an exception
     * on failure — so the ViewModel can surface the error without crashing.
     */
    suspend fun loadPlaylist(
        playlist: Playlist,
        onProgress: ((Int) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            // Wipe stale data first so a re-import is always clean.
            channelDao.deleteByPlaylist(playlist.id)
            categoryDao.deleteByPlaylist(playlist.id)

            val totalChannels: Int = when (playlist.type?.uppercase()) {

                // ── M3U ──────────────────────────────────────────────────────
                "M3U" -> {
                    val url = requireNotNull(playlist.url) {
                        "M3U playlist has no URL (id=${playlist.id})"
                    }
                    val result = M3uParser.parse(url, playlist.id, onProgress)

                    // Carry the EPG URL back into the playlist row if the M3U declares one.
                    if (!result.epgUrl.isNullOrEmpty() && playlist.epgUrl.isNullOrEmpty()) {
                        // We intentionally do NOT update the DB row here — callers can
                        // decide whether to persist it.  Just log it.
                        Log.i(TAG, "M3U advertised EPG URL: ${result.epgUrl}")
                    }

                    CountryDetector.detectPrefixes(result.channels)
                    channelDao.insertAll(result.channels)
                    result.channels.size
                }

                // ── Xtream Codes ─────────────────────────────────────────────
                "XTREAM" -> {
                    val server = requireNotNull(playlist.getServerBase()) {
                        "Xtream playlist has no server URL (id=${playlist.id})"
                    }
                    val user = requireNotNull(playlist.username) {
                        "Xtream playlist has no username (id=${playlist.id})"
                    }
                    val pass = requireNotNull(playlist.password) {
                        "Xtream playlist has no password (id=${playlist.id})"
                    }

                    val api = XtreamApi(server, user, pass)

                    // Verify credentials before downloading everything.
                    requireNotNull(api.login()) {
                        "Xtream login failed for ${playlist.name} ($server)"
                    }

                    // Fetch categories for all three content types and persist them.
                    val liveCategories  = api.getLiveCategories(playlist.id)
                    val vodCategories   = api.getVodCategories(playlist.id)
                    val seriesCategories = api.getSeriesCategories(playlist.id)
                    categoryDao.insertAll(liveCategories + vodCategories + seriesCategories)

                    // Fetch all streams (category IDs are resolved to names inside XtreamApi).
                    val liveChannels   = api.getAllLiveStreams(playlist.id)
                    onProgress?.invoke(liveChannels.size)

                    val vodChannels    = api.getAllVodStreams(playlist.id)
                    onProgress?.invoke(liveChannels.size + vodChannels.size)

                    val seriesChannels = api.getAllSeries(playlist.id)
                    val allChannels    = liveChannels + vodChannels + seriesChannels

                    CountryDetector.detectPrefixes(allChannels)
                    channelDao.insertAll(allChannels)
                    onProgress?.invoke(allChannels.size)

                    allChannels.size
                }

                else -> error("Unknown playlist type '${playlist.type}' (id=${playlist.id})")
            }

            // Stamp the refresh time on success.
            playlistDao.updateTimestamp(playlist.id, System.currentTimeMillis())
            Log.i(TAG, "Loaded $totalChannels channels for playlist '${playlist.name}'")
            totalChannels
        }.onFailure { e ->
            Log.e(TAG, "loadPlaylist failed for '${playlist.name}': ${e.message}", e)
        }
    }
}
