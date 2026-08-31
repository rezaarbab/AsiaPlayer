package com.asiaplayer

import android.animation.ObjectAnimator
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
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
    private var airSchedule = ""   // e.g. "Tuesday" from API
    private var dramaStatus = ""
    private var countdownTimer: CountDownTimer? = null

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
        log("CF-bypass WebView ep: $host")

        android.webkit.CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            private var phase = 0

            override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: android.net.http.SslError) { h.proceed() }

            override fun onPageFinished(view: WebView, url: String) {
                if (wvEpDone) return
                log("WV ep page: $url (phase=$phase)")
                if (url.contains("/cdn-cgi/") || url.contains("challenge-")) return

                when {
                    phase == 0 && url.contains(host) -> {
                        phase = 1
                        val apiUrl = "https://$host/api/DramaList/Drama/$dramaId?isq=true"
                        log("Phase 1: navigating to drama API")
                        handler.postDelayed({ view.loadUrl(apiUrl) }, 800L)
                    }
                    phase == 1 -> {
                        log("Phase 2: reading body from $url")
                        view.evaluateJavascript(
                            "(function(){ return document.body.textContent || document.body.innerText; })()"
                        ) { raw ->
                            if (wvEpDone) return@evaluateJavascript
                            val json = try {
                                org.json.JSONTokener(raw).nextValue().toString()
                            } catch (_: Exception) { raw.trim().removeSurrounding("\"") }

                            val trimmed = json.trimStart()
                            log("Body: ${trimmed.take(80)}")
                            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                                wvEpDone = true
                                SourceRegistry.setHost(this@EpisodeActivity, host)
                                parseAndShowEpisodes(json, host)
                            } else if (trimmed.startsWith("<") || trimmed.contains("<!DOCTYPE")) {
                                log("Still HTML at phase 1, waiting...", true)
                            } else {
                                log("$host ep unexpected response — next", true)
                                runOnUiThread { tryNextWebViewEpHost() }
                            }
                        }
                    }
                }
            }
        }

        webView.loadUrl("https://$host/")
        handler.postDelayed({
            if (!wvEpDone) { log("$host timeout (40s)", true); runOnUiThread { tryNextWebViewEpHost() } }
        }, 40_000L)
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
            dramaStatus = status
            // Try known field names for air schedule
            airSchedule = detail.optString("airOn",
                detail.optString("scheduleOn",
                detail.optString("schedule",
                detail.optString("broadcastOn",
                detail.optString("airedOn", "")))))
            log("Drama keys: ${detail.keys().asSequence().joinToString()}")
            // Fallback: derive schedule day from latest episode's date field
            if (airSchedule.isEmpty() && episodes.length() > 0) {
                val lastEp = episodes.getJSONObject(episodes.length() - 1)
                log("Ep keys: ${lastEp.keys().asSequence().joinToString()}")
                val dateStr = lastEp.optString("date",
                    lastEp.optString("createdAt",
                    lastEp.optString("airedDate",
                    lastEp.optString("releaseDate", ""))))
                if (dateStr.isNotEmpty()) {
                    log("Last ep date: $dateStr")
                    try {
                        val fmts = listOf("MMM d, yyyy", "yyyy-MM-dd", "MM/dd/yyyy", "d MMM yyyy")
                        val date = fmts.firstNotNullOfOrNull { fmt ->
                            try { java.text.SimpleDateFormat(fmt, Locale.US).parse(dateStr) } catch (_: Exception) { null }
                        }
                        if (date != null) {
                            val cal = Calendar.getInstance().apply { time = date }
                            val days = arrayOf("", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
                            airSchedule = days[cal.get(Calendar.DAY_OF_WEEK)]
                            log("Schedule derived from ep date: $airSchedule")
                        }
                    } catch (_: Exception) {}
                }
            }

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

        handler.postDelayed({
            if (!keyFound && isGettingKey) {
                isGettingKey = false
                adapter.setLocked(false)
                adapter.notifyDataSetChanged()
                log("Stream key timeout", true)
                runOnUiThread {
                    val allEps = adapter.items
                    val isLatestEp = ep.number >= (allEps.maxOfOrNull { it.number } ?: 0)
                    if (dramaStatus.equals("Ongoing", ignoreCase = true) && isLatestEp) {
                        showCountdownDialog(ep, nextAirTimeMs(airSchedule))
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("Episode ${ep.number}")
                            .setMessage("Couldn't load this episode. The stream didn't respond.")
                            .setPositiveButton("Retry") { d, _ -> d.dismiss(); getKeyAndPlay(ep) }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        }, 15000)
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

    private fun showCountdownDialog(ep: EpisodeItem, targetMs: Long) {
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(dp(32), dp(64), dp(32), dp(64))
        }

        val schedLabel = airSchedule.trim().replaceFirstChar { it.uppercaseChar() }
        val hasSchedule = airSchedule.isNotBlank() && targetMs > 0

        root.addView(TextView(this).apply {
            text = dramaTitle
            setTextColor(Color.rgb(82, 130, 220))
            textSize = 24f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(this).apply {
            text = if (hasSchedule) "(Every $schedLabel)" else "Ongoing"
            setTextColor(Color.rgb(82, 130, 220))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(40))
        })

        if (hasSchedule) {
            fun makeCell(label: String): Pair<LinearLayout, TextView> {
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), 0) }
                val num = TextView(this).apply { text = "00"; setTextColor(Color.WHITE); textSize = 56f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
                val lbl = TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER }
                col.addView(num); col.addView(lbl)
                return col to num
            }
            fun makeSep() = TextView(this).apply { text = ":"; setTextColor(Color.WHITE); textSize = 44f; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(18)) }

            val countRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val (dayCol, dayNum) = makeCell("DAYS")
            val (hrCol, hrNum) = makeCell("HOURS")
            val (minCol, minNum) = makeCell("MINUTES")
            val (secCol, secNum) = makeCell("SECONDS")
            countRow.addView(dayCol); countRow.addView(makeSep())
            countRow.addView(hrCol); countRow.addView(makeSep())
            countRow.addView(minCol); countRow.addView(makeSep())
            countRow.addView(secCol)
            root.addView(countRow)

            countdownTimer?.cancel()
            val remaining0 = (targetMs - System.currentTimeMillis()).coerceAtLeast(0L)
            countdownTimer = object : CountDownTimer(remaining0, 1000) {
                override fun onTick(r: Long) {
                    dayNum.text = "%02d".format(r / 86400000L)
                    hrNum.text = "%02d".format(r % 86400000L / 3600000L)
                    minNum.text = "%02d".format(r % 3600000L / 60000L)
                    secNum.text = "%02d".format(r % 60000L / 1000L)
                }
                override fun onFinish() { dayNum.text = "00"; hrNum.text = "00"; minNum.text = "00"; secNum.text = "00" }
            }.start()
        } else {
            // No schedule info — show "Coming Soon" screen
            root.addView(TextView(this).apply {
                text = "🎬"
                textSize = 64f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(24))
            })
            root.addView(TextView(this).apply {
                text = "Episode ${ep.number} hasn't been\nreleased yet"
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(12))
            })
            root.addView(TextView(this).apply {
                text = "Check back when the new episode airs"
                setTextColor(Color.rgb(140, 140, 140))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(40))
            })
        }

        root.addView(TextView(this).apply {
            text = "$dramaTitle  ·  Episode ${ep.number}"
            setTextColor(Color.rgb(100, 100, 100))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(16))
        })

        // Retry button
        root.addView(TextView(this).apply {
            text = "Retry"
            setTextColor(Color.rgb(255, 107, 0))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(12), dp(32), dp(12))
            background = GradientDrawable().apply { setColor(Color.rgb(40, 40, 40)); cornerRadius = dp(24).toFloat() }
            setOnClickListener { dialog.dismiss(); getKeyAndPlay(ep) }
        })

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.black)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnDismissListener { countdownTimer?.cancel() }
        dialog.show()
    }

    private fun nextAirTimeMs(airOn: String): Long {
        if (airOn.isBlank()) return -1L
        val day = airOn.trim().lowercase()
        val targetDay = when {
            day.contains("sun") -> Calendar.SUNDAY
            day.contains("mon") -> Calendar.MONDAY
            day.contains("tue") -> Calendar.TUESDAY
            day.contains("wed") -> Calendar.WEDNESDAY
            day.contains("thu") -> Calendar.THURSDAY
            day.contains("fri") -> Calendar.FRIDAY
            day.contains("sat") -> Calendar.SATURDAY
            else -> return -1L
        }
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_WEEK)
        var daysUntil = (targetDay - today + 7) % 7
        if (daysUntil == 0) daysUntil = 7
        cal.add(Calendar.DAY_OF_YEAR, daysUntil)
        cal.set(Calendar.HOUR_OF_DAY, 21)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSkeletonPulse()
        countdownTimer?.cancel()
    }
}

data class EpisodeItem(val id: Int, val number: Int, val hasSub: Boolean)
