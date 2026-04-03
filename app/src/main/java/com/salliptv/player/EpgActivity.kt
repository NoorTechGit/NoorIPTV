package com.salliptv.player

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.databinding.ActivityEpgBinding
import com.salliptv.player.model.Channel
import com.salliptv.player.model.EpgProgram
import com.salliptv.player.model.Playlist
import com.salliptv.player.parser.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class EpgActivity : AppCompatActivity() {

    private companion object {
        const val PIXELS_PER_MINUTE = 4   // 4dp per minute, 240dp per hour
        const val HOURS_TO_SHOW = 6
        const val ROW_HEIGHT_DP = 56
    }

    private lateinit var binding: ActivityEpgBinding
    private lateinit var db: AppDatabase
    private var playlistId: Int = -1
    private var startChannelId: Int = -1
    private var isPremium: Boolean = false
    private var channels: MutableList<Channel> = mutableListOf()
    private var startTimeMs: Long = 0L
    private var xtreamApi: XtreamApi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)
        playlistId = intent.getIntExtra("playlistId", -1)
        startChannelId = intent.getIntExtra("channelId", -1)
        isPremium = intent.getBooleanExtra("isPremium", false)

        // Calculate start time (current hour - 1)
        val cal = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, -1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        startTimeMs = cal.timeInMillis

        buildTimeHeader()

        // Sync horizontal scroll between header and programs
        binding.hsvPrograms.viewTreeObserver.addOnScrollChangedListener {
            binding.hsvTimeHeader.scrollTo(binding.hsvPrograms.scrollX, 0)
        }

        binding.rvEpgChannels.layoutManager = LinearLayoutManager(this)
        binding.rvEpgPrograms.layoutManager = LinearLayoutManager(this)

        // Sync vertical scroll (guard against infinite recursion)
        var isSyncing = false
        binding.rvEpgChannels.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!isSyncing) {
                    isSyncing = true
                    binding.rvEpgPrograms.scrollBy(0, dy)
                    isSyncing = false
                }
            }
        })
        binding.rvEpgPrograms.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!isSyncing) {
                    isSyncing = true
                    binding.rvEpgChannels.scrollBy(0, dy)
                    isSyncing = false
                }
            }
        })

        loadChannels()
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private fun buildTimeHeader() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val slotWidthDp = PIXELS_PER_MINUTE * 30 // 30-min slots

        for (i in 0 until HOURS_TO_SHOW * 2) {
            val slotTime = startTimeMs + (i * 30L * 60L * 1000L)
            TextView(this).apply {
                text = sdf.format(Date(slotTime))
                setTextColor(0xFF8E8E93.toInt())
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dpToPx(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dpToPx(slotWidthDp), ViewGroup.LayoutParams.MATCH_PARENT)
                binding.llTimeSlots.addView(this)
            }
        }

        // Scroll to "now"
        val nowOffsetMs = System.currentTimeMillis() - startTimeMs
        val nowScrollPx = (nowOffsetMs / 60000 * dpToPx(PIXELS_PER_MINUTE)).toInt()
        binding.hsvTimeHeader.post {
            binding.hsvTimeHeader.scrollTo(maxOf(0, nowScrollPx - dpToPx(100)), 0)
            binding.hsvPrograms.scrollTo(maxOf(0, nowScrollPx - dpToPx(100)), 0)
        }
    }

    private fun loadChannels() {
        lifecycleScope.launch {
            val (pl, loaded) = withContext(Dispatchers.IO) {
                val playlist: Playlist? = db.playlistDao().getById(playlistId)
                val ch = db.channelDao().getByType(playlistId, "LIVE")
                Pair(playlist, ch ?: emptyList<Channel>())
            }

            if (pl != null && pl.type == "XTREAM"
                && pl.url != null && pl.username != null && pl.password != null) {
                xtreamApi = XtreamApi(pl.url!!, pl.username!!, pl.password!!)
            }

            channels.clear()
            channels.addAll(loaded)

            binding.rvEpgChannels.adapter = ChannelSidebarAdapter()
            binding.rvEpgPrograms.adapter = ProgramRowAdapter()

            // Scroll to selected channel
            if (startChannelId > 0) {
                val idx = channels.indexOfFirst { it.id == startChannelId }
                if (idx >= 0) {
                    binding.rvEpgChannels.post {
                        binding.rvEpgChannels.scrollToPosition(idx)
                        binding.rvEpgChannels.post {
                            binding.rvEpgChannels.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
                        }
                    }
                }
            }
        }
    }

    private fun showProgramInfo(ch: Channel, title: String?, time: String?) {
        if (!ch.logoUrl.isNullOrEmpty()) {
            Glide.with(this).load(ch.logoUrl).into(binding.ivEpgChannelLogo)
        }
        binding.tvEpgProgramTitle.text = title ?: ch.name
        binding.tvEpgProgramTime.text = time ?: ""
        binding.tvEpgProgramDesc.text = ""
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ==========================================
    // CHANNEL SIDEBAR ADAPTER
    // ==========================================

    private inner class ChannelSidebarAdapter : RecyclerView.Adapter<ChannelSidebarAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = channels[position]
            h.tvName.text = ch.name
            h.tvNumber.text = if (ch.channelNumber > 0) ch.channelNumber.toString() else ""

            if (!ch.logoUrl.isNullOrEmpty()) {
                Glide.with(h.ivLogo.context).load(ch.logoUrl).centerInside().into(h.ivLogo)
            } else {
                h.ivLogo.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            h.itemView.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.05f else 1f)
                    .scaleY(if (hasFocus) 1.05f else 1f)
                    .translationZ(if (hasFocus) 6f else 0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
                if (hasFocus) showProgramInfo(ch, null, null)
            }

            h.itemView.setOnClickListener {
                startActivity(Intent(this@EpgActivity, PlayerActivity::class.java).apply {
                    putExtra("channelName", ch.name)
                    putExtra("channelLogo", ch.logoUrl)
                    putExtra("channelNumber", ch.channelNumber)
                    putExtra("streamUrl", ch.streamUrl)
                    putExtra("streamId", ch.streamId)
                    putExtra("currentPosition", position)
                    putExtra("playlistId", playlistId)
                    putExtra("isPremium", isPremium)
                    putExtra("groupTitle", ch.groupTitle)
                })
            }
        }

        override fun getItemCount(): Int = channels.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivLogo: ImageView = v.findViewById(R.id.iv_logo)
            val tvNumber: TextView = v.findViewById(R.id.tv_number)
            val tvName: TextView = v.findViewById(R.id.tv_name)
        }
    }

    // ==========================================
    // PROGRAM ROW ADAPTER
    // ==========================================

    private inner class ProgramRowAdapter : RecyclerView.Adapter<ProgramRowAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = channels[position]
            h.llPrograms.removeAllViews()

            val api = xtreamApi
            if (api != null && ch.streamId > 0) {
                lifecycleScope.launch {
                    try {
                        val programs = withContext(Dispatchers.IO) { api.getEpg(ch.streamId) }
                        populateRow(h.llPrograms, programs, ch, position)
                    } catch (_: Exception) {
                        populateEmptyRow(h.llPrograms, ch, position)
                    }
                }
            } else {
                populateEmptyRow(h.llPrograms, ch, position)
            }
        }

        private fun populateEmptyRow(container: LinearLayout, ch: Channel, position: Int) {
            container.removeAllViews()
            val totalWidthDp = PIXELS_PER_MINUTE * 60 * HOURS_TO_SHOW
            val bgColor = if (position % 2 == 0) 0xFF1C1C2E.toInt() else 0xFF22223A.toInt()
            container.addView(createProgramBlock(getString(R.string.no_program_info), totalWidthDp, bgColor, ch, ""))
        }

        private fun populateRow(container: LinearLayout, programs: List<EpgProgram>?, ch: Channel, position: Int) {
            container.removeAllViews()
            if (programs.isNullOrEmpty()) {
                populateEmptyRow(container, ch, position)
                return
            }

            val gridEndMs = startTimeMs + (HOURS_TO_SHOW * 60L * 60L * 1000L)
            val bgColor = if (position % 2 == 0) 0xFF1C1C2E.toInt() else 0xFF22223A.toInt()
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

            for (prog in programs) {
                val startMs = prog.startTime * 1000L
                val endMs = prog.endTime * 1000L

                if (endMs < startTimeMs || startMs > gridEndMs) continue

                val drawStart = maxOf(startMs, startTimeMs)
                val drawEnd = minOf(endMs, gridEndMs)
                val durationMins = ((drawEnd - drawStart) / 60000).toInt()
                if (durationMins <= 0) continue

                val timeRange = "${sdf.format(Date(startMs))} - ${sdf.format(Date(endMs))}"
                container.addView(createProgramBlock(prog.title ?: "", durationMins * PIXELS_PER_MINUTE, bgColor, ch, timeRange))
            }
        }

        private fun createProgramBlock(title: String, widthDp: Int, bgColor: Int, ch: Channel, time: String): TextView {
            return TextView(this@EpgActivity).apply {
                text = title
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 11f
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                    isFocusableInTouchMode = true
                isClickable = true

                background = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = dpToPx(4).toFloat()
                    setStroke(dpToPx(1), 0xFF2A2A3A.toInt())
                }

                layoutParams = LinearLayout.LayoutParams(dpToPx(widthDp), dpToPx(ROW_HEIGHT_DP - 4)).apply {
                    setMargins(0, dpToPx(2), dpToPx(1), dpToPx(2))
                }

                setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        v.background = GradientDrawable().apply {
                            setColor(0xFF0A84FF.toInt())
                            cornerRadius = dpToPx(4).toFloat()
                        }
                        (v as TextView).setTextColor(0xFFFFFFFF.toInt())
                        showProgramInfo(ch, title, time)
                    } else {
                        v.background = GradientDrawable().apply {
                            setColor(bgColor)
                            cornerRadius = dpToPx(4).toFloat()
                            setStroke(dpToPx(1), 0xFF2A2A3A.toInt())
                        }
                        (v as TextView).setTextColor(0xFFAAAAAA.toInt())
                    }
                }

                setOnClickListener {
                    startActivity(Intent(this@EpgActivity, PlayerActivity::class.java).apply {
                        putExtra("channelId", ch.id)
                        putExtra("channelName", ch.name)
                        putExtra("channelLogo", ch.logoUrl)
                        putExtra("channelNumber", ch.channelNumber)
                        putExtra("streamUrl", ch.streamUrl)
                        putExtra("streamId", ch.streamId)
                        putExtra("playlistId", playlistId)
                        putExtra("isPremium", isPremium)
                        putExtra("groupTitle", ch.groupTitle)
                    })
                }
            }
        }

        override fun getItemCount(): Int = channels.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val llPrograms: LinearLayout = v.findViewById(R.id.ll_programs)
        }
    }
}
