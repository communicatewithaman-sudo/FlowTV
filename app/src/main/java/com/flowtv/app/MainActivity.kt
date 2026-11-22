package com.flowtv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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

    // Active Playback Feed (Home)
    private val videoFeed = mutableListOf<VideoItem>()
    private var currentFeedIndex = 0

    // Search and History Active Lists
    private val currentSearchFeed = mutableListOf<VideoItem>()
    private val currentHistoryFeed = mutableListOf<VideoItem>()

    // Async Coroutine Jobs
    private var feedLoadJob: Job? = null
    private var playbackJob: Job? = null
    private var searchJob: Job? = null

    // Navigation state
    private var isSearchPlaybackMode = false // When true, pressing Back returns to Search shelf
    private var isCleanScreenMode = false    // When true, HUD is hidden unless paused
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
                                showStatus("Buffering…")
                            }
                            Player.STATE_READY -> {
                                binding.topProgressBar.visibility = View.GONE
                                hideStatus()
                                updateBottomHUDVisibility()
                                saveWatchHistory()
                            }
                            Player.STATE_ENDED -> {
                                // Do NOT auto-advance if user is browsing Search or History!
                                if (!binding.shelfOverlay.isVisible) {
                                    playNextVideo()
                                }
                            }
                            Player.STATE_IDLE -> {
                                binding.topProgressBar.visibility = View.GONE
                                hideStatus()
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateBottomHUDVisibility()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        binding.topProgressBar.visibility = View.GONE
                        if (!binding.shelfOverlay.isVisible) {
                            showStatus("Stream error, retrying…")
                            handler.postDelayed({
                                if (!binding.shelfOverlay.isVisible) playNextVideo()
                            }, 1800)
                        }
                    }
                })
            }
        binding.playerView.player = player
    }

    private fun setupUI() {
        // Shelf RecyclerView (Search & History)
        shelfAdapter = ShelfAdapter(emptyList()) { selectedVideo, _ ->
            val isHistory = binding.tvShelfTitle.text.toString().contains("History")
            closeMenu()
            binding.shelfOverlay.visibility = View.GONE

            if (!isHistory) {
                // User started playback from Search: mark search playback mode
                isSearchPlaybackMode = true
                val index = currentSearchFeed.indexOfFirst { it.videoId == selectedVideo.videoId }.coerceAtLeast(0)
                startNewFeed(currentSearchFeed, index)
            } else {
                isSearchPlaybackMode = false
                val index = currentHistoryFeed.indexOfFirst { it.videoId == selectedVideo.videoId }.coerceAtLeast(0)
                startNewFeed(currentHistoryFeed, index)
            }
        }
        binding.rvShelf.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvShelf.adapter = shelfAdapter

        // Keyboard Action / Enter Key for Search EditText
        binding.etSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                performSearch(binding.etSearchInput.text.toString())
                true
            } else {
                false
            }
        }

        // Search Submit Button (side of search box)
        binding.btnSubmitSearch.setOnClickListener {
            performSearch(binding.etSearchInput.text.toString())
        }

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

        // Exit Dialog buttons
        binding.btnExitCancel.setOnClickListener {
            binding.exitDialog.visibility = View.GONE
        }
        binding.btnExitConfirm.setOnClickListener {
            finish()
        }
    }

    // ==========================================
    // STATUS PILL ANIMATIONS (Show/Hide)
    // ==========================================

    private fun showStatus(message: String) {
        binding.tvStatusMessage.text = message
        binding.centerStatusPill.visibility = View.VISIBLE
        binding.centerStatusPill.alpha = 0f
        binding.centerStatusPill.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .start()
    }

    private fun hideStatus() {
        binding.centerStatusPill.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(200)
            .withEndAction {
                binding.centerStatusPill.visibility = View.GONE
            }.start()
    }

    // ==========================================
    // FEED & PLAYBACK LOGIC
    // ==========================================

    private fun loadRecommendedFeed() {
        // If shelf is open, do not load or auto-play home feed
        if (binding.shelfOverlay.isVisible) return

        showStatus("Loading videos…")
        binding.topProgressBar.visibility = View.VISIBLE

        feedLoadJob?.cancel()
        feedLoadJob = lifecycleScope.launch {
            val feed = YouTubeExtractor.getRecommendedFeed()
            if (binding.shelfOverlay.isVisible) return@launch
            if (feed.isNotEmpty()) {
                startNewFeed(feed, 0)
            } else {
                showStatus("Connecting…")
                handler.postDelayed({
                    if (!binding.shelfOverlay.isVisible) loadRecommendedFeed()
                }, 3000)
            }
        }
    }

    private fun startNewFeed(newFeed: List<VideoItem>, startIndex: Int = 0) {
        videoFeed.clear()
        videoFeed.addAll(newFeed)
        currentFeedIndex = startIndex.coerceIn(0, (videoFeed.size - 1).coerceAtLeast(0))
        isCleanScreenMode = false
        playCurrentVideo()
    }

    private fun playCurrentVideo() {
        val video = getCurrentVideo() ?: return

        isCleanScreenMode = false
        updateBottomHUDVisibility()

        binding.tvBottomTitle.text = video.title
        binding.tvBottomChannel.text = video.channelTitle
        binding.tvBottomLikes.text = "👍 " + video.likes
        binding.tvBottomDislikes.text = "👎 " + video.dislikes

        lifecycleScope.launch {
            val (likes, dislikes) = YouTubeExtractor.getLikesAndDislikes(video.videoId)
            video.likes = likes
            video.dislikes = dislikes
            binding.tvBottomLikes.text = "👍 $likes"
            binding.tvBottomDislikes.text = "👎 $dislikes"
        }

        showStatus("Loading ${if (video.isShort) "Short" else "Video"}…")
        binding.topProgressBar.visibility = View.VISIBLE

        playbackJob?.cancel()
        playbackJob = lifecycleScope.launch {
            val streamUrl = video.streamUrl ?: YouTubeExtractor.resolveStreamUrl(
                video.videoId,
                qualityOptions[qualityIndex]
            )
            video.streamUrl = streamUrl

            // If shelf was opened while resolving, abort playback
            if (binding.shelfOverlay.isVisible) return@launch

            if (streamUrl != null) {
                // ExoPlayer HLS detection: iOS streams return m3u8 manifests
                val mediaItem = if (streamUrl.contains(".m3u8", ignoreCase = true) ||
                    streamUrl.contains("manifest/hls_variant", ignoreCase = true) ||
                    streamUrl.contains("youtubei/v1/player", ignoreCase = true)
                ) {
                    MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMimeType(MimeTypes.APPLICATION_M3U8)
                        .build()
                } else {
                    MediaItem.fromUri(streamUrl)
                }

                player?.setMediaItem(mediaItem)
                if (video.resumePositionMs > 5000) {
                    player?.seekTo(video.resumePositionMs)
                }
                player?.prepare()
                player?.play()

                // Smooth fade-in
                binding.playerView.alpha = 0f
                binding.playerView.animate().alpha(1f).setDuration(280).start()
            } else {
                showStatus("Stream unavailable, skipping…")
                delay(1200)
                if (!binding.shelfOverlay.isVisible) {
                    playNextVideo()
                }
            }
        }
    }

    private fun playNextVideo() {
        if (binding.shelfOverlay.isVisible) return
        isCleanScreenMode = false
        if (currentFeedIndex < videoFeed.size - 1) {
            currentFeedIndex++
            playCurrentVideo()
        } else {
            loadRecommendedFeed()
        }
    }

    private fun playPreviousVideo() {
        if (binding.shelfOverlay.isVisible) return
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
            binding.bottomHUD.visibility = View.VISIBLE
        } else {
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
    // MENU DRAWER & NO-CLIPPING SLIDE + SCALE ANIMATION
    // ==========================================

    private fun openMenu() {
        binding.menuDrawer.visibility = View.VISIBLE

        // Mathematical scaling and translation so NOTHING clips off-screen:
        // Main content scales down and shifts left to leave exact space for the 320dp menu
        val menuWidthPx = 320f * resources.displayMetrics.density
        val screenWidthPx = resources.displayMetrics.widthPixels.toFloat()
        val scale = ((screenWidthPx - menuWidthPx) / screenWidthPx).coerceIn(0.6f, 0.95f)
        val translateX = -(menuWidthPx / 2f)

        binding.mainContentWrapper.animate()
            .translationX(translateX)
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(260)
            .start()

        binding.btnMenuSearch.requestFocus()
    }

    private fun closeMenu(): Boolean {
        if (binding.menuDrawer.isVisible) {
            binding.menuDrawer.visibility = View.GONE

            binding.mainContentWrapper.animate()
                .translationX(0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(240)
                .start()

            // Return focus to whichever view was underneath
            if (binding.shelfOverlay.isVisible) {
                if (binding.searchHeader.isVisible) {
                    binding.etSearchInput.requestFocus()
                } else {
                    binding.rvShelf.requestFocus()
                }
            } else {
                binding.playerView.requestFocus()
            }
            return true
        }
        return false
    }

    private fun openSearchShelf() {
        // Stop any background home feed loading or playback
        feedLoadJob?.cancel()
        playbackJob?.cancel()
        player?.pause()

        binding.shelfOverlay.visibility = View.VISIBLE
        binding.searchHeader.visibility = View.VISIBLE
        binding.tvShelfTitle.visibility = View.GONE // No "Search YouTube" label below placeholder

        shelfAdapter.updateList(currentSearchFeed)
        binding.etSearchInput.requestFocus()
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) return
        binding.searchLoader.visibility = View.VISIBLE
        showStatus("Searching…")

        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val results = YouTubeExtractor.searchVideos(query)
            hideStatus()
            binding.searchLoader.visibility = View.GONE
            currentSearchFeed.clear()
            currentSearchFeed.addAll(results)
            shelfAdapter.updateList(currentSearchFeed)
            if (results.isNotEmpty()) {
                binding.rvShelf.requestFocus()
            }
        }
    }

    private fun openHistoryShelf() {
        // Stop any background home feed loading or playback
        feedLoadJob?.cancel()
        playbackJob?.cancel()
        player?.pause()

        binding.shelfOverlay.visibility = View.VISIBLE
        binding.searchHeader.visibility = View.GONE
        binding.tvShelfTitle.visibility = View.VISIBLE
        binding.tvShelfTitle.text = "Watch History"

        val history = dbHelper.getHistory()
        currentHistoryFeed.clear()
        currentHistoryFeed.addAll(history)
        shelfAdapter.updateList(currentHistoryFeed)
        binding.rvShelf.requestFocus()
    }

    private fun isHomeScreen(): Boolean {
        return !binding.menuDrawer.isVisible &&
                !binding.shelfOverlay.isVisible &&
                !binding.exitDialog.isVisible &&
                !isSearchPlaybackMode
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

        // Handle Enter/Search from EditText
        if (binding.etSearchInput.hasFocus() &&
            (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) &&
            event.action == KeyEvent.ACTION_UP
        ) {
            performSearch(binding.etSearchInput.text.toString())
            return true
        }

        // 1. Long-press OK Detection -> Toggles Clean Screen / Full-Screen Mode
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!isOkPressed) {
                    isOkPressed = true
                    okLongPressJob = lifecycleScope.launch {
                        delay(600)
                        toggleCleanScreenMode()
                    }
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                isOkPressed = false
                if (okLongPressJob?.isActive == true) {
                    okLongPressJob?.cancel()
                    if (!binding.shelfOverlay.isVisible && !binding.menuDrawer.isVisible && !binding.exitDialog.isVisible) {
                        togglePlayPause()
                    }
                }
            }
            if (binding.shelfOverlay.isVisible || binding.menuDrawer.isVisible || binding.exitDialog.isVisible) {
                return super.dispatchKeyEvent(event)
            }
            return true
        }

        // 2. Menu button -> Opens/Closes Right-Side Centered Frosted Menu
        if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
            if (binding.menuDrawer.isVisible) {
                closeMenu()
            } else {
                openMenu()
            }
            return true
        }

        // 3. In-Video Remote Navigation (when no overlays are showing)
        if (!binding.shelfOverlay.isVisible && !binding.menuDrawer.isVisible && !binding.exitDialog.isVisible && event.action == KeyEvent.ACTION_DOWN) {
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
            // Priority 1: If Menu is open -> Close Menu (stays on Search, History, or whatever was open!)
            if (binding.menuDrawer.isVisible) {
                if (event.action == KeyEvent.ACTION_UP) {
                    closeMenu()
                }
                return true
            }

            // Priority 2: If Exit Dialog is open -> Close Exit Dialog
            if (binding.exitDialog.isVisible) {
                if (event.action == KeyEvent.ACTION_UP) {
                    binding.exitDialog.visibility = View.GONE
                }
                return true
            }

            // Priority 3: If playing a video that was opened from Search -> Return back to Search Shelf!
            if (isSearchPlaybackMode) {
                if (event.action == KeyEvent.ACTION_UP) {
                    isSearchPlaybackMode = false
                    player?.pause()
                    openSearchShelf()
                }
                return true
            }

            // Priority 4: If Search or History shelf is open -> Return to Home Screen!
            if (binding.shelfOverlay.isVisible) {
                if (event.action == KeyEvent.ACTION_UP) {
                    binding.shelfOverlay.visibility = View.GONE
                    // Resume home video playback
                    player?.play()
                }
                return true
            }

            // Priority 5: ONLY ON HOME SCREEN
            if (isHomeScreen()) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    // Hold Back ONLY on Home Screen -> Refreshes feed
                    if (event.isLongPress) {
                        showStatus("Refreshing Feed…")
                        loadRecommendedFeed()
                        return true
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    // Double tap Back ONLY on Home Screen -> Opens Exit Dialog
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
        }

        return super.dispatchKeyEvent(event)
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
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
