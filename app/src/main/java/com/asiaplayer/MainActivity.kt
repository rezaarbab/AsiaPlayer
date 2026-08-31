package com.asiaplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
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
    private lateinit var emptyState: View
    private lateinit var errorState: View
    private lateinit var errorMessage: TextView
    private lateinit var debugText: TextView
    private lateinit var debugScroll: ScrollView
    private lateinit var debugToggle: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var lastQuery = ""
    private var screenActive = true

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

    private fun log(msg: String, isError: Boolean = false) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] ${if (isError) "X" else "OK"} $msg\n"
        handler.post {
            if (!screenActive || isFinishing || isDestroyed) return@post
            debugText.append(line)
            debugScroll.post { debugScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        screenActive = false
        client.dispatcher.cancelAll()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.hiddenWebView)
        searchInput = findViewById(R.id.searchInput)
        statusDot = findViewById(R.id.statusDot)
        loadingBar = findViewById(R.id.loadingBar)
        recyclerView = findViewById(R.id.recyclerView)
        emptyState = findViewById(R.id.emptyState)
        errorState = findViewById(R.id.errorState)
        errorMessage = findViewById(R.id.errorMessage)
        debugText = findViewById(R.id.debugText)
        debugScroll = findViewById(R.id.debugScroll)
        debugToggle = findViewById(R.id.debugToggle)

        findViewById<TextView>(R.id.sourceButton).setOnClickListener { showSourcePicker() }

        recyclerView.layoutManager = GridLayoutManager(this, 2)
        WebViewHelper.setup(webView)

        log("App started")
        testConnection()
        checkKillSwitch()

        findViewById<TextView>(R.id.searchBtn).setOnClickListener {
            val q = searchInput.text.toString().trim()
            if (q.isNotEmpty()) {
                hideKeyboard()
                searchDrama(q)
            }
        }
        searchInput.setOnEditorActionListener { _, _, _ ->
            val q = searchInput.text.toString().trim()
            if (q.isNotEmpty()) {
                hideKeyboard()
                searchDrama(q)
            }
            true
        }

        findViewById<TextView>(R.id.retryBtn).setOnClickListener {
            if (lastQuery.isNotEmpty()) searchDrama(lastQuery)
        }

        debugToggle.setOnClickListener {
            val open = debugScroll.visibility == View.VISIBLE
            debugScroll.visibility = if (open) View.GONE else View.VISIBLE
            debugToggle.text = if (open) "\u25B6  DEBUG LOG" else "\u25BC  DEBUG LOG"
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun showSourcePicker() {
        val hosts = SourceRegistry.all.flatMap { source -> source.hosts.map { "${source.label}  ·  $it" to it } }
        val selected = SourceRegistry.host(this)
        AlertDialog.Builder(this)
            .setTitle("Select source")
            .setSingleChoiceItems(hosts.map { it.first }.toTypedArray(), hosts.indexOfFirst { it.second == selected }) { dialog, which ->
                SourceRegistry.setHost(this, hosts[which].second)
                findViewById<TextView>(R.id.sourceButton).text = hosts[which].second.removePrefix("www.").uppercase()
                log("Source selected: ${hosts[which].second}")
                dialog.dismiss()
            }.show()
    }

    private fun showState(state: String) {
        loadingBar.visibility = if (state == "loading") View.VISIBLE else View.GONE
        recyclerView.visibility = if (state == "list") View.VISIBLE else View.GONE
        emptyState.visibility = if (state == "empty") View.VISIBLE else View.GONE
        errorState.visibility = if (state == "error") View.VISIBLE else View.GONE
    }

    private fun testConnection() {
        val req = Request.Builder().url("https://${SourceRegistry.host(this)}/api/DramaList/Search?q=test&type=0")
            .header("User-Agent", "Mozilla/5.0").build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("Connection FAILED: ${e.javaClass.simpleName}", true)
            }
            override fun onResponse(call: Call, response: Response) {
                log("Connection OK: HTTP ${response.code}")
                response.close()
                runOnUiThread {
                    if (screenActive && !isFinishing && !isDestroyed) statusDot.alpha = 1f
                }
            }
        })
    }

    private fun checkKillSwitch() {
        val req = Request.Builder().url(KILL_SWITCH_URL).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: return
                response.close()
                try {
                    val json = JSONObject(body)
                    if (!json.optBoolean("active", true)) {
                        runOnUiThread {
                            if (!screenActive || isFinishing || isDestroyed) return@runOnUiThread
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
        lastQuery = query
        showState("loading")
        log("Searching: $query")

        val url = "https://${SourceRegistry.host(this)}/api/DramaList/Search?q=${Uri.encode(query)}&type=0"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://kisskh.is/")
            .header("Accept", "application/json").build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("FAILED: ${e.javaClass.simpleName}: ${e.message}", true)
                runOnUiThread {
                    showState("error")
                    errorMessage.text = "Network error. Check your connection."
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val statusCode = response.code
                val body = response.body?.string() ?: ""
                response.close()
                log("HTTP $statusCode, ${body.length}B")
                if (statusCode !in 200..299) {
                    runOnUiThread {
                        if (!screenActive || isFinishing || isDestroyed) return@runOnUiThread
                        showState("error")
                        errorMessage.text = "Server error (HTTP $statusCode)"
                    }
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
                        if (!screenActive || isFinishing || isDestroyed) return@runOnUiThread
                        if (items.isEmpty()) {
                            showState("empty")
                            emptyState.findViewById<TextView>(R.id.emptyTitle)?.text = "No results found"
                            emptyState.findViewById<TextView>(R.id.emptySubtitle)?.text = "Try a different title"
                        } else {
                            showState("list")
                            recyclerView.adapter = DramaAdapter(items) { drama ->
                                if (drama.id > 0 && drama.title.isNotEmpty()) {
                                    log("Selected: ${drama.title}")
                                    startActivity(Intent(this@MainActivity, EpisodeActivity::class.java).apply {
                                        putExtra("dramaId", drama.id)
                                        putExtra("dramaTitle", drama.title)
                                    })
                                } else {
                                    log("Blocked click on invalid item", true)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    log("Parse error: ${e.message}", true)
                    runOnUiThread {
                        showState("error")
                        errorMessage.text = "Couldn't read server response"
                    }
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
