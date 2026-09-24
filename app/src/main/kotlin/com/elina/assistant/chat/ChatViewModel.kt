package com.elina.assistant.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elina.assistant.data.ChatRepository
import com.elina.assistant.data.MessageEntity
import com.elina.assistant.data.Role
import com.elina.assistant.live.ConnectionState
import com.elina.assistant.live.GeminiLiveManager
import com.elina.assistant.memory.MemoryStore
import com.elina.assistant.util.logE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repo: ChatRepository,
    private val live: GeminiLiveManager,
    private val memory: MemoryStore,
) : ViewModel() {
    /**
     * The only writer of this flow is [onConnectionState], which is called directly from
     * GeminiLiveManager's callback. No other function in this class may set it — that's the
     * whole point of deriving UI state from the actual connection state instead of guessing.
     */
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** The most recent human-readable failure text, valid when [state] is ERROR. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _conversationId = MutableStateFlow<Long?>(null)
    val conversationId: StateFlow<Long?> = _conversationId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: Flow<List<MessageEntity>> = _conversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.observeMessages(id)
    }

    private val _avatarCommands = MutableSharedFlow<AvatarCommand>(extraBufferCapacity = 32)
    val avatarCommands: SharedFlow<AvatarCommand> = _avatarCommands

    private var pendingAssistantId: Long? = null
    private var assistantTranscript = StringBuilder()
    private var lastUserTranscript: String? = null
    private var micActive = false

    fun ensureConversation() {
        if (_conversationId.value != null) return
        viewModelScope.launch {
            _conversationId.value = repo.createConversation("New conversation")
        }
    }

    /** The single entry point that updates [state]. Called only from GeminiLiveManager's callback. */
    fun onConnectionState(newState: ConnectionState) {
        _state.value = newState
    }

    fun startLive() {
        ensureConversation()
        micActive = true
        _avatarCommands.tryEmit(AvatarCommand.SetEmotion("listening"))
        live.startMic()
    }

    fun stopLive() {
        micActive = false
        live.stopMic()
        _avatarCommands.tryEmit(AvatarCommand.SetEmotion("idle"))
    }

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        ensureConversation()
        _avatarCommands.tryEmit(AvatarCommand.SetEmotion("thinking"))
        viewModelScope.launch {
            val id = _conversationId.value ?: return@launch
            repo.appendMessage(id, Role.USER, clean)
            val hint = memory.relevant(clean)
            val forGemini = if (hint.isBlank()) clean else "$clean\n\n(Relevant memory:\n$hint)"
            live.sendText(forGemini)
        }
    }

    fun clearCurrentConversation() {
        live.stopAudioPlayback()
        live.cancelCurrentTurn()
        viewModelScope.launch {
            _conversationId.value?.let { /* conversation remains in history; a fresh thread is created */ }
            _conversationId.value = null
            ensureConversation()
        }
        pendingAssistantId = null
        assistantTranscript = StringBuilder()
        _avatarCommands.tryEmit(AvatarCommand.SetEmotion("idle"))
    }

    fun setMicActive(active: Boolean) {
        if (active) startLive() else stopLive()
    }

    fun onLiveConnected() {
        if (micActive) _avatarCommands.tryEmit(AvatarCommand.SetEmotion("listening"))
    }

    fun onLiveUserTranscript(text: String) {
        if (text.isBlank()) return
        val clean = text.trim()
        if (clean == lastUserTranscript) return
        lastUserTranscript = clean
        viewModelScope.launch {
            val id = _conversationId.value ?: return@launch
            repo.appendMessage(id, Role.USER, clean)
        }
        _avatarCommands.tryEmit(AvatarCommand.SetEmotion("thinking"))
    }

    fun onLiveAssistantTranscript(text: String) {
        if (text.isBlank()) return
        val id = _conversationId.value ?: return
        viewModelScope.launch {
            if (pendingAssistantId == null) pendingAssistantId = repo.appendMessage(id, Role.ASSISTANT, "")
            assistantTranscript.append(text)
            pendingAssistantId?.let { repo.updateMessageContent(it, assistantTranscript.toString()) }
        }
        _avatarCommands.tryEmit(AvatarCommand.SetEmotion("speaking"))
    }

    fun onLiveTurnComplete() {
        pendingAssistantId = null
        assistantTranscript = StringBuilder()
        lastUserTranscript = null
        _avatarCommands.tryEmit(AvatarCommand.SetSpeaking(false))
        _avatarCommands.tryEmit(AvatarCommand.SetEmotion(if (micActive) "listening" else "idle"))
    }

    fun onLiveSpeaking() {
        _avatarCommands.tryEmit(AvatarCommand.SetSpeaking(true))
    }

    fun onLiveInterrupted() {
        pendingAssistantId = null
        assistantTranscript = StringBuilder()
        _avatarCommands.tryEmit(AvatarCommand.SetSpeaking(false))
        _avatarCommands.tryEmit(AvatarCommand.SetEmotion("listening"))
    }

    fun onLiveStateError(message: String) {
        _errorMessage.value = message
        _avatarCommands.tryEmit(AvatarCommand.SetSpeaking(false))
        _avatarCommands.tryEmit(AvatarCommand.SetEmotion("error"))
    }

    fun onToolResult(name: String, result: String) {
        _avatarCommands.tryEmit(AvatarCommand.Tool(name, result))
    }

    override fun onCleared() {
        try { live.close() } catch (e: Exception) { logE("Live close failed", e) }
        super.onCleared()
    }
}

sealed class AvatarCommand {
    data class SetEmotion(val name: String) : AvatarCommand()
    data class SetSpeaking(val active: Boolean) : AvatarCommand()
    data object Wave : AvatarCommand()
    data class Tool(val name: String, val result: String) : AvatarCommand()
}
