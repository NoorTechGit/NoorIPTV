package com.salliptv.player.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.model.Channel
import com.salliptv.player.model.EpgProgram
import com.salliptv.player.parser.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class PlayerUiState {
    object Loading : PlayerUiState()
    data class Playing(
        val channel: Channel,
        val channels: List<Channel>,
        val currentIndex: Int,
        val epgPrograms: List<EpgProgram> = emptyList(),
        val isOverlayVisible: Boolean = false
    ) : PlayerUiState()
    data class Error(val message: String) : PlayerUiState()
}

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val channelDao = db.channelDao()

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /**
     * Load the channel list for a given playlist / type / group, then seek to
     * [startChannelId] as the initially playing channel.
     */
    fun loadChannels(
        playlistId: Long,
        type: String,
        group: String?,
        startChannelId: Long
    ) {
        _uiState.value = PlayerUiState.Loading
        viewModelScope.launch {
            try {
                val pid = playlistId.toInt()
                val channels = withContext(Dispatchers.IO) {
                    if (group != null) {
                        channelDao.getByGroup(pid, group, type)
                    } else {
                        channelDao.getByType(pid, type)
                    }
                }

                if (channels.isEmpty()) {
                    _uiState.value = PlayerUiState.Error("No channels found")
                    return@launch
                }

                val startIndex = channels.indexOfFirst { it.id == startChannelId.toInt() }
                    .takeIf { it >= 0 } ?: 0
                val channel = channels[startIndex]

                // Record watch timestamp.
                withContext(Dispatchers.IO) {
                    channelDao.updateLastWatched(channel.id, System.currentTimeMillis())
                }

                _uiState.value = PlayerUiState.Playing(
                    channel = channel,
                    channels = channels,
                    currentIndex = startIndex
                )
            } catch (e: Exception) {
                _uiState.value = PlayerUiState.Error(e.message ?: "Failed to load channels")
            }
        }
    }

    /** Switch to the next channel in the list (wraps around). */
    fun nextChannel() {
        val current = _uiState.value as? PlayerUiState.Playing ?: return
        goToChannel((current.currentIndex + 1) % current.channels.size)
    }

    /** Switch to the previous channel in the list (wraps around). */
    fun previousChannel() {
        val current = _uiState.value as? PlayerUiState.Playing ?: return
        val newIndex = (current.currentIndex - 1 + current.channels.size) % current.channels.size
        goToChannel(newIndex)
    }

    /** Jump directly to a channel by absolute index in the channel list. */
    fun goToChannel(index: Int) {
        val current = _uiState.value as? PlayerUiState.Playing ?: return
        if (index < 0 || index >= current.channels.size) return
        val channel = current.channels[index]
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    channelDao.updateLastWatched(channel.id, System.currentTimeMillis())
                }
            } catch (_: Exception) {}
        }
        _uiState.value = current.copy(
            channel = channel,
            currentIndex = index,
            epgPrograms = emptyList(),
            isOverlayVisible = current.isOverlayVisible
        )
    }

    /**
     * Fetch short EPG for the currently playing live channel.
     * Requires Xtream Codes credentials.
     */
    fun loadEpg(streamId: Int, server: String, username: String, password: String) {
        val current = _uiState.value as? PlayerUiState.Playing ?: return
        viewModelScope.launch {
            try {
                val api = XtreamApi(server, username, password)
                val programs = withContext(Dispatchers.IO) { api.getEpg(streamId) }
                _uiState.value = current.copy(epgPrograms = programs)
            } catch (e: Exception) {
                // EPG failure is non-fatal — keep playing.
            }
        }
    }

    /** Show or hide the on-screen channel/EPG overlay. */
    fun toggleOverlay() {
        val current = _uiState.value as? PlayerUiState.Playing ?: return
        _uiState.value = current.copy(isOverlayVisible = !current.isOverlayVisible)
    }

    /** Toggle favorite state of the currently playing channel. */
    fun toggleFavorite() {
        val current = _uiState.value as? PlayerUiState.Playing ?: return
        val channel = current.channel
        viewModelScope.launch {
            try {
                val newFav = !channel.isFavorite
                withContext(Dispatchers.IO) {
                    channelDao.updateFavorite(channel.id, newFav)
                }
                // Update the channel object in both the current slot and the list.
                val updatedChannel = channel.copy(isFavorite = newFav)
                val updatedList = current.channels.toMutableList().also {
                    it[current.currentIndex] = updatedChannel
                }
                _uiState.value = current.copy(channel = updatedChannel, channels = updatedList)
            } catch (e: Exception) {
                _uiState.value = PlayerUiState.Error(e.message ?: "Failed to toggle favorite")
            }
        }
    }
}
