package com.flowtv.app.service

import com.flowtv.app.model.VideoItem
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object YouTubeExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(14, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val YT_URL  = "https://www.youtube.com/youtubei/v1"
    // Public InnerTube key — same one used by web/app clients in the open
    private const val YT_KEY  = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val RYD_URL = "https://returnyoutubedislikeapi.com/votes?videoId="
    private val JSON_MT = "application/json; charset=utf-8".toMediaType()

    // ----- Invidious / Piped fallback lists -----
    private val INVIDIOUS = listOf(
        "https://yewtu.be",
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://invidious.drgns.space",
        "https://inv.tux.pizza"
    )
    private val PIPED = listOf(
        "https://api.piped.private.coffee",
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de"
    )

    // ========================================
    // PUBLIC API
    // ========================================

    suspend fun getRecommendedFeed(): List<VideoItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<VideoItem>()

        // Seed with shorts-first queries for instant playback on a fresh install
        for (q in listOf("#shorts", "shorts viral 2024", "music 2024", "funny clips")) {
            if (result.size >= 30) break
            val videos = searchVideos(q)
            result.addAll(videos.filter { v -> result.none { it.videoId == v.videoId } })
        }

        // Hard-coded fallbacks if everything fails
        if (result.isEmpty()) {
            result.addAll(listOf(
                VideoItem("kJQP7kiw5Fk", "Luis Fonsi - Despacito",                "Luis Fonsi",  "", "https://i.ytimg.com/vi/kJQP7kiw5Fk/hqdefault.jpg", 282),
                VideoItem("JGwWNGJdvx8", "Shape of You - Ed Sheeran",             "Ed Sheeran",  "", "https://i.ytimg.com/vi/JGwWNGJdvx8/hqdefault.jpg", 234),
                VideoItem("OPf0YbXqDm0", "Mark Ronson - Uptown Funk ft. Bruno Mars","VEVO",      "", "https://i.ytimg.com/vi/OPf0YbXqDm0/hqdefault.jpg", 270),
                VideoItem("hT_nvWreIhg", "OneRepublic - Counting Stars",           "OneRepublic", "", "https://i.ytimg.com/vi/hT_nvWreIhg/hqdefault.jpg", 258),
                VideoItem("09R8_2nJtjg", "PSY - Gangnam Style",                   "officialpsy", "", "https://i.ytimg.com/vi/09R8_2nJtjg/hqdefault.jpg", 253)
            ))
        }

        result.distinctBy { it.videoId }
    }

    /**
     * Resolve a playable stream URL using multiple tiers.
     *
     * Tier 1 – iOS InnerTube client  → returns hlsManifestUrl (m3u8, no cipher needed)
     * Tier 2 – Android InnerTube     → HLS manifest or direct mp4
     * Tier 3 – TVHTML5 embedded      → often returns plain HLS
     * Tier 4 – Invidious instances   → server-side deciphered
     * Tier 5 – Piped instances       → server-side deciphered
     */
    suspend fun resolveStreamUrl(videoId: String, @Suppress("UNUSED_PARAMETER") quality: String = "Auto"): String? =
        withContext(Dispatchers.IO) {
            iosHls(videoId)?.let { return@withContext it }
            androidHls(videoId)?.let { return@withContext it }
            tvEmbedHls(videoId)?.let { return@withContext it }
            for (host in INVIDIOUS) { invidiousUrl(videoId, host)?.let { return@withContext it } }
            for (host in PIPED)     { pipedUrl(videoId, host)?.let    { return@withContext it } }
            null
        }

    // ========================================
    // STREAM RESOLVERS
    // ========================================

    /** iOS client — YouTube almost always returns an unencrypted HLS manifest here. */
    private fun iosHls(videoId: String): String? = try {
        val body = """{"context":{"client":{"clientName":"IOS","clientVersion":"19.45.4","deviceModel":"iPhone16,2","userAgent":"com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1 like Mac OS X;)","hl":"en","gl":"US"}},"videoId":"$videoId","params":"2AMB"}"""
            .toRequestBody(JSON_MT)
        val req = Request.Builder()
            .url("$YT_URL/player?key=$YT_KEY&prettyPrint=false")
            .post(body)
            .header("User-Agent",               "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1 like Mac OS X;)")
            .header("X-YouTube-Client-Name",    "5")
            .header("X-YouTube-Client-Version", "19.45.4")
            .header("Content-Type",             "application/json")
            .build()
        val resp = client.newCall(req).execute()
        val json = JsonParser.parseString(resp.body?.string() ?: "").asJsonObject
        // Check playability first
        val status = json.getAsJsonObject("playabilityStatus")?.getAsString("status")
        if (status == "ERROR" || status == "LOGIN_REQUIRED") return null
        json.getAsJsonObject("streamingData")?.getAsString("hlsManifestUrl")?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    /** Android client — also often returns an unencrypted HLS or direct URL. */
    private fun androidHls(videoId: String): String? = try {
        val body = """{"context":{"client":{"clientName":"ANDROID","clientVersion":"19.44.38","androidSdkVersion":34,"userAgent":"com.google.android.youtube/19.44.38 (Linux; U; Android 14; en_US) gzip","hl":"en","gl":"US"}},"videoId":"$videoId","params":"2AMB"}"""
            .toRequestBody(JSON_MT)
        val req = Request.Builder()
            .url("$YT_URL/player?key=$YT_KEY&prettyPrint=false")
            .post(body)
            .header("User-Agent",               "com.google.android.youtube/19.44.38 (Linux; U; Android 14; en_US) gzip")
            .header("X-YouTube-Client-Name",    "3")
            .header("X-YouTube-Client-Version", "19.44.38")
            .header("Content-Type",             "application/json")
            .build()
        val json = JsonParser.parseString(client.newCall(req).execute().body?.string() ?: "").asJsonObject
        val status = json.getAsJsonObject("playabilityStatus")?.getAsString("status")
        if (status == "ERROR" || status == "LOGIN_REQUIRED") return null
        val sd = json.getAsJsonObject("streamingData") ?: return null
        // HLS manifest preferred (no cipher needed)
        sd.getAsString("hlsManifestUrl")?.takeIf { it.isNotEmpty() }
            ?: sd.getAsJsonArray("formats")?.firstNotNullOfOrNull { f ->
                val url = f.asJsonObject.getAsString("url")
                url?.takeIf { it.isNotEmpty() && !f.asJsonObject.has("signatureCipher") }
            }
    } catch (e: Exception) { null }

    /** TV embedded player client — often bypasses cipher for music/public content. */
    private fun tvEmbedHls(videoId: String): String? = try {
        val body = """{"context":{"client":{"clientName":"TVHTML5_SIMPLY_EMBEDDED_PLAYER","clientVersion":"2.0","hl":"en","gl":"US"}},"videoId":"$videoId"}"""
            .toRequestBody(JSON_MT)
        val req = Request.Builder()
            .url("$YT_URL/player?key=$YT_KEY&prettyPrint=false")
            .post(body)
            .header("User-Agent",    "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/538.1")
            .header("Content-Type",  "application/json")
            .build()
        val json = JsonParser.parseString(client.newCall(req).execute().body?.string() ?: "").asJsonObject
        val status = json.getAsJsonObject("playabilityStatus")?.getAsString("status")
        if (status == "ERROR" || status == "LOGIN_REQUIRED") return null
        val sd = json.getAsJsonObject("streamingData") ?: return null
        sd.getAsString("hlsManifestUrl")?.takeIf { it.isNotEmpty() }
            ?: sd.getAsJsonArray("formats")?.firstNotNullOfOrNull { f ->
                val url = f.asJsonObject.getAsString("url")
                url?.takeIf { it.isNotEmpty() && !f.asJsonObject.has("signatureCipher") }
            }
    } catch (e: Exception) { null }

    /** Query an Invidious instance — it deciphers streams server-side. */
    private fun invidiousUrl(videoId: String, host: String): String? = try {
        val req = Request.Builder()
            .url("$host/api/v1/videos/$videoId?fields=formatStreams,hlsUrl,adaptiveFormats")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 11)")
            .build()
        val res = client.newCall(req).execute()
        if (!res.isSuccessful) return null
        val json = JsonParser.parseString(res.body?.string() ?: "").asJsonObject
        // Prefer HLS
        json.getAsString("hlsUrl")?.takeIf { it.isNotEmpty() }
            // Then a muxed MP4 stream
            ?: json.getAsJsonArray("formatStreams")?.firstNotNullOfOrNull { s ->
                s.asJsonObject.getAsString("url")?.takeIf { it.isNotEmpty() }
            }
            // Then any adaptive video+audio
            ?: json.getAsJsonArray("adaptiveFormats")?.firstNotNullOfOrNull { s ->
                val obj  = s.asJsonObject
                val type = obj.getAsString("type") ?: ""
                if (!type.startsWith("video/mp4")) null
                else obj.getAsString("url")?.takeIf { it.isNotEmpty() }
            }
    } catch (e: Exception) { null }

    /** Query a Piped instance — also deciphers server-side. */
    private fun pipedUrl(videoId: String, host: String): String? = try {
        val req = Request.Builder()
            .url("$host/streams/$videoId")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val res = client.newCall(req).execute()
        if (!res.isSuccessful) return null
        val json = JsonParser.parseString(res.body?.string() ?: "").asJsonObject
        json.getAsString("hls")?.takeIf { it.isNotEmpty() }
            ?: json.getAsJsonArray("videoStreams")?.firstNotNullOfOrNull { vs ->
                val obj = vs.asJsonObject
                if (obj.get("videoOnly")?.asBoolean == true) null
                else obj.getAsString("url")?.takeIf { it.isNotEmpty() }
            }
    } catch (e: Exception) { null }

    // ========================================
    // SEARCH
    // ========================================

    suspend fun searchVideos(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<VideoItem>()
        try {
            val safeQ = query.replace("\"", "\\\"")
            val body = """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20241028.00.00","hl":"en","gl":"US"}},"query":"$safeQ"}"""
                .toRequestBody(JSON_MT)
            val req = Request.Builder()
                .url("$YT_URL/search?key=$YT_KEY&prettyPrint=false")
                .post(body)
                .header("User-Agent",   "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Content-Type", "application/json")
                .build()
            val body2 = client.newCall(req).execute().body?.string() ?: ""
            if (body2.isNotEmpty()) parseVideoItems(JsonParser.parseString(body2), list)
        } catch (e: Exception) { e.printStackTrace() }
        list
    }

    // ========================================
    // LIKES / DISLIKES
    // ========================================

    suspend fun getLikesAndDislikes(videoId: String): Pair<String, String> = withContext(Dispatchers.IO) {
        try {
            val res = client.newCall(Request.Builder().url("$RYD_URL$videoId").build()).execute()
            val obj = JsonParser.parseString(res.body?.string() ?: "").asJsonObject
            Pair(formatCount(obj.get("likes")?.asLong ?: 0), formatCount(obj.get("dislikes")?.asLong ?: 0))
        } catch (e: Exception) { Pair("—", "—") }
    }

    // ========================================
    // JSON PARSING HELPERS
    // ========================================

    private fun parseVideoItems(element: JsonElement, out: MutableList<VideoItem>) {
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                when {
                    obj.has("videoRenderer") -> {
                        val vr  = obj.getAsJsonObject("videoRenderer")
                        val vid = vr.getAsString("videoId") ?: return
                        if (vid.isEmpty() || out.any { it.videoId == vid }) return
                        val title   = vr.getAsJsonObject("title")?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.getAsString("text") ?: "Untitled"
                        val channel = vr.getAsJsonObject("ownerText")?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.getAsString("text")
                            ?: vr.getAsJsonObject("shortBylineText")?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.getAsString("text") ?: "Channel"
                        val thumbs  = vr.getAsJsonObject("thumbnail")?.getAsJsonArray("thumbnails")
                        val thumb   = if (thumbs != null && thumbs.size() > 0) thumbs.get(thumbs.size()-1).asJsonObject.getAsString("url") ?: "" else "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
                        val dur     = parseDuration(vr.getAsJsonObject("lengthText")?.getAsString("simpleText") ?: "0:00")
                        out.add(VideoItem(vid, title, channel, "", thumb, dur, isShort = dur in 1..90))
                    }
                    obj.has("reelItemRenderer") -> {
                        val rir = obj.getAsJsonObject("reelItemRenderer")
                        val vid = rir.getAsString("videoId") ?: return
                        if (vid.isEmpty() || out.any { it.videoId == vid }) return
                        val title = rir.getAsJsonObject("headline")?.getAsString("simpleText") ?: "Short"
                        out.add(VideoItem(vid, title, "", "", "https://i.ytimg.com/vi/$vid/hqdefault.jpg", 45, isShort = true))
                    }
                    else -> obj.entrySet().forEach { parseVideoItems(it.value, out) }
                }
            }
            element.isJsonArray -> element.asJsonArray.forEach { parseVideoItems(it, out) }
        }
    }

    private fun parseDuration(t: String): Long {
        val p = t.split(":").mapNotNull { it.toLongOrNull() }
        return when (p.size) { 3 -> p[0]*3600+p[1]*60+p[2]; 2 -> p[0]*60+p[1]; 1 -> p[0]; else -> 0L }
    }

    fun formatCount(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n/1_000_000.0)
        n >= 1_000     -> "%.1fK".format(n/1_000.0)
        else           -> n.toString()
    }

    // Helper to safely read a string from a JsonObject
    private fun com.google.gson.JsonObject.getAsString(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null
}
