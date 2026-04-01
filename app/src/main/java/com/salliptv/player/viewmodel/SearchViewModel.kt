package com.salliptv.player.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val channelDao = db.channelDao()

    /** The current search query, updated as the user types. */
    val query: MutableStateFlow<String> = MutableStateFlow("")

    private val _searchResults = MutableStateFlow<List<Channel>>(emptyList())
    val searchResults: StateFlow<List<Channel>> = _searchResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Active playlist scope for searches. 0 means search across all playlists.
    private var activePlaylistId: Int = 0

    init {
        observeQueryChanges()
    }

    /**
     * Wire up a debounced reactive search so the DAO is not hit on every keystroke.
     * The search triggers automatically when [query] changes.
     */
    @OptIn(FlowPreview::class)
    private fun observeQueryChanges() {
        viewModelScope.launch {
            query
                .debounce(300L)
                .distinctUntilChanged()
                .collect { q ->
                    performSearch(q, activePlaylistId)
                }
        }
    }

    /**
     * Manually trigger a search with an explicit query and playlist scope.
     * Also updates [query] so the search field stays in sync.
     */
    fun search(query: String, playlistId: Long) {
        activePlaylistId = playlistId.toInt()
        this.query.value = query
        // Bypass debounce for programmatic calls (e.g. voice search).
        viewModelScope.launch {
            performSearch(query, playlistId.toInt())
        }
    }

    /** Clear the current search results and reset the query. */
    fun clearSearch() {
        query.value = ""
        _searchResults.value = emptyList()
    }

    private suspend fun performSearch(q: String, playlistId: Int) {
        if (q.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        _isLoading.value = true
        try {
            val results = withContext(Dispatchers.IO) {
                channelDao.search(q.trim(), playlistId)
            }
            _searchResults.value = results
        } catch (e: Exception) {
            _searchResults.value = emptyList()
        } finally {
            _isLoading.value = false
        }
    }
}
