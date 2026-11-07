package com.ghosttap.core

/**
 * # Capability integration contract
 *
 * This file is documentation. The contract itself lives in
 * [CapabilityRegistry.register].
 *
 * ## How a new Ghost Tap tool joins the intelligence system
 *
 * 1. Find the tool's real entry point — the actual ViewModel function users
 *    tap today (e.g. `NetworkToolsViewModel.ping(host)`). Never wrap a stub.
 * 2. In [IntelEngine]'s init block, next to the other `reg(...)` calls, add
 *    ONE registration:
 *
 * ```
 * reg(
 *     id = "mytab.cool_probe",          // unique, lowercase, dotted
 *     title = "Probe the cool thing",   // short human label
 *     detail = "Reads the cool thing over the existing link.", // plain English
 *     category = "MYTAB",               // grouping for the UI
 *     riskLevel = "READ",               // READ | CONFIGURE | WRITE | CONTROL
 *     inputs = listOf("targetId"),      // arg names your run lambda reads
 *     outputs = listOf("probe readout"),
 *     preconditions = listOf("target must be connected"),
 *     effects = "Reads the cool thing and reports the readout. Nothing is changed.",
 *     verified = false,                 // true ONLY after a real-hardware test
 *     sourceMethod = "MyViewModel.probe", // real method, or "Manual: <screen>"
 *     run = { args ->
 *         try {
 *             val id = args["targetId"] ?: return@reg OpResult(false, "missing targetId")
 *             val out = myViewModel.probe(id)   // THE REAL CALL
 *             OpResult(true, "probe done", out)
 *         } catch (e: Exception) {
 *             OpResult(false, "probe failed: ${e.message}")
 *         }
 *     }
 * )
 * ```
 *
 * 3. Done. The planner ([Planner]), the investigations screen, knowledge
 *    search, `sys.capabilities`, and [CapabilityScanner] now see the new tool.
 *    No other file changes. There is deliberately no second registry, no
 *    hardcoded list, and no behavior table to keep in sync.
 *
 * ## Risk levels and the authorization model
 *
 * - `READ` — observes only: scans, reads, status, fetches.
 * - `CONFIGURE` — opens/closes a link (ble.connect, usb.connect).
 * - `WRITE` — sends data out (usb.send, net.udp_send).
 * - `CONTROL` — drives hardware behavior (usb.test_esp32).
 *
 * Full autonomous mode: every level auto-runs with no confirmation gates.
 * Each step's level is recorded and every auto-run action is logged in the
 * investigation timeline. Access controls are never bypassed.
 *
 * ## Verified vs unverified (honesty rule)
 *
 * `verified = true` means the underlying tool was exercised on real hardware
 * at least once — a green build alone does NOT count. Unverified capabilities
 * are labeled "(unverified)" wherever they are listed. Flip this flag only
 * after a real device test.
 *
 * ## Safety rules (non-negotiable)
 *
 * - A `run` lambda must never throw: wrap everything in try/catch and return
 *   `OpResult(ok=false, ...)` on failure. [IntelEngine.run] double-guards,
 *   but a throwing lambda is still a bug.
 * - Manual-only realities (NFC needs a physical tap, gallery pick needs a
 *   tap, joining Wi-Fi is a phone setting) are registered as capabilities
 *   that return an honest "manual step" OpResult — see `nfc.open_tools`,
 *   `visual.pick_gallery`, `sys.manual_step`. The planner treats them as
 *   guidance, not execution.
 * - The engine must never fabricate a connection or claim success without
 *   evidence: every OpResult comes from the real call's observed outcome.
 *
 * ## Discovery
 *
 * [CapabilityScanner.scan] uses reflection over the real ViewModel instances
 * and compares each public method against every registration's `sourceMethod`.
 * Public methods with no registry entry are reported as wiring candidates —
 * real code that exists but isn't visible to the intelligence system yet.
 *
 * ## Planner notes
 *
 * - [Planner.planFor] matches target text against offline patterns (MAC, IPv4,
 *   URLs, NFC/BLE/USB keywords) and emits steps referencing registered ids
 *   only. Unknown ids are dropped by validation; `auto` is forced true ONLY
 *   for riskLevel == "READ".
 * - Steps for a capability that already failed on the current investigation
 *   are deprioritized (moved to the end, auto cleared) unless the user taps
 *   RUN manually.
 * - [RuleBasedReasoner] is the on-device local reasoner: evidence-driven
 *   hypotheses with confidence, failure-driven reordering, memory-informed
 *   deprioritization. It is labeled "Local reasoning (on-device rules)" —
 *   never as AI.
 * - [RemoteLlmReasoner] is the optional OpenAI-compatible engine (your key,
 *   your endpoint). It can never invent capability ids, can never flip a
 *   non-READ step to auto, and any failure falls back to the local reasoner.
 *   The UI always shows which engine produced the plan.
 */
object CapabilityDoc
