package com.salliptv.player.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.model.Channel
import com.salliptv.player.model.Playlist
import com.salliptv.player.parser.M3uParser
import com.salliptv.player.parser.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class SettingsUiState {
    object Idle : SettingsUiState()
    object Loading : SettingsUiState()
    data class PlaylistsLoaded(val playlists: List<Playlist>) : SettingsUiState()
    data class TestResult(
        val success: Boolean,
        val channelCount: Int,
        val message: String
    ) : SettingsUiState()
    data class Error(val message: String) : SettingsUiState()
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val playlistDao = db.playlistDao()
    private val channelDao = db.channelDao()

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Idle)
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Load all saved playlists from the database. */
    fun loadPlaylists() {
        viewModelScope.launch {
            _uiState.value = SettingsUiState.Loading
            try {
                val playlists = withContext(Dispatchers.IO) { playlistDao.getAll() }
                _uiState.value = SettingsUiState.PlaylistsLoaded(playlists)
            } catch (e: Exception) {
                _uiState.value = SettingsUiState.Error(e.message ?: "Failed to load playlists")
            }
        }
    }

    /**
     * Test a playlist without persisting it.
     * For M3U playlists this performs a quick fetch and counts channels.
     * For Xtream playlists this attempts a login and returns server info.
     */
    fun testPlaylist(playlist: Playlist) {
        _uiState.value = SettingsUiState.Loading
        viewModelScope.launch {
            try {
                when (playlist.type) {
                    "M3U" -> {
                        val url = playlist.url
                        if (url.isNullOrBlank()) {
                            _uiState.value = SettingsUiState.TestResult(
                                success = false,
                                channelCount = 0,
                                message = "URL is empty"
                            )
                            return@launch
                        }
                        val result = withContext(Dispatchers.IO) {
                            M3uParser.parse(url, playlist.id)
                        }
                        _uiState.value = SettingsUiState.TestResult(
                            success = result.channels.isNotEmpty(),
                            channelCount = result.channels.size,
                            message = if (result.channels.isNotEmpty())
                                "Found ${result.channels.size} channels"
                            else
                                "Playlist loaded but contains no channels"
                        )
                    }
                    "XTREAM" -> {
                        val server = playlist.getServerBase()
                        val username = playlist.username
                        val password = playlist.password
                        if (server.isNullOrBlank() || username.isNullOrBlank() || password.isNullOrBlank()) {
                            _uiState.value = SettingsUiState.TestResult(
                                success = false,
                                channelCount = 0,
                                message = "Server, username and password are required"
                            )
                            return@launch
                        }
                        val api = XtreamApi(server, username, password)
                        val loginResult = withContext(Dispatchers.IO) { api.login() }
                        if (loginResult != null) {
                            _uiState.value = SettingsUiState.TestResult(
                                success = true,
                                channelCount = 0,
                                message = "Login successful"
                            )
                        } else {
                            _uiState.value = SettingsUiState.TestResult(
                                success = false,
                                channelCount = 0,
                                message = "Login failed — check credentials"
                            )
                        }
                    }
                    else -> {
                        _uiState.value = SettingsUiState.TestResult(
                            success = false,
                            channelCount = 0,
                            message = "Unknown playlist type: ${playlist.type}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = SettingsUiState.TestResult(
                    success = false,
                    channelCount = 0,
                    message = e.message ?: "Test failed"
                )
            }
        }
    }

    /**
     * Persist a playlist to the database.
     * If the playlist id is 0 it is treated as a new insert; otherwise it is
     * deleted and re-inserted (Room @Insert with REPLACE strategy requires an
     * @Update annotation — we keep it simple here).
     */
    fun savePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            _uiState.value = SettingsUiState.Loading
            try {
                withContext(Dispatchers.IO) {
                    if (playlist.id != 0) {
                        // Remove old record so the insert below replaces it cleanly.
                        playlistDao.delete(playlist)
                    }
                    playlistDao.insert(playlist)
                }
                loadPlaylists()
            } catch (e: Exception) {
                _uiState.value = SettingsUiState.Error(e.message ?: "Failed to save playlist")
            }
        }
    }

    /** Remove a playlist and all its associated channels from the database. */
    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            _uiState.value = SettingsUiState.Loading
            try {
                withContext(Dispatchers.IO) {
                    channelDao.deleteByPlaylist(playlist.id)
                    playlistDao.delete(playlist)
                }
                loadPlaylists()
            } catch (e: Exception) {
                _uiState.value = SettingsUiState.Error(e.message ?: "Failed to delete playlist")
            }
        }
    }

    /**
     * Re-fetch a playlist from its source and replace all channels in the database.
     * Works for both M3U and Xtream Codes playlists.
     */
    fun refreshPlaylist(playlist: Playlist) {
        _uiState.value = SettingsUiState.Loading
        viewModelScope.launch {
            try {
                val channels: List<Channel> = when (playlist.type) {
                    "M3U" -> {
                        val url = playlist.url
                            ?: throw IllegalArgumentException("M3U URL is missing")
                        val result = withContext(Dispatchers.IO) {
                            M3uParser.parse(url, playlist.id)
                        }
                        result.channels
                    }
                    "XTREAM" -> {
                        val server = playlist.getServerBase()
                            ?: throw IllegalArgumentException("Xtream server URL is missing")
                        val username = playlist.username
                            ?: throw IllegalArgumentException("Xtream username is missing")
                        val password = playlist.password
                            ?: throw IllegalArgumentException("Xtream password is missing")
                        val api = XtreamApi(server, username, password)
                        withContext(Dispatchers.IO) {
                            val live = api.getAllLiveStreams(playlist.id)
                            val vod = api.getAllVodStreams(playlist.id)
                            val series = api.getAllSeries(playlist.id)
                            live + vod + series
                        }
                    }
                    else -> throw IllegalArgumentException("Unknown playlist type: ${playlist.type}")
                }

                withContext(Dispatchers.IO) {
                    channelDao.deleteByPlaylist(playlist.id)
                    channelDao.insertAll(channels)
                    playlistDao.updateTimestamp(playlist.id, System.currentTimeMillis())
                }

                loadPlaylists()
            } catch (e: Exception) {
                _uiState.value = SettingsUiState.Error(e.message ?: "Failed to refresh playlist")
            }
        }
    }
}
