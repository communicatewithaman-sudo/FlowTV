# YouTok (FlowTV / TikTube) - Complete Master Requirements Specification

> **Project Name:** YouTok (formerly FlowTV / TikTube)  
> **Platform:** Android TV (Leanback / S905X Amlogic SoC, Android 7.1.2 Nougat, 1GB RAM)  
> **Target Architecture:** Universal Single APK (`armeabi-v7a` 32-bit + `arm64-v8a` 64-bit)  
> **Core Engine:** SmartTube `MediaServiceCore` + Custom ExoPlayer (100% Unaltered Playback Pipeline)  
> **Design Philosophy:** TikTok-style continuous TV experience, ultra-minimal bloat-free UI, YouTube Red theme (`#E50914`).

---

## 1. Core Architecture & Playback Guarantee

* **100% Guaranteed YouTube Playback:**
  * Must utilize SmartTube's battle-tested video loading and playback engine (`MediaServiceCore`, custom ExoPlayer, DASH/HLS stream decoders, live JS `n-sig` cipher resolver, and YouTube `poToken` attestation).
  * Absolutely no bot-gate failures, no infinite playback skipping loops, and no title-only loading without video.
  * The playback core remains completely intact and unmodified while all surrounding UI, navigation, and menu layers are custom-tailored.

* **Single Universal Signed APK (`YouTok.apk`):**
  * Outputs exactly one universal APK supporting both 32-bit (`armeabi-v7a`) and 64-bit (`arm64-v8a`).
  * No separate ABI split files (no 3 separate packages).
  * Packaged with fallback signature (`signingConfigs.debug`) so Android 7.1.2 package installer never throws *"Package appears to be invalid"* or parsing errors.
  * Published directly as raw `YouTok.apk` on GitHub Releases (never zipped).

---

## 2. Overall Visual Look & Branding

* **Identity:** Rebranded entirely to **YouTok** (no "beta" suffix or SmartTube references in public UI).
* **Color Palette:**
  * Primary Accent: YouTube / TikTok Red (`#E50914`).
  * Background: Pitch Black (`#0A0A0A`) / Dark Glass (`#141414`).
  * Text / Icons: Pure White (`#FFFFFF`) with subdued secondary gray (`#AAAAAA`).
* **Icons & TV Launcher Banner:**
  * Dark rounded squircle (`#181818`) with vibrant red badge and bold white **"Y"** emblem.
  * 320x180 Android TV Leanback launcher banner with the **YouTok** emblem and typography.
* **Loading Animation:**
  * Modern, clean, circular rotating spinner (no text).
  * Strict removal of all obstructive loading text banners (e.g. *"Please wait while data is loading..."*, *"Searching for next video..."*).

---

## 3. TikTok-Style Continuous TV Feed Behavior

* **Infinite Continuous Video Feed:**
  * Videos play automatically full-screen in a continuous feed.
  * Up/Down remote navigation moves immediately to the next or previous video smoothly, mimicking TikTok/Shorts on TV.
* **Single Unified View Mode (No Split Normal/Fullscreen Mode):**
  * Abolished confusing dual modes (no manual switching between normal mode and full screen mode).
  * The video always plays full-screen.
  * The title, channel information, timeline, and controls overlay appear for exactly **3 seconds** upon launch, seeking, or play/pause, and then automatically fade out (even if playback is paused).
* **Stop Auto-Search When Leaving Feed:**
  * If the player was searching for the next video or buffering in the background, searching halts when navigating away from Home and resumes only upon returning.

---

## 4. Remote Control Button Mappings & Interactions

| Remote Button | State / Context | Required Behavior |
| :--- | :--- | :--- |
| **D-Pad UP** | Main Feed | Swipe to previous video smoothly (TikTok style). |
| **D-Pad DOWN** | Main Feed | Swipe to next video smoothly (TikTok style). |
| **D-Pad LEFT** | Main Feed | Seek backward (-10s / -5s); displays timeline overlay for 3s. |
| **D-Pad RIGHT** | Main Feed | Seek forward (+10s / +5s); displays timeline overlay for 3s. |
| **OK / Center Click** | Main Feed | Toggle Play / Pause; displays overlay for 3s. |
| **POWER (Hold / Long-press)** | Anywhere | **Does nothing.** Consumes event so TV viewing is never interrupted. |
| **BACK (Single Press)** | Inside Menu / Search / History | Returns to the previous screen or back to Home. |
| **BACK (From Video in Search)** | Video opened via Search | Returns directly to the Search results screen with keyboard ready. |
| **BACK (Double Press on Home)**| Root Home Feed | Pauses video and displays custom **Exit Confirmation Dialog**. |

---

## 5. Navigation, Auto-Pause & Auto-Resume Rules

* **Exit Dialog Auto-Pause & Resume:**
  * Double-pressing Back triggers the exit dialog: **the background video immediately pauses**.
  * If the user selects **Cancel** or presses **Back** to dismiss the dialog: **the video automatically resumes playback**.
  * If the user selects **Confirm**: the app cleanly terminates.
* **Section Navigation Auto-Pause & Resume:**
  * Navigating away from Home (to **Search**, **History**, or **Settings**): **video immediately pauses**.
  * Returning back to **Home**: **video automatically resumes playback**.
* **Search Navigation Flow:**
  * Entering the Search tab automatically pops up the on-screen TV Leanback keyboard.
  * When a video is selected and played from Search results, pressing the remote's **Back** button returns directly to the Search results screen.

---

## 6. Menu Drawer & Bloat-Free UI Layout

* **Strip Down SmartTube Clutter:**
  * Remove all dozens of unused sections (Shorts shelf, Trending, Kids Home, Gaming, News, Music, Sports, Live, Channel Uploads, Notifications, Blocked Channels, Playlists, Add Section).
* **Essential 4 Sidebar Sections Only:**
  1. **Home** (Continuous feed)
  2. **Subscriptions** (Channels followed)
  3. **History** (Watch history list)
  4. **Settings** (Essential configuration)
* **Streamlined Settings Menu:**
  * Keep only essential options for normal users:
    1. **Accounts** (Sign in / Sign out / QR Code)
    2. **Video Player** (Resolution / Quality presets)
    3. **Language & Country** (Content region)
    4. **Subtitles** (Styling & language)
    5. **Remote Control / Cast** (Link TV code)
    6. **About YouTok** (Clean app version and details)
  * Remove developer options, deep SponsorBlock settings, AFR toggles, and backup/restore menus.

---

## 7. Watch History Management

* **Clean List Layout:**
  * Watch history presented as a vertical, easily scrollable list.
* **Single-Item Delete Button:**
  * Every watched video entry features a direct single-item delete action labeled with a trash icon (**🗑️ Remove from history**).
* **Delete All History (10-Second Countdown):**
  * Selecting "Clear History" opens a dedicated confirmation dialog.
  * Runs a visible **10-second countdown** before clearing.
  * User can click **Cancel** at any second to safely abort, or confirm immediately.

---

## 8. Tips Management (No Toasts, In-Menu Carousel)

* **No Floating Toast Popups:**
  * Completely eliminated floating toast popups on the TV screen (no *"Press again to exit"*, no *"Hold to refresh"*).
* **In-Menu Tips Carousel:**
  * All tips, shortcuts, and remote guidelines are neatly placed inside the menu drawer / box.
  * **Interactive Carousel:** Displays one tip at a time; pressing **Left** or **Right** arrow on the remote replaces the active tip with the previous or next tip cleanly.
