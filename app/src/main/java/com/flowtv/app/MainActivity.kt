package com.flowtv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.flowtv.app.adapter.ShelfAdapter
import com.flowtv.app.data.HistoryDatabaseHelper
import com.flowtv.app.databinding.ActivityMainBinding
import com.flowtv.app.model.VideoItem
import com.flowtv.app.service.YouTubeExtractor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private lateinit var dbHelper: HistoryDatabaseHelper

    // Active Playback Feed
    private val videoFeed = mutableListOf<VideoItem>()
    private var currentFeedIndex = 0

    // Display & Clean Full-screen mode state
    private var isCleanScreenMode = false // When true, HUD is hidden unless paused
    private var isOkPressed = false
    private var okLongPressJob: Job? = null
    private var lastBackPressTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var timeUpdateRunnable: Runnable? = null
    private var scrubHideRunnable: Runnable? = null

    // Settings
    private val qualityOptions = listOf("Auto", "720p", "1080p")
    private var qualityIndex = 0
    private var subtitlesEnabled = false

    private lateinit var shelfAdapter: ShelfAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = HistoryDatabaseHelper(this)

        initPlayer()
        setupUI()
        startTimeTracker()
        loadRecommendedFeed()
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this)
            .setSeekForwardIncrementMs(10_000)
            .setSeekBackIncrementMs(10_000)
            .build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                binding.topProgressBar.visibility = View.VISIBLE
                                showStatus("Buffering video…")
                            }
                            Player.STATE_READY -> {
                                binding.topProgressBar.visibility = View.GONE
                                hideStatus()
                                updateBottomHUDVisibility()
                                saveWatchHistory()
                            }
                            Player.STATE_ENDED -> playNextVideo()
                            Player.STATE_IDLE -> {
                                binding.topProgressBar.visibility = View.GONE
                                hideStatus()
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // In clean screen mode: show HUD only when paused!
                        updateBottomHUDVisibility()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        binding.topProgressBar.visibility = View.GONE
                        showStatus("Skipping unavailable stream…")
                        handler.postDelayed({ playNextVideo() }, 1800)
                    }
                })
            }
        binding.playerView.player = player
    }

    private fun setupUI() {
        // Shelf RecyclerView (Search & History)
        shelfAdapter = ShelfAdapter(emptyList()) { video, _ ->
            closeAllOverlays()
            val list = if (binding.tvShelfTitle.text.toString().contains("History")) {
                dbHelper.getHistory()
            } else {
                videoFeed
            }
            startNewFeed(list, list.indexOfFirst { it.videoId == video.videoId }.coerceAtLeast(0))
        }
        binding.rvShelf.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvShelf.adapter = shelfAdapter

        // Menu Button: Search
        binding.btnMenuSearch.setOnClickListener {
            closeMenu()
            openSearchShelf()
        }

        // Menu Button: Quality
        binding.btnMenuQuality.setOnClickListener {
            qualityIndex = (qualityIndex + 1) % qualityOptions.size
            val quality = qualityOptions[qualityIndex]
            binding.btnMenuQuality.text = "⚙️   Quality: $quality"
            restartCurrentVideoWithQuality()
        }

        // Menu Button: Subtitles
        binding.btnMenuSubtitles.setOnClickListener {
            subtitlesEnabled = !subtitlesEnabled
            binding.btnMenuSubtitles.text =
                if (subtitlesEnabled) "💬   Subtitles: English" else "💬   Subtitles: Off"
        }

        // Menu Button: History
        binding.btnMenuHistory.setOnClickListener {
            closeMenu()
            openHistoryShelf()
        }

        // Menu Button: Exit App
        binding.btnMenuExit.setOnClickListener {
            finish()
        }

        // Search Submit Button
        binding.btnSubmitSearch.setOnClickListener {
            performSearch(binding.etSearchInput.text.toString())
        }

        // Exit Dialog buttons
        binding.btnExitCancel.setOnClickListener {
            binding.exitDialog.visibility = View.GONE
        }
        binding.btnExitConfirm.setOnClickListener {
            finish()
        }
    }

    // ==========================================
    // FEED & PLAYBACK LOGIC
    // ==========================================

    private fun loadRecommendedFeed() {
        showStatus("Loading trending shorts…")
        binding.topProgressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val feed = YouTubeExtractor.getRecommendedFeed()
            if (feed.isNotEmpty()) {
                startNewFeed(feed, 0)
            } else {
                showStatus("No videos returned, retrying…")
                handler.postDelayed({ loadRecommendedFeed() }, 3000)
            }
        }
    }

    private fun startNewFeed(newFeed: List<VideoItem>, startIndex: Int = 0) {
        videoFeed.clear()
        videoFeed.addAll(newFeed)
        currentFeedIndex = startIndex.coerceIn(0, (videoFeed.size - 1).coerceAtLeast(0))
        // Always reset to normal mode when changing/starting feed
        isCleanScreenMode = false
        playCurrentVideo()
    }

    private fun playCurrentVideo() {
        val video = getCurrentVideo() ?: return

        // 1. Reset to Normal mode for every video (HUD always visible by default)
        isCleanScreenMode = false
        updateBottomHUDVisibility()

        // 2. Populate Bottom HUD metadata
        binding.tvBottomTitle.text = video.title
        binding.tvBottomChannel.text = video.channelTitle
        binding.tvBottomLikes.text = "👍 " + video.likes
        binding.tvBottomDislikes.text = "👎 " + video.dislikes

        // 3. Fetch live ReturnYouTubeDislike stats
        lifecycleScope.launch {
            val (likes, dislikes) = YouTubeExtractor.getLikesAndDislikes(video.videoId)
            video.likes = likes
            video.dislikes = dislikes
            binding.tvBottomLikes.text = "👍 $likes"
            binding.tvBottomDislikes.text = "👎 $dislikes"
        }

        // 4. Resolve stream URL with animated feedback
        showStatus("Playing ${if (video.isShort) "Short" else "Video"}…")
        binding.topProgressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val streamUrl = video.streamUrl ?: YouTubeExtractor.resolveStreamUrl(
                video.videoId,
                qualityOptions[qualityIndex]
            )
            video.streamUrl = streamUrl

            if (streamUrl != null) {
                val mediaItem = MediaItem.fromUri(streamUrl)
                player?.setMediaItem(mediaItem)
                if (video.resumePositionMs > 5000) {
                    player?.seekTo(video.resumePositionMs)
                }
                player?.prepare()
                player?.play()

                // Smooth video appearance animation
                binding.playerView.alpha = 0f
                binding.playerView.animate().alpha(1f).setDuration(300).start()
            } else {
                showStatus("Stream unavailable, skipping…")
                delay(1200)
                playNextVideo()
            }
        }
    }

    private fun playNextVideo() {
        // As requested: Each video always loads in normal mode (HUD visible)
        isCleanScreenMode = false
        if (currentFeedIndex < videoFeed.size - 1) {
            currentFeedIndex++
            playCurrentVideo()
        } else {
            loadRecommendedFeed()
        }
    }

    private fun playPreviousVideo() {
        isCleanScreenMode = false
        if (currentFeedIndex > 0) {
            currentFeedIndex--
            playCurrentVideo()
        }
    }

    private fun getCurrentVideo(): VideoItem? {
        return if (videoFeed.isNotEmpty() && currentFeedIndex in videoFeed.indices) {
            videoFeed[currentFeedIndex]
        } else null
    }

    // ==========================================
    // TIME TRACKER & BOTTOM HUD
    // ==========================================

    private fun startTimeTracker() {
        timeUpdateRunnable = object : Runnable {
            override fun run() {
                val p = player
                if (p != null && p.playbackState == Player.STATE_READY) {
                    val cur = p.currentPosition
                    val dur = p.duration.coerceAtLeast(0)
                    val curM = (cur / 1000) / 60
                    val curS = (cur / 1000) % 60
                    val durM = (dur / 1000) / 60
                    val durS = (dur / 1000) % 60
                    binding.tvBottomTimePill.text =
                        String.format("%02d:%02d / %02d:%02d", curM, curS, durM, durS)

                    if (dur > 0) {
                        binding.scrubProgressBar.progress =
                            ((cur.toFloat() / dur) * 1000).toInt()
                    }
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(timeUpdateRunnable!!)
    }

    private fun updateBottomHUDVisibility() {
        if (!isCleanScreenMode) {
            // Normal mode: ALWAYS VISIBLE
            binding.bottomHUD.visibility = View.VISIBLE
        } else {
            // Clean screen mode: VISIBLE ONLY WHEN PAUSED
            val isPlaying = player?.isPlaying ?: false
            binding.bottomHUD.visibility = if (!isPlaying) View.VISIBLE else View.GONE
        }
    }

    private fun toggleCleanScreenMode() {
        isCleanScreenMode = !isCleanScreenMode
        updateBottomHUDVisibility()
        val msg = if (isCleanScreenMode) "Full Screen Mode" else "Normal Mode"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ==========================================
    // SEEKING & SCRUB BAR OVERLAY
    // ==========================================

    private fun seekRelative(offsetMs: Long) {
        val p = player ?: return
        val newPos = (p.currentPosition + offsetMs).coerceIn(0, p.duration.coerceAtLeast(0))
        p.seekTo(newPos)

        binding.scrubOverlay.visibility = View.VISIBLE
        val sign = if (offsetMs >= 0) "+10s" else "-10s"
        val curM = (newPos / 1000) / 60
        val curS = (newPos / 1000) % 60
        val totM = (p.duration / 1000) / 60
        val totS = (p.duration / 1000) % 60
        binding.tvScrubTime.text =
            String.format("%s   %02d:%02d / %02d:%02d", sign, curM, curS, totM, totS)
        binding.scrubProgressBar.progress =
            ((newPos.toFloat() / p.duration.coerceAtLeast(1)) * 1000).toInt()

        scrubHideRunnable?.let { handler.removeCallbacks(it) }
        scrubHideRunnable = Runnable { binding.scrubOverlay.visibility = View.GONE }
        handler.postDelayed(scrubHideRunnable!!, 1500)
    }

    // ==========================================
    // MENU DRAWER & VIDEO SLIDE ANIMATION
    // ==========================================

    private fun openMenu() {
        closeAllOverlays()
        binding.menuDrawer.visibility = View.VISIBLE

        // Smoothly slide and scale video player to the left
        binding.playerWrapper.animate()
            .translationX(-130f)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(240)
            .start()

        binding.btnMenuSearch.requestFocus()
    }

    private fun closeMenu(): Boolean {
        if (binding.menuDrawer.isVisible) {
            binding.menuDrawer.visibility = View.GONE

            // Smoothly return video player back to center full screen
            binding.playerWrapper.animate()
                .translationX(0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(240)
                .start()
            return true
        }
        return false
    }

    private fun openSearchShelf() {
        binding.shelfOverlay.visibility = View.VISIBLE
        binding.searchHeader.visibility = View.VISIBLE
        binding.tvShelfTitle.text = "Search YouTube"
        binding.etSearchInput.requestFocus()
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) return
        showStatus("Searching YouTube…")
        lifecycleScope.launch {
            val results = YouTubeExtractor.searchVideos(query)
            hideStatus()
            shelfAdapter.updateList(results)
        }
    }

    private fun openHistoryShelf() {
        binding.shelfOverlay.visibility = View.VISIBLE
        binding.searchHeader.visibility = View.GONE
        binding.tvShelfTitle.text = "Watch History"

        val history = dbHelper.getHistory()
        shelfAdapter.updateList(history)
        binding.rvShelf.requestFocus()
    }

    private fun closeAllOverlays(): Boolean {
        var closed = closeMenu()
        if (binding.shelfOverlay.isVisible) {
            binding.shelfOverlay.visibility = View.GONE
            closed = true
        }
        if (binding.exitDialog.isVisible) {
            binding.exitDialog.visibility = View.GONE
            closed = true
        }
        return closed
    }

    private fun showStatus(message: String) {
        binding.tvStatusMessage.text = message
        binding.centerStatusPill.alpha = 0f
        binding.centerStatusPill.visibility = View.VISIBLE
        binding.centerStatusPill.animate().alpha(1f).setDuration(200).start()
    }

    private fun hideStatus() {
        binding.centerStatusPill.animate().alpha(0f).setDuration(200).withEndAction {
            binding.centerStatusPill.visibility = View.GONE
        }.start()
    }

    private fun restartCurrentVideoWithQuality() {
        val video = getCurrentVideo() ?: return
        video.streamUrl = null
        playCurrentVideo()
    }

    private fun saveWatchHistory() {
        val video = getCurrentVideo() ?: return
        val pos = player?.currentPosition ?: 0L
        if (pos > 3000) {
            dbHelper.saveWatchProgress(video, pos)
        }
    }

    // ==========================================
    // REMOTE CONTROL KEY HANDLING
    // ==========================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        // 1. Long-press OK Detection -> Toggles Clean Screen / Full-Screen Mode
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!isOkPressed) {
                    isOkPressed = true
                    okLongPressJob = lifecycleScope.launch {
                        delay(600) // 600ms hold -> Clean Full-Screen Mode
                        toggleCleanScreenMode()
                    }
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                isOkPressed = false
                if (okLongPressJob?.isActive == true) {
                    okLongPressJob?.cancel()
                    // Short tap OK -> Play / Pause
                    if (!isAnyOverlayVisible()) {
                        togglePlayPause()
                    }
                }
            }
            if (isAnyOverlayVisible()) {
                return super.dispatchKeyEvent(event)
            }
            return true
        }

        // 2. Menu button -> Opens Right-Side Centered Frosted Menu (Video slides left)
        if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
            if (binding.menuDrawer.isVisible) {
                closeMenu()
            } else {
                openMenu()
            }
            return true
        }

        // 3. In-Video Remote Navigation (when no overlays are showing)
        if (!isAnyOverlayVisible() && event.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    playNextVideo()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    playPreviousVideo()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    seekRelative(-10_000)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    seekRelative(+10_000)
                    return true
                }
            }
        }

        // 4. Back Key Handling
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.isLongPress) {
                    showStatus("Refreshing Feed…")
                    loadRecommendedFeed()
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                // Close any open overlays first
                if (closeAllOverlays()) {
                    return true
                }

                // If in full-screen video: Double-tap Back shows Frosted Exit Dialog
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressTime < 1800) {
                    binding.exitDialog.visibility = View.VISIBLE
                    binding.btnExitCancel.requestFocus()
                } else {
                    lastBackPressTime = currentTime
                    Toast.makeText(this, getString(R.string.refresh_hint), Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun isAnyOverlayVisible(): Boolean {
        return binding.menuDrawer.isVisible ||
                binding.shelfOverlay.isVisible ||
                binding.exitDialog.isVisible
    }

    override fun onPause() {
        super.onPause()
        saveWatchHistory()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveWatchHistory()
        timeUpdateRunnable?.let { handler.removeCallbacks(it) }
        player?.release()
        player = null
    }
}
