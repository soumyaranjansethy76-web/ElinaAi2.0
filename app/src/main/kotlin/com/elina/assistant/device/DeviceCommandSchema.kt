package com.elina.assistant.device

/**
 * The strict, closed set of device actions the AI may request, and the parameters each one
 * requires. This is the "structured command format": nothing outside this table can ever
 * reach AccessibilityService, no matter what free text the model or user produces — only an
 * action name from this exact set, with its exact required parameters, is accepted. This is a
 * pure validation gate; it does not execute anything itself.
 */
object DeviceCommandSchema {
    /** action name (already trim+lowercased) -> required parameter keys. */
    val SUPPORTED: Map<String, List<String>> = mapOf(
        "back" to emptyList(),
        "home" to emptyList(),
        "recents" to emptyList(),
        "tap" to listOf("x", "y"),
        "swipe" to emptyList(), // accepts either a "direction" OR explicit x1/y1/x2/y2 — checked below
        "swipe_left" to emptyList(),
        "swipe_right" to emptyList(),
        "swipe_up" to emptyList(),
        "swipe_down" to emptyList(),
        "type_text" to listOf("text"),
        "click_text" to listOf("text"),
        "open_app" to listOf("package"),
        "open_url" to listOf("url"),
        "youtube_search" to listOf("query"),
    )

    /**
     * Returns null if [action]/[args] pass structural validation (known action, required keys
     * present and non-blank), or a short rejection reason string if not. This only checks
     * shape — type-specific checks (numeric ranges, URL scheme, text length, etc.) still happen
     * in DeviceActionExecutor as they already did before this phase.
     */
    fun validate(action: String, args: Map<String, Any?>): String? {
        val required = SUPPORTED[action] ?: return "invalid command"
        if (action == "swipe") {
            val hasDirection = !args["direction"]?.toString().isNullOrBlank()
            val hasCoords = listOf("x1", "y1", "x2", "y2").all { !args[it]?.toString().isNullOrBlank() }
            if (!hasDirection && !hasCoords) return "invalid command"
            return null
        }
        for (key in required) {
            if (args[key]?.toString()?.trim().isNullOrBlank()) return "invalid command"
        }
        return null
    }
}
