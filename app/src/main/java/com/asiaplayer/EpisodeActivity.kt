package com.asiaplayer

import android.content.Intent
import android.os.Bundle
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

        log("Loading episodes for: $dramaTitle (id=$dramaId)")
        loadEpisodes(recycler, loading)
    }

    private fun loadEpisodes(recycler: RecyclerView, loading: ProgressBar) {
        loading.visibility = View.VISIBLE
        val url = "https://kisskh.is/api/DramaList/Drama/$dramaId?isq=true"
        log("API: $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://kisskh.is/")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("Episodes FAILED: ${e.message}", true)
                runOnUiThread { loading.visibility = View.GONE }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                log("Episodes: HTTP ${response.code}, ${body.length} bytes")
                try {
                    val detail = JSONObject(body)
                    val episodes = detail.getJSONArray("episodes")
                    val items = mutableListOf<EpisodeItem>()

                    for (i in 0 until episodes.length()) {
                        val ep = episodes.getJSONObject(i)
                        val numRaw = ep.optDouble("number", (i + 1).toDouble())
                        val num = if (numRaw == numRaw.toLong().toDouble()) numRaw.toLong().toInt() else numRaw.toInt()
                        items.add(EpisodeItem(
                            id = ep.getInt("id"),
                            number = num,
                            hasSub = ep.optInt("sub", 0) > 0
                        ))
                    }
                    log("Found ${items.size} episodes")

                    runOnUiThread {
                        loading.visibility = View.GONE
                        recycler.adapter = EpisodeAdapter(items) { ep ->
                            if (!isGettingKey) getKeyAndPlay(ep)
                            else log("Already loading, please wait...", true)
                        }
                    }
                } catch (e: Exception) {
                    log("Parse error: ${e.message}", true)
                    runOnUiThread { loading.visibility = View.GONE }
                }
            }
        })
    }

    private fun getKeyAndPlay(ep: EpisodeItem) {
        isGettingKey = true
        log("Getting key for ep ${ep.number} (id=${ep.id})")

        val encodedTitle = dramaTitle.replace(" ", "-").replace("(", "").replace(")", "")
        val pageUrl = "https://kisskh.is/Drama/$encodedTitle/Episode-${ep.number}?id=$dramaId&ep=${ep.id}&page=0&pageSize=100"
        log("Loading: $pageUrl")

        var keyFound = false

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                log("SSL error (proceeding): ${error.primaryError}")
                handler.proceed()
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                log("Page loading: ${url.take(60)}")
            }

            override fun onPageFinished(view: WebView, url: String) {
                log("Page loaded: ${url.take(60)}")
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()

                // لاگ همه request های مهم
                if (url.contains("kisskh") || url.contains("kkey") || url.contains("Sub/")) {
                    log("REQ: ${url.take(80)}")
                }

                if (!keyFound && url.contains("/api/Sub/${ep.id}") && url.contains("kkey=")) {
                    val kkey = url.substringAfter("kkey=").substringBefore("&").trim()
                    if (kkey.length > 10) {
                        keyFound = true
                        log("KEY FOUND! length=${kkey.length}")
                        runOnUiThread {
                            isGettingKey = false
                            fetchSubAndPlay(ep, kkey)
                        }
                    }
                }
                return null
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    log("Page error: ${error.errorCode}: ${error.description}", true)
                }
            }
        }

        webView.loadUrl(pageUrl)

        // timeout 45 ثانیه
        handler.postDelayed({
            if (!keyFound) {
                log("TIMEOUT - key not found after 45s", true)
                log("Trying direct subtitle URL...")
                isGettingKey = false
                // بدون کلید هم تلاش کن
                openPlayer(ep, "", "")
            }
        }, 45000)
    }

    private fun fetchSubAndPlay(ep: EpisodeItem, kkey: String) {
        val subApiUrl = "https://kisskh.is/api/Sub/${ep.id}?kkey=$kkey"
        log("Fetching subs: $subApiUrl")

        val request = Request.Builder()
            .url(subApiUrl)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://kisskh.is/")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("Sub fetch FAILED: ${e.message}", true)
                runOnUiThread { openPlayer(ep, "", kkey) }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                log("Subs response: HTTP ${response.code}, ${body.length} bytes")
                var enSubUrl = ""
                try {
                    val subs = JSONArray(body)
                    log("Found ${subs.length()} subtitle tracks")
                    for (i in 0 until subs.length()) {
                        val sub = subs.getJSONObject(i)
                        val land = sub.optString("land", "")
                        val src = sub.optString("src", "")
                        log("Sub $i: land=$land, src=${src.take(50)}")
                        if (land == "en" && enSubUrl.isEmpty()) enSubUrl = src
                    }
                    if (enSubUrl.isEmpty() && subs.length() > 0) {
                        enSubUrl = subs.getJSONObject(0).optString("src", "")
                        log("Using first subtitle: ${enSubUrl.take(50)}")
                    }
                } catch (e: Exception) {
                    log("Sub parse error: ${e.message}", true)
                }
                runOnUiThread { openPlayer(ep, enSubUrl, kkey) }
            }
        })
    }

    private fun openPlayer(ep: EpisodeItem, subUrl: String, kkey: String) {
        log("Opening player: ep=${ep.number}, sub=${subUrl.isNotEmpty()}")
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
