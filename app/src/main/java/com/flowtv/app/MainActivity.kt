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
import com.flowtv.app.adapter.CommentsAdapter
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

    // Remote navigation & double-tap state
    private var lastBackPressTime = 0L
    private val handler = Looper.myLooper()?.let { Handler(it) } ?: Handler(Looper.getMainLooper())
    private var scrubHideRunnable: Runnable? = null
    private var nowPlayingHideRunnable: Runnable? = null
    private var okLongPressJob: Job? = null
    private var isOkPressed = false

    // Quality & Subtitles settings
    private val qualityOptions = listOf("Auto", "720p", "1080p")
    private var qualityIndex = 0
    private var subtitlesEnabled = false

    // Adapters
    private lateinit var shelfAdapter: ShelfAdapter
    private lateinit var commentsAdapter: CommentsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = HistoryDatabaseHelper(this)

        initPlayer()
        setupUI()
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
                            Player.STATE_BUFFERING -> binding.topProgressBar.visibility = View.VISIBLE
                            Player.STATE_READY -> {
                                binding.topProgressBar.visibility = View.GONE
                                updateScrubBar()
                                saveWatchHistory()
                            }
                            Player.STATE_ENDED -> playNextVideo()
                            Player.STATE_IDLE -> binding.topProgressBar.visibility = View.GONE
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // Point 4: Auto-skip forward on error within 5 seconds without freezing
                        binding.topProgressBar.visibility = View.GONE
                        handler.postDelayed({ playNextVideo() }, 2000)
                    }
                })
            }
        binding.playerView.player = player
    }

    private fun setupUI() {
        // Shelf RecyclerView (Search & History)
        shelfAdapter = ShelfAdapter(emptyList()) { video, _ ->
            // Click video from shelf: start full-screen playback with that queue!
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

        // Comments RecyclerView
        commentsAdapter = CommentsAdapter(emptyList())
        binding.rvComments.layoutManager = LinearLayoutManager(this)
        binding.rvComments.adapter = commentsAdapter

        // Menu Button: Search
        binding.btnMenuSearch.setOnClickListener {
            binding.menuDrawer.visibility = View.GONE
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
            binding.btnMenuSubtitles.text = if (subtitlesEnabled) "💬   Subtitles: English" else "💬   Subtitles: Off"
        }

        // Menu Button: History
        binding.btnMenuHistory.setOnClickListener {
            binding.menuDrawer.visibility = View.GONE
            openHistoryShelf()
        }

        // Menu Card: Info Card -> Click opens Description & Comments
        binding.cardVideoInfo.setOnClickListener {
            binding.menuDrawer.visibility = View.GONE
            openCommentsDrawer()
        }

        // Menu Card: "View Channel" Pill -> Dive into creator's videos
        binding.btnViewChannel.setOnClickListener {
            val current = getCurrentVideo() ?: return@setOnClickListener
            binding.menuDrawer.visibility = View.GONE
            diveIntoChannel(current.channelId)
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
        binding.topProgressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val feed = YouTubeExtractor.getRecommendedFeed()
            if (feed.isNotEmpty()) {
                startNewFeed(feed, 0)
            } else {
                Toast.makeText(this@MainActivity, "Fetching videos...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startNewFeed(newFeed: List<VideoItem>, startIndex: Int = 0) {
        videoFeed.clear()
        videoFeed.addAll(newFeed)
        currentFeedIndex = startIndex.coerceIn(0, (videoFeed.size - 1).coerceAtLeast(0))
        playCurrentVideo()
    }

    private fun playCurrentVideo() {
        val video = getCurrentVideo() ?: return

        // 1. Show 3-second Now Playing pill (Apple-style HUD)
        showNowPlayingPill(video)

        // 2. Fetch live likes, dislikes, and comments stats
        updateMenuStats(video)

        // 3. Resolve stream URL and play
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
            } else {
                // If stream failed, auto-skip
                playNextVideo()
            }
        }
    }

    private fun playNextVideo() {
        if (currentFeedIndex < videoFeed.size - 1) {
            currentFeedIndex++
            playCurrentVideo()
        } else {
            // End of feed -> refresh
            loadRecommendedFeed()
        }
    }

    private fun playPreviousVideo() {
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

    private fun showNowPlayingPill(video: VideoItem) {
        binding.tvNowPlayingTitle.text = video.title
        binding.tvNowPlayingChannel.text = video.channelTitle
        binding.nowPlayingPill.alpha = 1.0f
        binding.nowPlayingPill.visibility = View.VISIBLE

        nowPlayingHideRunnable?.let { handler.removeCallbacks(it) }
        nowPlayingHideRunnable = Runnable {
            binding.nowPlayingPill.animate().alpha(0f).setDuration(400).withEndAction {
                binding.nowPlayingPill.visibility = View.GONE
            }.start()
        }
        handler.postDelayed(nowPlayingHideRunnable!!, 3000)
    }

    private fun updateMenuStats(video: VideoItem) {
        binding.tvMenuVideoTitle.text = video.title
        binding.tvMenuChannelName.text = video.channelTitle

        lifecycleScope.launch {
            val (likes, dislikes) = YouTubeExtractor.getLikesAndDislikes(video.videoId)
            video.likes = likes
            video.dislikes = dislikes
            binding.tvLikesCount.text = "👍 $likes"
            binding.tvDislikesCount.text = "👎 $dislikes"
            binding.tvCommentsCount.text = "💬 1.2K"
        }
    }

    // ==========================================
    // SCRUBBING & SEEK BAR OVERLAY
    // ==========================================

    private fun seekRelative(offsetMs: Long) {
        val p = player ?: return
        val newPos = (p.currentPosition + offsetMs).coerceIn(0, p.duration.coerceAtLeast(0))
        p.seekTo(newPos)

        // Show hairline scrub bar overlay
        binding.scrubOverlay.visibility = View.VISIBLE
        val sign = if (offsetMs >= 0) "+10s" else "-10s"
        val curM = (newPos / 1000) / 60
        val curS = (newPos / 1000) % 60
        val totM = (p.duration / 1000) / 60
        val totS = (p.duration / 1000) % 60
        binding.tvScrubTime.text = String.format("%s   %02d:%02d / %02d:%02d", sign, curM, curS, totM, totS)
        binding.scrubProgressBar.progress = ((newPos.toFloat() / p.duration.coerceAtLeast(1)) * 1000).toInt()

        scrubHideRunnable?.let { handler.removeCallbacks(it) }
        scrubHideRunnable = Runnable { binding.scrubOverlay.visibility = View.GONE }
        handler.postDelayed(scrubHideRunnable!!, 1500)
    }

    private fun updateScrubBar() {
        val p = player ?: return
        if (p.duration > 0) {
            binding.scrubProgressBar.progress = ((p.currentPosition.toFloat() / p.duration) * 1000).toInt()
        }
    }

    // ==========================================
    // OVERLAYS (MENU, COMMENTS, SHELF, EXIT)
    // ==========================================

    private fun toggleMenuDrawer() {
        if (binding.menuDrawer.isVisible) {
            binding.menuDrawer.visibility = View.GONE
        } else {
            closeAllOverlays()
            binding.menuDrawer.visibility = View.VISIBLE
            binding.btnMenuSearch.requestFocus()
        }
    }

    private fun openCommentsDrawer() {
        val video = getCurrentVideo() ?: return
        binding.commentsDrawer.visibility = View.VISIBLE
        binding.tvFullDescription.text = video.description.ifEmpty { video.title }

        lifecycleScope.launch {
            val comments = YouTubeExtractor.getComments(video.videoId)
            commentsAdapter.updateComments(comments)
        }
    }

    private fun openSearchShelf() {
        binding.shelfOverlay.visibility = View.VISIBLE
        binding.searchHeader.visibility = View.VISIBLE
        binding.tvShelfTitle.text = "Search YouTube"
        binding.etSearchInput.requestFocus()
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) return
        binding.topProgressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val results = YouTubeExtractor.searchVideos(query)
            binding.topProgressBar.visibility = View.GONE
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

    private fun diveIntoChannel(channelId: String) {
        binding.topProgressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val channelFeed = YouTubeExtractor.getChannelVideos(channelId)
            binding.topProgressBar.visibility = View.GONE
            if (channelFeed.isNotEmpty()) {
                startNewFeed(channelFeed, 0)
                Toast.makeText(this@MainActivity, "Playing channel videos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun closeAllOverlays(): Boolean {
        var closed = false
        if (binding.menuDrawer.isVisible) {
            binding.menuDrawer.visibility = View.GONE
            closed = true
        }
        if (binding.commentsDrawer.isVisible) {
            binding.commentsDrawer.visibility = View.GONE
            closed = true
        }
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

        // 1. Long-press OK detection
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!isOkPressed) {
                    isOkPressed = true
                    okLongPressJob = lifecycleScope.launch {
                        delay(600) // 600ms hold triggers Menu
                        toggleMenuDrawer()
                    }
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                isOkPressed = false
                if (okLongPressJob?.isActive == true) {
                    okLongPressJob?.cancel()
                    // Regular tap OK -> Play / Pause if no overlay open
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

        // 2. Menu button -> Opens frosted menu
        if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
            toggleMenuDrawer()
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
                // Long-press Back -> Refresh feed
                if (event.isLongPress) {
                    Toast.makeText(this, "Refreshing Feed…", Toast.LENGTH_SHORT).show()
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
                binding.commentsDrawer.isVisible ||
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
        player?.release()
        player = null
    }
}
