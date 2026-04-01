package com.salliptv.player.model

data class EpgProgram(
    var channelId: String? = null,
    var title: String? = null,
    var description: String? = null,
    var startTime: Long = 0L,
    var endTime: Long = 0L
) {
    fun isCurrentlyAiring(): Boolean {
        val now = System.currentTimeMillis() / 1000
        return now >= startTime && now <= endTime
    }
}
