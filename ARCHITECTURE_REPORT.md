# YouTok — Phase 1 Technical Architecture Report

**Project:** YouTok  
**Foundation:** SmartTubeNext Codebase  
**Target Hardware:** Amlogic S905X, Android 7.1.2 (API 25), 1 GB RAM  
**Target Architecture:** Single Universal APK (`armeabi-v7a` + `arm64-v8a`)  
**Design Philosophy:** Apple (polish/animations) + GNOME (minimalism/simplicity) + TikTok (continuous vertical feed)  
**Deliverable Type:** Phase 1 Architecture Inspection & Categorization Report  

---

## Executive Summary

Following a deep source-code inspection of the SmartTubeNext repository, this report establishes the definitive technical boundary between:
1. **The Protected Playback Engine** (which must be preserved in its entirety without alteration).
2. **The Bloated / Legacy Layers** (to be permanently stripped out: accounts, subscriptions, playlists, channels, notifications, sponsorblock UI, dense settings, on-screen toasts, hints, tips).
3. **Reusable Core Utilities** (local database, local watch history, search backend, video preset selectors, remote key translators, background task managers).
4. **Rebuilt YouTok Experience** (TikTok-style vertical feed combining regular videos and Shorts, seamless right-side sliding sidebar with video scale-left animation, single view self-hiding overlay that remains visible while paused, circular loading spinner with zero text, centered red play-triangle brand emblem).

---

## 1. Complete Source Code Breakdown & Module Mapping

```
flow-tv/
├── MediaServiceCore/              <-- [PROTECTED] Extraction, deciphering, Innertube, poToken
│   ├── youtubeapi/                <-- Innertube API, videoinfo, formatbuilders, decipher engine
│   └── mediaserviceinterfaces/     <-- Abstraction contracts for media items, groups, playback
├── exoplayer-amzn-2.10.6/         <-- [PROTECTED] Custom ExoPlayer fork for Android TV / S905X
│   └── library/                   <-- Core, DASH, HLS, UI, Ext-VP9, Ext-Opus, Ext-FFmpeg
├── SharedModules/                 <-- [REUSE & PROTECTED] Shared utils, Rx, IO helpers
│   ├── sharedutils/               <-- Helpers, RxHelper, Locale, Preferences, Loggers
│   └── commons-io-2.8.0/          <-- File operations
├── common/                        <-- [MIXED: PROTECT ENGINE, STRIP UI, REBUILD FEED]
│   ├── app/models/playback/       <-- [PROTECT] PlayerUI, PlayerEventListener, VideoState
│   ├── app/presenters/            <-- [REBUILD/STRIP] BrowsePresenter, VideoMenuPresenter
│   ├── exoplayer/                 <-- [PROTECT] Custom TrackSelectors, Renderers, Manifests
│   └── prefs/                     <-- [STRIP & REBUILD] Clean local preferences, PlayerData
├── smarttubetv/                   <-- [REBUILD] User-facing Android TV application layer
│   ├── ui/main/                   <-- SplashActivity, MainApplication
│   ├── ui/browse/                 <-- Legacy Grid/Row BrowseActivity (REPLACE with Feed)
│   ├── ui/playback/               <-- PlaybackActivity, PlaybackFragment (ADAPT for Feed)
│   ├── ui/search/                 <-- SearchTagsActivity, SearchTagsFragment
│   └── res/                       <-- Layouts, Drawables, Mipmaps, Values
└── leanback-1.0.0/                <-- AndroidX Leanback TV widgets
```

---

## 2. Protected Playback Engine (DO NOT MODIFY)

These modules, packages, and classes constitute the irreplaceable streaming and decoding backbone. They will remain isolated and unmodified to guarantee 100% video playback without bot-blocking or infinite skipping loops.

### 2.1 YouTube Extraction & Cipher Deciphering (`MediaServiceCore/youtubeapi`)
* **`videoinfo/` (V1, V2, kt):**
  * `VideoInfoService.java`, `VideoInfoServiceBase.java`: Resolves streaming URLs, adaptive audio/video tracks, and DASH formats.
  * `VideoInfoApi.java`, `DashInfoApi.java`: Innertube endpoint contracts (`/player`).
* **`formatbuilders/`:**
  * `DashFormatBuilder.java`, `HlsFormatBuilder.java`: Assembles valid MPD manifests and HLS m3u8 playlists on-the-fly for ExoPlayer.
* **`service/internal/` & `innertube/`:**
  * `InnertubeService.java`, `InnertubeCipher.java`: Extracts and executes JavaScript cipher routines (`n-sig` transformations) required by YouTube.
  * `PoTokenProvider.java`: Handles proof-of-origin tokens and bot attestation headers.
* **`service/YouTubeMediaItemService.java` & `YouTubeContentService.java`:**
  * Core engine for extracting video metadata and raw stream segments.

### 2.2 Adaptive Streaming Engine (`exoplayer-amzn-2.10.6` & `common/exoplayer`)
* **Amazon ExoPlayer Fork:**
  * `com.google.android.exoplayer2.source.dash.DashMediaSource`: Custom DASH period/chunk extractor.
  * `com.google.android.exoplayer2.trackselection.DefaultTrackSelector`: Adaptive bitrate and resolution engine.
  * `com.google.android.exoplayer2.video.MediaCodecVideoRenderer`: Hardware-accelerated decoding tuned for Amlogic S905X video decoders.
* **`common/exoplayer/`:**
  * `versions/renderer/`: Custom hardware surface renderers for 4K/1080p/720p/VP9/AV1.
  * `versions/selector/FormatItem.java`: Video and audio format definitions.
  * `controller/PlayerEngine.java`: Bridge between ExoPlayer events and the UI.

---

## 3. Components to REMOVE (Zero-Clutter Policy)

To satisfy the "Less is more" GNOME/Apple minimalism requirement, the following components are scheduled for complete removal or deactivation:

1. **Account & Authentication Infrastructure:**
   * Remove `SignInActivity`, `SignInFragment`, `SignInPresenter`.
   * Remove `AddDeviceActivity`, `AccountSelectionPresenter`, `AccountsData`.
   * Eliminate all QR code prompts, Google login requests, and auth checks.
2. **Account-Dependent Categories & Sections:**
   * Remove Subscriptions (`TYPE_SUBSCRIPTIONS`), Playlists (`TYPE_USER_PLAYLISTS`), Watch Later, Channel Uploads (`TYPE_CHANNEL_UPLOADS`), Channel Activity (`ChannelActivity`).
   * Remove Notifications (`TYPE_NOTIFICATIONS`), Blocked Channels (`TYPE_BLOCKED_CHANNELS`).
   * Remove YouTube explore bloat: Shorts Grid, Trending, Kids Home, Gaming, News, Music, Sports, Live.
3. **Complex / Unnecessary Settings Menus:**
   * Remove Auto Frame Rate (AFR) settings presenter.
   * Remove SponsorBlock configuration and category management.
   * Remove DeArrow submission and voting settings.
   * Remove Backup & Restore (GDriveBackupWorker, LocalDriveBackupWorker).
   * Remove Developer options, debug logging, and complex buffer tweaks.
4. **On-Screen Text & Messages (Zero-Message Policy):**
   * Eliminate all toast popups (`MessageHelpers.showMessage`, `MessageHelpers.showLongMessage`).
   * Remove "Press again to exit" toast.
   * Remove "Hold to refresh" toast.
   * Remove "Searching for next video...", "Please wait...", "Loading data...".
   * Remove the previously proposed Tips and Shortcuts carousel completely.

---

## 4. Components to REUSE

These robust, battle-tested utilities from the existing codebase will be reused without modification:

1. **Local Watch History Storage:**
   * `VideoStateService.java`: Tracks video progress and stores watched history locally in SQLite without requiring a YouTube account.
2. **Search API & Query Resolvers:**
   * `YouTubeSearchService.kt`, `SearchPresenter.java`: Executes YouTube keyword searches and paginates video results cleanly.
   * `SearchTagStorage.kt`: Stores recent search queries locally.
3. **Audio & Subtitle Track Selectors:**
   * `SubtitleSettingsPresenter.java`, `HQDialogController.java`: Native dialogue menus for choosing video quality (1080p, 720p, 480p, 360p, Auto) and subtitle languages.
4. **Remote Control Key Translation:**
   * `GlobalKeyTranslator.java`, `PlayerKeyTranslator.java`: Normalizes diverse Android TV vendor remote keycodes (Sony, Shield, FireTV, Amlogic).

---

## 5. Components to REBUILD (The YouTok Experience)

The entire user-facing surface will be replaced with custom, lightweight, beautifully animated components:

### 5.1 TikTok-Style Continuous TV Feed
* **Architecture:** Full-screen video presentation that opens immediately on app startup.
* **Content Source:** Intelligent, account-free feed generator mixing regular landscape videos and YouTube Shorts seamlessly.
* **Remote Interaction:**
  * `D-Pad DOWN`: Instantly scrolls and transitions to the next video.
  * `D-Pad UP`: Scrolls back to the previous video.
  * `D-Pad LEFT / RIGHT`: 5s / 10s seek jump with subtle timeline progress bar.
  * `OK (Short Press)`: Play / Pause toggle.
  * `OK (Long Press)`: Consumed (no action).
  * `BACK (Long Press on Home)`: Resets feed, cancels existing fetch, displays circular spinner with zero text, and reloads fresh feed.
  * `BACK (Double Press on Home)`: Pauses video, presents a clean exit confirmation dialog (Cancel resumes, Confirm exits).
  * `POWER (Hold)`: Do NOT intercept (preserve system power behavior).

### 5.2 Right-Side Sliding Sidebar with Video Push Animation
* **Animation Behavior:**
  * When opened, the sidebar slides smoothly in from the **RIGHT** side over a dark translucent glass surface (`#141414` with 90% opacity).
  * Simultaneously, the active video window scales down slightly (approx. 85%) and shifts smoothly toward the **LEFT**, remaining fully visible.
  * **Playback Never Stops:** Because the Home video remains visible behind the sidebar, video playback and feed preloading continue uninterrupted.
  * When closed, the video smoothly returns to full screen.
* **Sidebar Menu Items (Only 6 Items):**
  1. 🔍 **Search**
  2. 🕒 **History**
  3. 🎞️ **Video Quality**
  4. 💬 **Subtitles**
  5. ⚙️ **Other Video Settings**
  6. ℹ️ **About YouTok**

### 5.3 Single Video View Mode & Auto-Hide Overlay
* **Single Mode:** No confusing dual modes (no manual switching between normal mode and fullscreen mode).
* **Auto-Hide Behavior:**
  * When video begins, when seeking, or when toggling play/pause: overlay (video title, channel name, timeline, controls) appears for **exactly 3 seconds**, then fades out.
  * **Critical Requirement:** If the video is **paused**, the overlay **remains visible indefinitely** until playback is resumed.

### 5.4 Page Navigation & Playback Lifecycle
* **Home Feed Visible:** Home video plays, background feed preloading active.
* **Sidebar Open:** Home video plays continuously in scaled window.
* **Navigating to Search / History / Settings:**
  * Home feed playback immediately pauses.
  * Background preloading and video searches halt immediately.
  * Active video and playback position are preserved in memory.
* **Returning to Home:**
  * Previous video automatically resumes playback at the exact saved position.
  * Background feed preloading resumes.
* **Playing a Video from Search:**
  * Selected search result plays in dedicated player.
  * Pressing `BACK` closes player and returns directly to the Search screen with query and results intact.

### 5.5 Watch History with 10-Second Countdown
* **Vertical List Layout:** Stored locally on-device.
* **Individual Delete:** Trash icon (**🗑️ Remove from history**) on each item.
* **Clear All History:** Opens a confirmation dialog displaying a **10-second countdown timer**. If the user clicks **Cancel** at any second, clearing is aborted; when the 10 seconds elapse or if confirmed, history is wiped.

### 5.6 Visual Polish & Zero-Message Design
* **Logo:** Solid YouTube Red (`#E50914`) background with ONE large white YouTube-style play-triangle symbol in the center. Perfectly centered, no letter Y, no badge, no text, no gradients.
* **TV Banner:** Professional 320 × 180 Android TV Leanback launcher banner.
* **Zero-Message Policy:** Strict elimination of toasts, tips, and loading text. Only a clean, hardware-accelerated circular spinner with red accent is shown.

---

## 6. Resource & Hardware Budget (Amlogic S905X / 1 GB RAM)

| Constraint | Limit | Implementation Strategy |
| :--- | :--- | :--- |
| **Max Heap Usage** | < 120 MB | Limit image caching to 20 MB; recycle ExoPlayer surface textures. |
| **Active Player Instances** | Exactly 1 | Single ExoPlayer instance shared across feed items to prevent OOM. |
| **Background Threads** | Max 2 | Cancel background feed network requests immediately when leaving Home. |
| **APK File Size** | < 45 MB | Remove unused drawables, icons, and localized XML files for stripped sections. |
| **Native ABIs** | Universal | Package only `armeabi-v7a` and `arm64-v8a` `.so` libraries. |

---

## 7. Next Steps: Roadmap to Phase 2 & Beyond

1. **Phase 1 (Complete):** Architecture Inspection Report delivered and approved.
2. **Phase 2 (Architecture Design):** Create clean component boundaries separating the protected playback core from the new `TikTokFeedManager`, `YouTokSidebarView`, `YouTokOverlayController`, and `YouTokHistoryManager`.
3. **Phase 3 (Design System):** Generate the new solid red play-triangle logo, 320x180 TV banner, and dark glass style sheets.
4. **Phase 4 (Implementation):** Implement the continuous feed, sidebar sliding/video-scale animation, single view overlay, and zero-message circular spinner.
5. **Phase 5-8 (Testing & Universal Release):** Verify remote bindings, continuous playback, 1 GB RAM stability, and release signed universal `YouTok.apk`.
