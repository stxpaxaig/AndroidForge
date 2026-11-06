package com.ghosttap.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Package 7 (E-29, E-26, E-10, E-06) — small VNIC session state that must
 * survive an app restart, kept in SharedPreferences like [BaselineStore]
 * so the Room database `vnic.db` (version 1, no migration) is never
 * touched: no schema change, no migration, no risk to learned memory.
 *
 * What lives here:
 * - the last drive outcome (E-29): a "ready for you" indicator that stays
 *   until the user opens the controls or dismisses it;
 * - the drive-policy pins + cooldown stamps (E-26): a target already
 *   CONTROLLABLE is never re-driven, a just-driven target waits out its
 *   60s cooldown — even after a restart;
 * - the telemetry counters (E-10): rounds done, lookups, tries, connected,
 *   checked-OK, failed — measured counts no longer zero on restart;
 * - the working-memory scratch keys (E-06): last subject / action / state.
 *
 * What deliberately does NOT live here: the mesh weights (E-04). The mesh
 * is rebuilt from its fixed seed on every engine creation by design —
 * 4,096 units x 12 edges — and the panel says so in plain words instead
 * of pretending the practice patterns survive.
 *
 * Corrupt JSON reads as empty (same rule as BaselineStore): a bad value
 * never crashes the brain, it just looks like a fresh start.
 */
@Serializable
data class VnicDriveOutcome(
    /** What the brain looked at (device name, or the real target id). */
    val subject: String,
    /** VnicExecutionState name: CONTROLLABLE, FAILED, and so on. */
    val state: String,
    /** Nav route of the control screen ("" when not ready). */
    val route: String = "",
    /** Plain-words lines describing what can be controlled. */
    val controls: List<String> = emptyList(),
    /** Plain-words evidence / blocking reason. */
    val evidence: String = "",
    val ts: Long = System.currentTimeMillis()
)

@Serializable
data class VnicDrivePolicyState(
    val controllableKeys: List<String> = emptyList(),
    val lastDrivenAtMs: Map<String, Long> = emptyMap()
)

class VnicSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("vnic_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun saveDriveOutcome(outcome: VnicDriveOutcome?) {
        prefs.edit().apply {
            if (outcome == null) remove("drive_outcome")
            else putString("drive_outcome", json.encodeToString(outcome))
        }.apply()
    }

    fun loadDriveOutcome(): VnicDriveOutcome? {
        val raw = prefs.getString("drive_outcome", null) ?: return null
        return runCatching { json.decodeFromString<VnicDriveOutcome>(raw) }.getOrNull()
    }

    fun saveDrivePolicy(state: VnicDrivePolicyState) {
        prefs.edit().putString("drive_policy", json.encodeToString(state)).apply()
    }

    fun loadDrivePolicy(): VnicDrivePolicyState? {
        val raw = prefs.getString("drive_policy", null) ?: return null
        return runCatching { json.decodeFromString<VnicDrivePolicyState>(raw) }.getOrNull()
    }

    fun saveTelemetry(values: Map<String, Long>) {
        prefs.edit().putString("telemetry", json.encodeToString(values)).apply()
    }

    fun loadTelemetry(): Map<String, Long> {
        val raw = prefs.getString("telemetry", null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Long>>(raw) }.getOrElse { emptyMap() }
    }

    fun saveWorking(values: Map<String, String>) {
        prefs.edit().putString("working", json.encodeToString(values)).apply()
    }

    fun loadWorking(): Map<String, String> {
        val raw = prefs.getString("working", null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrElse { emptyMap() }
    }
}
