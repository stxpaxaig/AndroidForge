package com.ghosttap.core.ops

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ghosttap.NetworkToolsViewModel

/**
 * Shared preparation library (Package 0c).
 *
 * Every function returns a sealed outcome — nothing here throws, and
 * nothing here silently decides the user is ready when they are not.
 * Each function reuses the REAL mechanism the app already has:
 * MainActivity's permission launcher (via [OperationContext.requestPermissions]),
 * [com.ghosttap.GhostTapViewModel.lastNfcTag] for tag presence,
 * [com.ghosttap.GhostTapViewModel.gattStatusText] /
 * [com.ghosttap.GhostTapViewModel.isSppConnected] /
 * [com.ghosttap.UsbUartViewModel.isConnected] for live links,
 * [com.ghosttap.UsbUartViewModel.requestPermission] for USB permission,
 * and [NetworkToolsViewModel.consumePingPrefill] /
 * [NetworkToolsViewModel.consumeTcpPrefill] for prefills.
 * No new permission or connection flow is invented here.
 */

/** The outcome of one preparation check. */
sealed class PreparationOutcome {
    /** The check passed — continue. */
    object Ready : PreparationOutcome()

    /**
     * The operation cannot run until the user does something only they
     * can do. [prompt] is the plain-words instruction. When
     * [actionLabel] is set, the UI shows a button with that label that
     * runs [action] (the fix itself, e.g. asking the system to turn
     * Bluetooth on).
     */
    data class NeedsUserAction(
        val prompt: String,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null
    ) : PreparationOutcome()

    /**
     * The operation cannot continue until the user picks one of
     * [choices]. The picked choice is written to
     * [OperationContext.pendingChoice] and the operation runs again.
     */
    data class NeedsUserChoice(
        val prompt: String,
        val choices: List<String>
    ) : PreparationOutcome()

    /** Preparation itself failed. [reason] says why, in plain words. */
    data class Failed(val reason: String) : PreparationOutcome()
}

/** The permissions an operation needs, by default: the same Bluetooth / location set MainActivity asks for at startup. */
fun defaultOperationPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
}

/**
 * Permissions must be granted, then the operation runs again.
 *
 * When a permission is missing, this asks the system for it through
 * MainActivity's permission launcher — the grant lands in
 * [com.ghosttap.GhostTapViewModel.onPermissionsResult] — and returns
 * NeedsUserAction telling the user to tap the operation again once
 * they have answered. The runner cannot wait on a system dialog, so
 * the re-run is one honest tap, not a silent assumption.
 */
fun requestPermissionsAndRerun(
    context: OperationContext,
    permissions: List<String> = defaultOperationPermissions()
): PreparationOutcome {
    val app = context.ble?.getApplication<Application>()
        ?: return PreparationOutcome.Failed(
            "Can't check permissions — the app isn't ready yet."
        )
    val missing = permissions.filter {
        ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED
    }
    if (missing.isEmpty()) return PreparationOutcome.Ready
    context.requestPermissions()
    return PreparationOutcome.NeedsUserAction(
        prompt = "Ghost Tap needs permission to use Bluetooth and find nearby devices. " +
            "Give permission when asked, then tap this again."
    )
}

/**
 * A tap card must be physically held against the phone right now.
 * Uses the last tag the app actually saw ([com.ghosttap.GhostTapViewModel.lastNfcTag]).
 */
fun ensureTagPresent(context: OperationContext): PreparationOutcome {
    val ble = context.ble
        ?: return PreparationOutcome.Failed(
            "Can't check for a card — the app isn't ready yet."
        )
    return if (ble.lastNfcTag != null) {
        PreparationOutcome.Ready
    } else {
        PreparationOutcome.NeedsUserAction(
            prompt = "Hold a card against the back of the phone first."
        )
    }
}

/**
 * The named link must be live right now, re-checked against the real
 * state — never trusted from an earlier result.
 *
 * - BLE: the GATT status must start with "CONNECTED" (same live check
 *   the bring-up banner uses).
 * - SPP: the text link socket must still be open.
 * - USB: the cable link must be connected.
 * - NETWORK: [targetId] is the host address; it must have answered in
 *   the last Wi-Fi scan.
 * - NFC: a tag must still be held to the phone ([targetId] unused).
 *
 * When the link is down, the outcome carries a fix-it button where
 * one exists (Connect for BLE / SPP).
 */
fun ensureConnected(
    kind: TransportKind,
    targetId: String,
    context: OperationContext
): PreparationOutcome {
    return when (kind) {
        TransportKind.BLE -> {
            val ble = context.ble
                ?: return PreparationOutcome.Failed(
                    "Can't check the connection — the app isn't ready yet."
                )
            if (ble.gattStatusText(targetId).startsWith("CONNECTED")) {
                PreparationOutcome.Ready
            } else {
                PreparationOutcome.NeedsUserAction(
                    prompt = "Connect to the device first.",
                    actionLabel = "Connect",
                    action = { ble.connectBle(targetId) }
                )
            }
        }
        TransportKind.SPP -> {
            val ble = context.ble
                ?: return PreparationOutcome.Failed(
                    "Can't check the connection — the app isn't ready yet."
                )
            if (ble.isSppConnected(targetId)) {
                PreparationOutcome.Ready
            } else {
                PreparationOutcome.NeedsUserAction(
                    prompt = "Connect with Try text link first.",
                    actionLabel = "Connect text link",
                    action = { ble.connectSpp(targetId) }
                )
            }
        }
        TransportKind.USB -> {
            val usb = context.usb
                ?: return PreparationOutcome.Failed(
                    "Can't check the connection — the app isn't ready yet."
                )
            if (usb.isConnected) {
                PreparationOutcome.Ready
            } else {
                PreparationOutcome.NeedsUserAction(
                    prompt = "Plug in the board and connect it first."
                )
            }
        }
        TransportKind.NETWORK -> {
            val net = context.net
                ?: return PreparationOutcome.Failed(
                    "Can't check the connection — the app isn't ready yet."
                )
            if (targetId.isNotBlank() && targetId in net.liveHosts) {
                PreparationOutcome.Ready
            } else {
                PreparationOutcome.NeedsUserAction(
                    prompt = "Find devices on your Wi-Fi first."
                )
            }
        }
        TransportKind.NFC -> ensureTagPresent(context)
    }
}

/**
 * A USB device must be picked and the system must have allowed access
 * to it. When permission is missing, the outcome carries an
 * "Allow USB access" button that asks the system — the same flow the
 * USB screen's CONNECT button uses ([com.ghosttap.UsbUartViewModel.requestPermission]).
 */
fun ensureUsbPermission(context: OperationContext): PreparationOutcome {
    val usb = context.usb
        ?: return PreparationOutcome.Failed(
            "Can't check USB — the app isn't ready yet."
        )
    if (usb.selectedDevice == null) {
        return PreparationOutcome.NeedsUserAction(
            prompt = "Pick a USB device first — plug in the board, then pick it from the list."
        )
    }
    return if (!usb.permissionNeeded) {
        PreparationOutcome.Ready
    } else {
        PreparationOutcome.NeedsUserAction(
            prompt = "Allow USB access first.",
            actionLabel = "Allow USB access",
            action = { usb.requestPermission() }
        )
    }
}

/** The outcome of consuming a pending prefill. */
sealed class PrefillOutcome {
    /** A prefill was waiting and has been taken: [host], with [port] when it is a TCP prefill (null for a ping prefill). */
    data class Carried(val host: String, val port: Int?) : PrefillOutcome()

    /** Nothing was waiting to be carried over. */
    object NothingPending : PrefillOutcome()
}

/**
 * Takes a pending address prefill and carries it into the operation:
 * a TCP prefill first (set when tapping an open port found by SCAN MY
 * NETWORK), then a ping prefill (set when Visual Intel spots an
 * address). Consuming clears it, exactly like the screens do — a
 * prefill is used once, never twice.
 */
fun consumePrefill(net: NetworkToolsViewModel): PrefillOutcome {
    net.consumeTcpPrefill()?.let { (host, port) ->
        return PrefillOutcome.Carried(host, port)
    }
    net.consumePingPrefill()?.let { host ->
        return PrefillOutcome.Carried(host, null)
    }
    return PrefillOutcome.NothingPending
}
