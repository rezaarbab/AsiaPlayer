package com.asiaplayer

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
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
        val resumeBar: ProgressBar = view.findViewById(R.id.epResumeBar)
        val resumeLabel: TextView = view.findViewById(R.id.epResumeLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view)
    }

    fun setLocked(value: Boolean) { locked = value }

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

        // Resume progress
        val savedPos = holder.itemView.context
            .getSharedPreferences("PlayerPrefs", Context.MODE_PRIVATE)
            .getLong("pos_${item.id}", 0L)
        if (savedPos > 5_000L) {
            holder.resumeBar.visibility = View.VISIBLE
            holder.resumeLabel.visibility = View.VISIBLE
            holder.resumeLabel.text = "▶ ${formatTime(savedPos)}"
            // We only know position, not duration — show bar as indeterminate hint
            holder.resumeBar.isIndeterminate = false
            holder.resumeBar.progress = 30 // placeholder; real % needs duration
        } else {
            holder.resumeBar.visibility = View.GONE
            holder.resumeLabel.visibility = View.GONE
        }

        val enabled = !locked
        holder.itemView.isEnabled = enabled
        holder.itemView.isClickable = enabled
        holder.itemView.isFocusable = enabled
        holder.itemView.alpha = if (enabled) 1f else 0.55f
        holder.itemView.setOnClickListener { if (!locked) onClick(item) }
    }

    override fun getItemCount() = items.size

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }
}
