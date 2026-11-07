package com.ghosttap.data

import kotlinx.serialization.Serializable

@Serializable
enum class TargetType {
    BLE,
    BLUETOOTH_CLASSIC,
    NFC,
    USB,
    NETWORK,
    SENSOR
}

@Serializable
data class Capability(
    val id: String,
    val title: String,
    val kind: ControlKind,
    val readable: Boolean = false,
    val writable: Boolean = false,
    val notifiable: Boolean = false,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
enum class ControlKind {
    BUTTON,
    SWITCH,
    SLIDER,
    INPUT,
    RAW
}

@Serializable
data class ScanTarget(
    val id: String,
    val name: String,
    val type: TargetType,
    val identity: Map<String, String> = emptyMap(),
    val capabilities: List<Capability> = emptyList(),
    val rssi: Int? = null,
    val connected: Boolean = false,
    val watchValues: Map<String, String> = emptyMap(),
    val requiresAuth: Boolean = false,
    val lastSeenMs: Long = System.currentTimeMillis(),
    // Device identification (best-effort, offline). Null = not yet classified.
    val likely: String? = null,
    val confidence: String? = null, // "High" / "Medium" / "Low"
    val evidence: List<String> = emptyList()
)

@Serializable
data class BaselineSnapshot(
    val timestampMs: Long,
    val targets: Map<String, BaselineTarget>
)

@Serializable
data class BaselineTarget(
    val id: String,
    val name: String,
    val type: TargetType,
    val fingerprint: String,
    val rssi: Int?
)

@Serializable
data class BaselineDiff(
    val newTargets: List<String> = emptyList(),
    val missingTargets: List<String> = emptyList(),
    val changedTargets: List<String> = emptyList(),
    val signalChangedTargets: List<String> = emptyList()
)

/**
 * GATT discovery results, display-only. These are populated exclusively from
 * data returned by a real BluetoothGatt connection — never fabricated,
 * never simulated. Not persisted.
 */
data class DiscoveredCharacteristic(
    val uuid: String,
    /** e.g. ["READ", "WRITE_NO_RESPONSE", "NOTIFY"] — decoded from the real property bitmask. */
    val properties: List<String>
)

data class DiscoveredService(
    val uuid: String,
    val characteristics: List<DiscoveredCharacteristic>
)

/**
 * One captured BLE packet (received or sent). Displayed in the live
 * translator feed with its plain-English interpretation.
 */
data class PacketEvent(
    val timestampMs: Long,
    /** "RX" = device → phone, "TX" = phone → device */
    val direction: String,
    val serviceUuid: String,
    val charUuid: String,
    /** "A1 03 01 7F" */
    val hex: String,
    val bytes: List<Int>,
    val ascii: String?,
    val interpretation: String,
    /** LOW / MEDIUM / HIGH */
    val confidence: String,
    /** 0-based indexes of bytes that changed vs the previous packet on this channel. */
    val changedBytes: List<Int>,
    val prevHex: String?,
    /** Consecutive identical packets are grouped; this counts the group size. */
    val repeatCount: Int = 1
)

/** A user-confirmed mapping of a real-world action to observed BLE traffic. */
@Serializable
data class LearnedAction(
    val id: String,
    val name: String,
    val serviceUuid: String,
    val charUuid: String,
    /** Observed packet hex, e.g. "A1 03 01 7F". */
    val packetHex: String,
    /** e.g. "byte 3: 00 → 01" */
    val byteChanges: String,
    /** LOW / MEDIUM / HIGH */
    val confidence: String,
    val reason: String,
    /** Always "Captured from device interaction" — never fabricated. */
    val source: String,
    val learnedAtMs: Long
)

@Serializable
data class DeviceLearnedMap(
    val address: String,
    val actions: List<LearnedAction> = emptyList()
)

/** Plain-English summary shown right after a BLE connection. */
data class DeviceSummary(
    val connected: Boolean,
    val deviceType: String,
    val typeConfidence: String,
    val totalServices: Int,
    val standardServices: Int,
    val customServices: Int,
    /** Friendly description of the top-ranked custom channel, or null. */
    val mostInteresting: String?,
    val mostInterestingWhy: String?,
    /** (serviceUuid, charUuid) of the most interesting channel, or null. */
    val mostInterestingChannel: Pair<String, String>?
)

/** Result of one STOP LEARNING comparison, awaiting a user-supplied name. */
data class LearnedDiscovery(
    val id: String,
    val serviceUuid: String,
    val charUuid: String,
    val packetHex: String,
    val byteChanges: String,
    val confidence: String,
    val reason: String
)

/**
 * One response recorded from a BLE endpoint — powers the MAP INTERFACE
 * response history. Display-only, never fabricated.
 */
data class HistoryEntry(
    val timestampMs: Long,
    /** "READ", "WRITE", or "NOTIFY". */
    val kind: String,
    val serviceUuid: String,
    val charUuid: String,
    /** "A1 03 01 7F" */
    val hex: String,
    val ascii: String?,
    val interpretation: String
)

/** One "did it print?" check waiting on the user after a successful print. */
data class PrintCheck(
    val targetId: String,
    val text: String,
    val languageId: String
)

/** One channel that moved during a MAP INTERFACE capture round. */
data class ChannelChange(
    val serviceUuid: String,
    val charUuid: String,
    /** e.g. "byte 3: 00 → 01" */
    val byteChanges: String,
    val beforeHex: String,
    val afterHex: String
)

/**
 * One MAP INTERFACE capture round (TEST 1, TEST 2, ...): a BEFORE snapshot,
 * the user performs an action, then an AFTER snapshot. Changed channels are
 * compared across rounds to find repeatable correlations.
 */
data class MapCapture(
    val label: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val changes: List<ChannelChange>
)
