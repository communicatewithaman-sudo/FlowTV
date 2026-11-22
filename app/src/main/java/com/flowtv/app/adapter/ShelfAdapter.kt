package com.flowtv.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.flowtv.app.R
import com.flowtv.app.model.VideoItem

class ShelfAdapter(
    private var items: List<VideoItem>,
    private val isHistory: Boolean = false,
    private val onVideoClick: (VideoItem, Int) -> Unit
) : RecyclerView.Adapter<ShelfAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val tvDurationBadge: TextView = view.findViewById(R.id.tvDurationBadge)
        val tvVideoTitle: TextView = view.findViewById(R.id.tvVideoTitle)
        val tvChannelTitle: TextView = view.findViewById(R.id.tvChannelTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shelf_video, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.tvVideoTitle.text = item.title
        holder.tvChannelTitle.text = item.channelTitle

        if (isHistory && item.resumePositionMs > 5000) {
            val mins = (item.resumePositionMs / 1000) / 60
            val secs = (item.resumePositionMs / 1000) % 60
            holder.tvDurationBadge.text = String.format("Resume at %02d:%02d", mins, secs)
            holder.tvDurationBadge.visibility = View.VISIBLE
        } else if (item.durationSeconds > 0) {
            val mins = item.durationSeconds / 60
            val secs = item.durationSeconds % 60
            holder.tvDurationBadge.text = String.format("%d:%02d", mins, secs)
            holder.tvDurationBadge.visibility = View.VISIBLE
        } else {
            holder.tvDurationBadge.visibility = View.GONE
        }

        holder.ivThumbnail.load(item.thumbnailUrl) {
            crossfade(true)
        }

        // Apple TV style smooth focus animation
        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(180).start()
                view.elevation = 8f
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(180).start()
                view.elevation = 0f
            }
        }

        holder.itemView.setOnClickListener {
            onVideoClick(item, position)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newItems: List<VideoItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
