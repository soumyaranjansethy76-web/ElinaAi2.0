package com.elina.assistant.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.elina.assistant.ElinaApplication
import com.elina.assistant.databinding.ActivitySettingsBinding
import com.elina.assistant.ui.memory.MemoryActivity
import com.elina.assistant.util.DeviceCapabilityChecker
import com.elina.assistant.util.PermissionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private val app
        get() = application as ElinaApplication

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        lifecycleScope.launch {
            app.preferences.snapshot.first().let { p ->
                b.userName.setText(p.userName)
                b.wakePhrase.setText(p.wakePhrase)
                b.memoryEnabled.isChecked = p.memoryEnabled
                b.backgroundWake.isChecked = p.wakeEnabled

                val personalities = listOf("Companion", "Professional", "Assistant")
                b.personality.adapter = adapter(personalities)
                b.personality.setSelection(
                    personalities.indexOf(p.personality).coerceAtLeast(0)
                )

                val voices = listOf("Aoede", "Puck", "Charon", "Kore")
                b.voice.adapter = adapter(voices)
                b.voice.setSelection(
                    voices.indexOf(p.voice).coerceAtLeast(0)
                )

                val thinkingModes = listOf("minimal", "low", "medium", "high")
                b.thinking.adapter = adapter(thinkingModes)
                b.thinking.setSelection(
                    thinkingModes.indexOf(p.thinking).coerceAtLeast(0)
                )
            }

            b.authStatus.text =
                "API authentication: ${if(app.security.hasCredential()) "configured" else "not configured"}"

            val r = com.elina.assistant.avatar.AvatarAssetLoader(this@SettingsActivity).inspect()
            b.avatarInfo.text =
                "Avatar: Elina VRM\n" +
                    "VRM version: ${r.version}\n" +
                    "Humanoid: ${r.humanoid}\n" +
                    "Spring Bone: ${r.springBone}\n" +
                    "Expressions: ${r.expressions.joinToString()}\n" +
                    "Lip Sync: ${listOf("A", "I", "U", "E", "O").all { r.expressions.contains(it) }}\n" +
                    "LookAt: Limited (VRM 0.x asset)\n" +
                    "SHA-256: ${r.sha256}"

            b.capabilities.text =
                "Microphone: ${PermissionManager.micGranted(this@SettingsActivity)}\n" +
                    "Accessibility: ${PermissionManager.accessibilityEnabled(this@SettingsActivity)}\n" +
                    "Overlay: ${PermissionManager.overlayGranted(this@SettingsActivity)}\n" +
                    "Background wake: ${DeviceCapabilityChecker.status(this@SettingsActivity).wake}"
        }

        b.saveKey.setOnClickListener {
            app.security.saveApiCredential(b.apiKey.text.toString())
            b.apiKey.text.clear()
            b.authStatus.text = "API authentication: configured"
        }

        b.openMemory.setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }

        b.openAccessibility.setOnClickListener {
            startActivity(PermissionManager.accessibilityIntent())
        }

        b.memoryEnabled.setOnCheckedChangeListener { _, value ->
            lifecycleScope.launch {
                app.preferences.setMemoryEnabled(value)
            }
        }

        b.backgroundWake.setOnCheckedChangeListener { _, value ->
            lifecycleScope.launch {
                app.preferences.setWakeEnabled(value)
            }
        }

        b.userName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                lifecycleScope.launch {
                    app.preferences.setUserName(
                        b.userName.text.toString().ifBlank { "Friend" }
                    )
                }
            }
        }
    }

    private fun adapter(values: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            values
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }
}
