package com.ghosttap.core

/**
 * Translates raw technical values into human-readable form.
 * Raw values are always retained alongside the interpretation.
 */

/** -42 dBm — Very strong / -61 dBm — Moderate / -86 dBm — Weak. Never a distance. */
fun describeRssi(rssi: Int?): String {
    if (rssi == null) return "Unknown"
    val band = when {
        rssi >= -50 -> "Very strong"
        rssi >= -70 -> "Moderate"
        rssi >= -90 -> "Weak"
        else -> "Very weak"
    }
    return band
}

/** bondState: 10 -> "Bond: NONE (10)". Keeps the raw code. */
fun describeBondState(raw: String): String = when (raw.trim()) {
    "10", "BOND_NONE" -> "Not paired"
    "11", "BOND_BONDING" -> "Pairing…"
    "12", "BOND_BONDED" -> "Paired"
    else -> raw
}

/** Short display names for identity keys. */
fun prettyIdentityKey(key: String): String = when (key) {
    "bondState" -> "Paired with this phone"
    "txPower" -> "Signal power"
    "advertisedServices" -> "What it offers"
    "address" -> "Address"
    "mac" -> "Address"
    "tagId" -> "Card ID"
    "vendorId" -> "Maker code"
    "productId" -> "Product code"
    "ndefType" -> "Saved data type"
    "maxSize" -> "Most it can hold"
    "connectable" -> "Can connect"
    "bluetoothClass" -> "Device class"
    "manufacturerId" -> "Maker code"
    "manufacturerData" -> "Maker data"
    "serviceData" -> "Feature data"
    else -> key
}

/** Translate a raw identity value for display; raw is kept in parentheses. */
fun translateIdentityValue(key: String, value: String): String = when (key) {
    "bondState" -> describeBondState(value)
    "txPower" -> if (value == "unknown") value else "$value dBm"
    else -> value
}
