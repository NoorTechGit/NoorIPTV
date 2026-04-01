package com.salliptv.player.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "channels",
    indices = [
        Index("groupTitle"),
        Index("playlistId"),
        Index("isFavorite"),
        Index("type"),
        Index(value = ["playlistId", "type"]),
        Index(value = ["playlistId", "type", "groupTitle"])
    ]
)
data class Channel(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,
    var streamId: Int = 0,
    var name: String? = null,
    var logoUrl: String? = null,
    var groupTitle: String? = null,
    var streamUrl: String? = null,
    var channelNumber: Int = 0,
    var isFavorite: Boolean = false,
    var playlistId: Int = 0,
    var type: String? = null,        // "LIVE", "VOD", "SERIES"
    var lastWatched: Long = 0L,
    var hidden: Boolean = false,     // filtered out by smart filter
    var countryPrefix: String? = null // detected country code (FR, AR, US, etc.)
)
