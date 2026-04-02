package com.salliptv.player.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.salliptv.player.R
import com.salliptv.player.model.Channel

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
            tvTitle.text = section.title

            val showSeeAll = onSeeAllClick != null && section.totalCount > section.channels.size
            tvSeeAll?.visibility = if (showSeeAll) View.VISIBLE else View.GONE
            tvSeeAll?.setOnClickListener { onSeeAllClick?.invoke(currentSection!!) }
            tvSeeAll?.setOnFocusChangeListener { v, hasFocus ->
                (v as TextView).setTextColor(if (hasFocus) 0xFFFFFFFF.toInt() else 0xFF0A84FF.toInt())
                v.scaleX = if (hasFocus) 1.1f else 1f
                v.scaleY = if (hasFocus) 1.1f else 1f
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
