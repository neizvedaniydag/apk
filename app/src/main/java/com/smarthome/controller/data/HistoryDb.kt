package com.smarthome.controller.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history_events")
data class HistoryEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val type: String, // "ALARM", "INFO", "SYSTEM"
    val title: String,
    val message: String,
    val imagePath: String? = null // Путь к файлу на телефоне, если есть фото
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<HistoryEvent>>

    @Insert
    suspend fun insert(event: HistoryEvent)

    @Query("DELETE FROM history_events")
    suspend fun clearAll()
}

@Database(entities = [HistoryEvent::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smarthome_history.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

// Простой репозиторий для доступа из любого места
object HistoryRepository {
    private lateinit var db: AppDatabase
    
    fun init(context: Context) {
        db = AppDatabase.getDatabase(context)
    }
    
    suspend fun addEvent(event: HistoryEvent) {
        if (::db.isInitialized) db.historyDao().insert(event)
    }
    
    suspend fun clearHistory() {
        if (::db.isInitialized) db.historyDao().clearAll()
    }
    
    fun getEvents(): Flow<List<HistoryEvent>> {
        return if (::db.isInitialized) db.historyDao().getAllEvents() else kotlinx.coroutines.flow.flowOf(emptyList())
    }
}
