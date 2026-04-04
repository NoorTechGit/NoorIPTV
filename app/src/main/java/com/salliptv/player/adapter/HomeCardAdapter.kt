package com.salliptv.player.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.salliptv.player.R
import com.salliptv.player.model.Channel

class HomeCardAdapter(
    private var sectionType: SectionType,
    private val onItemClick: (Channel) -> Unit,
    private val onItemLongClick: (Channel) -> Unit,
    private val onItemFocus: (Channel) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var channels: List<Channel> = emptyList()
    private var showSeeAll = false

    fun setData(list: List<Channel>, seeAll: Boolean) {
        channels = list
        showSeeAll = seeAll
        notifyDataSetChanged()
    }

    fun updateSectionType(newType: SectionType) {
        if (newType != sectionType) {
            sectionType = newType
            notifyDataSetChanged()
        }
    }

    private val isPoster: Boolean
        get() = sectionType == SectionType.VOD || sectionType == SectionType.SERIES

    override fun getItemCount(): Int = channels.size + if (showSeeAll) 1 else 0

    override fun getItemViewType(position: Int): Int {
        if (showSeeAll && position == channels.size) return TYPE_SEE_ALL
        return if (isPoster) TYPE_POSTER else TYPE_CARD
    }

    // =========================================================================
    // Click listener set in onCreateViewHolder — resolves position at click time
    // per Google recommendation: https://developer.android.com/reference/androidx/recyclerview/widget/RecyclerView.Adapter
    // =========================================================================

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_SEE_ALL) {
            val layoutRes = if (isPoster) R.layout.item_see_all else R.layout.item_see_all_live
            val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
            val holder = SeeAllViewHolder(view)
            view.setOnClickListener {
                onItemClick(Channel(id = HomeSectionAdapter.SEE_ALL_ID))
            }
            view.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.06f else 1f)
                    .scaleY(if (hasFocus) 1.06f else 1f)
                    .translationZ(if (hasFocus) 8f else 0f)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            return holder
        }

        val layoutRes = if (viewType == TYPE_POSTER) R.layout.item_home_card_poster else R.layout.item_home_card
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        val holder = CardViewHolder(view, viewType)

        // Click: resolve position at click time, not at bind time
        view.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < channels.size) {
                onItemClick(channels[pos])
            }
        }
        view.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < channels.size) {
                onItemLongClick(channels[pos])
            }
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is SeeAllViewHolder) return
        if (holder is CardViewHolder && position < channels.size) {
            val channel = channels[position]
            holder.bindVisuals(channel, sectionType)
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                holder.applyFocusEffect(hasFocus)
                if (hasFocus) {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < channels.size) {
                        onItemFocus(channels[pos])
                    }
                }
            }
        }
    }

    // =========================================================================
    // SeeAllViewHolder
    // =========================================================================

    inner class SeeAllViewHolder(view: View) : RecyclerView.ViewHolder(view)

    // =========================================================================
    // CardViewHolder
    // =========================================================================

    class CardViewHolder(view: View, private val viewType: Int) : RecyclerView.ViewHolder(view) {

        private val ivThumb: ImageView? = view.findViewById(R.id.iv_card_thumb)
        private val tvTitle: TextView? = view.findViewById(R.id.tv_card_title)
        private val tvSubtitle: TextView? = view.findViewById(R.id.tv_card_subtitle)
        private val ivFavBadge: ImageView? = view.findViewById(R.id.iv_card_favorite)
        private val tvLiveBadge: TextView? = view.findViewById(R.id.tv_card_live_badge)

        private val ivPoster: ImageView? = view.findViewById(R.id.iv_poster)
        private val tvPosterName: TextView? = view.findViewById(R.id.tv_poster_title)
        private val tvPosterSubtitle: TextView? = view.findViewById(R.id.tv_poster_subtitle)
        private val ivPosterFavBadge: ImageView? = view.findViewById(R.id.iv_poster_favorite)

        fun bindVisuals(channel: Channel, sectionType: SectionType) {
            when (viewType) {
                TYPE_CARD -> bindCard(channel, sectionType)
                TYPE_POSTER -> bindPoster(channel)
            }
        }

        fun applyFocusEffect(hasFocus: Boolean) {
            val scale = if (hasFocus) 1.06f else 1.0f
            val elev = if (hasFocus) dpToPx(12) else 0f
            itemView.animate().scaleX(scale).scaleY(scale).translationZ(elev)
                .setDuration(if (hasFocus) 180L else 150L)
                .setInterpolator(DecelerateInterpolator(1.5f)).start()

            // Visible white border on focus — clear on dark backgrounds
            if (hasFocus) {
                val glow = android.graphics.drawable.GradientDrawable().apply {
                    setStroke(3, 0xAAFFFFFF.toInt())
                    cornerRadius = dpToPx(12)
                    setColor(0x10FFFFFF)
                }
                itemView.foreground = glow
            } else {
                itemView.foreground = null
            }
        }

        private fun bindCard(channel: Channel, sectionType: SectionType) {
            tvTitle?.text = channel.cleanName ?: channel.name
            tvSubtitle?.apply {
                val sub = channel.groupTitle
                if (!sub.isNullOrEmpty()) { text = sub; visibility = View.VISIBLE } else visibility = View.GONE
            }
            ivFavBadge?.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
            tvLiveBadge?.visibility = if (sectionType == SectionType.LIVE) View.VISIBLE else View.GONE
            val thumbImage = channel.posterUrl ?: channel.backdropUrl ?: channel.logoUrl
            if (!thumbImage.isNullOrEmpty() && ivThumb != null) {
                val hasPoster = !channel.posterUrl.isNullOrEmpty() || !channel.backdropUrl.isNullOrEmpty()
                val request = Glide.with(itemView.context).load(thumbImage)
                    .placeholder(R.drawable.bg_card_normal).error(R.drawable.bg_card_normal)
                // Always fill the card — no empty space, no padding
                ivThumb.setPadding(0, 0, 0, 0)
                ivThumb.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                request.into(ivThumb)
            } else ivThumb?.setImageResource(R.drawable.bg_card_normal)
        }

        private fun bindPoster(channel: Channel) {
            tvPosterName?.text = channel.cleanName ?: channel.name
            tvPosterSubtitle?.text = channel.groupTitle
            ivPosterFavBadge?.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
            val posterImage = channel.posterUrl ?: channel.backdropUrl ?: channel.logoUrl
            if (!posterImage.isNullOrEmpty() && ivPoster != null) {
                Glide.with(itemView.context).load(posterImage)
                    .placeholder(R.drawable.bg_poster_placeholder).error(R.drawable.bg_poster_placeholder)
                    .centerCrop().into(ivPoster)
            } else ivPoster?.setImageResource(R.drawable.bg_poster_placeholder)
        }

        private fun dpToPx(dp: Int): Float = dp * itemView.context.resources.displayMetrics.density
    }

    companion object {
        private const val TYPE_CARD = 0
        private const val TYPE_POSTER = 1
        private const val TYPE_SEE_ALL = 2
    }
}
