package com.salliptv.player

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.databinding.ActivitySettingsBinding
import com.salliptv.player.model.Channel
import com.salliptv.player.model.Playlist
import com.salliptv.player.parser.CountryDetector
import com.salliptv.player.parser.M3uParser
import com.salliptv.player.parser.XtreamApi
import com.salliptv.player.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "Settings"
        const val API_BASE = "https://salliptv.com/api"
        const val POLL_INTERVAL_MS = 5_000L
        const val PREFS_NAME = "salliptv_settings"
        val BUFFER_OPTIONS = arrayOf("Auto", "1s", "2s", "5s", "10s", "30s")
        val DECODER_OPTIONS = arrayOf("Hardware", "Software")
        val ASPECT_OPTIONS = arrayOf("Fit", "Fill", "16:9", "4:3")
    }

    private lateinit var binding: ActivitySettingsBinding

    private var deviceId: String? = null
    private lateinit var db: AppDatabase
    private lateinit var premiumManager: PremiumManager
    private var isXtreamMode = false
    private lateinit var prefs: android.content.SharedPreferences

    private val httpClient = OkHttpClient()
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        premiumManager = PremiumManager(this)
        premiumManager.init { isPremium ->
            if (isPremium) {
                binding.tvPremiumStatus.setText(R.string.premium_active)
                binding.tvPremiumStatus.setTextColor(getColor(R.color.accent))
            } else {
                binding.tvPremiumStatus.setText(R.string.premium_subtitle)
                binding.tvPremiumStatus.setTextColor(getColor(R.color.text_secondary))
            }
        }

        deviceId = premiumManager.getDeviceId()
        binding.tvDeviceId.text = deviceId
        createLinkAndShowQr()

        // Toggle manual input section
        binding.btnShowManual.setOnClickListener {
            if (binding.layoutManualInput.visibility == View.VISIBLE) {
                binding.layoutManualInput.visibility = View.GONE
                binding.btnShowManual.setText(R.string.manual_add)
            } else {
                binding.layoutManualInput.visibility = View.VISIBLE
                binding.btnShowManual.setText(R.string.manual_hide)
            }
        }

        // Type toggle
        binding.btnTypeM3u.setOnClickListener { setMode(false) }
        binding.btnTypeXtream.setOnClickListener { setMode(true) }

        // Test / Save
        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnSave.setOnClickListener { savePlaylist() }

        loadSavedPlaylists()
        initPlayerSettings()
        startPolling()
    }

    private fun initPlayerSettings() {
        val tvBuffer = binding.root.findViewById<TextView>(R.id.tv_buffer_value) ?: return
        val tvDecoder = binding.root.findViewById<TextView>(R.id.tv_decoder_value) ?: return
        val tvAspect = binding.root.findViewById<TextView>(R.id.tv_aspect_value) ?: return
        val tvVersion = binding.root.findViewById<TextView?>(R.id.tv_version)
        val btnClearCache = binding.root.findViewById<View?>(R.id.btn_clear_cache)

        tvBuffer.text = prefs.getString("buffer", "Auto")
        tvDecoder.text = prefs.getString("decoder", "Hardware")
        tvAspect.text = prefs.getString("aspect_ratio", "Fit")

        tvVersion?.text = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "" }

        fun setupCycleRow(tv: TextView, key: String, options: Array<String>, default: String) {
            val row = tv.parent as? View ?: return
            row.setOnClickListener {
                val current = prefs.getString(key, default) ?: default
                val idx = (options.indexOf(current) + 1) % options.size
                prefs.edit().putString(key, options[idx]).apply()
                tv.text = options[idx]
            }
            row.setOnFocusChangeListener { v, f ->
                v.setBackgroundResource(if (f) R.drawable.bg_settings_focused else R.drawable.bg_settings_row)
                v.animate().scaleX(if (f) 1.01f else 1f).scaleY(if (f) 1.01f else 1f).setDuration(150).start()
            }
        }

        setupCycleRow(tvBuffer, "buffer", BUFFER_OPTIONS, "Auto")
        setupCycleRow(tvDecoder, "decoder", DECODER_OPTIONS, "Hardware")
        setupCycleRow(tvAspect, "aspect_ratio", ASPECT_OPTIONS, "Fit")

        btnClearCache?.apply {
            setOnClickListener {
                com.bumptech.glide.Glide.get(this@SettingsActivity).clearMemory()
                Thread { com.bumptech.glide.Glide.get(this@SettingsActivity).clearDiskCache() }.start()
                Toast.makeText(this@SettingsActivity, getString(R.string.cache_cleared), Toast.LENGTH_SHORT).show()
            }
            setOnFocusChangeListener { v, f ->
                v.setBackgroundResource(if (f) R.drawable.bg_settings_focused else R.drawable.bg_settings_row)
                v.animate().scaleX(if (f) 1.01f else 1f).scaleY(if (f) 1.01f else 1f).setDuration(150).start()
            }
        }
    }

    private fun loadSavedPlaylists() {
        lifecycleScope.launch {
            val playlists = withContext(Dispatchers.IO) { db.playlistDao().getAll() }
            val container = binding.root.findViewById<LinearLayout>(R.id.layout_playlists) ?: return@launch
            container.removeAllViews()

            if (playlists.isEmpty()) {
                TextView(this@SettingsActivity).apply {
                    setText(R.string.no_playlist)
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 14f
                    setPadding(0, 8, 0, 16)
                    container.addView(this)
                }
                return@launch
            }

            val now = System.currentTimeMillis()
            val ONE_WEEK = 7L * 24 * 60 * 60 * 1000

            for (pl in playlists) {
                val channelCount = withContext(Dispatchers.IO) {
                    try { db.channelDao().getByType(pl.id, "LIVE").size } catch (_: Exception) { 0 }
                }

                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(16, 12, 16, 12)
                    setBackgroundColor(getColor(R.color.card_dark))
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = 8 }
                }

                val info = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                TextView(this@SettingsActivity).apply {
                    text = pl.name
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 15f
                    info.addView(this)
                }

                TextView(this@SettingsActivity).apply {
                    text = "${pl.type} - $channelCount ch."
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                    info.addView(this)
                }

                val isInactive = pl.lastUpdated > 0 && (now - pl.lastUpdated) > ONE_WEEK
                if (isInactive) {
                    val daysAgo = (now - pl.lastUpdated) / (24 * 60 * 60 * 1000)
                    TextView(this@SettingsActivity).apply {
                        text = "Inactive ${daysAgo}d — Refresh or remove?"
                        setTextColor(0xFFFF9500.toInt())
                        textSize = 11f
                        setPadding(0, 4, 0, 0)
                        info.addView(this)
                    }
                }

                row.addView(info)

                // Refresh button
                TextView(this@SettingsActivity).apply {
                    text = "\u21BB"
                    setTextColor(0xFF0A84FF.toInt())
                    textSize = 22f
                    setPadding(24, 16, 24, 16)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    setBackgroundResource(R.drawable.bg_action_focused)
                    setOnClickListener {
                        Toast.makeText(this@SettingsActivity, "Refreshing ${pl.name}...", Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                db.channelDao().deleteByPlaylist(pl.id)
                                db.playlistDao().updateTimestamp(pl.id, System.currentTimeMillis())
                            }
                            if ("XTREAM" == pl.type) loadXtreamChannels(pl, pl.id)
                            else loadM3uChannels(pl, pl.id)
                            loadSavedPlaylists()
                        }
                    }
                    row.addView(this)
                }

                // Delete button
                TextView(this@SettingsActivity).apply {
                    text = "X"
                    setTextColor(0xFFFF6B6B.toInt())
                    textSize = 18f
                    setPadding(24, 16, 24, 16)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    setBackgroundResource(R.drawable.bg_action_focused)
                    setOnClickListener {
                        lifecycleScope.launch(Dispatchers.IO) {
                            db.channelDao().deleteByPlaylist(pl.id)
                            db.playlistDao().delete(pl)
                            withContext(Dispatchers.Main) { loadSavedPlaylists() }
                        }
                    }
                    row.addView(this)
                }

                container.addView(row)

                // Auto-load channels if playlist has 0 channels
                if (channelCount == 0 && pl.lastUpdated == 0L) {
                    Toast.makeText(this@SettingsActivity, "Loading ${pl.name}...", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        if ("XTREAM" == pl.type) loadXtreamChannels(pl, pl.id)
                        else loadM3uChannels(pl, pl.id)
                        loadSavedPlaylists()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    override fun onDestroy() {
        stopPolling()
        premiumManager.destroy()
        super.onDestroy()
    }

    // ==========================================
    // WEB SYNC
    // ==========================================

    private fun startPolling() {
        Log.d(TAG, "startPolling called, deviceId=$deviceId, pollJob active=${pollJob?.isActive}")
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch {
            Log.d(TAG, "Polling started")
            while (isActive) {
                syncFromWeb()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun createLinkAndShowQr() {
        val id = deviceId ?: return
        lifecycleScope.launch {
            try {
                val jsonType = "application/json".toMediaType()

                // Step 1: Register device
                val fingerprint = premiumManager.getFingerprint()
                val isPro = premiumManager.isPremium()
                val regJson = """{"device_id":"$id","fingerprint":"$fingerprint","is_premium":$isPro}"""
                val regRequest = Request.Builder()
                    .url("$API_BASE/device/register")
                    .post(regJson.toRequestBody(jsonType))
                    .header("User-Agent", "SallIPTV/1.0")
                    .build()

                val regOk = withContext(Dispatchers.IO) {
                    httpClient.newCall(regRequest).execute().use { it.isSuccessful }
                }
                if (!regOk) {
                    binding.tvSyncStatus.text = "Server unavailable"
                    return@launch
                }

                // Step 2: Create link code
                val linkRequest = Request.Builder()
                    .url("$API_BASE/device/$id/link")
                    .post("{}".toRequestBody(jsonType))
                    .header("User-Agent", "SallIPTV/1.0")
                    .build()

                val qrUrlWithLang = withContext(Dispatchers.IO) {
                    httpClient.newCall(linkRequest).execute().use { resp ->
                        if (!resp.isSuccessful || resp.body == null) return@withContext null
                        val root = JsonParser.parseString(resp.body!!.string()).asJsonObject
                        val qrUrl = root.get("url").asString
                        val lang = Locale.getDefault().language
                        val sep = if (qrUrl.contains("?")) "&" else "?"
                        "$qrUrl${sep}lang=$lang"
                    }
                } ?: run {
                    binding.tvSyncStatus.text = "Server unavailable"
                    return@launch
                }

                val qr: Bitmap = QrCodeGenerator.generateUrlBitmap(qrUrlWithLang, 400)
                binding.ivQrCode.setImageBitmap(qr)
                binding.tvSyncStatus.setText(R.string.waiting_for_sync)

            } catch (e: Exception) {
                Log.w(TAG, "Link creation failed: ${e.message}")
                binding.tvSyncStatus.text = "Connection error"
            }
        }
    }

    private fun syncFromWeb() {
        val id = deviceId ?: return
        lifecycleScope.launch {
            try {
                val request = Request.Builder()
                    .url("$API_BASE/device/$id/inbox")
                    .header("User-Agent", "SallIPTV/1.0")
                    .build()

                val (status, data) = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful || resp.body == null) return@withContext Pair("empty", null)
                        val root = JsonParser.parseString(resp.body!!.string()).asJsonObject
                        Pair(root.get("status").asString, root.getAsJsonObject("data"))
                    }
                }

                when (status) {
                    "empty" -> return@launch
                    "upgrade" -> {
                        binding.tvSyncStatus.setText(R.string.pro_activated_web)
                        binding.tvPremiumStatus.setText(R.string.premium_active)
                        binding.tvPremiumStatus.setTextColor(getColor(R.color.accent))
                        stopPolling()
                        createLinkAndShowQr()
                    }
                    "playlist" -> {
                        if (data != null) handleSessionPlaylist(data)
                        stopPolling()
                        createLinkAndShowQr()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Inbox poll failed: ${e.message}")
            }
        }
    }

    private fun handleSessionPlaylist(data: JsonObject) {
        lifecycleScope.launch {
            Log.d(TAG, "handleSessionPlaylist: received playlist data from web/mobile")
            val existing = withContext(Dispatchers.IO) { db.playlistDao().getAll() }

            if (!premiumManager.isPremium() && existing.isNotEmpty()) {
                Toast.makeText(this@SettingsActivity, "${getString(R.string.feature_locked)} — 1 playlist max", Toast.LENGTH_LONG).show()
                return@launch
            }

            val type = data.get("type").asString
            val pl = Playlist().apply {
                lastUpdated = System.currentTimeMillis()
                if ("xtream" == type) {
                    this.type = "XTREAM"
                    name = data.get("name")?.asString ?: "Xtream"
                    url = data.get("server").asString
                    username = data.get("username").asString
                    password = data.get("password").asString
                } else {
                    this.type = "M3U"
                    name = data.get("name")?.asString ?: "M3U Playlist"
                    url = data.get("url").asString
                }
            }

            // Check for duplicates
            for (ex in existing) {
                if (pl.url == ex.url) {
                    Toast.makeText(this@SettingsActivity, "Playlist already exists", Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }

            val playlistId = withContext(Dispatchers.IO) {
                db.playlistDao().insert(pl).toInt()
            }
            Log.d(TAG, "Playlist inserted from web with ID: $playlistId")
            
            // Rafraîchir la liste affichée dans SettingsActivity
            loadSavedPlaylists()
            
            binding.tvSyncStatus.text = getString(R.string.playlist_synced, pl.name)
            showLoading(getString(R.string.loading))

            if ("XTREAM" == pl.type) loadXtreamChannels(pl, playlistId)
            else loadM3uChannels(pl, playlistId)
        }
    }

    // ==========================================
    // MANUAL PLAYLIST INPUT
    // ==========================================

    private fun setMode(xtream: Boolean) {
        isXtreamMode = xtream
        binding.layoutM3u.visibility = if (xtream) View.GONE else View.VISIBLE
        binding.layoutXtream.visibility = if (xtream) View.VISIBLE else View.GONE

        binding.btnTypeM3u.setBackgroundColor(getColor(if (xtream) R.color.card_dark else R.color.accent))
        binding.btnTypeM3u.setTextColor(getColor(if (xtream) R.color.text_secondary else R.color.text_primary))
        binding.btnTypeXtream.setBackgroundColor(getColor(if (xtream) R.color.accent else R.color.card_dark))
        binding.btnTypeXtream.setTextColor(getColor(if (xtream) R.color.text_primary else R.color.text_secondary))
    }

    private fun testConnection() {
        showLoading(getString(R.string.loading))
        binding.btnTest.isEnabled = false

        if (isXtreamMode) {
            val server = binding.etServer.text.toString().trim()
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (server.isEmpty()) { setStatus(getString(R.string.error_no_server)); binding.btnTest.isEnabled = true; return }
            if (user.isEmpty() || pass.isEmpty()) { setStatus(getString(R.string.error_no_credentials)); binding.btnTest.isEnabled = true; return }

            lifecycleScope.launch {
                try {
                    val api = XtreamApi(server, user, pass)
                    val loginResult = withContext(Dispatchers.IO) { api.login() }
                    if (loginResult != null) {
                        val channels = withContext(Dispatchers.IO) { api.getAllLiveStreams(0) }
                        setStatus(getString(R.string.test_success, channels.size))
                    } else {
                        setStatus(getString(R.string.test_failed, "Login failed"))
                    }
                } catch (e: Exception) {
                    setStatus(getString(R.string.test_failed, e.message))
                } finally {
                    binding.btnTest.isEnabled = true
                }
            }
        } else {
            val url = binding.etM3uUrl.text.toString().trim()
            if (url.isEmpty()) { setStatus(getString(R.string.error_no_url)); binding.btnTest.isEnabled = true; return }

            lifecycleScope.launch {
                try {
                    var testCount = 0
                    val result = M3uParser.parse(
                        url, 
                        0,
                        onProgress = { count ->
                            testCount = count
                            runOnUiThread { binding.tvStatus.text = "${getString(R.string.loading)} $count" }
                        }
                    )
                    if (!result.epgUrl.isNullOrEmpty() && binding.etEpgUrl.text.isEmpty()) {
                        binding.etEpgUrl.setText(result.epgUrl)
                    }
                    setStatus(getString(R.string.test_success, testCount))
                } catch (e: Exception) {
                    setStatus(getString(R.string.test_failed, e.message))
                } finally {
                    binding.btnTest.isEnabled = true
                }
            }
        }
    }

    private fun savePlaylist() {
        val rawName = binding.etName.text.toString().trim()
        val name = rawName.ifEmpty { "My IPTV" }

        if (!premiumManager.isPremium()) {
            lifecycleScope.launch {
                val existing = withContext(Dispatchers.IO) { db.playlistDao().getAll() }
                if (existing.isNotEmpty()) {
                    Toast.makeText(this@SettingsActivity, "${getString(R.string.feature_locked)} — 1 playlist max", Toast.LENGTH_LONG).show()
                    return@launch
                }
                doSavePlaylist(name)
            }
            return
        }
        doSavePlaylist(name)
    }

    private fun doSavePlaylist(name: String) {
        val pl = Playlist().apply {
            this.name = name
            epgUrl = binding.etEpgUrl.text.toString().trim()
            lastUpdated = System.currentTimeMillis()
        }

        if (isXtreamMode) {
            val server = binding.etServer.text.toString().trim()
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()
            if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) { setStatus(getString(R.string.error_no_credentials)); return }
            pl.type = "XTREAM"; pl.url = server; pl.username = user; pl.password = pass
        } else {
            val url = binding.etM3uUrl.text.toString().trim()
            if (url.isEmpty()) { setStatus(getString(R.string.error_no_url)); return }
            pl.type = "M3U"; pl.url = url
        }

        showLoading(getString(R.string.loading))
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            val playlistId = withContext(Dispatchers.IO) { db.playlistDao().insert(pl).toInt() }
            if ("XTREAM" == pl.type) loadXtreamChannels(pl, playlistId)
            else loadM3uChannels(pl, playlistId)
        }
    }

    // ==========================================
    // CHANNEL LOADING
    // ==========================================

    private suspend fun loadXtreamChannels(pl: Playlist, playlistId: Int) {
        try {
            showLoading(getString(R.string.loading_connecting))
            val serverUrl = pl.url ?: run { setStatus("Missing server URL"); binding.btnSave.isEnabled = true; return }
            val user = pl.username ?: run { setStatus("Missing username"); binding.btnSave.isEnabled = true; return }
            val pass = pl.password ?: run { setStatus("Missing password"); binding.btnSave.isEnabled = true; return }
            val api = XtreamApi(serverUrl, user, pass)

            val loginResult = withContext(Dispatchers.IO) { api.login() }
            if (loginResult == null) {
                setStatus(getString(R.string.test_failed, "Login failed"))
                binding.btnSave.isEnabled = true
                return
            }

            val channels = mutableListOf<Channel>()

            showLoading(String.format(getString(R.string.loading_channels), 0))
            val live = withContext(Dispatchers.IO) { api.getAllLiveStreams(playlistId) }
            channels.addAll(live)
            showLoading(String.format(getString(R.string.loading_channels), live.size))

            try {
                showLoading(String.format(getString(R.string.loading_vod), 0))
                val vod = withContext(Dispatchers.IO) { api.getAllVodStreams(playlistId) }
                channels.addAll(vod)
                showLoading(String.format(getString(R.string.loading_vod), vod.size))
            } catch (e: Exception) {
                Log.e(TAG, "VOD load error: ${e.message}")
            }

            try {
                showLoading(getString(R.string.loading_series))
                val series = withContext(Dispatchers.IO) { api.getAllSeries(playlistId) }
                channels.addAll(series)
            } catch (e: Exception) {
                Log.e(TAG, "Series load error: ${e.message}")
            }

            val total = channels.size
            showLoading(getString(R.string.loading_detecting))
            withContext(Dispatchers.IO) { CountryDetector.detectPrefixes(channels) }

            showLoading(String.format(getString(R.string.loading_saving), total))
            withContext(Dispatchers.IO) { db.channelDao().insertAll(channels) }

            setStatus("${getString(R.string.saved)} ($total channels)")
            binding.btnSave.isEnabled = true
            openFilter(playlistId)

        } catch (e: Exception) {
            setStatus(getString(R.string.test_failed, e.message))
            binding.btnSave.isEnabled = true
        }
    }

    private suspend fun loadM3uChannels(pl: Playlist, playlistId: Int) {
        showLoading(getString(R.string.loading_connecting))
        try {
            val allChannels = mutableListOf<Channel>()
            val result = M3uParser.parse(
                pl.url ?: "", 
                playlistId,
                onProgress = { count ->
                    runOnUiThread { showLoading(String.format(getString(R.string.loading_channels), count)) }
                },
                onBatch = { batch ->
                    allChannels.addAll(batch)
                    // Insérer par lots pour libérer la mémoire
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.channelDao().insertAll(batch)
                    }
                }
            )
            val channels = allChannels.toList()
            showLoading(getString(R.string.loading_detecting))
            withContext(Dispatchers.IO) { CountryDetector.detectPrefixes(channels) }
            setStatus("${getString(R.string.saved)} (${channels.size} channels)")
            binding.btnSave.isEnabled = true
            openFilter(playlistId)
        } catch (e: Exception) {
            setStatus(getString(R.string.test_failed, e.message))
            binding.btnSave.isEnabled = true
        }
    }

    private fun openFilter(playlistId: Int) {
        if (!premiumManager.isPremium()) {
            Toast.makeText(this, getString(R.string.playlist_added_restart), Toast.LENGTH_SHORT).show()
            // Redémarrer MainActivity pour charger la nouvelle playlist
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("playlistAdded", true)
                putExtra("playlistId", playlistId)
            }
            startActivity(intent)
            finish()
            return
        }
        startActivity(Intent(this, FilterActivity::class.java).apply {
            putExtra("playlistId", playlistId)
        })
        finish()
    }

    private fun showLoading(text: String) {
        binding.tvStatus.text = text
        binding.progressStatus.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        binding.progressStatus.visibility = View.GONE
    }

    private fun setStatus(text: String) {
        hideLoading()
        binding.tvStatus.text = text
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            finishAffinity()
            System.exit(0)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
