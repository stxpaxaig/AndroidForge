package com.ghosttap.core.vnic

import com.ghosttap.core.CapabilityRegistry
import com.ghosttap.core.DynAssertion
import com.ghosttap.core.DynStep
import com.ghosttap.core.DynamicCapabilityDef
import com.ghosttap.core.DynamicCapabilityRuntime
import com.ghosttap.core.IntelEngine
import com.ghosttap.core.TestReport

/**
 * Capability intelligence layer — the VNIC's view of what Ghost Tap can do.
 *
 * Discovery is RUNTIME, not a static list: it reads [CapabilityRegistry.all()]
 * (the single source of truth the planner, the investigations UI and
 * knowledge search all read from). A capability that is not registered is
 * invisible to the VNIC, exactly as it is invisible to the rest of the app.
 *
 * Procedure development reuses the existing [DynamicCapabilityRuntime]:
 * a procedure is a tested composition of registered capabilities. Only
 * READ steps execute during the test (the runtime enforces that); a
 * procedure is marked verified only when every step actually ran and
 * passed. Non-READ steps are never claimed verified by a test run.
 */
class VnicCapabilityBridge(private val engine: IntelEngine) {

    /** Everything Ghost Tap can do right now, as the VNIC sees it. */
    fun mapCapabilities(): List<VnicCapabilityInfo> =
        CapabilityRegistry.all().map {
            VnicCapabilityInfo(
                id = it.id,
                title = it.title,
                description = it.detail,
                riskLevel = it.riskLevel,
                verified = it.verified,
                inputs = it.inputs,
                outputs = it.outputs,
                preconditions = it.preconditions,
                effects = it.effects
            )
        }

    /** Capability ids whose title/detail words overlap the goal text. */
    fun matchFor(goal: String): List<VnicCapabilityInfo> {
        val words = goal.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return emptyList()
        return mapCapabilities().map { cap ->
            val hay = (cap.id + " " + cap.title + " " + cap.description).lowercase()
            cap to words.count { w -> hay.contains(w) }
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** Which of these plan ids are NOT registered (missing-capability detection). */
    fun missingFor(planIds: List<String>): List<String> = engine.validate(planIds)

    data class DevelopedProcedure(
        val def: DynamicCapabilityDef,
        val testPassed: Boolean,
        val notes: List<String>,
        /**
         * Package 7 (E-23) — the real test report, so the engine can
         * register the procedure as a runnable capability without
         * re-running (or fabricating) a test. Null when validation
         * failed before any test ran.
         */
        val report: TestReport? = null
    )

    /**
     * Develop a procedure for [goal] from [stepIds] (already-selected
     * capability ids, in order). Validates the composition, tests it through
     * the real engine (READ steps only), and returns the result. The caller
     * decides whether to persist it; nothing is registered as a capability
     * here — registration of a dynamic capability is a separate, explicit
     * step through [DynamicCapabilityRuntime.register].
     */
    suspend fun developProcedure(goal: String, stepIds: List<String>, args: Map<String, String> = emptyMap()): DevelopedProcedure {
        val id = "vnic." + goal.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }.take(4).joinToString(".")
        val def = DynamicCapabilityDef(
            id = id,
            title = "VNIC procedure: $goal",
            detail = "Composed by the VNIC from registered capabilities for: $goal",
            riskLevel = "READ",
            inputs = args.keys.toList(),
            steps = stepIds.map { DynStep(it, args) },
            assertions = listOf(DynAssertion("ok", "")),
            origin = "vnic"
        )
        // Package 7 (E-23 / E-37) — registration made re-development
        // fail validation ("id already registered"), which would have
        // turned every second cycle on the same goal into a failed test.
        // A procedure that is already registered as a runnable control
        // is reused as-is: it WAS tested when first registered, and the
        // note says so plainly instead of claiming a fresh test ran.
        if (CapabilityRegistry.get(id) != null) {
            return DevelopedProcedure(
                def, true,
                listOf("Already registered as a runnable control — the tested version is reused, not re-tested.")
            )
        }
        val errors = DynamicCapabilityRuntime.validate(def)
        if (errors.isNotEmpty()) {
            return DevelopedProcedure(def, false, errors)
        }
        val report = DynamicCapabilityRuntime.test(def, engine)
        val notes = report.notes + if (report.passed) {
            listOf("Test passed: every executed step returned ok.")
        } else {
            listOf("Test did not pass: ${report.stepResults.count { !it.ok }} step(s) failed or were skipped.")
        }
        return DevelopedProcedure(def, report.passed, notes, report)
    }
}
