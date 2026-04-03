package com.salliptv.player.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.salliptv.player.R
import com.salliptv.player.model.Playlist

data class PlaylistItem(
    val playlist: Playlist,
    val channelCount: Int,
    val daysInactive: Long? = null
)

class PlaylistAdapter(
    private val onRefresh: (Playlist) -> Unit,
    private val onDelete: (Playlist) -> Unit
) : ListAdapter<PlaylistItem, PlaylistAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_playlist_name)
        val meta: TextView = view.findViewById(R.id.tv_playlist_meta)
        val warning: TextView = view.findViewById(R.id.tv_playlist_warning)
        val btnRefresh: TextView = view.findViewById(R.id.btn_refresh)
        val btnDelete: TextView = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val pl = item.playlist

        holder.name.text = pl.name
        holder.meta.text = "${pl.type} — ${item.channelCount} ch."

        if (item.daysInactive != null) {
            holder.warning.text = "Inactive ${item.daysInactive}d — Refresh or remove?"
            holder.warning.visibility = View.VISIBLE
        } else {
            holder.warning.visibility = View.GONE
        }

        holder.btnRefresh.setOnClickListener { onRefresh(pl) }
        holder.btnDelete.setOnClickListener { onDelete(pl) }

        // Focus animation
        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(150)
                .start()
        }
        holder.itemView.onFocusChangeListener = focusListener
        holder.btnRefresh.onFocusChangeListener = focusListener
        holder.btnDelete.onFocusChangeListener = focusListener
    }

    class DiffCallback : DiffUtil.ItemCallback<PlaylistItem>() {
        override fun areItemsTheSame(old: PlaylistItem, new: PlaylistItem) =
            old.playlist.id == new.playlist.id
        override fun areContentsTheSame(old: PlaylistItem, new: PlaylistItem) =
            old == new
    }
}
