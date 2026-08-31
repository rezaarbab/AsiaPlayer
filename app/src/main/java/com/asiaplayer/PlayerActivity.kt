package com.asiaplayer

import android.os.Bundle
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var errorOverlay: LinearLayout
    private lateinit var playerErrorText: TextView
    private var webView: WebView? = null
    private var player: ExoPlayer? = null
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private val subtitles = mutableListOf<SubtitleEntry>()
    private var subtitleRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null

    private var dramaId = 0
    private var epId = 0
    private var subUrl = ""
    private var kkey = ""
    private var videoFound = false
    private val subtitleLabels = mutableListOf("Off")
    private val subtitleUrls = mutableListOf("")
    private val pickSubtitle = 7001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        subtitleView = findViewById(R.id.subtitleView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        errorOverlay = findViewById(R.id.errorOverlay)
        playerErrorText = findViewById(R.id.playerErrorText)

        dramaId = intent.getIntExtra("dramaId", 0)
        epId = intent.getIntExtra("epId", 0)
        subUrl = intent.getStringExtra("subUrl") ?: ""
        kkey = intent.getStringExtra("kkey") ?: ""
        intent.getStringArrayListExtra("subtitleLabels")?.let {
            subtitleLabels.clear(); subtitleLabels.add("Off"); subtitleLabels.addAll(it)
        }
        intent.getStringArrayListExtra("subtitleUrls")?.let {
            subtitleUrls.clear(); subtitleUrls.add(""); subtitleUrls.addAll(it)
        }

        findViewById<android.view.View>(R.id.playerBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.playerRetry).setOnClickListener { startPlayback() }
        findViewById<TextView>(R.id.subtitleMenu).setOnClickListener { showSubtitleMenu() }
        playerView.setControllerVisibilityListener(StyledPlayerView.ControllerVisibilityListener { visibility ->
            findViewById<TextView>(R.id.subtitleMenu).animate()
                .alpha(if (visibility == View.VISIBLE) 1f else 0f)
                .setDuration(180)
                .withEndAction {
                    findViewById<TextView>(R.id.subtitleMenu).visibility =
                        if (visibility == View.VISIBLE) View.VISIBLE else View.INVISIBLE
                }.start()
        })

        if (subUrl.isNotEmpty()) {
            loadSubtitle(subUrl)
        }

        startPlayback()
    }

    private fun showSubtitleMenu() {
        val options = subtitleLabels.toMutableList().apply { add("Add subtitle file…") }
        AlertDialog.Builder(this).setTitle("Subtitles")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == options.lastIndex) {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "text/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }, pickSubtitle)
                } else {
                    val url = subtitleUrls.getOrNull(which).orEmpty()
                    subtitles.clear()
                    if (url.isNotEmpty()) loadSubtitle(url)
                    subtitleView.text = ""
                }
            }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickSubtitle || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { parseSrt(it.readText()) }
        } catch (_: Exception) {
            Toast.makeText(this, "Could not read subtitle file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPlayerState(state: String) {
        loadingOverlay.visibility = if (state == "loading") View.VISIBLE else View.GONE
        errorOverlay.visibility = if (state == "error") View.VISIBLE else View.GONE
    }

    private fun startPlayback() {
        videoFound = false
        showPlayerState("loading")

        setupWebViewForVideo()
    }

    private fun setupWebViewForVideo() {
        webView?.destroy()
        val wv = WebView(this)
        webView = wv
        WebViewHelper.setup(wv)

        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.proceed()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()

                if (!videoFound && (url.contains(".m3u8") || url.contains("index.m3u8"))
                    && (url.contains("cdnvideo") || url.contains("streamingcdn") || url.contains("hls"))) {
                    videoFound = true
                    runOnUiThread { setupPlayer(url) }
                }
                return null
            }
        }

        val pageUrl = "https://kisskh.is/Drama/X/Episode-1?id=$dramaId&ep=$epId&page=0&pageSize=100"
        wv.loadUrl(pageUrl)

        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = Runnable {
            if (!videoFound) {
                runOnUiThread {
                    showPlayerState("error")
                    playerErrorText.text = "Stream didn't load in time"
                }
            }
        }
        handler.postDelayed(timeoutRunnable!!, 30000)
    }

    private fun setupPlayer(videoUrl: String) {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        showPlayerState("none")

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
                    runOnUiThread {
                        showPlayerState("error")
                        playerErrorText.text = "Playback error"
                    }
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
                response.close()
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
                    .replace(Regex("<[^>]*>"), "")
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
        subtitleRunnable?.let { handler.removeCallbacks(it) }
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
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        player?.release()
        webView?.destroy()
    }
}

data class SubtitleEntry(val start: Long, val end: Long, val text: String)
