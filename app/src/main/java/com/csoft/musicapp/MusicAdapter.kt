package com.csoft.musicapp

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class MusicFile(val title: String, val artist: String?, val uri: Uri)

class MusicAdapter(
    private val items: MutableList<MusicFile>,
    private val onItemClick: (MusicFile) -> Unit
) : RecyclerView.Adapter<MusicAdapter.VH>() {

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
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<MusicFile>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
