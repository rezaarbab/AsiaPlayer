package com.asiaplayer

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import java.io.IOException

class DramaAdapter(
    private val items: List<DramaItem>,
    private val onClick: (DramaItem) -> Unit
) : RecyclerView.Adapter<DramaAdapter.ViewHolder>() {

    private val client = OkHttpClient()

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
        holder.episode.text = "${item.episodeCount} episodes"
        holder.status.text = item.status
        holder.poster.setImageBitmap(null)
        holder.poster.setBackgroundColor(0xFF1A1A1A.toInt())

        // لود پوستر
        if (item.thumbnailUrl.isNotEmpty()) {
            val req = Request.Builder().url(item.thumbnailUrl)
                .header("User-Agent", "Mozilla/5.0").build()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    val bytes = response.body?.bytes() ?: return
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    (holder.itemView.context as? android.app.Activity)?.runOnUiThread {
                        if (holder.adapterPosition == position) {
                            holder.poster.setImageBitmap(bitmap)
                        }
                    }
                }
            })
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
