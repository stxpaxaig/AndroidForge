package com.ghosttap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Package 4 — unit tests for the pure NFC helpers: the named script
 * blocks ([buildNamedScript]) and the shared tag-session manager
 * ([gateForPanel], [ndefFitsOnCard], [nextApduCheck],
 * [emulatableFromSummary], [ultralightPageWarning]). Pure Kotlin,
 * JUnit 4 — no Android framework, no ViewModels. The one Android-only
 * helper ([classicTrailerBlock], needs a real `Tag`) is exercised
 * on-device only, never here.
 */
class NfcPackage4Test {

    private fun facts(
        hasNdef: Boolean = true,
        writable: Boolean = true,
        canFormat: Boolean = false,
        isIsoDep: Boolean = false,
        maxSize: Int? = 100,
        classicSectors: Int? = null,
        ultralightType: String? = null,
        likelyType: String = "NDEF data tag",
        techs: List<String> = listOf("android.nfc.tech.Ndef")
    ) = NfcFacts(
        uidHex = "04AABBCC",
        techs = techs,
        likelyType = likelyType,
        ndefType = if (hasNdef) "NFC Forum" else null,
        ndefMaxSize = maxSize,
        ndefWritable = writable,
        hasNdef = hasNdef,
        canFormat = canFormat,
        isIsoDep = isIsoDep,
        isoDepHistorical = null,
        isoDepHiLayer = null,
        mifareClassicSize = null,
        mifareClassicSectors = classicSectors,
        mifareUltralightType = ultralightType,
        capacityLine = null,
        chipLine = null
    )

    // ---- Named scripts: one parser, no second hex parser ----

    @Test
    fun namedScriptBuildsThreeHexLines() {
        val script = buildNamedScript("Read the first pages")
        assertEquals("30 00\n30 04\n30 08", script)
        // The single shared parser must accept what the builder built.
        assertEquals(3, parseHexScript(script!!).size)
    }

    @Test
    fun namedScriptTechMatchesItsBlocks() {
        assertEquals("NfcA", namedScriptTech("Read the first pages"))
        assertEquals("IsoDep", namedScriptTech("Check the stored data app"))
        assertNull(namedScriptTech("No such script"))
        assertNull(buildNamedScript("No such script"))
    }

    @Test
    fun namedCommandsResolveToHex() {
        assertEquals("30 00", namedCommandHex("Read page 0"))
        assertEquals("NfcA", namedCommandTech("Read page 0"))
        assertEquals(
            "00 A4 04 00 07 D2 76 00 00 85 01 01 00",
            namedCommandHex("Pick stored data")
        )
        assertNull(namedCommandHex("No such command"))
    }

    @Test
    fun everyNamedBlockParsesThroughTheOneParser() {
        NAMED_NFC_COMMANDS.forEach { block ->
            assertEquals(1, parseHexScript(block.hex).size)
        }
        NAMED_NFC_SCRIPTS.forEach { script ->
            val built = buildNamedScript(script.name)!!
            assertEquals(script.blocks.size, parseHexScript(built).size)
        }
    }

    // ---- Panel gating (D-35 / F-32) ----

    @Test
    fun noCardBlocksCardPanelsButNotReadOrPretend() {
        assertTrue(gateForPanel(1, null, false).usable)
        assertFalse(gateForPanel(2, null, false).usable)
        assertFalse(gateForPanel(3, null, false).usable)
        assertFalse(gateForPanel(5, null, false).usable)
        assertFalse(gateForPanel(6, null, false).usable)
        assertTrue(gateForPanel(7, null, false).usable)
        assertFalse(gateForPanel(8, null, false).usable)
        assertFalse(gateForPanel(9, null, false).usable)
        assertFalse(gateForPanel(10, null, false).usable)
    }

    @Test
    fun readOnlyCardBlocksWriteEraseButNotRead() {
        val f = facts(writable = false)
        assertTrue(gateForPanel(1, f, false).usable)
        assertFalse(gateForPanel(3, f, false).usable)
        assertFalse(gateForPanel(4, f, false).usable)
        assertFalse(gateForPanel(6, f, false).usable)
    }

    @Test
    fun clonePanelNeedsSavedCopyFirst() {
        val f = facts()
        assertFalse(gateForPanel(5, f, false).usable)
        assertTrue(gateForPanel(5, f, true).usable)
    }

    @Test
    fun mifarePanelNeedsClassicAndMemoryPanelNeedsUltralight() {
        val classic = facts(
            classicSectors = 16,
            techs = listOf("android.nfc.tech.MifareClassic")
        )
        assertTrue(gateForPanel(9, classic, false).usable)
        assertFalse(gateForPanel(10, classic, false).usable)
        val ultra = facts(
            ultralightType = "Ultralight (64 bytes)",
            techs = listOf("android.nfc.tech.MifareUltralight")
        )
        assertFalse(gateForPanel(9, ultra, false).usable)
        assertTrue(gateForPanel(10, ultra, false).usable)
    }

    // ---- Capacity check (D-32's ndefMaxSize) ----

    @Test
    fun shortWordsFitLongWordsDoNot() {
        val f = facts(maxSize = 20)
        assertNull(ndefFitsOnCard(f, "hi", false))
        val tooBig = ndefFitsOnCard(f, "these words are far too long for this card", false)
        assertTrue(tooBig!!.startsWith("Too big for this card"))
        assertTrue(tooBig.contains("20"))
    }

    @Test
    fun unknownCapacityLetsTheWriteDecide() {
        assertNull(ndefFitsOnCard(facts(maxSize = null), "anything at all", false))
        assertNull(ndefFitsOnCard(null, "anything at all", false))
    }

    @Test
    fun urlSizeCountsTheAddedHttpsPrefix() {
        // "example.com" (11) + "https://" (8) + 1 prefix + 4 header = 24.
        assertEquals(24, ndefMessageSizeBytes("example.com", true))
        // "hi" text: 2 + 3 status/language + 4 header = 9.
        assertEquals(9, ndefMessageSizeBytes("hi", false))
    }

    // ---- What to try next (A-22 pattern, shared) ----

    @Test
    fun nextCheckSkipsTriedCommands() {
        assertEquals(
            "Pick stored data",
            nextApduCheck(emptySet())!!.first
        )
        val tried = setOf("00A4040007D276000085010100")
        assertEquals("Read data", nextApduCheck(tried)!!.first)
        val allTried = APDU_QUICK_CHECKS.map { it.second.replace(" ", "") }.toSet()
        assertNull(nextApduCheck(allTried))
    }

    // ---- HCE content from a read card (B-043 / D-40) ----

    @Test
    fun emulatableContentComesFromTheSummary() {
        assertEquals(
            "text" to "hello",
            emulatableFromSummary("Record 1: Text: \"hello\"")
        )
        assertEquals(
            "url" to "https://example.com",
            emulatableFromSummary("Record 1: URL: https://example.com")
        )
        assertNull(emulatableFromSummary("No saved message on it."))
    }

    // ---- Safe pages (B-036 / D-48) ----

    @Test
    fun idPagesWarnAndLaterPagesDoNot() {
        assertTrue(ultralightPageWarning(0)!!.contains("Pages 4 and up"))
        assertTrue(ultralightPageWarning(3)!!.contains("Page 3"))
        assertNull(ultralightPageWarning(4))
        assertNull(ultralightPageWarning(100))
    }

    // ---- Unknown card next step (D-33) ----

    @Test
    fun unknownCardGetsANextStepKnownCardDoesNot() {
        assertTrue(unknownCardNextStep("Unknown NFC target")!!.contains("raw console"))
        assertNull(unknownCardNextStep("NDEF data tag"))
    }
}
