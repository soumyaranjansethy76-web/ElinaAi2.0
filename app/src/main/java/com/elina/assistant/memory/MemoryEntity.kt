package com.elina.assistant.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class MemoryEntity(@PrimaryKey(autoGenerate = true) val id: Long=0, val category:String, val content:String, val createdAt:Long=System.currentTimeMillis(), val updatedAt:Long=System.currentTimeMillis())
