package com.ghosttap.core.investigate

/**
 * Shared models for Ghost Tap's Autonomous Investigations capability.
 *
 * Pure Kotlin — no Android framework imports. Every string that reaches
 * the screen must be plain words a non-tech person understands.
 */

enum class InvestigationSource {
    BLE,
    USB,
    NFC,
    NETWORK,
    VISUAL,
    VIRTUAL_ESP32,
    MANUAL
}

enum class Confidence {
    HIGH,
    MEDIUM,
    LOW
}

/** Plain-words wording for a confidence level. */
fun Confidence.plainWords(): String = when (this) {
    Confidence.HIGH -> "Pretty sure"
    Confidence.MEDIUM -> "Fairly sure"
    Confidence.LOW -> "Not sure yet"
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

/** Plain-words wording for a risk level. */
fun RiskLevel.plainWords(): String = when (this) {
    RiskLevel.LOW -> "Low risk"
    RiskLevel.MEDIUM -> "Some risk"
    RiskLevel.HIGH -> "High risk"
}

enum class ControlKind {
    READ,
    WRITE,
    TOGGLE,
    ACTION
}

/**
 * One thing Ghost Tap believes it can do with an investigated target.
 * [label] and [plainDescription] are shown on screen as-is, so both
 * must already be plain words. [readOnly] is true only for controls
 * that cannot change the device.
 */
data class InvestigatedControl(
    val label: String,
    val plainDescription: String,
    val kind: ControlKind,
    val readOnly: Boolean
)

/** One risk, in plain words, attached to a findings report. */
data class RiskNote(
    val level: RiskLevel,
    val plainText: String
)

/**
 * Everything the caller observed about a target, before any judgement.
 * [signalText] is passed through verbatim by the investigator — callers
 * supply things like "-86 dBm — Weak" and it must never be turned into
 * a distance. [answeredHandshake] is true only when the target actually
 * answered a handshake check.
 */
data class InvestigationInput(
    val source: InvestigationSource,
    val rawName: String?,
    val rawDetails: Map<String, String>,
    val readableValues: List<String>,
    val answeredHandshake: Boolean,
    val signalText: String?,
    val targetId: String? = null
)

/**
 * The investigator's report on one target. [believedIdentity] is
 * "UNKNOWN" whenever the evidence was not enough to name the target —
 * the investigator never invents a device name, model, or brand.
 */
data class FindingsReport(
    val targetLabel: String,
    val targetKind: String,
    val believedIdentity: String,
    val confidence: Confidence,
    val evidence: List<String>,
    val capabilities: List<InvestigatedControl>,
    val risks: List<RiskNote>,
    val connectLabel: String,
    val stateChanging: Boolean,
    val answeredHandshake: Boolean,
    val targetId: String? = null
)
