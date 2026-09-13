package com.elina.assistant

import android.app.Application
import com.elina.assistant.memory.MemoryDatabase
import com.elina.assistant.memory.MemoryRepository
import com.elina.assistant.util.PreferencesManager
import com.elina.assistant.util.SecurityManager

class ElinaApplication : Application() {
    val preferences by lazy { PreferencesManager(this) }
    val security by lazy { SecurityManager(this) }
    val memoryRepository by lazy { MemoryRepository(MemoryDatabase.get(this).memoryDao()) }
}
