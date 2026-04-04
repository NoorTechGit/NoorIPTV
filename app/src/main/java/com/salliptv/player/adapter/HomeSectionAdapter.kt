package com.salliptv.player.adapter

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.salliptv.player.R
import com.salliptv.player.model.Channel

/**
 * Apple TV style pill badge — rounded rect background with text
 */
class RoundedBadgeSpan(
    private val bgColor: Int,
    private val textColor: Int,
    private val cornerRadius: Float
) : ReplacementSpan() {

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val textWidth = paint.measureText(text, start, end)
        return (textWidth + cornerRadius * 2).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val textWidth = paint.measureText(text, start, end)
        val totalWidth = textWidth + cornerRadius * 2
        val height = (bottom - top).toFloat()
        val badgeHeight = height * 0.8f
        val badgeTop = top + (height - badgeHeight) / 2

        // Draw pill background
        val bgPaint = Paint(paint).apply { color = bgColor; isAntiAlias = true }
        val rect = RectF(x, badgeTop, x + totalWidth, badgeTop + badgeHeight)
        canvas.drawRoundRect(rect, badgeHeight / 2, badgeHeight / 2, bgPaint)

        // Draw text centered
        paint.color = textColor
        val textY = badgeTop + badgeHeight / 2 - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(text, start, end, x + cornerRadius, textY, paint)
    }
}

enum class SectionType {
    CONTINUE_WATCHING,
    FAVORITES,
    LIVE,
    VOD,
    SERIES
}

data class HomeSection(
    val title: String,
    val channels: List<Channel>,
    val sectionType: SectionType,
    val groupName: String = "",
    val totalCount: Int = 0
)

class HomeSectionAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelLongClick: (Channel) -> Unit,
    private val onChannelFocus: (Channel) -> Unit,
    private val onSeeAllClick: ((HomeSection) -> Unit)? = null
) : RecyclerView.Adapter<HomeSectionAdapter.SectionViewHolder>() {

    private var sections: List<HomeSection> = emptyList()
    private var originalSections: List<HomeSection> = emptyList()

    fun setSections(newSections: List<HomeSection>) {
        originalSections = newSections
        sections = newSections
        notifyDataSetChanged()
    }

    /**
     * Filter sections and their channels by [query].
     * Matches section titles or channel names. Empty query restores original data.
     */
    fun filterSections(query: String) {
        if (query.isEmpty()) {
            sections = originalSections
        } else {
            val q = query.lowercase()
            sections = originalSections.mapNotNull { section ->
                val titleMatch = section.title.lowercase().contains(q)
                val filteredChannels = section.channels.filter { ch ->
                    (ch.name?.lowercase()?.contains(q) == true) ||
                    (ch.groupTitle?.lowercase()?.contains(q) == true)
                }
                if (titleMatch) {
                    // Keep all channels if title matches
                    section
                } else if (filteredChannels.isNotEmpty()) {
                    // Keep only matching channels
                    section.copy(channels = filteredChannels)
                } else {
                    null // Drop section entirely
                }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = sections.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_section, parent, false)
        return SectionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SectionViewHolder, position: Int) {
        holder.bind(sections[position], onChannelClick, onChannelLongClick, onChannelFocus, onSeeAllClick)
    }

    class SectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {

        private val tvTitle: TextView = view.findViewById(R.id.tv_section_title)
        private val tvSeeAll: TextView? = view.findViewById(R.id.tv_section_see_all)
        private val recyclerCards: RecyclerView = view.findViewById(R.id.recyclerCards)
        private var cardAdapter: HomeCardAdapter? = null
        private var currentSection: HomeSection? = null

        fun bind(
            section: HomeSection,
            onChannelClick: (Channel) -> Unit,
            onChannelLongClick: (Channel) -> Unit,
            onChannelFocus: (Channel) -> Unit,
            onSeeAllClick: ((HomeSection) -> Unit)?
        ) {
            currentSection = section

            // Parse title: "Netflix [FR]" → title="Netflix", badge="FR"
            var displayTitle = section.title
            var lang: String? = null

            // Extract [XX] from title
            val bracketMatch = Regex("\\[([A-Z]{2,8})\\]\\s*$").find(displayTitle)
            if (bracketMatch != null) {
                lang = bracketMatch.groupValues[1]
                displayTitle = displayTitle.substring(0, bracketMatch.range.first).trim()
            }

            // Fallback: use first channel's countryPrefix
            if (lang == null) {
                lang = section.channels.firstOrNull()?.countryPrefix
            }

            if (!lang.isNullOrEmpty() && section.sectionType != SectionType.LIVE
                && section.sectionType != SectionType.FAVORITES
                && section.sectionType != SectionType.CONTINUE_WATCHING) {
                val spannable = android.text.SpannableStringBuilder(displayTitle)
                spannable.append("  ")
                // Apple TV pill badge: dark bg, rounded, generous padding
                val padded = "  $lang  "
                val badge = android.text.SpannableString(padded)
                badge.setSpan(
                    RoundedBadgeSpan(0xFF2C2C2E.toInt(), 0xB0FFFFFF.toInt(), 14f),
                    0, padded.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                badge.setSpan(
                    android.text.style.RelativeSizeSpan(0.65f),
                    0, padded.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.append(badge)
                tvTitle.text = spannable
            } else {
                tvTitle.text = displayTitle
            }

            val showSeeAll = onSeeAllClick != null && section.totalCount > section.channels.size
            tvSeeAll?.visibility = if (showSeeAll) View.VISIBLE else View.GONE
            tvSeeAll?.setOnClickListener { onSeeAllClick?.invoke(currentSection!!) }
            tvSeeAll?.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.06f else 1f)
                    .scaleY(if (hasFocus) 1.06f else 1f)
                    .translationZ(if (hasFocus) 8f else 0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }

            if (cardAdapter == null) {
                cardAdapter = HomeCardAdapter(
                    sectionType = section.sectionType,
                    onItemClick = { channel ->
                        if (channel.id == SEE_ALL_ID) {
                            currentSection?.let { onSeeAllClick?.invoke(it) }
                        } else {
                            onChannelClick(channel)
                        }
                    },
                    onItemLongClick = { channel -> if (channel.id != SEE_ALL_ID) onChannelLongClick(channel) },
                    onItemFocus = onChannelFocus
                )
                recyclerCards.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
                recyclerCards.adapter = cardAdapter
                recyclerCards.setItemViewCacheSize(30)
            } else {
                cardAdapter!!.updateSectionType(section.sectionType)
            }

            val showSeeAllInCarousel = onSeeAllClick != null && section.totalCount > section.channels.size
            cardAdapter!!.setData(section.channels, showSeeAllInCarousel)
        }
    }

    companion object {
        const val SEE_ALL_ID = -999
    }
}
