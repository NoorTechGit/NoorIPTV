package com.salliptv.player.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,
    var categoryId: String? = null,
    var categoryName: String? = null,
    var type: String? = null,    // "LIVE", "VOD", "SERIES"
    var playlistId: Int = 0
)
