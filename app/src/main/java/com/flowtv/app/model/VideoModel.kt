package com.flowtv.app.model

data class VideoItem(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val channelId: String = "",
    val thumbnailUrl: String = "",
    val durationSeconds: Long = 0,
    var streamUrl: String? = null,
    var likes: String = "—",
    var dislikes: String = "—",
    var commentCount: String = "—",
    var description: String = "",
    val isShort: Boolean = false,
    var resumePositionMs: Long = 0
)

data class CommentItem(
    val author: String,
    val text: String,
    val likes: String = ""
)
