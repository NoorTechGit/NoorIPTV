package com.salliptv.player.repository

import android.util.Log
import com.salliptv.player.model.EpgProgram
import com.salliptv.player.parser.XtreamApi

private const val TAG = "EpgRepository"

/**
 * Repository for Electronic Programme Guide (EPG) data.
 *
 * EPG is fetched on demand via the Xtream Codes API and is not persisted locally —
 * the data is live-only and changes frequently enough that caching adds complexity
 * without much benefit.  ViewModels should cache the result in their own state.
 *
 * [XtreamApi] is injected per call so the repository is stateless and can be shared
 * across playlists without holding a stale API reference.
 */
class EpgRepository {

    /**
     * Fetch the short EPG for a live stream identified by [streamId].
     *
     * The Xtream API returns a compact listing (typically the current programme and
     * the next few upcoming ones) via the `get_short_epg` action.
     *
     * Returns an empty list — never throws — so the UI degrades gracefully when
     * the provider does not support EPG.
     */
    suspend fun getShortEpg(xtreamApi: XtreamApi, streamId: Int): List<EpgProgram> {
        return try {
            val programs = xtreamApi.getEpg(streamId)
            Log.d(TAG, "Fetched ${programs.size} EPG entries for stream $streamId")
            programs
        } catch (e: Exception) {
            Log.e(TAG, "getShortEpg failed for stream $streamId: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Alias for [getShortEpg] — exposed as a separate entry point so ViewModels can
     * express intent clearly.  Both methods call the same `get_short_epg` endpoint;
     * a full multi-day EPG grid would require an XML/XMLTV source (future work).
     */
    suspend fun getEpg(xtreamApi: XtreamApi, streamId: Int): List<EpgProgram> =
        getShortEpg(xtreamApi, streamId)

    /**
     * Returns only the programme that is currently airing for [streamId], or null
     * if nothing is on air (e.g. EPG gap or not supported).
     */
    suspend fun getCurrentProgram(xtreamApi: XtreamApi, streamId: Int): EpgProgram? =
        getShortEpg(xtreamApi, streamId).firstOrNull { it.isCurrentlyAiring() }

    /**
     * Returns the next upcoming programme after the currently-airing one, or null.
     */
    suspend fun getNextProgram(xtreamApi: XtreamApi, streamId: Int): EpgProgram? {
        val programs = getShortEpg(xtreamApi, streamId)
        val nowIndex = programs.indexOfFirst { it.isCurrentlyAiring() }
        return if (nowIndex >= 0 && nowIndex + 1 < programs.size) programs[nowIndex + 1] else null
    }
}
