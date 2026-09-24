package com.elina.assistant.device

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.elina.assistant.service.ElinaAccessibilityService

/** Safe, small set of actions exposed to the model. */
class DeviceActionExecutor(private val context: Context) {
    private val lock = Any()
    private var lastActionKey: String? = null
    private var lastActionAt: Long = 0L

    fun accessibilityEnabled(): Boolean = ElinaAccessibilityService.instance != null

    fun openAccessibilitySettings() {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private val disabledMessage =
        "FAILED: Accessibility service is not enabled. Ask the user to open Settings > Accessibility > Elina and turn it on."

    fun execute(action: String, args: Map<String, Any?>): String = synchronized(lock) {
        // Serialize every device action through this lock so gestures/typing never overlap,
        // and reject an identical action repeated within 300ms (accidental double-fire —
        // e.g. a duplicated tool call — not a user's own deliberate rapid repeat, which in
        // practice is always paced by normal conversation turn-taking).
        val key = "$action|" + args.entries.sortedBy { it.key.toString() }.joinToString(",") { "${it.key}=${it.value}" }
        val now = System.currentTimeMillis()
        if (key == lastActionKey && now - lastActionAt < 300L) {
            return@synchronized "FAILED: Ignored — the same action was just executed a moment ago."
        }

        val service = ElinaAccessibilityService.instance
        val result: String = when (action) {
            "back" -> service?.let { tagCore(it.globalBack()) } ?: disabledMessage
            "home" -> service?.let { tagCore(it.globalHome()) } ?: disabledMessage
            "recents" -> service?.let { tagCore(it.globalRecents()) } ?: disabledMessage
            "tap" -> {
                if (service == null) disabledMessage
                else {
                    val w = context.resources.displayMetrics.widthPixels.toFloat()
                    val h = context.resources.displayMetrics.heightPixels.toFloat()
                    val x = args["x"]?.toString()?.toFloatOrNull()
                    val y = args["y"]?.toString()?.toFloatOrNull()
                    when {
                        x == null -> "FAILED: Invalid or missing x coordinate."
                        y == null -> "FAILED: Invalid or missing y coordinate."
                        x < 0f || x > w || y < 0f || y > h ->
                            "FAILED: Coordinates ($x, $y) are outside the screen (${w.toInt()}x${h.toInt()})."
                        else -> tagCore(service.tap(x, y))
                    }
                }
            }
            "swipe" -> {
                if (service == null) disabledMessage
                else {
                    val direction = args["direction"]?.toString()?.trim()?.uppercase()
                    if (!direction.isNullOrBlank()) {
                        // Structured form: SWIPE { direction: "LEFT" } — same underlying gesture
                        // as the swipe_left/right/up/down actions below, just addressed by a
                        // single action name with a validated direction parameter.
                        directionalSwipe(direction, args, service)
                    } else {
                        val w = context.resources.displayMetrics.widthPixels.toFloat()
                        val h = context.resources.displayMetrics.heightPixels.toFloat()
                        val x1 = args["x1"]?.toString()?.toFloatOrNull()
                        val y1 = args["y1"]?.toString()?.toFloatOrNull()
                        val x2 = args["x2"]?.toString()?.toFloatOrNull()
                        val y2 = args["y2"]?.toString()?.toFloatOrNull()
                        when {
                            x1 == null || y1 == null -> "FAILED: Invalid swipe start coordinates."
                            x2 == null || y2 == null -> "FAILED: Invalid swipe end coordinates."
                            x1 < 0f || x1 > w || y1 < 0f || y1 > h || x2 < 0f || x2 > w || y2 < 0f || y2 > h ->
                                "FAILED: Swipe coordinates are outside the screen (${w.toInt()}x${h.toInt()})."
                            else -> tagCore(
                                service.swipe(x1, y1, x2, y2, args["durationMs"]?.toString()?.toLongOrNull()?.coerceIn(100, 2000) ?: 350L),
                            )
                        }
                    }
                }
            }
            "swipe_left", "swipe_right", "swipe_up", "swipe_down" -> {
                if (service == null) disabledMessage
                else directionalSwipe(action.removePrefix("swipe_").uppercase(), args, service)
            }
            "type_text" -> {
                if (service == null) disabledMessage
                else {
                    // Text content itself is never logged anywhere in this path — only whether
                    // the action succeeded is reported back.
                    val text = args["text"]?.toString().orEmpty()
                    when {
                        text.isBlank() -> "FAILED: No text provided to type."
                        text.length > 2000 -> "FAILED: Text is too long."
                        else -> tagCore(service.typeText(text))
                    }
                }
            }
            "click_text" -> {
                if (service == null) "Accessibility service is not enabled."
                else service.clickText(args["text"]?.toString().orEmpty())
            }
            "open_url" -> {
                val raw = args["url"]?.toString().orEmpty().trim()
                val uri = if (raw.isBlank()) null else try { Uri.parse(raw) } catch (e: Exception) { null }
                when {
                    raw.isBlank() -> "FAILED: Missing URL."
                    uri == null -> "FAILED: Malformed URL."
                    uri.scheme?.lowercase() !in setOf("http", "https") -> "FAILED: Only http/https URLs are allowed."
                    uri.host.isNullOrBlank() -> "FAILED: URL is missing a host."
                    else -> try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "SUCCESS: Opened URL."
                    } catch (e: Exception) {
                        "FAILED: Could not open URL."
                    }
                }
            }
            "open_app" -> openApp(args["package"]?.toString().orEmpty())
            "youtube_search" -> {
                val query = args["query"]?.toString()?.trim().orEmpty()
                when {
                    query.isBlank() -> "FAILED: No search query provided."
                    query.length > 200 -> "FAILED: Search query is too long."
                    else -> {
                        val encoded = try {
                            java.net.URLEncoder.encode(query, "UTF-8")
                        } catch (e: Exception) {
                            null
                        }
                        if (encoded == null) {
                            "FAILED: Could not encode search query."
                        } else {
                            // Try the YouTube app's own search deep link first; only dispatch it
                            // if something can actually resolve it, otherwise fall back to the
                            // universal web results page — no screen scraping either way.
                            val nativeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://results?q=$encoded"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            val canUseApp = nativeIntent.resolveActivity(context.packageManager) != null
                            try {
                                if (canUseApp) {
                                    context.startActivity(nativeIntent)
                                    "SUCCESS: Opened YouTube search in the app."
                                } else {
                                    val webUri = Uri.parse("https://www.youtube.com/results?search_query=$encoded")
                                    context.startActivity(Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    "SUCCESS: Opened YouTube search in the browser."
                                }
                            } catch (e: Exception) {
                                "FAILED: Could not open YouTube search."
                            }
                        }
                    }
                }
            }
            else -> "FAILED: Invalid command ($action)."
        }
        lastActionKey = key
        lastActionAt = now
        result
    }

    /** Shared by the swipe_left/right/up/down actions and the structured swipe{direction} form. */
    private fun directionalSwipe(direction: String, args: Map<String, Any?>, service: ElinaAccessibilityService): String {
        val w = context.resources.displayMetrics.widthPixels.toFloat()
        val h = context.resources.displayMetrics.heightPixels.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val dx = w * 0.38f
        val dy = h * 0.32f
        val (x1, y1, x2, y2) = when (direction) {
            "LEFT" -> listOf(cx + dx, cy, cx - dx, cy)
            "RIGHT" -> listOf(cx - dx, cy, cx + dx, cy)
            "UP" -> listOf(cx, cy + dy, cx, cy - dy)
            "DOWN" -> listOf(cx, cy - dy, cx, cy + dy)
            else -> return "FAILED: Invalid swipe direction ($direction)."
        }
        return tagCore(service.swipe(x1, y1, x2, y2, args["durationMs"]?.toString()?.toLongOrNull()?.coerceIn(100, 2000) ?: 350L))
    }

    /** Applies an explicit SUCCESS:/FAILED: prefix to the fixed set of strings the 9 Phase 6A
     * core actions can return. Not used for click_text (its message can echo arbitrary
     * on-screen text, which could coincidentally contain one of these marker words) —
     * open_url/open_app/youtube_search now self-tag directly instead. */
    private fun tagCore(message: String): String {
        val lower = message.lowercase()
        val failed = lower.contains("failed") || lower.contains("could not")
        return if (failed) "FAILED: $message" else "SUCCESS: $message"
    }

    private fun String.toFloatOrNull(): Float? = trim().toFloatOrNull()
    private fun String.toLongOrNull(): Long? = trim().toLongOrNull()

    /** A small, fixed set of common apps so "open YouTube" works without the model needing to
     * know the exact package name. Anything containing a dot is treated as an explicit,
     * already-validated package name instead. */
    private val knownApps = mapOf(
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "settings" to "com.android.settings",
        "play store" to "com.android.vending",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
    )

    private fun openApp(nameOrPackage: String): String {
        val input = nameOrPackage.trim()
        if (input.isBlank()) return "FAILED: Missing app name or package."
        val packageName = if ('.' in input) input else knownApps[input.lowercase()] ?: input
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return "FAILED: App not found or not installed ($packageName)."
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "SUCCESS: Opened app."
        } catch (e: Exception) {
            "FAILED: Could not open app."
        }
    }
}
