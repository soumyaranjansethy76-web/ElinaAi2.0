package com.elina.assistant.ui

import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Bridge that forwards Kotlin → JS commands into the avatar WebView.
 *
 * The WebView's avatar.html exposes `window.ElinaAI = { setEmotion, setSpeaking, wave }`.
 * We call those from Android via [WebView.evaluateJavascript].
 *
 * Reverse channel (JS → Kotlin) is also wired here as @JavascriptInterface
 * so the avatar.html can report ready / errors back to the app.
 */
class AvatarBridge(private val webView: WebView) {

    fun setEmotion(name: String) = run("window.ElinaAI?.setEmotion('$name')")

    fun setSpeaking(active: Boolean) =
        run("window.ElinaAI?.setSpeaking(${if (active) "true" else "false"})")

    fun wave() = run("window.ElinaAI?.wave()")

    fun setMouthLevel(level: Float) = run("window.ElinaAI?.setMouthLevel(${level.coerceIn(0f, 1f)})")

    private fun run(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }

    /* ----- JS → Kotlin ----- */

    @JavascriptInterface
    fun onAvatarReady() {
        // Hook: useful for hiding a splash or beginning a scripted tour.
    }

    @JavascriptInterface
    fun onAvatarError(message: String) {
        android.util.Log.w("ElinaAI/Bridge", "Avatar JS error: $message")
    }
}
