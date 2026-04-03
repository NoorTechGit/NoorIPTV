package com.salliptv.player.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.salliptv.player.R

class CategoryAdapter(
    private var onCategoryClick: (String, Int) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

    // Backward-compatible setter
    fun setOnCategoryClickListener(listener: (String, Int) -> Unit) { onCategoryClick = listener }

    private var categories: List<String> = emptyList()
    private var counts: List<Int> = emptyList()
    var selectedPosition: Int = 0
        private set

    fun setCategories(categories: List<String>?, counts: List<Int>?) {
        this.categories = categories ?: emptyList()
        this.counts = counts ?: emptyList()
        selectedPosition = 0
        notifyDataSetChanged()
    }

    fun setSelectedPosition(position: Int) {
        val old = selectedPosition
        selectedPosition = position
        notifyItemChanged(old)
        notifyItemChanged(position)
    }

    override fun getItemCount(): Int = categories.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = categories[position]
        holder.tvName.text = category

        if (position < counts.size) {
            holder.tvCount.text = counts[position].toString()
            holder.tvCount.visibility = View.VISIBLE
        } else {
            holder.tvCount.visibility = View.GONE
        }

        val isSelected = position == selectedPosition

        // Selected = highlight flat, otherwise transparent
        applySelectionState(holder, isSelected)

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .translationZ(if (hasFocus) 6f else 0f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        holder.itemView.setOnClickListener {
            onCategoryClick(category, position)
            setSelectedPosition(position)
        }
    }

    private fun applySelectionState(holder: ViewHolder, isSelected: Boolean) {
        if (isSelected) {
            holder.itemView.setBackgroundColor(0x33FFFFFF)
            holder.tvName.setTextColor(Color.WHITE)
            holder.tvCount.setTextColor(0xCCFFFFFF.toInt())
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.tvName.setTextColor(0xFF8E8E93.toInt())
            holder.tvCount.setTextColor(0xFF5E5E63.toInt())
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_category_name)
        val tvCount: TextView = view.findViewById(R.id.tv_count)
    }
}
