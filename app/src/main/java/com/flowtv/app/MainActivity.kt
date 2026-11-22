package com.flowtv.app

import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.flowtv.app.adapter.HistoryListAdapter
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

    // 10-second timer for Clear All History
    private var clearHistoryTimer: CountDownTimer? = null

    private val handler = Handler(Looper.getMainLooper())
    private var scrubHideRunnable: Runnable? = null

    // Settings
    private val qualityOptions = listOf("Auto", "720p", "1080p")
    private var qualityIndex = 0
    private var subtitlesEnabled = false

    private lateinit var shelfAdapter: ShelfAdapter
    private lateinit var historyAdapter: HistoryListAdapter

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

                // CRITICAL FIX FOR ANDROID TV: Disable user gesture restriction so videos play automatically
                binding.youtubePlayerView.post {
                    findInnerWebView(binding.youtubePlayerView)?.settings?.apply {
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                    }
                }

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

    private fun findInnerWebView(viewGroup: ViewGroup): WebView? {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is WebView) return child
            if (child is ViewGroup) {
                val found = findInnerWebView(child)
                if (found != null) return found
            }
        }
        return null
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
        // Shelf RecyclerView (Horizontal for Search)
        shelfAdapter = ShelfAdapter(emptyList()) { selectedVideo, _ ->
            closeMenu()
            binding.shelfOverlay.visibility = View.GONE

            // User started playback from Search: mark search playback mode
            isSearchPlaybackMode = true
            val index = currentSearchFeed.indexOfFirst { it.videoId == selectedVideo.videoId }.coerceAtLeast(0)
            startNewFeed(currentSearchFeed, index)
        }
        binding.rvShelf.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvShelf.adapter = shelfAdapter

        // History RecyclerView (Vertical list format with per-item Delete)
        historyAdapter = HistoryListAdapter(
            items = emptyList(),
            onItemClick = { selectedVideo, _ ->
                closeMenu()
                binding.shelfOverlay.visibility = View.GONE
                isSearchPlaybackMode = false
                val index = currentHistoryFeed.indexOfFirst { it.videoId == selectedVideo.videoId }.coerceAtLeast(0)
                startNewFeed(currentHistoryFeed, index)
            },
            onDeleteClick = { selectedVideo, _ ->
                dbHelper.deleteItem(selectedVideo.videoId)
                refreshHistoryList()
                Toast.makeText(this, "Removed from history", Toast.LENGTH_SHORT).show()
            }
        )
        binding.rvHistoryList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        binding.rvHistoryList.adapter = historyAdapter

        // Clear Entire History Button -> Opens 10-Second Countdown Dialog
        binding.btnClearAllHistory.setOnClickListener {
            openClearHistoryDialog()
        }

        // Cancel Clear History
        binding.btnCancelClearHistory.setOnClickListener {
            clearHistoryTimer?.cancel()
            binding.clearHistoryDialog.visibility = View.GONE
            binding.btnClearAllHistory.requestFocus()
        }

        // Confirm Clear History (Enabled after 10-second timer completes)
        binding.btnConfirmClearHistory.setOnClickListener {
            clearHistoryTimer?.cancel()
            binding.clearHistoryDialog.visibility = View.GONE
            dbHelper.clearAllHistory()
            refreshHistoryList()
            Toast.makeText(this, "All history cleared", Toast.LENGTH_SHORT).show()
            binding.rvHistoryList.requestFocus()
        }

        // Keyboard Action / Enter Key for Search EditText
        binding.etSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideSoftKeyboard(binding.etSearchInput)
                performSearch(binding.etSearchInput.text.toString())
                true
            } else {
                false
            }
        }

        // On-Screen Keyboard Trigger: when EditText is clicked or focused, pop up TV keyboard
        binding.etSearchInput.setOnClickListener {
            showSoftKeyboard(binding.etSearchInput)
        }
        binding.etSearchInput.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                showSoftKeyboard(view)
            }
        }

        // Search Submit Button (side of search box)
        binding.btnSubmitSearch.setOnClickListener {
            hideSoftKeyboard(binding.etSearchInput)
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

    private fun showSoftKeyboard(view: View) {
        view.postDelayed({
            view.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_FORCED)
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY)
        }, 120)
    }

    private fun hideSoftKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun openClearHistoryDialog() {
        binding.clearHistoryDialog.visibility = View.VISIBLE
        binding.btnConfirmClearHistory.isEnabled = false
        binding.btnConfirmClearHistory.isClickable = false
        binding.btnConfirmClearHistory.isFocusable = false
        binding.btnConfirmClearHistory.alpha = 0.4f
        binding.btnConfirmClearHistory.text = "Wait (10s)"
        binding.btnCancelClearHistory.requestFocus()

        clearHistoryTimer?.cancel()
        clearHistoryTimer = object : CountDownTimer(10_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                val secRemaining = (millisUntilFinished / 1000) + 1
                binding.btnConfirmClearHistory.text = "Wait (${secRemaining}s)"
                binding.tvCountdownNotice.text =
                    "Please wait ${secRemaining}s to confirm deleting entire history."
            }

            override fun onFinish() {
                binding.btnConfirmClearHistory.isEnabled = true
                binding.btnConfirmClearHistory.isClickable = true
                binding.btnConfirmClearHistory.isFocusable = true
                binding.btnConfirmClearHistory.alpha = 1.0f
                binding.btnConfirmClearHistory.text = "🗑️ Delete All"
                binding.tvCountdownNotice.text = "You can now permanently delete all history."
                binding.btnConfirmClearHistory.requestFocus()
            }
        }.start()
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
                    binding.rvHistoryList.requestFocus()
                }
            } else {
                binding.playerTouchOverlay.requestFocus()
            }
            return true
        }
        return false
    }

    private fun openSearchShelf() {
        // Auto-pause video on home page and cancel any background feed searches
        feedLoadJob?.cancel()
        ytPlayer?.pause()

        binding.shelfOverlay.visibility = View.VISIBLE
        binding.searchHeader.visibility = View.VISIBLE
        binding.historyHeader.visibility = View.GONE
        binding.rvShelf.visibility = View.VISIBLE
        binding.rvHistoryList.visibility = View.GONE

        shelfAdapter.updateList(currentSearchFeed)
        binding.etSearchInput.requestFocus()
        showSoftKeyboard(binding.etSearchInput)
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
        // Auto-pause video on home page and cancel any background feed searches
        feedLoadJob?.cancel()
        ytPlayer?.pause()

        binding.shelfOverlay.visibility = View.VISIBLE
        binding.searchHeader.visibility = View.GONE
        binding.historyHeader.visibility = View.VISIBLE
        binding.rvShelf.visibility = View.GONE
        binding.rvHistoryList.visibility = View.VISIBLE

        refreshHistoryList()
        binding.rvHistoryList.requestFocus()
    }

    private fun refreshHistoryList() {
        val history = dbHelper.getHistory()
        currentHistoryFeed.clear()
        currentHistoryFeed.addAll(history)
        historyAdapter.updateList(currentHistoryFeed)
    }

    private fun isHomeScreen(): Boolean {
        return !binding.menuDrawer.isVisible &&
                !binding.shelfOverlay.isVisible &&
                !binding.exitDialog.isVisible &&
                !binding.clearHistoryDialog.isVisible &&
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
            hideSoftKeyboard(binding.etSearchInput)
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
                    if (!binding.shelfOverlay.isVisible &&
                        !binding.menuDrawer.isVisible &&
                        !binding.exitDialog.isVisible &&
                        !binding.clearHistoryDialog.isVisible
                    ) {
                        togglePlayPause()
                    }
                }
            }
            if (binding.shelfOverlay.isVisible ||
                binding.menuDrawer.isVisible ||
                binding.exitDialog.isVisible ||
                binding.clearHistoryDialog.isVisible
            ) {
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
        if (!binding.shelfOverlay.isVisible &&
            !binding.menuDrawer.isVisible &&
            !binding.exitDialog.isVisible &&
            !binding.clearHistoryDialog.isVisible &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
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
            // Priority 0: If Clear History Dialog is open -> Close Clear History Dialog
            if (binding.clearHistoryDialog.isVisible) {
                if (event.action == KeyEvent.ACTION_UP) {
                    clearHistoryTimer?.cancel()
                    binding.clearHistoryDialog.visibility = View.GONE
                    binding.btnClearAllHistory.requestFocus()
                }
                return true
            }

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
                    hideSoftKeyboard(binding.etSearchInput)
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
        clearHistoryTimer?.cancel()
        binding.youtubePlayerView.release()
    }
}
