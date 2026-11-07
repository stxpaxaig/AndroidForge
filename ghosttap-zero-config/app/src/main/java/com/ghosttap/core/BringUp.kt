package com.ghosttap.core

import kotlinx.coroutines.delay

/**
 * Zero-tap bring-up: the user taps a target ONCE; Ghost Tap runs the whole
 * bring-up (connect → enumerate → handshake → map what's controllable) and
 * lands the user on the target's control screen. Then the USER drives.
 *
 * The handshake (C-11) is an internal step, not a new button or decision:
 * after connection and the honesty gates, each path sends a real command
 * the device is expected to answer — a read, never a write to an unknown
 * device — and requires an actual answer before READY is declared. A
 * device that doesn't answer is reported honestly, never called ready.
 * Where no safe probe exists (nothing readable, a silent non-ESP32
 * board), READY stands on the honesty gates and the summary says so
 * in plain words.
 *
 * The intelligence stays fully active under the hood — it keeps learning
 * from bring-up results, adapting step choice, and extending capabilities.
 * It just no longer dumps investigation reports as the destination; the
 * destination is the control screen.
 */

/** What kind of target is being brought to controllable state. */
enum class BringUpKind { BLE, NFC, NETWORK, USB }

/** User-visible bring-up progress. */
enum class BringUpState { IDLE, CONNECTING, ENUMERATING, READY, FAILED }

/** Bring-up phases reported back for the status line. */
enum class BringUpPhase { CONNECTING, ENUMERATING, HANDSHAKE }

/** One target to bring up — built from bus events. */
data class BringUpTarget(
    val kind: BringUpKind,
    /** BLE targetId, NFC tag key, host IP, or "usb". */
    val id: String,
    val title: String,
    val detail: String = ""
)

/**
 * Where READY lands + what's controllable, in plain words.
 * autoNavigate=false means: bring-up still runs and reports, but the user
 * isn't yanked to another screen (used for scan-discovered hosts the user
 * never tapped).
 */
data class ControlDescriptor(
    /** Existing nav route READY lands on. */
    val route: String,
    val autoNavigate: Boolean = true,
    /** Plain-English lines describing what's controllable. */
    val summaryLines: List<String>
)

data class BringUpResult(
    val ready: Boolean,
    val descriptor: ControlDescriptor?,
    /** Plain-English blocking reason when not ready. */
    val failReason: String?,
    val log: List<String>
)

/** Counts of controllable characteristics from a real GATT discovery. */
data class BleControlCounts(
    val services: Int,
    val read: Int,
    val write: Int,
    val notify: Int
)

/**
 * Ordered, zero-tap bring-up plans per target kind. Every step runs through
 * the existing [IntelEngine.run] — real implementations, never stubs.
 * Failures try the next viable step; an unrecoverable block returns FAILED
 * with the exact reason in plain words.
 */
object BringUpPipeline {

    suspend fun bringUp(
        target: BringUpTarget,
        engine: IntelEngine,
        /** Last recorded failure for this target, if any — influences messaging. */
        pastFailure: String? = null,
        phase: (BringUpPhase) -> Unit = {},
        note: (String) -> Unit = {}
    ): BringUpResult {
        val log = mutableListOf<String>()
        fun say(s: String) {
            log.add(s)
            runCatching { note(s) }
        }
        return try {
            when (target.kind) {
                BringUpKind.BLE -> bringUpBle(target, engine, pastFailure, phase, ::say, log)
                BringUpKind.NFC -> bringUpNfc(target, engine, phase, ::say, log)
                BringUpKind.NETWORK -> bringUpNetwork(target, engine, phase, ::say, log)
                BringUpKind.USB -> bringUpUsb(target, engine, phase, ::say, log)
            }
        } catch (e: Exception) {
            BringUpResult(
                ready = false,
                descriptor = null,
                failReason = "Getting it ready hit a problem: ${e.message ?: "unknown problem"}.",
                log = log
            )
        }
    }

    // ---------- BLE ----------

    private suspend fun bringUpBle(
        target: BringUpTarget,
        engine: IntelEngine,
        pastFailure: String?,
        phase: (BringUpPhase) -> Unit,
        say: (String) -> Unit,
        log: MutableList<String>
    ): BringUpResult {
        fun fail(reason: String): BringUpResult {
            say("FAILED: $reason")
            return BringUpResult(false, null, reason, log.toList())
        }
        phase(BringUpPhase.CONNECTING)
        say("Connecting to ${target.title}…")
        engine.run("ble.connect", mapOf("targetId" to target.id))

        // Wait for a real connection (connect auto-discovers services).
        val deadline = System.currentTimeMillis() + 20_000
        var gatt = engine.bleGattStatus(target.id)
        while (System.currentTimeMillis() < deadline) {
            gatt = engine.bleGattStatus(target.id)
            when {
                gatt.startsWith("CONNECTED") -> break
                gatt.contains("PERMISSION DENIED", ignoreCase = true) ->
                    return fail("Bluetooth permission was denied — tap Ask again on the home screen to allow it, then tap the target again.")
                gatt.contains("NO ADAPTER", ignoreCase = true) ->
                    return fail("This phone reports no Bluetooth adapter — can't connect.")
                gatt.contains("NO ANSWER", ignoreCase = true) ->
                    return fail(
                        "The device didn't answer." +
                            if (pastFailure?.contains("didn't answer") == true)
                                " Same as last time — this one probably refuses connections."
                            else " Many devices refuse connections — try moving closer or pick another one."
                    )
            }
            delay(500)
        }
        gatt = engine.bleGattStatus(target.id)
        if (!gatt.startsWith("CONNECTED")) {
            return fail(
                "Couldn't connect: ${gatt.ifBlank { "the device didn't answer" }.take(160)}"
            )
        }

        phase(BringUpPhase.ENUMERATING)
        say("Connected. Reading what it can do…")
        // Give service discovery a moment to populate.
        val d2 = System.currentTimeMillis() + 10_000
        var counts = engine.bleControlSummary(target.id)
        while (System.currentTimeMillis() < d2 &&
            counts.services == 0 &&
            engine.bleGattStatus(target.id).startsWith("CONNECTED — discovering")
        ) {
            delay(500)
            counts = engine.bleControlSummary(target.id)
        }
        val lines = mutableListOf(
            "Connected to ${target.title}.",
            "You can: read ${counts.read} values · send to ${counts.write} · get live updates from ${counts.notify}."
        )
        if (counts.services == 0) {
            lines.add("It has nothing readable or writable — nothing to control here.")
        }
        // Final honesty check: still connected RIGHT NOW, not just during the
        // poll above. A link that dropped mid-bring-up must FAIL, never READY.
        if (!engine.bleGattStatus(target.id).startsWith("CONNECTED")) {
            return fail("It connected, then the link dropped before setup finished — tap the target again.")
        }
        // Handshake (C-11): prove the device understands a command before
        // READY. A read is the safe probe — it asks for a value and changes
        // nothing. Up to 3 readable characteristics are asked at once; any
        // answer proves the device speaks. A read the device refuses for
        // pairing fails honestly (pairing is not part of setup); a read
        // that gets no answer fails honestly too. When nothing on the
        // device can be read there is no safe probe — READY then stands
        // on the honesty gates above, and the summary says so plainly.
        phase(BringUpPhase.HANDSHAKE)
        say("Checking it answers…")
        val probeChars = engine.bleFirstReadables(target.id)
        if (probeChars.isEmpty()) {
            lines.add("No readable value to check against — the link stayed up, so it's ready.")
            say("No readable value to check against — ready because the link held.")
        } else {
            val startedAt = System.currentTimeMillis()
            for ((svc, char) in probeChars) {
                engine.bleRead(target.id, svc, char)
                delay(150)
            }
            val wanted = probeChars.map { it.second }.toSet()
            var answered = false
            val readDeadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < readDeadline) {
                if (engine.bleReadAnswered(target.id, wanted, startedAt)) {
                    answered = true
                    break
                }
                val statusNow = engine.bleGattStatus(target.id)
                if (statusNow.contains("Needs pairing")) {
                    return fail("This device wants pairing first — pair it in the phone's Bluetooth settings, then tap it again.")
                }
                if (!statusNow.startsWith("CONNECTED")) {
                    return fail("It connected, then the link dropped before the check finished — tap the target again.")
                }
                delay(250)
            }
            if (!answered) {
                return fail("It connected, but it didn't answer the check — it may ignore reads. Try moving closer or pick another one.")
            }
            lines.add("It answered the check — it understands commands.")
            say("It answered the check.")
        }
        say("Ready: ${counts.read} you can read, ${counts.write} you can send to, ${counts.notify} live updates.")
        return BringUpResult(
            ready = true,
            descriptor = ControlDescriptor(
                route = "target/${target.id}",
                autoNavigate = true,
                summaryLines = lines
            ),
            failReason = null,
            log = log.toList()
        )
    }

    // ---------- NFC ----------

    private suspend fun bringUpNfc(
        target: BringUpTarget,
        engine: IntelEngine,
        phase: (BringUpPhase) -> Unit,
        say: (String) -> Unit,
        log: MutableList<String>
    ): BringUpResult {
        phase(BringUpPhase.ENUMERATING)
        // The card must still be there: a tap that already left the
        // phone must fail, never report ready.
        if (!engine.nfcTagPresent()) {
            val reason = "The card isn't there any more — hold it against the phone again."
            say("FAILED: $reason")
            return BringUpResult(false, null, reason, log.toList())
        }
        val techs = target.detail.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        say("Card in hand (${if (techs.isNotEmpty()) techs.joinToString(", ") else "unknown type"}). Reading what it really is…")
        // Handshake (C-11): the tap itself was the card answering real NFC
        // commands, and this read must return real tag content — a card
        // that doesn't answer the read can't be called ready. The read op
        // returns ok unconditionally, so content — not ok — is the gate.
        phase(BringUpPhase.HANDSHAKE)
        val summary = engine.run("nfc.read_summary", emptyMap())
        if (!nfcSummaryUsable(summary.summary)) {
            val reason = "The card didn't answer the read — hold it still against the phone and try again."
            say("FAILED: $reason")
            return BringUpResult(false, null, reason, log.toList())
        }
        val tagLine = summary.summary.lineSequence().take(3).joinToString(" ").take(160)
        say("Card says: $tagLine — mapping what it can do…")
        val lines = mutableListOf<String>()
        lines.add("Card ready${if (techs.isNotEmpty()) " — type: ${techs.joinToString(", ")}" else ""}.")
        lines.add("It reported: $tagLine")
        lines.add("What you can control: read the card's memory and ID · send direct commands to the card.")
        if (techs.any { it.contains("Ndef", ignoreCase = true) }) {
            lines.add("It carries a saved message — you can read it and write a new one.")
        }
        if (techs.any { it.contains("IsoDep", ignoreCase = true) }) {
            lines.add("It's a smart card — direct card commands are available.")
        }
        if (techs.any { it.contains("Mifare", ignoreCase = true) }) {
            lines.add("Door card found — the door-card tools apply.")
        }
        say("Ready: tap-card tools mapped.")
        return BringUpResult(
            ready = true,
            descriptor = ControlDescriptor(
                route = "nfc_tools",
                autoNavigate = true,
                summaryLines = lines
            ),
            failReason = null,
            log = log.toList()
        )
    }

    // ---------- NETWORK ----------

    private suspend fun bringUpNetwork(
        target: BringUpTarget,
        engine: IntelEngine,
        phase: (BringUpPhase) -> Unit,
        say: (String) -> Unit,
        log: MutableList<String>
    ): BringUpResult {
        // Handshake (C-11): the ping IS the probe — a real command (echo
        // request) the host must answer. A host that doesn't answer can't
        // be called ready. No payload is sent to an unknown service: the
        // "hello" send is deliberately not used as a probe, and the port
        // scan below only ever opens connections to see which answer.
        phase(BringUpPhase.HANDSHAKE)
        say("Checking ${target.id} answers…")
        val ping = engine.run("net.ping", mapOf("host" to target.id))
        // A ping that got no answer blocks READY. "answered in" is the
        // only success evidence in a ping reply — "didn't answer" and
        // "couldn't find" never contain it — so this checks for the
        // presence of a real answer, never for the absence of an error.
        if (!pingAnswered(ping.summary)) {
            val reason = "The device didn't answer the check — it may be off, or it may block this kind of check."
            say("FAILED: $reason")
            return BringUpResult(false, null, reason, log.toList())
        }
        say("It answered the check: ${ping.summary.take(120)}")
        phase(BringUpPhase.ENUMERATING)
        say("Checking its connection points…")
        val scan = engine.run("net.port_scan", mapOf("host" to target.id))
        val lines = mutableListOf(
            "Device ${target.title}: ${ping.summary.take(120)}",
            "Connection check: ${scan.summary.take(160)}",
            "Open NETWORK TOOLS to work on this device — check if it answers, connect and send, fetch pages, and check connection points are all there."
        )
        say("Ready: device checked.")
        return BringUpResult(
            ready = true,
            // Scan-discovered hosts weren't tapped by the user — report, don't yank screens.
            descriptor = ControlDescriptor(
                route = "network_tools",
                autoNavigate = false,
                summaryLines = lines
            ),
            failReason = null,
            log = log.toList()
        )
    }

    // ---------- USB ----------

    private suspend fun bringUpUsb(
        target: BringUpTarget,
        engine: IntelEngine,
        phase: (BringUpPhase) -> Unit,
        say: (String) -> Unit,
        log: MutableList<String>
    ): BringUpResult {
        phase(BringUpPhase.CONNECTING)
        say("Opening the USB cable link…")
        val conn = engine.run("usb.connect", emptyMap())
        if (!conn.ok && conn.summary.contains("permission", ignoreCase = true)) {
            val reason = "USB permission was denied — approve the USB prompt and try again."
            say("FAILED: $reason")
            return BringUpResult(false, null, reason, log.toList())
        }
        phase(BringUpPhase.ENUMERATING)
        say("Link: ${conn.summary.take(120)} — reading what it can do…")
        val status = engine.run("usb.status", emptyMap())
        // A connect that didn't actually connect blocks READY — for
        // any reason, not just a denied permission. usb.status
        // reports the live state as "connected=true/false", so this
        // re-checks the real link instead of trusting the connect
        // call's own words.
        if (!usbConnected(status.summary)) {
            val reason = "The USB cable didn't connect — check the cable and that the board is plugged in, then try again."
            say("FAILED: $reason")
            return BringUpResult(false, null, reason, log.toList())
        }
        // Handshake (C-11): there is no universal "are you there" for a
        // USB serial device, so the probe is two safe steps, in order:
        // 1. Listen — a board that talks on its own proves it answers,
        //    and listening sends nothing.
        // 2. ESP32 sync — the app's own board test; only an ESP32 in
        //    download mode answers it, and other boards ignore the bytes.
        // A silent, non-ESP32 board has no safe probe: the link held
        // through connect + status, so READY stands on the honesty gates
        // — said so plainly in the summary, never hidden.
        phase(BringUpPhase.HANDSHAKE)
        say("Checking the board answers…")
        val rxBefore = engine.usbRxLength()
        delay(2000)
        var handshakeEvidence: String? = null
        if (engine.usbRxLength() > rxBefore) {
            handshakeEvidence = "The board talked first — it answers over the cable."
        } else {
            engine.run("usb.test_esp32", emptyMap())
            // The board test runs async: poll the status until it leaves
            // "Checking…" (the sync itself takes up to ~7s).
            var espStatus = engine.usbEsp32Status()
            val espDeadline = System.currentTimeMillis() + 8_000
            while (espStatus == "Checking…" && System.currentTimeMillis() < espDeadline) {
                delay(250)
                espStatus = engine.usbEsp32Status()
            }
            if (esp32Answered(espStatus)) {
                handshakeEvidence = "The board answered the check — it's an ESP32, ready to load code."
            }
        }
        val lines = mutableListOf(
            "USB cable: ${status.summary.take(160)}",
            handshakeEvidence ?: "No safe check for this board — the cable link stayed up, so it's ready.",
            "What you can control: send text over the message box · run the board link test and software loader."
        )
        say(handshakeEvidence ?: "No safe check for this board — ready because the cable link stayed up.")
        say("Ready: USB cable open.")
        return BringUpResult(
            ready = true,
            descriptor = ControlDescriptor(
                route = "usb_uart",
                autoNavigate = true,
                summaryLines = lines
            ),
            failReason = null,
            log = log.toList()
        )
    }
}
