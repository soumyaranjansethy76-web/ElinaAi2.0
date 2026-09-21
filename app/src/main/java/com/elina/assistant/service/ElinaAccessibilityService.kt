package com.elina.assistant.service

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import android.media.AudioManager

class ElinaAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: ElinaAccessibilityService? = null
    }

    enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (n in nodes) {
            if (n.isClickable || n.isEnabled) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val n = findEditable(root) ?: return false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle()
        b.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectScrollable(root, nodes)

        for (n in nodes) {
            val action =
                if (direction.lowercase() == "down") {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }

            if (n.performAction(action)) return true
        }
        return false
    }

    fun media(action: MediaAction): Boolean {
        val audioManager = getSystemService(AudioManager::class.java)
        val keyCode = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }

        audioManager.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_UP, keyCode)
        )
        return true
    }

    fun closeCurrentApp(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let {
                findEditable(it)?.let { result -> return result }
            }
        }
        return null
    }

    private fun collectScrollable(
        n: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (n.isScrollable) out.add(n)
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { collectScrollable(it, out) }
        }
    }
}
