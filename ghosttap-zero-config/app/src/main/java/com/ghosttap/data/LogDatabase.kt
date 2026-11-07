package com.ghosttap.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "log_entries")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val targetId: String?,
    val category: String,
    val message: String
)

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: LogEntry)

    @Query("SELECT * FROM log_entries ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<LogEntry>>

    @Query("DELETE FROM log_entries")
    suspend fun clearAll()
}

@Database(entities = [LogEntry::class], version = 1, exportSchema = false)
abstract class GhostTapDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    companion object {
        @Volatile
        private var instance: GhostTapDatabase? = null

        fun get(context: Context): GhostTapDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GhostTapDatabase::class.java,
                    "ghost_tap.db"
                ).build().also { instance = it }
            }
        }
    }
}
