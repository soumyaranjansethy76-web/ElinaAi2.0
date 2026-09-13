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

class SettingsActivity:AppCompatActivity(){
    private lateinit var b:ActivitySettingsBinding
    private val app get()=application as ElinaApplication
    override fun onCreate(s:Bundle?){super.onCreate(s);b=ActivitySettingsBinding.inflate(layoutInflater);setContentView(b.root)
        lifecycleScope.launch{
            app.preferences.snapshot.first().let{p->b.userName.setText(p.userName);b.wakePhrase.setText(p.wakePhrase);b.memoryEnabled.isChecked=p.memoryEnabled;b.backgroundWake.isChecked=p.wakeEnabled;b.personality.adapter=adapter(listOf("Companion","Professional","Assistant"),p.personality);b.voice.adapter=adapter(listOf("Aoede","Puck","Charon","Kore"),p.voice);b.thinking.adapter=adapter(listOf("minimal","low","medium","high"),p.thinking)}
            b.authStatus.text="API authentication: ${if(app.security.hasCredential())"configured" else "not configured"}"
            val r=com.elina.assistant.avatar.AvatarAssetLoader(this@SettingsActivity).inspect();b.avatarInfo.text="Avatar: Elina VRM\nVRM version: ${r.version}\nHumanoid: ${r.humanoid}\nSpring Bone: ${r.springBone}\nExpressions: ${r.expressions.joinToString()}\nLip Sync: ${listOf("A","I","U","E","O").all{r.expressions.contains(it)}}\nLookAt: Limited (VRM 0.x asset)\nSHA-256: ${r.sha256}"
            b.capabilities.text="Microphone: ${PermissionManager.micGranted(this@SettingsActivity)}\nAccessibility: ${PermissionManager.accessibilityEnabled(this@SettingsActivity)}\nOverlay: ${PermissionManager.overlayGranted(this@SettingsActivity)}\nBackground wake: ${DeviceCapabilityChecker.status(this@SettingsActivity).wake}"
        }
        b.saveKey.setOnClickListener{app.security.saveApiCredential(b.apiKey.text.toString());b.apiKey.text.clear();b.authStatus.text="API authentication: configured"}
        b.openMemory.setOnClickListener{startActivity(Intent(this,MemoryActivity::class.java))}
        b.openAccessibility.setOnClickListener{startActivity(PermissionManager.accessibilityIntent())}
        b.memoryEnabled.setOnCheckedChangeListener{_,v->lifecycleScope.launch{app.preferences.setMemoryEnabled(v)}}
        b.backgroundWake.setOnCheckedChangeListener{_,v->lifecycleScope.launch{app.preferences.setWakeEnabled(v)}}
        b.userName.setOnFocusChangeListener{_,has->if(!has)lifecycleScope.launch{app.preferences.setUserName(b.userName.text.toString().ifBlank{"Friend"})}}
    }
    private fun adapter(values:List<String>,selected:String)=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,values).also{it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}.apply{setSelection(values.indexOf(selected).coerceAtLeast(0))}
}
