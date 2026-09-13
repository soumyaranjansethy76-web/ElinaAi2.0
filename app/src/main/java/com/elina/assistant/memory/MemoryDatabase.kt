package com.elina.assistant.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities=[MemoryEntity::class], version=1, exportSchema=false)
abstract class MemoryDatabase:RoomDatabase(){ abstract fun memoryDao():MemoryDao
    companion object { @Volatile private var INSTANCE:MemoryDatabase?=null; fun get(c:Context)=INSTANCE ?: synchronized(this){INSTANCE ?: Room.databaseBuilder(c.applicationContext,MemoryDatabase::class.java,"elina_memory.db").build().also{INSTANCE=it}}}
}
