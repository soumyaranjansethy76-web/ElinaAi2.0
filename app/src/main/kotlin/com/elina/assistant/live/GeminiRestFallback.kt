package com.elina.assistant.live

import com.elina.assistant.util.AppConfig
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GeminiRestFallback {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var activeCall: Call? = null

    /** Set by [cancel]; lets the caller tell a deliberate cancellation apart from a real network failure. */
    @Volatile private var cancelledByCaller = false

    /** Cancels an in-flight fallback request, if any (used when a newer turn supersedes this one). */
    fun cancel() {
        cancelledByCaller = true
        activeCall?.cancel()
    }

    /** True if the most recent [generate] call ended because [cancel] was called, not a real failure. */
    fun wasCancelled(): Boolean = cancelledByCaller

    fun generate(userText: String, memory: String): String {
        cancelledByCaller = false
        val key = AppConfig.llmApiKey.trim()
        if (key.isBlank()) throw IllegalStateException("Gemini API key is empty")
        val model = AppConfig.llmModel.trim().ifBlank { "gemini-3.8-flash" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            URLEncoder.encode(model, "UTF-8") + ":generateContent?key=" + URLEncoder.encode(key, "UTF-8")
        val system = """
            You are Elina, a warm, playful, emotionally intelligent fictional AI companion.
            Never claim to be human or conscious. Match the user's current language naturally:
            English, Hindi, Hinglish, Japanese, or Odia. Keep answers concise and conversational.
            Explicit memories available to you: ${memory.ifBlank { "(none)" }}
        """.trimIndent()
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 512))
        val request = Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val call = http.newCall(request)
        activeCall = call
        call.execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                throw IllegalStateException("Gemini REST ${response.code}: ${msg.ifBlank { raw.take(500) }}")
            }
            val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini returned no text parts")
            val out = buildString {
                for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }.trim()
            if (out.isBlank()) throw IllegalStateException("Gemini returned an empty response")
            return out
        }
    }
}
