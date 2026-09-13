package com.elina.assistant.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionManager {
    fun micGranted(context: Context)=ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    fun accessibilityEnabled(context: Context): Boolean = runCatching { Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)==1 && Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(context.packageName, true)==true }.getOrDefault(false)
    fun overlayGranted(context: Context)=android.provider.Settings.canDrawOverlays(context)
    fun accessibilityIntent()=Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
