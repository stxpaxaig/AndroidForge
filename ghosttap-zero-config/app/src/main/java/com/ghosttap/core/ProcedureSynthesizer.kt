package com.ghosttap.core

import com.ghosttap.data.IntelDatabase
import com.ghosttap.data.Procedure
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * How the system grows new capabilities honestly.
 *
 * The phone cannot compile new Kotlin, so "developing a capability" here
 * means something verifiable instead: when an auto-investigation finishes
 * with a trail of steps that ALL actually worked, that exact step sequence
 * is saved as a Procedure — a new COMPOSITE capability built from verified
 * primitives. It is labeled "(auto-learned)", it is re-runnable from the
 * procedures list, and every step in it re-validates against the registry
 * when loaded (see IntelViewModel.loadProcedure).
 *
 * Nothing is saved from a failed or partial run — except a stopped run
 * whose steps DID work: those proven steps are kept under a name that says
 * the run stopped short (Package 6 / C-18, F-45), so a dead end still
 * leaves a plan that starts further along next time. Generating steps
 * without running them is not learning.
 */
object ProcedureSynthesizer {

    /**
     * Saves the verified step trail as a re-runnable procedure.
     * Returns the saved procedure, or null when there is nothing worth
     * keeping (not complete or stopped short, fewer than 2 working steps,
     * or a DB error).
     */
    suspend fun maybeSaveProcedure(state: LoopState, db: IntelDatabase): Procedure? {
        if (state.status != "COMPLETE" && state.status != "DEAD_END") return null
        // Only steps that ran AND returned ok are in executedSteps.
        val steps = state.executedSteps
        if (steps.size < 2) return null

        return try {
            // Field names must match StoredStep (id/args/why/auto) so the
            // existing procedure loader can read this back.
            val arr = JSONArray()
            for (s in steps) {
                arr.put(
                    JSONObject().apply {
                        put("id", s.capabilityId)
                        put("args", JSONObject(s.args))
                        put("why", "Auto-learned: ${s.why}".take(200))
                        put("auto", s.riskLevel == "READ")
                    }
                )
            }
            val stopped = state.status == "DEAD_END"
            val proc = Procedure(
                id = UUID.randomUUID().toString(),
                name = ("${state.target.title} (auto-learned" +
                    if (stopped) ", stopped short)" else ")").take(80),
                stepsJson = arr.toString(),
                createdMs = System.currentTimeMillis()
            )
            db.procedureDao().insert(proc)
            proc
        } catch (_: Exception) {
            null
        }
    }
}
