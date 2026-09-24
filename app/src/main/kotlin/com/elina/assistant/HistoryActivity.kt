package com.elina.assistant

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.elina.assistant.data.AppDatabase
import com.elina.assistant.data.ChatRepository
import com.elina.assistant.databinding.ActivityHistoryBinding
import com.elina.assistant.ui.HistoryAdapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.topBar.setNavigationOnClickListener { finish() }

        adapter = HistoryAdapter { _ ->
            // For the MVP, "resume" simply finishes back to MainActivity.
            // A future revision can broadcast the selected conversation id
            // back to MainActivity via a result contract.
            finish()
        }
        binding.historyRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.historyRecyclerView.adapter = adapter

        observeConversations()
    }

    private fun observeConversations() {
        val repo = ChatRepository(AppDatabase.get(applicationContext))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeConversations().collect { list ->
                    val items = list.map { conv ->
                        val msgs = repo.observeMessages(conv.id).first()
                        val preview = msgs.firstOrNull()?.content?.take(60).orEmpty()
                        HistoryAdapter.Item(conv, preview, msgs.size)
                    }
                    adapter.submitList(items)
                    binding.emptyView.visibility =
                        if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
    }
}
