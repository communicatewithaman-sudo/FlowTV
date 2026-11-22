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
import androidx.recyclerview.widget.LinearLayoutManager
import com.flowtv.app.adapter.ShelfAdapter
import com.flowtv.app.data.HistoryDatabaseHelper
import com.flowtv.app.databinding.ActivityMainBinding
import com.flowtv.app.model.VideoItem
import com.flowtv.app.service.YouTubeExtractor
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var ytPlayer: YouTubePlayer? = null
    private var isPlayerReady = false
    private lateinit var dbHelper: HistoryDatabaseHelper

    // Active Playback Feed (Home)
    private val videoFeed = mutableListOf<VideoItem>()
    private var currentFeedIndex = 0

    // Search and History Active Lists
    private val currentSearchFeed = mutableListOf<VideoItem>()
    private val currentHistoryFeed = mutableListOf<VideoItem>()

    // Current video playback stats
    private var currentVideoDuration = 0f
    private var currentVideoPosition = 0f
    private var isVideoPlaying = false

    // Async Coroutine Jobs
    private var feedLoadJob: Job? = null
    private var searchJob: Job? = null

    // Navigation state
    private var isSearchPlaybackMode = false // When true, pressing Back returns to Search shelf
    private var isCleanScreenMode = false    // When true, HUD is hidden unless paused
    private var isOkPressed = false
    private var okLongPressJob: Job? = null
    private var lastBackPressTime = 0L

    private val handler = Handler(Looper.getMainLooper())
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

        lifecycle.addObserver(binding.youtubePlayerView)
        dbHelper = HistoryDatabaseHelper(this)

        initYouTubePlayer()
        setupUI()
        loadRecommendedFeed()
    }

    private fun initYouTubePlayer() {
        // Disable YouTube default web controls: controls(0)
        // All playback, UI, scrubbing, HUD, remote keys are handled by our TV interface
        val options = IFramePlayerOptions.Builder()
            .controls(0)
            .rel(0)
            .ivLoadPolicy(3)
            .ccLoadPolicy(0)
            .build()

        binding.youtubePlayerView.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                ytPlayer = youTubePlayer
                isPlayerReady = true

                // Play first video if feed has already loaded
                if (videoFeed.isNotEmpty()) {
                    playCurrentVideo()
                }
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                when (state) {
                    PlayerConstants.PlayerState.BUFFERING -> {
                        binding.topProgressBar.visibility = View.VISIBLE
                        showStatus("Buffering…")
                    }
                    PlayerConstants.PlayerState.PLAYING -> {
                        isVideoPlaying = true
                        binding.topProgressBar.visibility = View.GONE
                        hideStatus()
                        updateBottomHUDVisibility()
                        saveWatchHistory()
                    }
                    PlayerConstants.PlayerState.PAUSED -> {
                        isVideoPlaying = false
                        binding.topProgressBar.visibility = View.GONE
                        updateBottomHUDVisibility()
                    }
                    PlayerConstants.PlayerState.ENDED -> {
                        isVideoPlaying = false
                        // Do NOT auto-advance if user is on Search or History shelf
                        if (!binding.shelfOverlay.isVisible) {
                            playNextVideo()
                        }
                    }
                    else -> {}
                }
            }

            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                currentVideoPosition = second
                updateTimeDisplay()
            }

            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                currentVideoDuration = duration
                updateTimeDisplay()
            }

            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                binding.topProgressBar.visibility = View.GONE
                if (!binding.shelfOverlay.isVisible) {
                    showStatus("Retrying next video…")
                    handler.postDelayed({
                        if (!binding.shelfOverlay.isVisible) playNextVideo()
                    }, 1500)
                }
            }
        }, options)
    }

    private fun updateTimeDisplay() {
        val cur = currentVideoPosition.toLong()
        val dur = currentVideoDuration.toLong().coerceAtLeast(0)
        val curM = cur / 60
        val curS = cur % 60
        val durM = dur / 60
        val durS = dur % 60
        binding.tvBottomTimePill.text =
            String.format("%02d:%02d / %02d:%02d", curM, curS, durM, durS)

        if (dur > 0) {
            binding.scrubProgressBar.progress =
                ((currentVideoPosition / currentVideoDuration) * 1000).toInt().coerceIn(0, 1000)
        }
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
            restartCurrentVideo()
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
        if (!isPlayerReady || ytPlayer == null) return

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

        showStatus("Playing ${if (video.isShort) "Short" else "Video"}…")
        binding.topProgressBar.visibility = View.VISIBLE

        // Load and play video directly through YouTube Player Engine
        val startSeconds = if (video.resumePositionMs > 5000) (video.resumePositionMs / 1000f) else 0f
        ytPlayer?.loadVideo(video.videoId, startSeconds)

        // Smooth video container fade-in
        binding.youtubePlayerView.alpha = 0f
        binding.youtubePlayerView.animate().alpha(1f).setDuration(260).start()
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

    private fun restartCurrentVideo() {
        val curTime = currentVideoPosition
        getCurrentVideo()?.let {
            ytPlayer?.loadVideo(it.videoId, curTime)
        }
    }

    private fun updateBottomHUDVisibility() {
        if (!isCleanScreenMode) {
            binding.bottomHUD.visibility = View.VISIBLE
        } else {
            binding.bottomHUD.visibility = if (!isVideoPlaying) View.VISIBLE else View.GONE
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

    private fun seekRelative(offsetSec: Float) {
        val newPos = (currentVideoPosition + offsetSec).coerceIn(0f, currentVideoDuration.coerceAtLeast(1f))
        ytPlayer?.seekTo(newPos)
        currentVideoPosition = newPos

        binding.scrubOverlay.visibility = View.VISIBLE
        val sign = if (offsetSec >= 0) "+10s" else "-10s"
        val curM = newPos.toLong() / 60
        val curS = newPos.toLong() % 60
        val totM = currentVideoDuration.toLong() / 60
        val totS = currentVideoDuration.toLong() % 60
        binding.tvScrubTime.text =
            String.format("%s   %02d:%02d / %02d:%02d", sign, curM, curS, totM, totS)
        binding.scrubProgressBar.progress =
            ((newPos / currentVideoDuration.coerceAtLeast(1f)) * 1000).toInt().coerceIn(0, 1000)

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
                binding.playerTouchOverlay.requestFocus()
            }
            return true
        }
        return false
    }

    private fun openSearchShelf() {
        feedLoadJob?.cancel()
        ytPlayer?.pause()

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
        feedLoadJob?.cancel()
        ytPlayer?.pause()

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

    private fun saveWatchHistory() {
        val video = getCurrentVideo() ?: return
        val pos = (currentVideoPosition * 1000).toLong()
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
                    seekRelative(-10f)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    seekRelative(+10f)
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
                    ytPlayer?.pause()
                    openSearchShelf()
                }
                return true
            }

            // Priority 4: If Search or History shelf is open -> Return to Home Screen!
            if (binding.shelfOverlay.isVisible) {
                if (event.action == KeyEvent.ACTION_UP) {
                    binding.shelfOverlay.visibility = View.GONE
                    // Resume home video playback
                    ytPlayer?.play()
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
        if (isVideoPlaying) {
            ytPlayer?.pause()
        } else {
            ytPlayer?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        saveWatchHistory()
        ytPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveWatchHistory()
        binding.youtubePlayerView.release()
    }
}
