package com.ghosttap.core.vnic

/**
 * VNIC persistent memory — Kotlin port of the VNIC Python `MemoryStore`.
 *
 * Four memory classes, exactly as in the Python reference:
 * - episodic: what happened, when (observations, cycles, executions)
 * - semantic: what is known (facts, research findings, device knowledge)
 * - procedural: how to do things (verified procedures, with outcomes)
 * - working: the current cycle's scratch state (promoted or dropped)
 *
 * Plus a concept graph: named concepts with definitions and confidence,
 * linked by typed relations with a strength.
 *
 * This file defines the data classes and the [VnicMemoryStore] interface.
 * The interface is suspend-based so the Room implementation can call DAOs
 * directly. Two implementations exist:
 * - [InMemoryVnicMemory]: pure-Kotlin, for unit tests and as a fallback if
 *   the on-device database cannot be opened (the core must never crash the
 *   app because its memory store failed).
 * - RoomVnicMemory (VnicMemoryRoom.kt): the on-device persistent store in
 *   `vnic.db` — Room, version 1, explicit migrations only. Unlike the
 *   existing intel.db, vnic.db NEVER uses fallbackToDestructiveMigration:
 *   a schema bump that wipes stored investigations would destroy the
 *   learned memory this core exists to keep.
 *
 * Recall ranking matches the Python reference exactly: memories whose
 * content contains one of the query's terms ([a-z0-9_]{2,}, at most 12
 * distinct terms), ordered by strength DESC then created DESC. Each
 * recalled memory's access count is incremented, as in Python.
 * Concept names are lowercased on write and lookup, as in Python.
 * A re-related pair bumps its weight by 0.05, capped at 1.0.
 */
enum class VnicMemoryClass { EPISODIC, SEMANTIC, PROCEDURAL, WORKING }

data class VnicMemoryRecord(
    val id: Long = 0,
    val type: VnicMemoryClass,
    val content: String,
    val context: String = "",
    val confidence: Double = 0.5,
    val ts: Long = System.currentTimeMillis()
)

data class VnicConcept(
    val id: Long = 0,
    val name: String,
    val definition: String,
    val confidence: Double = 0.5,
    val ts: Long = System.currentTimeMillis()
)

data class VnicRelation(
    val id: Long = 0,
    val fromConcept: String,
    val toConcept: String,
    val relation: String,
    val strength: Double = 0.5
)

data class VnicProcedure(
    val name: String,
    val stepsJson: String,
    val successes: Int = 0,
    val failures: Int = 0,
    val verified: Boolean = false,
    val updatedTs: Long = System.currentTimeMillis()
) {
    val successRate: Double
        get() {
            val total = successes + failures
            return if (total == 0) 0.0 else successes.toDouble() / total
        }
}

interface VnicMemoryStore {
    suspend fun remember(record: VnicMemoryRecord): Long
    suspend fun recall(query: String, limit: Int = 5, type: VnicMemoryClass? = null): List<VnicMemoryRecord>
    suspend fun addConcept(concept: VnicConcept): Long
    suspend fun getConcept(name: String): VnicConcept?
    suspend fun searchConcepts(query: String, limit: Int = 5): List<VnicConcept>
    suspend fun relate(relation: VnicRelation): Long
    suspend fun relationsOf(concept: String): List<VnicRelation>
    /** Concept-graph neighbourhood: relations where [name] is source or target, weight DESC. */
    suspend fun graph(name: String, limit: Int = 30): List<VnicRelation>
    suspend fun saveProcedure(procedure: VnicProcedure)
    suspend fun getProcedure(name: String): VnicProcedure?
    suspend fun listProcedures(): List<VnicProcedure>
    suspend fun recordProcedureOutcome(name: String, success: Boolean)
    suspend fun setWorking(key: String, value: String)
    suspend fun getWorking(key: String): String?
    suspend fun clearWorking()
    suspend fun counts(): Map<VnicMemoryClass, Int>
    /** Number of times a memory has been recalled (Python `accesses`). */
    suspend fun accesses(id: Long): Int
    /**
     * Most recent memories, newest first (Package 7 / E-06, E-28: the
     * viewer the user reads them with). Default is empty so older
     * implementations keep compiling; both shipped stores override it.
     */
    suspend fun recent(limit: Int): List<VnicMemoryRecord> = emptyList()
}

/** Pure-Kotlin in-memory store. Behaviour-identical to the Room store for tests. */
class InMemoryVnicMemory : VnicMemoryStore {
    private val memories = mutableListOf<VnicMemoryRecord>()
    private val accessCounts = mutableMapOf<Long, Int>()
    private val concepts = mutableMapOf<String, VnicConcept>()
    private val relations = mutableListOf<VnicRelation>()
    private val procedures = mutableMapOf<String, VnicProcedure>()
    private val working = mutableMapOf<String, String>()
    private var nextId = 1L

    override suspend fun remember(record: VnicMemoryRecord): Long {
        val id = nextId++
        memories += record.copy(id = id)
        accessCounts[id] = 0
        return id
    }

    override suspend fun recall(query: String, limit: Int, type: VnicMemoryClass?): List<VnicMemoryRecord> {
        // Python: terms = unique [a-z0-9_]{2,} tokens, at most 12; match =
        // content contains a term; order = strength DESC, created DESC.
        val terms = Regex("[a-z0-9_]{2,}").findAll(query.lowercase()).map { it.value }.distinct().take(12).toList()
        if (terms.isEmpty()) return emptyList()
        val hits = memories
            .filter { type == null || it.type == type }
            .filter { rec -> terms.any { t -> rec.content.lowercase().contains(t) } }
            .sortedWith(compareByDescending<VnicMemoryRecord> { it.confidence }.thenByDescending { it.ts })
            .take(limit)
        hits.forEach { accessCounts[it.id] = (accessCounts[it.id] ?: 0) + 1 }
        return hits
    }

    override suspend fun addConcept(concept: VnicConcept): Long {
        val name = concept.name.lowercase()
        val existing = concepts[name]
        val id = existing?.id ?: nextId++
        concepts[name] = concept.copy(id = id, name = name)
        return id
    }

    override suspend fun getConcept(name: String): VnicConcept? = concepts[name.lowercase()]

    override suspend fun searchConcepts(query: String, limit: Int): List<VnicConcept> {
        val q = query.lowercase()
        return concepts.values
            .filter { it.name.contains(q) || it.definition.lowercase().contains(q) }
            .sortedByDescending { it.confidence }
            .take(limit)
    }

    override suspend fun relate(relation: VnicRelation): Long {
        // Python: upsert, weight = MIN(1, weight + 0.05) on conflict.
        val from = relation.fromConcept.lowercase()
        val to = relation.toConcept.lowercase()
        val existing = relations.firstOrNull { it.fromConcept == from && it.toConcept == to && it.relation == relation.relation }
        if (existing != null) {
            val updated = existing.copy(strength = minOf(1.0, existing.strength + 0.05))
            relations[relations.indexOf(existing)] = updated
            return updated.id
        }
        val id = nextId++
        relations += relation.copy(id = id, fromConcept = from, toConcept = to)
        return id
    }

    override suspend fun relationsOf(concept: String): List<VnicRelation> {
        val c = concept.lowercase()
        return relations.filter { it.fromConcept == c || it.toConcept == c }
    }

    override suspend fun graph(name: String, limit: Int): List<VnicRelation> {
        val c = name.lowercase()
        return relations.filter { it.fromConcept == c || it.toConcept == c }
            .sortedByDescending { it.strength }
            .take(limit)
    }

    override suspend fun accesses(id: Long): Int = accessCounts[id] ?: 0

    override suspend fun recent(limit: Int): List<VnicMemoryRecord> =
        memories.sortedByDescending { it.ts }.take(limit)

    override suspend fun saveProcedure(procedure: VnicProcedure) {
        procedures[procedure.name] = procedure
    }

    override suspend fun getProcedure(name: String): VnicProcedure? = procedures[name]

    override suspend fun listProcedures(): List<VnicProcedure> =
        procedures.values.sortedByDescending { it.updatedTs }

    override suspend fun recordProcedureOutcome(name: String, success: Boolean) {
        val p = procedures[name] ?: return
        procedures[name] = p.copy(
            successes = p.successes + if (success) 1 else 0,
            failures = p.failures + if (success) 0 else 1,
            updatedTs = System.currentTimeMillis()
        )
    }

    override suspend fun setWorking(key: String, value: String) {
        working[key] = value
    }

    override suspend fun getWorking(key: String): String? = working[key]

    override suspend fun clearWorking() {
        working.clear()
    }

    override suspend fun counts(): Map<VnicMemoryClass, Int> =
        VnicMemoryClass.values().associateWith { c -> memories.count { it.type == c } }
}
