package com.salliptv.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.bumptech.glide.Glide
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.model.Channel
import com.salliptv.player.model.EpgProgram
import com.salliptv.player.parser.XtreamApi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SallIPTV-Player"
        private const val OVERLAY_TIMEOUT = 5000L
        private const val MINI_INFO_TIMEOUT = 3000L
        private const val EPG_REFRESH_INTERVAL = 60000L
        private const val CLOCK_UPDATE_INTERVAL = 30000L
        private const val NUMBER_INPUT_TIMEOUT = 2000L
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    // Top bar
    private lateinit var overlayTopBar: LinearLayout
    private lateinit var tvCategoryName: TextView
    private lateinit var tvClock: TextView

    // Program info overlay (bottom)
    private lateinit var overlayProgramInfo: LinearLayout
    private lateinit var ivChannelLogo: ImageView
    private lateinit var tvProgramTitle: TextView
    private lateinit var tvProgramTime: TextView
    private lateinit var tvProgramDuration: TextView
    private lateinit var tvChannelBadge: TextView
    private lateinit var tvProgramDesc: TextView
    private lateinit var tvNextProgram: TextView
    private lateinit var pbProgramProgress: ProgressBar

    // Stream info badges
    private lateinit var tvBadgeResolution: TextView
    private lateinit var tvBadgeCodec: TextView
    private lateinit var tvBadgeAudio: TextView

    // Channel strip (horizontal)
    private lateinit var rvChannelStrip: RecyclerView
    private lateinit var channelStripAdapter: ChannelStripAdapter

    // Action bar buttons
    private lateinit var btnTvGuide: LinearLayout
    private lateinit var btnSearch: LinearLayout
    private lateinit var btnChannels: LinearLayout
    private lateinit var btnAudio: LinearLayout
    private lateinit var btnSubtitles: LinearLayout

    // Mini info (channel switch)
    private lateinit var overlayMiniInfo: LinearLayout
    private lateinit var ivMiniLogo: ImageView
    private lateinit var tvMiniNumber: TextView
    private lateinit var tvMiniName: TextView
    private lateinit var tvMiniEpg: TextView

    // Channel list overlay (full, left side)
    private lateinit var overlayChannelList: LinearLayout
    private lateinit var rvOverlayChannels: RecyclerView
    private lateinit var tvOverlayTitle: TextView
    private lateinit var tvOverlayCount: TextView
    private lateinit var overlayAdapter: OverlayChannelAdapter
    private var isChannelListVisible = false

    // Number direct input
    private lateinit var tvNumberInput: TextView
    private val numberBuffer = StringBuilder()
    private var numberInputJob: Job? = null

    // Track selector
    private lateinit var overlayTrackSelector: LinearLayout
    private lateinit var tvTrackTitle: TextView
    private lateinit var rvTracks: RecyclerView
    private var isTrackSelectorVisible = false

    // State
    private var isOverlayVisible = false
    private var hideOverlayJob: Job? = null
    private var hideMiniInfoJob: Job? = null
    private var epgRefreshJob: Job? = null
    private var clockUpdateJob: Job? = null

    private lateinit var db: AppDatabase
    private var channelList: List<Channel> = emptyList()
    private var currentPosition = 0
    private var currentStreamUrl: String? = null
    private var currentStreamId = 0
    private var playlistId = -1
    private var currentChannelId = -1
    private var currentChannelName: String? = null
    private var currentChannelLogo: String? = null
    private var currentChannelNumber = 0
    private var currentGroupTitle: String? = null

    // Xtream API for EPG & catch-up
    private var xtreamApi: XtreamApi? = null
    private var isXtream = false
    private var currentEpg: List<EpgProgram>? = null
    private var currentProgram: EpgProgram? = null
    private var isCatchupMode = false
    private var isPremium = false

    // Quality fallback
    private var currentGroupId: String = ""
    private var currentChannelQuality: String? = null
    private val alternativeStreams = mutableListOf<Channel>()
    private var isFallingBack = false

    // VOD controls
    private var overlayVodControls: View? = null
    private var tvVodTitle: TextView? = null
    private var tvVodClock: TextView? = null
    private var tvVodCurrentTime: TextView? = null
    private var tvVodDuration: TextView? = null
    private var seekBarVod: SeekBar? = null
    private var btnVodPlayPause: ImageView? = null
    private var btnVodRewind: ImageView? = null
    private var btnVodForward: ImageView? = null
    private var btnVodAudio: ImageView? = null
    private var btnVodSubs: ImageView? = null
    private var isVodMode = false
    private var isVodOverlayVisible = false
    private var hideVodOverlayJob: Job? = null
    private var seekBarUpdateJob: Job? = null

    // Skip Intro / Up Next
    private var btnSkipIntro: TextView? = null
    private var cardUpNext: LinearLayout? = null
    private var ivUpNextPoster: ImageView? = null
    private var tvUpNextTitle: TextView? = null
    private var skipIntroShown = false
    private var skipIntroDismissed = false
    private var upNextShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        db = AppDatabase.getInstance(this)

        initViews()
        initPlayer()
        initActionBar()

        currentStreamUrl = intent.getStringExtra("streamUrl")
        currentChannelId = intent.getIntExtra("channelId", -1)
        currentPosition = intent.getIntExtra("currentPosition", 0)
        currentStreamId = intent.getIntExtra("streamId", 0)
        playlistId = intent.getIntExtra("playlistId", -1)
        isPremium = intent.getBooleanExtra("isPremium", false)
        currentChannelName = intent.getStringExtra("channelName")
        currentChannelLogo = intent.getStringExtra("channelLogo")
        currentChannelNumber = intent.getIntExtra("channelNumber", 0)
        currentGroupTitle = intent.getStringExtra("groupTitle")

        val channelType = intent.getStringExtra("channelType")

        // Quality fallback data
        currentGroupId = intent.getStringExtra("groupId") ?: ""
        currentChannelQuality = intent.getStringExtra("channelQuality")
        loadAlternativeStreams()
        isVodMode = channelType == "VOD" || channelType == "SERIES"

        loadChannelList()
        initXtreamApi()
        playStream(currentStreamUrl, currentChannelName, currentChannelLogo, currentChannelNumber)
        startClockUpdate()

        if (isVodMode) {
            initVodControls()
        }

        // First launch keyboard legend
        val prefs = getSharedPreferences("salliptv_player", MODE_PRIVATE)
        if (!prefs.getBoolean("legend_shown", false)) {
            val legend = findViewById<View>(R.id.overlay_keyboard_legend)
            legend?.visibility = View.VISIBLE
            legend?.setOnClickListener {
                legend.animate().alpha(0f).setDuration(300).withEndAction {
                    legend.visibility = View.GONE
                }.start()
                prefs.edit().putBoolean("legend_shown", true).apply()
            }
        }
    }

    // ==========================================
    // INIT
    // ==========================================

    private fun initViews() {
        playerView = findViewById(R.id.player_view)

        overlayTopBar = findViewById(R.id.overlay_top_bar)
        tvCategoryName = findViewById(R.id.tv_category_name)
        tvClock = findViewById(R.id.tv_clock)

        overlayProgramInfo = findViewById(R.id.overlay_program_info)
        ivChannelLogo = findViewById(R.id.iv_channel_logo)
        tvProgramTitle = findViewById(R.id.tv_program_title)
        tvProgramTime = findViewById(R.id.tv_program_time)
        tvProgramDuration = findViewById(R.id.tv_program_duration)
        tvChannelBadge = findViewById(R.id.tv_channel_badge)
        tvProgramDesc = findViewById(R.id.tv_program_desc)
        tvNextProgram = findViewById(R.id.tv_next_program)
        pbProgramProgress = findViewById(R.id.pb_program_progress)

        tvBadgeResolution = findViewById(R.id.tv_badge_resolution)
        tvBadgeCodec = findViewById(R.id.tv_badge_codec)
        tvBadgeAudio = findViewById(R.id.tv_badge_audio)

        rvChannelStrip = findViewById(R.id.rv_channel_strip)
        channelStripAdapter = ChannelStripAdapter()
        rvChannelStrip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvChannelStrip.adapter = channelStripAdapter

        btnTvGuide = findViewById(R.id.btn_tv_guide)
        btnSearch = findViewById(R.id.btn_search)
        btnChannels = findViewById(R.id.btn_channels)
        btnAudio = findViewById(R.id.btn_audio)
        btnSubtitles = findViewById(R.id.btn_subtitles)

        overlayMiniInfo = findViewById(R.id.overlay_mini_info)
        ivMiniLogo = findViewById(R.id.iv_mini_logo)
        tvMiniNumber = findViewById(R.id.tv_mini_number)
        tvMiniName = findViewById(R.id.tv_mini_name)
        tvMiniEpg = findViewById(R.id.tv_mini_epg)

        overlayChannelList = findViewById(R.id.overlay_channel_list)
        rvOverlayChannels = findViewById(R.id.rv_overlay_channels)
        tvOverlayTitle = findViewById(R.id.tv_overlay_title)
        tvOverlayCount = findViewById(R.id.tv_overlay_count)
        overlayAdapter = OverlayChannelAdapter()
        rvOverlayChannels.layoutManager = LinearLayoutManager(this)
        rvOverlayChannels.adapter = overlayAdapter

        tvNumberInput = findViewById(R.id.tv_number_input)

        overlayTrackSelector = findViewById(R.id.overlay_track_selector)
        tvTrackTitle = findViewById(R.id.tv_track_title)
        rvTracks = findViewById(R.id.rv_tracks)
        rvTracks.layoutManager = LinearLayoutManager(this)

        btnSkipIntro = findViewById(R.id.btn_skip_intro)
        cardUpNext = findViewById(R.id.card_up_next)
        ivUpNextPoster = findViewById(R.id.iv_up_next_poster)
        tvUpNextTitle = findViewById(R.id.tv_up_next_title)

        overlayVodControls = findViewById(R.id.overlay_vod_controls)
        tvVodTitle = findViewById(R.id.tv_vod_title)
        tvVodClock = findViewById(R.id.tv_vod_clock)
        tvVodCurrentTime = findViewById(R.id.tv_vod_current_time)
        tvVodDuration = findViewById(R.id.tv_vod_duration)
        seekBarVod = findViewById(R.id.seek_bar_vod)
        btnVodPlayPause = findViewById(R.id.btn_vod_play_pause)
        btnVodRewind = findViewById(R.id.btn_vod_rewind)
        btnVodForward = findViewById(R.id.btn_vod_forward)
        btnVodAudio = findViewById(R.id.btn_vod_audio)
        btnVodSubs = findViewById(R.id.btn_vod_subs)
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}")
                if (isCatchupMode) {
                    isCatchupMode = false
                    Toast.makeText(
                        this@PlayerActivity,
                        "Catch-up not available, returning to live",
                        Toast.LENGTH_SHORT
                    ).show()
                    playStream(currentStreamUrl, currentChannelName, currentChannelLogo, currentChannelNumber)
                } else if (!isFallingBack && alternativeStreams.isNotEmpty()) {
                    // Auto-fallback to next quality
                    isFallingBack = true
                    val next = alternativeStreams.removeAt(0)
                    val qualityLabel = next.qualityBadge ?: "autre qualité"
                    Toast.makeText(
                        this@PlayerActivity,
                        "${currentChannelQuality ?: "Flux"} indisponible → bascule $qualityLabel",
                        Toast.LENGTH_SHORT
                    ).show()
                    currentChannelQuality = next.qualityBadge
                    playStream(next.streamUrl, currentChannelName, currentChannelLogo, currentChannelNumber)
                } else {
                    Toast.makeText(
                        this@PlayerActivity,
                        "Playback error: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateStreamBadges()
            }
        })
    }

    private fun initActionBar() {
        val buttons = arrayOf<View>(btnTvGuide, btnSearch, btnChannels, btnAudio, btnSubtitles)
        for (btn in buttons) {
            btn.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.06f else 1f)
                    .scaleY(if (hasFocus) 1.06f else 1f)
                    .translationZ(if (hasFocus) 8f else 0f)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        btnChannels.setOnClickListener {
            hideFullOverlay()
            showChannelList()
        }

        // Quality selector
        val tvQualitySelector = findViewById<TextView>(R.id.tv_quality_selector)
        tvQualitySelector?.setOnClickListener { showQualityDialog() }
        tvQualitySelector?.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start()
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
        }

        btnSearch.setOnClickListener {
            hideFullOverlay()
            val searchIntent = Intent(this, SearchActivity::class.java)
            searchIntent.putExtra("playlistId", playlistId)
            searchIntent.putExtra("isPremium", isPremium)
            startActivity(searchIntent)
        }

        btnTvGuide.setOnClickListener {
            hideFullOverlay()
            val epgIntent = Intent(this, EpgActivity::class.java)
            epgIntent.putExtra("playlistId", playlistId)
            epgIntent.putExtra("isPremium", isPremium)
            epgIntent.putExtra("channelId", currentChannelId)
            epgIntent.putExtra("channelName", currentChannelName)
            startActivity(epgIntent)
        }

        btnAudio.setOnClickListener {
            hideFullOverlay()
            showTrackSelector(C.TRACK_TYPE_AUDIO)
        }

        btnSubtitles.setOnClickListener {
            hideFullOverlay()
            showTrackSelector(C.TRACK_TYPE_TEXT)
        }
    }

    // ==========================================
    // STREAM INFO BADGES
    // ==========================================

    private fun updateStreamBadges() {
        val p = player ?: return
        val tracks = p.currentTracks
        var foundVideo = false
        var foundAudio = false

        for (trackGroup in tracks.groups) {
            val trackType = trackGroup.type

            if (trackType == C.TRACK_TYPE_VIDEO && !foundVideo) {
                for (i in 0 until trackGroup.length) {
                    if (trackGroup.isTrackSelected(i)) {
                        val format = trackGroup.getTrackFormat(i)
                        foundVideo = true

                        if (format.height > 0) {
                            val res = when {
                                format.height >= 2160 -> "4K"
                                format.height >= 1080 -> "FHD"
                                format.height >= 720 -> "HD"
                                else -> "${format.height}p"
                            }
                            tvBadgeResolution.text = res
                            tvBadgeResolution.visibility = View.VISIBLE
                        }

                        if (format.codecs != null) {
                            var codec = format.codecs!!.uppercase(Locale.ROOT)
                            codec = when {
                                codec.contains("AVC") || codec.contains("H264") -> "H.264"
                                codec.contains("HEVC") || codec.contains("H265") -> "H.265"
                                codec.contains("VP9") -> "VP9"
                                codec.contains("AV01") || codec.contains("AV1") -> "AV1"
                                else -> codec
                            }
                            tvBadgeCodec.text = codec
                            tvBadgeCodec.visibility = View.VISIBLE
                        }
                        break
                    }
                }
            }

            if (trackType == C.TRACK_TYPE_AUDIO && !foundAudio) {
                for (i in 0 until trackGroup.length) {
                    if (trackGroup.isTrackSelected(i)) {
                        val format = trackGroup.getTrackFormat(i)
                        foundAudio = true

                        val audioInfo = when (format.channelCount) {
                            6 -> "5.1"
                            8 -> "7.1"
                            2 -> "STEREO"
                            1 -> "MONO"
                            else -> ""
                        }

                        if (audioInfo.isNotEmpty()) {
                            tvBadgeAudio.text = audioInfo
                            tvBadgeAudio.visibility = View.VISIBLE
                        }
                        break
                    }
                }
            }
        }
        // Update quality badge after resolution is detected
        updateQualityBadge()
    }

    // ==========================================
    // TRACK SELECTOR (Audio / Subtitles)
    // ==========================================

    private fun showTrackSelector(trackType: Int) {
        val p = player ?: return
        val tracks = p.currentTracks
        val trackInfos = mutableListOf<TrackInfo>()

        if (trackType == C.TRACK_TYPE_TEXT) {
            trackInfos.add(TrackInfo("Off", "", null, -1, true))
        }

        var hasSelected = false
        for (trackGroup in tracks.groups) {
            if (trackGroup.type != trackType) continue

            for (i in 0 until trackGroup.length) {
                val format = trackGroup.getTrackFormat(i)
                val selected = trackGroup.isTrackSelected(i)

                val name = when {
                    !format.label.isNullOrEmpty() -> format.label!!
                    format.language != null -> Locale(format.language).displayLanguage
                    else -> "Track ${trackInfos.size + 1}"
                }

                val info = when (trackType) {
                    C.TRACK_TYPE_AUDIO -> buildString {
                        if (format.channelCount > 0) append("${format.channelCount}ch")
                        if (format.codecs != null) append(" ${format.codecs}")
                    }.trim()
                    else -> format.codecs ?: ""
                }

                if (selected) hasSelected = true
                trackInfos.add(TrackInfo(name, info, trackGroup.mediaTrackGroup, i, selected))
            }
        }

        if (trackType == C.TRACK_TYPE_TEXT && !hasSelected && trackInfos.isNotEmpty()) {
            trackInfos[0].selected = true
        }

        if (trackInfos.isEmpty() || (trackType == C.TRACK_TYPE_TEXT && trackInfos.size <= 1)) {
            val msg = if (trackType == C.TRACK_TYPE_AUDIO) "No audio tracks" else "No subtitles"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        tvTrackTitle.text = if (trackType == C.TRACK_TYPE_AUDIO)
            getString(R.string.action_audio) else getString(R.string.action_subtitles)

        val adapter = TrackAdapter(trackInfos, trackType)
        rvTracks.adapter = adapter

        isTrackSelectorVisible = true
        overlayTrackSelector.visibility = View.VISIBLE
        overlayTrackSelector.alpha = 0f
        overlayTrackSelector.scaleX = 0.9f
        overlayTrackSelector.scaleY = 0.9f
        overlayTrackSelector.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()

        rvTracks.post {
            for (i in trackInfos.indices) {
                if (trackInfos[i].selected) {
                    rvTracks.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus()
                    break
                }
            }
        }
    }

    private fun hideTrackSelector() {
        isTrackSelectorVisible = false
        overlayTrackSelector.animate()
            .alpha(0f).scaleX(0.9f).scaleY(0.9f)
            .setDuration(150)
            .withEndAction { overlayTrackSelector.visibility = View.GONE }
            .start()
    }

    private fun selectTrack(trackInfo: TrackInfo, trackType: Int) {
        val p = player ?: return

        if (trackInfo.trackGroup == null) {
            p.setTrackSelectionParameters(
                p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(trackType, true)
                    .build()
            )
            saveTrackLanguage(trackType, "off")
        } else {
            p.setTrackSelectionParameters(
                p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(trackType, false)
                    .clearOverridesOfType(trackType)
                    .addOverride(TrackSelectionOverride(trackInfo.trackGroup, trackInfo.trackIndex))
                    .build()
            )
            val format = trackInfo.trackGroup.getFormat(trackInfo.trackIndex)
            if (format.language != null) {
                saveTrackLanguage(trackType, format.language!!)
                Log.i(TAG, "Saved preferred ${if (trackType == C.TRACK_TYPE_AUDIO) "audio" else "subtitle"} language: ${format.language}")
            }
        }

        hideTrackSelector()
    }

    private fun saveTrackLanguage(trackType: Int, language: String) {
        val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        val key = if (trackType == C.TRACK_TYPE_AUDIO) "preferred_audio_lang" else "preferred_subtitle_lang"
        prefs.edit().putString(key, language).apply()
    }

    private fun getPreferredLanguage(trackType: Int): String? {
        val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        val key = if (trackType == C.TRACK_TYPE_AUDIO) "preferred_audio_lang" else "preferred_subtitle_lang"
        return prefs.getString(key, null)
    }

    private fun applyPreferredLanguages() {
        if (player == null) return
        if (!isVodMode) return

        lifecycleScope.launch {
            delay(2000)
            val currentPlayer = player ?: return@launch
            val tracks = currentPlayer.currentTracks
            val prefAudio = getPreferredLanguage(C.TRACK_TYPE_AUDIO)
            val prefSub = getPreferredLanguage(C.TRACK_TYPE_TEXT)
            val paramsBuilder = currentPlayer.trackSelectionParameters.buildUpon()
            var changed = false

            if (prefAudio != null && prefAudio != "off") {
                outer@ for (trackGroup in tracks.groups) {
                    if (trackGroup.type != C.TRACK_TYPE_AUDIO) continue
                    for (i in 0 until trackGroup.length) {
                        val format = trackGroup.getTrackFormat(i)
                        if (prefAudio == format.language) {
                            paramsBuilder
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                .addOverride(TrackSelectionOverride(trackGroup.mediaTrackGroup, i))
                            changed = true
                            Log.i(TAG, "Applied preferred audio: ${format.language}")
                            break@outer
                        }
                    }
                }
            }

            var subChanged = false
            if (prefSub != null) {
                if (prefSub == "off") {
                    paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    subChanged = true
                } else {
                    outer@ for (trackGroup in tracks.groups) {
                        if (trackGroup.type != C.TRACK_TYPE_TEXT) continue
                        for (i in 0 until trackGroup.length) {
                            val format = trackGroup.getTrackFormat(i)
                            if (prefSub == format.language) {
                                paramsBuilder
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                    .addOverride(TrackSelectionOverride(trackGroup.mediaTrackGroup, i))
                                subChanged = true
                                Log.i(TAG, "Applied preferred subtitle: ${format.language}")
                                break@outer
                            }
                        }
                    }
                }
            }

            if (changed || subChanged) {
                currentPlayer.setTrackSelectionParameters(paramsBuilder.build())
            }
        }
    }

    // ==========================================
    // NUMBER DIRECT INPUT
    // ==========================================

    private fun onNumberPressed(digit: Int) {
        numberBuffer.append(digit)
        tvNumberInput.alpha = 1f
        tvNumberInput.translationY = 0f
        tvNumberInput.visibility = View.VISIBLE

        // Preview channel while typing number
        val targetNumber = numberBuffer.toString().toIntOrNull() ?: 0
        val matchedChannel = channelList.firstOrNull { it.channelNumber == targetNumber }
        if (matchedChannel != null) {
            tvNumberInput.text = "$numberBuffer  ${matchedChannel.cleanName ?: matchedChannel.name}"
        } else {
            tvNumberInput.text = numberBuffer.toString()
        }

        numberInputJob?.cancel()
        numberInputJob = lifecycleScope.launch {
            delay(NUMBER_INPUT_TIMEOUT)
            var targetNumber = 0
            try {
                targetNumber = numberBuffer.toString().toInt()
            } catch (_: NumberFormatException) {}

            numberBuffer.setLength(0)
            animateOut(tvNumberInput)

            if (channelList.isNotEmpty() && targetNumber > 0) {
                val idx = channelList.indexOfFirst { it.channelNumber == targetNumber }
                if (idx >= 0) {
                    switchToChannel(idx)
                    return@launch
                }
                val directIdx = targetNumber - 1
                if (directIdx >= 0 && directIdx < channelList.size) {
                    switchToChannel(directIdx)
                    return@launch
                }
                Toast.makeText(this@PlayerActivity, "Channel $targetNumber not found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==========================================
    // FULL OVERLAY (OK press)
    // ==========================================

    private fun showFullOverlay() {
        if (isChannelListVisible || isTrackSelectorVisible) return

        isOverlayVisible = true

        tvCategoryName.text = currentGroupTitle ?: currentChannelName ?: ""

        updateClock()
        updateProgramInfo()
        updateStreamBadges()

        // Favourite toggle
        val btnFavOverlay = findViewById<TextView>(R.id.btn_fav_overlay)
        btnFavOverlay?.text = if (channelList.getOrNull(currentPosition)?.isFavorite == true) "♥" else "♡"
        btnFavOverlay?.setTextColor(if (channelList.getOrNull(currentPosition)?.isFavorite == true) 0xFFFF2D55.toInt() else 0xFF8E8E93.toInt())
        btnFavOverlay?.setOnClickListener {
            val ch = channelList.getOrNull(currentPosition) ?: return@setOnClickListener
            ch.isFavorite = !ch.isFavorite
            btnFavOverlay.text = if (ch.isFavorite) "♥" else "♡"
            btnFavOverlay.setTextColor(if (ch.isFavorite) 0xFFFF2D55.toInt() else 0xFF8E8E93.toInt())
            btnFavOverlay.animate().scaleX(1.3f).scaleY(1.3f).setDuration(150).withEndAction {
                btnFavOverlay.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }.start()
            lifecycleScope.launch(Dispatchers.IO) {
                db.channelDao().updateFavorite(ch.id, ch.isFavorite)
            }
        }
        btnFavOverlay?.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundColor(if (hasFocus) 0x40FFFFFF else 0x00000000)
        }

        // Quality selector focus
        val qualBadge = findViewById<TextView>(R.id.tv_quality_selector)
        qualBadge?.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundColor(if (hasFocus) 0x60FFFFFF else 0x33FFFFFF)
            (v as TextView).setTextColor(if (hasFocus) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }

        // Load recent channels for the strip (exclude current)
        lifecycleScope.launch(Dispatchers.IO) {
            val recent = db.channelDao().getRecent(playlistId).filter { it.id != currentChannelId }.take(8)
            withContext(Dispatchers.Main) {
                val strip = findViewById<android.widget.LinearLayout?>(R.id.strip_recent)
                val sep = findViewById<View?>(R.id.strip_separator)
                if (strip != null && recent.isNotEmpty()) {
                    strip.removeAllViews()
                    for (ch in recent) {
                        val iv = ImageView(this@PlayerActivity).apply {
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                                marginStart = 4; marginEnd = 4
                            }
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setPadding(6, 6, 6, 6)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            isClickable = true
                            setOnClickListener {
                                // Play directly — don't search in channelList (recent may be from other groups)
                                hideFullOverlay()
                                currentChannelId = ch.id
                                currentStreamId = ch.streamId
                                currentChannelName = ch.cleanName ?: ch.name
                                currentChannelLogo = ch.logoUrl
                                currentStreamUrl = ch.streamUrl
                                playStream(ch.streamUrl, currentChannelName, ch.logoUrl, ch.channelNumber)
                                loadAlternativeStreams()
                            }
                            setOnFocusChangeListener { v, hasFocus ->
                                v.animate()
                                    .scaleX(if (hasFocus) 1.25f else 1f)
                                    .scaleY(if (hasFocus) 1.25f else 1f)
                                    .alpha(if (hasFocus) 1f else 0.7f)
                                    .setDuration(150)
                                    .setInterpolator(DecelerateInterpolator())
                                    .start()
                            }
                            alpha = 0.7f
                            // Rounded dark background to mask ugly logo backgrounds
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(0xFF1C1C1E.toInt())
                                cornerRadius = 10f * context.resources.displayMetrics.density
                            }
                        }
                        if (!ch.logoUrl.isNullOrEmpty()) {
                            Glide.with(this@PlayerActivity).load(ch.logoUrl).centerInside().into(iv)
                        }
                        strip.addView(iv)
                    }
                    strip.visibility = View.VISIBLE
                    sep?.visibility = View.VISIBLE
                } else {
                    strip?.visibility = View.GONE
                    sep?.visibility = View.GONE
                }
            }
        }

        animateIn(overlayTopBar)
        animateIn(overlayProgramInfo)

        rvChannelStrip.post {
            rvChannelStrip.findViewHolderForAdapterPosition(currentPosition)?.itemView?.requestFocus()
        }

        hideOverlayJob?.cancel()
        hideOverlayJob = lifecycleScope.launch {
            delay(OVERLAY_TIMEOUT)
            hideFullOverlay()
        }
    }

    private fun hideFullOverlay() {
        if (!isOverlayVisible) return
        isOverlayVisible = false
        hideOverlayJob?.cancel()
        qualityPopup?.dismiss()
        qualityPopup = null
        animateOut(overlayTopBar)
        animateOut(overlayProgramInfo)
        findViewById<View?>(R.id.overlay_info_panel)?.visibility = View.GONE
    }

    private fun toggleFullOverlay() {
        if (isOverlayVisible) hideFullOverlay() else showFullOverlay()
    }

    private fun updateProgramInfo() {
        if (!currentChannelLogo.isNullOrEmpty()) {
            Glide.with(this).load(currentChannelLogo).into(ivChannelLogo)
            ivChannelLogo.visibility = View.VISIBLE
        } else {
            ivChannelLogo.visibility = View.GONE
        }

        tvChannelBadge.text = if (currentChannelNumber > 0) "$currentChannelNumber" else ""
        tvProgramTitle.text = currentChannelName ?: ""

        // Show LIVE badge
        val liveBadge = findViewById<TextView?>(R.id.tv_channel_name)
        if (!isVodMode) {
            liveBadge?.visibility = View.VISIBLE
        }

        val program = currentProgram
        if (program != null) {
            // Current program
            findViewById<TextView>(R.id.tv_now_playing).text = "${formatTime(program.startTime)} - ${formatTime(program.endTime)}  ${program.title}"
            findViewById<TextView>(R.id.tv_now_playing).visibility = View.VISIBLE

            tvProgramTime.text = "${formatTime(program.startTime)} - ${formatTime(program.endTime)}"

            val totalSec = program.endTime - program.startTime
            val elapsedSec = (System.currentTimeMillis() / 1000) - program.startTime
            tvProgramDuration.text = "${(totalSec / 60).toInt()} min"

            if (totalSec > 0) {
                val progress = ((elapsedSec * 100) / totalSec).toInt()
                pbProgramProgress.progress = progress.coerceIn(0, 100)
                pbProgramProgress.visibility = View.VISIBLE
            }

            // Description and next program are only shown in the info panel (DOWN press)
            tvProgramDesc.visibility = View.GONE
        } else {
            findViewById<TextView>(R.id.tv_now_playing).visibility = View.GONE
            tvProgramTime.text = ""
            tvProgramDuration.text = ""
            tvProgramDesc.visibility = View.GONE
            pbProgramProgress.visibility = View.INVISIBLE
        }

        // Next program is only shown in the info panel (DOWN press)
        tvNextProgram.visibility = View.GONE
    }

    // ==========================================
    // MINI INFO (channel up/down)
    // ==========================================

    private fun showMiniInfo(ch: Channel) {
        if (isOverlayVisible) return

        tvMiniNumber.text = if (ch.channelNumber > 0) ch.channelNumber.toString() else ""
        tvMiniName.text = ch.cleanName ?: ch.name

        if (!ch.logoUrl.isNullOrEmpty()) {
            Glide.with(this).load(ch.logoUrl).into(ivMiniLogo)
            ivMiniLogo.visibility = View.VISIBLE
        } else {
            ivMiniLogo.visibility = View.GONE
        }

        // Show current EPG program in mini info
        tvMiniEpg.text = ""
        if (!ch.cleanName.isNullOrEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val programs = loadEpgFromBackend(ch.cleanName!!)
                    val now = System.currentTimeMillis() / 1000
                    val current = programs.firstOrNull { it.startTime <= now && it.endTime >= now }
                    withContext(Dispatchers.Main) {
                        if (current != null) {
                            tvMiniEpg.text = "${formatTime(current.startTime)} ${current.title}"
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        animateIn(overlayMiniInfo)

        hideMiniInfoJob?.cancel()
        hideMiniInfoJob = lifecycleScope.launch {
            delay(MINI_INFO_TIMEOUT)
            animateOut(overlayMiniInfo)
        }
    }

    // ==========================================
    // ANIMATIONS (Apple TV smooth)
    // ==========================================

    private fun animateIn(view: View) {
        if (view.visibility == View.VISIBLE && view.alpha == 1f) return
        view.alpha = 0f
        val fromTop = view.id == R.id.overlay_top_bar
                || view.id == R.id.overlay_mini_info
                || view.id == R.id.tv_number_input
        view.translationY = if (fromTop) -20f else 20f
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f).translationY(0f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateOut(view: View) {
        if (view.visibility != View.VISIBLE) return
        val toTop = view.id == R.id.overlay_top_bar
                || view.id == R.id.overlay_mini_info
                || view.id == R.id.tv_number_input
        val ty = if (toTop) -20f else 20f
        view.animate()
            .alpha(0f).translationY(ty)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { view.visibility = View.GONE }
            .start()
    }

    // ==========================================
    // CLOCK
    // ==========================================

    private fun startClockUpdate() {
        clockUpdateJob?.cancel()
        clockUpdateJob = lifecycleScope.launch {
            while (isActive) {
                updateClock()
                delay(CLOCK_UPDATE_INTERVAL)
            }
        }
    }

    private fun updateClock() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        tvClock.text = sdf.format(Date())
    }

    // ==========================================
    // VOD / SERIES CONTROLS
    // ==========================================

    private fun initVodControls() {
        tvVodTitle?.text = currentChannelName

        seekBarVod?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0L
                    if (duration > 0) {
                        val seekPos = (duration * progress) / 1000
                        player?.seekTo(seekPos)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                hideVodOverlayJob?.cancel()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                scheduleVodHide()
            }
        })

        btnVodPlayPause?.setOnClickListener {
            player?.let { p ->
                if (p.isPlaying) {
                    p.pause()
                    btnVodPlayPause?.setImageResource(R.drawable.ic_play_arrow)
                } else {
                    p.play()
                    btnVodPlayPause?.setImageResource(R.drawable.ic_pause)
                }
            }
            scheduleVodHide()
        }

        btnVodRewind?.setOnClickListener {
            player?.let { p -> p.seekTo(maxOf(0L, p.currentPosition - 10000L)) }
            scheduleVodHide()
        }

        btnVodForward?.setOnClickListener {
            player?.let { p -> p.seekTo(minOf(p.duration, p.currentPosition + 10000L)) }
            scheduleVodHide()
        }

        btnVodAudio?.setOnClickListener { showTrackSelector(C.TRACK_TYPE_AUDIO) }
        btnVodSubs?.setOnClickListener { showTrackSelector(C.TRACK_TYPE_TEXT) }

        val vodControls = arrayOf(btnVodRewind, btnVodPlayPause, btnVodForward, btnVodAudio, btnVodSubs)
        for (ctrl in vodControls) {
            ctrl?.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.08f else 1f)
                    .scaleY(if (hasFocus) 1.08f else 1f)
                    .setDuration(150).start()
            }
        }

        btnSkipIntro?.apply {
            setOnClickListener {
                player?.let { p ->
                    p.seekTo(minOf(p.duration, p.currentPosition + 90000L))
                }
                visibility = View.GONE
                skipIntroDismissed = true
            }
            setOnFocusChangeListener { v, hasFocus ->
                setTextColor(if (hasFocus) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                v.animate().scaleX(if (hasFocus) 1.05f else 1f)
                    .scaleY(if (hasFocus) 1.05f else 1f)
                    .setDuration(150).start()
            }
        }

        cardUpNext?.apply {
            setOnClickListener { playNextInList() }
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.03f else 1f)
                    .scaleY(if (hasFocus) 1.03f else 1f)
                    .setDuration(150).start()
            }
        }

        seekBarUpdateJob?.cancel()
        seekBarUpdateJob = lifecycleScope.launch {
            while (isActive) {
                updateVodProgress()
                checkSkipIntro()
                checkUpNext()
                delay(1000)
            }
        }
    }

    private fun checkSkipIntro() {
        val p = player ?: return
        if (skipIntroDismissed || btnSkipIntro == null) return
        val pos = p.currentPosition
        when {
            pos > 20000 && pos < 120000 && !skipIntroShown -> {
                skipIntroShown = true
                btnSkipIntro?.visibility = View.VISIBLE
                btnSkipIntro?.alpha = 0f
                btnSkipIntro?.animate()?.alpha(1f)?.setDuration(300)?.start()
            }
            pos >= 120000 && btnSkipIntro?.visibility == View.VISIBLE -> {
                btnSkipIntro?.animate()?.alpha(0f)?.setDuration(200)
                    ?.withEndAction { btnSkipIntro?.visibility = View.GONE }?.start()
            }
        }
    }

    private fun checkUpNext() {
        val p = player ?: return
        if (cardUpNext == null || upNextShown) return
        val dur = p.duration
        val pos = p.currentPosition
        if (dur <= 0) return

        val remaining = dur - pos
        if (remaining < 180000 && remaining > 0 && dur > 300000) {
            upNextShown = true
            if (currentPosition + 1 < channelList.size) {
                val next = channelList[currentPosition + 1]
                tvUpNextTitle?.text = next.name
                if (!next.logoUrl.isNullOrEmpty()) {
                    Glide.with(this).load(next.logoUrl).centerCrop().into(ivUpNextPoster!!)
                }
                cardUpNext?.visibility = View.VISIBLE
                cardUpNext?.alpha = 0f
                cardUpNext?.animate()?.alpha(1f)?.setDuration(400)?.start()
            }
        }
    }

    private fun playNextInList() {
        if (currentPosition + 1 < channelList.size) {
            val next = channelList[++currentPosition]
            playStream(next.streamUrl, next.name, next.logoUrl, next.channelNumber)
            tvVodTitle?.text = next.name
            cardUpNext?.visibility = View.GONE
            upNextShown = false
            skipIntroShown = false
            skipIntroDismissed = false
        }
    }

    private fun updateVodProgress() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (dur <= 0) return

        if (seekBarVod?.isPressed == false) {
            seekBarVod?.progress = (pos * 1000 / dur).toInt()
        }
        tvVodCurrentTime?.text = formatDuration(pos)
        tvVodDuration?.text = "-${formatDuration(dur - pos)}"
        btnVodPlayPause?.setImageResource(if (p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun showVodOverlay() {
        val overlay = overlayVodControls ?: return
        isVodOverlayVisible = true
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(200).start()
        updateVodProgress()
        tvVodClock?.let {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            it.text = sdf.format(Date())
        }
        scheduleVodHide()
    }

    private fun hideVodOverlay() {
        val overlay = overlayVodControls ?: return
        isVodOverlayVisible = false
        overlay.animate().alpha(0f).setDuration(200)
            .withEndAction { overlay.visibility = View.GONE }
            .start()
    }

    private fun scheduleVodHide() {
        hideVodOverlayJob?.cancel()
        hideVodOverlayJob = lifecycleScope.launch {
            delay(OVERLAY_TIMEOUT)
            hideVodOverlay()
        }
    }

    // ==========================================
    // CHANNEL LIST OVERLAY
    // ==========================================

    private fun loadChannelList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val type = intent.getStringExtra("channelType") ?: "LIVE"
            val plId = intent.getIntExtra("playlistId", 1)
            val group = intent.getStringExtra("groupTitle")

            // Load channels from same group (not all 8000+ channels)
            val channels = if (!group.isNullOrEmpty()) {
                db.channelDao().getByGroup(plId, group, type)
            } else {
                db.channelDao().getByType(plId, type)
            }

            withContext(Dispatchers.Main) {
                if (channels.isNotEmpty()) {
                    channelList = channels

                    // Find current channel position in filtered list
                    val currentId = intent.getIntExtra("channelId", -1)
                    val idx = channels.indexOfFirst { it.id == currentId }
                    if (idx >= 0) currentPosition = idx

                    overlayAdapter.setChannels(channels, currentPosition)
                    channelStripAdapter.setChannels(channels, currentPosition)
                    tvOverlayTitle.text = group ?: when (type) {
                        "VOD" -> getString(R.string.tab_vod)
                        "SERIES" -> getString(R.string.tab_series)
                        else -> getString(R.string.tab_live)
                    }
                    tvOverlayCount.text = channels.size.toString()
                }
            }
        }
    }

    private fun showChannelList() {
        if (channelList.isEmpty()) return

        isChannelListVisible = true
        hideFullOverlay()

        overlayAdapter.setCurrentPlaying(currentPosition)
        overlayChannelList.visibility = View.VISIBLE

        rvOverlayChannels.scrollToPosition(maxOf(0, currentPosition - 3))
        rvOverlayChannels.post {
            rvOverlayChannels.findViewHolderForAdapterPosition(currentPosition)?.itemView?.requestFocus()
        }
    }

    private fun hideChannelList() {
        isChannelListVisible = false
        overlayChannelList.visibility = View.GONE
    }

    private fun onOverlayChannelSelected(position: Int) {
        if (position < 0 || position >= channelList.size) return
        hideChannelList()
        switchToChannel(position)
    }

    // ==========================================
    // XTREAM & EPG
    // ==========================================

    private fun initXtreamApi() {
        Log.d(TAG, "initXtreamApi: playlistId=$playlistId")
        if (playlistId < 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            val pl = db.playlistDao().getById(playlistId)
            Log.d(TAG, "initXtreamApi: pl=${pl?.type}, url=${pl?.url?.take(50)}")
            if (pl == null) return@launch

            if (pl.type == "XTREAM" && !pl.url.isNullOrEmpty()) {
                xtreamApi = XtreamApi(pl.url!!, pl.username ?: "", pl.password ?: "")
                isXtream = true
            } else if (pl.type == "M3U" && !pl.url.isNullOrEmpty()) {
                val url = pl.url!!
                val userMatch = Regex("[?&]username=([^&]+)").find(url)
                val passMatch = Regex("[?&]password=([^&]+)").find(url)
                Log.d(TAG, "initXtreamApi: M3U detect user=${userMatch != null} pass=${passMatch != null}")
                if (userMatch != null && passMatch != null) {
                    val serverUrl = url.substringBefore("/get.php")
                    val user = userMatch.groupValues[1]
                    val pass = passMatch.groupValues[1]
                    Log.d(TAG, "initXtreamApi: Xtream detected server=$serverUrl user=$user")
                    xtreamApi = XtreamApi(serverUrl, user, pass)
                    isXtream = true
                }
            }

            Log.d(TAG, "initXtreamApi: isXtream=$isXtream, streamId=$currentStreamId")
            if (isXtream && currentStreamId > 0) {
                loadEpg(currentStreamId)
            }
        }
    }

    private fun loadEpg(streamId: Int) {
        Log.d(TAG, "loadEpg: streamId=$streamId, channelName=$currentChannelName")
        lifecycleScope.launch(Dispatchers.IO) {
            var programs: List<com.salliptv.player.model.EpgProgram> = emptyList()

            // Try 1: Our backend EPG API (most complete, open source EPG)
            if (!currentChannelName.isNullOrEmpty()) {
                try {
                    programs = loadEpgFromBackend(currentChannelName!!)
                    Log.d(TAG, "Backend EPG: ${programs.size} programs for '$currentChannelName'")
                } catch (e: Exception) {
                    Log.d(TAG, "Backend EPG failed: ${e.message}")
                }
            }

            // Try 2: Xtream API fallback (if backend had nothing)
            if (programs.isEmpty() && isXtream && xtreamApi != null && streamId > 0) {
                try {
                    programs = xtreamApi!!.getEpg(streamId)
                    Log.d(TAG, "Xtream EPG fallback: ${programs.size} programs")
                } catch (e: Exception) {
                    Log.d(TAG, "Xtream EPG failed: ${e.message}")
                }
            }

            val currentTime = System.currentTimeMillis() / 1000
            Log.d(TAG, "EPG result: ${programs.size} programs, currentTime=$currentTime")

            if (programs.isNotEmpty()) {
                currentEpg = programs
                val now = programs.firstOrNull { it.startTime <= currentTime && it.endTime >= currentTime }
                val next = programs.firstOrNull { it.startTime > currentTime }
                currentProgram = now
                Log.d(TAG, "EPG now=${now?.title}, next=${next?.title}")

                withContext(Dispatchers.Main) {
                    if (now != null && overlayMiniInfo.visibility == View.VISIBLE) {
                        tvMiniEpg.text = now.title
                    }
                    if (isOverlayVisible) updateProgramInfo()
                }
            }
        }
    }

    private fun loadEpgFromBackend(channelName: String): List<com.salliptv.player.model.EpgProgram> {
        val url = "https://salliptv.com/api/epg/by-name/${java.net.URLEncoder.encode(channelName, "UTF-8")}"
        val request = okhttp3.Request.Builder().url(url).get().build()
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()

        val body = response.body?.string() ?: return emptyList()
        val json = com.google.gson.JsonParser.parseString(body).asJsonObject
        val programsArray = json.getAsJsonArray("programs") ?: return emptyList()

        return programsArray.mapNotNull { elem ->
            val obj = elem.asJsonObject
            val title = obj.get("title")?.asString ?: return@mapNotNull null
            val start = obj.get("start")?.asLong ?: return@mapNotNull null
            val end = obj.get("end")?.asLong ?: return@mapNotNull null

            com.salliptv.player.model.EpgProgram(
                title = title,
                startTime = start,
                endTime = end,
                description = obj.get("desc")?.asString
            )
        }
    }

    private fun startEpgRefresh() {
        stopEpgRefresh()
        epgRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(EPG_REFRESH_INTERVAL)
                loadEpg(currentStreamId)
            }
        }
    }

    private fun stopEpgRefresh() {
        epgRefreshJob?.cancel()
        epgRefreshJob = null
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp * 1000))

    // ==========================================
    // CATCH-UP / REPLAY
    // ==========================================

    private fun playCatchup(program: EpgProgram) {
        val api = xtreamApi ?: return
        if (currentStreamId <= 0) return
        val durationMinutes = ((program.endTime - program.startTime) / 60).toInt()
        val catchupUrl = api.buildCatchupUrl(currentStreamId, program.startTime, durationMinutes)
        isCatchupMode = true
        Toast.makeText(this, getString(R.string.catchup_playing, program.title), Toast.LENGTH_SHORT).show()
        val mediaItem = MediaItem.fromUri(Uri.parse(catchupUrl))
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()
        currentProgram = program
        if (isOverlayVisible) updateProgramInfo()
    }

    private fun returnToLive() {
        if (!isCatchupMode) return
        isCatchupMode = false
        player?.setMediaItem(MediaItem.fromUri(Uri.parse(currentStreamUrl)))
        player?.prepare()
        player?.play()
        Toast.makeText(this, "Back to live", Toast.LENGTH_SHORT).show()
        if (currentStreamId > 0) loadEpg(currentStreamId)
    }

    // ==========================================
    // PLAYBACK
    // ==========================================

    // ============================================================================
    // QUALITY FALLBACK
    // ============================================================================

    private var allQualityVariants = mutableListOf<Channel>()

    private fun loadAlternativeStreams() {
        if (currentGroupId.isEmpty() || playlistId < 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(this@PlayerActivity)
            // Load ALL variants (including hidden ones) for this groupId
            val all = db.channelDao().getAlternativeVersions(playlistId, currentGroupId, -1)
            // Also include current channel
            val current = db.channelDao().getById(currentChannelId)
            withContext(Dispatchers.Main) {
                allQualityVariants.clear()
                if (current != null) allQualityVariants.add(current)
                allQualityVariants.addAll(all)
                // Remove duplicates by id
                allQualityVariants = allQualityVariants.distinctBy { it.id }.toMutableList()

                // Alternatives = all except current
                alternativeStreams.clear()
                alternativeStreams.addAll(allQualityVariants.filter { it.id != currentChannelId })
                updateQualityBadge()
            }
        }
    }

    private fun updateQualityBadge() {
        val badge = findViewById<TextView>(R.id.tv_quality_selector)
        badge?.let {
            val q = currentChannelQuality ?: tvBadgeResolution?.text?.toString() ?: ""
            val altCount = alternativeStreams.size
            if (q.isNotEmpty()) {
                it.text = if (altCount > 0) "$q  +$altCount" else q
            } else if (altCount > 0) {
                it.text = "+$altCount"
            } else {
                it.visibility = View.GONE
                return
            }
            it.visibility = View.VISIBLE
        }
    }

    private var qualityPopup: android.widget.PopupWindow? = null

    private fun showQualityDialog() {
        if (allQualityVariants.size <= 1) {
            Toast.makeText(this, "Une seule qualité disponible", Toast.LENGTH_SHORT).show()
            return
        }

        // Dismiss old popup if it exists
        qualityPopup?.dismiss()
        qualityPopup = null

        val trackSelector = overlayTrackSelector
        tvTrackTitle.text = "Qualité"

        val sortedVariants = allQualityVariants.sortedByDescending {
            when (it.qualityBadge) { "4K" -> 5; "FHD" -> 4; "HD" -> 3; "SD" -> 1; else -> 0 }
        }

        rvTracks.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        rvTracks.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class QVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = TextView(this@PlayerActivity).apply {
                    textSize = 15f
                    setPadding(24, 14, 24, 14)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                }
                return QVH(tv)
            }

            override fun getItemCount() = sortedVariants.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val ch = sortedVariants[position]
                val vh = holder as QVH
                val label = ch.qualityBadge ?: "Auto"
                val isCurrent = ch.id == currentChannelId
                vh.tv.text = if (isCurrent) "$label  ●" else label
                vh.tv.setTextColor(if (isCurrent) Color.WHITE else 0xFFB0B0B5.toInt())

                vh.tv.setOnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundColor(if (hasFocus) 0x40FFFFFF else Color.TRANSPARENT)
                    (v as TextView).setTextColor(if (hasFocus) Color.WHITE else if (isCurrent) Color.WHITE else 0xFFB0B0B5.toInt())
                }

                vh.tv.setOnClickListener {
                    if (!isCurrent) {
                        isFallingBack = false
                        currentChannelId = ch.id
                        currentStreamId = ch.streamId
                        currentChannelQuality = ch.qualityBadge
                        currentStreamUrl = ch.streamUrl
                        player?.setMediaItem(MediaItem.fromUri(Uri.parse(ch.streamUrl)))
                        player?.prepare()
                        player?.play()
                        Log.i(TAG, "Quality: ${ch.qualityBadge}")
                        alternativeStreams.clear()
                        alternativeStreams.addAll(allQualityVariants.filter { it.id != currentChannelId })
                        updateQualityBadge()
                    }
                    hideTrackSelector()
                }
            }
        }

        isTrackSelectorVisible = true
        trackSelector.visibility = View.VISIBLE
        trackSelector.alpha = 0f
        trackSelector.scaleX = 0.9f
        trackSelector.scaleY = 0.9f
        trackSelector.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
        // DON'T hide overlay — keep it visible behind

        rvTracks.post {
            rvTracks.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun playStream(url: String?, name: String?, logo: String?, number: Int) {
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, "No stream URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentStreamUrl = url
        currentChannelName = name
        currentChannelLogo = logo
        currentChannelNumber = number
        isCatchupMode = false
        currentProgram = null
        currentEpg = null

        tvBadgeResolution.visibility = View.GONE
        tvBadgeCodec.visibility = View.GONE
        tvBadgeAudio.visibility = View.GONE

        player?.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player?.prepare()
        player?.play()

        if (isVodMode) applyPreferredLanguages()

        loadEpg(currentStreamId)
        startEpgRefresh()

        Log.i(TAG, "Playing: $name -> $url")
    }

    private fun switchToChannel(position: Int) {
        if (position < 0 || position >= channelList.size) return

        currentPosition = position
        val ch = channelList[position]
        currentChannelId = ch.id
        currentStreamId = ch.streamId
        currentStreamUrl = ch.streamUrl
        currentChannelName = ch.cleanName ?: ch.name
        currentChannelLogo = ch.logoUrl
        currentChannelNumber = ch.channelNumber
        currentGroupTitle = ch.groupTitle

        showMiniInfo(ch)
        playStream(ch.streamUrl, currentChannelName, ch.logoUrl, ch.channelNumber)

        lifecycleScope.launch(Dispatchers.IO) {
            db.channelDao().updateLastWatched(ch.id, System.currentTimeMillis())
        }
    }

    // ==========================================
    // KEY HANDLING (D-pad / Remote)
    // ==========================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Dismiss first-launch legend on any key press
        val legend = findViewById<View?>(R.id.overlay_keyboard_legend)
        if (legend != null && legend.visibility == View.VISIBLE) {
            legend.animate().alpha(0f).setDuration(300).withEndAction {
                legend.visibility = View.GONE
            }.start()
            getSharedPreferences("salliptv_player", MODE_PRIVATE).edit().putBoolean("legend_shown", true).apply()
            return true
        }

        if (isTrackSelectorVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                hideTrackSelector()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (isChannelListVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    hideChannelList()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val focused = rvOverlayChannels.focusedChild
                    if (focused != null) {
                        val pos = rvOverlayChannels.getChildAdapterPosition(focused)
                        if (pos >= 0) onOverlayChannelSelected(pos)
                    }
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        if (isOverlayVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    hideFullOverlay()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // First DOWN: focus recent channels strip
                    // Second DOWN: focus quality selector or fav button (if visible)
                    // Third DOWN: show info panel
                    val strip = rvChannelStrip
                    val infoPanel = findViewById<View?>(R.id.overlay_info_panel)
                    val qualitySelector = findViewById<View?>(R.id.tv_quality_selector)

                    if (strip.visibility == View.VISIBLE && !strip.hasFocus()) {
                        // Focus the recent channels strip
                        strip.requestFocus()
                        strip.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    } else if (qualitySelector != null && qualitySelector.visibility == View.VISIBLE && !qualitySelector.hasFocus()) {
                        // Focus quality selector button
                        qualitySelector.requestFocus()
                    } else if (infoPanel != null && infoPanel.visibility != View.VISIBLE) {
                        infoPanel.visibility = View.VISIBLE
                        // Populate EPG timeline
                        val infoDesc = findViewById<TextView>(R.id.tv_info_program_desc)
                        val epgList = currentEpg
                        if (epgList != null && epgList.isNotEmpty()) {
                            val now = System.currentTimeMillis() / 1000
                            val upcoming = epgList.filter { it.endTime > now }.take(6)
                            val epgText = upcoming.joinToString("\n") { prog ->
                                val isCur = prog.startTime <= now && prog.endTime >= now
                                val prefix = if (isCur) "● " else "  "
                                "${prefix}${formatTime(prog.startTime)} - ${formatTime(prog.endTime)}  ${prog.title}"
                            }
                            infoDesc?.text = epgText
                            infoDesc?.visibility = View.VISIBLE
                        }
                    } else {
                        return super.onKeyDown(keyCode, event)
                    }
                    // Reset auto-hide timer
                    hideOverlayJob?.cancel()
                    hideOverlayJob = lifecycleScope.launch {
                        delay(OVERLAY_TIMEOUT)
                        hideFullOverlay()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // Hide info panel if visible
                    val infoPanel = findViewById<View?>(R.id.overlay_info_panel)
                    if (infoPanel != null && infoPanel.visibility == View.VISIBLE) {
                        infoPanel.visibility = View.GONE
                    }
                    hideOverlayJob?.cancel()
                    hideOverlayJob = lifecycleScope.launch {
                        delay(OVERLAY_TIMEOUT)
                        hideFullOverlay()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    hideFullOverlay()
                    return true
                }
            }
            hideOverlayJob?.cancel()
            hideOverlayJob = lifecycleScope.launch {
                delay(OVERLAY_TIMEOUT)
                hideFullOverlay()
            }
            return super.onKeyDown(keyCode, event)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isVodMode) {
                    if (!isVodOverlayVisible) showVodOverlay()
                    return true
                }
                switchToChannel(
                    if (channelList.isNotEmpty()) (currentPosition - 1 + channelList.size) % channelList.size
                    else currentPosition
                )
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isVodMode) {
                    if (!isVodOverlayVisible) showVodOverlay()
                    return true
                }
                switchToChannel(
                    if (channelList.isNotEmpty()) (currentPosition + 1) % channelList.size
                    else currentPosition
                )
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (isVodMode) {
                    if (isVodOverlayVisible) hideVodOverlay() else showVodOverlay()
                } else {
                    showFullOverlay()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isVodMode) {
                    player?.let { it.seekTo(maxOf(0L, it.currentPosition - 10000L)) }
                    if (!isVodOverlayVisible) showVodOverlay() else scheduleVodHide()
                    return true
                }
                if (isCatchupMode) {
                    player?.let { it.seekTo(maxOf(0L, it.currentPosition - 30000L)) }
                    return true
                }
                // LIVE mode: LEFT opens channel list
                showChannelList()
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isVodMode) {
                    player?.let { it.seekTo(minOf(it.duration, it.currentPosition + 10000L)) }
                    if (!isVodOverlayVisible) showVodOverlay() else scheduleVodHide()
                    return true
                }
                if (isCatchupMode) {
                    player?.let { it.seekTo(minOf(it.duration, it.currentPosition + 30000L)) }
                    return true
                }
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_P -> {
                player?.let { p ->
                    if (p.isPlaying) p.pause() else p.play()
                    if (isVodMode) {
                        if (!isVodOverlayVisible) showVodOverlay()
                        updateVodProgress()
                    }
                }
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                if (isVodOverlayVisible) {
                    hideVodOverlay()
                    return true
                }
                if (isCatchupMode) {
                    returnToLive()
                    return true
                }
                finish()
                return true
            }

            else -> {
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    onNumberPressed(keyCode - KeyEvent.KEYCODE_0)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showCatchupOptions() {
        val epg = currentEpg ?: return
        if (epg.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        val lastPast = epg.lastOrNull { it.endTime < now && it.title != null }
        if (lastPast != null) playCatchup(lastPast)
        else Toast.makeText(this, "No catch-up available", Toast.LENGTH_SHORT).show()
    }

    // ==========================================
    // TRACK INFO MODEL
    // ==========================================

    private data class TrackInfo(
        val name: String,
        val info: String,
        val trackGroup: TrackGroup?,
        val trackIndex: Int,
        var selected: Boolean
    )

    // ==========================================
    // TRACK ADAPTER
    // ==========================================

    private inner class TrackAdapter(
        private val items: List<TrackInfo>,
        private val trackType: Int
    ) : RecyclerView.Adapter<TrackAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ti = items[position]
            h.tvCheck.text = if (ti.selected) "\u2713" else ""
            h.tvName.text = ti.name
            h.tvInfo.text = ti.info

            h.itemView.setBackgroundResource(if (ti.selected) R.drawable.bg_track_selected else 0)
            if (!ti.selected) h.itemView.setBackgroundColor(Color.TRANSPARENT)

            h.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.06f else 1f)
                    .scaleY(if (hasFocus) 1.06f else 1f)
                    .translationZ(if (hasFocus) 8f else 0f)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            h.itemView.setOnClickListener { selectTrack(ti, trackType) }
        }

        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvCheck: TextView = v.findViewById(R.id.tv_track_check)
            val tvName: TextView = v.findViewById(R.id.tv_track_name)
            val tvInfo: TextView = v.findViewById(R.id.tv_track_info)
        }
    }

    // ==========================================
    // CHANNEL STRIP ADAPTER (horizontal)
    // ==========================================

    private inner class ChannelStripAdapter : RecyclerView.Adapter<ChannelStripAdapter.VH>() {
        private var items: List<Channel> = emptyList()
        private var playingPosition = -1

        fun setChannels(channels: List<Channel>, playing: Int) {
            items = channels
            playingPosition = playing
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel_strip, parent, false)
            // Fill width evenly: each item gets equal share
            val itemCount = items.size.coerceAtLeast(1)
            val totalPadding = (56 * parent.context.resources.displayMetrics.density).toInt() // paddingStart + paddingEnd
            val itemWidth = (parent.width - totalPadding) / itemCount
            v.layoutParams = RecyclerView.LayoutParams(itemWidth.coerceAtLeast(60), RecyclerView.LayoutParams.MATCH_PARENT)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = items[position]
            val label = buildString {
                if (ch.channelNumber > 0) append("${ch.channelNumber} ")
                append(ch.cleanName ?: ch.name)
            }
            h.tvName.text = label
            h.tvEpg.text = ""

            if (!ch.logoUrl.isNullOrEmpty()) {
                Glide.with(h.ivLogo.context).load(ch.logoUrl).centerInside().into(h.ivLogo)
            } else {
                h.ivLogo.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            val isPlaying = position == playingPosition
            h.itemView.setBackgroundResource(
                if (isPlaying) R.drawable.bg_strip_item_playing else android.R.color.transparent
            )

            h.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.08f else 1f)
                    .scaleY(if (hasFocus) 1.08f else 1f)
                    .translationZ(if (hasFocus) 10f else 0f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                if (hasFocus) {
                    v.foreground = android.graphics.drawable.GradientDrawable().apply {
                        setStroke(3, 0xAAFFFFFF.toInt())
                        cornerRadius = 10f * v.context.resources.displayMetrics.density
                        setColor(0x15FFFFFF)
                    }
                    hideOverlayJob?.cancel()
                    hideOverlayJob = lifecycleScope.launch {
                        delay(OVERLAY_TIMEOUT)
                        hideFullOverlay()
                    }
                } else {
                    v.foreground = null
                }
            }

            h.itemView.setOnClickListener {
                hideFullOverlay()
                switchToChannel(position)
            }
        }

        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivLogo: ImageView = v.findViewById(R.id.iv_strip_logo)
            val tvName: TextView = v.findViewById(R.id.tv_strip_name)
            val tvEpg: TextView = v.findViewById(R.id.tv_strip_epg)
        }
    }

    // ==========================================
    // OVERLAY CHANNEL ADAPTER (vertical list)
    // ==========================================

    private inner class OverlayChannelAdapter : RecyclerView.Adapter<OverlayChannelAdapter.VH>() {
        private var items: List<Channel> = emptyList()
        private var playingPosition = -1

        fun setChannels(channels: List<Channel>, playing: Int) {
            items = channels
            playingPosition = playing
            notifyDataSetChanged()
        }

        fun setCurrentPlaying(position: Int) {
            val old = playingPosition
            playingPosition = position
            if (old >= 0 && old < items.size) notifyItemChanged(old)
            if (position >= 0 && position < items.size) notifyItemChanged(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_overlay_channel, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = items[position]
            h.tvName.text = ch.cleanName ?: ch.name
            h.tvNum.text = if (ch.channelNumber > 0) ch.channelNumber.toString() else ""

            if (!ch.logoUrl.isNullOrEmpty()) {
                Glide.with(h.ivLogo.context)
                    .load(ch.logoUrl)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_gallery)
                    .centerInside().into(h.ivLogo)
            } else {
                h.ivLogo.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            val isPlaying = position == playingPosition
            h.itemView.setBackgroundColor(if (isPlaying) 0x15FFFFFF else Color.TRANSPARENT)
            h.tvName.setTextColor(if (isPlaying) Color.WHITE else 0xFFB0B0B5.toInt())
            h.tvNum.setTextColor(if (isPlaying) Color.WHITE else 0xFF6E6E73.toInt())

            // Load EPG (backend first, then Xtream fallback)
            h.tvEpg.text = ""
            val channelName = ch.cleanName ?: ch.name ?: ""
            if (channelName.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val now = System.currentTimeMillis() / 1000
                    var title: String? = null

                    // Try backend
                    try {
                        val programs = loadEpgFromBackend(channelName)
                        title = programs.firstOrNull { it.startTime <= now && it.endTime >= now }?.title
                    } catch (_: Exception) {}

                    // Try Xtream
                    if (title == null && isXtream && xtreamApi != null && ch.streamId > 0) {
                        try {
                            val programs = xtreamApi!!.getEpg(ch.streamId)
                            title = programs.firstOrNull { it.startTime <= now && it.endTime >= now }?.title
                        } catch (_: Exception) {}
                    }

                    withContext(Dispatchers.Main) {
                        h.tvEpg.text = title ?: ""
                    }
                }
            }

            h.itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.setBackgroundColor(0x30FFFFFF)
                    h.tvName.setTextColor(Color.WHITE)
                } else {
                    v.setBackgroundColor(if (isPlaying) 0x15FFFFFF else Color.TRANSPARENT)
                    h.tvName.setTextColor(if (isPlaying) Color.WHITE else 0xFFB0B0B5.toInt())
                }
            }
            h.itemView.setOnClickListener { onOverlayChannelSelected(position) }
        }

        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvNum: TextView = v.findViewById(R.id.tv_num)
            val tvName: TextView = v.findViewById(R.id.tv_name)
            val tvEpg: TextView = v.findViewById(R.id.tv_epg)
            val ivLogo: ImageView = v.findViewById(R.id.iv_logo)
        }
    }

    // ==========================================
    // LIFECYCLE
    // ==========================================

    override fun onStart() {
        super.onStart()
        if (player == null) {
            initPlayer()
            if (currentStreamUrl != null) {
                playStream(currentStreamUrl, currentChannelName, currentChannelLogo, currentChannelNumber)
            }
            if (isVodMode) {
                seekBarUpdateJob?.cancel()
                seekBarUpdateJob = lifecycleScope.launch {
                    while (isActive) {
                        updateVodProgress()
                        checkSkipIntro()
                        checkUpNext()
                        delay(1000)
                    }
                }
            }
        }
        player?.play()
        startEpgRefresh()
    }

    override fun onStop() {
        super.onStop()
        hideOverlayJob?.cancel()
        hideMiniInfoJob?.cancel()
        hideVodOverlayJob?.cancel()
        seekBarUpdateJob?.cancel()
        clockUpdateJob?.cancel()
        numberInputJob?.cancel()
        stopEpgRefresh()
        player?.release()
        player = null
    }

    override fun onResume() {
        super.onResume()
        if (player == null) {
            initPlayer()
            if (currentStreamUrl != null) {
                playStream(currentStreamUrl, currentChannelName, currentChannelLogo, currentChannelNumber)
            }
        }
        player?.play()
        startEpgRefresh()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
        stopEpgRefresh()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isVodMode) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPiP: Boolean, config: Configuration) {
        super.onPictureInPictureModeChanged(isInPiP, config)
        if (isInPiP) {
            hideFullOverlay()
            hideChannelList()
            overlayMiniInfo.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        // Cleanup is handled in onStop(), but guard against edge cases
        if (player != null) {
            player?.release()
            player = null
        }
        super.onDestroy()
    }
}
