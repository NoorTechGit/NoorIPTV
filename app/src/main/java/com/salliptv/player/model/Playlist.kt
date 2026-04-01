package com.salliptv.player.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,
    var name: String? = null,
    var type: String? = null,     // "M3U" or "XTREAM"
    var url: String? = null,      // M3U URL or Xtream server URL
    var username: String? = null,
    var password: String? = null,
    var epgUrl: String? = null,
    var lastUpdated: Long = 0L
) {
    fun getServerBase(): String? {
        if (type != "XTREAM" || url == null) return null
        return if (url!!.endsWith("/")) url!!.substring(0, url!!.length - 1) else url
    }
}
