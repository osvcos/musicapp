package com.csoft.musicapp

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

data class MusicFile(val title: String, val artist: String?, val uri: Uri, val filename: String)

class MusicAdapter(
    private val items: MutableList<MusicFile>,
    private val onItemClick: (MusicFile) -> Unit
) : RecyclerView.Adapter<MusicAdapter.VH>() {

    private var playingUri: android.net.Uri? = null

    fun setPlayingUri(uri: android.net.Uri?) {
        val old = playingUri
        if (old == uri) return
        // find indices to refresh
        var oldIndex = -1
        var newIndex = -1
        if (old != null) {
            for (i in items.indices) if (items[i].uri == old) { oldIndex = i; break }
        }
        if (uri != null) {
            for (i in items.indices) if (items[i].uri == uri) { newIndex = i; break }
        }
        playingUri = uri
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        if (newIndex >= 0) notifyItemChanged(newIndex)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.text_title)
        val artist: TextView = view.findViewById(R.id.text_artist)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_music, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.artist.text = item.artist ?: ""
        holder.itemView.setOnClickListener { onItemClick(item) }
        // highlight if this is the currently playing item
        if (playingUri != null && item.uri == playingUri) {
            val color = ContextCompat.getColor(holder.itemView.context, R.color.current_song_selected)
            holder.itemView.setBackgroundColor(color)
        } else {
            holder.itemView.setBackgroundColor(ContextCompat.getColor(holder.itemView.context, android.R.color.transparent))
        }
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<MusicFile>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
        // clear playing URI if it's not present
        if (playingUri != null) {
            var found = false
            for (it in items) if (it.uri == playingUri) { found = true; break }
            if (!found) playingUri = null
        }
    }
}
