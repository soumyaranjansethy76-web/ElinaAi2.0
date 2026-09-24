package com.elina.assistant.llm

import kotlinx.coroutines.flow.Flow

interface LlmClient {
    fun streamChat(messages: List<ChatMessage>): Flow<LlmStreamEvent>
}

sealed class LlmStreamEvent {
    data class TokenDelta(val delta: String) : LlmStreamEvent()
    data class Done(val finishReason: String?) : LlmStreamEvent()
    data class Error(val message: String, val cause: Throwable? = null) : LlmStreamEvent()
}
