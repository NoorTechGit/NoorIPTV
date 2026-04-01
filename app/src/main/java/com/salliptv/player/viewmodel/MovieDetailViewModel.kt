package com.salliptv.player.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.model.Channel
import com.salliptv.player.parser.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class MovieDetailUiState {
    object Loading : MovieDetailUiState()
    data class Success(
        val channel: Channel,
        /** Raw metadata from get_vod_info or get_series_info (title, plot, cast, etc.) */
        val vodInfo: JsonObject? = null,
        /** Flat list of episodes for the currently selected season (series only). */
        val episodes: List<JsonObject> = emptyList(),
        /** Available season numbers (series only). */
        val seasons: List<Int> = emptyList(),
        /** Up to 20 similar titles from the same category. */
        val similarContent: List<Channel> = emptyList(),
        val isFavorite: Boolean = false
    ) : MovieDetailUiState()
    data class Error(val message: String) : MovieDetailUiState()
}

class MovieDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val channelDao = db.channelDao()

    private val _uiState = MutableStateFlow<MovieDetailUiState>(MovieDetailUiState.Loading)
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()

    // Kept so that season changes can reuse the parsed series info.
    private var seriesInfoCache: JsonObject? = null

    /**
     * Load full metadata for a VOD movie or series item.
     * Dispatches to [getVodInfo] or [getSeriesInfo] based on [channel.type].
     */
    fun loadDetail(channel: Channel, server: String, username: String, password: String) {
        _uiState.value = MovieDetailUiState.Loading
        viewModelScope.launch {
            try {
                val api = XtreamApi(server, username, password)

                when (channel.type) {
                    "VOD" -> {
                        val vodInfo = withContext(Dispatchers.IO) {
                            api.getVodInfo(channel.streamId)
                        }
                        val similar = withContext(Dispatchers.IO) {
                            val categoryId = vodInfo?.get("categoryId")?.asString
                            if (!categoryId.isNullOrBlank()) {
                                api.getSimilarVod(categoryId, channel.playlistId, channel.streamId)
                            } else {
                                emptyList()
                            }
                        }
                        _uiState.value = MovieDetailUiState.Success(
                            channel = channel,
                            vodInfo = vodInfo,
                            similarContent = similar,
                            isFavorite = channel.isFavorite
                        )
                    }
                    "SERIES" -> {
                        val seriesInfo = withContext(Dispatchers.IO) {
                            api.getSeriesInfo(channel.streamId)
                        }
                        seriesInfoCache = seriesInfo
                        val (seasons, firstSeasonEpisodes) = extractSeasonData(seriesInfo, seasonNumber = null)
                        _uiState.value = MovieDetailUiState.Success(
                            channel = channel,
                            vodInfo = seriesInfo,
                            episodes = firstSeasonEpisodes,
                            seasons = seasons,
                            isFavorite = channel.isFavorite
                        )
                    }
                    else -> {
                        _uiState.value = MovieDetailUiState.Success(
                            channel = channel,
                            isFavorite = channel.isFavorite
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = MovieDetailUiState.Error(e.message ?: "Failed to load details")
            }
        }
    }

    /**
     * Switch the displayed episode list to [seasonNumber].
     * Uses the cached series info so no network request is needed.
     */
    fun loadSeason(seasonNumber: Int) {
        val current = _uiState.value as? MovieDetailUiState.Success ?: return
        val seriesInfo = seriesInfoCache ?: return
        val (seasons, episodes) = extractSeasonData(seriesInfo, seasonNumber)
        _uiState.value = current.copy(seasons = seasons, episodes = episodes)
    }

    /** Toggle the favorite state of the currently displayed channel. */
    fun toggleFavorite() {
        val current = _uiState.value as? MovieDetailUiState.Success ?: return
        val channel = current.channel
        viewModelScope.launch {
            try {
                val newFav = !current.isFavorite
                withContext(Dispatchers.IO) {
                    channelDao.updateFavorite(channel.id, newFav)
                }
                _uiState.value = current.copy(isFavorite = newFav)
            } catch (e: Exception) {
                _uiState.value = MovieDetailUiState.Error(e.message ?: "Failed to toggle favorite")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Extract season numbers and episode list from a parsed series info JsonObject.
     * If [seasonNumber] is null the first available season is used.
     * Returns a pair of (sortedSeasonNumbers, episodesForSelectedSeason).
     */
    private fun extractSeasonData(
        seriesInfo: JsonObject?,
        seasonNumber: Int?
    ): Pair<List<Int>, List<JsonObject>> {
        if (seriesInfo == null || !seriesInfo.has("seasons")) return Pair(emptyList(), emptyList())

        val seasonsArray = try {
            seriesInfo.getAsJsonArray("seasons")
        } catch (_: Exception) {
            return Pair(emptyList(), emptyList())
        }

        val seasonNumbers = mutableListOf<Int>()
        val episodesBySeason = mutableMapOf<Int, List<JsonObject>>()

        for (el in seasonsArray) {
            try {
                val seasonObj = el.asJsonObject
                val num = seasonObj.get("seasonNumber")?.asInt ?: continue
                seasonNumbers.add(num)
                val eps = mutableListOf<JsonObject>()
                val epArr = seasonObj.getAsJsonArray("episodes")
                for (epEl in epArr) {
                    try { eps.add(epEl.asJsonObject) } catch (_: Exception) {}
                }
                episodesBySeason[num] = eps
            } catch (_: Exception) {}
        }

        seasonNumbers.sort()
        val targetSeason = seasonNumber ?: seasonNumbers.firstOrNull() ?: return Pair(seasonNumbers, emptyList())
        val episodes = episodesBySeason[targetSeason] ?: emptyList()
        return Pair(seasonNumbers, episodes)
    }
}
