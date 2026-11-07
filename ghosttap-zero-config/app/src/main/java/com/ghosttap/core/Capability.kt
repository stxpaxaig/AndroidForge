package com.ghosttap.core

/**
 * Machine-readable description of one real Ghost Tap operation.
 *
 * NOTE ON NAMING: this is intentionally called [IntelCapability] and NOT
 * `Capability`, because `com.ghosttap.data.Capability` already exists (it
 * describes a BLE target's discovered abilities). The two types are unrelated;
 * this one describes an *invokable operation* for the intelligence system.
 *
 * @param id stable id, e.g. "net.ping". The planner may only emit ids that
 *   exist in [CapabilityRegistry]; anything else is dropped by validation.
 * @param title short human label, e.g. "Ping a host".
 * @param detail plain-English explanation of what the operation does.
 * @param category grouping, e.g. "VISUAL", "BLE", "NETWORK", "USB", "NFC", "SYSTEM".
 * @param inputs names of string arguments the run lambda reads from the args map.
 * @param outputs what the operation produces, e.g. ["finding"], ["reply times"].
 * @param preconditions human-readable requirements, e.g. "NFC tag must be physically tapped".
 * @param readOnly true when the operation only observes and never changes state.
 *   Derived from riskLevel: true only when riskLevel == "READ".
 * @param riskLevel one of "READ" (observes only), "CONFIGURE" (opens/closes a
 *   link), "WRITE" (sends data out), "CONTROL" (drives hardware behavior).
 *   Full autonomous mode: every level auto-runs with no confirmation gates.
 *   The level is recorded on every step and every auto-run action is logged
 *   to the investigations timeline.
 * @param effects plain-English description of what the operation ACTUALLY does
 *   to the target, the phone, or the radio — shown next to APPROVE & RUN so the
 *   user knows what they are authorizing. Never vague, never marketing.
 * @param verified true only when the underlying tool has been exercised on
 *   real hardware at least once. Green build alone does NOT count. Unverified
 *   capabilities are labeled as such in the UI.
 * @param sourceMethod the real method this wraps, e.g.
 *   "GhostTapViewModel.startScan" — used by [CapabilityScanner] to verify the
 *   registry against the actual code. For manual-only steps, name the screen,
 *   e.g. "Manual: NFC TOOLS screen".
 * @param run the actual work. Receives the step's args map, returns [OpResult].
 *   MUST be wrapped in try/catch by the author — a throwing run is a bug.
 */
data class IntelCapability(
    val id: String,
    val title: String,
    val detail: String,
    val category: String,
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList(),
    val preconditions: List<String> = emptyList(),
    val readOnly: Boolean,
    val riskLevel: String = "READ",
    val effects: String = "",
    val verified: Boolean = false,
    val sourceMethod: String = "",
    val run: suspend (Map<String, String>) -> OpResult
)

/** Result of one capability execution. Never throws; errors come back as ok=false. */
data class OpResult(
    val ok: Boolean,
    val summary: String,
    val data: String = ""
)

/**
 * The single source of truth the intelligence system uses to discover what
 * Ghost Tap can do. The planner, the investigations UI, and the knowledge
 * search all read from here — nothing else.
 */
object CapabilityRegistry {
    private val caps = LinkedHashMap<String, IntelCapability>()

    /**
     * THE integration contract — any new Ghost Tap tool becomes visible to the
     * intelligence system by calling register() exactly once, usually from
     * [IntelEngine]'s init block next to the other registrations.
     *
     * Example:
     * ```
     * CapabilityRegistry.register(
     *     IntelCapability(
     *         id = "mytab.cool_probe",
     *         title = "Probe the cool thing",
     *         detail = "Reads the cool thing over the existing link.",
     *         category = "MYTAB",
     *         inputs = listOf("targetId"),
     *         outputs = listOf("probe readout"),
     *         preconditions = listOf("target must be connected"),
     *         readOnly = true,                 // derived: riskLevel == "READ"
     *         riskLevel = "READ",              // READ | CONFIGURE | WRITE | CONTROL
     *         effects = "Reads the cool thing and reports the readout. Nothing is changed.",
     *         verified = false,                // true ONLY after a real-hardware test
     *         sourceMethod = "MyViewModel.probe", // real method, or "Manual: <screen>"
     *         run = { args ->
     *             try {
     *                 val id = args["targetId"] ?: return@register OpResult(false, "missing targetId")
     *                 val out = myViewModel.probe(id)   // the REAL call, not a stub
     *                 OpResult(true, "probe done", out)
     *             } catch (e: Exception) {
     *                 OpResult(false, "probe failed: ${e.message}")
     *             }
     *         }
     *     )
     * )
     * ```
     *
     * The planner discovers capabilities ONLY through this registry. There are
     * no other hardcoded behavior lists — if it isn't registered, the
     * intelligence system cannot see it, propose it, or run it.
     *
     * [CapabilityScanner] cross-checks every registration's [IntelCapability.sourceMethod]
     * against the actual ViewModel code via reflection and reports public
     * methods with no registry entry as wiring candidates.
     */
    fun register(c: IntelCapability) {
        caps[c.id] = c
    }

    fun all(): List<IntelCapability> = caps.values.toList()
    fun get(id: String): IntelCapability? = caps[id]
    fun ids(): Set<String> = caps.keys.toSet()
    fun byCategory(category: String): List<IntelCapability> =
        caps.values.filter { it.category == category }
}
