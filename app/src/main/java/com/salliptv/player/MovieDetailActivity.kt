package com.salliptv.player

import android.content.Intent
import android.os.Bundle
import com.salliptv.player.BuildConfig
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.bumptech.glide.Glide
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.salliptv.player.adapter.ChannelAdapter
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.model.Channel
import com.salliptv.player.parser.XtreamApi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.net.URLEncoder
import java.util.Locale

class MovieDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MovieDetail"
    }

    private lateinit var ivBackdrop: ImageView
    private lateinit var ivPoster: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvYear: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvRating: TextView
    private lateinit var tvGenre: TextView
    private lateinit var tvPlot: TextView
    private lateinit var tvCast: TextView
    private lateinit var tvDirector: TextView
    private lateinit var btnPlay: TextView
    private lateinit var btnFavorite: TextView
    private lateinit var dotRating: View
    private lateinit var progress: ProgressBar

    // Similar movies
    private lateinit var tvSimilarTitle: TextView
    private lateinit var rvSimilar: RecyclerView

    // Series: seasons/episodes
    private lateinit var layoutSeasons: LinearLayout
    private lateinit var tvSeasonTitle: TextView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var llSeasonTabs: LinearLayout

    private var btnTrailer: TextView? = null
    private var trailerSearchQuery: String? = null
    private var trailerContainer: FrameLayout? = null
    private var wvTrailer: WebView? = null
    private var btnCloseTrailer: TextView? = null
    private var isTrailerPlaying = false

    private lateinit var db: AppDatabase
    private var api: XtreamApi? = null

    private var streamId = 0
    private var playlistId = -1
    private var channelType: String? = null
    private var channelName: String? = null
    private var channelLogo: String? = null
    private var streamUrl: String? = null
    private var groupTitle: String? = null
    private var channelId = -1
    private var isPremium = false
    private var isFavorite = false

    // Series episode data
    private val allEpisodes = mutableListOf<JsonObject>()
    private var allSeasons: JsonArray? = null
    private var selectedSeason = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movie_detail)

        db = AppDatabase.getInstance(this)
        initViews()
        extractIntent()
        initXtreamApi()
        showBasicInfo()
        loadDetails()
    }

    private fun initViews() {
        ivBackdrop = findViewById(R.id.iv_backdrop)
        ivPoster = findViewById(R.id.iv_poster)
        tvTitle = findViewById(R.id.tv_title)
        tvYear = findViewById(R.id.tv_year)
        tvDuration = findViewById(R.id.tv_duration)
        tvRating = findViewById(R.id.tv_rating)
        tvGenre = findViewById(R.id.tv_genre)
        tvPlot = findViewById(R.id.tv_plot)
        tvCast = findViewById(R.id.tv_cast)
        tvDirector = findViewById(R.id.tv_director)
        dotRating = findViewById(R.id.dot_rating)
        btnPlay = findViewById(R.id.btn_play)
        btnFavorite = findViewById(R.id.btn_favorite)
        progress = findViewById(R.id.progress)
        tvSimilarTitle = findViewById(R.id.tv_similar_title)
        rvSimilar = findViewById(R.id.rv_similar)
        layoutSeasons = findViewById(R.id.layout_seasons)
        tvSeasonTitle = findViewById(R.id.tv_season_title)
        rvEpisodes = findViewById(R.id.rv_episodes)
        llSeasonTabs = findViewById(R.id.ll_season_tabs)

        btnPlay.text = "\u25B6  ${getString(R.string.action_play, "")}"
        btnPlay.setOnClickListener { launchPlayer() }
        btnPlay.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .setDuration(150).start()
        }

        btnTrailer = findViewById(R.id.btn_trailer)
        trailerContainer = findViewById(R.id.trailer_container)
        wvTrailer = findViewById(R.id.wv_trailer)
        btnCloseTrailer = findViewById(R.id.btn_close_trailer)

        btnTrailer?.apply {
            text = "\uD83C\uDFAC  ${getString(R.string.trailer)}"
            setOnClickListener { openTrailer() }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.06f else 1f)
                    .scaleY(if (hasFocus) 1.06f else 1f)
                    .setDuration(150).start()
            }
        }

        btnCloseTrailer?.setOnClickListener { closeTrailer() }

        wvTrailer?.let { wv ->
            val ws: WebSettings = wv.settings
            ws.javaScriptEnabled = true
            ws.mediaPlaybackRequiresUserGesture = false
            ws.domStorageEnabled = true
            wv.webChromeClient = WebChromeClient()
            wv.webViewClient = WebViewClient()
        }

        btnFavorite.setOnClickListener { toggleFavorite() }
        btnFavorite.setOnFocusChangeListener { v, hasFocus ->
            v.animate().scaleX(if (hasFocus) 1.1f else 1f)
                .scaleY(if (hasFocus) 1.1f else 1f)
                .setDuration(150).start()
        }
    }

    private fun extractIntent() {
        streamId = intent.getIntExtra("streamId", 0)
        playlistId = intent.getIntExtra("playlistId", -1)
        channelType = intent.getStringExtra("channelType")
        channelName = intent.getStringExtra("channelName")
        channelLogo = intent.getStringExtra("channelLogo")
        streamUrl = intent.getStringExtra("streamUrl")
        groupTitle = intent.getStringExtra("groupTitle")
        channelId = intent.getIntExtra("channelId", -1)
        isPremium = intent.getBooleanExtra("isPremium", false)
        isFavorite = intent.getBooleanExtra("isFavorite", false)
    }

    private fun showBasicInfo() {
        tvTitle.text = channelName
        updateFavoriteUI()

        if (!channelLogo.isNullOrEmpty()) {
            Glide.with(this).load(channelLogo).centerCrop().into(ivPoster)
            Glide.with(this).load(channelLogo).centerCrop().into(ivBackdrop)
        }

        btnPlay.text = if (channelType == "SERIES") {
            "\u25B6  ${getString(R.string.play_series)}"
        } else {
            "\u25B6  ${getString(R.string.play_movie)}"
        }

        setupTrailerButton(channelName)

        // Load full channel data from Room (has TMDB/provider enriched data)
        if (channelId > 0) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ch = db.channelDao().getById(channelId)
                if (ch != null) {
                    withContext(Dispatchers.Main) {
                        // Poster HD
                        val poster = ch.posterUrl ?: ch.backdropUrl ?: ch.logoUrl
                        if (!poster.isNullOrEmpty()) {
                            Glide.with(this@MovieDetailActivity).load(poster).centerCrop().into(ivPoster)
                        }
                        val backdrop = ch.backdropUrl ?: ch.posterUrl
                        if (!backdrop.isNullOrEmpty()) {
                            Glide.with(this@MovieDetailActivity).load(backdrop).centerCrop().into(ivBackdrop)
                        }

                        // Title
                        tvTitle.text = ch.cleanName ?: ch.name

                        // Year
                        if (!ch.releaseDate.isNullOrEmpty()) {
                            tvYear.text = ch.releaseDate
                            tvYear.visibility = View.VISIBLE
                        }

                        // Rating
                        if (!ch.rating.isNullOrEmpty() && ch.rating != "0" && ch.rating != "0.0") {
                            tvRating.text = "★ ${ch.rating}"
                            tvRating.visibility = View.VISIBLE
                            dotRating.visibility = View.VISIBLE
                        }

                        // Genre
                        if (!ch.genre.isNullOrEmpty()) {
                            tvGenre.text = ch.genre
                            tvGenre.visibility = View.VISIBLE
                        }

                        // Plot
                        if (!ch.plot.isNullOrEmpty()) {
                            tvPlot.text = ch.plot
                            tvPlot.visibility = View.VISIBLE
                        }

                        // Cast
                        if (!ch.cast.isNullOrEmpty()) {
                            tvCast.text = "${getString(R.string.detail_cast)} ${ch.cast}"
                            tvCast.visibility = View.VISIBLE
                        }

                        // Director
                        if (!ch.director.isNullOrEmpty()) {
                            tvDirector.text = "${getString(R.string.detail_director)} ${ch.director}"
                            tvDirector.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun initXtreamApi() {
        lifecycleScope.launch(Dispatchers.IO) {
            val playlists = db.playlistDao().getAll()
            if (playlists.isNotEmpty()) {
                val pl = playlists[0]
                if (pl.type == "XTREAM") {
                    api = XtreamApi(pl.url ?: "", pl.username ?: "", pl.password ?: "")
                }
            }
        }
    }

    private fun loadDetails() {
        lifecycleScope.launch {
            // Wait for API init
            var tries = 0
            while (api == null && tries < 20) {
                delay(100)
                tries++
            }

            if (api == null) {
                progress.visibility = View.GONE
                btnPlay.requestFocus()
                return@launch
            }

            try {
                if (channelType == "SERIES") {
                    loadSeriesDetails()
                } else {
                    loadVodDetails()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading details: ${e.message}")
                progress.visibility = View.GONE
                btnPlay.requestFocus()
            }
        }
    }

    private suspend fun loadVodDetails() {
        val info = withContext(Dispatchers.IO) { api?.getVodInfo(streamId) }
        if (info == null) {
            progress.visibility = View.GONE
            btnPlay.requestFocus()
            return
        }

        val newUrl = getJsonStr(info, "streamUrl")
        if (!newUrl.isNullOrEmpty()) streamUrl = newUrl

        val categoryId = getJsonStr(info, "categoryId")

        // Try to get translated synopsis from TMDB
        val tmdbId = getJsonStr(info, "tmdbId")
        if (!tmdbId.isNullOrEmpty()) {
            try {
                withContext(Dispatchers.IO) {
                    val lang = Locale.getDefault().language
                    val tmdbUrl = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=${BuildConfig.TMDB_API_KEY}&language=$lang"
                    val client = okhttp3.OkHttpClient()
                    val resp = client.newCall(okhttp3.Request.Builder().url(tmdbUrl).build()).execute()
                    if (resp.isSuccessful && resp.body != null) {
                        val tmdb = com.google.gson.JsonParser.parseString(resp.body!!.string()).asJsonObject
                        val localPlot = if (tmdb.has("overview") && !tmdb.get("overview").isJsonNull)
                            tmdb.get("overview").asString else null
                        if (!localPlot.isNullOrEmpty()) info.addProperty("plot", localPlot)

                        val localTitle = if (tmdb.has("title") && !tmdb.get("title").isJsonNull)
                            tmdb.get("title").asString else null
                        if (!localTitle.isNullOrEmpty()) info.addProperty("localTitle", localTitle)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TMDB fetch error: ${e.message}")
            }
        }

        progress.visibility = View.GONE
        populateInfo(info)
        btnPlay.requestFocus()

        if (!categoryId.isNullOrEmpty()) {
            try {
                val similar = withContext(Dispatchers.IO) {
                    api?.getSimilarVod(categoryId, playlistId, streamId) ?: emptyList()
                }
                showSimilar(similar)
            } catch (e: Exception) {
                Log.e(TAG, "Similar load error: ${e.message}")
            }
        }
    }

    private suspend fun loadSeriesDetails() {
        val info = withContext(Dispatchers.IO) { api?.getSeriesInfo(streamId) }
        if (info == null) {
            progress.visibility = View.GONE
            btnPlay.requestFocus()
            return
        }

        progress.visibility = View.GONE
        populateInfo(info)

        if (info.has("seasons")) {
            showSeasons(info.getAsJsonArray("seasons"))
        }
        btnPlay.requestFocus()
    }

    private fun populateInfo(info: JsonObject) {
        val title = getJsonStr(info, "title")
        val localTitle = getJsonStr(info, "localTitle")
        when {
            !localTitle.isNullOrEmpty() -> {
                tvTitle.text = localTitle
                setupTrailerButton(localTitle)
            }
            !title.isNullOrEmpty() -> {
                tvTitle.text = title
                setupTrailerButton(title)
            }
        }

        val plot = getJsonStr(info, "plot")
        if (!plot.isNullOrEmpty()) {
            tvPlot.text = plot
            tvPlot.visibility = View.VISIBLE
        }

        var year = getJsonStr(info, "year") ?: getJsonStr(info, "releaseDate")
        if (!year.isNullOrEmpty()) {
            if (year.length > 4) year = year.substring(0, 4)
            tvYear.text = year
            tvYear.visibility = View.VISIBLE
        }

        val duration = getJsonStr(info, "duration")
        if (!duration.isNullOrEmpty()) {
            if (!year.isNullOrEmpty()) {
                findViewById<View>(R.id.dot1)?.visibility = View.VISIBLE
            }
            tvDuration.text = duration
            tvDuration.visibility = View.VISIBLE
        }

        val rating = getJsonStr(info, "rating")
        if (!rating.isNullOrEmpty() && rating != "0" && rating != "0.0") {
            tvRating.text = "\u2605 $rating"
            tvRating.visibility = View.VISIBLE
            dotRating.visibility = View.VISIBLE
        }

        val genre = getJsonStr(info, "genre")
        if (!genre.isNullOrEmpty()) {
            tvGenre.text = genre
            tvGenre.visibility = View.VISIBLE
        }

        val cast = getJsonStr(info, "cast")
        if (!cast.isNullOrEmpty()) {
            tvCast.text = "${getString(R.string.detail_cast)} $cast"
            tvCast.visibility = View.VISIBLE
        }

        val director = getJsonStr(info, "director")
        if (!director.isNullOrEmpty()) {
            tvDirector.text = "${getString(R.string.detail_director)} $director"
            tvDirector.visibility = View.VISIBLE
        }

        val backdrop = getJsonStr(info, "backdrop")
        if (!backdrop.isNullOrEmpty()) {
            val bdUrl = if (backdrop.startsWith("http")) backdrop else "https://image.tmdb.org/t/p/w780$backdrop"
            Glide.with(this).load(bdUrl).centerCrop().into(ivBackdrop)
        }

        val cover = getJsonStr(info, "cover")
        if (!cover.isNullOrEmpty()) {
            Glide.with(this).load(cover).centerCrop().into(ivPoster)
        }
    }

    private fun showSeasons(seasons: JsonArray?) {
        if (seasons == null || seasons.size() == 0) return
        allSeasons = seasons
        layoutSeasons.visibility = View.VISIBLE

        llSeasonTabs.removeAllViews()
        for (s in 0 until seasons.size()) {
            val season = seasons.get(s).asJsonObject
            val num = if (season.has("seasonNumber")) season.get("seasonNumber").asString else (s + 1).toString()

            val tab = TextView(this).apply {
                text = "${getString(R.string.season_label)} $num"
                textSize = 14f
                isFocusable = true
                    isFocusableInTouchMode = true
                isClickable = true
                setPadding(24, 12, 24, 12)
            }

            val seasonIdx = s
            tab.setOnClickListener { selectSeason(seasonIdx) }

            if (s == 0) {
                tab.setBackgroundResource(R.drawable.bg_chip_selected)
                tab.setTextColor(0xFFFFFFFF.toInt())
            } else {
                tab.setBackgroundResource(R.drawable.bg_chip)
                tab.setTextColor(0xFF8E8E93.toInt())
            }
            tab.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1f)
                    .scaleY(if (hasFocus) 1.05f else 1f)
                    .setDuration(150).start()
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 8
            llSeasonTabs.addView(tab, lp)
        }

        selectSeason(0)
    }

    private fun selectSeason(seasonIdx: Int) {
        val seasons = allSeasons ?: return
        if (seasonIdx >= seasons.size()) return
        selectedSeason = seasonIdx

        for (i in 0 until llSeasonTabs.childCount) {
            val tab = llSeasonTabs.getChildAt(i) as? TextView ?: continue
            if (i == seasonIdx) {
                tab.setBackgroundResource(R.drawable.bg_chip_selected)
                tab.setTextColor(0xFFFFFFFF.toInt())
            } else {
                tab.setBackgroundResource(R.drawable.bg_chip)
                tab.setTextColor(0xFF8E8E93.toInt())
            }
        }

        val season = seasons.get(seasonIdx).asJsonObject
        val eps = if (season.has("episodes")) season.getAsJsonArray("episodes") else JsonArray()

        allEpisodes.clear()
        for (i in 0 until eps.size()) {
            allEpisodes.add(eps.get(i).asJsonObject)
        }

        rvEpisodes.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvEpisodes.adapter = EpisodeCardAdapter(allEpisodes) { pos ->
            if (pos < allEpisodes.size) {
                val epUrl = getJsonStr(allEpisodes[pos], "streamUrl")
                if (epUrl != null) {
                    streamUrl = epUrl
                    val epNum = if (allEpisodes[pos].has("episodeNum"))
                        allEpisodes[pos].get("episodeNum").asInt else (pos + 1)
                    channelName = "${tvTitle.text} S${selectedSeason + 1}E$epNum"
                    launchPlayer()
                }
            }
        }

        if (allEpisodes.isNotEmpty()) {
            val firstEpUrl = getJsonStr(allEpisodes[0], "streamUrl")
            if (firstEpUrl != null) streamUrl = firstEpUrl
            val firstEpNum = if (allEpisodes[0].has("episodeNum")) allEpisodes[0].get("episodeNum").asInt else 1
            btnPlay.text = "\u25B6  ${getString(R.string.play_episode, firstEpNum)}"
        }
    }

    private fun showSimilar(similar: List<Channel>) {
        if (similar.isEmpty()) return

        tvSimilarTitle.text = getString(R.string.more_like_this)
        tvSimilarTitle.visibility = View.VISIBLE
        rvSimilar.visibility = View.VISIBLE

        val adapter = ChannelAdapter()
        adapter.setPosterMode(true)
        adapter.setChannels(similar)
        adapter.setOnChannelClickListener { channel, _ ->
            val intent = Intent(this, MovieDetailActivity::class.java)
            intent.putExtra("streamId", channel.streamId)
            intent.putExtra("channelName", channel.name)
            intent.putExtra("channelLogo", channel.logoUrl)
            intent.putExtra("streamUrl", channel.streamUrl)
            intent.putExtra("playlistId", playlistId)
            intent.putExtra("channelType", "VOD")
            intent.putExtra("isPremium", isPremium)
            intent.putExtra("channelId", channel.id)
            intent.putExtra("groupTitle", channel.groupTitle)
            startActivity(intent)
            finish()
        }

        rvSimilar.layoutManager = GridLayoutManager(this, 6)
        rvSimilar.adapter = adapter
    }

    private fun launchPlayer() {
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra("streamUrl", streamUrl)
        intent.putExtra("channelId", channelId)
        intent.putExtra("channelName", channelName)
        intent.putExtra("channelLogo", channelLogo)
        intent.putExtra("streamId", streamId)
        intent.putExtra("playlistId", playlistId)
        intent.putExtra("isPremium", isPremium)
        intent.putExtra("groupTitle", groupTitle)
        intent.putExtra("channelType", channelType)
        startActivity(intent)
    }

    private fun toggleFavorite() {
        isFavorite = !isFavorite
        updateFavoriteUI()
        if (channelId > 0) {
            lifecycleScope.launch(Dispatchers.IO) {
                db.channelDao().updateFavorite(channelId, isFavorite)
            }
        }
        Toast.makeText(
            this,
            if (isFavorite) getString(R.string.added_favorite) else getString(R.string.removed_favorite),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateFavoriteUI() {
        btnFavorite.text = if (isFavorite) "\u2665" else "\u2661"
        btnFavorite.setTextColor(if (isFavorite) 0xFFFF375F.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun openTrailer() {
        val query = trailerSearchQuery
        if (query.isNullOrEmpty()) return
        val wv = wvTrailer ?: return
        val container = trailerContainer ?: return

        try {
            val lang = Locale.getDefault().language
            val trailerWord = getString(R.string.trailer)
            val encoded = URLEncoder.encode("$query $trailerWord", "UTF-8")

            container.visibility = View.VISIBLE
            container.alpha = 0f
            container.animate().alpha(1f).setDuration(300).start()
            isTrailerPlaying = true
            btnCloseTrailer?.requestFocus()

            val ytUrl = "https://m.youtube.com/results?search_query=$encoded&hl=$lang"
            wv.loadUrl(ytUrl)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot play trailer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeTrailer() {
        wvTrailer?.loadUrl("about:blank")
        trailerContainer?.animate()?.alpha(0f)?.setDuration(200)
            ?.withEndAction { trailerContainer?.visibility = View.GONE }?.start()
        isTrailerPlaying = false
        btnPlay.requestFocus()
    }

    private fun setupTrailerButton(title: String?) {
        if (title.isNullOrEmpty() || btnTrailer == null) return

        val cleanTitle = title
            .replace(Regex("^[A-Z]{2,3}\\s*[-:|]\\s*"), "")
            .replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
            .trim()

        trailerSearchQuery = cleanTitle
        btnTrailer?.visibility = View.VISIBLE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val keyCode = event.keyCode

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isTrailerPlaying) { closeTrailer(); return true }
            finish()
            return true
        }

        if (isTrailerPlaying) return super.dispatchKeyEvent(event)

        val focused = currentFocus
        if (focused == btnPlay || focused == btnTrailer || focused == btnFavorite) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    when {
                        focused == btnPlay && btnTrailer?.visibility == View.VISIBLE -> btnTrailer?.requestFocus()
                        focused == btnPlay -> btnFavorite.requestFocus()
                        focused == btnTrailer -> btnFavorite.requestFocus()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    when {
                        focused == btnFavorite && btnTrailer?.visibility == View.VISIBLE -> btnTrailer?.requestFocus()
                        focused == btnFavorite -> btnPlay.requestFocus()
                        focused == btnTrailer -> btnPlay.requestFocus()
                    }
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        wvTrailer?.let {
            it.loadUrl("about:blank")
            it.destroy()
        }
        super.onDestroy()
    }

    private fun getJsonStr(obj: JsonObject?, key: String): String? {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull) {
            return obj.get(key).asString
        }
        return null
    }

    // Episode card adapter (Apple TV style horizontal cards)
    private inner class EpisodeCardAdapter(
        private val episodes: List<JsonObject>,
        private val listener: (Int) -> Unit
    ) : RecyclerView.Adapter<EpisodeCardAdapter.VH>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_episode, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = episodes[position]
            val epNum = if (ep.has("episodeNum")) ep.get("episodeNum").asInt else (position + 1)

            holder.tvNumber.text = "E$epNum"

            val title = getJsonStr(ep, "title")
            holder.tvTitle.text = title ?: "Episode $epNum"

            val plot = getJsonStr(ep, "plot")
            if (!plot.isNullOrEmpty()) {
                holder.tvPlot.text = plot
                holder.tvPlot.visibility = View.VISIBLE
            } else {
                holder.tvPlot.visibility = View.GONE
            }

            val duration = getJsonStr(ep, "duration")
            if (!duration.isNullOrEmpty()) {
                holder.tvDuration.text = duration
                holder.tvDuration.visibility = View.VISIBLE
            }

            if (!channelLogo.isNullOrEmpty()) {
                Glide.with(holder.itemView.context)
                    .load(channelLogo)
                    .centerCrop()
                    .into(holder.ivThumb)
            }

            holder.itemView.setOnClickListener { listener(position) }
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.05f else 1f)
                    .scaleY(if (hasFocus) 1.05f else 1f)
                    .translationZ(if (hasFocus) 8f else 0f)
                    .setDuration(200).start()
            }
        }

        override fun getItemCount() = episodes.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivThumb: ImageView = v.findViewById(R.id.iv_episode_thumb)
            val tvNumber: TextView = v.findViewById(R.id.tv_episode_number)
            val tvTitle: TextView = v.findViewById(R.id.tv_episode_title)
            val tvPlot: TextView = v.findViewById(R.id.tv_episode_plot)
            val tvDuration: TextView = v.findViewById(R.id.tv_episode_duration)
        }
    }
}
