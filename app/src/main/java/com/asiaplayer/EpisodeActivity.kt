package com.asiaplayer

import android.content.Intent
import android.os.Bundle
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

class EpisodeActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val client = OkHttpClient()
    private var dramaId = 0
    private var dramaTitle = ""
    private var isGettingKey = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dramaId = intent.getIntExtra("dramaId", 0)
        dramaTitle = intent.getStringExtra("dramaTitle") ?: ""

        setContentView(R.layout.activity_episodes)

        val titleView = findViewById<TextView>(R.id.episodeDramaTitle)
        val recycler = findViewById<RecyclerView>(R.id.episodeRecycler)
        val loading = findViewById<ProgressBar>(R.id.episodeLoading)

        titleView.text = dramaTitle
        recycler.layoutManager = LinearLayoutManager(this)

        // Hidden WebView کامل
        webView = findViewById(R.id.episodeWebView)
        WebViewHelper.setup(webView)

        loadEpisodes(recycler, loading)
    }

    private fun loadEpisodes(recycler: RecyclerView, loading: ProgressBar) {
        loading.visibility = View.VISIBLE
        val url = "https://kisskh.is/api/DramaList/Drama/$dramaId?isq=true"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://kisskh.is/")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    loading.visibility = View.GONE
                    Toast.makeText(this@EpisodeActivity, "Failed to load episodes", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
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

                    runOnUiThread {
                        loading.visibility = View.GONE
                        recycler.adapter = EpisodeAdapter(items) { ep ->
                            if (!isGettingKey) getKeyAndPlay(ep)
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        loading.visibility = View.GONE
                        Toast.makeText(this@EpisodeActivity, "Error loading episodes", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun getKeyAndPlay(ep: EpisodeItem) {
        isGettingKey = true
        Toast.makeText(this, "Loading episode ${ep.number}...", Toast.LENGTH_SHORT).show()

        val pageUrl = "https://kisskh.is/Drama/${dramaTitle.replace(" ", "-")}/Episode-${ep.number}?id=$dramaId&ep=${ep.id}&page=0&pageSize=100"

        // Intercept برای گرفتن kkey
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.proceed()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()

                // گرفتن kkey از subtitle request
                if (url.contains("/api/Sub/${ep.id}") && url.contains("kkey=")) {
                    val kkey = url.substringAfter("kkey=").substringBefore("&").trim()
                    if (kkey.length > 10) {
                        runOnUiThread {
                            isGettingKey = false
                            fetchSubAndPlay(ep, kkey)
                        }
                    }
                }

                // گرفتن m3u8
                if (url.contains(".m3u8") && url.contains("cdnvideo")) {
                    runOnUiThread {
                        // ذخیره برای PlayerActivity
                    }
                }

                return null
            }
        }

        webView.loadUrl(pageUrl)
    }

    private fun fetchSubAndPlay(ep: EpisodeItem, kkey: String) {
        val subApiUrl = "https://kisskh.is/api/Sub/${ep.id}?kkey=$kkey"
        val request = Request.Builder()
            .url(subApiUrl)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://kisskh.is/")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { openPlayer(ep, "", kkey) }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                var enSubUrl = ""
                try {
                    val subs = JSONArray(body)
                    for (i in 0 until subs.length()) {
                        val sub = subs.getJSONObject(i)
                        val land = sub.optString("land", "")
                        if (land == "en") {
                            enSubUrl = sub.optString("src", "")
                            break
                        }
                    }
                    // اگه انگلیسی نبود اولی رو بگیر
                    if (enSubUrl.isEmpty() && subs.length() > 0) {
                        enSubUrl = subs.getJSONObject(0).optString("src", "")
                    }
                } catch (e: Exception) {}

                runOnUiThread { openPlayer(ep, enSubUrl, kkey) }
            }
        })
    }

    private fun openPlayer(ep: EpisodeItem, subUrl: String, kkey: String) {
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
