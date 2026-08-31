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
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var searchInput: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var statusDot: View
    private lateinit var loadingBar: ProgressBar
    private lateinit var debugText: TextView
    private lateinit var debugScroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())

    private val customDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                when {
                    hostname.contains("kisskh") -> listOf(
                        InetAddress.getByName("172.67.167.97"),
                        InetAddress.getByName("104.21.83.142")
                    )
                    else -> okhttp3.Dns.SYSTEM.lookup(hostname)
                }
            } catch (e: Exception) {
                okhttp3.Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(customDns)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val KILL_SWITCH_URL = "https://asiaplayer-control.your-worker.workers.dev/status"
    private val KISSKH_SEARCH = "https://kisskh.is/api/DramaList/Search?q="

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
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.hiddenWebView)
        searchInput = findViewById(R.id.searchInput)
        statusDot = findViewById(R.id.statusDot)
        loadingBar = findViewById(R.id.loadingBar)
        recyclerView = findViewById(R.id.recyclerView)
        debugText = findViewById(R.id.debugText)
        debugScroll = findViewById(R.id.debugScroll)

        recyclerView.layoutManager = LinearLayoutManager(this)
        WebViewHelper.setup(webView)

        log("App started")
        testConnection()
        checkKillSwitch()

        findViewById<Button>(R.id.searchBtn).setOnClickListener {
            val q = searchInput.text.toString().trim()
            if (q.isNotEmpty()) searchDrama(q)
        }
        searchInput.setOnEditorActionListener { _, _, _ ->
            val q = searchInput.text.toString().trim()
            if (q.isNotEmpty()) searchDrama(q)
            true
        }
    }

    private fun testConnection() {
        val req = Request.Builder().url("https://kisskh.is/api/DramaList/Search?q=test&type=0")
            .header("User-Agent", "Mozilla/5.0").build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("Connection FAILED: ${e.javaClass.simpleName}", true)
            }
            override fun onResponse(call: Call, response: Response) {
                log("Connection OK: HTTP ${response.code}")
                runOnUiThread { statusDot.setBackgroundColor(0xFF00FF88.toInt()) }
                response.close()
            }
        })
    }

    private fun checkKillSwitch() {
        val req = Request.Builder().url(KILL_SWITCH_URL).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                try {
                    val json = JSONObject(body)
                    if (!json.optBoolean("active", true)) {
                        runOnUiThread {
                            statusDot.setBackgroundColor(0xFFFF4444.toInt())
                            setContentView(R.layout.activity_update)
                            findViewById<TextView>(R.id.updateMessage)?.text =
                                json.optString("message", "Update required")
                        }
                    }
                } catch (e: Exception) {}
            }
        })
    }

    private fun searchDrama(query: String) {
        loadingBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        log("Searching: $query")

        val url = "$KISSKH_SEARCH${query.replace(" ", "+")}&type=0"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://kisskh.is/")
            .header("Accept", "application/json").build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("FAILED: ${e.javaClass.simpleName}: ${e.message}", true)
                runOnUiThread {
                    loadingBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                log("HTTP ${response.code}, ${body.length}B")
                if (!response.isSuccessful) {
                    runOnUiThread { loadingBar.visibility = View.GONE }
                    return
                }
                try {
                    val arr = JSONArray(body)
                    log("${arr.length()} results")
                    val items = mutableListOf<DramaItem>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        items.add(DramaItem(
                            id = obj.getInt("id"),
                            title = obj.getString("title"),
                            episodeCount = obj.optInt("episodesCount", 0),
                            status = obj.optString("status", ""),
                            thumbnailUrl = obj.optString("thumbnail", "")
                        ))
                    }
                    runOnUiThread {
                        loadingBar.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                        recyclerView.adapter = DramaAdapter(items) { drama ->
                            log("Selected: ${drama.title}")
                            startActivity(Intent(this@MainActivity, EpisodeActivity::class.java).apply {
                                putExtra("dramaId", drama.id)
                                putExtra("dramaTitle", drama.title)
                            })
                        }
                    }
                } catch (e: Exception) {
                    log("Parse error: ${e.message}", true)
                    runOnUiThread { loadingBar.visibility = View.GONE }
                }
            }
        })
    }
}

data class DramaItem(
    val id: Int,
    val title: String,
    val episodeCount: Int,
    val status: String,
    val thumbnailUrl: String = ""
)
