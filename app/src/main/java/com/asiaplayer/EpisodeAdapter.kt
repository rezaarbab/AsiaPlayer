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
        val number: TextView = view.findViewById(R.id.epNumber)
        val title: TextView = view.findViewById(R.id.epTitle)
        val subStatus: TextView = view.findViewById(R.id.epSubStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.number.text = item.number.toString()
        holder.title.text = "Episode ${item.number}"
        if (item.hasSub) {
            holder.subStatus.text = "Subtitle available"
            holder.subStatus.setTextColor(Color.parseColor("#00C853"))
        } else {
            holder.subStatus.text = "No subtitle yet"
            holder.subStatus.setTextColor(Color.parseColor("#666666"))
        }
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
