package com.ghosttap.core

import com.ghosttap.data.DiscoveredCharacteristic
import com.ghosttap.data.DiscoveredService
import com.ghosttap.data.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the bring-up handshake helpers (C-11). Pure Kotlin,
 * JUnit 4 — no Android framework, no ViewModels. The async wait/poll
 * in BringUpPipeline is not covered here (same sandbox limit as the
 * rest of the pipeline); the decisions the pipeline branches on are.
 */
class HandshakeTest {

    // ---- firstReadableCharacteristics ----

    private fun svc(uuid: String, vararg chars: Pair<String, List<String>>) =
        DiscoveredService(uuid, chars.map { DiscoveredCharacteristic(it.first, it.second) })

    @Test
    fun picksFirstReadableInDiscoveryOrder() {
        val services = listOf(
            svc("svc-a", "char-1" to listOf("READ"), "char-2" to listOf("WRITE")),
            svc("svc-b", "char-3" to listOf("READ", "NOTIFY"))
        )
        assertEquals(
            listOf("svc-a" to "char-1", "svc-b" to "char-3"),
            firstReadableCharacteristics(services)
        )
    }

    @Test
    fun skipsWriteOnlyAndNotifyOnlyCharacteristics() {
        val services = listOf(
            svc("svc-a", "char-1" to listOf("WRITE_NO_RESPONSE"), "char-2" to listOf("NOTIFY"))
        )
        assertTrue(firstReadableCharacteristics(services).isEmpty())
    }

    @Test
    fun emptyWhenNothingReadable() {
        assertTrue(firstReadableCharacteristics(emptyList()).isEmpty())
        assertTrue(
            firstReadableCharacteristics(
                listOf(svc("svc-a", "char-1" to listOf("WRITE")))
            ).isEmpty()
        )
    }

    @Test
    fun capsAtThreeByDefault() {
        val services = listOf(
            svc(
                "svc-a",
                "c1" to listOf("READ"), "c2" to listOf("READ"),
                "c3" to listOf("READ"), "c4" to listOf("READ")
            )
        )
        assertEquals(3, firstReadableCharacteristics(services).size)
        assertEquals(
            listOf("svc-a" to "c1", "svc-a" to "c2", "svc-a" to "c3"),
            firstReadableCharacteristics(services)
        )
    }

    // ---- readAnsweredSince ----

    private fun readEntry(charUuid: String, timestampMs: Long) =
        HistoryEntry(timestampMs, "READ", "svc", charUuid, "00", null, "")

    private fun notifyEntry(charUuid: String, timestampMs: Long) =
        HistoryEntry(timestampMs, "NOTIFY", "svc", charUuid, "00", null, "")

    @Test
    fun readAnswerCounts() {
        val records = listOf(readEntry("char-1", 1000L))
        assertTrue(readAnsweredSince(records, setOf("char-1"), 1000L))
        assertTrue(readAnsweredSince(records, setOf("char-1", "char-2"), 500L))
    }

    @Test
    fun staleReadDoesNotCount() {
        // A value from before the handshake started never proves an answer.
        val records = listOf(readEntry("char-1", 999L))
        assertFalse(readAnsweredSince(records, setOf("char-1"), 1000L))
    }

    @Test
    fun wrongCharacteristicDoesNotCount() {
        val records = listOf(readEntry("char-9", 1000L))
        assertFalse(readAnsweredSince(records, setOf("char-1"), 1000L))
    }

    @Test
    fun notifyEntryDoesNotCountAsReadAnswer() {
        val records = listOf(notifyEntry("char-1", 1000L))
        assertFalse(readAnsweredSince(records, setOf("char-1"), 1000L))
    }

    // ---- pingAnswered ----

    @Test
    fun pingAnsweredOnlyOnRealReply() {
        assertTrue(pingAnswered("Ping done: reply in 12 ms (answered in 12 ms)"))
        assertTrue(pingAnswered("192.168.1.5 answered in 3 ms"))
        assertFalse(pingAnswered("The device didn't answer the check"))
        assertFalse(pingAnswered("Couldn't find that device"))
        assertFalse(pingAnswered(""))
    }

    // ---- usbConnected ----

    @Test
    fun usbConnectedOnlyOnTrue() {
        assertTrue(usbConnected("connected=true, driver=CH340"))
        assertFalse(usbConnected("connected=false"))
        assertFalse(usbConnected(""))
    }

    // ---- esp32Answered ----

    @Test
    fun esp32AnsweredOnlyOnSuccessLine() {
        assertTrue(esp32Answered("The board answered — ready to load code"))
        // The failure line must NOT match: "didn't answer" has no "answered".
        assertFalse(esp32Answered("The board didn't answer — check the wires and that it's in download mode"))
        assertFalse(esp32Answered("Checking…"))
        assertFalse(esp32Answered("Not checked yet"))
        assertFalse(esp32Answered("Connect the USB cable first"))
        assertFalse(esp32Answered("No USB cable connection"))
    }

    // ---- nfcSummaryUsable ----

    @Test
    fun nfcSummaryUsableOnlyOnRealContent() {
        assertTrue(nfcSummaryUsable("MifareClassic 1K — UID 04 A1 2B 3C"))
        assertTrue(nfcSummaryUsable("NDEF: Hello"))
        assertFalse(nfcSummaryUsable(""))
        assertFalse(nfcSummaryUsable("   "))
        assertFalse(nfcSummaryUsable("No tag tapped yet."))
    }
}
