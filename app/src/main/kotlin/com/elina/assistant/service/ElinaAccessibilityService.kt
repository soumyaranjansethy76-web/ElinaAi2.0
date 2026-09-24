package com.elina.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional device-control bridge. The user explicitly enables this service in Android settings.
 */
class ElinaAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: ElinaAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // Fires reliably when the user disables the service in Settings — onDestroy alone is
        // not guaranteed to run promptly, which would otherwise leave a stale instance visible
        // to DeviceActionExecutor for a window after accessibility was actually turned off.
        instance = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun globalBack(): String = if (performGlobalAction(GLOBAL_ACTION_BACK)) "Back pressed." else "Back failed."
    fun globalHome(): String = if (performGlobalAction(GLOBAL_ACTION_HOME)) "Home pressed." else "Home failed."
    fun globalRecents(): String = if (performGlobalAction(GLOBAL_ACTION_RECENTS)) "Recents opened." else "Recents failed."

    fun tap(x: Float, y: Float): String {
        val p = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(p, 0, 80)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        return if (ok) "Tapped." else "Tap failed."
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): String {
        val p = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(p, 0, duration)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        return if (ok) "Swipe completed." else "Swipe failed."
    }

    fun clickText(text: String): String {
        val root = rootInActiveWindow ?: return "No active window."
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Clicked $text."
            if (node.parent?.isClickable == true && node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                return "Clicked $text."
            }
        }
        return "Could not find clickable text: $text"
    }

    fun typeText(text: String): String {
        val root = rootInActiveWindow ?: return "No active window."
        val focus = findFocusedEditable(root) ?: return "No editable field is focused."
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            "Text entered."
        } else {
            "Could not enter text."
        }
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditable(child)
            if (found != null) return found
        }
        return null
    }
}
