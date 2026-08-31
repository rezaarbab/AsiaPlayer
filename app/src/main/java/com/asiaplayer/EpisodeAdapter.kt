package com.asiaplayer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class EpisodeAdapter(
    private val items: List<EpisodeItem>,
    private val onClick: (EpisodeItem) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.titleText)
        val episode: TextView = view.findViewById(R.id.episodeText)
        val status: TextView = view.findViewById(R.id.statusText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_drama, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = "Episode ${item.number}"
        holder.episode.text = if (item.hasSub) "✅ Subtitle available" else "⏳ No subtitle yet"
        holder.episode.setTextColor(if (item.hasSub) Color.parseColor("#00ff88") else Color.parseColor("#888888"))
        holder.status.text = ""
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
