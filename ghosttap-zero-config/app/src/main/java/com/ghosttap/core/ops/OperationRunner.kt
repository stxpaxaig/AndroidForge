package com.ghosttap.core.ops

import com.ghosttap.core.OpResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Executes one [Operation]: preparation → execution → verification, in
 * that order, on the caller's coroutine scope.
 *
 * - Preparation failure stops the run before anything executes:
 *   NeedsUserAction → [OperationResult.NeedsUserAction] with the exact
 *   plain-words prompt (and fix-it button, when the check supplies
 *   one); NeedsUserChoice → [OperationResult.NeedsUserChoice];
 *   Failed → [OperationResult.Failed] with the reason.
 * - Execution failure (exception, or ok=false) retries per the
 *   operation's bounded [RetryPolicy], reporting the plain-words
 *   reason for each retry, then returns [OperationResult.Failed] with
 *   the exact reason.
 * - Execution success is verified against what was actually observed.
 *   Verification failure → [OperationResult.Failed] with the reason.
 *   DONE is returned only with the verification evidence.
 *
 * The runner never silently swallows a failure: every exception in
 * preparation, execution or verification is converted into a Failed
 * (or NeedsUserAction) result carrying the reason. Progress — the
 * current step label and fraction — is exposed as [progress] for the
 * UI to observe; the final outcome is exposed as [result].
 */
class OperationRunner(private val context: OperationContext) {

    /** The context this runner executes against (exposed so the entry screen can set a pending choice). */
    val operationContext: OperationContext get() = context

    private val _progress = MutableStateFlow(
        OperationProgress("", OperationStep("Ready", 0f), false)
    )

    /** Current step label + fraction. The UI collects this while a run is in progress. */
    val progress: StateFlow<OperationProgress> = _progress

    private val _result = MutableStateFlow<OperationResult?>(null)

    /** The outcome of the last run, or null while nothing has run yet. */
    val result: StateFlow<OperationResult?> = _result

    /** Clears the last result (the entry screen calls this when starting a new run). */
    fun clearResult() {
        _result.value = null
    }

    /** Runs one operation to completion and returns its outcome. Never throws, except that coroutine cancellation always propagates. */
    suspend fun run(operation: Operation): OperationResult {
        _result.value = null
        emit(operation.id, "Getting ready…", 0f)
        val totalSteps = operation.preparation.size + 2

        // ---- Preparation: every check must pass before anything executes. ----
        operation.preparation.forEachIndexed { index, step ->
            emit(
                operation.id,
                step.label,
                stepFraction(index, totalSteps)
            )
            val outcome = try {
                step.check(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return finish(
                    operation,
                    OperationResult.Failed(
                        reason = "Something went wrong while getting ready: " +
                            (e.message ?: "unknown problem")
                    )
                )
            }
            when (outcome) {
                is PreparationOutcome.Ready -> {
                    // Continue to the next check.
                }
                is PreparationOutcome.NeedsUserAction -> {
                    return finish(
                        operation,
                        OperationResult.NeedsUserAction(
                            reason = outcome.prompt,
                            prompt = outcome.prompt,
                            actionLabel = outcome.actionLabel,
                            action = outcome.action
                        )
                    )
                }
                is PreparationOutcome.NeedsUserChoice -> {
                    return finish(
                        operation,
                        OperationResult.NeedsUserChoice(
                            reason = outcome.prompt,
                            choices = outcome.choices
                        )
                    )
                }
                is PreparationOutcome.Failed -> {
                    return finish(
                        operation,
                        OperationResult.Failed(reason = outcome.reason)
                    )
                }
            }
        }

        // ---- Execution: bounded retry, with the plain-words reason each time. ----
        emit(operation.id, "Doing it…", stepFraction(operation.preparation.size, totalSteps))
        val executed: AttemptResult<OpResult> = try {
            retryBounded(
                maxAttempts = operation.retry.maxAttempts,
                delayMs = operation.retry.delayMs,
                onRetry = { attempt, reason ->
                    emit(
                        operation.id,
                        "That didn't work ($reason) — trying again, try $attempt of ${operation.retry.maxAttempts}.",
                        stepFraction(operation.preparation.size, totalSteps)
                    )
                }
            ) { _ ->
                try {
                    val execResult = operation.execute(context)
                    if (execResult.ok) {
                        AttemptResult.Ok(execResult)
                    } else {
                        AttemptResult.Fail(
                            execResult.summary.ifBlank { "It didn't work." }
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AttemptResult.Fail(
                        "Something went wrong: ${e.message ?: "unknown problem"}"
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return finish(
                operation,
                OperationResult.Failed(
                    reason = "Something went wrong while doing it: " +
                        (e.message ?: "unknown problem")
                )
            )
        }
        val execResult = when (executed) {
            is AttemptResult.Ok -> executed.value
            is AttemptResult.Fail -> {
                return finish(
                    operation,
                    OperationResult.Failed(reason = executed.reason)
                )
            }
        }

        // ---- Verification: DONE only with observed evidence. ----
        emit(operation.id, "Checking it worked…", stepFraction(operation.preparation.size + 1, totalSteps))
        val verified = try {
            operation.verify(context, execResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return finish(
                operation,
                OperationResult.Failed(
                    reason = "Something went wrong while checking: " +
                        (e.message ?: "unknown problem"),
                    evidence = execResult.summary
                )
            )
        }
        return when (verified) {
            is VerificationOutcome.Verified -> {
                finish(
                    operation,
                    OperationResult.Done(
                        reason = "Done: ${operation.title}",
                        evidence = verified.evidence
                    )
                )
            }
            is VerificationOutcome.NotVerified -> {
                finish(
                    operation,
                    OperationResult.Failed(
                        reason = verified.reason,
                        evidence = execResult.summary
                    )
                )
            }
        }
    }

    /** Fraction at the START of step [index] of [totalSteps] steps (preparation, then execution, then verification). */
    private fun stepFraction(index: Int, totalSteps: Int): Float =
        (index.toFloat() / totalSteps.toFloat()).coerceIn(0f, 1f)

    private fun emit(operationId: String, label: String, fraction: Float) {
        _progress.value = OperationProgress(operationId, OperationStep(label, fraction), true)
    }

    private fun finish(operation: Operation, outcome: OperationResult): OperationResult {
        _progress.value = OperationProgress(operation.id, OperationStep("Done", 1f), false)
        _result.value = outcome
        return outcome
    }
}
