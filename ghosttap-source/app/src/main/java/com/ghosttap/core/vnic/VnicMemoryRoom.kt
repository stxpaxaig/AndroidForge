package com.ghosttap.core.vnic

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

/**
 * On-device persistent store for the VNIC, `vnic.db` (Room, version 1).
 *
 * HARD RULE: this database NEVER uses fallbackToDestructiveMigration.
 * The existing intel.db wipes its contents on any schema bump; that is
 * acceptable there only because it shipped with no user data. vnic.db is
 * the VNIC's learned memory — investigations, concepts, procedures. A
 * destructive migration would destroy exactly what the core exists to
 * retain. Any future schema change MUST ship an explicit Migration object.
 */
@Entity(tableName = "vnic_memories")
data class VnicMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val content: String,
    val context: String = "",
    val confidence: Double = 0.5,
    val ts: Long = System.currentTimeMillis(),
    val accesses: Int = 0
)

@Entity(tableName = "vnic_concepts")
data class VnicConceptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val definition: String,
    val confidence: Double = 0.5,
    val ts: Long = System.currentTimeMillis()
)

@Entity(tableName = "vnic_relations")
data class VnicRelationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromConcept: String,
    val toConcept: String,
    val relation: String,
    val strength: Double = 0.5
)

@Entity(tableName = "vnic_procedures", primaryKeys = ["name"])
data class VnicProcedureEntity(
    val name: String,
    val stepsJson: String,
    val successes: Int = 0,
    val failures: Int = 0,
    val verified: Boolean = false,
    val updatedTs: Long = System.currentTimeMillis()
)

@Dao
interface VnicMemoryDao {
    @Insert
    suspend fun insertMemory(e: VnicMemoryEntity): Long

    @Query("SELECT * FROM vnic_memories ORDER BY ts DESC LIMIT :limit")
    suspend fun recentMemories(limit: Int): List<VnicMemoryEntity>

    @Query("SELECT * FROM vnic_memories WHERE type = :type ORDER BY ts DESC LIMIT :limit")
    suspend fun memoriesByType(type: String, limit: Int): List<VnicMemoryEntity>

    @Query("SELECT COUNT(*) FROM vnic_memories WHERE type = :type")
    suspend fun countByType(type: String): Int

    @Query("UPDATE vnic_memories SET accesses = accesses + 1 WHERE id = :id")
    suspend fun bumpAccesses(id: Long)

    @Query("SELECT accesses FROM vnic_memories WHERE id = :id")
    suspend fun accessesOf(id: Long): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConcept(e: VnicConceptEntity): Long

    @Query("SELECT * FROM vnic_concepts WHERE name = :name LIMIT 1")
    suspend fun conceptByName(name: String): VnicConceptEntity?

    @Query("SELECT * FROM vnic_concepts WHERE name LIKE '%'||:q||'%' OR definition LIKE '%'||:q||'%' ORDER BY confidence DESC LIMIT :limit")
    suspend fun searchConcepts(q: String, limit: Int): List<VnicConceptEntity>

    @Insert
    suspend fun insertRelation(e: VnicRelationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelation(e: VnicRelationEntity): Long

    @Query("SELECT * FROM vnic_relations WHERE fromConcept = :concept OR toConcept = :concept")
    suspend fun relationsOf(concept: String): List<VnicRelationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProcedure(e: VnicProcedureEntity)

    @Query("SELECT * FROM vnic_procedures WHERE name = :name LIMIT 1")
    suspend fun procedureByName(name: String): VnicProcedureEntity?

    @Query("SELECT * FROM vnic_procedures ORDER BY updatedTs DESC")
    suspend fun allProcedures(): List<VnicProcedureEntity>
}

@Database(
    entities = [VnicMemoryEntity::class, VnicConceptEntity::class, VnicRelationEntity::class, VnicProcedureEntity::class],
    version = 1,
    exportSchema = false
)
abstract class VnicDatabase : RoomDatabase() {
    abstract fun vnicMemoryDao(): VnicMemoryDao
}

object VnicDb {
    @Volatile
    private var instance: VnicDatabase? = null

    fun get(context: Context): VnicDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                VnicDatabase::class.java,
                "vnic.db"
            ).build().also { instance = it }
        }
    }
}

/** Room-backed [VnicMemoryStore]. Recall ranking matches the in-memory store and the Python reference. */
class RoomVnicMemory(private val dao: VnicMemoryDao) : VnicMemoryStore {

    override suspend fun remember(record: VnicMemoryRecord): Long =
        dao.insertMemory(
            VnicMemoryEntity(
                type = record.type.name, content = record.content,
                context = record.context, confidence = record.confidence, ts = record.ts
            )
        )

    override suspend fun recall(query: String, limit: Int, type: VnicMemoryClass?): List<VnicMemoryRecord> {
        // Same semantics as the Python reference and the in-memory store:
        // terms = unique [a-z0-9_]{2,} tokens (max 12); match = content
        // contains a term; order = strength DESC, created DESC; bump accesses.
        val terms = Regex("[a-z0-9_]{2,}").findAll(query.lowercase()).map { it.value }.distinct().take(12).toList()
        if (terms.isEmpty()) return emptyList()
        val pool = if (type == null) dao.recentMemories(200) else dao.memoriesByType(type.name, 200)
        val hits = pool.map { e ->
            VnicMemoryRecord(e.id, VnicMemoryClass.valueOf(e.type), e.content, e.context, e.confidence, e.ts)
        }.filter { rec -> terms.any { t -> rec.content.lowercase().contains(t) } }
            .sortedWith(compareByDescending<VnicMemoryRecord> { it.confidence }.thenByDescending { it.ts })
            .take(limit)
        hits.forEach { dao.bumpAccesses(it.id) }
        return hits
    }

    override suspend fun addConcept(concept: VnicConcept): Long {
        val name = concept.name.lowercase()
        val existing = dao.conceptByName(name)
        return dao.upsertConcept(
            VnicConceptEntity(
                id = existing?.id ?: 0, name = name, definition = concept.definition,
                confidence = concept.confidence, ts = concept.ts
            )
        )
    }

    override suspend fun getConcept(name: String): VnicConcept? =
        dao.conceptByName(name.lowercase())?.let { VnicConcept(it.id, it.name, it.definition, it.confidence, it.ts) }

    override suspend fun searchConcepts(query: String, limit: Int): List<VnicConcept> =
        dao.searchConcepts(query.lowercase(), limit).map { VnicConcept(it.id, it.name, it.definition, it.confidence, it.ts) }

    override suspend fun relate(relation: VnicRelation): Long {
        val from = relation.fromConcept.lowercase()
        val to = relation.toConcept.lowercase()
        val existing = dao.relationsOf(from).firstOrNull { it.toConcept == to && it.relation == relation.relation }
            ?: dao.relationsOf(to).firstOrNull { it.fromConcept == from && it.relation == relation.relation }
        if (existing != null) {
            dao.upsertRelation(existing.copy(strength = minOf(1.0, existing.strength + 0.05)))
            return existing.id
        }
        return dao.insertRelation(
            VnicRelationEntity(
                fromConcept = from, toConcept = to,
                relation = relation.relation, strength = relation.strength
            )
        )
    }

    override suspend fun relationsOf(concept: String): List<VnicRelation> =
        dao.relationsOf(concept.lowercase()).map { VnicRelation(it.id, it.fromConcept, it.toConcept, it.relation, it.strength) }

    override suspend fun graph(name: String, limit: Int): List<VnicRelation> =
        dao.relationsOf(name.lowercase())
            .sortedByDescending { it.strength }
            .take(limit)
            .map { VnicRelation(it.id, it.fromConcept, it.toConcept, it.relation, it.strength) }

    override suspend fun accesses(id: Long): Int = dao.accessesOf(id) ?: 0

    override suspend fun recent(limit: Int): List<VnicMemoryRecord> =
        dao.recentMemories(limit).map {
            VnicMemoryRecord(it.id, VnicMemoryClass.valueOf(it.type), it.content, it.context, it.confidence, it.ts)
        }

    override suspend fun saveProcedure(procedure: VnicProcedure) {
        dao.upsertProcedure(
            VnicProcedureEntity(
                name = procedure.name, stepsJson = procedure.stepsJson,
                successes = procedure.successes, failures = procedure.failures,
                verified = procedure.verified, updatedTs = procedure.updatedTs
            )
        )
    }

    override suspend fun getProcedure(name: String): VnicProcedure? =
        dao.procedureByName(name)?.let {
            VnicProcedure(it.name, it.stepsJson, it.successes, it.failures, it.verified, it.updatedTs)
        }

    override suspend fun listProcedures(): List<VnicProcedure> =
        dao.allProcedures().map {
            VnicProcedure(it.name, it.stepsJson, it.successes, it.failures, it.verified, it.updatedTs)
        }

    override suspend fun recordProcedureOutcome(name: String, success: Boolean) {
        val p = dao.procedureByName(name) ?: return
        dao.upsertProcedure(
            p.copy(
                successes = p.successes + if (success) 1 else 0,
                failures = p.failures + if (success) 0 else 1,
                updatedTs = System.currentTimeMillis()
            )
        )
    }

    // Working memory is per-cycle scratch: kept in memory, not in Room.
    private val working = mutableMapOf<String, String>()
    override suspend fun setWorking(key: String, value: String) { working[key] = value }
    override suspend fun getWorking(key: String): String? = working[key]
    override suspend fun clearWorking() { working.clear() }

    override suspend fun counts(): Map<VnicMemoryClass, Int> =
        VnicMemoryClass.values().associateWith { dao.countByType(it.name) }
}
