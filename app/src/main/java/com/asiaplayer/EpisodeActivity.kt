package com.asiaplayer

import android.animation.ObjectAnimator
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.app.Dialog
import androidx.appcompat.app.AlertDialog
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
    private lateinit var debugToggle: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var skeleton: LinearLayout
    private lateinit var errorView: LinearLayout
    private lateinit var adapter: EpisodeAdapter
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private var dramaId = 0
    private var dramaTitle = ""
    private var isGettingKey = false
    private var dataLoaded = false
    private var skeletonAnim: ObjectAnimator? = null

    data class SubtitleTrack(val lang: String, val label: String, val src: String)

    private fun log(msg: String, isError: Boolean = false) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] ${if (isError) "X" else "OK"} $msg\n"
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

        recycler = findViewById(R.id.episodeRecycler)
        skeleton = findViewById(R.id.skeletonContainer)
        errorView = findViewById(R.id.episodeError)
        debugText = findViewById(R.id.episodeDebugText)
        debugScroll = findViewById(R.id.episodeDebugScroll)
        debugToggle = findViewById(R.id.episodeDebugToggle)

        recycler.layoutManager = LinearLayoutManager(this)
        adapter = EpisodeAdapter(emptyList()) { ep ->
            if (!isGettingKey) getKeyAndPlay(ep)
        }
        recycler.adapter = adapter

        webView = findViewById(R.id.episodeWebView)
        WebViewHelper.setup(webView)

        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }

        findViewById<TextView>(R.id.episodeRetry).setOnClickListener { loadEpisodes() }

        debugToggle.setOnClickListener {
            val open = debugScroll.visibility == View.VISIBLE
            debugScroll.visibility = if (open) View.GONE else View.VISIBLE
            debugToggle.text = if (open) "\u25B6  DEBUG LOG" else "\u25BC  DEBUG LOG"
        }

        log("Loading: $dramaTitle")
        loadEpisodes()
    }

    private fun showEpisodesState(state: String) {
        skeleton.visibility = if (state == "loading") View.VISIBLE else View.GONE
        recycler.visibility = if (state == "list") View.VISIBLE else View.GONE
        errorView.visibility = if (state == "error") View.VISIBLE else View.GONE
        if (state == "loading") startSkeletonPulse() else stopSkeletonPulse()
    }

    private fun startSkeletonPulse() {
        skeletonAnim?.cancel()
        skeletonAnim = ObjectAnimator.ofFloat(skeleton, "alpha", 1f, 0.35f, 1f).apply {
            duration = 1100
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopSkeletonPulse() {
        skeletonAnim?.cancel()
        skeletonAnim = null
        skeleton.alpha = 1f
    }

    private fun loadEpisodes() {
        if (dramaId <= 0) {
            findViewById<TextView>(R.id.episodeErrorText)?.text = "Invalid drama"
            showEpisodesState("error")
            return
        }
        dataLoaded = false
        showEpisodesState("loading")
        recycler.adapter = adapter

        val selectedHost = SourceRegistry.host(this)
        val family = SourceRegistry.all.find { s -> s.hosts.contains(selectedHost) } ?: SourceRegistry.all[0]
        val customHosts = SourceRegistry.getCustomHosts(this)
        val candidates = (customHosts + listOf(selectedHost) + family.hosts).distinct()
        tryLoadEpisodesOnHosts(candidates, 0)
    }

    private fun tryLoadEpisodesOnHosts(hosts: List<String>, index: Int) {
        if (index >= hosts.size) {
            log("Direct requests failed — WebView fallback", true)
            runOnUiThread { loadEpisodesViaWebView(hosts[0], hosts) }
            return
        }
        val host = hosts[index]
        log("Trying $host for drama $dramaId")

        val request = Request.Builder()
            .url("https://$host/api/DramaList/Drama/$dramaId?isq=true")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            .header("Referer", "https://$host/")
            .header("Accept", "application/json, */*")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("$host failed: ${e.message}", true)
                tryLoadEpisodesOnHosts(hosts, index + 1)
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                response.close()
                val bodyTrimmed = body.trimStart()
                if (response.code !in 200..299 || bodyTrimmed.startsWith("<!") || bodyTrimmed.startsWith("<html")) {
                    log("$host returned HTML — skipping", true)
                    tryLoadEpisodesOnHosts(hosts, index + 1)
                    return
                }
                SourceRegistry.setHost(this@EpisodeActivity, host)
                parseAndShowEpisodes(body, host)
            }
        })
    }

    private var wvEpDone = false
    private var wvEpHosts = listOf<String>()
    private var wvEpHostIndex = 0

    private fun loadEpisodesViaWebView(host: String, allHosts: List<String>) {
        wvEpDone = false
        wvEpHosts = allHosts
        wvEpHostIndex = allHosts.indexOf(host).coerceAtLeast(0)
        log("CF-bypass: loading $host for episode $dramaId")

        android.webkit.CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            private var homepageLoaded = false

            override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: android.net.http.SslError) { h.proceed() }

            override fun onPageFinished(view: WebView, url: String) {
                if (wvEpDone) return
                if (url.contains("/cdn-cgi/") || url.contains("challenge-")) return

                if (!homepageLoaded && url.contains(host)) {
                    homepageLoaded = true
                    log("$host homepage ready — stealing cookie in 2s")
                    handler.postDelayed({
                        if (wvEpDone) return@postDelayed
                        val cookies = android.webkit.CookieManager.getInstance()
                            .getCookie("https://$host/") ?: ""
                        log("Cookies: ${if (cookies.isEmpty()) "NONE" else cookies.take(80)}")
                        tryEpApiWithCookies(host, cookies)
                    }, 2_000L)
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): WebResourceResponse? {
                val reqUrl = request.url.toString()
                if (!wvEpDone && reqUrl.contains("/api/DramaList/Drama/$dramaId")) {
                    val cookies = android.webkit.CookieManager.getInstance()
                        .getCookie("https://$host/") ?: ""
                    Thread { tryEpApiWithCookies(host, cookies, reqUrl) }.start()
                }
                return null
            }
        }
        webView.loadUrl("https://$host/")

        handler.postDelayed({
            if (!wvEpDone) { log("$host timeout (40s)", true); runOnUiThread { tryNextWebViewEpHost() } }
        }, 40_000L)
    }

    private fun tryEpApiWithCookies(host: String, cookies: String, overrideUrl: String? = null) {
        if (wvEpDone) return
        val url = overrideUrl ?: "https://$host/api/DramaList/Drama/$dramaId?isq=true"
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/json, */*")
            .header("Referer", "https://$host/")
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { if (cookies.isNotEmpty()) header("Cookie", cookies) }
            .build()

        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                log("Cookie-API $host failed: ${e.message}", true)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: ""; response.close()
                val trimmed = body.trimStart()
                log("Cookie-API $host ${response.code} ${body.length}B — ${trimmed.take(30)}")
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    wvEpDone = true
                    SourceRegistry.setHost(this@EpisodeActivity, host)
                    parseAndShowEpisodes(body, host)
                } else {
                    log("$host still HTML with cookies", true)
                }
            }
        })
    }

    private fun tryNextWebViewEpHost() {
        wvEpHostIndex++
        if (wvEpHostIndex >= minOf(wvEpHosts.size, 3)) {
            log("All WebView ep hosts exhausted", true)
            runOnUiThread { showEpisodesState("error") }
            return
        }
        runOnUiThread { loadEpisodesViaWebView(wvEpHosts[wvEpHostIndex], wvEpHosts) }
    }

    private fun parseAndShowEpisodes(body: String, host: String) {
        try {
            val detail = JSONObject(body)
            val episodes = detail.getJSONArray("episodes")
            val thumbnail = detail.optString("thumbnail", "")
            val status = detail.optString("status", "")
            val description = detail.optString("description", "")
            val country = detail.optString("country", "")

            val items = mutableListOf<EpisodeItem>()
            for (i in 0 until episodes.length()) {
                val ep = episodes.getJSONObject(i)
                val numRaw = ep.optDouble("number", (i + 1).toDouble())
                val num = if (numRaw == numRaw.toLong().toDouble()) numRaw.toLong().toInt() else numRaw.toInt()
                items.add(EpisodeItem(ep.getInt("id"), num, ep.optInt("sub", 0) > 0))
            }
            log("${items.size} episodes from $host")
            runOnUiThread {
                setupInfo(thumbnail, status, description, country, items.size)
                if (items.isEmpty()) {
                    findViewById<TextView>(R.id.episodeErrorText)?.text = "No episodes available"
                    showEpisodesState("error")
                } else {
                    dataLoaded = true
                    adapter = EpisodeAdapter(items) { ep ->
                        if (!isGettingKey) getKeyAndPlay(ep)
                    }
                    recycler.adapter = adapter
                    showEpisodesState("list")
                }
            }
        } catch (e: Exception) {
            log("Parse error: ${e.message}", true)
            runOnUiThread { showEpisodesState("error") }
        }
    }

    private fun setupInfo(thumbnail: String, status: String, desc: String, country: String, epCount: Int) {
        val titleView = findViewById<TextView>(R.id.episodeDramaTitle)
        val statusView = findViewById<TextView>(R.id.infoStatus)
        val epsView = findViewById<TextView>(R.id.infoEpisodes)
        val countryView = findViewById<TextView>(R.id.infoCountry)
        val descView = findViewById<TextView>(R.id.infoDesc)
        val badge = findViewById<TextView>(R.id.episodeCountBadge)
        val posterView = findViewById<ImageView>(R.id.posterImage)
        val backdropView = findViewById<ImageView>(R.id.backdropImage)

        titleView.text = dramaTitle

        // قسمت‌هایی که نیومدن، اصلا نشون داده نمیشن (مثل سایت)
        if (status.isNotEmpty()) {
            statusView.visibility = View.VISIBLE
            statusView.text = status
        } else statusView.visibility = View.GONE

        if (epCount > 0) {
            epsView.visibility = View.VISIBLE
            epsView.text = "$epCount EPS"
            badge.visibility = View.VISIBLE
            badge.text = epCount.toString()
        } else {
            epsView.visibility = View.GONE
            badge.visibility = View.GONE
        }

        if (country.isNotEmpty()) {
            countryView.visibility = View.VISIBLE
            countryView.text = country
        } else countryView.visibility = View.GONE

        if (desc.isNotEmpty()) {
            descView.visibility = View.VISIBLE
            descView.text = desc
        } else descView.visibility = View.GONE

        if (thumbnail.isNotEmpty()) {
            ImageLoader.load(thumbnail) { bmp ->
                bmp?.let {
                    posterView.setImageBitmap(it)
                    backdropView.setImageBitmap(it)
                }
            }
        }
    }

    private fun getKeyAndPlay(ep: EpisodeItem) {
        if (!dataLoaded || isGettingKey) return
        isGettingKey = true
        adapter.setLocked(true)
        adapter.notifyDataSetChanged()
        Toast.makeText(this, "Preparing episode ${ep.number}...", Toast.LENGTH_SHORT).show()
        log("Getting key ep ${ep.number}")
        val encoded = dramaTitle.replace(" ", "-").replace("(", "").replace(")", "")
        val host = SourceRegistry.host(this)
        val pageUrl = "https://$host/Drama/$encoded/Episode-${ep.number}?id=$dramaId&ep=${ep.id}&page=0&pageSize=100"
        var keyFound = false

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: android.net.http.SslError) { h.proceed() }
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (!keyFound && url.contains("/api/Sub/${ep.id}") && url.contains("kkey=")) {
                    val kkey = url.substringAfter("kkey=").substringBefore("&").trim()
                    if (kkey.length > 10) {
                        keyFound = true
                        log("Key found!")
                        fetchSubs(ep, kkey)
                    }
                }
                return null
            }
        }
        webView.loadUrl(pageUrl)

        // اگه استریم نیومد => کاربر به جای پلیر خراب، خطا + Retry میبینه
        handler.postDelayed({
            if (!keyFound && isGettingKey) {
                isGettingKey = false
                adapter.setLocked(false)
                adapter.notifyDataSetChanged()
                log("Stream key timeout", true)
                AlertDialog.Builder(this)
                    .setTitle("Episode ${ep.number}")
                    .setMessage("Couldn't load this episode. The stream didn't respond.")
                    .setPositiveButton("Retry") { d, _ -> d.dismiss(); getKeyAndPlay(ep) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }, 45000)
    }

    private fun fetchSubs(ep: EpisodeItem, kkey: String) {
        val host = SourceRegistry.host(this)
        val request = Request.Builder()
            .url("https://$host/api/Sub/${ep.id}?kkey=$kkey")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            .header("Referer", "https://$host/")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("Subtitle request failed; offering subtitle-free playback", true)
                runOnUiThread {
                    unlock()
                    Toast.makeText(this@EpisodeActivity, "No subtitles available for this source", Toast.LENGTH_SHORT).show()
                    showDialog(ep, emptyList(), kkey)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                response.close()
                val tracks = mutableListOf<SubtitleTrack>()
                try {
                    val subs = JSONArray(body)
                    log("${subs.length()} subtitle tracks")
                    for (i in 0 until subs.length()) {
                        val sub = subs.getJSONObject(i)
                        val src = sub.optString("src", "")
                        if (src.isNotEmpty()) {
                            tracks.add(SubtitleTrack(
                                lang = sub.optString("land", "unknown"),
                                label = sub.optString("label", "Unknown"),
                                src = src
                            ))
                        }
                    }
                } catch (e: Exception) {
                    log("Sub error: ${e.message}", true)
                }
                runOnUiThread {
                    unlock()
                    if (tracks.isEmpty()) {
                        Toast.makeText(this@EpisodeActivity, "No subtitles found — you can play without subtitles", Toast.LENGTH_SHORT).show()
                    }
                    showDialog(ep, tracks, kkey)
                }
            }
        })
    }

    private fun unlock() {
        isGettingKey = false
        adapter.setLocked(false)
        adapter.notifyDataSetChanged()
    }

    private fun showDialog(ep: EpisodeItem, tracks: List<SubtitleTrack>, kkey: String) {
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
            background = GradientDrawable().apply { setColor(Color.rgb(18, 18, 27)); cornerRadius = dp(22).toFloat() }
        }
        root.addView(TextView(this).apply {
            text = "Episode ${ep.number}  ·  Subtitles"
            setTextColor(Color.WHITE); textSize = 19f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        })
        val hint = TextView(this).apply {
            text = if (tracks.isEmpty()) "No online subtitles found" else "Choose a language to start playback"
            setTextColor(Color.rgb(156, 163, 176)); textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(hint)
        root.addView(subtitleRow("OFF", "Play without subtitle", null) { dialog.dismiss(); openPlayer(ep, "", kkey, tracks) })
        tracks.forEach { track ->
            root.addView(subtitleRow(track.lang.uppercase(), "Play with ${track.label}", "Download") {
                dialog.dismiss(); openPlayer(ep, track.src, kkey, tracks)
            }.also { row ->
                row.findViewWithTag<TextView>("download")?.setOnClickListener { downloadSub(track, ep.number) }
            })
        }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun subtitleRow(code: String, title: String, action: String?, onPlay: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(7), 0, dp(7)) }
        val badge = TextView(this).apply { text = code; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 10f; setTypeface(null, 1); background = GradientDrawable().apply { setColor(Color.rgb(44, 48, 64)); cornerRadius = dp(8).toFloat() }; setPadding(dp(9), dp(7), dp(9), dp(7)) }
        row.addView(badge)
        row.addView(TextView(this).apply { text = title; setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(12), 0, dp(8), 0); layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setOnClickListener { onPlay() } })
        action?.let { row.addView(TextView(this).apply { tag = "download"; text = "↓  $it"; setTextColor(Color.rgb(255, 107, 0)); textSize = 11f; setPadding(dp(8), dp(8), 0, dp(8)) }) }
        return row
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun downloadSub(track: SubtitleTrack, epNum: Int) {
        val fileName = "${dramaTitle.replace(" ", "_")}_E${epNum}_${track.lang}.srt"
        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(track.src))
                .setTitle("$dramaTitle E$epNum - ${track.label}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .addRequestHeader("User-Agent", "Mozilla/5.0")
            dm.enqueue(req)
            Toast.makeText(this, "Downloading ${track.label}...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openPlayer(ep: EpisodeItem, subUrl: String, kkey: String, tracks: List<SubtitleTrack> = emptyList()) {
        val allEps = adapter.items
        val currentIndex = allEps.indexOfFirst { it.id == ep.id }.coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("dramaId", dramaId)
            putExtra("dramaTitle", dramaTitle)
            putExtra("epNumber", ep.number)
            putExtra("epId", ep.id)
            putExtra("subUrl", subUrl)
            putExtra("kkey", kkey)
            putStringArrayListExtra("subtitleLabels", ArrayList(tracks.map { it.label }))
            putStringArrayListExtra("subtitleUrls", ArrayList(tracks.map { it.src }))
            putIntegerArrayListExtra("episodeIds", ArrayList(allEps.map { it.id }))
            putIntegerArrayListExtra("episodeNumbers", ArrayList(allEps.map { it.number }))
            putExtra("currentEpIndex", currentIndex)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSkeletonPulse()
    }
}

data class EpisodeItem(val id: Int, val number: Int, val hasSub: Boolean)
