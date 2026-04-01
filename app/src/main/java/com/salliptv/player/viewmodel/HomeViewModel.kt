package com.salliptv.player.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.model.Channel
import com.salliptv.player.model.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class HomeUiState {
    object Loading : HomeUiState()
    data class Success(
        val continueWatching: List<Channel>,
        val favorites: List<Channel>,
        val liveChannels: List<Channel>,
        val vodContent: List<Channel>,
        val seriesContent: List<Channel>,
        val categories: List<String>,
        val selectedCategory: String? = null,
        val currentPlaylist: Playlist? = null
    ) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val channelDao = db.channelDao()
    private val playlistDao = db.playlistDao()

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Tracks the active playlist so category/tab filters can reload without re-fetching the id.
    private val _currentPlaylistId = MutableStateFlow(0L)

    // Tracks the active content tab (LIVE, VOD, SERIES).
    private var currentTab: String = "LIVE"

    // Tracks the active category filter.
    private var selectedCategory: String? = null

    /**
     * Load all home sections for the given playlist.
     * Fetches recent, favorites, and channel lists for all content types.
     */
    fun loadPlaylist(playlistId: Long) {
        _currentPlaylistId.value = playlistId
        _uiState.value = HomeUiState.Loading
        viewModelScope.launch {
            try {
                val pid = playlistId.toInt()
                val playlist = withContext(Dispatchers.IO) { playlistDao.getById(pid) }

                val continueWatching = withContext(Dispatchers.IO) { channelDao.getRecent(pid) }
                val favorites = withContext(Dispatchers.IO) { channelDao.getFavorites(pid) }
                val liveChannels = withContext(Dispatchers.IO) { channelDao.getByType(pid, "LIVE") }
                val vodContent = withContext(Dispatchers.IO) { channelDao.getByType(pid, "VOD") }
                val seriesContent = withContext(Dispatchers.IO) { channelDao.getByType(pid, "SERIES") }
                val categories = withContext(Dispatchers.IO) {
                    channelDao.getGroups(pid, currentTab)
                }

                _uiState.value = HomeUiState.Success(
                    continueWatching = continueWatching,
                    favorites = favorites,
                    liveChannels = liveChannels,
                    vodContent = vodContent,
                    seriesContent = seriesContent,
                    categories = categories,
                    selectedCategory = selectedCategory,
                    currentPlaylist = playlist
                )
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(e.message ?: "Failed to load playlist")
            }
        }
    }

    /**
     * Filter the displayed channels by group/category name.
     * Pass null to clear the filter and show all channels.
     */
    fun selectCategory(category: String?) {
        selectedCategory = category
        val playlistId = _currentPlaylistId.value
        if (playlistId == 0L) return
        viewModelScope.launch {
            try {
                val pid = playlistId.toInt()
                val current = _uiState.value as? HomeUiState.Success ?: return@launch

                val channels = withContext(Dispatchers.IO) {
                    if (category == null) {
                        channelDao.getByType(pid, currentTab)
                    } else {
                        channelDao.getByGroup(pid, category, currentTab)
                    }
                }

                val updated = when (currentTab) {
                    "VOD"    -> current.copy(vodContent = channels, selectedCategory = category)
                    "SERIES" -> current.copy(seriesContent = channels, selectedCategory = category)
                    else     -> current.copy(liveChannels = channels, selectedCategory = category)
                }
                _uiState.value = updated
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(e.message ?: "Failed to filter channels")
            }
        }
    }

    /**
     * Switch the active content tab and reload channels and categories accordingly.
     * Valid values: "LIVE", "VOD", "SERIES".
     */
    fun setCurrentTab(tab: String) {
        currentTab = tab
        selectedCategory = null
        val playlistId = _currentPlaylistId.value
        if (playlistId == 0L) return
        viewModelScope.launch {
            try {
                val pid = playlistId.toInt()
                val current = _uiState.value as? HomeUiState.Success ?: return@launch

                val channels = withContext(Dispatchers.IO) { channelDao.getByType(pid, tab) }
                val categories = withContext(Dispatchers.IO) { channelDao.getGroups(pid, tab) }

                val updated = when (tab) {
                    "VOD"    -> current.copy(vodContent = channels, categories = categories, selectedCategory = null)
                    "SERIES" -> current.copy(seriesContent = channels, categories = categories, selectedCategory = null)
                    else     -> current.copy(liveChannels = channels, categories = categories, selectedCategory = null)
                }
                _uiState.value = updated
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(e.message ?: "Failed to switch tab")
            }
        }
    }

    /**
     * Toggle the favorite flag of a channel and refresh the favorites section.
     */
    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    channelDao.updateFavorite(channel.id, !channel.isFavorite)
                }
                // Refresh favorites list in current success state.
                val current = _uiState.value as? HomeUiState.Success ?: return@launch
                val favorites = withContext(Dispatchers.IO) { channelDao.getFavorites(_currentPlaylistId.value.toInt()) }
                _uiState.value = current.copy(favorites = favorites)
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(e.message ?: "Failed to toggle favorite")
            }
        }
    }

    /**
     * Record that the user clicked a channel so it appears in "Continue Watching".
     */
    fun onChannelClicked(channel: Channel) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    channelDao.updateLastWatched(channel.id, System.currentTimeMillis())
                }
                // Refresh continueWatching section.
                val current = _uiState.value as? HomeUiState.Success ?: return@launch
                val recent = withContext(Dispatchers.IO) { channelDao.getRecent(_currentPlaylistId.value.toInt()) }
                _uiState.value = current.copy(continueWatching = recent)
            } catch (e: Exception) {
                // Non-critical — swallow silently so playback is not interrupted.
            }
        }
    }
}
