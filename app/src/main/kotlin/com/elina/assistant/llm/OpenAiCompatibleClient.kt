package com.elina.assistant.llm

import com.elina.assistant.util.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Streaming chat completion client speaking the OpenAI-compatible
 * `/chat/completions` SSE protocol. Works with:
 *   - Ollama (`http://host:11434/v1`)
 *   - OpenAI
 *   - 智谱 GLM, 百度千帆, Together, Groq, etc.
 */
class OpenAiCompatibleClient(
) : LlmClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // CRITICAL: without this, default-valued fields (stream=true, temperature=0.7,
        // think=false) are silently dropped from the wire payload — and Ollama then
        // defaults `stream` to false, returning a single JSON object that our
        // SSE reader hangs on forever.
        encodeDefaults = true
        explicitNulls = false
    }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override fun streamChat(messages: List<ChatMessage>): Flow<LlmStreamEvent> = flow {
        val payload = ChatCompletionRequest(
            model = AppConfig.llmModel,
            messages = messages,
            stream = true,
        )
        val body = json.encodeToString(ChatCompletionRequest.serializer(), payload)
            .toRequestBody(jsonMedia)

        val builder = Request.Builder()
            .url("${AppConfig.llmBaseUrl.trimEnd('/')}/chat/completions")
            .post(body)
            .header("Accept", "text/event-stream")
        if (AppConfig.llmApiKey.isNotBlank()) builder.header("Authorization", "Bearer ${AppConfig.llmApiKey}")

        val response = http.newCall(builder.build()).execute()
        if (!response.isSuccessful) {
            emit(LlmStreamEvent.Error("HTTP ${response.code}: ${response.message}"))
            response.close()
            return@flow
        }

        val source = response.body?.source()
            ?: run {
                emit(LlmStreamEvent.Error("Empty response body"))
                return@flow
            }

        try {
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    emit(LlmStreamEvent.Done(finishReason = "stop"))
                    break
                }
                try {
                    val chunk = json.decodeFromString(ChatStreamChunk.serializer(), data)
                    val delta = chunk.choices.firstOrNull()?.delta?.content
                    if (!delta.isNullOrEmpty()) emit(LlmStreamEvent.TokenDelta(delta))
                    val finish = chunk.choices.firstOrNull()?.finishReason
                    if (finish != null) {
                        emit(LlmStreamEvent.Done(finish))
                        break
                    }
                } catch (e: Exception) {
                    // Tolerate malformed chunks (provider quirks)
                }
            }
        } catch (e: Exception) {
            emit(LlmStreamEvent.Error("Stream read failed: ${e.message}", e))
        } finally {
            response.close()
        }
    }.flowOn(Dispatchers.IO)
}
