package com.salliptv.player.adapter

import android.graphics.Color
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.salliptv.player.R
import com.salliptv.player.model.Channel

class ChannelAdapter(
    private var onItemClick: (Channel, Int) -> Unit = { _, _ -> },
    private var onItemLongClick: (Channel, Int) -> Unit = { _, _ -> },
    private var onItemFocus: (Channel, Int) -> Unit = { _, _ -> }
) : ListAdapter<Channel, ChannelAdapter.ViewHolder>(ChannelDiffCallback()) {

    enum class DisplayMode { GRID, LIST, POSTER }

    var displayMode: DisplayMode = DisplayMode.GRID
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    // Backward-compatible setter methods
    fun setListMode(enabled: Boolean) { if (enabled) displayMode = DisplayMode.LIST }
    fun setPosterMode(enabled: Boolean) { if (enabled) displayMode = DisplayMode.POSTER }
    fun isPosterMode(): Boolean = displayMode == DisplayMode.POSTER

    fun setOnChannelClickListener(listener: (Channel, Int) -> Unit) { onItemClick = listener }
    fun setOnChannelLongClickListener(listener: (Channel, Int) -> Unit) { onItemLongClick = listener }

    private val epgMap = mutableMapOf<String, String>()

    fun updateEpg(channelName: String, programTitle: String) {
        epgMap[channelName.lowercase()] = programTitle
    }

    fun refreshEpgViews() {
        notifyDataSetChanged()
    }
    fun setOnChannelFocusListener(listener: (Channel, Int) -> Unit) { onItemFocus = listener }

    private var selectedPosition: Int = -1
    private var allChannels: List<Channel> = emptyList()

    fun setSelectedPosition(position: Int) {
        val old = selectedPosition
        selectedPosition = position
        if (old >= 0) notifyItemChanged(old)
        if (position >= 0) notifyItemChanged(position)
    }

    // Legacy setter for callers that still pass a full list
    fun setChannels(channels: List<Channel>?) {
        allChannels = channels ?: emptyList()
        submitList(allChannels)
    }

    fun getChannels(): List<Channel> = currentList

    /**
     * Filter the displayed channels by [query].
     * Matches channel name. Empty query restores the full list.
     */
    fun filter(query: String) {
        if (query.isEmpty()) {
            submitList(allChannels)
        } else {
            val q = query.lowercase()
            submitList(allChannels.filter { ch ->
                ch.name?.lowercase()?.contains(q) == true ||
                (ch.groupTitle?.lowercase()?.contains(q) == true)
            })
        }
    }

    override fun getItemViewType(position: Int): Int = when (displayMode) {
        DisplayMode.POSTER -> TYPE_POSTER
        DisplayMode.LIST   -> TYPE_LIST
        DisplayMode.GRID   -> TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = when (viewType) {
            TYPE_POSTER -> R.layout.item_channel_poster
            TYPE_LIST   -> R.layout.item_channel_list
            else        -> R.layout.item_channel
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = getItem(position)
        holder.tvName.text = channel.cleanName ?: channel.name
        
        // Quality badge
        holder.tvQualityBadge?.apply {
            if (!channel.qualityBadge.isNullOrEmpty()) {
                text = channel.qualityBadge
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        // Logo loading
        if (!channel.logoUrl.isNullOrEmpty()) {
            if (displayMode == DisplayMode.POSTER) {
                Glide.with(holder.ivLogo.context)
                    .load(channel.logoUrl)
                    .placeholder(R.drawable.bg_poster_placeholder)
                    .error(R.drawable.bg_poster_placeholder)
                    .centerCrop()
                    .into(holder.ivLogo)
            } else {
                Glide.with(holder.ivLogo.context)
                    .load(channel.logoUrl)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_gallery)
                    .fitCenter()
                    .into(holder.ivLogo)
            }
        } else {
            holder.ivLogo.setImageResource(
                if (displayMode == DisplayMode.POSTER) R.drawable.bg_poster_placeholder
                else android.R.drawable.ic_menu_gallery
            )
        }

        // Poster-only extras
        if (displayMode == DisplayMode.POSTER) {
            holder.tvFavBadge?.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
            holder.tvEpg?.apply {
                if (!channel.groupTitle.isNullOrEmpty()) {
                    text = channel.groupTitle
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }
        }

        when (displayMode) {
            DisplayMode.POSTER -> bindPosterMode(holder, channel, position)
            DisplayMode.LIST   -> bindListMode(holder, channel, position)
            DisplayMode.GRID   -> bindGridMode(holder, channel, position)
        }

        holder.itemView.setOnClickListener {
            android.util.Log.d("ChannelAdapter", "CLICK on ${channel.name} type=${channel.type}")
            onItemClick(channel, position)
        }

        holder.itemView.setOnLongClickListener {
            onItemLongClick(channel, position)
            true
        }
    }

    private fun bindListMode(holder: ViewHolder, channel: Channel, position: Int) {
        holder.tvNumber?.apply {
            when {
                channel.type == "LIVE" -> {
                    text = if (channel.channelNumber > 0) channel.channelNumber.toString()
                           else (position + 1).toString()
                    visibility = View.VISIBLE
                }
                else -> visibility = View.GONE
            }
        }

        val isSelected = position == selectedPosition

        holder.tvPlaying?.apply {
            text = if (isSelected) "\u25B6" else ""
            setTextColor(0xFF0A84FF.toInt())
        }

        holder.itemView.setBackgroundColor(if (isSelected) 0x15FFFFFF else Color.TRANSPARENT)
        holder.tvName.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFFB0B0B5.toInt())
        holder.tvNumber?.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFF6E6E73.toInt())
        holder.tvEpg?.apply {
            if (channel.type == "LIVE") {
                visibility = View.VISIBLE
                val name = (channel.cleanName ?: channel.name ?: "").lowercase()
                val epg = epgMap[name]
                text = epg ?: ""
                setTextColor(if (isSelected) 0xAAFFFFFF.toInt() else 0xFF8E8E93.toInt())
            } else {
                visibility = View.GONE
            }
        }

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundColor(0x30FFFFFF)
                holder.tvName.setTextColor(Color.WHITE)
                holder.tvNumber?.setTextColor(Color.WHITE)
            } else {
                v.setBackgroundColor(if (isSelected) 0x15FFFFFF else Color.TRANSPARENT)
                holder.tvName.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFFB0B0B5.toInt())
                holder.tvNumber?.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFF6E6E73.toInt())
            }
            if (hasFocus) onItemFocus(channel, position)
        }
    }

    private fun bindPosterMode(holder: ViewHolder, channel: Channel, position: Int) {
        val card = holder.itemView as CardView
        card.setCardBackgroundColor(0xFF1C1C1E.toInt())
        card.cardElevation = 2f
        card.foreground = null

        // Ensure D-pad ENTER triggers click
        holder.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                v.performClick()
                true
            } else false
        }

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .translationZ(if (hasFocus) 8f else 0f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
            card.cardElevation = if (hasFocus) 16f else 2f
            if (hasFocus) {
                card.foreground = android.graphics.drawable.GradientDrawable().apply {
                    setStroke(3, 0xAAFFFFFF.toInt())
                    cornerRadius = 12f * v.context.resources.displayMetrics.density
                    setColor(0x10FFFFFF)
                }
            } else {
                card.foreground = null
            }
            if (hasFocus) onItemFocus(channel, position)
        }
    }

    private fun bindGridMode(holder: ViewHolder, channel: Channel, position: Int) {
        val card = holder.itemView as CardView
        card.setCardBackgroundColor(0xFF1C1C1E.toInt())
        card.cardElevation = 0f
        card.foreground = null

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .translationZ(if (hasFocus) 8f else 0f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
            card.cardElevation = if (hasFocus) 12f else 0f
            if (hasFocus) {
                card.foreground = android.graphics.drawable.GradientDrawable().apply {
                    setStroke(3, 0xAAFFFFFF.toInt())
                    cornerRadius = 12f * v.context.resources.displayMetrics.density
                    setColor(0x10FFFFFF)
                }
            } else {
                card.foreground = null
            }
            if (hasFocus) onItemFocus(channel, position)
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivLogo: ImageView = view.findViewById(R.id.iv_logo)
        val tvName: TextView = view.findViewById(R.id.tv_name)
        val tvEpg: TextView? = view.findViewById(R.id.tv_epg)
        val tvNumber: TextView? = view.findViewById(R.id.tv_number)
        val tvPlaying: TextView? = view.findViewById(R.id.tv_playing)
        val tvFavBadge: TextView? = view.findViewById(R.id.tv_fav_badge)
        val tvRating: TextView? = view.findViewById(R.id.tv_rating)
        val tvQualityBadge: TextView? = view.findViewById(R.id.tv_quality_badge)
    }

    private class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val TYPE_GRID   = 0
        private const val TYPE_LIST   = 1
        private const val TYPE_POSTER = 2
    }
}
