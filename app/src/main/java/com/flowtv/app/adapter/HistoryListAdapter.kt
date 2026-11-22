package com.flowtv.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.flowtv.app.R
import com.flowtv.app.model.VideoItem

class HistoryListAdapter(
    private var items: List<VideoItem>,
    private val onItemClick: (VideoItem, Int) -> Unit,
    private val onDeleteClick: (VideoItem, Int) -> Unit
) : RecyclerView.Adapter<HistoryListAdapter.HistoryViewHolder>() {

    inner class HistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivHistoryThumb)
        val tvDuration: TextView = view.findViewById(R.id.tvHistoryDuration)
        val tvTitle: TextView = view.findViewById(R.id.tvHistoryTitle)
        val tvChannel: TextView = view.findViewById(R.id.tvHistoryChannel)
        val btnDelete: TextView = view.findViewById(R.id.btnDeleteHistoryItem)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick(items[pos], pos)
                }
            }

            btnDelete.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDeleteClick(items[pos], pos)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_row, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val item = items[position]

        holder.tvTitle.text = item.title
        holder.tvChannel.text = item.channelTitle
        holder.tvDuration.text = formatDuration(item.durationSeconds)

        holder.ivThumbnail.load(item.thumbnailUrl) {
            crossfade(true)
            scale(Scale.FILL)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateList(newList: List<VideoItem>) {
        items = newList
        notifyDataSetChanged()
    }

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }
}
