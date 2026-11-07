package com.ghosttap.core.ops

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Package 5 — every USB / ESP32 / Virtual ESP32 / Network / Visual
 * operation (D-01–D-31, B-084–B-126) registers, with plain-words
 * titles and descriptions and no placeholder text. Registration only
 * constructs Operation data — no ViewModel, no Android call — so this
 * runs on the JVM like the other ops tests.
 */
class Package5OperationsTest {

    private val expectedIds = listOf(
        // USB (27)
        "usb.find-board", "usb.describe-board", "usb.allow-access",
        "usb.connect-board", "usb.disconnect-board", "usb.find-speed",
        "usb.set-speed", "usb.talk-to-board", "usb.watch-replies",
        "usb.clear-messages", "usb.switch-message-mode", "usb.toggle-newline",
        "usb.clear-history", "usb.wires-what-they-do", "usb.flip-reset-wire",
        "usb.flip-boot-wire", "usb.test-board", "usb.read-board-setting",
        "usb.put-in-load-mode", "usb.restart-board", "usb.check-software-file",
        "usb.pick-software-file", "usb.load-software",
        "usb.verify-loaded-software", "usb.set-flash-spot", "usb.copy-log",
        "usb.show-details",
        // Virtual ESP32 (12)
        "vesp32.check-installed", "vesp32.connect-practice-board",
        "vesp32.disconnect-practice-board", "vesp32.use-practice-board",
        "vesp32.start-practice-software", "vesp32.stop-practice-software",
        "vesp32.restart-practice-software", "vesp32.send-message",
        "vesp32.check-light-pin", "vesp32.flip-switch-pin",
        "vesp32.use-built-in-software", "vesp32.check-practice-status",
        // Network (16)
        "net.find-on-wifi", "net.find-and-talk", "net.check-host-ports",
        "net.whats-talking", "net.open-advertised-service",
        "net.talk-to-device", "net.udp-send", "net.fetch-page",
        "net.ping-host", "net.ping-from-photo", "net.talk-to-open-port",
        "net.port-scan", "net.start-hotspot", "net.check-hotspot-joined",
        "net.stop-hotspot", "net.clear-results",
        // Visual (13)
        "visual.open-camera", "visual.stop-camera", "visual.capture-and-act",
        "visual.act-on-last-photo", "visual.read-gallery-photo",
        "visual.save-last-photo", "visual.rename-profile",
        "visual.delete-profile", "visual.reload-profiles",
        "visual.fetch-link", "visual.join-wifi-from-photo",
        "visual.open-wifi-settings", "visual.copy-from-photo"
    )

    @Before
    fun setUp() {
        OperationRegistry.clear()
    }

    @After
    fun tearDown() {
        OperationRegistry.clear()
    }

    @Test
    fun allPackage5OperationsRegister() {
        registerAreaHardwareOperations()
        assertEquals(68, expectedIds.size)
        expectedIds.forEach { id ->
            assertNotNull("missing operation: $id", OperationRegistry.get(id))
        }
    }

    @Test
    fun allPackage5OperationsHavePlainWordsCopy() {
        registerAreaHardwareOperations()
        val banned = listOf("TODO", "placeholder", "not implemented")
        expectedIds.forEach { id ->
            val op = OperationRegistry.get(id)!!
            assertTrue("$id: blank title", op.title.isNotBlank())
            assertTrue("$id: blank description", op.description.isNotBlank())
            banned.forEach { word ->
                assertTrue(
                    "$id: '$word' in title/description",
                    word !in op.title && word !in op.description
                )
            }
        }
    }
}
