package com.elina.assistant.memory

class MemoryManager(private val repo:MemoryRepository, private val enabled:()->Boolean){
    private val explicit = listOf("remember this","isko yaad rakhna","forget this","ye bhool jao","what do you remember about me","show my memories","clear my memory")
    fun isMemoryCommand(text:String)=explicit.any{text.contains(it,ignoreCase=true)}
    suspend fun handle(text:String):String? {
        val t=text.trim()
        return when {
            t.contains("what do you remember about me",true) || t.contains("show my memories",true) -> repo.search("").joinToString("\n"){it.content}.ifBlank{"I don't have any saved memories."}
            t.contains("clear my memory",true) -> { repo.clearAll(); "All saved memories were cleared." }
            t.contains("forget this",true) || t.contains("ye bhool jao",true) -> "Please tell me which saved memory to forget, and I will remove the matching item."
            t.contains("remember this",true) || t.contains("isko yaad rakhna",true) -> {
                if(!enabled()) return "Memory is disabled in Settings."
                val payload=t.replace(Regex("(?i)remember this|isko yaad rakhna"),"").trim(' ',':','-')
                if(payload.isBlank()) "Tell me what you want me to remember." else { repo.add("user-approved",payload); "Okay, I saved that locally." }
            }
            else -> null
        }
    }
}
