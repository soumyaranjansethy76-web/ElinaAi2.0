package com.elina.assistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt ASC")
    fun observeByConversation(id: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt ASC")
    suspend fun listByConversation(id: Long): List<MessageEntity>

    @Query("""
        SELECT content FROM messages
        WHERE conversationId = :id AND role = 'USER'
        ORDER BY createdAt ASC LIMIT 1
    """)
    suspend fun firstUserMessage(id: Long): String?

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)
}
