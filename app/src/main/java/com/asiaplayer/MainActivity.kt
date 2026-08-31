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
        val customHosts = SourceRegistry.getCustomHosts(this)
        val builtIn = SourceRegistry.all.flatMap { source -> source.hosts.map { "${source.label}  ·  $it" to it } }
        val customEntries = customHosts.map { "Custom  ·  $it" to it }
        val allEntries = customEntries + builtIn + listOf("＋  Add custom domain…" to "__add__")
        val selected = SourceRegistry.host(this)

        AlertDialog.Builder(this)
            .setTitle("Select source")
            .setSingleChoiceItems(allEntries.map { it.first }.toTypedArray(),
                allEntries.indexOfFirst { it.second == selected }.coerceAtLeast(0)) { dialog, which ->
                val host = allEntries[which].second
                if (host == "__add__") {
                    dialog.dismiss()
                    showAddDomainDialog()
                } else {
                    SourceRegistry.setHost(this, host)
                    findViewById<TextView>(R.id.sourceButton).text = host.removePrefix("www.").uppercase()
                    log("Source selected: $host")
                    dialog.dismiss()
                }
            }.show()
    }

    private fun showAddDomainDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. kisskh.co or myasiantv.ac"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Add custom domain")
            .setMessage("Enter the domain (without https://). It will be tried first on every search.")
            .setView(input)
            .setPositiveButton("Add & use") { _, _ ->
                val domain = input.text.toString().trim()
                    .removePrefix("https://").removePrefix("http://").trimEnd('/')
                if (domain.isNotEmpty() && domain.contains(".")) {
                    SourceRegistry.addCustomHost(this, domain)
                    SourceRegistry.setHost(this, domain)
                    findViewById<TextView>(R.id.sourceButton).text = domain.removePrefix("www.").uppercase()
                    log("Custom domain added: $domain")
                    if (lastQuery.isNotEmpty()) searchDrama(lastQuery)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showState(state: String) {
        loadingBar.visibility = if (state == "loading") View.VISIBLE else View.GONE
        recyclerView.visibility = if (state == "list") View.VISIBLE else View.GONE
        emptyState.visibility = if (state == "empty") View.VISIBLE else View.GONE
        errorState.visibility = if (state == "error") View.VISIBLE else View.GONE
    }

    private fun testConnection() {
        val host = SourceRegistry.host(this)
        val req = Request.Builder()
            .url("https://$host/api/DramaList/Search?q=test&type=0")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            .header("Referer", "https://$host/")
            .header("Accept", "application/json, */*")
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("Connection FAILED: ${e.javaClass.simpleName}", true)
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""; response.close()
                val ok = response.code in 200..299 && !body.trimStart().startsWith("<!") && !body.trimStart().startsWith("<html")
                log("$host ${if (ok) "OK" else "HTML/error"}: HTTP ${response.code}")
                runOnUiThread {
                    if (screenActive && !isFinishing && !isDestroyed) statusDot.alpha = if (ok) 1f else 0.3f
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

        val selectedHost = SourceRegistry.host(this)
        val family = SourceRegistry.all.find { s -> s.hosts.contains(selectedHost) } ?: SourceRegistry.all[0]
        val customHosts = SourceRegistry.getCustomHosts(this)
        // Custom domains first, then selected host, then rest of family
        val candidates = (customHosts + listOf(selectedHost) + family.hosts).distinct()

        trySearchOnHosts(query, candidates, 0)
    }

    private fun trySearchOnHosts(query: String, hosts: List<String>, index: Int) {
        if (index >= hosts.size) {
            // All direct requests failed — fall back to WebView (bypasses Cloudflare JS challenge)
            log("Direct requests failed — trying WebView fallback", true)
            val selectedHost = SourceRegistry.host(this)
            val family = SourceRegistry.all.find { s -> s.hosts.contains(selectedHost) } ?: SourceRegistry.all[0]
            runOnUiThread {
                if (!screenActive || isFinishing || isDestroyed) return@runOnUiThread
                searchViaWebView(query, family.hosts[0], family.hosts)
            }
            return
        }

        val host = hosts[index]
        val url = "https://$host/api/DramaList/Search?q=${Uri.encode(query)}&type=0"
        log("Trying $host")

        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            .header("Referer", "https://$host/")
            .header("Accept", "application/json, text/plain, */*")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                log("$host failed: ${e.javaClass.simpleName}", true)
                trySearchOnHosts(query, hosts, index + 1)
            }
            override fun onResponse(call: Call, response: Response) {
                val statusCode = response.code
                val body = response.body?.string() ?: ""
                response.close()
                log("$host HTTP $statusCode, ${body.length}B")

                val bodyTrimmed = body.trimStart()
                if (statusCode !in 200..299 || bodyTrimmed.startsWith("<!") || bodyTrimmed.startsWith("<html")) {
                    log("$host returned HTML — skipping", true)
                    trySearchOnHosts(query, hosts, index + 1)
                    return
                }

                try {
                    val arr = JSONArray(body)
                    log("${arr.length()} results on $host")
                    SourceRegistry.setHost(this@MainActivity, host)
                    runOnUiThread {
                        if (!screenActive || isFinishing || isDestroyed) return@runOnUiThread
                        findViewById<TextView>(R.id.sourceButton)?.text = host.removePrefix("www.").uppercase()
                    }
                    showResults(query, arr)
                } catch (e: Exception) {
                    log("$host parse error: ${e.message} — skipping", true)
                    trySearchOnHosts(query, hosts, index + 1)
                }
            }
        })
    }

    // ── WebView fallback (Cloudflare bypass) ────────────────────────────────
    // Strategy:
    //  1. Load the site homepage — WebView executes CF JS challenge automatically
    //  2. onPageFinished fires for every redirect; we wait for the REAL page
    //     (URL matches our host and has no /cdn-cgi/ path)
    //  3. After an extra 4-second delay (CF async checks), inject fetch()
    //  4. shouldInterceptRequest also captures any JSON API call the page makes

    private var wvSearchQuery = ""
    private var wvSearchHosts = listOf<String>()
    private var wvSearchHostIndex = 0
    private var wvSearchDone = false
    private var wvCurrentHost = ""
    private var wvFetchPosted = false

    private fun searchViaWebView(query: String, host: String, allHosts: List<String>) {
        wvSearchQuery = query
        wvSearchHosts = allHosts
        wvSearchHostIndex = allHosts.indexOf(host).coerceAtLeast(0)
        wvSearchDone = false
        wvCurrentHost = host
        wvFetchPosted = false
        log("WebView CF-bypass on $host")

        // Remove old bridge and re-add to avoid duplicate callbacks
        try { webView.removeJavascriptInterface("AsiaPlayerBridge") } catch (_: Exception) {}
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onResult(json: String) {
                if (wvSearchDone) return
                val trimmed = json.trimStart()
                if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                    wvSearchDone = true
                    try {
                        val arr = if (trimmed.startsWith("[")) JSONArray(json)
                                  else JSONArray().also { it.put(JSONObject(json)) }
                        log("WebView ✓ ${arr.length()} results on $host")
                        SourceRegistry.setHost(this@MainActivity, host)
                        runOnUiThread {
                            if (!screenActive || isFinishing || isDestroyed) return@runOnUiThread
                            findViewById<TextView>(R.id.sourceButton)?.text = host.removePrefix("www.").uppercase()
                        }
                        showResults(query, arr)
                    } catch (e: Exception) {
                        log("WebView parse fail: ${e.message}", true)
                        runOnUiThread { tryNextWebViewHost() }
                    }
                } else {
                    log("WebView $host non-JSON: ${json.take(60)}", true)
                    runOnUiThread { tryNextWebViewHost() }
                }
            }
        }, "AsiaPlayerBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: android.net.http.SslError) { h.proceed() }

            override fun onPageFinished(view: WebView, url: String) {
                if (wvSearchDone || wvFetchPosted) return
                // Skip CF challenge intermediate pages
                if (url.contains("/cdn-cgi/") || url.contains("challenge") || !url.contains(host)) return
                // Real page loaded — wait for CF async validation to finish, then inject
                wvFetchPosted = true
                log("WebView page ready ($url) — injecting in 5s")
                handler.postDelayed({
                    if (wvSearchDone) return@postDelayed
                    injectFetch(view, query)
                }, 5_000L)
            }

            override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                // Capture if the page itself makes the Search API call
                if (!wvSearchDone && url.contains("/api/DramaList/Search")) {
                    try {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        request.requestHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                        conn.setRequestProperty("Accept", "application/json")
                        conn.connect()
                        val body = conn.inputStream.bufferedReader().readText()
                        val trimmed = body.trimStart()
                        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                            wvSearchDone = true
                            log("shouldIntercept captured JSON ${body.length}B")
                            val arr = if (trimmed.startsWith("[")) JSONArray(body) else JSONArray().also { it.put(JSONObject(body)) }
                            SourceRegistry.setHost(this@MainActivity, host)
                            showResults(query, arr)
                        }
                    } catch (_: Exception) {}
                }
                return null
            }
        }
        webView.loadUrl("https://$host/")

        // Per-host timeout: 35s (CF challenge + 5s delay + fetch time)
        handler.postDelayed({
            if (!wvSearchDone) {
                log("WebView $host timeout (35s)", true)
                runOnUiThread { tryNextWebViewHost() }
            }
        }, 35_000L)
    }

    private fun injectFetch(view: WebView, query: String) {
        val encodedQ = Uri.encode(query)
        val js = """
            (function(){
                fetch('/api/DramaList/Search?q=$encodedQ&type=0',{
                    credentials:'include',
                    headers:{'Accept':'application/json','X-Requested-With':'XMLHttpRequest'}
                })
                .then(function(r){return r.text();})
                .then(function(t){
                    if(typeof AsiaPlayerBridge!=='undefined') AsiaPlayerBridge.onResult(t);
                })
                .catch(function(e){
                    if(typeof AsiaPlayerBridge!=='undefined') AsiaPlayerBridge.onResult('ERR:'+e.message);
                });
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
        log("fetch() injected for $wvCurrentHost")
    }

    private fun tryNextWebViewHost() {
        if (wvSearchDone) return
        wvSearchHostIndex++
        // Only try first 3 WebView hosts to avoid 3-minute waits
        if (wvSearchHostIndex >= minOf(wvSearchHosts.size, 3)) {
            log("WebView fallback exhausted", true)
            if (!screenActive || isFinishing || isDestroyed) return
            showState("error")
            errorMessage.text = "Couldn't reach any source.\nTap SOURCE to add a custom domain."
            return
        }
        val nextHost = wvSearchHosts[wvSearchHostIndex]
        searchViaWebView(wvSearchQuery, nextHost, wvSearchHosts)
    }

    private fun showResults(query: String, arr: JSONArray) {
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
                    }
                }
            }
        }
    }
}

data class DramaItem(
    val id: Int,
    val title: String,
    val episodeCount: Int,
    val status: String,
    val thumbnailUrl: String = ""
)
