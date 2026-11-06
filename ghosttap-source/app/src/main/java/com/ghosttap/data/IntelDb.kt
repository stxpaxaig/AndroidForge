package com.ghosttap.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Persistent memory for the intelligence system, stored in "intel.db"
 * (separate from the log database — investigations are user data, not logs).
 *
 * - [Investigation]: one target being worked, with a status.
 * - [Observation]: one timestamped fact on an investigation's timeline —
 *   the target itself, the plan, a command result, or a user note.
 * - [Procedure]: a saved, reusable plan (list of capability ids) that can be
 *   loaded onto a new investigation.
 */
@Entity(tableName = "investigations")
data class Investigation(
    @PrimaryKey val id: String,
    val title: String,
    val targetKind: String,
    val targetText: String,
    val createdMs: Long,
    val status: String, // "open" | "closed"
    val markings: String = "", // comma-joined candidate markings
    val barcodes: String = "" // ";"-joined barcode raw values
)

@Entity(
    tableName = "observations",
    indices = [Index("investigationId")]
)
data class Observation(
    @PrimaryKey val id: String,
    val investigationId: String,
    val ts: Long,
    val kind: String, // "target" | "plan" | "result" | "note" | "memory"
    val title: String,
    val detail: String,
    /**
     * Where this record came from: "OBSERVED" (a real execution result, a
     * target the user provided, a plan that was actually produced) or
     * "ASSUMED" (a hypothesis, an inference, a candidate marking). Assumptions
     * must never be promoted into verified facts.
     */
    val provenance: String = "OBSERVED",
    /** 0.0..1.0 — how much weight planning should give this record. */
    val confidence: Double = 1.0
)

@Entity(tableName = "procedures")
data class Procedure(
    @PrimaryKey val id: String,
    val name: String,
    val stepsJson: String,
    val createdMs: Long
)

@Dao
interface InvestigationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(i: Investigation)

    @Query("SELECT * FROM investigations ORDER BY createdMs DESC")
    suspend fun listAll(): List<Investigation>

    @Query("UPDATE investigations SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("DELETE FROM investigations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM investigations WHERE title LIKE '%'||:q||'%' OR targetText LIKE '%'||:q||'%' ORDER BY createdMs DESC LIMIT 50")
    suspend fun search(q: String): List<Investigation>
}

@Dao
interface ObservationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(o: Observation)

    @Query("SELECT * FROM observations WHERE investigationId = :iid ORDER BY ts ASC")
    suspend fun forInvestigation(iid: String): List<Observation>

    @Query("SELECT * FROM observations ORDER BY ts DESC LIMIT :n")
    suspend fun latest(n: Int): List<Observation>

    @Query("SELECT * FROM observations WHERE title LIKE '%'||:q||'%' OR detail LIKE '%'||:q||'%' ORDER BY ts DESC LIMIT 50")
    suspend fun search(q: String): List<Observation>

    /** All records of one kind — used at startup to restore saved extensions. */
    @Query("SELECT * FROM observations WHERE kind = :kind ORDER BY ts DESC")
    suspend fun byKind(kind: String): List<Observation>

    @Query("DELETE FROM observations WHERE investigationId = :iid")
    suspend fun deleteFor(iid: String)
}

@Dao
interface ProcedureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: Procedure)

    @Query("SELECT * FROM procedures ORDER BY createdMs DESC")
    suspend fun listAll(): List<Procedure>

    @Query("DELETE FROM procedures WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM procedures WHERE name LIKE '%'||:q||'%' ORDER BY createdMs DESC LIMIT 50")
    suspend fun search(q: String): List<Procedure>
}

@Database(
    entities = [Investigation::class, Observation::class, Procedure::class],
    version = 2,
    exportSchema = false
)
abstract class IntelDatabase : RoomDatabase() {
    abstract fun investigationDao(): InvestigationDao
    abstract fun observationDao(): ObservationDao
    abstract fun procedureDao(): ProcedureDao
}

object IntelDb {
    @Volatile
    private var instance: IntelDatabase? = null

    fun get(context: Context): IntelDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                IntelDatabase::class.java,
                "intel.db"
            )
                // intel.db shipped hours ago in v1 with no user data worth
                // migrating; a clean v2 schema beats a fragile migration.
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
