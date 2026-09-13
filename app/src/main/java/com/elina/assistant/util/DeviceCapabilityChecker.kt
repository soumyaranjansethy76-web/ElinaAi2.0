package com.elina.assistant.util

import android.content.Context
import android.os.PowerManager
import android.provider.Settings

data class CapabilityStatus(val accessibility:Boolean,val overlay:Boolean,val wake:Boolean,val batteryOptimization:Boolean)
object DeviceCapabilityChecker {
    fun status(context: Context): CapabilityStatus {
        val pm=context.getSystemService(PowerManager::class.java)
        val ignore=runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
        val wake=Settings.Secure.getString(context.contentResolver,"voice_interaction_service")?.contains(context.packageName,true)==true
        return CapabilityStatus(PermissionManager.accessibilityEnabled(context),PermissionManager.overlayGranted(context),wake,ignore)
    }
}
