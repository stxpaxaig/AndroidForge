package com.ghosttap.core.ops

import com.ghosttap.core.OpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the verification + retry helpers. Pure Kotlin, JUnit
 * 4 — no Android framework, no ViewModels. The live-connection checks
 * are tested only for their no-ViewModel branch here: with an empty
 * context they must report honestly that the check cannot run, never
 * claim a link is up.
 */
class VerificationTest {

    // ---- verifyContent ----

    @Test
    fun blankObservedIsNotVerified() {
        val result = verifyContent("reply", "")
        assertTrue(result is VerificationOutcome.NotVerified)
        assertEquals(
            "Nothing came back to check.",
            (result as VerificationOutcome.NotVerified).reason
        )
    }

    @Test
    fun expectedEvidencePresentVerifies() {
        val result = verifyContent("reply", "Ping done: reply in 12 ms")
        assertTrue(result is VerificationOutcome.Verified)
        assertTrue((result as VerificationOutcome.Verified).evidence.contains("reply"))
    }

    @Test
    fun expectedMatchIsCaseInsensitive() {
        val result = verifyContent("REPLY", "ping done: reply in 12 ms")
        assertTrue(result is VerificationOutcome.Verified)
    }

    @Test
    fun expectedEvidenceAbsentIsNotVerified() {
        val result = verifyContent("reply", "Ping done: no answer")
        assertTrue(result is VerificationOutcome.NotVerified)
        assertTrue(
            (result as VerificationOutcome.NotVerified).reason.contains("reply")
        )
    }

    @Test
    fun didNothingMarkerIsNotVerified() {
        val result = verifyContent("", "nothing to do")
        assertTrue(result is VerificationOutcome.NotVerified)
        assertEquals(
            "It ran, but nothing actually happened.",
            (result as VerificationOutcome.NotVerified).reason
        )
    }

    @Test
    fun blankExpectedWithSubstantiveResponseVerifies() {
        val result = verifyContent("", "Connected — 3 feature groups found")
        assertTrue(result is VerificationOutcome.Verified)
        assertEquals(
            "Connected — 3 feature groups found",
            (result as VerificationOutcome.Verified).evidence
        )
    }

    // ---- defaultVerify ----

    @Test
    fun failedOpResultIsNotVerified() {
        val result = defaultVerify(OpResult(false, "The device didn't answer."))
        assertTrue(result is VerificationOutcome.NotVerified)
        assertEquals(
            "The device didn't answer.",
            (result as VerificationOutcome.NotVerified).reason
        )
    }

    @Test
    fun okButEmptyOpResultIsNotVerified() {
        val result = defaultVerify(OpResult(true, "", ""))
        assertTrue(result is VerificationOutcome.NotVerified)
    }

    @Test
    fun okWithResponseVerifies() {
        val result = defaultVerify(OpResult(true, "did it", "real evidence"))
        assertTrue(result is VerificationOutcome.Verified)
    }

    // ---- verifyStillConnected (empty context: honest "cannot check") ----

    @Test
    fun stillConnectedWithoutViewModelsIsNotVerified() {
        val context = OperationContext()
        listOf(
            TransportKind.BLE,
            TransportKind.SPP,
            TransportKind.USB,
            TransportKind.NETWORK,
            TransportKind.NFC
        ).forEach { kind ->
            val result = verifyStillConnected(kind, "some-target", context)
            assertTrue("kind $kind must not verify without a ViewModel", result is VerificationOutcome.NotVerified)
            assertTrue(
                (result as VerificationOutcome.NotVerified).reason.contains("isn't ready yet")
            )
        }
    }

    // ---- retryBounded ----

    @Test
    fun retryBoundedStopsAtMaxAttempts() = runBlocking {
        var calls = 0
        val result = retryBounded<String>(maxAttempts = 3, delayMs = 1L) {
            calls++
            AttemptResult.Fail("still failing")
        }
        assertEquals(3, calls)
        assertTrue(result is AttemptResult.Fail)
        assertEquals("still failing", (result as AttemptResult.Fail).reason)
    }

    @Test
    fun retryBoundedReturnsFirstSuccess() = runBlocking {
        var calls = 0
        val result = retryBounded<String>(maxAttempts = 3, delayMs = 1L) {
            calls++
            if (calls < 2) AttemptResult.Fail("first try failed") else AttemptResult.Ok("done")
        }
        assertEquals(2, calls)
        assertTrue(result is AttemptResult.Ok)
        assertEquals("done", (result as AttemptResult.Ok).value)
    }

    @Test
    fun retryBoundedCallsOnRetryWithReason() = runBlocking {
        val retries = mutableListOf<Pair<Int, String>>()
        retryBounded<String>(
            maxAttempts = 3,
            delayMs = 1L,
            onRetry = { attempt, reason -> retries.add(attempt to reason) }
        ) { attempt ->
            if (attempt < 3) AttemptResult.Fail("failed at $attempt") else AttemptResult.Ok("done")
        }
        assertEquals(listOf(2 to "failed at 1", 3 to "failed at 2"), retries)
    }

    @Test
    fun retryBoundedWithOneAttemptNeverRetries() = runBlocking {
        var calls = 0
        retryBounded<String>(maxAttempts = 1, delayMs = 1L) {
            calls++
            AttemptResult.Fail("nope")
        }
        assertEquals(1, calls)
    }

    @Test
    fun retryBoundedZeroAttemptsCoercesToOne() = runBlocking {
        var calls = 0
        retryBounded<String>(maxAttempts = 0, delayMs = 1L) {
            calls++
            AttemptResult.Fail("nope")
        }
        assertEquals(1, calls)
    }
}
