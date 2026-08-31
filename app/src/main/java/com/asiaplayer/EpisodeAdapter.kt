package com.asiaplayer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class EpisodeAdapter(
    val items: List<EpisodeItem>,
    private val onClick: (EpisodeItem) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    private var locked = false

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.epNumber)
        val title: TextView = view.findViewById(R.id.epTitle)
        val subStatus: TextView = view.findViewById(R.id.epSubStatus)
        val playIcon: ImageView = view.findViewById(R.id.epPlayIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view)
    }

    fun setLocked(value: Boolean) {
        locked = value
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.number.text = item.number.toString()
        holder.title.text = "Episode ${item.number}"

        if (item.hasSub) {
            holder.subStatus.text = "SUBTITLES READY"
            holder.subStatus.setTextColor(Color.parseColor("#22C55E"))
            holder.playIcon.alpha = 0.95f
        } else {
            holder.subStatus.text = "NO SUBTITLE"
            holder.subStatus.setTextColor(Color.parseColor("#5A5F6E"))
            holder.playIcon.alpha = 0.5f
        }

        // تا وقتی استریم در حال آماده‌شدنه، هیچ آیتمی کلیک‌پذیر نیست
        val enabled = !locked
        holder.itemView.isEnabled = enabled
        holder.itemView.isClickable = enabled
        holder.itemView.isFocusable = enabled
        holder.itemView.alpha = if (enabled) 1f else 0.55f
        holder.itemView.setOnClickListener { if (!locked) onClick(item) }
    }

    override fun getItemCount() = items.size
}
