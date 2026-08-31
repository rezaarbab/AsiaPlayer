package com.asiaplayer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DramaAdapter(
    private val items: List<DramaItem>,
    private val onClick: (DramaItem) -> Unit
) : RecyclerView.Adapter<DramaAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.dramaPoster)
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

        holder.title.text = item.title
        holder.episode.text = if (item.episodeCount > 0) "${item.episodeCount} episodes" else ""

        // بخش خالی نمایش داده نشه (مثل سایت)
        if (item.status.isNotEmpty()) {
            holder.status.visibility = View.VISIBLE
            holder.status.text = item.status
            holder.status.background = when (item.status.lowercase()) {
                "ongoing" -> holder.status.context.getDrawableCompat(R.drawable.bg_gradient_accent)
                else -> holder.status.context.getDrawableCompat(R.drawable.bg_chip_dark)
            }
        } else {
            holder.status.visibility = View.GONE
        }

        holder.poster.setImageBitmap(null)

        // پوستر نگیرد => فقط پلیس‌هولدر خوشگل میمونه
        ImageLoader.load(item.thumbnailUrl) { bmp ->
            if (holder.adapterPosition == position) {
                if (bmp != null) {
                    holder.poster.setImageBitmap(bmp)
                } else {
                    holder.poster.setImageBitmap(null)
                    holder.poster.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }

        // فقط آیتم معتبر کلیک‌پذیره
        val valid = item.id > 0 && item.title.isNotEmpty()
        holder.itemView.isEnabled = valid
        holder.itemView.isClickable = valid
        holder.itemView.isFocusable = valid
        holder.itemView.setOnClickListener { if (valid) onClick(item) }
    }

    override fun getItemCount() = items.size
}

fun android.content.Context.getDrawableCompat(id: Int): android.graphics.drawable.Drawable? =
    androidx.core.content.ContextCompat.getDrawable(this, id)
