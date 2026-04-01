package com.salliptv.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.salliptv.player.model.Category
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE playlistId = :playlistId AND type = :type ORDER BY categoryName")
    fun getByType(playlistId: Int, type: String): List<Category>

    @Query("SELECT * FROM categories WHERE playlistId = :playlistId AND type = :type ORDER BY categoryName")
    fun getByTypeFlow(playlistId: Int, type: String): Flow<List<Category>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<Category>)

    @Query("DELETE FROM categories WHERE playlistId = :playlistId")
    suspend fun deleteByPlaylist(playlistId: Int)
}
