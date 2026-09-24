package com.elina.assistant.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.elina.assistant.R
import com.elina.assistant.data.MessageEntity
import com.elina.assistant.data.Role

class ChatAdapter : ListAdapter<MessageEntity, ChatAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<MessageEntity>() {
        override fun areItemsTheSame(old: MessageEntity, new: MessageEntity) = old.id == new.id
        override fun areContentsTheSame(old: MessageEntity, new: MessageEntity) = old == new
    }

    override fun getItemViewType(position: Int): Int = getItem(position).role.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ctx: Context = itemView.context
        private val role: TextView = itemView.findViewById(R.id.role)
        private val bubble: TextView = itemView.findViewById(R.id.bubble)
        private val container: LinearLayout = itemView as LinearLayout

        fun bind(message: MessageEntity) {
            when (message.role) {
                Role.USER -> {
                    role.text = ctx.getString(R.string.role_user)
                    role.gravity = Gravity.END
                    bubble.background = ctx.getDrawable(R.drawable.bg_message_user)
                    container.gravity = Gravity.END
                }
                Role.ASSISTANT -> {
                    role.text = ctx.getString(R.string.role_assistant)
                    role.gravity = Gravity.START
                    bubble.background = ctx.getDrawable(R.drawable.bg_message_assistant)
                    container.gravity = Gravity.START
                }
                Role.SYSTEM -> {
                    // System messages aren't shown directly; we skip
                    role.text = ""
                    bubble.text = ""
                    return
                }
            }
            bubble.text = message.content
        }
    }
}
