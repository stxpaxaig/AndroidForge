package com.ghosttap.core.vnic

import com.ghosttap.core.IntelEngine

/**
 * Execution bridge — the VNIC invokes Ghost Tap capabilities through their
 * REAL interfaces ([IntelEngine.run]) and receives REAL results.
 *
 * Verification itself lives in [VnicVerify] (pure Kotlin, shared with the
 * JVM demo); this class is the thin Android-side wrapper: run, then verify.
 * Production behaviour is identical to before the split.
 */
class VnicExecution(private val engine: IntelEngine) : VnicExecutor {

    companion object {
        /** Capabilities audited as returning ok=true unconditionally — their ok alone proves nothing. */
        val UNCONDITIONAL_OK: Set<String> get() = VnicVerify.UNCONDITIONAL_OK
        /** Phrases that mean "nothing actually happened" even when ok=true. */
        val NOOP_MARKERS: List<String> get() = VnicVerify.NOOP_MARKERS
    }

    override suspend fun execute(capabilityId: String, args: Map<String, String>): VnicExecutionOutcome {
        val result = try {
            engine.run(capabilityId, args)
        } catch (e: Exception) {
            return VnicExecutionOutcome(
                VnicExecutionState.FAILED,
                "Capability '$capabilityId' crashed: ${e.message ?: "unknown error"}"
            )
        }
        return VnicVerify.verify(capabilityId, result)
    }
}
