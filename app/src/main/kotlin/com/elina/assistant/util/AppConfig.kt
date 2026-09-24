package com.elina.assistant.util

import android.content.Context

/** Runtime configuration persisted inside app-private storage. */
object AppConfig {
    private const val PREFS = "elina_config"
    private const val KEY_URL = "llm_url"
    private const val KEY_API = "llm_api_key"
    private const val KEY_MODEL = "llm_model"
    private const val KEY_LIVE_MODEL = "live_model"
    private const val KEY_LIVE_VOICE = "live_voice"
    private const val KEY_WAKE_WORD = "wake_word_enabled"

    var llmBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta/openai/"
    var llmApiKey: String = ""
    var llmModel: String = "gemini-3.8-flash"
    var liveModel: String = "gemini-3.8-live"
    var liveVoice: String = "Kore"

    /** Persisted "Hey Elina" user preference. Defaults to OFF — the feature must be explicitly enabled. */
    var wakeWordEnabled: Boolean = false

    var systemPrompt: String = """
        You are Elina, a warm, intelligent Android AI voice assistant and 3D VRM companion.
        Speak naturally and conversationally. Keep answers concise unless the user asks for detail.
        You can chat, remember conversation history, listen through the microphone, speak answers aloud,
        and control the Elina avatar with emotions and lip-sync.
        Never claim a device action succeeded unless the app confirms it.
        Do not pretend to be human or claim consciousness.
        Match the user's language automatically (Hindi, Hinglish, English, etc.).
    """.trimIndent()

    var ragEnabled: Boolean = false
    var contextWindow: Int = 24

    /** Whether a (non-blank) Gemini API key has been configured yet. Used to drive startup UI state. */
    fun hasApiKey(): Boolean = llmApiKey.isNotBlank()

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        llmBaseUrl = p.getString(KEY_URL, llmBaseUrl) ?: llmBaseUrl
        llmApiKey = p.getString(KEY_API, "") ?: ""
        llmModel = p.getString(KEY_MODEL, llmModel) ?: llmModel
        liveModel = (p.getString(KEY_LIVE_MODEL, liveModel) ?: liveModel).let { if (it == "gemini-3.1-flash-live-preview") "gemini-3.8-live" else it }
        liveVoice = p.getString(KEY_LIVE_VOICE, liveVoice) ?: liveVoice
        wakeWordEnabled = p.getBoolean(KEY_WAKE_WORD, false)
    }

    fun setWakeWordEnabled(context: Context, enabled: Boolean) {
        wakeWordEnabled = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WAKE_WORD, enabled)
            .apply()
    }

    fun save(context: Context, baseUrl: String, apiKey: String, model: String) {
        llmBaseUrl = baseUrl.trim().ifBlank { "https://generativelanguage.googleapis.com/v1beta/openai/" }
        llmApiKey = apiKey.trim()
        llmModel = model.trim().ifBlank { "gemini-3.8-flash" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, llmBaseUrl)
            .putString(KEY_API, llmApiKey)
            .putString(KEY_MODEL, llmModel)
            .apply()
    }

    fun saveLiveModel(context: Context, model: String) {
        liveModel = model.trim().ifBlank { "gemini-3.8-live" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIVE_MODEL, liveModel)
            .apply()
    }

    fun saveLiveVoice(context: Context, voice: String) {
        liveVoice = voice.trim().ifBlank { "Kore" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIVE_VOICE, liveVoice)
            .apply()
    }
}
