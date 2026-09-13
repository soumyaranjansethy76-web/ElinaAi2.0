package com.elina.assistant.ui.memory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.elina.assistant.databinding.ItemMemoryBinding
import com.elina.assistant.memory.MemoryEntity
class MemoryAdapter(private val onDelete:(MemoryEntity)->Unit):RecyclerView.Adapter<MemoryAdapter.H>(){private val items=mutableListOf<MemoryEntity>();fun submit(x:List<MemoryEntity>){items.clear();items.addAll(x);notifyDataSetChanged()};override fun getItemCount()=items.size;override fun onCreateViewHolder(p:ViewGroup,v:Int)=H(ItemMemoryBinding.inflate(LayoutInflater.from(p.context),p,false));override fun onBindViewHolder(h:H,p:Int){val m=items[p];h.b.category.text=m.category;h.b.content.text=m.content;h.b.root.setOnLongClickListener{onDelete(m);true}};class H(val b:ItemMemoryBinding):RecyclerView.ViewHolder(b.root)}
