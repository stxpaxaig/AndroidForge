package com.ghosttap.core

/**
 * Bluetooth SIG standard UUID database (offline).
 *
 * Recognized 16-bit service/characteristic UUIDs are expanded to their full
 * 128-bit form and mapped to plain-English names. Anything not in these
 * tables is treated as custom/device-specific — never guessed.
 *
 * Tables are intentionally conservative: only well-established SIG assignments
 * are listed. Add new entries in the marked sections below.
 */
object SigUuids {

    private fun u16(short: Int): String =
        "0000" + short.toString(16).padStart(4, '0') + "-0000-1000-8000-00805f9b34fb"

    // ---------- Standard GATT services ----------
    val services: Map<String, String> = mapOf(
        u16(0x1800) to "Generic Access",
        u16(0x1801) to "Generic Attribute",
        u16(0x1802) to "Immediate Alert",
        u16(0x1803) to "Link Loss",
        u16(0x1804) to "Tx Power",
        u16(0x1805) to "Current Time",
        u16(0x1808) to "Glucose",
        u16(0x1809) to "Health Thermometer",
        u16(0x180A) to "Device Information",
        u16(0x180D) to "Heart Rate",
        u16(0x180F) to "Battery Service",
        u16(0x1810) to "Blood Pressure",
        u16(0x1811) to "Alert Notification",
        u16(0x1812) to "Human Interface Device",
        u16(0x1813) to "Scan Parameters",
        u16(0x1814) to "Running Speed and Cadence",
        u16(0x1815) to "Automation IO",
        u16(0x1816) to "Cycling Speed and Cadence",
        u16(0x1818) to "Cycling Power",
        u16(0x1819) to "Location and Navigation",
        u16(0x181A) to "Environmental Sensing",
        u16(0x181C) to "User Data",
        u16(0x181D) to "Weight Scale",
        u16(0x1820) to "Internet Protocol Support",
        u16(0x1821) to "Indoor Positioning",
        u16(0x1822) to "Pulse Oximeter",
        u16(0x1826) to "Fitness Machine"
        // ADD NEW STANDARD SERVICES HERE
    )

    // ---------- Standard GATT characteristics ----------
    val characteristics: Map<String, String> = mapOf(
        u16(0x2A00) to "Device Name",
        u16(0x2A01) to "Appearance",
        u16(0x2A02) to "Peripheral Privacy Flag",
        u16(0x2A03) to "Reconnection Address",
        u16(0x2A04) to "Preferred Connection Parameters",
        u16(0x2A07) to "Tx Power Level",
        u16(0x2A19) to "Battery Level",
        u16(0x2A1C) to "Temperature Measurement",
        u16(0x2A24) to "Model Number",
        u16(0x2A25) to "Serial Number",
        u16(0x2A26) to "Firmware Revision",
        u16(0x2A27) to "Hardware Revision",
        u16(0x2A28) to "Software Revision",
        u16(0x2A29) to "Manufacturer Name",
        u16(0x2A37) to "Heart Rate Measurement",
        u16(0x2A38) to "Body Sensor Location",
        u16(0x2A39) to "Heart Rate Control Point",
        u16(0x2A6E) to "Temperature",
        u16(0x2A6F) to "Humidity",
        u16(0x2A70) to "True Wind Speed",
        u16(0x2A71) to "True Wind Direction",
        u16(0x2A72) to "Apparent Wind Speed",
        u16(0x2A73) to "Apparent Wind Direction",
        u16(0x2A74) to "Gust Factor",
        u16(0x2A75) to "Pollen Concentration",
        u16(0x2A76) to "UV Index",
        u16(0x2A77) to "Irradiance",
        u16(0x2A78) to "Rainfall",
        u16(0x2A79) to "Wind Chill",
        u16(0x2A7A) to "Heat Index",
        u16(0x2A7B) to "Dew Point",
        u16(0x2AF0) to "Electric Current",
        u16(0x2AF1) to "Electric Current Range",
        u16(0x2AF2) to "Electric Current Specification"
        // ADD NEW STANDARD CHARACTERISTICS HERE
    )

    fun serviceName(uuid: String): String? = services[uuid.lowercase()]

    fun characteristicName(uuid: String): String? = characteristics[uuid.lowercase()]

    fun isStandardService(uuid: String): Boolean = services.containsKey(uuid.lowercase())

    /** Short display form: "0000180f…" */
    fun shortForm(uuid: String): String =
        if (uuid.length >= 8) uuid.take(8) + "…" else uuid
}
