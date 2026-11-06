package com.ghosttap.core.ops

import com.ghosttap.core.CapabilityRegistry
import com.ghosttap.core.IntelCapability
import com.ghosttap.core.OpResult

/**
 * The single list of operations the user can pick. The entry screen,
 * the planner, the loop and VNIC all read from here — an operation
 * that is not registered is invisible to all of them.
 *
 * Registration is idempotent: registering the same id twice keeps the
 * latest definition. That is what lets area packages (1–7) add their
 * operations later without touching this file.
 */
object OperationRegistry {

    private val ops = LinkedHashMap<String, Operation>()

    /** Register one operation (overwrites any earlier definition with the same id). */
    fun register(op: Operation) {
        ops[op.id] = op
    }

    /** Look one operation up by id. Null when nothing is registered under that id. */
    fun get(id: String): Operation? = ops[id]

    /** Every registered operation, in registration order. */
    fun all(): List<Operation> = ops.values.toList()

    /** Every registered operation in one area, in registration order. */
    fun byArea(area: OperationArea): List<Operation> =
        ops.values.filter { it.area == area }

    /** The areas that have at least one operation, in first-seen order. */
    fun areas(): List<OperationArea> = ops.values.map { it.area }.distinct()

    /** Test-only: empties the registry so tests start from a known state. Never called by the app. */
    internal fun clear() {
        ops.clear()
    }

    /**
     * Publishes every registered operation to the [CapabilityRegistry] so
     * the planner, the loop and VNIC can see and run them through the
     * same [com.ghosttap.core.IntelEngine.run] path as every other
     * capability. Idempotent — safe to call again when new operations
     * are registered later.
     */
    fun publishToCapabilityRegistry(context: OperationContext) {
        ops.values.forEach { op ->
            CapabilityRegistry.register(op.asCapability(context))
        }
    }
}

/**
 * The bridge to the existing [CapabilityRegistry]: one operation becomes
 * one [IntelCapability] with the same id, title and plain-words detail,
 * so the planner can emit the operation's id and the loop can run it.
 * The capability's run executes the operation through a fresh
 * [OperationRunner] bound to [context] — preparation, execution and
 * verification all still happen, and the [OperationResult] is mapped to
 * an [OpResult] (DONE → ok=true, anything else → ok=false with the
 * plain-words reason). Nothing is re-implemented here.
 */
fun Operation.asCapability(context: OperationContext): IntelCapability =
    IntelCapability(
        id = id,
        title = title,
        detail = description,
        category = area.capabilityCategory,
        inputs = emptyList(),
        outputs = listOf("outcome: what actually happened, in plain words"),
        preconditions = prerequisites,
        readOnly = riskLevel == "READ",
        riskLevel = riskLevel,
        effects = description,
        verified = verified,
        sourceMethod = if (route != null) "Manual: $route screen (operation $id)" else "Operation: $id",
        run = { _ ->
            try {
                when (val result = OperationRunner(context).run(this)) {
                    is OperationResult.Done ->
                        OpResult(true, result.reason, result.evidence)
                    is OperationResult.Failed ->
                        OpResult(false, result.reason, result.evidence)
                    is OperationResult.NeedsUserAction ->
                        OpResult(false, result.prompt, result.evidence)
                    is OperationResult.NeedsUserChoice ->
                        OpResult(false, result.reason, result.evidence)
                }
            } catch (e: Exception) {
                OpResult(false, "Operation '$id' failed: ${e.message ?: "unknown problem"}")
            }
        }
    )
