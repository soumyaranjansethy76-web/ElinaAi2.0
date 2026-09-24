package com.elina.assistant.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ConversationEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :id")
    suspend fun messageCount(id: Long): Int

    @Query("UPDATE conversations SET updatedAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)
}
