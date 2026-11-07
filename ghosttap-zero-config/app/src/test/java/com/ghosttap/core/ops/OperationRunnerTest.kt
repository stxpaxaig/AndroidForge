package com.ghosttap.core.ops

import com.ghosttap.core.OpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the operation runner. Pure Kotlin, JUnit 4 — no
 * Android framework, no ViewModels: every operation here runs against
 * an empty [OperationContext] and lambdas that never touch it.
 */
class OperationRunnerTest {

    private fun op(
        id: String = "test.pkg0.op",
        preparation: List<PreparationStep> = emptyList(),
        execute: suspend (OperationContext) -> OpResult =
            { OpResult(true, "did it", "real evidence") },
        verify: suspend (OperationContext, OpResult) -> VerificationOutcome =
            { _, result -> defaultVerify(result) },
        retry: RetryPolicy = RetryPolicy()
    ): Operation = Operation(
        id = id,
        title = "Test operation",
        description = "A test operation.",
        area = OperationArea.SYSTEM,
        preparation = preparation,
        execute = execute,
        verify = verify,
        retry = retry
    )

    private fun runner(): OperationRunner = OperationRunner(OperationContext())

    @Test
    fun happyPathReturnsDoneWithEvidence() = runBlocking {
        val r = runner()
        val result = r.run(op())
        assertTrue(result is OperationResult.Done)
        assertEquals("real evidence", (result as OperationResult.Done).evidence)
        assertTrue(result.reason.contains("Test operation"))
        assertEquals(1f, r.progress.value.step.fraction)
        assertFalse(r.progress.value.running)
        assertEquals(result, r.result.value)
    }

    @Test
    fun preparationNeedsUserActionStopsBeforeExecution() = runBlocking {
        var executed = 0
        val check = PreparationStep("Checking Bluetooth is on") {
            PreparationOutcome.NeedsUserAction(
                prompt = "Turn on Bluetooth first.",
                actionLabel = "Turn on Bluetooth",
                action = {}
            )
        }
        val result = runner().run(
            op(preparation = listOf(check), execute = { executed++; OpResult(true, "did it") })
        )
        assertTrue(result is OperationResult.NeedsUserAction)
        assertEquals("Turn on Bluetooth first.", (result as OperationResult.NeedsUserAction).prompt)
        assertEquals("Turn on Bluetooth", result.actionLabel)
        assertEquals(0, executed)
    }

    @Test
    fun preparationFailureReturnsFailedWithReason() = runBlocking {
        val check = PreparationStep("Checking something") {
            PreparationOutcome.Failed("The board isn't plugged in.")
        }
        val result = runner().run(op(preparation = listOf(check)))
        assertTrue(result is OperationResult.Failed)
        assertEquals("The board isn't plugged in.", (result as OperationResult.Failed).reason)
    }

    @Test
    fun preparationNeedsUserChoiceReturnsChoices() = runBlocking {
        val check = PreparationStep("Picking a device") {
            PreparationOutcome.NeedsUserChoice(
                prompt = "Pick which device to use.",
                choices = listOf("Device A", "Device B")
            )
        }
        val result = runner().run(op(preparation = listOf(check)))
        assertTrue(result is OperationResult.NeedsUserChoice)
        val choice = result as OperationResult.NeedsUserChoice
        assertEquals(listOf("Device A", "Device B"), choice.choices)
        assertEquals("Pick which device to use.", choice.reason)
    }

    @Test
    fun verificationFailureReturnsFailedWithReason() = runBlocking {
        val result = runner().run(
            op(verify = { _, _ -> VerificationOutcome.NotVerified("Nothing came back to check.") })
        )
        assertTrue(result is OperationResult.Failed)
        assertEquals(
            "Nothing came back to check.",
            (result as OperationResult.Failed).reason
        )
    }

    @Test
    fun doneOnlyWithEvidence() = runBlocking {
        // Execution reports ok, but with no response at all: the default
        // verification must NOT let that through as DONE.
        val result = runner().run(
            op(execute = { OpResult(true, "", "") })
        )
        assertTrue(result is OperationResult.Failed)
        assertEquals(
            "Nothing came back to check.",
            (result as OperationResult.Failed).reason
        )
    }

    @Test
    fun executionFailureRetriesUpToBoundThenFails() = runBlocking {
        var calls = 0
        val result = runner().run(
            op(
                retry = RetryPolicy(maxAttempts = 3, delayMs = 1L),
                execute = {
                    calls++
                    OpResult(false, "The device didn't answer.")
                }
            )
        )
        assertEquals(3, calls)
        assertTrue(result is OperationResult.Failed)
        assertEquals("The device didn't answer.", (result as OperationResult.Failed).reason)
    }

    @Test
    fun retrySucceedsOnSecondAttempt() = runBlocking {
        var calls = 0
        val result = runner().run(
            op(
                retry = RetryPolicy(maxAttempts = 3, delayMs = 1L),
                execute = {
                    calls++
                    if (calls < 2) OpResult(false, "First try failed.") else OpResult(true, "did it", "evidence")
                }
            )
        )
        assertEquals(2, calls)
        assertTrue(result is OperationResult.Done)
        assertEquals("evidence", (result as OperationResult.Done).evidence)
    }

    @Test
    fun executionExceptionRetriesThenFails() = runBlocking {
        var calls = 0
        val result = runner().run(
            op(
                retry = RetryPolicy(maxAttempts = 2, delayMs = 1L),
                execute = {
                    calls++
                    throw IllegalStateException("boom")
                }
            )
        )
        assertEquals(2, calls)
        assertTrue(result is OperationResult.Failed)
        assertTrue((result as OperationResult.Failed).reason.contains("boom"))
    }

    @Test
    fun singleAttemptPolicyNeverRetries() = runBlocking {
        var calls = 0
        runner().run(
            op(
                retry = RetryPolicy(maxAttempts = 1),
                execute = { calls++; OpResult(false, "nope") }
            )
        )
        assertEquals(1, calls)
    }

    @Test
    fun verificationExceptionReturnsFailed() = runBlocking {
        val result = runner().run(
            op(verify = { _, _ -> throw IllegalStateException("verify broke") })
        )
        assertTrue(result is OperationResult.Failed)
        assertTrue((result as OperationResult.Failed).reason.contains("verify broke"))
    }

    @Test
    fun preparationExceptionReturnsFailed() = runBlocking {
        val check = PreparationStep("Breaking check") {
            throw IllegalStateException("prep broke")
        }
        val result = runner().run(op(preparation = listOf(check)))
        assertTrue(result is OperationResult.Failed)
        assertTrue((result as OperationResult.Failed).reason.contains("prep broke"))
    }

    @Test
    fun clearResultEmptiesLastResult() = runBlocking {
        val r = runner()
        r.run(op())
        assertTrue(r.result.value is OperationResult.Done)
        r.clearResult()
        assertNull(r.result.value)
    }
}
