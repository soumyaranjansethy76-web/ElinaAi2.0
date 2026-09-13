package com.elina.assistant.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("elina_settings")

class PreferencesManager(private val context: Context) {
    private val userNameKey = stringPreferencesKey("user_name")
    private val personalityKey = stringPreferencesKey("personality_mode")
    private val modelKey = stringPreferencesKey("gemini_model")
    private val voiceKey = stringPreferencesKey("gemini_voice")
    private val thinkingKey = stringPreferencesKey("thinking_level")
    private val wakePhraseKey = stringPreferencesKey("wake_phrase")
    private val wakeEnabledKey = booleanPreferencesKey("background_wake_enabled")
    private val memoryEnabledKey = booleanPreferencesKey("memory_enabled")
    val snapshot: Flow<SettingsSnapshot> = context.dataStore.data.map { p -> SettingsSnapshot(
        p[userNameKey] ?: "Friend", p[personalityKey] ?: "Companion", p[modelKey] ?: "gemini-3.1-flash-live-preview",
        p[voiceKey] ?: "Aoede", p[thinkingKey] ?: "minimal", p[wakePhraseKey] ?: "Elina, wake up",
        p[wakeEnabledKey] ?: false, p[memoryEnabledKey] ?: true)
    }
    suspend fun setUserName(v: String)=context.dataStore.updateData { it.toMutablePreferences().apply { set(userNameKey,v) } }
    suspend fun setPersonality(v: String)=context.dataStore.updateData { it.toMutablePreferences().apply { set(personalityKey,v) } }
    suspend fun setModel(v: String)=context.dataStore.updateData { it.toMutablePreferences().apply { set(modelKey,v) } }
    suspend fun setVoice(v: String)=context.dataStore.updateData { it.toMutablePreferences().apply { set(voiceKey,v) } }
    suspend fun setThinking(v: String)=context.dataStore.updateData { it.toMutablePreferences().apply { set(thinkingKey,v) } }
    suspend fun setWakePhrase(v: String)=context.dataStore.updateData { it.toMutablePreferences().apply { set(wakePhraseKey,v) } }
    suspend fun setWakeEnabled(v: Boolean)=context.dataStore.updateData { it.toMutablePreferences().apply { set(wakeEnabledKey,v) } }
    suspend fun setMemoryEnabled(v: Boolean)=context.dataStore.updateData { it.toMutablePreferences().apply { set(memoryEnabledKey,v) } }
}

data class SettingsSnapshot(val userName:String,val personality:String,val model:String,val voice:String,val thinking:String,val wakePhrase:String,val wakeEnabled:Boolean,val memoryEnabled:Boolean)
