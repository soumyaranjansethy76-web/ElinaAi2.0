package com.elina.assistant.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC") fun observeAll(): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories WHERE content LIKE '%' || :query || '%' ORDER BY updatedAt DESC LIMIT 20") suspend fun search(query:String): List<MemoryEntity>
    @Insert suspend fun insert(memory:MemoryEntity):Long
    @Delete suspend fun delete(memory:MemoryEntity)
    @Query("DELETE FROM memories") suspend fun clearAll()
}
