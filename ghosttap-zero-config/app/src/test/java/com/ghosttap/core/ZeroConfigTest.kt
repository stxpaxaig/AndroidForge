package com.ghosttap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the zero-config transformation (2026-11-06):
 * DISCOVER → SELECT → AUTO-CONNECT → COMMANDS, with no manual
 * config, no standalone global picker, and intent routing grounded
 * in real registered capabilities only.
 *
 * Scope honesty, read before trusting these:
 * - These are pure-JVM tests of the rules in ZeroConfig.kt. They do
 *   NOT run the app, the Compose UI, a Gradle build, or a device.
 * - The capability refs below copy REAL registered operations
 *   (id + title + description + area, verified against
 *   OperationAreaA.kt) so the router tests prove matching against
 *   the real registry surface, not invented entries.
 * - Auto-connect on a real phone (BLE bring-up, USB connect,
 *   Wi-Fi join, TCP HELLO) is NOT proven here — it needs the
 *   user's phone and field test. What IS proven here: the
 *   pre-filled details are complete for every discovered kind
 *   except secured Wi-Fi, and no port is ever guessed.
 * - No API key exists in this environment; none is used here.
 */
class ZeroConfigTest {

    /** Real registered operations (OperationAreaA.kt), as router inputs. */
    private val realCaps = listOf(
        IntentRouter.CapabilityRef(
            id = "scan.find-strongest",
            title = "Find the strongest device nearby",
            description = "Searches for about 10 seconds, then names the strongest device found.",
            area = "BLE"
        ),
        IntentRouter.CapabilityRef(
            id = "scan.bring-up-highlighted",
            title = "Bring up the strongest device found",
            description = "Connects to the device the last search highlighted and gets it ready to control.",
            area = "BLE"
        ),
        IntentRouter.CapabilityRef(
            id = "target.connect",
            title = "Connect to a device",
            description = "Connects to the device you pick and checks the link is really open.",
            area = "BLE"
        ),
        IntentRouter.CapabilityRef(
            id = "ble.read-interesting",
            title = "Read the most interesting thing",
            description = "Reads the one part of the device that looks most useful, without reading everything.",
            area = "BLE"
        ),
        IntentRouter.CapabilityRef(
            id = "net.scan-wifi",
            title = "Find devices on my Wi-Fi",
            description = "Finds every live device on your Wi-Fi and lists them.",
            area = "NETWORK"
        ),
        IntentRouter.CapabilityRef(
            id = "screen.network_tools",
            title = "Check what's on my Wi-Fi",
            description = "Opens NETWORK TOOLS with a Wi-Fi scan already running.",
            area = "NETWORK"
        )
    )

    // ---- DISCOVER → AUTO-CONNECT: pre-filled details, no typing ----

    @Test
    fun discoveredNetworkHostPrefillsAddressAndPort() {
        val endpoint = DiscoveredEndpoint(
            kind = DiscoveredKind.NETWORK_HOST,
            id = "net:192.168.1.42",
            label = "192.168.1.42",
            host = "192.168.1.42",
            openPorts = listOf(9000, 80)
        )
        val connection = autoConnectionFor(endpoint)
        assertEquals("192.168.1.42", connection.host)
        assertEquals(80, connection.port)
        assertTrue(connection.ready)
        assertTrue(connection.missingFields.isEmpty())
    }

    @Test
    fun discoveredNetworkHostWithNoOpenPortIsNotReadyAndGuessesNothing() {
        val endpoint = DiscoveredEndpoint(
            kind = DiscoveredKind.NETWORK_HOST,
            id = "net:192.168.1.43",
            label = "192.168.1.43",
            host = "192.168.1.43",
            openPorts = emptyList()
        )
        val connection = autoConnectionFor(endpoint)
        assertNull(connection.port)
        assertFalse(connection.ready)
        assertTrue(connection.missingFields.any { it.contains("port") })
    }

    @Test
    fun openWifiNetworkNeedsNothingTyped() {
        val endpoint = DiscoveredEndpoint(
            kind = DiscoveredKind.WIFI_NETWORK,
            id = "wifi:HomeNet",
            label = "HomeNet",
            ssid = "HomeNet",
            security = "OPEN"
        )
        val connection = autoConnectionFor(endpoint)
        assertEquals("HomeNet", connection.ssid)
        assertFalse(connection.needsPassword)
        assertTrue(connection.ready)
        assertTrue(connection.missingFields.isEmpty())
    }

    @Test
    fun securedWifiPasswordIsTheOnlyMissingField() {
        val endpoint = DiscoveredEndpoint(
            kind = DiscoveredKind.WIFI_NETWORK,
            id = "wifi:HomeNet",
            label = "HomeNet",
            ssid = "HomeNet",
            security = "WPA2"
        )
        val connection = autoConnectionFor(endpoint)
        assertTrue(connection.needsPassword)
        assertFalse(connection.ready)
        assertEquals(listOf("Wi-Fi password"), connection.missingFields)
    }

    @Test
    fun bleUsbNfcAndVirtualNeedNoTyping() {
        val endpoints = listOf(
            DiscoveredEndpoint(DiscoveredKind.BLE, "AA:BB:CC:DD:EE:FF", "Bluetooth device"),
            DiscoveredEndpoint(DiscoveredKind.USB, "usb", "Plugged-in board"),
            DiscoveredEndpoint(DiscoveredKind.NFC, "nfc:04A1B2", "Tap card"),
            DiscoveredEndpoint(DiscoveredKind.VIRTUAL_ESP32, "virtual:practice-board", "Practice board")
        )
        for (endpoint in endpoints) {
            val connection = autoConnectionFor(endpoint)
            assertTrue("expected ${endpoint.kind} to be ready: $connection", connection.ready)
            assertTrue(
                "expected ${endpoint.kind} to need nothing typed: $connection",
                connection.missingFields.isEmpty()
            )
        }
    }

    @Test
    fun preferredPortPrefersReadablePortsThenFirstOpen() {
        assertEquals(80, preferredPort(listOf(9000, 80)))
        assertEquals(443, preferredPort(listOf(9000, 443)))
        assertEquals(1883, preferredPort(listOf(1883)))
        assertEquals(9000, preferredPort(listOf(9000)))
        assertNull(preferredPort(emptyList()))
    }

    // ---- INTENT BOX: plain words → real capability, never invented ----

    @Test
    fun intentRoutesToRealCapabilityForWifiScan() {
        assertEquals("net.scan-wifi", IntentRouter.resolve("find devices on my wifi", realCaps))
    }

    @Test
    fun intentRoutesToRealCapabilityForStrongestDevice() {
        assertEquals(
            "scan.find-strongest",
            IntentRouter.resolve("find the strongest device nearby", realCaps)
        )
    }

    @Test
    fun intentRoutesToRealCapabilityForConnect() {
        assertEquals("target.connect", IntentRouter.resolve("connect to a device", realCaps))
    }

    @Test
    fun intentRoutesToRealCapabilityForReading() {
        assertEquals(
            "ble.read-interesting",
            IntentRouter.resolve("read the most interesting thing", realCaps)
        )
    }

    @Test
    fun unknownIntentReturnsNullAndInventsNothing() {
        assertNull(IntentRouter.resolve("paint my house purple and order pizza", realCaps))
        assertTrue(IntentRouter.route("paint my house purple and order pizza", realCaps).isEmpty())
        assertNull(IntentRouter.resolve("", realCaps))
        assertNull(IntentRouter.resolve("please", realCaps))
    }

    @Test
    fun routingNeverReturnsAnIdOutsideTheSuppliedList() {
        val knownIds = realCaps.map { it.id }.toSet()
        val intents = listOf(
            "find devices on my wifi",
            "find the strongest device nearby",
            "connect to a device",
            "read the most interesting thing",
            "check what's on my wifi",
            "bring up the strongest device found",
            "do something bluetooth",
            "look at the network"
        )
        for (intent in intents) {
            for (id in IntentRouter.route(intent, realCaps)) {
                assertTrue("router invented id $id for intent '$intent'", id in knownIds)
            }
        }
    }

    // ---- NO STANDALONE GLOBAL PICKER ----

    @Test
    fun globalPickerRouteIsRemoved() {
        assertTrue(ZeroConfigRoutes.isGlobalPickerRoute("what_to_do"))
        assertFalse(ZeroConfigRoutes.isGlobalPickerRoute("what_to_do/NETWORK"))
        assertFalse("what_to_do" in ZeroConfigRoutes.appRoutes())
    }

    @Test
    fun perFunctionPickerRoutesExistForEveryArea() {
        for (area in ZeroConfigRoutes.AREAS) {
            val route = ZeroConfigRoutes.perFunctionPickerRoute(area)
            assertTrue(ZeroConfigRoutes.isPerFunctionPickerRoute(route))
            assertTrue(route in ZeroConfigRoutes.appRoutes())
        }
        assertEquals(7, ZeroConfigRoutes.AREAS.size)
    }

    @Test
    fun perFunctionPickerContentIsPerAreaOnly() {
        val network = IntentRouter.perFunctionPicker("NETWORK", realCaps)
        assertEquals(listOf("net.scan-wifi", "screen.network_tools"), network.map { it.id })
        val ble = IntentRouter.perFunctionPicker("BLE", realCaps)
        assertEquals(
            listOf(
                "scan.find-strongest",
                "scan.bring-up-highlighted",
                "target.connect",
                "ble.read-interesting"
            ),
            ble.map { it.id }
        )
        assertTrue(IntentRouter.perFunctionPicker("VISUAL", realCaps).isEmpty())
    }

    // ---- NO-CONFIG REGRESSION: nothing demands typed input first ----

    @Test
    fun noScreenDemandsTypedInputBeforeConnectExceptSecuredWifi() {
        val discovered = listOf(
            DiscoveredEndpoint(DiscoveredKind.BLE, "AA:BB:CC:DD:EE:FF", "Bluetooth device"),
            DiscoveredEndpoint(
                DiscoveredKind.NETWORK_HOST, "net:192.168.1.42", "192.168.1.42",
                host = "192.168.1.42", openPorts = listOf(80)
            ),
            DiscoveredEndpoint(
                DiscoveredKind.WIFI_NETWORK, "wifi:OpenNet", "OpenNet",
                ssid = "OpenNet", security = "OPEN"
            ),
            DiscoveredEndpoint(DiscoveredKind.USB, "usb", "Plugged-in board"),
            DiscoveredEndpoint(DiscoveredKind.NFC, "nfc:04A1B2", "Tap card"),
            DiscoveredEndpoint(DiscoveredKind.VIRTUAL_ESP32, "virtual:practice-board", "Practice board")
        )
        for (endpoint in discovered) {
            val connection = autoConnectionFor(endpoint)
            assertTrue(
                "${endpoint.kind} must connect with no typed input: $connection",
                connection.missingFields.isEmpty()
            )
        }
        val secured = autoConnectionFor(
            DiscoveredEndpoint(
                DiscoveredKind.WIFI_NETWORK, "wifi:HomeNet", "HomeNet",
                ssid = "HomeNet", security = "WPA2"
            )
        )
        assertEquals(listOf("Wi-Fi password"), secured.missingFields)
    }
}
