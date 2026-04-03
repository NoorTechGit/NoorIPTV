package com.salliptv.player

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.salliptv.player.adapter.CategoryAdapter
import com.salliptv.player.adapter.ChannelAdapter
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.model.Channel
import com.salliptv.player.model.EpgProgram
import com.salliptv.player.model.Playlist
import com.salliptv.player.parser.XtreamApi
import com.salliptv.player.parser.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.salliptv.player.adapter.HomeSectionAdapter
import com.salliptv.player.adapter.HomeSection
import com.salliptv.player.adapter.SectionType
import com.salliptv.player.view.PremiumLoader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var tvCategoryName: TextView
    private lateinit var tvChannelCount: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var tabLive: TextView
    private lateinit var tabVod: TextView
    private lateinit var tabSeries: TextView
    private lateinit var tabFavorites: TextView
    private lateinit var tabRecent: TextView
    private lateinit var tabSettings: TextView
    private var tabHomeVis: TextView? = null
    private var tabLiveVis: TextView? = null
    private var tabVodVis: TextView? = null
    private var tabSeriesVis: TextView? = null
    private var tabFavoritesVis: TextView? = null
    private var tabRecentVis: TextView? = null
    private var tabSettingsVis: TextView? = null

    // Sidebar screens (landscape only)
    private var sidebarTabs: View? = null
    private var sidebarGroups: View? = null
    private var tvSidebarBack: TextView? = null
    private var isShowingGroups = false
    private var isLandscape = false

    // Overlay mode (landscape only)
    private var overlayBrowse: View? = null
    private var isFullscreen = false

    // EPG (integrated in landscape)
    private var epgOverlay: View? = null
    private var isEpgVisible = false
    private var epgHsvTimeHeader: HorizontalScrollView? = null
    private var epgHsvPrograms: HorizontalScrollView? = null
    private var epgLlTimeSlots: LinearLayout? = null
    private var epgRvChannels: RecyclerView? = null
    private var epgRvPrograms: RecyclerView? = null
    private var epgIvChannelLogo: ImageView? = null
    private var epgTvProgramTitle: TextView? = null
    private var epgTvProgramTime: TextView? = null
    private var epgTvProgramDesc: TextView? = null
    private var epgXtreamApi: XtreamApi? = null
    private var epgChannels: MutableList<Channel> = mutableListOf()
    private var epgStartTimeMs: Long = 0

    companion object {
        private const val EPG_PX_PER_MIN = 4
        private const val EPG_HOURS = 6
        private const val EPG_ROW_HEIGHT_DP = 56
        private const val TAG = "MainActivity"
    }

    // Preview player (landscape only)
    private var previewPlayer: ExoPlayer? = null
    private var previewPlayerView: PlayerView? = null
    private var tvPreviewChannel: TextView? = null
    private var tvProgramTitle: TextView? = null
    private var tvProgramTime: TextView? = null
    private var tvProgramDuration: TextView? = null
    private var tvProgramDesc: TextView? = null
    private var tvDateTime: TextView? = null
    private val previewHandler = Handler(Looper.getMainLooper())

    // Icon bar (landscape only)
    private var iconSearch: ImageView? = null
    private var iconTv: ImageView? = null
    private var iconVod: ImageView? = null
    private var iconSeries: ImageView? = null
    private var iconFavorite: ImageView? = null
    private var iconEpg: ImageView? = null
    private var iconHistory: ImageView? = null
    private var iconSettings: ImageView? = null
    private var currentFocusedChannel: Channel? = null

    // Home sections carousel
    private var recyclerSections: RecyclerView? = null
    private lateinit var homeSectionAdapter: HomeSectionAdapter

    private lateinit var db: AppDatabase
    private lateinit var premiumManager: PremiumManager
    private var currentPlaylistId = -1
    private var currentType = "LIVE"
    private var currentGroup: String? = null
    private var currentPlayingUrl: String? = null

    // Contextual search/filter bar
    private var searchFilterBar: LinearLayout? = null
    private var etFilter: EditText? = null
    private var btnClearFilter: ImageView? = null
    private var isSearchVisible = false
    private var filterHandler = Handler(Looper.getMainLooper())
    private var filterRunnable: Runnable? = null
    private var currentFilterQuery: String = ""

    // Premium loader
    private var premiumLoader: PremiumLoader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getInstance(this)
        premiumManager = PremiumManager(this)

        rvCategories = findViewById(R.id.rv_categories)
        rvChannels = findViewById(R.id.rv_channels)
        tvCategoryName = findViewById(R.id.tv_category_name)
        tvChannelCount = findViewById(R.id.tv_channel_count)
        tvStatus = findViewById(R.id.tv_status)
        progress = findViewById(R.id.progress)
        tabLive = findViewById(R.id.tab_live)
        tabVod = findViewById(R.id.tab_vod)
        tabSeries = findViewById(R.id.tab_series)
        tabFavorites = findViewById(R.id.tab_favorites)
        tabRecent = findViewById(R.id.tab_recent)
        tabSettings = findViewById(R.id.tab_settings)

        // Init search/filter bar
        searchFilterBar = findViewById(R.id.search_filter_bar)
        etFilter = findViewById(R.id.et_filter)
        btnClearFilter = findViewById(R.id.btn_clear_filter)
        initSearchFilterBar()

        // Init premium AFTER views are ready (callback may fire synchronously)
        premiumManager.init { updateTabAccess() }

        // Sidebar group screen (landscape only)
        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        sidebarTabs = findViewById(R.id.sidebar_tabs)
        sidebarGroups = findViewById(R.id.sidebar_groups)
        tvSidebarBack = findViewById(R.id.tv_sidebar_back)

        overlayBrowse = findViewById(R.id.overlay_browse)

        tvSidebarBack?.let { backView ->
            backView.setOnClickListener { showTabs() }
            backView.setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundColor(if (hasFocus) 0x330A84FF else 0x00000000)
            }
        }

        // Setup RecyclerViews
        categoryAdapter = CategoryAdapter()
        if (isLandscape) {
            rvCategories.layoutManager = LinearLayoutManager(this) // vertical sidebar
        } else {
            rvCategories.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        }
        rvCategories.adapter = categoryAdapter

        channelAdapter = ChannelAdapter()
        if (isLandscape) {
            channelAdapter.setListMode(true)
            rvChannels.layoutManager = LinearLayoutManager(this)
        } else {
            rvChannels.layoutManager = GridLayoutManager(this, 3)
        }
        rvChannels.adapter = channelAdapter

        // Setup home section carousels (Apple TV-style)
        recyclerSections = findViewById(R.id.recyclerSections)
        recyclerSections?.let { rv ->
            homeSectionAdapter = HomeSectionAdapter(
                onChannelClick = { channel -> openChannel(channel) },
                onChannelLongClick = { channel -> toggleFavoriteSection(channel) },
                onChannelFocus = { channel -> currentFocusedChannel = channel },
                onSeeAllClick = { section -> openCategoryDetail(section) }
            )
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = homeSectionAdapter
        }

        // Preview player + icon bar (landscape only)
        if (isLandscape) {
            initPreviewPlayer()
            initEpg()
            initIconBar()
        }

        // Category click
        categoryAdapter.setOnCategoryClickListener { category, position ->
            currentGroup = if (position == 0) null else category // first = "All"
            tvCategoryName.text = category
            loadChannels()
        }

        // Channel click → open PlayerActivity (Apple TV overlay with channel strip, program info, action bar)
        channelAdapter.setOnChannelClickListener { channel, position ->
            lifecycleScope.launch(Dispatchers.IO) { db.channelDao().updateLastWatched(channel.id, System.currentTimeMillis()) }

            // VOD/Series: open detail page first
            if (currentType == "VOD" || currentType == "SERIES") {
                val intent = Intent(this, MovieDetailActivity::class.java).apply {
                    putExtra("channelId", channel.id)
                    putExtra("channelName", channel.name)
                    putExtra("channelLogo", channel.logoUrl)
                    putExtra("streamUrl", channel.streamUrl)
                    putExtra("streamId", channel.streamId)
                    putExtra("playlistId", currentPlaylistId)
                    putExtra("isPremium", premiumManager.isPremium())
                    putExtra("groupTitle", channel.groupTitle ?: currentGroup)
                    putExtra("channelType", currentType)
                    putExtra("isFavorite", channel.isFavorite)
                }
                startActivity(intent)
                return@setOnChannelClickListener
            }

            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("channelId", channel.id)
                putExtra("channelName", channel.cleanName ?: channel.name)
                putExtra("channelLogo", channel.logoUrl)
                putExtra("channelNumber", channel.channelNumber)
                putExtra("streamUrl", channel.streamUrl)
                putExtra("streamId", channel.streamId)
                putExtra("currentPosition", position)
                putExtra("playlistId", currentPlaylistId)
                putExtra("isPremium", premiumManager.isPremium())
                putExtra("groupTitle", channel.groupTitle ?: currentGroup)
                putExtra("channelType", currentType)
                putExtra("groupId", channel.groupId ?: "")
                putExtra("channelQuality", channel.qualityBadge ?: "")
            }
            startActivity(intent)
        }

        // Long click → toggle favorite
        channelAdapter.setOnChannelLongClickListener { channel, position ->
            val newFav = !channel.isFavorite
            channel.isFavorite = newFav
            lifecycleScope.launch(Dispatchers.IO) { db.channelDao().updateFavorite(channel.id, newFav) }
            channelAdapter.notifyItemChanged(position)
            Toast.makeText(
                this,
                if (newFav) getString(R.string.added_favorite) else getString(R.string.removed_favorite),
                Toast.LENGTH_SHORT
            ).show()
        }

        // Tab buttons with focus effects
        setupTabFocus(tabLive)
        setupTabFocus(tabVod)
        setupTabFocus(tabSeries)
        setupTabFocus(tabFavorites)
        setupTabFocus(tabRecent)
        setupTabFocus(tabSettings)

        tabLive.setOnClickListener { switchTab("LIVE") }
        tabVod.setOnClickListener { switchTab("VOD") }
        tabSeries.setOnClickListener { switchTab("SERIES") }
        tabFavorites.setOnClickListener { loadFavorites() }
        tabRecent.setOnClickListener { loadRecent() }
        tabSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        // Visible tabs (portrait tab_home_vis + landscape tab_*_vis)
        tabHomeVis = findViewById(R.id.tab_home_vis)
        tabLiveVis = findViewById(R.id.tab_live_vis)
        tabVodVis = findViewById(R.id.tab_vod_vis)
        tabSeriesVis = findViewById(R.id.tab_series_vis)
        tabFavoritesVis = findViewById(R.id.tab_favorites_vis)
        tabRecentVis = findViewById(R.id.tab_recent_vis)
        // tab_settings_vis removed — settings icon in top bar is sufficient

        Log.d(TAG, "tabHomeVis=${tabHomeVis != null}, tabLiveVis=${tabLiveVis != null}, tabVodVis=${tabVodVis != null}")
        tabHomeVis?.setOnClickListener { Log.d(TAG, "TAP: HOME"); switchTab("HOME") }
        tabLiveVis?.setOnClickListener { Log.d(TAG, "TAP: LIVE"); switchTab("LIVE") }
        tabVodVis?.setOnClickListener { Log.d(TAG, "TAP: VOD"); switchTab("VOD") }
        tabSeriesVis?.setOnClickListener { Log.d(TAG, "TAP: SERIES"); switchTab("SERIES") }
        tabFavoritesVis?.setOnClickListener { Log.d(TAG, "TAP: FAV"); loadFavorites() }
        tabRecentVis?.setOnClickListener { loadRecent() }
        // tab_settings_vis removed — settings icon in top bar is sufficient

        // Portrait home tab also in the stub slot
        tabHomeVis?.let { setupVisTabFocus(it) }
        tabLiveVis?.let { setupVisTabFocus(it) }
        tabVodVis?.let { setupVisTabFocus(it) }
        tabSeriesVis?.let { setupVisTabFocus(it) }
        tabFavoritesVis?.let { setupVisTabFocus(it) }
        tabRecentVis?.let { setupVisTabFocus(it) }
        // tab_settings_vis removed
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Si une playlist a été ajoutée, forcer le rechargement
        if (intent?.getBooleanExtra("playlistAdded", false) == true) {
            val newPlaylistId = intent.getIntExtra("playlistId", -1)
            if (newPlaylistId >= 0) {
                Log.d(TAG, "Playlist added detected, reloading playlistId=$newPlaylistId")
                currentPlaylistId = newPlaylistId
                reloadHome()
            }
        }
    }

    private fun reloadHome() {
        lifecycleScope.launch(Dispatchers.Main) {
            tvStatus.text = "Chargement..."
            tvStatus.visibility = View.VISIBLE
            loadHomeSections()
        }
    }

    override fun onResume() {
        super.onResume()
        previewPlayer?.play()
        checkPlaylist()
        // Vérifier si on revient avec une nouvelle playlist
        if (intent?.getBooleanExtra("playlistAdded", false) == true) {
            intent.removeExtra("playlistAdded")
            reloadHome()
        }

        // Sync latest watched channel so returning from PlayerActivity doesn't jump back
        lifecycleScope.launch(Dispatchers.IO) {
            val recent = if (currentPlaylistId >= 0) db.channelDao().getRecent(currentPlaylistId) else emptyList()
            if (recent.isNotEmpty()) {
                val lastWatched = recent[0]
                val lastUrl = lastWatched.streamUrl
                // Only if NOT already playing this URL
                if (lastUrl != null && lastUrl != currentPlayingUrl) {
                    withContext(Dispatchers.Main) { playChannelFromList(lastWatched, -1) }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        previewPlayer?.pause()
    }

    override fun onDestroy() {
        releasePreviewPlayer()
        super.onDestroy()
    }

    private fun checkPlaylist() {
        lifecycleScope.launch(Dispatchers.IO) {
            val playlists = db.playlistDao().getAll()
            if (playlists.isEmpty()) {
                // AUTO-INJECT DEMO PLAYLIST
                injectDemoPlaylist()
            }
            val updatedPlaylists = db.playlistDao().getAll()
            withContext(Dispatchers.Main) {
                if (updatedPlaylists.isEmpty()) {
                    showNoPlaylist()
                } else {
                    val firstId = updatedPlaylists[0].id
                    if (currentPlaylistId != firstId) {
                        currentPlaylistId = firstId
                        switchTab("HOME")
                    }
                }
            }
        }
    }
    
    private suspend fun injectDemoPlaylist() {
        Log.d("MainActivity", "Injecting PorscheTV playlist...")
        try {
            val pl = com.salliptv.player.model.Playlist().apply {
                name = "PorscheTV"
                url = "http://line.porschetv.net:80/get.php?username=FNBAYSLNJE&password=OVVOL5VTZY&output=ts&type=m3u_plus"
                type = "M3U"
                username = "FNBAYSLNJE"
                password = "OVVOL5VTZY"
                lastUpdated = System.currentTimeMillis()
            }
            Log.d("MainActivity", "Inserting playlist...")
            val playlistId = db.playlistDao().insert(pl).toInt()
            Log.d("MainActivity", "Playlist inserted with ID: $playlistId")
            
            // Parse and load channels with premium loader
            withContext(Dispatchers.Main) {
                premiumLoader = PremiumLoader(this@MainActivity).apply {
                    attachTo(findViewById(android.R.id.content))
                    show(
                        title = "Chargement de la playlist",
                        subtitle = "Connexion à PorscheTV..."
                    )
                }
            }
            
            // Parser la playlist avec insertion par lots
            Log.d("MainActivity", "Parsing M3U from: ${pl.url}")
            var totalChannels = 0
            val result = M3uParser.parse(
                pl.url!!, 
                playlistId,
                onProgress = { count ->
                    totalChannels = count
                    lifecycleScope.launch(Dispatchers.Main) {
                        premiumLoader?.updateCount(count, "chaînes")
                        when {
                            count < 1000 -> premiumLoader?.updateSubtitle("Téléchargement...")
                            count < 50000 -> premiumLoader?.updateSubtitle("Analyse des chaînes...")
                            else -> premiumLoader?.updateSubtitle("Insertion en base de données...")
                        }
                    }
                },
                onBatch = { batch ->
                    kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                        db.channelDao().insertAll(batch)
                    }
                    Log.d("MainActivity", "Inserted batch of ${batch.size} channels")
                }
            )
            
            Log.d("MainActivity", "Parsed $totalChannels channels")
            
            // Rafraîchir l'UI
            withContext(Dispatchers.Main) {
                premiumLoader?.updateSubtitle("Prêt !")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    premiumLoader?.hide()
                    currentPlaylistId = playlistId
                    loadHomeSections()
                }, 500)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error injecting playlist", e)
            withContext(Dispatchers.Main) {
                premiumLoader?.hide()
                tvStatus.text = "Erreur: ${e.message}"
                tvStatus.visibility = View.VISIBLE
            }
        }
    }

    private fun showNoPlaylist() {
        rvChannels.visibility = View.GONE
        rvCategories.visibility = View.GONE
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.no_playlist)
        // Auto-open settings
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ==========================================
    // TABS
    // ==========================================

    private fun switchTab(type: String) {
        currentType = type
        currentGroup = null
        updateTabUI()
        updateGridMode(type)

        val liveListContainer = findViewById<View?>(R.id.live_list_container)

        if (type == "LIVE") {
            // LIVE: classic list mode (categories sidebar + channel list)
            recyclerSections?.visibility = View.GONE
            liveListContainer?.visibility = View.VISIBLE
            channelAdapter.setListMode(true)
            channelAdapter.setPosterMode(false)
            if (rvChannels.layoutManager !is LinearLayoutManager || rvChannels.layoutManager is GridLayoutManager) {
                rvChannels.layoutManager = LinearLayoutManager(this)
            }
            loadCategories()
            loadChannels()
        } else {
            // HOME, VOD, SERIES: carousel mode
            liveListContainer?.visibility = View.GONE
            recyclerSections?.visibility = View.VISIBLE
            loadHomeSections()
        }

        // In landscape: for HOME tab stay on tabs panel; for others show groups
        if (isLandscape && sidebarTabs != null && sidebarGroups != null) {
            if (type == "HOME" || type == "VOD" || type == "SERIES") {
                isShowingGroups = false
                sidebarGroups?.visibility = View.GONE
                sidebarTabs?.visibility = View.VISIBLE
            } else {
                showGroups(type)
            }
        }
    }

    private fun updateGridMode(type: String) {
        val isPoster = type == "VOD" || type == "SERIES"

        if (isLandscape) {
            if (isPoster) {
                // Switch to poster grid for VOD/Series
                channelAdapter.setListMode(false)
                channelAdapter.setPosterMode(true)
                rvChannels.layoutManager = GridLayoutManager(this, 5)
            } else {
                // Back to list mode for Live
                channelAdapter.setListMode(true)
                channelAdapter.setPosterMode(false)
                rvChannels.layoutManager = LinearLayoutManager(this)
            }
        } else {
            if (isPoster) {
                channelAdapter.setPosterMode(true)
                channelAdapter.setListMode(false)
                rvChannels.layoutManager = GridLayoutManager(this, 3)
            } else {
                channelAdapter.setPosterMode(false)
                rvChannels.layoutManager = GridLayoutManager(this, 3)
            }
        }
        rvChannels.adapter = channelAdapter
    }

    private fun showGroups(type: String) {
        isShowingGroups = true
        sidebarTabs?.visibility = View.GONE
        sidebarGroups?.visibility = View.VISIBLE

        // Set back header text
        tvSidebarBack?.let { backView ->
            if (backView.visibility != View.GONE) {
                val label = when (type) {
                    "HOME" -> getString(R.string.tab_home)
                    "LIVE" -> getString(R.string.tab_live)
                    "VOD" -> getString(R.string.tab_vod)
                    "SERIES" -> getString(R.string.tab_series)
                    else -> type
                }
                backView.text = "\u2190  $label"
            }
        }

        // Focus first category
        rvCategories.post {
            rvCategories.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun showTabs() {
        isShowingGroups = false
        sidebarGroups?.visibility = View.GONE
        sidebarTabs?.let { tabs ->
            tabs.visibility = View.VISIBLE
            // Focus the current tab
            when (currentType) {
                "HOME" -> tabHomeVis?.requestFocus() ?: tabLive.requestFocus()
                "LIVE" -> tabLive.requestFocus()
                "VOD" -> tabVod.requestFocus()
                "SERIES" -> tabSeries.requestFocus()
                else -> tabLive.requestFocus()
            }
        }
    }

    private fun updateTabUI() {
        val white = Color.WHITE
        val gray = Color.parseColor("#8E8E93")

        // Hidden stub tabs (zero-size, no background needed — just text color)
        val tabs = arrayOf(tabLive, tabVod, tabSeries, tabFavorites, tabRecent)
        val types = arrayOf("LIVE", "VOD", "SERIES", "FAV", "RECENT")
        for (i in tabs.indices) {
            val active = types[i] == currentType
            tabs[i].setBackgroundColor(Color.TRANSPARENT)
            tabs[i].setTextColor(if (active) white else gray)
            tabs[i].typeface = if (active) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }

        // Visible tabs (portrait tab_home_vis + landscape tab_*_vis)
        // Apple TV style: white bold = active, gray normal = inactive, no background ever
        val visTabs = listOf(
            Pair(tabHomeVis, "HOME"),
            Pair(tabLiveVis, "LIVE"),
            Pair(tabVodVis, "VOD"),
            Pair(tabSeriesVis, "SERIES"),
            Pair(tabFavoritesVis, "FAV"),
            Pair(tabRecentVis, "RECENT")
        )
        for ((tab, type) in visTabs) {
            tab ?: continue
            val active = type == currentType
            tab.setBackgroundColor(Color.TRANSPARENT)
            tab.setTextColor(if (active) white else gray)
            tab.typeface = if (active) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
    }

    // ==========================================
    // LOAD DATA
    // ==========================================

    private fun loadCategories() {
        if (currentPlaylistId < 0) return

        lifecycleScope.launch(Dispatchers.IO) {
            val groupCounts = db.channelDao().getGroupsWithCounts(currentPlaylistId, currentType)

            val allGroups = mutableListOf(getString(R.string.all_channels))
            val counts = mutableListOf<Int>()
            var totalCount = 0

            for (gc in groupCounts) {
                allGroups.add(gc.groupTitle)
                counts.add(gc.cnt)
                totalCount += gc.cnt
            }
            counts.add(0, totalCount)

            withContext(Dispatchers.Main) {
                categoryAdapter.setCategories(allGroups, counts)
                rvCategories.visibility = View.VISIBLE
            }
        }
    }

    private fun loadChannels() {
        if (currentPlaylistId < 0) return
        progress.visibility = View.VISIBLE
        tvStatus.visibility = View.GONE
        rvChannels.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val channels: List<Channel> = if (currentGroup == null) {
                db.channelDao().getByType(currentPlaylistId, currentType)
            } else {
                db.channelDao().getByGroup(currentPlaylistId, currentGroup!!, currentType)
            }

            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
                channelAdapter.setChannels(channels)
                tvChannelCount.text = getString(R.string.channels_count, channels.size)

                if (channels.isEmpty()) {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = if (currentType == "VOD" || currentType == "SERIES") {
                        getString(R.string.no_channels) + "\n(Puisque c'est un nouvel import, allez dans les réglages et faites un 'Refresh' de votre liste)"
                    } else {
                        getString(R.string.no_channels)
                    }
                }

                // In poster mode, focus first card and set currentFocusedChannel
                if (channelAdapter.isPosterMode() && channels.isNotEmpty()) {
                    currentFocusedChannel = channels[0]
                    rvChannels.postDelayed({
                        if (rvChannels.childCount > 0) {
                            rvChannels.getChildAt(0).requestFocus()
                        }
                    }, 200)
                }

                // Auto-play preview for first channel in landscape
                if (isLandscape && previewPlayer != null && channels.isNotEmpty()) {
                    if (currentFocusedChannel == null && currentPlayingUrl == null) {
                        val first = channels[0]
                        if (first.streamUrl != null) {
                            currentPlayingUrl = first.streamUrl
                            tvPreviewChannel?.text = first.name
                            tvProgramTitle?.text = first.name
                            try {
                                previewPlayer?.setMediaItem(MediaItem.fromUri(Uri.parse(first.streamUrl)))
                                previewPlayer?.prepare()
                                previewPlayer?.play()
                            } catch (ignored: Exception) {}
                        }
                    }
                }
            }
        }
    }

    private fun loadFavorites() {
        currentType = "FAV"
        updateTabUI()

        // Show carousel mode, hide live list
        val liveListContainer = findViewById<View?>(R.id.live_list_container)
        liveListContainer?.visibility = View.GONE
        recyclerSections?.visibility = View.VISIBLE
        rvCategories.visibility = View.GONE
        tvCategoryName.text = getString(R.string.tab_favorites)
        progress.visibility = View.VISIBLE
        tvStatus.visibility = View.GONE

        if (isLandscape && sidebarGroups != null) {
            isShowingGroups = false
            sidebarGroups?.visibility = View.GONE
            sidebarTabs?.visibility = View.VISIBLE
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val channels = db.channelDao().getFavorites(currentPlaylistId)
            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
                if (channels.isEmpty()) {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = getString(R.string.no_channels)
                } else if (::homeSectionAdapter.isInitialized && recyclerSections != null) {
                    val sections = listOf(
                        HomeSection(getString(R.string.tab_favorites), channels, SectionType.FAVORITES)
                    )
                    homeSectionAdapter.setSections(sections)
                    tvChannelCount.text = getString(R.string.channels_count, channels.size)
                }
            }
        }
    }

    private fun updateTabAccess() {
        val pro = premiumManager.isPremium()
        val alpha = if (pro) 1.0f else 0.5f
        tabVod.alpha = alpha
        tabSeries.alpha = alpha
        if (!pro) {
            tabVod.text = getString(R.string.tab_vod) + " \uD83D\uDD12"
            tabSeries.text = getString(R.string.tab_series) + " \uD83D\uDD12"
        } else {
            tabVod.text = getString(R.string.tab_vod)
            tabSeries.text = getString(R.string.tab_series)
        }
    }

    private fun showProRequired() {
        Toast.makeText(this, getString(R.string.feature_locked), Toast.LENGTH_SHORT).show()
    }

    private fun loadRecent() {
        currentType = "RECENT"
        updateTabUI()

        // Show carousel mode, hide live list
        val liveListContainer = findViewById<View?>(R.id.live_list_container)
        liveListContainer?.visibility = View.GONE
        recyclerSections?.visibility = View.VISIBLE
        rvCategories.visibility = View.GONE
        tvCategoryName.text = getString(R.string.tab_recent)
        progress.visibility = View.VISIBLE
        tvStatus.visibility = View.GONE

        if (isLandscape && sidebarGroups != null) {
            isShowingGroups = false
            sidebarGroups?.visibility = View.GONE
            sidebarTabs?.visibility = View.VISIBLE
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val channels = db.channelDao().getRecent(currentPlaylistId)
            withContext(Dispatchers.Main) {
                progress.visibility = View.GONE
                if (channels.isEmpty()) {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = getString(R.string.no_channels)
                } else if (::homeSectionAdapter.isInitialized && recyclerSections != null) {
                    val sections = listOf(
                        HomeSection(getString(R.string.tab_recent), channels, SectionType.CONTINUE_WATCHING)
                    )
                    homeSectionAdapter.setSections(sections)
                    tvChannelCount.text = getString(R.string.channels_count, channels.size)
                }
            }
        }
    }

    private fun loadHomeSections() {
        Log.d(TAG, "loadHomeSections: playlistId=$currentPlaylistId, type=$currentType, adapterInit=${::homeSectionAdapter.isInitialized}, recyclerSections=${recyclerSections != null}")
        if (currentPlaylistId < 0) return
        if (!::homeSectionAdapter.isInitialized) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sections = mutableListOf<HomeSection>()

                // Based on current tab
                when (currentType) {
                    "HOME" -> {
                        // Continue watching + Favorites only on HOME
                        val recent = db.channelDao().getRecent(currentPlaylistId)
                        if (recent.isNotEmpty()) {
                            sections.add(HomeSection(getString(R.string.section_continue_watching), recent.take(20), SectionType.CONTINUE_WATCHING))
                        }
                        val favorites = db.channelDao().getFavorites(currentPlaylistId)
                        if (favorites.isNotEmpty()) {
                            sections.add(HomeSection(getString(R.string.section_favorites), favorites.take(20), SectionType.FAVORITES))
                        }

                        // Popular Live channels (first 4 groups)
                        val liveGroups = db.channelDao().getGroups(currentPlaylistId, "LIVE")
                        for (group in liveGroups.take(4)) {
                            if (group.isNullOrEmpty()) continue
                            val channels = db.channelDao().getByGroupGrouped(currentPlaylistId, group, "LIVE")
                            if (channels.isNotEmpty()) {
                                sections.add(HomeSection(group, channels.take(10), SectionType.LIVE))
                            }
                        }

                        // Popular VOD (first 8 groups)
                        val vodGC = db.channelDao().getGroupsWithCountsLimited(currentPlaylistId, "VOD", 50)
                        for (gc in vodGC.take(8)) {
                            val channels = db.channelDao().getByGroupLimited(currentPlaylistId, gc.groupTitle, "VOD", 30)
                            if (channels.isNotEmpty()) {
                                sections.add(HomeSection(cleanGroupName(gc.groupTitle), channels, SectionType.VOD, groupName = gc.groupTitle, totalCount = gc.cnt))
                            }
                        }

                        // Popular Series (first 8 groups)
                        val seriesGC = db.channelDao().getGroupsWithCountsLimited(currentPlaylistId, "SERIES", 50)
                        for (gc in seriesGC.take(8)) {
                            val channels = db.channelDao().getByGroupLimited(currentPlaylistId, gc.groupTitle, "SERIES", 30)
                            if (channels.isNotEmpty()) {
                                sections.add(HomeSection(cleanGroupName(gc.groupTitle), channels, SectionType.SERIES, groupName = gc.groupTitle, totalCount = gc.cnt))
                            }
                        }
                    }
                    "LIVE" -> {
                        val groups = db.channelDao().getGroups(currentPlaylistId, "LIVE")
                        for (group in groups) {
                            if (group.isNullOrEmpty()) continue
                            val channels = db.channelDao().getByGroupGrouped(currentPlaylistId, group, "LIVE")
                            if (channels.isNotEmpty()) {
                                sections.add(HomeSection(group, channels, SectionType.LIVE))
                            }
                            if (sections.size > 15) break
                        }
                    }
                    "VOD" -> {
                        val groupCounts = db.channelDao().getGroupsWithCountsLimited(currentPlaylistId, "VOD", 100)
                        for (gc in groupCounts) {
                            val channels = db.channelDao().getByGroupLimited(currentPlaylistId, gc.groupTitle, "VOD", 30)
                            if (channels.isNotEmpty()) {
                                sections.add(HomeSection(cleanGroupName(gc.groupTitle), channels, SectionType.VOD, groupName = gc.groupTitle, totalCount = gc.cnt))
                            }
                        }
                    }
                    "SERIES" -> {
                        val groupCounts = db.channelDao().getGroupsWithCountsLimited(currentPlaylistId, "SERIES", 100)
                        for (gc in groupCounts) {
                            val channels = db.channelDao().getByGroupLimited(currentPlaylistId, gc.groupTitle, "SERIES", 30)
                            if (channels.isNotEmpty()) {
                                sections.add(HomeSection(cleanGroupName(gc.groupTitle), channels, SectionType.SERIES, groupName = gc.groupTitle, totalCount = gc.cnt))
                            }
                            if (sections.size > 20) break
                        }
                    }
                }

                Log.d(TAG, "loadHomeSections: ${sections.size} sections built")
                withContext(Dispatchers.Main) {
                    if (sections.isEmpty()) {
                        Log.d(TAG, "loadHomeSections: no sections, showing empty state")
                        tvStatus.visibility = View.VISIBLE
                        tvStatus.text = getString(R.string.no_playlist)
                    } else {
                        Log.d(TAG, "loadHomeSections: showing ${sections.size} sections")
                        tvStatus.visibility = View.GONE
                        homeSectionAdapter.setSections(sections)
                    }
                    progress.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading sections", e)
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = "Erreur: ${e.message}"
                }
            }
        }
    }

    private fun openCategoryDetail(section: HomeSection) {
        val typeStr = when (section.sectionType) {
            SectionType.LIVE -> "LIVE"
            SectionType.VOD -> "VOD"
            SectionType.SERIES -> "SERIES"
            else -> return
        }
        startActivity(Intent(this, CategoryDetailActivity::class.java).apply {
            putExtra("group", section.groupName)
            putExtra("type", typeStr)
            putExtra("playlistId", currentPlaylistId)
            putExtra("title", section.title)
        })
    }

    private fun cleanGroupName(name: String): String {
        return name
            .removePrefix("VOD - ").removePrefix("VOD -")
            .removePrefix("SERIES - ").removePrefix("SERIES -")
            .removePrefix("vod - ").removePrefix("series - ")
            .removePrefix("VOD| ").removePrefix("SERIES| ")
            .removePrefix("|").removePrefix("| ")
            .trim()
            .ifEmpty { name }
    }

    private fun openChannel(channel: Channel) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.channelDao().updateLastWatched(channel.id, System.currentTimeMillis())
        }

        // When on the HOME tab, determine actual type from the channel's own type field
        val effectiveType = if (currentType == "HOME") {
            channel.type ?: "LIVE"
        } else {
            currentType
        }

        if (effectiveType == "VOD" || effectiveType == "SERIES") {
            val intent = Intent(this, MovieDetailActivity::class.java).apply {
                putExtra("channelId", channel.id)
                putExtra("channelName", channel.name)
                putExtra("channelLogo", channel.logoUrl)
                putExtra("streamUrl", channel.streamUrl)
                putExtra("streamId", channel.streamId)
                putExtra("playlistId", currentPlaylistId)
                putExtra("isPremium", premiumManager.isPremium())
                putExtra("groupTitle", channel.groupTitle ?: currentGroup)
                putExtra("channelType", effectiveType)
                putExtra("isFavorite", channel.isFavorite)
            }
            startActivity(intent)
            return
        }

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("channelId", channel.id)
            putExtra("channelName", channel.cleanName ?: channel.name)
            putExtra("channelLogo", channel.logoUrl)
            putExtra("channelNumber", channel.channelNumber)
            putExtra("streamUrl", channel.streamUrl)
            putExtra("streamId", channel.streamId)
            putExtra("playlistId", currentPlaylistId)
            putExtra("isPremium", premiumManager.isPremium())
            putExtra("groupTitle", channel.groupTitle ?: currentGroup)
            putExtra("channelType", effectiveType)
            putExtra("groupId", channel.groupId ?: "")
            putExtra("channelQuality", channel.qualityBadge ?: "")
        }
        // Add Xtream extras if available
        lifecycleScope.launch(Dispatchers.IO) {
            val playlist = db.playlistDao().getAll().firstOrNull { it.id == currentPlaylistId }
            withContext(Dispatchers.Main) {
                if (playlist?.type == "XTREAM") {
                    intent.putExtra("xtream_server", playlist.url ?: "")
                    intent.putExtra("xtream_user", playlist.username ?: "")
                    intent.putExtra("xtream_pass", playlist.password ?: "")
                }
                startActivity(intent)
            }
        }
    }

    private fun toggleFavoriteSection(channel: Channel) {
        val newFav = !channel.isFavorite
        lifecycleScope.launch(Dispatchers.IO) {
            db.channelDao().updateFavorite(channel.id, newFav)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    if (newFav) getString(R.string.added_favorite) else getString(R.string.removed_favorite),
                    Toast.LENGTH_SHORT
                ).show()
                // Ne recharger les sections que si on est sur HOME/VOD/SERIES
                if (currentType != "LIVE") {
                    loadHomeSections()
                }
            }
        }
    }

    private fun setupTabFocus(tab: TextView) {
        tab.setOnFocusChangeListener { _, hasFocus ->
            val white = Color.WHITE
            val gray = Color.parseColor("#8E8E93")
            val isActive = when (tab) {
                tabLive -> currentType == "LIVE"
                tabVod -> currentType == "VOD"
                tabSeries -> currentType == "SERIES"
                tabFavorites -> currentType == "FAV"
                tabRecent -> currentType == "RECENT"
                else -> false
            }
            tab.setBackgroundColor(Color.TRANSPARENT)
            if (!isActive) {
                tab.setTextColor(if (hasFocus) white else gray)
            }
        }
    }

    private fun setupVisTabFocus(tab: TextView) {
        tab.setOnFocusChangeListener { _, hasFocus ->
            val white = Color.WHITE
            val gray = Color.parseColor("#8E8E93")
            val isActive = when (tab) {
                tabHomeVis -> currentType == "HOME"
                tabLiveVis -> currentType == "LIVE"
                tabVodVis -> currentType == "VOD"
                tabSeriesVis -> currentType == "SERIES"
                tabFavoritesVis -> currentType == "FAV"
                tabRecentVis -> currentType == "RECENT"
                else -> false
            }
            // No background change — Apple TV style: just brighten text on focus
            tab.setBackgroundColor(Color.TRANSPARENT)
            if (!isActive) {
                // Focused-but-inactive: brighten to white; unfocused: back to gray
                tab.setTextColor(if (hasFocus) white else gray)
            }
            // Active tab always stays white — no animation needed
        }
    }

    // ==========================================
    // PREVIEW PLAYER (landscape)
    // ==========================================

    private fun initPreviewPlayer() {
        previewPlayerView = findViewById(R.id.preview_player)
        tvPreviewChannel = findViewById(R.id.tv_preview_channel)
        tvProgramTitle = findViewById(R.id.tv_program_title)
        tvProgramTime = findViewById(R.id.tv_program_time)
        tvProgramDuration = findViewById(R.id.tv_program_duration)
        tvProgramDesc = findViewById(R.id.tv_program_desc)
        tvDateTime = findViewById(R.id.tv_date_time)

        if (previewPlayerView == null) return

        previewPlayer = ExoPlayer.Builder(this).build()
        previewPlayerView?.setPlayer(previewPlayer)
        previewPlayerView?.useController = false

        // Update date/time
        updateDateTime()
        previewHandler.postDelayed(object : Runnable {
            override fun run() {
                updateDateTime()
                previewHandler.postDelayed(this, 30000)
            }
        }, 30000)

        // Focus listener: only update info text, DON'T switch the playing channel
        // The video stays on the current channel while browsing (Apple TV style)
        channelAdapter.setOnChannelFocusListener { channel, _ ->
            currentFocusedChannel = channel
            // Update info bar with focused channel info (but don't play it)
            tvProgramTitle?.text = channel.name
            tvProgramTime?.text = ""
            tvProgramDuration?.text = ""
            tvProgramDesc?.text = ""
        }
    }

    private fun playChannelFromList(channel: Channel?, position: Int) {
        if (channel == null || previewPlayer == null || channel.streamUrl == null) return

        // Avoid re-playing the same stream if already active
        if (channel.streamUrl == currentPlayingUrl && previewPlayer?.playbackState != Player.STATE_IDLE) {
            currentFocusedChannel = channel
            if (position >= 0) channelAdapter.setSelectedPosition(position)
            return
        }

        currentFocusedChannel = channel
        currentPlayingUrl = channel.streamUrl
        if (position >= 0) channelAdapter.setSelectedPosition(position)
        tvPreviewChannel?.text = channel.name
        tvProgramTitle?.text = channel.name
        try {
            previewPlayer?.setMediaItem(MediaItem.fromUri(Uri.parse(channel.streamUrl)))
            previewPlayer?.prepare()
            previewPlayer?.play()
            previewPlayer?.volume = 1f // always audible when user explicitly selects
        } catch (ignored: Exception) {}

        lifecycleScope.launch(Dispatchers.IO) { db.channelDao().updateLastWatched(channel.id, System.currentTimeMillis()) }
    }

    private fun updateDateTime() {
        tvDateTime?.text = SimpleDateFormat("EEE dd MMM  HH:mm", Locale.getDefault()).format(Date())
    }

    private fun releasePreviewPlayer() {
        previewPlayer?.release()
        previewPlayer = null
    }

    // ==========================================
    // ICON BAR (landscape)
    // ==========================================

    private fun initIconBar() {
        iconSearch = findViewById(R.id.icon_search)
        iconTv = findViewById(R.id.icon_tv)
        iconVod = findViewById(R.id.icon_vod)
        iconSeries = findViewById(R.id.icon_series)
        iconFavorite = findViewById(R.id.icon_favorite)
        iconEpg = findViewById(R.id.icon_epg)
        iconHistory = findViewById(R.id.icon_history)
        iconSettings = findViewById(R.id.icon_settings)

        if (iconSearch == null) return

        // Focus effect for all icons
        val icons = arrayOf(iconSearch, iconTv, iconVod, iconSeries, iconFavorite, iconEpg, iconHistory, iconSettings)
        for (icon in icons) {
            icon ?: continue
            icon.setOnFocusChangeListener { v, hasFocus ->
                v.alpha = if (hasFocus) 1f else 0.6f
                v.animate().scaleX(if (hasFocus) 1.2f else 1f).scaleY(if (hasFocus) 1.2f else 1f).setDuration(100).start()
            }
            icon.alpha = 0.6f
        }

        iconSearch?.setOnClickListener {
            toggleSearchBar()
        }

        iconTv?.setOnClickListener { highlightIcon(iconTv!!); switchTab("LIVE") }
        iconVod?.setOnClickListener { highlightIcon(iconVod!!); switchTab("VOD") }
        iconSeries?.setOnClickListener { highlightIcon(iconSeries!!); switchTab("SERIES") }
        iconFavorite?.setOnClickListener { highlightIcon(iconFavorite!!); loadFavorites() }
        iconHistory?.setOnClickListener { highlightIcon(iconHistory!!); loadRecent() }
        iconEpg?.setOnClickListener { highlightIcon(iconEpg!!); toggleEpg() }
        iconSettings?.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        // Default: TV icon active
        iconTv?.let { highlightIcon(it) }
    }

    private fun highlightIcon(activeIcon: ImageView) {
        if (iconSearch == null) return
        val icons = arrayOf(iconSearch, iconTv, iconVod, iconSeries, iconFavorite, iconEpg, iconHistory, iconSettings)
        for (icon in icons) {
            icon ?: continue
            if (icon === activeIcon) {
                icon.alpha = 1f
                icon.setColorFilter(0xFF0A84FF.toInt()) // indigo tint
            } else {
                icon.alpha = 0.5f
                icon.setColorFilter(0xFF8E8E93.toInt()) // gray tint
            }
        }
    }

    // ==========================================
    // CONTEXTUAL SEARCH / FILTER
    // ==========================================

    private fun initSearchFilterBar() {
        val bar = searchFilterBar ?: return
        val et = etFilter ?: return
        val btnClear = btnClearFilter ?: return

        // Clear button
        btnClear.setOnClickListener {
            et.text.clear()
            btnClear.visibility = View.GONE
            currentFilterQuery = ""
            applyFilter("")
        }

        // Debounced text watcher
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                btnClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                filterRunnable?.let { filterHandler.removeCallbacks(it) }
                filterRunnable = Runnable { applyFilter(query) }
                filterHandler.postDelayed(filterRunnable!!, 300)
            }
        })

        // Action search on keyboard
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                val query = et.text?.toString()?.trim() ?: ""
                applyFilter(query)
                true
            } else false
        }
    }

    private fun toggleSearchBar() {
        if (isSearchVisible) hideSearchBar() else showSearchBar()
    }

    private fun showSearchBar() {
        val bar = searchFilterBar ?: return
        isSearchVisible = true
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        bar.animate().alpha(1f).setDuration(200).start()
        etFilter?.requestFocus()
        // Show keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        etFilter?.let { imm?.showSoftInput(it, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideSearchBar() {
        val bar = searchFilterBar ?: return
        isSearchVisible = false
        currentFilterQuery = ""
        etFilter?.text?.clear()
        btnClearFilter?.visibility = View.GONE
        bar.animate().alpha(0f).setDuration(200).withEndAction {
            bar.visibility = View.GONE
        }.start()
        // Restore unfiltered data
        applyFilter("")
    }

    private fun applyFilter(query: String) {
        currentFilterQuery = query
        val q = query.lowercase(Locale.getDefault())

        if (currentType == "LIVE" && rvChannels.visibility == View.VISIBLE) {
            // Filter channel list (LIVE mode)
            channelAdapter.filter(q)
        } else {
            // Filter home sections (carousel mode)
            if (::homeSectionAdapter.isInitialized) {
                homeSectionAdapter.filterSections(q)
            }
        }
    }

    // ==========================================
    // FULLSCREEN / OVERLAY TOGGLE (landscape)
    // ==========================================

    private fun goFullscreen() {
        if (!isLandscape || overlayBrowse == null) return
        isFullscreen = true
        overlayBrowse?.animate()?.alpha(0f)?.setDuration(250)?.withEndAction {
            overlayBrowse?.visibility = View.GONE
        }?.start()
        // Show channel name on video
        tvPreviewChannel?.visibility = View.VISIBLE
        // Unmute
        previewPlayer?.volume = 1f
    }

    private fun showBrowseOverlay() {
        if (!isLandscape || overlayBrowse == null) return
        isFullscreen = false
        overlayBrowse?.alpha = 0f
        overlayBrowse?.visibility = View.VISIBLE
        overlayBrowse?.animate()?.alpha(1f)?.setDuration(250)?.start()
        // Hide channel name overlay
        tvPreviewChannel?.visibility = View.GONE
        // Do not mute video on BACK anymore (Apple TV keeps video audio behind UI)
        previewPlayer?.volume = 1f
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Let the default focus system handle ALL key events.
        // Item clicks are handled by setOnClickListener on each view.
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Fullscreen mode: OK = show overlay, UP/DOWN = channel switch
        if (isLandscape && isFullscreen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    showBrowseOverlay()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val chs = channelAdapter.getChannels()
                    if (!chs.isNullOrEmpty()) {
                        var pos = chs.indexOf(currentFocusedChannel)
                        if (pos < 0) pos = 0
                        pos = (pos - 1 + chs.size) % chs.size
                        playChannelFromList(chs[pos], pos)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val chs = channelAdapter.getChannels()
                    if (!chs.isNullOrEmpty()) {
                        var pos = chs.indexOf(currentFocusedChannel)
                        if (pos < 0) pos = 0
                        pos = (pos + 1) % chs.size
                        playChannelFromList(chs[pos], pos)
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    showBrowseOverlay()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Close EPG if visible
            if (isEpgVisible) {
                hideEpg()
                return true
            }
            // In overlay mode: BACK = go fullscreen (not exit app)
            if (isLandscape && overlayBrowse != null && overlayBrowse?.visibility == View.VISIBLE) {
                goFullscreen()
                return true
            }
            if (isShowingGroups && sidebarTabs != null && sidebarTabs?.visibility != View.GONE) {
                showTabs()
                return true
            }
            // Quitter l'application complètement
            finishAffinity()
            System.exit(0)
            return true
        }

        // In landscape: handle RIGHT from sidebar → channel list
        if (isLandscape && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val focused = currentFocus
            if (focused != null) {
                var parent = focused.parent as? View
                var inSidebar = false
                while (parent != null) {
                    if (parent === rvCategories || parent.id == R.id.icon_bar) {
                        inSidebar = true
                        break
                    }
                    parent = parent.parent as? View
                }
                if (inSidebar) {
                    // Focus the first visible item in channel list
                    rvChannels.post {
                        if (rvChannels.childCount > 0) {
                            rvChannels.getChildAt(0).requestFocus()
                        }
                    }
                    return true
                }
            }
        }

        // LEFT from channel list → go back to sidebar
        if (isLandscape && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            val focused = currentFocus
            if (focused != null) {
                var parent = focused.parent as? View
                while (parent != null) {
                    if (parent === rvChannels) {
                        // Go back to sidebar
                        rvCategories.post {
                            if (rvCategories.childCount > 0) {
                                rvCategories.getChildAt(0).requestFocus()
                            }
                        }
                        return true
                    }
                    parent = parent.parent as? View
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    // ==========================================
    // EPG (integrated in landscape layout)
    // ==========================================

    private fun initEpg() {
        epgOverlay = findViewById(R.id.epg_overlay)
        if (epgOverlay == null) return
        epgHsvTimeHeader = findViewById(R.id.epg_hsv_time_header)
        epgHsvPrograms = findViewById(R.id.epg_hsv_programs)
        epgLlTimeSlots = findViewById(R.id.epg_ll_time_slots)
        epgRvChannels = findViewById(R.id.epg_rv_channels)
        epgRvPrograms = findViewById(R.id.epg_rv_programs)
        epgIvChannelLogo = findViewById(R.id.epg_iv_channel_logo)
        epgTvProgramTitle = findViewById(R.id.epg_tv_program_title)
        epgTvProgramTime = findViewById(R.id.epg_tv_program_time)
        epgTvProgramDesc = findViewById(R.id.epg_tv_program_desc)

        epgRvChannels?.layoutManager = LinearLayoutManager(this)
        epgRvPrograms?.layoutManager = LinearLayoutManager(this)

        // Sync horizontal scroll
        epgHsvPrograms?.viewTreeObserver?.addOnScrollChangedListener {
            epgHsvTimeHeader?.scrollTo(epgHsvPrograms!!.scrollX, 0)
        }

        // Sync vertical scroll
        val epgRvCh = epgRvChannels
        val epgRvPr = epgRvPrograms
        if (epgRvCh != null && epgRvPr != null) {
            val syncing = booleanArrayOf(false)
            epgRvCh.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (!syncing[0]) { syncing[0] = true; epgRvPr.scrollBy(0, dy); syncing[0] = false }
                }
            })
            epgRvPr.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (!syncing[0]) { syncing[0] = true; epgRvCh.scrollBy(0, dy); syncing[0] = false }
                }
            })
        }
    }

    private fun toggleEpg() {
        if (isEpgVisible) hideEpg() else showEpg()
    }

    private fun showEpg() {
        if (epgOverlay == null) return
        isEpgVisible = true
        epgOverlay?.visibility = View.VISIBLE
        epgOverlay?.alpha = 0f
        epgOverlay?.animate()?.alpha(1f)?.setDuration(200)?.start()

        // Calculate start time
        val cal = Calendar.getInstance()
        cal.add(Calendar.HOUR_OF_DAY, -1)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        epgStartTimeMs = cal.timeInMillis

        // Build time header
        epgLlTimeSlots?.removeAllViews()
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val slotWidthDp = EPG_PX_PER_MIN * 30
        for (i in 0 until EPG_HOURS * 2) {
            val slotTime = epgStartTimeMs + (i * 30L * 60L * 1000L)
            val tv = TextView(this)
            tv.text = sdf.format(Date(slotTime))
            tv.setTextColor(0xFF8E8E93.toInt())
            tv.textSize = 13f
            tv.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            tv.setPadding(epgDp(8), 0, 0, 0)
            val lp = LinearLayout.LayoutParams(epgDp(slotWidthDp), ViewGroup.LayoutParams.MATCH_PARENT)
            tv.layoutParams = lp
            epgLlTimeSlots?.addView(tv)
        }

        // Scroll to now
        val nowOffset = System.currentTimeMillis() - epgStartTimeMs
        val nowPx = (nowOffset / 60000 * epgDp(EPG_PX_PER_MIN)).toInt()
        epgHsvTimeHeader?.post {
            epgHsvTimeHeader?.scrollTo(maxOf(0, nowPx - epgDp(100)), 0)
            epgHsvPrograms?.scrollTo(maxOf(0, nowPx - epgDp(100)), 0)
        }

        // Load channels
        loadEpgChannels()
    }

    private fun hideEpg() {
        if (epgOverlay == null) return
        isEpgVisible = false
        epgOverlay?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
            epgOverlay?.visibility = View.GONE
        }?.start()
    }

    private fun loadEpgChannels() {
        lifecycleScope.launch(Dispatchers.IO) {
            val playlists = db.playlistDao().getAll()
            if (playlists.isNotEmpty()) {
                val pl = playlists[0]
                if (pl.type == "XTREAM") {
                    epgXtreamApi = XtreamApi(pl.url ?: "", pl.username ?: "", pl.password ?: "")
                }
            }
            epgChannels = (db.channelDao().getByType(currentPlaylistId, "LIVE") ?: emptyList()).toMutableList()

            withContext(Dispatchers.Main) {
                epgRvChannels?.adapter = EpgChannelAdapter()
                epgRvPrograms?.adapter = EpgProgramAdapter()
            }
        }
    }

    private fun epgDp(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private fun epgShowInfo(ch: Channel, title: String?, time: String?) {
        if (!ch.logoUrl.isNullOrEmpty()) {
            Glide.with(this).load(ch.logoUrl).into(epgIvChannelLogo!!)
        }
        epgTvProgramTitle?.text = title ?: ch.name
        epgTvProgramTime?.text = time ?: ""
    }

    // EPG Channel sidebar adapter
    private inner class EpgChannelAdapter : RecyclerView.Adapter<EpgChannelAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val ch = epgChannels[pos]
            h.tvName.text = ch.name
            h.tvNumber.text = if (ch.channelNumber > 0) ch.channelNumber.toString() else ""
            if (!ch.logoUrl.isNullOrEmpty()) {
                Glide.with(h.ivLogo.context).load(ch.logoUrl).centerInside().into(h.ivLogo)
            }
            h.itemView.setOnFocusChangeListener { v, f ->
                v.setBackgroundColor(if (f) 0x200A84FF else Color.TRANSPARENT)
                if (f) epgShowInfo(ch, null, null)
            }
            h.itemView.setOnClickListener {
                hideEpg()
                playChannelFromList(ch, pos)
            }
        }

        override fun getItemCount(): Int = epgChannels.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivLogo: ImageView = v.findViewById(R.id.iv_logo)
            val tvNumber: TextView = v.findViewById(R.id.tv_number)
            val tvName: TextView = v.findViewById(R.id.tv_name)
        }
    }

    // EPG Program row adapter
    private inner class EpgProgramAdapter : RecyclerView.Adapter<EpgProgramAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val ch = epgChannels[pos]
            h.llPrograms.removeAllViews()
            val api = epgXtreamApi
            if (api != null && ch.streamId > 0) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val progs = api.getEpg(ch.streamId)
                        runOnUiThread { epgPopulateRow(h.llPrograms, progs, ch, pos) }
                    } catch (e: Exception) {
                        runOnUiThread { epgEmptyRow(h.llPrograms, pos) }
                    }
                }
            } else {
                epgEmptyRow(h.llPrograms, pos)
            }
        }

        override fun getItemCount(): Int = epgChannels.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val llPrograms: LinearLayout = v.findViewById(R.id.ll_programs)
        }
    }

    private fun epgEmptyRow(c: LinearLayout, pos: Int) {
        c.removeAllViews()
        c.addView(epgBlock(
            getString(R.string.no_program_info),
            EPG_PX_PER_MIN * 60 * EPG_HOURS,
            if (pos % 2 == 0) 0xFF1C1C2E.toInt() else 0xFF22223A.toInt(),
            null,
            ""
        ))
    }

    private fun epgPopulateRow(c: LinearLayout, progs: List<EpgProgram>?, ch: Channel, pos: Int) {
        c.removeAllViews()
        if (progs.isNullOrEmpty()) { epgEmptyRow(c, pos); return }
        val gridEnd = epgStartTimeMs + (EPG_HOURS * 60L * 60L * 1000L)
        val bg = if (pos % 2 == 0) 0xFF1C1C2E.toInt() else 0xFF22223A.toInt()
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        for (p in progs) {
            val s = p.startTime * 1000L
            val e = p.endTime * 1000L
            if (e < epgStartTimeMs || s > gridEnd) continue
            val ds = maxOf(s, epgStartTimeMs)
            val de = minOf(e, gridEnd)
            val mins = ((de - ds) / 60000).toInt()
            if (mins <= 0) continue
            val tr = sdf.format(Date(s)) + " - " + sdf.format(Date(e))
            c.addView(epgBlock(p.title ?: "", mins * EPG_PX_PER_MIN, bg, ch, tr))
        }
    }

    private fun epgBlock(title: String, widthDp: Int, bgColor: Int, ch: Channel?, time: String): TextView {
        val tv = TextView(this)
        tv.text = title
        tv.setTextColor(0xFFAAAAAA.toInt())
        tv.textSize = 12f
        tv.setPadding(epgDp(8), epgDp(4), epgDp(8), epgDp(4))
        tv.maxLines = 1
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.isFocusable = true
        tv.isFocusableInTouchMode = true
        tv.isClickable = true
        val bg = GradientDrawable()
        bg.setColor(bgColor)
        bg.cornerRadius = epgDp(4).toFloat()
        bg.setStroke(1, 0xFF2A2A3A.toInt())
        tv.background = bg
        val lp = LinearLayout.LayoutParams(epgDp(widthDp), epgDp(EPG_ROW_HEIGHT_DP - 4))
        lp.setMargins(0, epgDp(2), epgDp(1), epgDp(2))
        tv.layoutParams = lp
        tv.setOnFocusChangeListener { v, f ->
            val d = GradientDrawable()
            d.cornerRadius = epgDp(4).toFloat()
            if (f) {
                d.setColor(0xFF0A84FF.toInt())
                (v as TextView).setTextColor(0xFFFFFFFF.toInt())
                if (ch != null) epgShowInfo(ch, title, time)
            } else {
                d.setColor(bgColor)
                d.setStroke(1, 0xFF2A2A3A.toInt())
                (v as TextView).setTextColor(0xFFAAAAAA.toInt())
            }
            v.background = d
        }
        if (ch != null) {
            tv.setOnClickListener {
                hideEpg()
                playChannelFromList(ch, epgChannels.indexOf(ch))
            }
        }
        return tv
    }
}
