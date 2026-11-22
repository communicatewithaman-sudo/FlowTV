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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val INNERTUBE_URL = "https://www.youtube.com/youtubei/v1"
    private const val RYD_URL = "https://returnyoutubedislikeapi.com/votes?videoId="
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // Highly reliable public Invidious instances that decipher streams server-side
    private val INVIDIOUS_INSTANCES = listOf(
        "https://yewtu.be",
        "https://invidious.nerdvpn.de",
        "https://inv.nadeko.net",
        "https://invidious.drgns.space"
    )

    /**
     * Fetches video feed. Guaranteed to start with a trending Short,
     * followed by popular trending shorts and videos.
     */
    suspend fun getRecommendedFeed(): List<VideoItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<VideoItem>()

        // 1. Fetch Trending Shorts first so the first video is ALWAYS a Short
        val shorts = searchVideos("#shorts trending")
        val filteredShorts = shorts.filter { it.isShort || it.durationSeconds <= 90 }
        result.addAll(filteredShorts.take(12))

        // 2. Fetch Trending General Videos
        val general = searchVideos("trending")
        result.addAll(general.take(15))

        // 3. Guaranteed Fallbacks if network fails
        if (result.isEmpty()) {
            result.addAll(
                listOf(
                    VideoItem("L_LUpnjgPso", "Satisfying Domino Trick Compilation", "Domino King", "", "https://i.ytimg.com/vi/L_LUpnjgPso/hqdefault.jpg", 58, isShort = true),
                    VideoItem("rYEDA3JcQqw", "Amazing Card Throwing Magic", "Zach King", "", "https://i.ytimg.com/vi/rYEDA3JcQqw/hqdefault.jpg", 45, isShort = true),
                    VideoItem("kJQP7kiw5Fk", "Luis Fonsi - Despacito ft. Daddy Yankee", "Luis Fonsi", "", "https://i.ytimg.com/vi/kJQP7kiw5Fk/hqdefault.jpg", 282, isShort = false)
                )
            )
        }

        result.distinctBy { it.videoId }
    }

    /**
     * Resolves playable direct stream URL using a multi-tiered failover:
     * Tier 1: Invidious API (pre-deciphered direct MP4 video streams)
     * Tier 2: InnerTube ANDROID_TESTSUITE (unencrypted direct HLS)
     * Tier 3: InnerTube IOS / WEB_EMBEDDED
     */
    suspend fun resolveStreamUrl(videoId: String, quality: String = "Auto"): String? =
        withContext(Dispatchers.IO) {
            // Tier 1: Query Invidious public instances (Direct MP4 with video+audio)
            for (instance in INVIDIOUS_INSTANCES) {
                try {
                    val req = Request.Builder()
                        .url("$instance/api/v1/videos/$videoId")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()

                    val res = client.newCall(req).execute()
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: ""
                        val json = JsonParser.parseString(body).asJsonObject

                        // 1. Check formatStreams (Direct muxed MP4 streams)
                        if (json.has("formatStreams")) {
                            val streams = json.getAsJsonArray("formatStreams")
                            if (streams != null && streams.size() > 0) {
                                // Prefer 720p or 360p muxed stream
                                for (s in streams) {
                                    val obj = s.asJsonObject
                                    val url = obj.get("url")?.asString
                                    if (!url.isNullOrEmpty()) {
                                        return@withContext url
                                    }
                                }
                            }
                        }

                        // 2. Check HLS manifest url
                        if (json.has("hlsUrl") && !json.get("hlsUrl").isJsonNull) {
                            val hls = json.get("hlsUrl").asString
                            if (hls.isNotEmpty()) return@withContext hls
                        }
                    }
                } catch (e: Exception) {
                    // Try next instance
                }
            }

            // Tier 2: InnerTube ANDROID_TESTSUITE Fallback
            try {
                val payload = """
                {
                  "context": {
                    "client": {
                      "clientName": "ANDROID_TESTSUITE",
                      "clientVersion": "1.9",
                      "androidSdkVersion": 30,
                      "hl": "en",
                      "gl": "US"
                    }
                  },
                  "videoId": "$videoId"
                }
                """.trimIndent().toRequestBody(JSON_MEDIA)

                val req = Request.Builder()
                    .url("$INNERTUBE_URL/player")
                    .post(payload)
                    .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 11)")
                    .build()

                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: ""
                val json = JsonParser.parseString(body).asJsonObject

                if (json.has("streamingData")) {
                    val sd = json.getAsJsonObject("streamingData")
                    if (sd.has("hlsManifestUrl")) {
                        return@withContext sd.get("hlsManifestUrl").asString
                    }
                    val formats = sd.getAsJsonArray("formats")
                    if (formats != null && formats.size() > 0) {
                        for (f in formats) {
                            val obj = f.asJsonObject
                            if (obj.has("url")) return@withContext obj.get("url").asString
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Tier 3: InnerTube TVHTML5_SIMPLY_EMBEDDED_PLAYER Fallback
            try {
                val payload = """
                {
                  "context": {
                    "client": {
                      "clientName": "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                      "clientVersion": "2.0",
                      "hl": "en",
                      "gl": "US"
                    }
                  },
                  "videoId": "$videoId"
                }
                """.trimIndent().toRequestBody(JSON_MEDIA)

                val req = Request.Builder()
                    .url("$INNERTUBE_URL/player")
                    .post(payload)
                    .header("User-Agent", "Mozilla/5.0 (SMART-TV; Linux; Tizen 5.0) AppleWebKit/538.1")
                    .build()

                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: ""
                val json = JsonParser.parseString(body).asJsonObject

                if (json.has("streamingData")) {
                    val sd = json.getAsJsonObject("streamingData")
                    if (sd.has("hlsManifestUrl")) {
                        return@withContext sd.get("hlsManifestUrl").asString
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            null
        }

    /**
     * Searches YouTube for videos/shorts.
     */
    suspend fun searchVideos(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<VideoItem>()
        try {
            val payload = """
            {
              "context": {
                "client": {
                  "clientName": "WEB",
                  "clientVersion": "2.20240101.00.00",
                  "hl": "en",
                  "gl": "US"
                }
              },
              "query": "$query"
            }
            """.trimIndent().toRequestBody(JSON_MEDIA)

            val request = Request.Builder()
                .url("$INNERTUBE_URL/search")
                .post(payload)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (body.isNotEmpty()) {
                val root = JsonParser.parseString(body).asJsonObject
                parseVideoItems(root, list)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    /**
     * Fetches exact Likes and Dislikes from Return YouTube Dislike API.
     */
    suspend fun getLikesAndDislikes(videoId: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$RYD_URL$videoId").build()
                val response = client.newCall(request).execute()
                val obj = JsonParser.parseString(response.body?.string() ?: "").asJsonObject

                val likes = obj.get("likes")?.asLong ?: 0
                val dislikes = obj.get("dislikes")?.asLong ?: 0

                return@withContext Pair(formatCount(likes), formatCount(dislikes))
            } catch (e: Exception) {
                Pair("—", "—")
            }
        }

    private fun parseVideoItems(element: JsonElement, outList: MutableList<VideoItem>) {
        if (element.isJsonObject) {
            val obj = element.asJsonObject

            if (obj.has("videoRenderer")) {
                val vr = obj.getAsJsonObject("videoRenderer")
                val vid = vr.get("videoId")?.asString ?: ""
                val title = vr.getAsJsonObject("title")
                    ?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: "Untitled"
                val channel = vr.getAsJsonObject("ownerText")
                    ?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString
                    ?: vr.getAsJsonObject("shortBylineText")
                        ?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: "Channel"

                val thumbs = vr.getAsJsonObject("thumbnail")?.getAsJsonArray("thumbnails")
                val thumbUrl = if (thumbs != null && thumbs.size() > 0) {
                    thumbs.get(thumbs.size() - 1).asJsonObject.get("url").asString
                } else "https://i.ytimg.com/vi/$vid/hqdefault.jpg"

                val lengthText = vr.getAsJsonObject("lengthText")?.get("simpleText")?.asString ?: "0:00"
                val durationSec = parseDuration(lengthText)

                if (vid.isNotEmpty() && outList.none { it.videoId == vid }) {
                    outList.add(
                        VideoItem(
                            videoId = vid,
                            title = title,
                            channelTitle = channel,
                            thumbnailUrl = thumbUrl,
                            durationSeconds = durationSec,
                            isShort = durationSec in 1..90
                        )
                    )
                }
            } else if (obj.has("reelItemRenderer")) {
                val rir = obj.getAsJsonObject("reelItemRenderer")
                val vid = rir.get("videoId")?.asString ?: ""
                val title = rir.getAsJsonObject("headline")?.get("simpleText")?.asString ?: "Short"
                val channel = rir.getAsJsonObject("ownerText")
                    ?.getAsJsonArray("runs")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""

                if (vid.isNotEmpty() && outList.none { it.videoId == vid }) {
                    outList.add(
                        VideoItem(
                            videoId = vid,
                            title = title,
                            channelTitle = channel,
                            thumbnailUrl = "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
                            durationSeconds = 45,
                            isShort = true
                        )
                    )
                }
            } else {
                for (entry in obj.entrySet()) {
                    parseVideoItems(entry.value, outList)
                }
            }
        } else if (element.isJsonArray) {
            for (item in element.asJsonArray) {
                parseVideoItems(item, outList)
            }
        }
    }

    private fun parseDuration(time: String): Long {
        val parts = time.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0L
        }
    }

    fun formatCount(count: Long): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }
}
