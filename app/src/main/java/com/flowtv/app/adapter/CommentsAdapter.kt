package com.flowtv.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.flowtv.app.R
import com.flowtv.app.model.CommentItem

class CommentsAdapter(
    private var comments: List<CommentItem>
) : RecyclerView.Adapter<CommentsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAuthor: TextView = view.findViewById(R.id.tvCommentAuthor)
        val tvLikes: TextView = view.findViewById(R.id.tvCommentLikes)
        val tvText: TextView = view.findViewById(R.id.tvCommentText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = comments[position]
        holder.tvAuthor.text = item.author
        holder.tvLikes.text = item.likes
        holder.tvText.text = item.text
    }

    override fun getItemCount(): Int = comments.size

    fun updateComments(newComments: List<CommentItem>) {
        comments = newComments
        notifyDataSetChanged()
    }
}
