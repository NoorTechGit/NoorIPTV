package com.salliptv.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.salliptv.player.model.Category
import com.salliptv.player.model.Channel
import com.salliptv.player.model.Playlist

@Database(entities = [Playlist::class, Channel::class, Category::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "salliptv.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
