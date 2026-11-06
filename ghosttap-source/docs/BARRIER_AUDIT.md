# Barrier Audit — GHOST TAP Intelligence (v1.23)

Engineering ledger of the REAL blockers behind this app's intelligence
ambitions. Each entry records the exact operation blocked, the layer it
lives in, whether it is absolute or conditional, the strongest legitimate
alternative, what was researched or tested, and what remains open.

This is not a surrender note. Where a blocker is absolute, the entry says
exactly which mechanism is missing. Where an alternative exists, it is named.

Date: 2026-10-17. Device class reference: moto g power 5G (Android 15,
mid-range SoC, ~6 GB RAM class).

---

## 1. Dynamic code execution

Question: can an investigation author NEW executable behavior at runtime —
not just compose existing tools?

### 1a. Composition of registered primitives — VIABLE (built, v1.22)

- Mechanism: `DynamicCapabilityRuntime` chains existing registry
  capabilities (`DynStep` lists), with arg substitution
  (`${prev.data}`, `${input.NAME}`), risk escalated to the max of its
  parts, READ-only test runs, `verified=true` only after a passing test.
- Layer: app code. No new permission. No new dependency.
- Limitation: can only do what the registry already does. It is a
  workflow, not new code — labeled honestly as "composed workflow from
  verified primitives", never code generation.
- Status: implemented, compiles, never run on-device.

### 1b. Embedded JS interpreter (Rhino) — VIABLE (built, v1.23)

- Mechanism: Rhino 1.7.14 in pure interpreter mode
  (`setOptimizationLevel(-1)` — no bytecode generation), `ClassShutter`
  blocking ALL Java access (no `Packages.*`, no reflection into app
  classes), sealed scope, one host object `ghost` exposing exactly three
  functions: `run(capabilityId, argsJson)` (delegates to
  `IntelEngine.run`, inheriting the loop's READ-only auto-run enforcement),
  `finding(title, text, source)` (records to IntelDb), `log(msg)`.
  Instruction-count limit aborts runaway scripts.
- Layer: app code + one stable Maven dependency
  (`org.mozilla:rhino:1.7.14`). No new permission.
- Why Rhino and not alternatives:
  - **WebAssembly**: needs a WASM runtime (e.g. chicory/wasmtime-jni) —
    heavier dependency, no mature pure-Java option at this project's
    weight class, and a WASM module still can't touch Android APIs without
    a host bridge we'd have to build anyway. Rhino gives the same
    sandboxed-logic capability with one jar.
  - **Plugin APKs**: installing code at runtime requires either the
    `REQUEST_INSTALL_PACKAGES` permission (new permission — forbidden by
    project rule) or abusing `DexClassLoader` on downloaded dex — the
    latter is exactly what Play Protect flags, and dynamic code loading
    outside the APK is a policy red line. Rejected.
  - **On-device compilation (Java/Kotlin)**: no JDK on the device; the
    Android runtime cannot compile Kotlin. `javax.tools` is absent. There
    is no legitimate path — this is the ABSOLUTE blocker that killed
    "real code generation" as a concept.
- Status: implemented in v1.23 (`ScriptCapability.kt`), compiles via the
  workflow build. Not run on-device yet.

### 1c. Verdict

Strongest viable mechanism: **Rhino interpreter** for genuinely new
logic (loops, branching, string/data shaping) over the existing
capability surface, with composition retained for pure workflows.
On-device compilation is the absolute blocker — no mechanism exists on
Android to compile new native/DEX code in-app, and plugin-APK loading
is rejected on permission/policy grounds.

---

## 2. Local model inference on this device class

Question: can the phone run a real language model on-device instead of
the rule-based reasoner?

Researched (2026-10-17): MediaPipe LLM Inference API
(`com.google.mediapipe:tasks-genai`), AICore/Gemini Nano, llama.cpp
ports.

- **MediaPipe LLM Inference**: real, stable API. Requires an ARM64
  device, Android API 26+, 4 GB+ RAM recommended. Models are Gemma
  variants converted to the MediaPipe bundle format. Gemma 3 1B INT4
  needs ~1–2 GB free RAM; Gemma 3n uses ~3 GB; quantized Gemma-2B is a
  ~1.2 GB download. CPU fallback works, GPU/NPU delegation is faster
  but device-specific.
- **AICore / Gemini Nano**: OS-managed, but only on Pixel 8 Pro / S24
  class devices. This device class is NOT eligible.
- **llama.cpp on Android**: works via NDK builds, but is a native
  library integration with its own threading/memory management — a
  separate project, not a Gradle one-liner.

Blockers (conditional, not absolute):
1. **Model distribution.** No model may be bundled — a 1–2 GB download
   inside the APK is not shippable through this project's handoff
   (Drive ZIP → workflow build). The model would have to be downloaded
   by the user at runtime (HuggingFace/Kaggle terms + login for Gemma),
   which is a UX and licensing flow that does not exist in the app.
2. **Memory pressure.** A 1B INT4 model (~1–2 GB) on a 6 GB-class device
   alongside CameraX + ML Kit + BLE scanning risks process kills. The
   guidance is to release the model when navigating away — more
   lifecycle machinery, untested here.
3. **No new permissions** is fine (no permission needed), but the
   dependency + model-download flow is a feature-sized project on its
   own.

Verdict: CONDITIONALLY BLOCKED. Technically possible (MediaPipe
`tasks-genai` + user-downloaded Gemma 3 1B INT4), deliberately NOT
bundled: the honest cost is a 1–2 GB runtime download, elevated OOM
risk on this device class, and a model-terms flow the project has not
approved. The remote-model path (`RemoteLlmReasoner`, user's own key)
remains the supported way to get real model reasoning today.

---

## 3. Background execution limits for long investigations

Question: can a 10-minute investigation keep running with the screen off?

- Layer: OS sandbox / background policy (Android 8+ background limits,
  Doze, App Standby; Android 12+ foreground-service launch restrictions;
  Android 14+ stricter foreground service types).
- The investigation loop currently runs in `viewModelScope` — it dies
  with the process. Android will kill a backgrounded app's process under
  memory pressure with NO guarantee of completion.
- Legitimate alternatives:
  - **Foreground service** with a persistent notification (type
    `dataSync` or `specialUse`): allowed, keeps the process alive, but
    requires the `FOREGROUND_SERVICE*` permissions — NEW permissions,
    forbidden by project rule.
  - **WorkManager**: allowed without new permissions, survives process
    death, but is deferrable — Doze can delay a 10-minute job by hours.
    Honest for "finish eventually", dishonest for "live investigation".
- Verdict: CONDITIONALLY BLOCKED. A live 10-minute loop needs either a
  foreground service (new permission — rejected) or the user keeping the
  app in the foreground. Implemented behavior: the loop runs while the
  app is open; `stopLoop()` exists; nothing pretends to run in the
  background. WorkManager-based "finish later" is future work, not
  promised.

---

## 4. VirtualESP32 IPC — the ACTUAL interface (source evidence)

Question: how does Ghost Tap really talk to the VirtualESP32 app?

Source: `app/src/main/java/com/ghosttap/core/esp32/VirtualEsp32Client.kt`
(class KDoc: "Client for the Virtual ESP32 IPC bridge (Intent + broadcast
based)"), `ui/VirtualEsp32Screen.kt` (buttons call `client.connect()`,
`client.loadFirmware(uri)`, `client.sendUart(...)`, etc.).

- Commands: **explicit Intents** to the bridge service
  `dev.virtualesp32.VirtualEsp32BridgeService` in package
  `dev.virtualesp32`, with actions:
  `START_GUEST`, `STOP_GUEST`, `RESET_GUEST`, `SEND_UART`, `WRITE_GPIO`,
  `QUERY_STATUS`, `READ_GPIO`, `LOAD_FIRMWARE`, `EJECT_FIRMWARE`,
  `USE_BUILTIN_FIRMWARE`. Firmware images cross the boundary as
  `content://` URIs via this app's FileProvider with read-permission
  grants.
- Events: **broadcasts** the client subscribes to via `IntentFilter`:
  `GUEST_STARTED/STOPPED/RESET`, `UART_RX`, `GPIO_CHANGED`, `ERROR`,
  `STATUS`, `GPIO_VALUE`, `FIRMWARE_LOADED/EJECTED`.
- NOT present: no AIDL interface, no bound service, no sockets, no
  ContentProvider, no shared memory. Assumptions of any of those would
  be wrong.
- Implications for intelligence: the emulator is controllable ONLY
  through these fixed Intent actions. There is no channel for the
  intelligence to invent new emulator commands — a capability gap like
  "pause the emulated CPU mid-instruction" cannot be bridged in-app.
  Layer: external service (another app's API surface). Absolute for new
  commands; the existing 10 actions are fully usable.

---

## 5. Web research viability — VIABLE (built, v1.22)

- Mechanism: `WebResearcher.kt` — real HTTP via DuckDuckGo's HTML
  endpoint through the app's existing HTTP client, plus page-text
  extraction. Findings are ALWAYS `verified=false` with ASSUMED
  provenance; max 3 page fetches per call.
- Layer: external service (DuckDuckGo HTML). No API key, no new
  permission (INTERNET was already declared).
- Honest limits (documented in code): HTML scraping is heuristic and
  can break if DuckDuckGo changes markup; results are leads, never
  facts. The research step runs on-device evidence FIRST and treats
  web findings as corroboration only.
- Status: implemented, compiles. Network-dependent behavior never
  verified on-device.

---

## 6. Remaining absolute blockers (no legitimate alternative)

1. **On-device compilation.** Nothing on Android compiles Kotlin/Java
   to executable code in-app. "AI writes new native code and runs it"
   is not a thing on this platform, full stop.
2. **New VirtualESP32 commands.** Fixed Intent surface; new emulator
   primitives need the other app's author.
3. **Background longevity without new permissions.** Live
   screen-off investigations need a foreground service permission the
   project forbids.
4. **Pairing-gated / encrypted targets.** By design: the intelligence
   never bypasses Bluetooth pairing, NFC passwords, or encryption. A
   target that demands pairing stays a manual step — this is a safety
   invariant, not a gap to close.

## 7. What's viable today (the honest stack)

- Compose new behavior from verified primitives (DynamicCapability).
- Execute genuinely new sandboxed logic in JS over the capability
  surface (ScriptCapability / Rhino, v1.23).
- Investigate autonomously with READ-only auto-run + one-tap gates
  (InvestigationLoop, progress-aware limits v1.23).
- Research on-device evidence first, the real web second
  (Researcher + WebResearcher).
- Reason locally with honest labels, or remotely with the user's own
  key (RuleBasedReasoner / RemoteLlmReasoner).
- Trigger investigations automatically from real app events
  (IntelEventBus, v1.23) — never from fabricated data.

## 8. Wave 4 (v1.24) — core intelligence transformation

- **Authorization policy.** The user explicitly directed ZERO TAPS ON
  EVERYTHING (2026-10-18, recorded in project memory after the conflict
  was flagged). The per-operation WAITING_USER gates are removed: the loop
  auto-runs every risk level with no confirmation gates, including the
  EXTEND script path (validate → test → register → run). What stays:
  risk classification on every step, every auto-run action logged to the
  investigations timeline, access controls never bypassed (pairing/password
  gates fail honestly), no fabrication.
- **Event coverage.** Publisher sweep across every tool section: BLE scan
  batches (throttled), GATT service discovery, characteristic read/write
  results + failures, SPP connect, printer probe/print, map rounds, learn
  sessions; all NFC TOOLS results via the screen's `say()` choke point;
  network ping/TCP/UDP/HTTP/port-scan/NSD/Ghost-AP results; USB
  connect/disconnect (UsbState), serial RX (throttled 15s), ESP32
  sync/auto-baud/download-mode/flash; VirtualESP32 guest/status/firmware/
  error broadcasts; Visual Intel captures (VisualCapture) + analysis errors.
  Existing event types were reused (ToolOpResult/IntelError); only
  VisualCapture and UsbState were added.
- **Visual pipeline.** Every analyzed image (camera, gallery) publishes its
  OCR text + barcodes + markings; the engine auto-opens an investigation
  and auto-starts the loop on the extracted identifiers. No new AI screen.
- **Capability audit.** All 29 registry entries traced to real
  implementations: 27 verified=true, 2 honestly unverified
  (visual.pick_gallery, nfc.open_tools — both need a physical user action
  the app cannot perform itself).
- **Cross-tool correlation.** Identifiers (MAC/IP/hostname) in any tool's
  results are matched against past investigations; matches are linked with
  a timeline note.
- **Continuity.** IntelViewModel is Activity-scoped — investigations survive
  navigation between tools. Verified, no change needed.
- **Not device-verified.** No on-device runs of the new event flow, loop,
  or correlation were possible from here; the AndroidForge workflow is the
  only compile test.

## Wave 5 — "zero-tap bring-up" reorientation (v1.25)

**The correction (user's explicit words):** "Zero taps" means zero taps FROM
THE USER to bring a target to controllable state — he taps a target once,
Ghost Tap auto-runs the whole bring-up (connect, enumerate, handshake, map
controls) and lands him on the control interface; then HE drives. NOT
autonomous app control of targets. The investigations-timeline-as-destination
was the wrong product.

**What changed:** new `core/BringUp.kt` — `BringUpPipeline.bringUp()` runs
ordered per-kind plans (BLE connect → GATT discover → map writable/notify
characteristics; NFC tech → applicable ops; NETWORK ping → port scan; USB
permission → open → interface info) entirely through the existing
`IntelEngine.run`, with plain-English failure reasons. `IntelViewModel`
grew `bringUpTarget()` + `bringUpState` (IDLE → CONNECTING → ENUMERATING →
READY / FAILED); target-selected events now run bring-up instead of opening
investigations. On READY the app shell navigates to the target's EXISTING
control screen (`target/{id}`, `nfc_tools`, `usb_uart`; scan-discovered
network hosts report without yanking screens). A plain-English banner shows
progress ("Connecting…", "Finding what it can do…", "Ready — you're in
control."). Bring-up outcomes persist to IntelDb (`kind="bringup"`) and are
read back before each run so repeated failures are recognized.

**What explicitly did NOT change:** the intelligence stays fully active —
event bus + all publishers, capability registry + audit flags, reasoning
providers (remote + local), IntelDb/LearnedStore memory, ProcedureSynthesizer,
DynamicCapability + script authoring/extension all keep running and keep
improving bring-up over time. The investigations screen remains as the
diagnostics/log view. "Gets out of your way" was the wrong phrasing: the
intelligence serves his control of targets, continuously.

**Not device-verified.** No on-device runs of the bring-up flow were possible
from here; the AndroidForge workflow is the only compile test.
