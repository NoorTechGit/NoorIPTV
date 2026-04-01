package com.salliptv.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.salliptv.player.adapter.ChannelAdapter
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CategoryDetailActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var channelAdapter: ChannelAdapter
    private var playlistId = -1
    private var type = "VOD"
    private var group = ""
    private var allChannels: List<Channel> = emptyList()

    // Search/filter
    private var searchFilterBar: LinearLayout? = null
    private var etFilter: EditText? = null
    private var btnClearFilter: ImageView? = null
    private var filterHandler = Handler(Looper.getMainLooper())
    private var filterRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_detail)

        db = AppDatabase.getInstance(this)

        group = intent.getStringExtra("group") ?: ""
        type = intent.getStringExtra("type") ?: "VOD"
        playlistId = intent.getIntExtra("playlistId", -1)
        val title = intent.getStringExtra("title") ?: group

        val tvTitle = findViewById<TextView>(R.id.tv_title)
        val tvCount = findViewById<TextView>(R.id.tv_count)
        val btnBack = findViewById<ImageView>(R.id.btn_back)
        val rvGrid = findViewById<RecyclerView>(R.id.rv_grid)

        tvTitle.text = title

        btnBack.setOnClickListener { finish() }
        btnBack.setOnFocusChangeListener { v, hasFocus ->
            v.alpha = if (hasFocus) 1f else 0.6f
        }

        // Setup grid
        val isPoster = type == "VOD" || type == "SERIES"
        val spanCount = if (isPoster) 6 else 4

        channelAdapter = ChannelAdapter()
        if (isPoster) {
            channelAdapter.setPosterMode(true)
        }

        rvGrid.layoutManager = GridLayoutManager(this, spanCount)
        rvGrid.adapter = channelAdapter

        channelAdapter.setOnChannelClickListener { channel, _ ->
            openChannel(channel)
        }
        channelAdapter.setOnChannelLongClickListener { channel, _ ->
            toggleFavorite(channel)
        }

        // Search/filter bar
        searchFilterBar = findViewById(R.id.search_filter_bar_detail)
        etFilter = findViewById(R.id.et_filter_detail)
        btnClearFilter = findViewById(R.id.btn_clear_filter_detail)
        initDetailSearchBar()

        // Load channels
        lifecycleScope.launch(Dispatchers.IO) {
            val channels = db.channelDao().getByGroup(playlistId, group, type)
            allChannels = channels
            withContext(Dispatchers.Main) {
                channelAdapter.setChannels(channels)
                tvCount.text = "${channels.size} éléments"
            }
        }
    }

    private fun openChannel(channel: Channel) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.channelDao().updateLastWatched(channel.id, System.currentTimeMillis())
        }

        if (type == "VOD" || type == "SERIES") {
            val intent = Intent(this, MovieDetailActivity::class.java).apply {
                putExtra("channelId", channel.id)
                putExtra("channelName", channel.name)
                putExtra("channelLogo", channel.logoUrl)
                putExtra("streamUrl", channel.streamUrl)
                putExtra("streamId", channel.streamId)
                putExtra("playlistId", playlistId)
                putExtra("channelType", type)
                putExtra("groupTitle", channel.groupTitle ?: group)
                putExtra("isFavorite", channel.isFavorite)
            }
            // Add Xtream extras
            lifecycleScope.launch(Dispatchers.IO) {
                val playlist = db.playlistDao().getAll().firstOrNull { it.id == playlistId }
                if (playlist?.type == "XTREAM") {
                    intent.putExtra("xtream_server", playlist.url ?: "")
                    intent.putExtra("xtream_user", playlist.username ?: "")
                    intent.putExtra("xtream_pass", playlist.password ?: "")
                }
                withContext(Dispatchers.Main) { startActivity(intent) }
            }
        } else {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("channel_name", channel.name)
                putExtra("channel_url", channel.streamUrl)
                putExtra("channel_logo", channel.logoUrl)
                putExtra("channel_id", channel.id)
                putExtra("playlist_id", playlistId)
                putExtra("type", type)
                putExtra("group", group)
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val playlist = db.playlistDao().getAll().firstOrNull { it.id == playlistId }
                if (playlist?.type == "XTREAM") {
                    intent.putExtra("xtream_server", playlist.url ?: "")
                    intent.putExtra("xtream_user", playlist.username ?: "")
                    intent.putExtra("xtream_pass", playlist.password ?: "")
                }
                withContext(Dispatchers.Main) { startActivity(intent) }
            }
        }
    }

    private fun toggleFavorite(channel: Channel) {
        val newFav = !channel.isFavorite
        lifecycleScope.launch(Dispatchers.IO) {
            db.channelDao().updateFavorite(channel.id, newFav)
            val channels = db.channelDao().getByGroup(playlistId, group, type)
            allChannels = channels
            withContext(Dispatchers.Main) {
                // Re-apply current filter if any
                val query = etFilter?.text?.toString()?.trim() ?: ""
                if (query.isNotEmpty()) {
                    applyDetailFilter(query)
                } else {
                    channelAdapter.setChannels(channels)
                }
                Toast.makeText(
                    this@CategoryDetailActivity,
                    if (newFav) getString(R.string.added_favorite) else getString(R.string.removed_favorite),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun initDetailSearchBar() {
        val et = etFilter ?: return
        val btnClear = btnClearFilter ?: return

        btnClear.setOnClickListener {
            et.text.clear()
            btnClear.visibility = View.GONE
            applyDetailFilter("")
        }

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                btnClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                filterRunnable?.let { filterHandler.removeCallbacks(it) }
                filterRunnable = Runnable { applyDetailFilter(query) }
                filterHandler.postDelayed(filterRunnable!!, 300)
            }
        })
    }

    fun showDetailSearchBar() {
        val bar = searchFilterBar ?: return
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        bar.animate().alpha(1f).setDuration(200).start()
        etFilter?.requestFocus()
    }

    private fun applyDetailFilter(query: String) {
        if (query.isEmpty()) {
            channelAdapter.setChannels(allChannels)
        } else {
            val q = query.lowercase(Locale.getDefault())
            channelAdapter.setChannels(allChannels.filter { ch ->
                ch.name.lowercase(Locale.getDefault()).contains(q) ||
                (ch.groupTitle?.lowercase(Locale.getDefault())?.contains(q) == true)
            })
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
