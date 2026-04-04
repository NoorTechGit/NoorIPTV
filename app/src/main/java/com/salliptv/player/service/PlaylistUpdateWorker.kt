package com.salliptv.player.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.model.Playlist
import com.salliptv.player.parser.XtreamApi
import com.salliptv.player.repository.PlaylistManager
import kotlinx.coroutines.flow.collect
import java.util.concurrent.TimeUnit

class PlaylistUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "PlaylistUpdate"
        const val WORK_NAME = "playlist_auto_update"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<PlaylistUpdateWorker>(
                24, TimeUnit.HOURS,
                6, TimeUnit.HOURS // flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.d(TAG, "Scheduled daily playlist update")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting playlist update check")

        return try {
            val db = AppDatabase.getInstance(applicationContext)
            val playlists = db.playlistDao().getAll()

            for (pl in playlists) {
                val needsUpdate = checkIfUpdateNeeded(pl)
                if (needsUpdate) {
                    Log.d(TAG, "Updating playlist: ${pl.name}")
                    updatePlaylist(pl, db)
                } else {
                    Log.d(TAG, "Playlist ${pl.name} is up to date")
                }
            }

            // Mark that an update occurred so MainActivity can show badge
            showUpdateNotification()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Update failed", e)
            Result.retry()
        }
    }

    private suspend fun checkIfUpdateNeeded(playlist: Playlist): Boolean {
        val lastUpdate = playlist.lastUpdated
        val oneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000

        // Always update if never updated or older than 24h
        if (lastUpdate == 0L || lastUpdate < oneDayAgo) return true

        // For Xtream: ping the server to confirm it's alive and consider a refresh
        if (playlist.type == "XTREAM") {
            val server = playlist.getServerBase() ?: return false
            val username = playlist.username ?: return false
            val password = playlist.password ?: return false
            return try {
                val api = XtreamApi(server, username, password)
                val loginResult = api.login()
                // Server responded — check if data is stale (older than 24h)
                loginResult != null && lastUpdate < oneDayAgo
            } catch (_: Exception) {
                false
            }
        }

        // For M3U: check HTTP Last-Modified header against our last update
        val url = playlist.url ?: return false
        return try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.setRequestProperty("User-Agent", "VLC/3.0.18")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.connect()

            val lastModified = connection.lastModified
            connection.disconnect()

            // Update if server's Last-Modified is newer than our last refresh
            lastModified > 0 && lastModified > lastUpdate
        } catch (_: Exception) {
            // Can't check — fall back to time-based rule
            lastUpdate < oneDayAgo
        }
    }

    private suspend fun updatePlaylist(playlist: Playlist, db: AppDatabase) {
        val url = playlist.url ?: run {
            Log.w(TAG, "Playlist ${playlist.name} has no URL, skipping")
            return
        }

        val manager = PlaylistManager(applicationContext, db.channelDao())

        // Delete old channels for this playlist
        db.channelDao().deleteByPlaylist(playlist.id)

        // Re-download and parse via PlaylistManager (backend + local fallback)
        manager.addPlaylist(url, playlist.id).collect { state ->
            when (state) {
                is PlaylistManager.State.Success -> {
                    db.playlistDao().updateTimestamp(playlist.id, System.currentTimeMillis())
                    Log.d(TAG, "Updated ${playlist.name}: ${state.totalChannels} channels")
                }
                is PlaylistManager.State.Error -> {
                    Log.e(TAG, "Failed to update ${playlist.name}: ${state.message}")
                }
                else -> {
                    // Progress/intermediate states — no-op in background
                }
            }
        }
    }

    /**
     * Writes a flag to SharedPreferences instead of posting a system notification.
     * MainActivity reads this on resume and shows a short Toast badge, then clears it.
     */
    private fun showUpdateNotification() {
        val prefs = applicationContext.getSharedPreferences("salliptv_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("playlist_updated", true)
            .putLong("last_auto_update", System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Update complete — badge flag set in SharedPreferences")
    }
}
