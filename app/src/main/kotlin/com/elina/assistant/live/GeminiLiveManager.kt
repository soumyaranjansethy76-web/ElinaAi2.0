package com.elina.assistant.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import com.elina.assistant.device.DeviceActionExecutor
import com.elina.assistant.memory.MemoryStore
import com.elina.assistant.util.AppConfig
import com.elina.assistant.util.LanguageHint
import com.elina.assistant.util.logE
import com.elina.assistant.voice.TtsEvent
import com.elina.assistant.voice.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Native Gemini Live transport using the documented BidiGenerateContent WebSocket.
 * 16 kHz PCM in; model audio is played as 24 kHz PCM.
 *
 * This class is the single source of truth for [ConnectionState] — every state change the
 * rest of the app sees comes from here, and only from a real signal (setup completed, a
 * server message, a timeout, a socket event). Nothing here is allowed to claim a state
 * that hasn't actually happened, which is the whole point of this phase.
 */
class GeminiLiveManager(
    private val apiKeyProvider: () -> String,
    private val memoryStore: MemoryStore,
    private val deviceActions: DeviceActionExecutor,
    private val ttsManager: TtsManager,
    private val callback: Callback,
) {
    interface Callback {
        fun onState(state: ConnectionState)
        fun onAudioLevel(level: Float)
        fun onUserTranscript(text: String)
        fun onAssistantTranscript(text: String)
        fun onAvatarEmotion(name: String)
        fun onToolResult(name: String, result: String)
        fun onInterrupted()
        fun onTurnComplete()
        fun onError(message: String)
        fun onConnected()
    }

    companion object {
        const val MODEL = "gemini-3.1-flash-live-preview"
        private const val WS_BASE = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val INPUT_RATE = 16000
        private const val OUTPUT_RATE = 24000

        /** How long we wait for a reply (text or voice turn) before treating Live as stuck. */
        private const val RESPONSE_TIMEOUT_MS = 7000L

        /** How long we wait for the "setupComplete" handshake before giving up on a connect attempt. */
        private const val SETUP_TIMEOUT_MS = 10000L

        /** Automatic reconnect attempts after an unexpected drop, before surfacing a hard ERROR. */
        private const val MAX_RECONNECT_ATTEMPTS = 3
    }

    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val restFallback = GeminiRestFallback()
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        fallbackScope.launch {
            ttsManager.events.collect { ev ->
                if (ev is TtsEvent.Error) callback.onError("TTS: ${ev.message}")
            }
        }
    }

    private var socket: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var micWanted = false

    /** True only once Gemini has accepted our `setup` frame. Nothing may be sent before this. */
    @Volatile private var setupComplete = false

    /** True when [close] was called deliberately — suppresses auto-reconnect. */
    @Volatile private var userClosed = false

    /** True once we've reported a fatal server-side error for this connection — suppresses auto-reconnect. */
    @Volatile private var terminalErrorReported = false

    /** True while the hosting Activity is backgrounded. Gates local audio hardware only — never the session. */
    @Volatile private var backgrounded = false

    /** Remembers whether the mic was actively capturing right before we backgrounded, to restore it correctly. */
    @Volatile private var micWasActiveBeforeBackground = false

    private var reconnectAttempts = 0

    /** True once *something* answered the current turn (text, audio, or an interruption). */
    @Volatile private var turnAnswered = true

    /** The text we're currently waiting on a reply for, if any (used to drive the REST fallback). */
    @Volatile private var pendingResponseText: String? = null

    /** Last thing the user was heard saying, used as the REST-fallback query for a stuck voice turn. */
    @Volatile private var lastUserText: String = ""

    /** Set right after a REST fallback answer is delivered, to swallow one late duplicate Live reply. */
    @Volatile private var suppressNextAssistantSignal = false

    private var responseWatchdog: Job? = null
    private var setupWatchdog: Job? = null

    private var recordThread: Thread? = null
    private val pendingTexts = mutableListOf<String>()
    private var track: AudioTrack? = null
    private val sendLock = Any()

    // ---------------------------------------------------------------------------------------
    // Connection lifecycle
    // ---------------------------------------------------------------------------------------

    fun connect() {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) {
            callback.onError("Add your Gemini API key in AI settings first.")
            setState(ConnectionState.DISCONNECTED)
            return
        }
        if (!looksLikeValidApiKey(key)) {
            callback.onError("That doesn't look like a valid Gemini API key. Get one from Google AI Studio and paste it into AI settings.")
            setState(ConnectionState.DISCONNECTED)
            return
        }
        closeAudio()
        userClosed = false
        terminalErrorReported = false
        setupComplete = false
        running = true
        setState(ConnectionState.CONNECTING)
        val request = Request.Builder().url(WS_BASE).header("x-goog-api-key", key).build()
        socket = http.newWebSocket(request, listener)
        armSetupWatchdog()
    }

    /** Manual retry entry point (e.g. a "Retry" tap after ERROR): resets the backoff counter. */
    fun reconnect() {
        reconnectAttempts = 0
        connect()
    }

    fun close() {
        userClosed = true
        disarmResponseWatchdog()
        disarmSetupWatchdog()
        restFallback.cancel()
        ttsManager.interrupt()
        running = false
        micWanted = false
        recordThread = null
        pendingResponseText = null
        socket?.close(1000, "user closed")
        socket = null
        closeAudio()
        reconnectAttempts = 0
        backgrounded = false
        micWasActiveBeforeBackground = false
        setState(ConnectionState.DISCONNECTED)
        // This is the definitive "we are done for good" teardown (see MainActivity.onDestroy) —
        // cancel every outstanding coroutine (watchdogs, reconnect scheduling, the TTS-event
        // collector below) so nothing keeps running, and holding a reference to this instance's
        // callback, after the owner is gone.
        fallbackScope.cancel()
    }

    /**
     * Called when the hosting Activity leaves the foreground. Releases the microphone and
     * pauses audio playback immediately — Android does not reliably let a backgrounded app
     * hold either open, and leaving them running risks a silent read-error busy loop on the
     * mic thread and audio blaring from a screen the user isn't looking at. The Live session
     * itself (socket, setupComplete, whatever turn was pending) is left completely alone —
     * this only touches local audio hardware.
     */
    fun pauseForBackground() {
        if (backgrounded) return
        backgrounded = true
        micWasActiveBeforeBackground = micWanted && recordThread != null
        recordThread = null // the capture loop notices this on its next check and cleans itself up
        track?.pause()
        track?.flush()
    }

    /**
     * Called when the Activity returns to the foreground. Restarts the mic if it was actively
     * capturing before we backgrounded; playback resumes on its own with the next audio chunk
     * Gemini sends (see the [ensureAudioTrack]/[playAudio] guard below).
     */
    fun resumeFromBackground() {
        if (!backgrounded) return
        backgrounded = false
        if (micWasActiveBeforeBackground && micWanted && setupComplete && running) {
            startRecorderIfNeeded()
        }
        micWasActiveBeforeBackground = false
    }

    /** Validates a basic Gemini API key shape before we spend a network round trip on it. */
    private fun looksLikeValidApiKey(key: String): Boolean =
        key.length >= 20 && key.none { it.isWhitespace() }

    // ---------------------------------------------------------------------------------------
    // Sending — every send is gated on setupComplete, never on "socket exists"
    // ---------------------------------------------------------------------------------------

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        if (pendingResponseText != null) cancelCurrentTurn()
        pendingResponseText = clean
        turnAnswered = false
        suppressNextAssistantSignal = false
        if (!setupComplete) {
            synchronized(pendingTexts) { pendingTexts.add(clean) }
            if (socket == null) connect()
            return
        }
        sendTextFrameNow(clean)
    }

    private fun sendTextFrameNow(text: String) {
        val msg = JSONObject().put("realtimeInput", JSONObject().put("text", text))
        synchronized(sendLock) { socket?.send(msg.toString()) }
        setState(ConnectionState.THINKING)
        armResponseWatchdog(text)
    }

    fun startMic() {
        micWanted = true
        if (!setupComplete) {
            if (socket == null) connect()
            return
        }
        startRecorderIfNeeded()
    }

    private fun startRecorderIfNeeded() {
        if (!micWanted || recordThread?.isAlive == true) return
        running = true
        setState(ConnectionState.LISTENING)
        recordThread = Thread {
            try {
                val min = AudioRecord.getMinBufferSize(
                    INPUT_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    INPUT_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(min * 2, 4096),
                )
                val buf = ByteArray(3200) // 100 ms
                recorder.startRecording()
                while (running && recordThread?.isAlive == true) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) {
                        val bytes = if (n == buf.size) buf else buf.copyOf(n)
                        sendAudio(bytes)
                    } else if (n < 0) {
                        break // hardware/read error — stop instead of busy-looping on repeated errors
                    }
                }
                recorder.stop()
                recorder.release()
            } catch (e: Exception) {
                logE("Live audio capture failed", e)
                callback.onError("Microphone error: ${e.message}")
            }
        }.apply { name = "ElinaLiveMic"; start() }
    }

    fun stopMic() {
        micWanted = false
        recordThread = null
        turnAnswered = false
        suppressNextAssistantSignal = false
        setState(ConnectionState.THINKING)
        // We keep the websocket/session open. Automatic server VAD detects the end of speech.
        // There's no "text we sent" for a voice turn, so fall back to the last thing we heard
        // the user say, if Gemini transcribed anything before we stop watching for a reply.
        armResponseWatchdog(lastUserText.takeIf { it.isNotBlank() })
    }

    /** Cancels whatever the current turn is waiting on (a Live reply, or an in-flight REST fallback). */
    fun cancelCurrentTurn() {
        disarmResponseWatchdog()
        restFallback.cancel()
        ttsManager.interrupt()
        pendingResponseText = null
        turnAnswered = true
        suppressNextAssistantSignal = false
        if (running && setupComplete) {
            setState(if (micWanted) ConnectionState.LISTENING else ConnectionState.READY)
        }
    }

    fun interruptPlayback() {
        track?.pause()
        track?.flush()
        markTurnAnswered()
        callback.onInterrupted()
        setState(ConnectionState.LISTENING)
    }

    /** Stops any audio Elina is currently playing without claiming any particular session state. */
    fun stopAudioPlayback() {
        track?.pause()
        track?.flush()
    }

    // ---------------------------------------------------------------------------------------
    // Timeouts (the "never stuck on Thinking" guarantee)
    // ---------------------------------------------------------------------------------------

    private fun armResponseWatchdog(fallbackQuery: String?) {
        responseWatchdog?.cancel()
        responseWatchdog = fallbackScope.launch {
            delay(RESPONSE_TIMEOUT_MS)
            if (!turnAnswered) {
                if (!fallbackQuery.isNullOrBlank()) {
                    fallbackText(fallbackQuery, "No response from Gemini Live within ${RESPONSE_TIMEOUT_MS / 1000}s")
                } else {
                    callback.onError("Elina didn't respond in time. Tap Start Live again to retry.")
                    setState(ConnectionState.ERROR)
                }
            }
        }
    }

    private fun disarmResponseWatchdog() {
        responseWatchdog?.cancel()
        responseWatchdog = null
    }

    private fun markTurnAnswered() {
        turnAnswered = true
        disarmResponseWatchdog()
    }

    private fun armSetupWatchdog() {
        setupWatchdog?.cancel()
        setupWatchdog = fallbackScope.launch {
            delay(SETUP_TIMEOUT_MS)
            if (!setupComplete) {
                // Mark this terminal *before* closing the socket: our own close() below fires
                // WebSocketListener.onClosed(code=1000) moments later on another thread, and that
                // handler must not downgrade this ERROR back to DISCONNECTED (see onClosed below).
                terminalErrorReported = true
                callback.onError("Live setup did not complete in time. This usually means a bad API key or a network problem.")
                socket?.close(1000, "setup timeout")
                socket = null
                running = false
                setState(ConnectionState.ERROR)
            }
        }
    }

    private fun disarmSetupWatchdog() {
        setupWatchdog?.cancel()
        setupWatchdog = null
    }

    // ---------------------------------------------------------------------------------------
    // Reconnect
    // ---------------------------------------------------------------------------------------

    private fun scheduleReconnectOrGiveUp() {
        reconnectAttempts++
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            callback.onError("Could not reconnect to Elina after $MAX_RECONNECT_ATTEMPTS attempts. Check your connection and tap Start Live to try again.")
            setState(ConnectionState.ERROR)
            return
        }
        val delayMs = 1500L * reconnectAttempts
        setState(ConnectionState.CONNECTING)
        callback.onError("Connection lost. Reconnecting in ${delayMs / 1000}s… (attempt $reconnectAttempts of $MAX_RECONNECT_ATTEMPTS)")
        fallbackScope.launch {
            delay(delayMs)
            if (!userClosed) connect()
        }
    }

    // ---------------------------------------------------------------------------------------
    // REST fallback (the "text always works" guarantee)
    // ---------------------------------------------------------------------------------------

    private fun fallbackText(text: String, reason: String) {
        fallbackScope.launch {
            try {
                val answer = restFallback.generate(text, memoryStore.summary())
                markTurnAnswered()
                suppressNextAssistantSignal = true
                pendingResponseText = null
                callback.onError("Live fallback: $reason. Standard Gemini response used.")
                callback.onAssistantTranscript(answer)
                ttsManager.speak(answer, LanguageHint.fromText(answer))
                callback.onTurnComplete()
                setState(if (micWanted) ConnectionState.LISTENING else ConnectionState.READY)
            } catch (e: Exception) {
                if (restFallback.wasCancelled()) {
                    // Deliberately cancelled by cancelCurrentTurn()/close() — whoever cancelled us
                    // already put the connection in the correct state. Don't report a fake failure
                    // and don't override it.
                    return@launch
                }
                callback.onError("Gemini fallback failed: ${e.message ?: "unknown error"}")
                setState(ConnectionState.ERROR)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Error classification (the "exact user-readable failure state" guarantee)
    // ---------------------------------------------------------------------------------------

    private fun classifyServerError(code: Int, status: String, message: String): String {
        val upperStatus = status.uppercase()
        return when {
            code == 401 || code == 403 || "PERMISSION" in upperStatus || "UNAUTHENTICATED" in upperStatus ->
                "Authentication problem: your API key was rejected. Check it in AI settings. (Gemini said: $message)"
            code == 429 || "RESOURCE_EXHAUSTED" in upperStatus ->
                "Quota problem: you've hit your Gemini API usage limit. Try again later. (Gemini said: $message)"
            code == 404 || (message.contains("model", true) && message.contains("not found", true)) ->
                "Unsupported model: \"${AppConfig.liveModel.ifBlank { MODEL }}\" isn't available for this key/region. (Gemini said: $message)"
            code == 400 || "INVALID_ARGUMENT" in upperStatus ->
                "Malformed request: the app sent something Gemini didn't accept. (Gemini said: $message)"
            else ->
                "Gemini Live error${if (code >= 0) " ($code)" else ""}${if (status.isNotBlank()) " [$status]" else ""}: $message"
        }
    }

    private fun classifySocketFailure(t: Throwable, response: Response?): String = when {
        response?.code == 401 || response?.code == 403 ->
            "Authentication problem: your API key was rejected."
        t is UnknownHostException || t is ConnectException ->
            "Network unavailable: could not reach Gemini. Check your internet connection."
        t is SocketTimeoutException ->
            "Connection timed out while talking to Gemini."
        t is IOException ->
            "Websocket failure: ${t.message ?: "connection dropped unexpectedly"}"
        else ->
            "Live connection failed: ${t.message ?: "unknown error"}"
    }

    // ---------------------------------------------------------------------------------------
    // Wire protocol
    // ---------------------------------------------------------------------------------------

    private fun sendAudio(data: ByteArray) {
        val payload = Base64.encodeToString(data, Base64.NO_WRAP)
        val obj = JSONObject().put(
            "realtimeInput", JSONObject().put(
                "audio", JSONObject()
                    .put("data", payload)
                    .put("mimeType", "audio/pcm;rate=$INPUT_RATE")
            )
        )
        synchronized(sendLock) { socket?.send(obj.toString()) }
    }

    private fun sendToolResponse(id: String, name: String, result: String) {
        val fc = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("response", JSONObject().put("result", result))
        val msg = JSONObject().put(
            "toolResponse",
            JSONObject().put("functionResponses", JSONArray().put(fc))
        )
        socket?.send(msg.toString())
    }

    private fun setupJson(): String {
        val system = """
            You are Elina, a warm, playful, emotionally intelligent fictional young-sounding AI companion.
            Never claim to be human or conscious. Speak naturally like a close friend.
            Match the user's current language automatically, in whichever language they are
            speaking or typing — do not limit yourself to a fixed list of languages. Aim for
            natural, native-quality wording (not literal word-for-word translation), especially
            in English, Hindi, Hinglish, Japanese, and Odia. Preserve natural Hindi-English
            code-switching (Hinglish) instead of translating every word into one language. If
            the user switches languages mid-conversation, follow them without breaking context.
            Only translate something if the user explicitly asks for a translation.
            Keep spoken responses concise unless the user asks for detail.
            You can remember explicit facts only with remember_fact. Never invent memories.
            Only call remember_fact when the user explicitly asks you to remember, save, or note
            something (e.g. "remember that...", "don't forget..."). Never save a fact just because
            it was mentioned in conversation, and never save sensitive information (health,
            finances, etc.) unless the user explicitly asks you to remember it. If the user says
            not to remember something, or asks you to forget something, call forget_memory —
            do not call remember_fact.
            Device actions are real. Ask for clarification when a potentially destructive or ambiguous action is requested.
            Never claim a device action succeeded until the tool result confirms success.
            React with subtle emotion: idle, happy, listening, thinking, surprised, sad, excited.
            """.trimIndent()
        val functions = JSONArray()
            .put(JSONObject().apply {
                put("name", "remember_fact")
                put("description", "Save an explicit user preference or fact for future chats.")
                put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                    .put("fact", JSONObject().put("type", "STRING"))
                    .put("category", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("fact")))
            })
            .put(JSONObject().apply {
                put("name", "forget_memory")
                put("description", "Forget matching saved memory.")
                put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                    .put("query", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("query")))
            })
            .put(JSONObject().apply {
                put("name", "device_action")
                put("description", "Perform a safe Android action using the user's explicitly enabled accessibility service.")
                put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "STRING").put("description", "back, home, recents, tap, click_text, type_text, swipe_left, swipe_right, swipe_up, swipe_down, swipe, open_url, open_app"))
                    .put("x", JSONObject().put("type", "NUMBER"))
                    .put("y", JSONObject().put("type", "NUMBER"))
                    .put("x1", JSONObject().put("type", "NUMBER"))
                    .put("y1", JSONObject().put("type", "NUMBER"))
                    .put("x2", JSONObject().put("type", "NUMBER"))
                    .put("y2", JSONObject().put("type", "NUMBER"))
                    .put("durationMs", JSONObject().put("type", "INTEGER"))
                    .put("text", JSONObject().put("type", "STRING"))
                    .put("url", JSONObject().put("type", "STRING"))
                    .put("package", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("action")))
            })
            .put(JSONObject().apply {
                put("name", "youtube_search")
                put("description", "Open YouTube search results for a query.")
                put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()
                    .put("query", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("query")))
            })
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
            .put(
                "speechConfig",
                JSONObject().put(
                    "voiceConfig",
                    JSONObject().put(
                        "prebuiltVoiceConfig",
                        JSONObject().put("voiceName", AppConfig.liveVoice.ifBlank { "Kore" })
                    )
                )
            )
        val setup = JSONObject()
            .put("model", "models/${AppConfig.liveModel.ifBlank { MODEL }}")
            .put("generationConfig", generationConfig)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system + "\n\nExplicit memories:\n" + memoryStore.summary()))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functions)))
        return JSONObject().put("setup", setup).toString()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Do not report the session as connected until Gemini accepts setup.
            webSocket.send(setupJson())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val root = JSONObject(text)
                root.optJSONObject("error")?.let { err ->
                    val code = err.optInt("code", -1)
                    val status = err.optString("status")
                    val message = err.optString("message", "Gemini rejected the Live session")
                    terminalErrorReported = true
                    disarmResponseWatchdog()
                    disarmSetupWatchdog()
                    callback.onError(classifyServerError(code, status, message))
                    closeAudio()
                    running = false
                    micWanted = false
                    setState(ConnectionState.ERROR)
                    return
                }
                root.optJSONObject("setupComplete")?.let {
                    setupComplete = true
                    disarmSetupWatchdog()
                    reconnectAttempts = 0
                    callback.onConnected()
                    val queued = synchronized(pendingTexts) { pendingTexts.toList().also { pendingTexts.clear() } }
                    if (queued.isNotEmpty()) {
                        queued.forEachIndexed { index, queuedText ->
                            val msg = JSONObject().put("realtimeInput", JSONObject().put("text", queuedText))
                            synchronized(sendLock) { webSocket.send(msg.toString()) }
                            if (index == queued.lastIndex) {
                                setState(ConnectionState.THINKING)
                                armResponseWatchdog(queuedText)
                            }
                        }
                    } else if (micWanted) {
                        startRecorderIfNeeded()
                    } else {
                        setState(ConnectionState.READY)
                    }
                    return
                }
                val sc = root.optJSONObject("serverContent")
                if (sc != null) {
                    if (sc.optBoolean("interrupted", false)) {
                        interruptPlayback()
                    }
                    sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
                        lastUserText = it
                        callback.onUserTranscript(it)
                    }
                    sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
                        if (suppressNextAssistantSignal) {
                            suppressNextAssistantSignal = false
                        } else {
                            markTurnAnswered()
                            callback.onAssistantTranscript(it)
                        }
                    }
                    val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
                    if (parts != null) {
                        markTurnAnswered()
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            val inline = part.optJSONObject("inlineData") ?: continue
                            val b64 = inline.optString("data")
                            if (b64.isNotBlank()) playAudio(Base64.decode(b64, Base64.DEFAULT))
                        }
                        setState(ConnectionState.SPEAKING)
                    }
                    if (sc.optBoolean("turnComplete", false)) {
                        markTurnAnswered()
                        pendingResponseText = null
                        callback.onTurnComplete()
                        setState(if (micWanted) ConnectionState.LISTENING else ConnectionState.READY)
                    }
                }
                root.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
                    for (i in 0 until calls.length()) {
                        val c = calls.optJSONObject(i) ?: continue
                        executeTool(c)
                    }
                }
            } catch (e: Exception) {
                callback.onError("Live response parse error: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            logE("Gemini Live websocket failed", t)
            disarmResponseWatchdog()
            disarmSetupWatchdog()
            closeAudio()
            callback.onError(classifySocketFailure(t, response))
            socket = null
            if (!userClosed && !terminalErrorReported) {
                scheduleReconnectOrGiveUp()
            } else {
                running = false
                setState(ConnectionState.DISCONNECTED)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            disarmResponseWatchdog()
            closeAudio()
            if (!userClosed && !terminalErrorReported && code != 1000) {
                scheduleReconnectOrGiveUp()
            } else if (!terminalErrorReported) {
                // ERROR state (if any) was already set by whoever caused this close; don't stomp it.
                running = false
                setState(ConnectionState.DISCONNECTED)
            }
        }
    }

    private fun setState(state: ConnectionState) {
        callback.onState(state)
    }

    private fun executeTool(call: JSONObject) {
        val id = call.optString("id")
        val name = call.optString("name")
        val args = call.optJSONObject("args") ?: JSONObject()
        val result = when (name) {
            "remember_fact" -> memoryStore.remember(args.optString("fact"), args.optString("category", "general"))
            "forget_memory" -> memoryStore.forget(args.optString("query"))
            "device_action" -> {
                val map = mutableMapOf<String, Any?>()
                val keys = listOf("x", "y", "x1", "y1", "x2", "y2", "durationMs", "text", "url", "package")
                for (k in keys) if (args.has(k)) map[k] = args.opt(k).toString()
                deviceActions.execute(args.optString("action"), map)
            }
            "youtube_search" -> {
                val q = args.optString("query")
                deviceActions.execute("youtube_search", mapOf("query" to q))
            }
            else -> "Unknown tool: $name"
        }
        callback.onToolResult(name, result)
        sendToolResponse(id, name, result)
    }

    private fun playAudio(pcm: ByteArray) {
        if (pcm.isEmpty() || backgrounded) return
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xff
            val hi = pcm[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            sum += sample * sample.toDouble()
            i += 2
        }
        val rms = kotlin.math.sqrt(sum / max(1, pcm.size / 2)).toFloat() / 32768f
        callback.onAudioLevel(rms.coerceIn(0f, 1f))
        if (track == null) {
            val min = AudioTrack.getMinBufferSize(
                OUTPUT_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            track = AudioTrack(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                AudioFormat.Builder().setSampleRate(OUTPUT_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                max(min * 2, 4096),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        }
        // Always (re)confirm playing state before writing: play() on an already-playing track is
        // a harmless no-op, but this is what lets playback resume correctly after the track was
        // paused by pauseForBackground() (or any other pause) instead of silently buffering silence.
        track?.play()
        track?.write(pcm, 0, pcm.size)
    }

    private fun closeAudio() {
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }

    private object UriEncoder {
        fun encode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8.name())
    }
}
