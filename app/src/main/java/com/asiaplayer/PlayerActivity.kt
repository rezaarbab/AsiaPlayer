package com.asiaplayer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import okhttp3.*
import java.io.IOException

class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: StyledPlayerView
    private lateinit var subtitleView: TextView
    private lateinit var loadingView: ProgressBar
    private lateinit var webView: WebView
    private var player: ExoPlayer? = null
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private val subtitles = mutableListOf<SubtitleEntry>()
    private var subtitleRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        subtitleView = findViewById(R.id.subtitleView)
        loadingView = findViewById(R.id.loadingView)

        val dramaId = intent.getIntExtra("dramaId", 0)
        val epId = intent.getIntExtra("epId", 0)
        val subUrl = intent.getStringExtra("subUrl") ?: ""
        val kkey = intent.getStringExtra("kkey") ?: ""

        loadingView.visibility = View.VISIBLE

        // اول زیرنویس لود کن
        if (subUrl.isNotEmpty()) {
            loadSubtitle(subUrl)
        }

        // بعد ویدیو از WebView بگیر
        setupWebViewForVideo(dramaId, epId, kkey)
    }

    private fun setupWebViewForVideo(dramaId: Int, epId: Int, kkey: String) {
        webView = WebView(this)
        WebViewHelper.setup(webView)

        var videoFound = false

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.proceed()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()

                // گرفتن m3u8
                if (!videoFound && (url.contains(".m3u8") || url.contains("index.m3u8")) 
                    && (url.contains("cdnvideo") || url.contains("streamingcdn") || url.contains("hls"))) {
                    videoFound = true
                    runOnUiThread { setupPlayer(url) }
                }
                return null
            }
        }

        val pageUrl = "https://kisskh.is/Drama/X/Episode-1?id=$dramaId&ep=$epId&page=0&pageSize=100"
        webView.loadUrl(pageUrl)

        // timeout — اگه ۳۰ ثانیه ویدیو پیدا نشد
        handler.postDelayed({
            if (!videoFound) {
                runOnUiThread {
                    loadingView.visibility = View.GONE
                    subtitleView.text = "Could not load video. Try again."
                }
            }
        }, 30000)
    }

    private fun setupPlayer(videoUrl: String) {
        loadingView.visibility = View.GONE

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
            "Referer" to "https://kisskh.is/"
        )

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"]!!)
            .setDefaultRequestProperties(headers)

        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(videoUrl))

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaSource(mediaSource)
            exo.prepare()
            exo.play()

            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    subtitleView.text = "Playback error: ${error.message}"
                }
            })
        }

        startSubtitleSync()
    }

    private fun loadSubtitle(url: String) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                parseSrt(body)
            }
        })
    }

    private fun parseSrt(content: String) {
        subtitles.clear()
        val isVtt = content.startsWith("WEBVTT")
        val blocks = content.trim().split("\n\n")
        for (block in blocks) {
            val lines = block.trim().split("\n")
            val timeLine = lines.find { it.contains("-->") } ?: continue
            val times = timeLine.split("-->")
            if (times.size == 2) {
                val start = parseTime(times[0].trim())
                val end = parseTime(times[1].trim())
                val timeIndex = lines.indexOfFirst { it.contains("-->") }
                val text = lines.drop(timeIndex + 1).joinToString("\n")
                    .replace(Regex("<[^>]*>"), "") // حذف HTML tags
                    .trim()
                if (text.isNotEmpty() && start >= 0) {
                    subtitles.add(SubtitleEntry(start, end, text))
                }
            }
        }
    }

    private fun parseTime(time: String): Long {
        return try {
            val clean = time.replace(",", ".").trim().substringBefore(" ")
            val parts = clean.split(":")
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toDouble()
                    h * 3600000 + m * 60000 + (s * 1000).toLong()
                }
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toDouble()
                    m * 60000 + (s * 1000).toLong()
                }
                else -> -1L
            }
        } catch (e: Exception) { -1L }
    }

    private fun startSubtitleSync() {
        subtitleRunnable = object : Runnable {
            override fun run() {
                val pos = player?.currentPosition ?: 0L
                val current = subtitles.find { pos >= it.start && pos <= it.end }
                subtitleView.text = current?.text ?: ""
                handler.postDelayed(this, 100)
            }
        }
        handler.post(subtitleRunnable!!)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        subtitleRunnable?.let { handler.removeCallbacks(it) }
        player?.release()
        webView.destroy()
    }
}

data class SubtitleEntry(val start: Long, val end: Long, val text: String)
