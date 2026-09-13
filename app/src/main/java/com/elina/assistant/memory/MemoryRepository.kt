package com.elina.assistant.memory

import kotlinx.coroutines.flow.Flow

class MemoryRepository(private val dao:MemoryDao){
    fun observe():Flow<List<MemoryEntity>> = dao.observeAll()
    suspend fun search(query:String)=dao.search(query)
    suspend fun add(category:String,content:String)=dao.insert(MemoryEntity(category=category,content=content))
    suspend fun delete(m:MemoryEntity)=dao.delete(m)
    suspend fun clearAll()=dao.clearAll()
}
