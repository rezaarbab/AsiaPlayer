package com.asiaplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

object ImageLoader {

    private val client = OkHttpClient()
    private val memoryCache = object : LruCache<String, Bitmap>(30) {}
    private val handler = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    fun load(url: String, onResult: (Bitmap?) -> Unit) {
        if (url.isEmpty()) {
            onResult(null)
            return
        }
        memoryCache.get(url)?.let {
            onResult(it)
            return
        }
        val queue = synchronized(pending) { pending.getOrPut(url) { mutableListOf() } }
        synchronized(queue) {
            queue.add(onResult)
            if (queue.size > 1) return
        }

        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = deliver(url, null)
            override fun onResponse(call: Call, response: Response) {
                val bytes = response.body?.bytes()
                response.close()
                val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (bmp != null) memoryCache.put(url, bmp)
                deliver(url, bmp)
            }
        })
    }

    private fun deliver(url: String, bmp: Bitmap?) {
        val queue = synchronized(pending) { pending.remove(url) } ?: return
        handler.post {
            queue.forEach { it(bmp) }
        }
    }
}
