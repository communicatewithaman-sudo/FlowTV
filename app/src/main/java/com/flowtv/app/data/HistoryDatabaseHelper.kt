package com.flowtv.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.flowtv.app.model.VideoItem

class HistoryDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "flowtv_history.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_HISTORY = "history"

        private const val COL_ID = "video_id"
        private const val COL_TITLE = "title"
        private const val COL_CHANNEL = "channel"
        private const val COL_CHANNEL_ID = "channel_id"
        private const val COL_THUMB = "thumbnail"
        private const val COL_DURATION = "duration"
        private const val COL_POSITION = "last_position_ms"
        private const val COL_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createQuery = """
            CREATE TABLE $TABLE_HISTORY (
                $COL_ID TEXT PRIMARY KEY,
                $COL_TITLE TEXT,
                $COL_CHANNEL TEXT,
                $COL_CHANNEL_ID TEXT,
                $COL_THUMB TEXT,
                $COL_DURATION INTEGER,
                $COL_POSITION INTEGER,
                $COL_TIMESTAMP INTEGER
            )
        """.trimIndent()
        db.execSQL(createQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        onCreate(db)
    }

    fun saveWatchProgress(video: VideoItem, positionMs: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, video.videoId)
            put(COL_TITLE, video.title)
            put(COL_CHANNEL, video.channelTitle)
            put(COL_CHANNEL_ID, video.channelId)
            put(COL_THUMB, video.thumbnailUrl)
            put(COL_DURATION, video.durationSeconds)
            put(COL_POSITION, positionMs)
            put(COL_TIMESTAMP, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_HISTORY, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getHistory(limit: Int = 50): List<VideoItem> {
        val list = mutableListOf<VideoItem>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_HISTORY,
            null,
            null,
            null,
            null,
            null,
            "$COL_TIMESTAMP DESC",
            limit.toString()
        )

        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(COL_ID)
            val titleIdx = it.getColumnIndexOrThrow(COL_TITLE)
            val channelIdx = it.getColumnIndexOrThrow(COL_CHANNEL)
            val channelIdIdx = it.getColumnIndexOrThrow(COL_CHANNEL_ID)
            val thumbIdx = it.getColumnIndexOrThrow(COL_THUMB)
            val durationIdx = it.getColumnIndexOrThrow(COL_DURATION)
            val posIdx = it.getColumnIndexOrThrow(COL_POSITION)

            while (it.moveToNext()) {
                list.add(
                    VideoItem(
                        videoId = it.getString(idIdx),
                        title = it.getString(titleIdx),
                        channelTitle = it.getString(channelIdx),
                        channelId = it.getString(channelIdIdx) ?: "",
                        thumbnailUrl = it.getString(thumbIdx) ?: "",
                        durationSeconds = it.getLong(durationIdx),
                        resumePositionMs = it.getLong(posIdx)
                    )
                )
            }
        }
        return list
    }
}
