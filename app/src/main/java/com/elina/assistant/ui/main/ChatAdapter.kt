package com.elina.assistant.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.elina.assistant.databinding.ItemChatElinaBinding
import com.elina.assistant.databinding.ItemChatUserBinding
import com.elina.assistant.model.ChatMessage

class ChatAdapter:RecyclerView.Adapter<RecyclerView.ViewHolder>(){
    private val items=mutableListOf<ChatMessage>()
    fun submit(list:List<ChatMessage>){items.clear();items.addAll(list.takeLast(12));notifyDataSetChanged()}
    override fun getItemViewType(p:Int)=if(items[p].isUser)1 else 0
    override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):RecyclerView.ViewHolder=if(viewType==1)User(ItemChatUserBinding.inflate(LayoutInflater.from(parent.context),parent,false)) else Elina(ItemChatElinaBinding.inflate(LayoutInflater.from(parent.context),parent,false))
    override fun getItemCount()=items.size
    override fun onBindViewHolder(h:RecyclerView.ViewHolder,p:Int){val m=items[p];if(h is User)h.b.message.text=m.text else if(h is Elina)h.b.message.text=m.text}
    class User(val b:ItemChatUserBinding):RecyclerView.ViewHolder(b.root); class Elina(val b:ItemChatElinaBinding):RecyclerView.ViewHolder(b.root)
}
