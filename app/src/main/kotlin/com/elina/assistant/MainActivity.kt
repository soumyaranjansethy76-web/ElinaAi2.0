package com.elina.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.elina.assistant.chat.AvatarCommand
import com.elina.assistant.chat.ChatViewModel
import com.elina.assistant.data.AppDatabase
import com.elina.assistant.data.ChatRepository
import com.elina.assistant.databinding.ActivityMainBinding
import com.elina.assistant.device.DeviceActionExecutor
import com.elina.assistant.live.ConnectionState
import com.elina.assistant.live.GeminiLiveManager
import com.elina.assistant.memory.MemoryStore
import com.elina.assistant.service.ElinaAccessibilityService
import com.elina.assistant.service.WakeWordService
import com.elina.assistant.service.WakeWordState
import com.elina.assistant.voice.AndroidTtsManager
import com.elina.assistant.voice.TtsManager
import com.elina.assistant.ui.AvatarBridge
import com.elina.assistant.ui.ChatAdapter
import com.elina.assistant.util.AppConfig
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var avatarBridge: AvatarBridge? = null
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var viewModel: ChatViewModel
    private lateinit var live: GeminiLiveManager
    private lateinit var ttsManager: TtsManager
    private var liveActive = false
    private var wakeEnabled = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast("Microphone permission is required for live voice.")
    }

    private val wakeWordMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableWakeWord() else toast("Microphone permission is required for \"Hey Elina\".")
    }

    // Best-effort only: on API 33+ this controls whether the listening notification can show.
    // Wake word still works without it — the platform just won't display that notification.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppConfig.load(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Paint the real state immediately — never let the static layout text ("Elina is ready")
        // show before we actually know whether an API key is configured.
        renderState(ConnectionState.DISCONNECTED)
        createViewModel()
        setupAvatar()
        setupChatList()
        setupTopBar()
        setupWakeWord()
        setupLiveButton()
        setupTextInput()
        observeViewModel()
        ensureMicPermission()
        viewModel.ensureConversation()
        if (intent.getBooleanExtra("WAKE_UP", false)) {
            window.decorView.postDelayed({
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    if (!liveActive) binding.liveButton.performClick()
                }
            }, 500)
        }
    }

    override fun onPause() {
        super.onPause()
        // Release mic/playback hardware immediately when we leave the foreground — Android
        // does not reliably let a backgrounded app hold either, and the Live session itself
        // (socket, setup, pending turn) is left untouched so it can resume without reconnecting.
        if (::live.isInitialized) live.pauseForBackground()
    }

    override fun onResume() {
        super.onResume()
        if (::live.isInitialized) live.resumeFromBackground()
    }

    override fun onDestroy() {
        WakeWordState.setListener(null)
        // ChatViewModel.onCleared() (which also calls live.close()) never actually fires under
        // this app's architecture — ChatViewModel is constructed directly here, not obtained via
        // ViewModelProvider, so there is no ViewModelStore to invoke it. This is the real
        // lifecycle owner, so this is where the Live session must actually be torn down —
        // without it, the WebSocket, audio resources, and every watchdog/reconnect coroutine
        // would otherwise keep running indefinitely, holding a reference to this destroyed
        // Activity through the connection callback.
        if (::live.isInitialized) live.close()
        if (::ttsManager.isInitialized) ttsManager.release()
        super.onDestroy()
    }

    private fun createViewModel() {
        val repo = ChatRepository(AppDatabase.get(applicationContext))
        val memory = MemoryStore(applicationContext)
        val device = DeviceActionExecutor(applicationContext)
        val tts = AndroidTtsManager(applicationContext)
        ttsManager = tts
        lifecycleScope.launch { tts.init() }
        live = GeminiLiveManager(
            apiKeyProvider = { AppConfig.llmApiKey },
            memoryStore = memory,
            deviceActions = device,
            ttsManager = tts,
            callback = object : GeminiLiveManager.Callback {
                // This is the ONLY place a ConnectionState reaches the ViewModel — nothing else
                // in the UI layer is allowed to guess or set it independently.
                override fun onState(state: ConnectionState) {
                    runOnUiThread {
                        viewModel.onConnectionState(state)
                        if (state == ConnectionState.LISTENING) binding.liveButton.isSelected = true
                        if (state == ConnectionState.SPEAKING) viewModel.onLiveSpeaking()
                    }
                }
                override fun onAudioLevel(level: Float) = runOnUiThread { avatarBridge?.setMouthLevel(level) }
                override fun onUserTranscript(text: String) = runOnUiThread { viewModel.onLiveUserTranscript(text) }
                override fun onAssistantTranscript(text: String) = runOnUiThread { viewModel.onLiveAssistantTranscript(text) }
                override fun onAvatarEmotion(name: String) = runOnUiThread { avatarBridge?.setEmotion(name) }
                override fun onToolResult(name: String, result: String) = runOnUiThread { viewModel.onToolResult(name, result) }
                override fun onInterrupted() = runOnUiThread { viewModel.onLiveInterrupted() }
                override fun onTurnComplete() = runOnUiThread { viewModel.onLiveTurnComplete() }
                override fun onError(message: String) = runOnUiThread { viewModel.onLiveStateError(message) }
                override fun onConnected() = runOnUiThread { viewModel.onLiveConnected() }
            }
        )
        viewModel = ChatViewModel(repo, live, memory)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setupAvatar() {
        try {
            val webView = android.webkit.WebView(this)
            webView.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            )
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.mediaPlaybackRequiresUserGesture = false
            webView.settings.allowFileAccess = false
            webView.setBackgroundColor(0)
            val bridge = AvatarBridge(webView)
            avatarBridge = bridge
            webView.addJavascriptInterface(bridge, "ElinaAIBridge")
            val assetLoader = androidx.webkit.WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this))
                .build()
            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(view: android.webkit.WebView, request: android.webkit.WebResourceRequest) = assetLoader.shouldInterceptRequest(request.url)
            }
            binding.avatarContainer.addView(webView)
            webView.loadUrl("https://appassets.androidplatform.net/assets/avatar/index.html")
        } catch (e: Throwable) {
            // A WebView provider missing/disabled/mid-update on this device throws here —
            // this is a real, device-dependent Android failure mode, not hypothetical. The
            // avatar just won't render; the rest of the app (chat, voice, memory, device
            // control) must still work, so this is caught here instead of taking down onCreate.
            android.util.Log.e("MainActivity", "Avatar WebView unavailable on this device", e)
            binding.avatarSubtitle.text = "Avatar unavailable on this device (WebView failed to load). Chat and voice still work."
        }
    }

    private fun setupChatList() {
        chatAdapter = ChatAdapter()
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = chatAdapter
        }
    }

    private fun setupTopBar() {
        binding.topBar.setOnMenuItemClickListener { item -> handleMenu(item) }
    }

    private fun handleMenu(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_history -> {
            startActivity(Intent(this, HistoryActivity::class.java)); true
        }
        R.id.action_clear -> {
            viewModel.clearCurrentConversation(); true
        }
        R.id.action_settings -> {
            showSettings(); true
        }
        R.id.action_device_controls -> {
            if (ElinaAccessibilityService.instance == null) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            else toast("Device control is enabled.")
            true
        }
        R.id.action_wake_word -> {
            toggleWakeWord(); true
        }
        R.id.action_memory -> {
            showMemoryInfo(); true
        }
        else -> false
    }

    private fun setupLiveButton() {
        binding.liveButton.setOnClickListener {
            if (!liveActive) {
                ensureMicPermission()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    liveActive = true
                    binding.liveButton.isSelected = true
                    binding.liveButton.text = getString(R.string.stop_live_label)
                    viewModel.startLive()
                    animateLive(true)
                }
            } else {
                liveActive = false
                binding.liveButton.isSelected = false
                binding.liveButton.text = getString(R.string.start_live_label)
                viewModel.stopLive()
                animateLive(false)
            }
        }
    }

    private fun animateLive(active: Boolean) {
        val scale = if (active) 1.03f else 1f
        binding.avatarContainer.animate().scaleX(scale).scaleY(scale).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        binding.liveButton.animate().alpha(if (active) 1f else 0.88f).setDuration(180).start()
    }

    private fun setupTextInput() {
        binding.sendButton.setOnClickListener {
            val text = binding.messageInput.text?.toString().orEmpty().trim()
            if (text.isNotBlank()) {
                binding.messageInput.text?.clear()
                viewModel.sendText(text)
            }
        }
        binding.messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                binding.sendButton.performClick(); true
            } else false
        }
    }

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)

        // AI / Voice
        val apiKeyStatus = view.findViewById<TextView>(R.id.apiKeyStatus)
        val modelInput = view.findViewById<EditText>(R.id.liveModelInput)
        val voiceInput = view.findViewById<EditText>(R.id.liveVoiceInput)
        fun refreshApiKeyStatus() {
            val configured = AppConfig.hasApiKey()
            apiKeyStatus.text = getString(if (configured) R.string.settings_api_key_configured else R.string.settings_api_key_not_configured)
            apiKeyStatus.setTextColor(ContextCompat.getColor(this, if (configured) R.color.state_ready else R.color.state_error))
        }
        refreshApiKeyStatus()
        modelInput.setText(AppConfig.liveModel)
        voiceInput.setText(AppConfig.liveVoice)
        view.findViewById<View>(R.id.btnEditApiKey).setOnClickListener { showApiKeyEditDialog { refreshApiKeyStatus() } }

        // Memory
        val memoryStatus = view.findViewById<TextView>(R.id.memoryStatus)
        memoryStatus.text = MemoryStore(this).summary(10)
        view.findViewById<View>(R.id.btnClearMemory).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_clear_memory_confirm_title)
                .setMessage(R.string.settings_clear_memory_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_clear_memory) { _, _ ->
                    MemoryStore(this).clear()
                    memoryStatus.text = MemoryStore(this).summary(10)
                    toast("Memory cleared.")
                }
                .show()
        }

        // Device Control (Accessibility)
        val accDot = view.findViewById<View>(R.id.accessibilityDot)
        val accStatus = view.findViewById<TextView>(R.id.accessibilityStatus)
        val accessibilityOn = ElinaAccessibilityService.instance != null
        accStatus.text = getString(if (accessibilityOn) R.string.settings_api_key_configured else R.string.settings_api_key_not_configured)
        accDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (accessibilityOn) R.color.state_ready else R.color.state_error),
        )
        view.findViewById<View>(R.id.btnAccessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Wake Word
        val wakeSwitch = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.wakeWordSwitch)
        val wakeStatusText = view.findViewById<TextView>(R.id.wakeWordStatusText)
        fun renderWakeRow(state: WakeWordState) {
            wakeStatusText.text = when {
                !wakeEnabled -> "Off"
                state == WakeWordState.LISTENING_FOR_WAKE -> "Listening for \"Hey Elina\""
                state == WakeWordState.ERROR -> "Error — turned back off"
                state == WakeWordState.READY -> "Starting…"
                state == WakeWordState.WAKE_DETECTED || state == WakeWordState.STARTING_CONVERSATION -> "Heard you!"
                else -> "On"
            }
            if (wakeSwitch.isChecked != wakeEnabled) wakeSwitch.isChecked = wakeEnabled
        }
        wakeSwitch.isChecked = wakeEnabled
        renderWakeRow(WakeWordState.current)
        wakeSwitch.setOnClickListener { toggleWakeWord() }
        // While this panel is open, the same listener slot also updates this row — restored to
        // the plain toolbar-only listener on dismiss so the menu keeps reflecting state after.
        WakeWordState.setListener { state ->
            runOnUiThread {
                resetWakeWordOnError(state)
                renderWakeRow(state)
                updateWakeWordMenuTitle(state)
            }
        }

        // About
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        view.findViewById<TextView>(R.id.aboutText).text =
            "${getString(R.string.app_name)}${versionName?.let { " · v$it" } ?: ""}\n" +
                "Gemini Live voice, on-device VRM avatar, local memory and device control."

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setView(view)
            .setNegativeButton("Close", null)
            .setPositiveButton("Save") { _, _ ->
                AppConfig.saveLiveModel(this, modelInput.text.toString())
                AppConfig.saveLiveVoice(this, voiceInput.text.toString())
                toast(getString(R.string.settings_saved_reconnect))
            }
            .create()
        dialog.setOnDismissListener { installWakeWordMenuListener() }
        dialog.show()
    }

    private fun showApiKeyEditDialog(onSaved: () -> Unit) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val field = EditText(this).apply {
            hint = if (AppConfig.hasApiKey()) getString(R.string.settings_api_key_hint_replace) else getString(R.string.settings_api_key_label)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        box.addView(field)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_api_key_dialog_title)
            .setMessage(R.string.settings_api_key_dialog_message)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Save") { _, _ ->
                val newKey = field.text.toString().trim()
                if (newKey.isNotBlank()) {
                    AppConfig.save(this, AppConfig.llmBaseUrl, newKey, AppConfig.llmModel)
                    toast(getString(R.string.settings_saved_reconnect))
                    onSaved()
                }
            }
            .show()
    }

    private fun setupWakeWord() {
        wakeEnabled = AppConfig.wakeWordEnabled
        installWakeWordMenuListener()
        if (wakeEnabled) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
            } else {
                // Permission is no longer granted since this was last saved — don't resume a
                // feature Android won't actually let us run.
                wakeEnabled = false
                AppConfig.setWakeWordEnabled(this, false)
            }
        }
    }

    /** Never keep claiming the feature is on once it has actually failed — shared by every
     * WakeWordState listener this Activity installs (the toolbar one and the settings panel's). */
    private fun resetWakeWordOnError(state: WakeWordState) {
        if (state == WakeWordState.ERROR && wakeEnabled) {
            wakeEnabled = false
            AppConfig.setWakeWordEnabled(this, false)
        }
    }

    /** The normal, always-installed listener: keeps the toolbar menu title in sync. Re-installed
     * whenever the settings panel (which temporarily takes over the listener slot) is dismissed. */
    private fun installWakeWordMenuListener() {
        WakeWordState.setListener { state ->
            runOnUiThread {
                resetWakeWordOnError(state)
                updateWakeWordMenuTitle(state)
            }
        }
    }

    private fun updateWakeWordMenuTitle(state: WakeWordState) {
        val label = when {
            !wakeEnabled -> "Hey Elina: Off"
            state == WakeWordState.LISTENING_FOR_WAKE -> "Hey Elina: Listening"
            state == WakeWordState.WAKE_DETECTED || state == WakeWordState.STARTING_CONVERSATION -> "Hey Elina: Heard you!"
            state == WakeWordState.ERROR -> "Hey Elina: Error"
            state == WakeWordState.READY -> "Hey Elina: Starting…"
            else -> "Hey Elina: On"
        }
        binding.topBar.menu?.findItem(R.id.action_wake_word)?.title = label
    }

    private fun toggleWakeWord() {
        if (wakeEnabled) {
            wakeEnabled = false
            AppConfig.setWakeWordEnabled(this, false)
            stopService(Intent(this, WakeWordService::class.java))
            updateWakeWordMenuTitle(WakeWordState.DISABLED)
            toast("Wake word disabled.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setTitle("Microphone needed")
                .setMessage("Elina needs microphone access to listen for \"Hey Elina\" so she can wake up when you call her.")
                .setPositiveButton("Continue") { _, _ -> wakeWordMicPermission.launch(Manifest.permission.RECORD_AUDIO) }
                .setNegativeButton("Not now", null)
                .show()
            return
        }
        enableWakeWord()
    }

    private fun enableWakeWord() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
            wakeEnabled = true
            AppConfig.setWakeWordEnabled(this, true)
            toast("Wake word enabled. Say \"Hey Elina\".")
        } catch (e: Exception) {
            wakeEnabled = false
            AppConfig.setWakeWordEnabled(this, false)
            updateWakeWordMenuTitle(WakeWordState.ERROR)
            toast("Couldn't start wake-word listening on this device.")
        }
    }

    private fun showMemoryInfo() {
        val summary = MemoryStore(this).summary(30)
        AlertDialog.Builder(this)
            .setTitle("Elina memory")
            .setMessage(summary)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * The single place status text is decided. UI state must always derive from the real
     * [ConnectionState] plus the currently configured API key — never from a static default.
     */
    private fun renderState(state: ConnectionState) {
        binding.statusBar.text = when (state) {
            ConnectionState.DISCONNECTED ->
                if (AppConfig.hasApiKey()) getString(R.string.status_idle) else getString(R.string.status_api_key_required)
            ConnectionState.CONNECTING -> getString(R.string.status_connecting)
            ConnectionState.READY -> getString(R.string.status_idle)
            ConnectionState.LISTENING -> getString(R.string.status_listening)
            ConnectionState.THINKING -> getString(R.string.status_thinking)
            ConnectionState.SPEAKING -> getString(R.string.status_speaking)
            ConnectionState.ERROR -> getString(R.string.status_error, viewModel.errorMessage.value ?: "Unknown error")
        }
        val stateColorRes = when (state) {
            ConnectionState.LISTENING -> R.color.state_listening
            ConnectionState.THINKING, ConnectionState.CONNECTING -> R.color.state_thinking
            ConnectionState.SPEAKING -> R.color.state_speaking
            ConnectionState.ERROR -> R.color.state_error
            ConnectionState.READY, ConnectionState.DISCONNECTED -> R.color.state_ready
        }
        val stateColor = ContextCompat.getColor(this, stateColorRes)
        binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(stateColor)
        if (state != ConnectionState.LISTENING) binding.liveButton.isSelected = state == ConnectionState.SPEAKING
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state -> renderState(state) }
                }
                launch {
                    viewModel.messages.collect { msgs ->
                        val lm = binding.chatRecyclerView.layoutManager as LinearLayoutManager
                        val itemsBefore = chatAdapter.itemCount
                        val wasNearBottom = itemsBefore == 0 || lm.findLastVisibleItemPosition() >= itemsBefore - 2
                        chatAdapter.submitList(msgs.filter { it.role.name != "SYSTEM" }) {
                            val last = chatAdapter.itemCount - 1
                            if (last >= 0 && wasNearBottom) binding.chatRecyclerView.scrollToPosition(last)
                        }
                    }
                }
                launch {
                    viewModel.avatarCommands.collect { cmd ->
                        when (cmd) {
                            is AvatarCommand.SetEmotion -> avatarBridge?.setEmotion(cmd.name)
                            is AvatarCommand.SetSpeaking -> avatarBridge?.setSpeaking(cmd.active)
                            is AvatarCommand.Wave -> avatarBridge?.wave()
                            is AvatarCommand.Tool -> avatarBridge?.setEmotion(if (cmd.result.contains("failed", true)) "error" else "happy")
                        }
                    }
                }
            }
        }
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
