package com.elina.assistant.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small durable explicit-memory store. Facts are only saved when Elina explicitly decides to remember them. */
class MemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("elina_memory", Context.MODE_PRIVATE)

    private fun readItems(): JSONArray =
        try { JSONArray(prefs.getString("items", "[]")) } catch (e: Exception) { JSONArray() }

    /** Writes with commit() (not apply()) so a failure is knowable instead of silently assumed. */
    private fun writeItems(arr: JSONArray): Boolean =
        try { prefs.edit().putString("items", arr.toString()).commit() } catch (e: Exception) { false }

    @Synchronized
    fun remember(fact: String, category: String = "general"): String {
        val clean = fact.trim().take(500)
        if (clean.isBlank()) return "Nothing to remember."
        return try {
            val arr = readItems()
            val now = System.currentTimeMillis()
            var replaced = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("fact").equals(clean, ignoreCase = true)) {
                    o.put("category", category.ifBlank { "general" })
                    o.put("updatedAt", now)
                    replaced = true
                    break
                }
            }
            if (!replaced) {
                arr.put(JSONObject().apply {
                    put("fact", clean)
                    put("category", category.ifBlank { "general" })
                    put("updatedAt", now)
                })
            }
            if (!writeItems(arr)) return "I couldn't save that — memory storage failed. Let's continue anyway."
            if (replaced) "Memory updated." else "Memory saved."
        } catch (e: Exception) {
            "I couldn't save that — memory storage failed. Let's continue anyway."
        }
    }

    @Synchronized
    fun forget(query: String): String {
        val q = query.trim()
        if (q.isBlank()) return "Tell me what to forget."
        return try {
            val old = readItems()
            val out = JSONArray()
            var removed = 0
            for (i in 0 until old.length()) {
                val o = old.optJSONObject(i) ?: continue
                val fact = o.optString("fact")
                if (fact.contains(q, ignoreCase = true)) removed++ else out.put(o)
            }
            if (removed == 0) return "I couldn't find a matching memory."
            if (!writeItems(out)) return "I found that memory but couldn't remove it — storage failed. Let's continue anyway."
            "Memory removed."
        } catch (e: Exception) {
            "I couldn't forget that — memory storage failed. Let's continue anyway."
        }
    }

    @Synchronized
    fun clear() {
        try { prefs.edit().clear().commit() } catch (e: Exception) { /* best-effort */ }
    }

    fun summary(maxItems: Int = 20): String {
        val arr = readItems()
        if (arr.length() == 0) return "(no explicit memories yet)"
        val start = maxOf(0, arr.length() - maxItems)
        return buildString {
            for (i in start until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                append("- [")
                append(o.optString("category", "general"))
                append("] ")
                append(o.optString("fact"))
                append('\n')
            }
        }.trim()
    }

    /**
     * Returns only the memories whose fact/category text overlaps with words in [query], most
     * relevant first — or "" if nothing matches. Deliberately simple word-overlap scoring, no
     * embeddings/vector search: keeps this dependency-free and fast enough to run on every
     * text turn, at the cost of not catching paraphrases. This is what lets a per-turn caller
     * include only relevant memories instead of the whole store.
     */
    @Synchronized
    fun relevant(query: String, maxItems: Int = 3): String {
        val q = query.trim()
        if (q.isBlank()) return ""
        val stop = setOf("the", "and", "for", "are", "was", "were", "you", "your", "what", "whats", "have", "has", "with", "that", "this", "did", "does")
        val queryWords = q.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in stop }.toSet()
        if (queryWords.isEmpty()) return ""
        val arr = readItems()
        val scored = mutableListOf<Pair<Int, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val fact = o.optString("fact")
            val category = o.optString("category", "general")
            val factWords = (fact + " " + category).lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
            val score = queryWords.intersect(factWords).size
            if (score > 0) scored.add(score to "- [$category] $fact")
        }
        if (scored.isEmpty()) return ""
        return scored.sortedByDescending { it.first }.take(maxItems).joinToString("\n") { it.second }
    }
}
