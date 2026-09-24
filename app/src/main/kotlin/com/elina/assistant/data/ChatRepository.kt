package com.elina.assistant.data

import kotlinx.coroutines.flow.Flow

class ChatRepository(
    private val db: AppDatabase,
) {
    private val convDao = db.conversations()
    private val msgDao = db.messages()

    fun observeConversations(): Flow<List<ConversationEntity>> = convDao.observeAll()

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        msgDao.observeByConversation(conversationId)

    suspend fun createConversation(title: String = "新对话"): Long {
        val now = System.currentTimeMillis()
        return convDao.insert(ConversationEntity(title = title, createdAt = now, updatedAt = now))
    }

    suspend fun appendMessage(
        conversationId: Long,
        role: Role,
        content: String,
    ): Long {
        val now = System.currentTimeMillis()
        val id = msgDao.insert(
            MessageEntity(
                conversationId = conversationId,
                role = role,
                content = content,
                createdAt = now,
            )
        )
        convDao.touch(conversationId, now)
        return id
    }

    suspend fun listMessages(conversationId: Long): List<MessageEntity> =
        msgDao.listByConversation(conversationId)

    suspend fun updateMessageContent(messageId: Long, content: String) {
        msgDao.updateContent(messageId, content)
    }

    suspend fun renameToFirstUserMessage(conversationId: Long) {
        val firstUser = msgDao.firstUserMessage(conversationId) ?: return
        val conv = convDao.findById(conversationId) ?: return
        val title = firstUser.take(24)
        if (conv.title != title) {
            convDao.update(conv.copy(title = title))
        }
    }

    suspend fun deleteConversation(conversation: ConversationEntity) {
        convDao.delete(conversation)
    }

    suspend fun messageCount(conversationId: Long): Int =
        convDao.messageCount(conversationId)
}
