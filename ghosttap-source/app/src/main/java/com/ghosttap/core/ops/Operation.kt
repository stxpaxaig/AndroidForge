package com.ghosttap.core.ops

import com.ghosttap.GhostTapViewModel
import com.ghosttap.IntelViewModel
import com.ghosttap.NetworkToolsViewModel
import com.ghosttap.UsbUartViewModel
import com.ghosttap.VisualIntelViewModel
import com.ghosttap.core.IntelEngine
import com.ghosttap.core.OpResult
import com.ghosttap.core.esp32.VirtualEsp32Client

/**
 * Package 0 — shared operation framework.
 *
 * An [Operation] is one outcome the user can pick ("find the strongest
 * device and connect to it", "print these words", "copy this card onto
 * that card"). It wraps the EXISTING ViewModel / engine functions — it
 * never re-implements them. The [OperationRunner] runs preparation, then
 * execution, then verification, in that order, and reports the outcome
 * in plain words.
 *
 * Everything in this file is pure Kotlin data plus references to the
 * existing ViewModels / engine, so the registry, runner and verification
 * helpers can be unit-tested on the JVM with no Android framework.
 */

/** The kind of live link an operation can check. Used by preparation and verification. */
enum class TransportKind {
    /** Bluetooth Low Energy (GATT) link. */
    BLE,
    /** Classic Bluetooth text link (SPP / RFCOMM). */
    SPP,
    /** USB cable link to a board. */
    USB,
    /** A host on the phone's Wi-Fi. */
    NETWORK,
    /** A tap card held against the phone. */
    NFC
}

/**
 * Grouping for the "pick what you want to do" screen. The [label] is
 * plain words; [capabilityCategory] is the matching [com.ghosttap.core.IntelCapability]
 * category so an operation published to the [com.ghosttap.core.CapabilityRegistry]
 * lands in a category the planner already understands.
 */
enum class OperationArea(val label: String, val capabilityCategory: String) {
    BLE("Bluetooth devices", "BLE"),
    USB("USB cable and boards", "USB"),
    NETWORK("Wi-Fi and network", "NETWORK"),
    NFC("Tap cards", "NFC"),
    VISUAL("Camera and photos", "VISUAL"),
    INVESTIGATIONS("Investigations", "SYSTEM"),
    SYSTEM("App tools", "SYSTEM")
}

/**
 * Bounded retry policy. [maxAttempts] is the TOTAL number of tries
 * (1 = try once, never retry). [delayMs] is the wait between tries.
 * The runner never retries without bound.
 */
data class RetryPolicy(
    val maxAttempts: Int = 1,
    val delayMs: Long = 1000L
)

/**
 * Everything an operation's steps need to call the real app: the
 * ViewModels, the engine, and the two things only the UI can do
 * (navigate to a screen, ask the system for permissions / Bluetooth).
 * All ViewModel references are nullable so the pure-Kotlin framework
 * (registry, runner, verification) can be tested with an empty context
 * — operations that need a ViewModel report that honestly instead of
 * crashing when it is missing.
 *
 * [pendingChoice] is the choice channel for NEEDS_USER_CHOICE results:
 * when a run ends asking the user to pick one of several things, the
 * entry screen writes the picked choice here and runs the operation
 * again; preparation steps read it to continue with that choice.
 */
class OperationContext(
    val ble: GhostTapViewModel? = null,
    val usb: UsbUartViewModel? = null,
    val net: NetworkToolsViewModel? = null,
    val visual: VisualIntelViewModel? = null,
    val intel: IntelViewModel? = null,
    val engine: IntelEngine? = null,
    /** The practice-board client, for the VIRTUAL ESP32 screen operation. */
    val vesp32: VirtualEsp32Client? = null,
    /** Opens a screen by route, e.g. "usb_uart". No-op when unset. */
    val navigate: (String) -> Unit = {},
    /** Asks the system for runtime permissions (MainActivity's launcher). */
    val requestPermissions: () -> Unit = {},
    /** Opens the system file picker for a firmware .bin (MainActivity's launcher). */
    val selectFirmware: () -> Unit = {},
    /** Opens the system gallery picker for a photo (MainActivity's launcher). */
    val pickImage: () -> Unit = {}
) {
    var pendingChoice: String? = null
}

/** One preparation check, in plain words, run before execution. */
data class PreparationStep(
    /** Plain-words label shown while this check runs, e.g. "Checking Bluetooth is on". */
    val label: String,
    val check: suspend (OperationContext) -> PreparationOutcome
)

/** One progress report: a plain-words step label and how far along the run is (0.0–1.0). */
data class OperationStep(
    val label: String,
    val fraction: Float
)

/** Progress of a run, observed by the UI through the runner's StateFlow. */
data class OperationProgress(
    val operationId: String,
    val step: OperationStep,
    val running: Boolean
)

/**
 * The outcome of one operation run. Every outcome carries a plain-words
 * [reason] and the [evidence] that was actually observed — a DONE is
 * never returned without verification evidence.
 */
sealed class OperationResult {
    abstract val reason: String
    abstract val evidence: String

    /** It worked, and [evidence] says what was actually observed. */
    data class Done(
        override val reason: String,
        override val evidence: String
    ) : OperationResult()

    /** It did not work. [reason] says why, in plain words. */
    data class Failed(
        override val reason: String,
        override val evidence: String = ""
    ) : OperationResult()

    /**
     * The operation cannot continue until the user picks one of [choices].
     * The entry screen shows the choices as buttons; the picked choice is
     * written to [OperationContext.pendingChoice] and the operation runs again.
     */
    data class NeedsUserChoice(
        override val reason: String,
        override val evidence: String = "",
        val choices: List<String> = emptyList()
    ) : OperationResult()

    /**
     * The operation cannot continue until the user does something only
     * they can do. [prompt] is the plain-words instruction. When
     * [actionLabel] is set, the entry screen shows a button with that
     * label that runs [action] (e.g. "Turn on Bluetooth").
     */
    data class NeedsUserAction(
        override val reason: String,
        override val evidence: String = "",
        val prompt: String,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null
    ) : OperationResult()
}

/**
 * One outcome the user can pick.
 *
 * @param id stable id, e.g. "scan.connect-strongest" or "screen.usb_uart".
 *   The planner may only emit ids that exist in [OperationRegistry].
 * @param title plain-words title of the outcome, e.g. "Connect to the strongest thing nearby".
 * @param description one plain-words line saying what happens when it runs.
 * @param area grouping for the entry screen.
 * @param prerequisites plain-words list of what must already be true
 *   (shown on the entry screen so the user knows what is needed).
 * @param preparation checks run in order before execution. A check that
 *   returns NeedsUserAction / NeedsUserChoice / Failed stops the run
 *   before anything executes.
 * @param execute the real work — a suspend lambda that calls an EXISTING
 *   ViewModel / engine function and returns its [OpResult]. It must
 *   never re-implement what that function does, only wrap it.
 * @param verify checks the execution result against what was actually
 *   observed. Defaults to [defaultVerify]: ok + a real response. A run
 *   is DONE only when verify returns Verified with evidence.
 * @param retry bounded retry for execution failures (exception or
 *   ok=false). Verification failure is NOT retried — the operation ran,
 *   and re-running it would not change what was observed.
 * @param riskLevel same words as [com.ghosttap.core.IntelCapability]:
 *   READ (observes only), CONFIGURE (opens/closes a link), WRITE (sends
 *   data out), CONTROL (drives hardware behaviour).
 * @param verified true only when the underlying tool has been exercised
 *   on real hardware at least once. A green build alone does NOT count.
 * @param route when set, this is a screen-first operation: execution
 *   opens this route (e.g. "usb_uart"). The run still goes through the
 *   runner — preparation, navigation, verification — like any operation.
 */
data class Operation(
    val id: String,
    val title: String,
    val description: String,
    val area: OperationArea,
    val prerequisites: List<String> = emptyList(),
    val preparation: List<PreparationStep> = emptyList(),
    val execute: suspend (OperationContext) -> OpResult,
    val verify: suspend (OperationContext, OpResult) -> VerificationOutcome =
        { _, result -> defaultVerify(result) },
    val retry: RetryPolicy = RetryPolicy(),
    val riskLevel: String = "READ",
    val verified: Boolean = false,
    val route: String? = null
) {
    /** True when this operation opens a screen instead of driving a device. */
    val isScreenFirst: Boolean get() = route != null
}
