package com.ghosttap.core.ops

import com.ghosttap.core.OpResult
import kotlinx.coroutines.delay

/**
 * Shared verification + retry library (Package 0d).
 *
 * Honest patterns reused from [com.ghosttap.core.vnic.VnicVerify]:
 * an ok=false result is a failure; an ok=true with an empty or
 * "did nothing" response proves nothing; only a substantive response
 * that matches the expected outcome counts as verified. What is NOT
 * copied here is the substring-matching error-sentence approach used
 * by the NFC Tools screen (`looksLikeNfcFailure`): success is never
 * judged by matching error words in a message. Verification checks
 * for the PRESENCE of expected evidence in a live re-check or a real
 * response — never for the absence of an error sentence.
 *
 * Nothing here throws. Every function returns a sealed outcome.
 */

/** The outcome of one verification check. */
sealed class VerificationOutcome {
    /** The check passed. [evidence] is what was actually observed. */
    data class Verified(val evidence: String) : VerificationOutcome()

    /** The check did not pass. [reason] says why, in plain words. */
    data class NotVerified(val reason: String) : VerificationOutcome()
}

/**
 * Default verification for an operation's execution result: ok=false
 * fails with the operation's own summary; ok=true with nothing to
 * look at fails ("nothing came back to check"); anything else with a
 * real response verifies with that response as the evidence.
 */
fun defaultVerify(result: OpResult): VerificationOutcome {
    if (!result.ok) {
        return VerificationOutcome.NotVerified(
            result.summary.ifBlank { "It didn't work." }
        )
    }
    val observed = (result.summary + " " + result.data).trim()
    return verifyContent(expected = "", observed = observed)
}

/**
 * Content verification: does [observed] actually contain the [expected]
 * evidence?
 *
 * - Blank observed → NotVerified ("nothing came back to check").
 * - Observed that only says nothing happened (a "did nothing" marker)
 *   → NotVerified, even when the caller reported ok.
 * - [expected] blank → any substantive observed response verifies.
 * - [expected] present in observed (case-insensitive) → Verified with
 *   the observed text as evidence.
 * - [expected] absent → NotVerified, quoting what actually came back.
 *
 * This checks for expected evidence, never for error sentences.
 */
fun verifyContent(expected: String, observed: String): VerificationOutcome {
    val content = observed.trim()
    if (content.isBlank()) {
        return VerificationOutcome.NotVerified("Nothing came back to check.")
    }
    val lower = content.lowercase()
    if (NOOP_MARKERS.any { it in lower }) {
        return VerificationOutcome.NotVerified(
            "It ran, but nothing actually happened."
        )
    }
    if (expected.isBlank()) {
        return VerificationOutcome.Verified(content.take(300))
    }
    return if (expected.lowercase() in lower) {
        VerificationOutcome.Verified(content.take(300))
    } else {
        VerificationOutcome.NotVerified(
            "Expected to see \"$expected\" but got: ${content.take(200)}"
        )
    }
}

/** Phrases that mean "nothing actually happened" even when ok=true. */
private val NOOP_MARKERS = listOf(
    "manual step", "not implemented", "no-op", "placeholder",
    "not yet", "skipped", "nothing to do", "open the"
)

/**
 * Live still-connected re-check for every transport kind — not just
 * BLE. Each branch re-reads the REAL live state right now (GATT
 * status, SPP socket, USB link, last Wi-Fi scan, last NFC tag); it
 * never trusts a one-shot result from earlier.
 *
 * For NETWORK, [targetId] is the host address and the check is
 * whether the last Wi-Fi scan found it answering. For NFC, [targetId]
 * is unused — the check is whether a tag is still held to the phone.
 */
fun verifyStillConnected(
    kind: TransportKind,
    targetId: String,
    context: OperationContext
): VerificationOutcome {
    return when (kind) {
        TransportKind.BLE -> {
            val ble = context.ble
                ?: return VerificationOutcome.NotVerified(
                    "Can't check the connection — the app isn't ready yet."
                )
            val status = ble.gattStatusText(targetId)
            if (status.startsWith("CONNECTED")) {
                VerificationOutcome.Verified("Still connected: $status")
            } else {
                VerificationOutcome.NotVerified(
                    "The connection dropped — the device is no longer connected."
                )
            }
        }
        TransportKind.SPP -> {
            val ble = context.ble
                ?: return VerificationOutcome.NotVerified(
                    "Can't check the connection — the app isn't ready yet."
                )
            if (ble.isSppConnected(targetId)) {
                VerificationOutcome.Verified("The text link is still open.")
            } else {
                VerificationOutcome.NotVerified("The text link closed.")
            }
        }
        TransportKind.USB -> {
            val usb = context.usb
                ?: return VerificationOutcome.NotVerified(
                    "Can't check the connection — the app isn't ready yet."
                )
            if (usb.isConnected) {
                VerificationOutcome.Verified("The USB cable is still connected.")
            } else {
                VerificationOutcome.NotVerified("The USB cable disconnected.")
            }
        }
        TransportKind.NETWORK -> {
            val net = context.net
                ?: return VerificationOutcome.NotVerified(
                    "Can't check the connection — the app isn't ready yet."
                )
            if (targetId.isNotBlank() && targetId in net.liveHosts) {
                VerificationOutcome.Verified("$targetId answered on your Wi-Fi.")
            } else {
                VerificationOutcome.NotVerified(
                    "$targetId didn't answer on your Wi-Fi any more."
                )
            }
        }
        TransportKind.NFC -> {
            val ble = context.ble
                ?: return VerificationOutcome.NotVerified(
                    "Can't check the card — the app isn't ready yet."
                )
            if (ble.lastNfcTag != null) {
                VerificationOutcome.Verified("The card is still there.")
            } else {
                VerificationOutcome.NotVerified(
                    "The card isn't there any more — hold it against the phone again."
                )
            }
        }
    }
}

/**
 * One attempt of a bounded retry. [Ok] carries the successful value;
 * [Fail] carries the plain-words reason that attempt failed.
 */
sealed class AttemptResult<out T> {
    data class Ok<T>(val value: T) : AttemptResult<T>()
    data class Fail(val reason: String) : AttemptResult<Nothing>()
}

/**
 * Runs [block] until it returns [AttemptResult.Ok] or [maxAttempts]
 * total tries are used up — never more. [block] receives the attempt
 * number (1-based). [onRetry] is called before each retry with the
 * number of the attempt about to run and the plain-words reason the
 * previous attempt failed. Returns the successful result, or the last
 * failure when the bound is reached.
 */
suspend fun <T> retryBounded(
    maxAttempts: Int,
    delayMs: Long = 1000L,
    onRetry: ((attempt: Int, reason: String) -> Unit)? = null,
    block: suspend (attempt: Int) -> AttemptResult<T>
): AttemptResult<T> {
    val attempts = maxAttempts.coerceAtLeast(1)
    var lastFailure: AttemptResult.Fail? = null
    for (attempt in 1..attempts) {
        when (val result = block(attempt)) {
            is AttemptResult.Ok -> return result
            is AttemptResult.Fail -> {
                lastFailure = result
                if (attempt < attempts) {
                    onRetry?.invoke(attempt + 1, result.reason)
                    delay(delayMs)
                }
            }
        }
    }
    return lastFailure ?: AttemptResult.Fail("It didn't work.")
}
