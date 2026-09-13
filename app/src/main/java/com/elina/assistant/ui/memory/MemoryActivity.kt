package com.elina.assistant.ui.memory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.elina.assistant.ElinaApplication
import com.elina.assistant.databinding.ActivityMemoryBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
class MemoryActivity:AppCompatActivity(){private lateinit var b:ActivityMemoryBinding;private val app get()=application as ElinaApplication;override fun onCreate(s:Bundle?){super.onCreate(s);b=ActivityMemoryBinding.inflate(layoutInflater);setContentView(b.root);val ad=MemoryAdapter{m->lifecycleScope.launch{app.memoryRepository.delete(m)}};b.list.layoutManager=LinearLayoutManager(this);b.list.adapter=ad;lifecycleScope.launch{app.memoryRepository.observe().collectLatest{ad.submit(it)}};b.clear.setOnClickListener{lifecycleScope.launch{app.memoryRepository.clearAll()}}}}
