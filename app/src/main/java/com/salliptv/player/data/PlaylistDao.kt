package com.salliptv.player.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.salliptv.player.model.Playlist
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY name")
    fun getAll(): List<Playlist>

    @Query("SELECT * FROM playlists ORDER BY name")
    fun getAllFlow(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun getById(id: Int): Playlist?

    @Insert
    suspend fun insert(playlist: Playlist): Long

    @Delete
    suspend fun delete(playlist: Playlist)

    @Query("UPDATE playlists SET lastUpdated = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: Int, timestamp: Long)
}
