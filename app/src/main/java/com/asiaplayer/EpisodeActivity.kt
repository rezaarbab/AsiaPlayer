package com.asiaplayer

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class EpisodeActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var debugText: TextView
    private lateinit var debugScroll: ScrollView
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private var dramaId = 0
    private var dramaTitle = ""
    private var isGettingKey = false
    private var currentSubtitles = listOf<SubtitleTrack>()

    data class SubtitleTrack(val lang: String, val label: String, val src: String)

    private fun log(msg: String, isError: Boolean = false) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] ${if (isError) "❌" else "✅"} $msg\n"
        handler.post {
            debugText.append(line)
            debugScroll.post { debugScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dramaId = intent.getIntExtra("dramaId", 0)
        dramaTitle = intent.getStringExtra("dramaTitle") ?: ""
        setContentView(R.layout.activity_episodes)

        val titleView = findViewById<TextView>(R.id.episodeDramaTitle)
        val recycler = findViewById<RecyclerView>(R.id.episodeRecycler)
        val loading = findViewById<ProgressBar>(R.id.episodeLoading)
        debugText = findViewById(R.id.episodeDebugText)
        debugScroll = findViewById(R.id.episodeDebugScroll)

        titleView.text = dramaTitle
        recycler.layoutManager = LinearLayoutManager(this)

        webView = findViewById(R.id.episodeWebView)
        WebViewHelper.setup(webView)

        log("Loading: $dramaTitle (id=$dramaId)")
        loadEpisodes(recycler, loading)
    }

    private fun loadEpisodes(recycler: RecyclerView, loading: ProgressBar) {
        loading.visibility = View.VISIBLE
        val request = Request.Builder()
            .url("https://kisskh.is/api/DramaList/Drama/$dramaId?isq=true")
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://kisskh.is/")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("FAILED: ${e.message}", true)
                runOnUiThread { loading.visibility = View.GONE }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val detail = JSONObject(body)
                    val episodes = detail.getJSONArray("episodes")
                    val thumbnail = detail.optString("thumbnail", "")
                    val status = detail.optString("status", "")
                    val description = detail.optString("description", "")

                    val items = mutableListOf<EpisodeItem>()
                    for (i in 0 until episodes.length()) {
                        val ep = episodes.getJSONObject(i)
                        val numRaw = ep.optDouble("number", (i + 1).toDouble())
                        val num = if (numRaw == numRaw.toLong().toDouble()) numRaw.toLong().toInt() else numRaw.toInt()
                        items.add(EpisodeItem(ep.getInt("id"), num, ep.optInt("sub", 0) > 0))
                    }

                    log("Found ${items.size} episodes")
                    runOnUiThread {
                        loading.visibility = View.GONE
                        showInfo(thumbnail, status, description, items.size)
                        recycler.adapter = EpisodeAdapter(items) { ep ->
                            showSubtitleOptions(ep)
                        }
                    }
                } catch (e: Exception) {
                    log("Error: ${e.message}", true)
                    runOnUiThread { loading.visibility = View.GONE }
                }
            }
        })
    }

    private fun showInfo(thumbnail: String, status: String, description: String, epCount: Int) {
        val infoLayout = findViewById<LinearLayout>(R.id.infoLayout)
        val posterImage = findViewById<ImageView>(R.id.posterImage)
        val infoStatus = findViewById<TextView>(R.id.infoStatus)
        val infoEpisodes = findViewById<TextView>(R.id.infoEpisodes)
        val infoTitle = findViewById<TextView>(R.id.infoTitle)
        val infoDesc = findViewById<TextView>(R.id.infoDesc)

        infoLayout.visibility = View.VISIBLE
        infoTitle.text = dramaTitle
        infoStatus.text = "Status: $status"
        infoEpisodes.text = "$epCount Episodes"
        infoDesc.text = description

        if (thumbnail.isNotEmpty()) {
            log("Loading poster...")
            val req = Request.Builder().url(thumbnail).build()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    log("Poster failed: ${e.message}", true)
                }
                override fun onResponse(call: Call, response: Response) {
                    val bytes = response.body?.bytes() ?: return
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    runOnUiThread { posterImage.setImageBitmap(bitmap) }
                    log("Poster loaded!")
                }
            })
        }
    }

    private fun showSubtitleOptions(ep: EpisodeItem) {
        if (isGettingKey) {
            log("Already loading...", true)
            return
        }
        isGettingKey = true
        log("Getting key for ep ${ep.number}")

        val encodedTitle = dramaTitle.replace(" ", "-").replace("(", "").replace(")", "")
        val pageUrl = "https://kisskh.is/Drama/$encodedTitle/Episode-${ep.number}?id=$dramaId&ep=${ep.id}&page=0&pageSize=100"
        var keyFound = false

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, h: SslErrorHandler, e: android.net.http.SslError) { h.proceed() }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (!keyFound && url.contains("/api/Sub/${ep.id}") && url.contains("kkey=")) {
                    val kkey = url.substringAfter("kkey=").substringBefore("&").trim()
                    if (kkey.length > 10) {
                        keyFound = true
                        log("Key found! Fetching subs...")
                        fetchSubs(ep, kkey)
                    }
                }
                return null
            }
        }
        webView.loadUrl(pageUrl)

        handler.postDelayed({
            if (!keyFound) {
                log("Timeout - opening without subtitle", true)
                isGettingKey = false
                openPlayer(ep, "", "")
            }
        }, 45000)
    }

    private fun fetchSubs(ep: EpisodeItem, kkey: String) {
        val request = Request.Builder()
            .url("https://kisskh.is/api/Sub/${ep.id}?kkey=$kkey")
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://kisskh.is/")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("Sub fetch failed: ${e.message}", true)
                runOnUiThread { isGettingKey = false; openPlayer(ep, "", kkey) }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                try {
                    val subs = JSONArray(body)
                    val tracks = mutableListOf<SubtitleTrack>()
                    for (i in 0 until subs.length()) {
                        val sub = subs.getJSONObject(i)
                        tracks.add(SubtitleTrack(
                            lang = sub.optString("land", "unknown"),
                            label = sub.optString("label", "Unknown"),
                            src = sub.optString("src", "")
                        ))
                    }
                    log("Found ${tracks.size} subtitle tracks")
                    currentSubtitles = tracks

                    runOnUiThread {
                        isGettingKey = false
                        showSubtitleDialog(ep, tracks, kkey)
                    }
                } catch (e: Exception) {
                    log("Sub parse error: ${e.message}", true)
                    runOnUiThread { isGettingKey = false; openPlayer(ep, "", kkey) }
                }
            }
        })
    }

    private fun showSubtitleDialog(ep: EpisodeItem, tracks: List<SubtitleTrack>, kkey: String) {
        val options = mutableListOf<String>()
        options.add("▶ Play without subtitle")
        tracks.forEach { options.add("▶ Play - ${it.label}") }
        options.add("─────────────")
        tracks.forEach { options.add("⬇ Download - ${it.label}") }

        android.app.AlertDialog.Builder(this)
            .setTitle("Episode ${ep.number}")
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which == 0 -> openPlayer(ep, "", kkey)
                    which in 1..tracks.size -> {
                        val track = tracks[which - 1]
                        openPlayer(ep, track.src, kkey)
                    }
                    which > tracks.size + 1 -> {
                        val trackIndex = which - tracks.size - 2
                        if (trackIndex < tracks.size) {
                            downloadSubtitle(tracks[trackIndex], ep.number)
                        }
                    }
                }
            }
            .show()
    }

    private fun downloadSubtitle(track: SubtitleTrack, epNumber: Int) {
        val fileName = "${dramaTitle.replace(" ", "_")}_E${epNumber}_${track.lang}.srt"
        log("Downloading: $fileName")
        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(track.src))
                .setTitle("$dramaTitle - Episode $epNumber - ${track.label}")
                .setDescription("Subtitle download")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .addRequestHeader("User-Agent", "Mozilla/5.0")
            dm.enqueue(req)
            Toast.makeText(this, "Downloading ${track.label}...", Toast.LENGTH_SHORT).show()
            log("Download started: $fileName")
        } catch (e: Exception) {
            log("Download failed: ${e.message}", true)
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openPlayer(ep: EpisodeItem, subUrl: String, kkey: String) {
        log("Opening player: sub=${subUrl.isNotEmpty()}")
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("dramaId", dramaId)
            putExtra("dramaTitle", dramaTitle)
            putExtra("epNumber", ep.number)
            putExtra("epId", ep.id)
            putExtra("subUrl", subUrl)
            putExtra("kkey", kkey)
        }
        startActivity(intent)
    }
}

data class EpisodeItem(val id: Int, val number: Int, val hasSub: Boolean)
