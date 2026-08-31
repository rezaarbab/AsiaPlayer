package com.asiaplayer

import android.animation.ObjectAnimator
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import okhttp3.*
import java.io.IOException

class PlayerActivity : AppCompatActivity(), GestureOverlayView.Listener {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var playerView: PlayerView
    private lateinit var gestureOverlay: GestureOverlayView
    private lateinit var controlsContainer: View
    private lateinit var lockOverlay: View
    private lateinit var errorState: View
    private lateinit var tvTitle: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var tvGestureFeedback: TextView
    private lateinit var tvHudChip: TextView
    private lateinit var tvSleepTimer: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var bufferingIndicator: ProgressBar
    private lateinit var subtitleView: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnPrevEp: TextView
    private lateinit var btnNextEp: TextView
    private lateinit var brightnessIndicator: View
    private lateinit var brightnessFill: View
    private lateinit var brightnessValue: TextView
    private lateinit var volumeIndicator: View
    private lateinit var volumeFill: View
    private lateinit var volumeValue: TextView
    private lateinit var flashLeft: View
    private lateinit var flashRight: View

    // ── Player / network ──────────────────────────────────────────────────────
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient()

    // ── State ─────────────────────────────────────────────────────────────────
    private var videoFound = false
    private var isLocked = false
    private var controlsVisible = true
    private var playbackSpeed = 1f
    private var zoomScale = 1f
    private var seeking = false
    private var positionRestored = false

    private var gestureStartPosition = -1L
    private var gestureTargetPosition = -1L
    private var gestureAccumulatedDeltaX = 0f

    // ── Subtitles ─────────────────────────────────────────────────────────────
    private val subtitles = mutableListOf<SubtitleEntry>()
    private var subtitleRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null
    private val subtitleLabels = mutableListOf("Off")
    private val subtitleUrls = mutableListOf("")
    private val PICK_SUB = 7001

    // ── Intent extras ────────────────────────────────────────────────────────
    private var dramaId = 0
    private var epId = 0
    private var epNumber = 0
    private var dramaTitle = ""
    private var subUrl = ""
    private var kkey = ""
    private var episodeIds = listOf<Int>()
    private var episodeNumbers = listOf<Int>()
    private var currentEpIndex = 0

    // ── Sleep timer ───────────────────────────────────────────────────────────
    private var sleepTimer: CountDownTimer? = null

    // ── Constants ────────────────────────────────────────────────────────────
    private val CONTROLS_HIDE_MS = 3_000L
    private val PREFS_NAME = "PlayerPrefs"

    private val hideFeedbackRunnable = Runnable { tvGestureFeedback.visibility = View.GONE }
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val hideIndicatorRunnable = Runnable {
        brightnessIndicator.visibility = View.GONE
        volumeIndicator.visibility = View.GONE
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        readIntent(intent)
        bindViews()
        setupControls()
        gestureOverlay.listener = this

        if (subUrl.isNotEmpty()) loadSubtitle(subUrl)
        startPlayback()
    }

    private fun readIntent(i: Intent) {
        dramaId = i.getIntExtra("dramaId", 0)
        epId = i.getIntExtra("epId", 0)
        epNumber = i.getIntExtra("epNumber", 0)
        dramaTitle = i.getStringExtra("dramaTitle") ?: ""
        subUrl = i.getStringExtra("subUrl") ?: ""
        kkey = i.getStringExtra("kkey") ?: ""
        i.getStringArrayListExtra("subtitleLabels")?.let {
            subtitleLabels.clear(); subtitleLabels.add("Off"); subtitleLabels.addAll(it)
        }
        i.getStringArrayListExtra("subtitleUrls")?.let {
            subtitleUrls.clear(); subtitleUrls.add(""); subtitleUrls.addAll(it)
        }
        episodeIds = i.getIntegerArrayListExtra("episodeIds") ?: emptyList()
        episodeNumbers = i.getIntegerArrayListExtra("episodeNumbers") ?: emptyList()
        currentEpIndex = i.getIntExtra("currentEpIndex", 0)
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        if (player?.playbackState == Player.STATE_READY) player?.play()
    }

    override fun onPause() {
        super.onPause()
        savePosition()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) {
            player?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        savePosition()
        sleepTimer?.cancel()
        handler.removeCallbacksAndMessages(null)
        mediaSession?.release()
        mediaSession = null
        player?.release()
        webView?.destroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean) {
        super.onPictureInPictureModeChanged(inPip)
        controlsContainer.visibility = if (inPip) View.GONE else View.VISIBLE
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        gestureOverlay = findViewById(R.id.gestureOverlay)
        controlsContainer = findViewById(R.id.controlsContainer)
        lockOverlay = findViewById(R.id.lockOverlay)
        errorState = findViewById(R.id.errorState)
        tvTitle = findViewById(R.id.tvVideoTitle)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        tvGestureFeedback = findViewById(R.id.tvGestureFeedback)
        tvHudChip = findViewById(R.id.tvHudChip)
        tvSleepTimer = findViewById(R.id.tvSleepTimer)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        bufferingIndicator = findViewById(R.id.bufferingIndicator)
        subtitleView = findViewById(R.id.subtitleView)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnPrevEp = findViewById(R.id.btnPrevEp)
        btnNextEp = findViewById(R.id.btnNextEp)
        brightnessIndicator = findViewById(R.id.brightnessIndicator)
        brightnessFill = findViewById(R.id.brightnessFill)
        brightnessValue = findViewById(R.id.brightnessValue)
        volumeIndicator = findViewById(R.id.volumeIndicator)
        volumeFill = findViewById(R.id.volumeFill)
        volumeValue = findViewById(R.id.volumeValue)
        flashLeft = findViewById(R.id.flashLeft)
        flashRight = findViewById(R.id.flashRight)

        updateTitleAndNav()
    }

    private fun updateTitleAndNav() {
        tvTitle.text = if (epNumber > 0) "$dramaTitle  ·  EP $epNumber" else dramaTitle
        val hasPrev = currentEpIndex > 0
        val hasNext = currentEpIndex < episodeIds.size - 1
        btnPrevEp.visibility = if (hasPrev) View.VISIBLE else View.GONE
        btnNextEp.visibility = if (hasNext) View.VISIBLE else View.GONE
    }

    private fun setupControls() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnPlayPause.setOnClickListener { togglePlayPause() }
        findViewById<View>(R.id.btnRewind).setOnClickListener { seekRelative(-10_000L); showFeedback("-10s") }
        findViewById<View>(R.id.btnForward).setOnClickListener { seekRelative(10_000L); showFeedback("+10s") }
        btnSpeed.setOnClickListener { showSpeedMenu() }
        findViewById<View>(R.id.btnLock).setOnClickListener { setLocked(true) }
        findViewById<View>(R.id.btnUnlock).setOnClickListener { setLocked(false) }
        findViewById<View>(R.id.btnSubtitle).setOnClickListener { showSubtitleMenu() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMoreMenu() }
        findViewById<View>(R.id.btnPip).setOnClickListener { enterPip() }
        btnPrevEp.setOnClickListener { navigateEpisode(currentEpIndex - 1) }
        btnNextEp.setOnClickListener { navigateEpisode(currentEpIndex + 1) }
        findViewById<View>(R.id.btnRetry).setOnClickListener {
            errorState.visibility = View.GONE
            startPlayback()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = player?.duration ?: 0L
                    tvCurrentTime.text = formatTime(dur * progress / 1000)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                seeking = true; cancelAutoHide()
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                val dur = player?.duration ?: 0L
                player?.seekTo(dur * sb.progress / 1000)
                seeking = false; scheduleAutoHide()
            }
        })
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private fun startPlayback() {
        videoFound = false
        positionRestored = false
        bufferingIndicator.visibility = View.VISIBLE
        errorState.visibility = View.GONE
        setupWebViewForVideo()
    }

    private fun setupWebViewForVideo() {
        player?.release(); player = null
        webView?.destroy()
        val wv = WebView(this).also { webView = it }
        WebViewHelper.setup(wv)

        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.proceed()
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (!videoFound && url.contains(".m3u8")) {
                    videoFound = true
                    runOnUiThread { setupPlayer(url) }
                }
                return null
            }
        }

        val host = SourceRegistry.host(this)
        val encoded = dramaTitle.replace(" ", "-").replace("(", "").replace(")", "")
        wv.loadUrl("https://$host/Drama/$encoded/Episode-$epNumber?id=$dramaId&ep=$epId&page=0&pageSize=100")

        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = Runnable {
            if (!videoFound) runOnUiThread { showError("Stream didn't load.\nTry again or choose another source.") }
        }
        handler.postDelayed(timeoutRunnable!!, 30_000L)
    }

    private fun setupPlayer(videoUrl: String) {
        timeoutRunnable?.let { handler.removeCallbacks(it) }

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
            .setDefaultRequestProperties(mapOf("Referer" to "https://${SourceRegistry.host(this)}/"))

        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(videoUrl))

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            // MediaSession — enables lockscreen / notification controls
            mediaSession?.release()
            mediaSession = MediaSession.Builder(this, exo)
                .setId("AsiaPlayerSession")
                .build()
            // setMediaSource carries the HLS source; metadata is set separately via MediaItem wrapping
            exo.setMediaSource(mediaSource)
            exo.prepare()
            exo.setPlaybackSpeed(playbackSpeed)
            exo.play()
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    bufferingIndicator.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_READY && !positionRestored) {
                        positionRestored = true
                        val saved = loadSavedPosition()
                        if (saved > 0L) {
                            exo.seekTo(saved)
                            showFeedback("Resumed from ${formatTime(saved)}")
                        }
                    }
                    if (state == Player.STATE_ENDED && currentEpIndex < episodeIds.size - 1) {
                        navigateEpisode(currentEpIndex + 1)
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    btnPlayPause.setImageResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    if (isPlaying) scheduleAutoHide()
                }
                override fun onPlayerError(error: PlaybackException) {
                    runOnUiThread { showError("Playback error: ${error.errorCodeName}") }
                }
            })
        }

        handler.post(progressRunnable)
        startSubtitleSync()
        setControlsVisible(true)
        scheduleAutoHide()
    }

    private fun togglePlayPause() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
        scheduleAutoHide()
    }

    private fun seekRelative(deltaMs: Long) {
        val pos = player?.currentPosition ?: 0L
        val dur = player?.duration ?: 0L
        if (dur > 0) player?.seekTo((pos + deltaMs).coerceIn(0L, dur))
        scheduleAutoHide()
    }

    // ── Episode navigation ────────────────────────────────────────────────────

    private fun navigateEpisode(index: Int) {
        if (index < 0 || index >= episodeIds.size) return
        savePosition()
        currentEpIndex = index
        epId = episodeIds[index]
        epNumber = episodeNumbers.getOrElse(index) { index + 1 }
        subtitles.clear()
        subtitleView.text = ""
        subtitleLabels.clear(); subtitleLabels.add("Off")
        subtitleUrls.clear(); subtitleUrls.add("")
        updateTitleAndNav()
        handler.removeCallbacks(progressRunnable)
        subtitleRunnable?.let { handler.removeCallbacks(it) }
        startPlayback()
    }

    // ── PiP ───────────────────────────────────────────────────────────────────

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } catch (_: Exception) { }
    }

    // ── Position persistence ──────────────────────────────────────────────────

    private fun savePosition() {
        val pos = player?.currentPosition ?: return
        val dur = player?.duration ?: 0L
        val remaining = dur - pos
        if (pos > 5_000L && remaining > 10_000L) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong("pos_$epId", pos).apply()
        } else {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove("pos_$epId").apply()
        }
    }

    private fun loadSavedPosition(): Long =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("pos_$epId", 0L)

    // ── Controls visibility ───────────────────────────────────────────────────

    private fun setControlsVisible(visible: Boolean) {
        if (controlsVisible == visible) return
        controlsVisible = visible
        controlsContainer.animate()
            .alpha(if (visible) 1f else 0f)
            .setDuration(220)
            .withEndAction { if (!visible) controlsContainer.visibility = View.INVISIBLE }
            .start()
        if (visible) controlsContainer.visibility = View.VISIBLE
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (!isLocked && player?.isPlaying == true) {
            handler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_MS)
        }
    }

    private fun cancelAutoHide() = handler.removeCallbacks(hideControlsRunnable)

    // ── Lock ──────────────────────────────────────────────────────────────────

    private fun setLocked(locked: Boolean) {
        isLocked = locked
        lockOverlay.visibility = if (locked) View.VISIBLE else View.GONE
        if (!locked) { setControlsVisible(true); scheduleAutoHide() }
        else setControlsVisible(false)
    }

    // ── Speed menu ────────────────────────────────────────────────────────────

    private fun showSpeedMenu() {
        cancelAutoHide()
        val labels = arrayOf("0.5x", "0.75x", "1.0x  (Normal)", "1.25x", "1.5x", "2.0x")
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val current = speeds.indexOfFirst { it == playbackSpeed }.coerceAtLeast(2)
        AlertDialog.Builder(this).setTitle("Playback speed")
            .setSingleChoiceItems(labels, current) { d, which ->
                playbackSpeed = speeds[which]
                player?.setPlaybackSpeed(playbackSpeed)
                btnSpeed.text = "%.2gx".format(playbackSpeed)
                showFeedback("Speed ${labels[which].substringBefore(" ")}")
                d.dismiss(); scheduleAutoHide()
            }.show()
    }

    // ── More menu (sleep timer + audio tracks) ────────────────────────────────

    private fun showMoreMenu() {
        cancelAutoHide()
        val items = arrayOf("Sleep timer", "Audio track")
        AlertDialog.Builder(this).setTitle("More options")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSleepTimerMenu()
                    1 -> showAudioTrackMenu()
                }
            }.show()
    }

    private fun showSleepTimerMenu() {
        val items = arrayOf("Off", "5 minutes", "10 minutes", "15 minutes", "20 minutes", "30 minutes", "45 minutes", "60 minutes")
        val minutes = intArrayOf(0, 5, 10, 15, 20, 30, 45, 60)
        AlertDialog.Builder(this).setTitle("Sleep timer")
            .setItems(items) { _, which ->
                sleepTimer?.cancel()
                sleepTimer = null
                tvSleepTimer.visibility = View.GONE
                if (minutes[which] > 0) {
                    val totalMs = minutes[which] * 60_000L
                    sleepTimer = object : CountDownTimer(totalMs, 1_000) {
                        override fun onTick(ms: Long) {
                            val m = ms / 60_000; val s = (ms % 60_000) / 1_000
                            tvSleepTimer.text = "%d:%02d".format(m, s)
                        }
                        override fun onFinish() {
                            player?.pause()
                            tvSleepTimer.visibility = View.GONE
                            sleepTimer = null
                        }
                    }.start()
                    tvSleepTimer.visibility = View.VISIBLE
                    showFeedback("Sleep in ${items[which]}")
                }
                scheduleAutoHide()
            }.show()
    }

    private fun showAudioTrackMenu() {
        val exo = player ?: return
        val tracks = exo.currentTracks
        val audioGroups = mutableListOf<androidx.media3.common.Tracks.Group>()
        for (g in tracks.groups) {
            if (g.type == C.TRACK_TYPE_AUDIO) audioGroups.add(g)
        }
        if (audioGroups.isEmpty()) {
            showFeedback("No audio tracks"); scheduleAutoHide(); return
        }
        val labels = audioGroups.mapIndexed { i, g ->
            val format = g.getTrackFormat(0)
            val lang = format.language ?: "Track ${i + 1}"
            val label = format.label ?: lang
            "$label (${format.sampleRate / 1000}kHz)"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Audio track")
            .setItems(labels) { _, which ->
                val group = audioGroups[which]
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    .build()
                showFeedback("Audio: ${labels[which]}")
                scheduleAutoHide()
            }.show()
    }

    // ── Subtitle menu ─────────────────────────────────────────────────────────

    private fun showSubtitleMenu() {
        cancelAutoHide()
        val options = subtitleLabels.toMutableList().apply { add("Add file…") }
        AlertDialog.Builder(this).setTitle("Subtitles")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == options.lastIndex) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "text/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    }, PICK_SUB)
                } else {
                    subtitles.clear(); subtitleView.text = ""
                    val url = subtitleUrls.getOrNull(which).orEmpty()
                    if (url.isNotEmpty()) loadSubtitle(url)
                }
                scheduleAutoHide()
            }.show()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_SUB || resultCode != RESULT_OK) return
        try {
            contentResolver.openInputStream(data?.data ?: return)
                ?.bufferedReader()?.use { parseSrt(it.readText()) }
        } catch (_: Exception) { }
    }

    // ── Subtitle parsing ──────────────────────────────────────────────────────

    private fun loadSubtitle(url: String) {
        client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: return; response.close()
                    parseSrt(body)
                }
            })
    }

    private fun parseSrt(content: String) {
        val newSubs = mutableListOf<SubtitleEntry>()
        for (block in content.trim().split("\n\n")) {
            val lines = block.trim().split("\n")
            val timeLine = lines.find { it.contains("-->") } ?: continue
            val parts = timeLine.split("-->")
            if (parts.size < 2) continue
            val start = parseTime(parts[0].trim())
            val end = parseTime(parts[1].trim())
            val idx = lines.indexOfFirst { it.contains("-->") }
            val text = lines.drop(idx + 1).joinToString("\n")
                .replace(Regex("<[^>]*>"), "").trim()
            if (text.isNotEmpty() && start >= 0) newSubs.add(SubtitleEntry(start, end, text))
        }
        subtitles.clear(); subtitles.addAll(newSubs)
    }

    private fun parseTime(t: String): Long = try {
        val clean = t.replace(",", ".").trim().substringBefore(" ")
        val parts = clean.split(":")
        when (parts.size) {
            3 -> parts[0].toLong() * 3_600_000 + parts[1].toLong() * 60_000 + (parts[2].toDouble() * 1000).toLong()
            2 -> parts[0].toLong() * 60_000 + (parts[1].toDouble() * 1000).toLong()
            else -> -1L
        }
    } catch (_: Exception) { -1L }

    private fun startSubtitleSync() {
        subtitleRunnable?.let { handler.removeCallbacks(it) }
        subtitleRunnable = object : Runnable {
            override fun run() {
                val pos = player?.currentPosition ?: 0L
                subtitleView.text = subtitles.find { pos in it.start..it.end }?.text ?: ""
                handler.postDelayed(this, 100)
            }
        }
        handler.post(subtitleRunnable!!)
    }

    // ── Progress UI ───────────────────────────────────────────────────────────

    private fun updateProgress() {
        if (seeking) return
        val dur = player?.duration?.takeIf { it > 0 } ?: return
        val pos = player?.currentPosition ?: 0L
        val buffered = player?.bufferedPosition ?: 0L
        tvCurrentTime.text = formatTime(pos)
        tvTotalTime.text = formatTime(dur)
        seekBar.progress = (pos * 1000 / dur).toInt()
        seekBar.secondaryProgress = (buffered * 1000 / dur).toInt()
        val buffPct = (buffered * 100 / dur).toInt()
        tvHudChip.text = "%.2gx  ·  %d%% BUFFERED".format(playbackSpeed, buffPct)
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    // ── Feedback overlay ──────────────────────────────────────────────────────

    private fun showFeedback(text: String) {
        tvGestureFeedback.text = text
        tvGestureFeedback.visibility = View.VISIBLE
        handler.removeCallbacks(hideFeedbackRunnable)
        handler.postDelayed(hideFeedbackRunnable, 1_500L)
    }

    // ── Double-tap flash ──────────────────────────────────────────────────────

    private fun flashZone(view: View) {
        view.visibility = View.VISIBLE
        ObjectAnimator.ofFloat(view, "alpha", 0.7f, 0f).apply {
            duration = 350
            addUpdateListener { if ((it.animatedValue as Float) == 0f) view.visibility = View.GONE }
            start()
        }
    }

    // ── Side indicator bars ───────────────────────────────────────────────────

    private fun showBrightnessBar(fraction: Float) {
        brightnessIndicator.visibility = View.VISIBLE
        val pct = (fraction * 100).toInt()
        brightnessValue.text = "$pct"
        (brightnessFill as View).post {
            val parent = brightnessFill.parent as View
            brightnessFill.layoutParams = brightnessFill.layoutParams.also {
                it.height = (parent.height * fraction).toInt()
            }
            brightnessFill.requestLayout()
        }
        handler.removeCallbacks(hideIndicatorRunnable)
        handler.postDelayed(hideIndicatorRunnable, 1_500L)
    }

    private fun showVolumeBar(fraction: Float) {
        volumeIndicator.visibility = View.VISIBLE
        val pct = (fraction * 100).toInt()
        volumeValue.text = "$pct"
        (volumeFill as View).post {
            val parent = volumeFill.parent as View
            volumeFill.layoutParams = volumeFill.layoutParams.also {
                it.height = (parent.height * fraction).toInt()
            }
            volumeFill.requestLayout()
        }
        handler.removeCallbacks(hideIndicatorRunnable)
        handler.postDelayed(hideIndicatorRunnable, 1_500L)
    }

    // ── Error ─────────────────────────────────────────────────────────────────

    private fun showError(msg: String) {
        bufferingIndicator.visibility = View.GONE
        errorState.visibility = View.VISIBLE
        controlsContainer.visibility = View.INVISIBLE
        controlsVisible = false
        findViewById<TextView>(R.id.tvPlaybackErrorMessage).text = msg
    }

    // ── GestureOverlayView.Listener ───────────────────────────────────────────

    override fun onSingleTap() {
        if (isLocked) return
        if (controlsVisible) { cancelAutoHide(); setControlsVisible(false) }
        else { setControlsVisible(true); scheduleAutoHide() }
    }

    override fun onDoubleTapLeft() {
        if (isLocked) return
        flashZone(flashLeft)
        seekRelative(-10_000L); showFeedback("-10s")
    }

    override fun onDoubleTapCenter() {
        if (isLocked) return
        togglePlayPause()
    }

    override fun onDoubleTapRight() {
        if (isLocked) return
        flashZone(flashRight)
        seekRelative(10_000L); showFeedback("+10s")
    }

    override fun onVerticalSwipeLeft(deltaY: Float, isStart: Boolean) {
        if (isLocked) return
        val params = window.attributes
        var b = if (params.screenBrightness < 0) 0.5f else params.screenBrightness
        b = (b - deltaY / 800f).coerceIn(0.01f, 1f)
        params.screenBrightness = b
        window.attributes = params
        showBrightnessBar(b)
    }

    override fun onVerticalSwipeRight(deltaY: Float, isStart: Boolean) {
        if (isLocked) return
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        val dir = if (deltaY < 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val fraction = if (max > 0) cur.toFloat() / max else 0f
        showVolumeBar(fraction)
    }

    override fun onHorizontalSwipe(deltaX: Float, isStart: Boolean) {
        if (isLocked) return
        if (isStart || gestureStartPosition < 0) {
            gestureStartPosition = player?.currentPosition ?: 0L
            gestureAccumulatedDeltaX = 0f
        }
        gestureAccumulatedDeltaX += deltaX
        val deltaMs = (gestureAccumulatedDeltaX * 200).toLong()
        val dur = player?.duration ?: 0L
        gestureTargetPosition = (gestureStartPosition + deltaMs)
            .coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        showFeedback(formatTime(gestureTargetPosition))
    }

    override fun onPinchZoom(scaleFactor: Float, isStart: Boolean) {
        if (isLocked || isStart) return
        zoomScale = (zoomScale * scaleFactor).coerceIn(1f, 3f)
        playerView.scaleX = zoomScale
        playerView.scaleY = zoomScale
        showFeedback("Zoom %.1fx".format(zoomScale))
    }

    override fun onGestureEnd() {
        if (gestureTargetPosition >= 0) player?.seekTo(gestureTargetPosition)
        gestureStartPosition = -1L
        gestureTargetPosition = -1L
        gestureAccumulatedDeltaX = 0f
    }
}

data class SubtitleEntry(val start: Long, val end: Long, val text: String)
