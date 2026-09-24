package com.elina.assistant.util

/**
 * Best-effort language hint for TTS voice selection, based on the dominant Unicode script in
 * a piece of text. This is deliberately NOT a language-detection model — it can't tell Hindi
 * apart from another Devanagari-script language, and mixed-script Hinglish just resolves to
 * whichever script is more frequent in that particular reply. It exists only for the one place
 * in this app that has no other language signal at all: choosing a system TTS voice for the
 * REST fallback path (the main Gemini Live conversation needs no such hint — the model
 * understands and replies in the user's language natively).
 */
object LanguageHint {
    fun fromText(text: String): String? {
        if (text.isBlank()) return null
        var devanagari = 0
        var odia = 0
        var japanese = 0
        var latin = 0
        for (ch in text) {
            val c = ch.code
            when {
                c in 0x0900..0x097F -> devanagari++
                c in 0x0B00..0x0B7F -> odia++
                c in 0x3040..0x30FF || c in 0x4E00..0x9FFF -> japanese++
                ch.isLetter() && c < 0x2000 -> latin++
            }
        }
        val max = maxOf(devanagari, odia, japanese, latin)
        if (max == 0) return null
        return when (max) {
            devanagari -> "hi"
            odia -> "or"
            japanese -> "ja"
            else -> "en"
        }
    }
}
