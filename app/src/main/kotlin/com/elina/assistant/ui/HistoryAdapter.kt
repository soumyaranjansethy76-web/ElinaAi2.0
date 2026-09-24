package com.elina.assistant.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.elina.assistant.R
import com.elina.assistant.data.ConversationEntity

class HistoryAdapter(
    private val onClick: (ConversationEntity) -> Unit,
) : ListAdapter<HistoryAdapter.Item, HistoryAdapter.VH>(Diff) {

    data class Item(
        val conversation: ConversationEntity,
        val preview: String,
        val messageCount: Int,
    )

    object Diff : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(old: Item, new: Item) =
            old.conversation.id == new.conversation.id
        override fun areContentsTheSame(old: Item, new: Item) = old == new
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_conversation, parent, false)
        return VH(view, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(itemView: View, val onClick: (ConversationEntity) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.title)
        private val preview: TextView = itemView.findViewById(R.id.preview)
        private val timestamp: TextView = itemView.findViewById(R.id.timestamp)
        private val messageCount: TextView = itemView.findViewById(R.id.messageCount)

        fun bind(item: Item) {
            title.text = item.conversation.title
            preview.text = item.preview
            timestamp.text = DateUtils.getRelativeTimeSpanString(
                item.conversation.updatedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
            messageCount.text = "${item.messageCount} 条消息"
            itemView.setOnClickListener { onClick(item.conversation) }
        }
    }
}
