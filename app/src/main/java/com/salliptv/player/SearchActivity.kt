package com.salliptv.player

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.databinding.ActivitySearchBinding
import com.salliptv.player.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var db: AppDatabase
    private var playlistId: Int = -1
    private var isPremium: Boolean = false
    private var searchJob: Job? = null
    private val adapter = SearchAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)
        playlistId = intent.getIntExtra("playlistId", -1)
        isPremium = intent.getBooleanExtra("isPremium", false)

        binding.rvSearchResults.layoutManager = GridLayoutManager(this, 4)
        binding.rvSearchResults.adapter = adapter

        // Live search with 300 ms debounce implemented via coroutine delay
        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: ""
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300L)
                    performSearch(query)
                }
            }
        })

        binding.etSearch.requestFocus()
    }

    private suspend fun performSearch(query: String) {
        if (query.isEmpty()) {
            adapter.setResults(emptyList())
            binding.tvResultCount.text = ""
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvSearchResults.visibility = View.GONE
            return
        }

        val results = withContext(Dispatchers.IO) {
            db.channelDao().search(query, playlistId)
        }

        adapter.setResults(results)
        binding.tvResultCount.text = "${results.size} results"
        if (results.isEmpty()) {
            binding.tvEmpty.setText(R.string.no_channels)
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvSearchResults.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvSearchResults.visibility = View.VISIBLE
        }
    }

    private fun openChannel(channel: Channel, position: Int) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("channelId", channel.id)
            putExtra("channelName", channel.name)
            putExtra("channelLogo", channel.logoUrl)
            putExtra("channelNumber", channel.channelNumber)
            putExtra("streamUrl", channel.streamUrl)
            putExtra("streamId", channel.streamId)
            putExtra("currentPosition", position)
            putExtra("playlistId", playlistId)
            putExtra("isPremium", isPremium)
            putExtra("groupTitle", channel.groupTitle)
            putExtra("channelType", channel.type)
        }
        startActivity(intent)
    }

    // ==========================================
    // SEARCH ADAPTER
    // ==========================================

    private inner class SearchAdapter : RecyclerView.Adapter<SearchAdapter.VH>() {
        private var items: List<Channel> = emptyList()

        fun setResults(results: List<Channel>) {
            items = results
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = items[position]
            h.tvName.text = ch.name

            if (!ch.logoUrl.isNullOrEmpty()) {
                Glide.with(h.ivLogo.context).load(ch.logoUrl).centerInside().into(h.ivLogo)
            } else {
                h.ivLogo.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            h.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.05f else 1f)
                    .scaleY(if (hasFocus) 1.05f else 1f)
                    .setDuration(150)
                    .start()
            }

            h.itemView.setOnClickListener { openChannel(ch, position) }
        }

        override fun getItemCount(): Int = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivLogo: ImageView = v.findViewById(R.id.iv_logo)
            val tvName: TextView = v.findViewById(R.id.tv_name)
        }
    }
}
