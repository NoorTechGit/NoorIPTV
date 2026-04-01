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

sealed class EpgUiState {
    object Loading : EpgUiState()
    data class Success(
        val groups: List<String>,
        val channelsByGroup: Map<String, List<Channel>>,
        val epgByChannel: Map<Long, List<EpgProgram>>,
        val selectedGroup: String? = null
    ) : EpgUiState()
    data class Error(val message: String) : EpgUiState()
}

class EpgViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val channelDao = db.channelDao()

    private val _uiState = MutableStateFlow<EpgUiState>(EpgUiState.Loading)
    val uiState: StateFlow<EpgUiState> = _uiState.asStateFlow()

    // Kept so that per-channel EPG loads can reuse the same API instance.
    private var xtreamApi: XtreamApi? = null

    /**
     * Load live channels grouped by their group/category and fetch EPG for each.
     * This is intended for the full EPG grid view.
     */
    fun loadEpg(playlistId: Long, server: String, username: String, password: String) {
        _uiState.value = EpgUiState.Loading
        viewModelScope.launch {
            try {
                val pid = playlistId.toInt()
                val api = XtreamApi(server, username, password).also { xtreamApi = it }

                val groups = withContext(Dispatchers.IO) {
                    channelDao.getGroups(pid, "LIVE")
                }

                if (groups.isEmpty()) {
                    _uiState.value = EpgUiState.Success(
                        groups = emptyList(),
                        channelsByGroup = emptyMap(),
                        epgByChannel = emptyMap()
                    )
                    return@launch
                }

                // Build channel map grouped by category.
                val channelsByGroup = withContext(Dispatchers.IO) {
                    groups.associateWith { group ->
                        channelDao.getByGroup(pid, group, "LIVE")
                    }
                }

                // Fetch EPG for each channel concurrently (best-effort, failures ignored).
                val epgByChannel = mutableMapOf<Long, List<EpgProgram>>()
                withContext(Dispatchers.IO) {
                    channelsByGroup.values.flatten().forEach { channel ->
                        if (channel.streamId != 0) {
                            try {
                                val programs = api.getEpg(channel.streamId)
                                if (programs.isNotEmpty()) {
                                    epgByChannel[channel.id.toLong()] = programs
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                _uiState.value = EpgUiState.Success(
                    groups = groups,
                    channelsByGroup = channelsByGroup,
                    epgByChannel = epgByChannel,
                    selectedGroup = groups.firstOrNull()
                )
            } catch (e: Exception) {
                _uiState.value = EpgUiState.Error(e.message ?: "Failed to load EPG")
            }
        }
    }

    /**
     * Filter the EPG grid to show only channels belonging to [group].
     * The channels and EPG data are already loaded; this just updates the
     * selectedGroup in the state so the UI can scroll/filter accordingly.
     */
    fun selectGroup(group: String) {
        val current = _uiState.value as? EpgUiState.Success ?: return
        _uiState.value = current.copy(selectedGroup = group)
    }

    /**
     * Fetch EPG programs for a single channel and merge them into the existing
     * state. Useful for lazy loading individual rows in the EPG grid.
     */
    fun loadEpgForChannel(channel: Channel) {
        val api = xtreamApi ?: return
        val current = _uiState.value as? EpgUiState.Success ?: return
        viewModelScope.launch {
            try {
                val programs = withContext(Dispatchers.IO) { api.getEpg(channel.streamId) }
                val updatedEpg = current.epgByChannel.toMutableMap().also {
                    it[channel.id.toLong()] = programs
                }
                _uiState.value = current.copy(epgByChannel = updatedEpg)
            } catch (e: Exception) {
                // Non-fatal — the row will just show no EPG data.
            }
        }
    }
}
