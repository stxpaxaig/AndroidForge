# VNIC Defect Confirmation — read-only, 2026-11-06

Working tree: `~/workspace/ghosttap/vnic-repair/` (copy of fixed3). Original
`~/workspace/ghosttap/ai-command-center-fixed3/` untouched. Nothing modified,
built, or branched. All paths below are relative to the working tree unless
stated. App identity verified: `app/build.gradle.kts:9-17` — namespace /
applicationId `com.ghosttap`, compileSdk 34, minSdk 26, targetSdk 34,
versionCode 28, versionName `1.25`.

## Verdicts

| Defect | Verdict |
|---|---|
| 1 — VNIC engine never receives the saved endpoint/key; always falls back to `LocalRuleReasoner` | **CONFIRMED** (with one correction: the VNIC remote reasoner also demands a non-blank *endpoint*, unlike the older path where endpoint is optional) |
| 2 — `research_required` subjects never receive an AI-generated capability plan because the planner runs only when `action == "answer_from_context"` | **PARTLY / NOT AS ASSERTED** — the gate is exactly as asserted, but the claim "never" is wrong: when live research returns findings, the action flips to `answer_from_context` in the same cycle and the planner *does* run. The real gap is narrower: only when research returns **zero** findings (or the intent is `store_context`) is no plan produced. |

---

## Defect 1 — CONFIRMED

### 1a. The default config is empty, exactly as asserted

`app/src/main/java/com/ghosttap/core/vnic/VnicReasoning.kt:84-90`:

```kotlin
data class VnicLlmConfig(
    /** OpenAI-compatible chat-completions endpoint. Unset by default — set VNIC_LLM_ENDPOINT to enable. */
    val endpoint: String? = null,
    /** Bearer key for the endpoint. Unset by default — set VNIC_LLM_API_KEY to enable. */
    val apiKey: String? = null,
    val model: String = "gpt-4o-mini",
    ...
)
```

### 1b. The fallback condition is exactly as asserted

`app/src/main/java/com/ghosttap/core/vnic/VnicEngine.kt:61-65,94-99`:

```kotlin
class VnicEngine(
    context: Context,
    private val engine: IntelEngine,
    researchConfig: VnicResearchConfig = VnicResearchConfig(),
    llmConfig: VnicLlmConfig = VnicLlmConfig()
) {
    ...
    private val reasoner: VnicReasoningProvider =
        if (!llmConfig.endpoint.isNullOrBlank() && !llmConfig.apiKey.isNullOrBlank()) {
            RemoteLlmReasoner(llmConfig)
        } else {
            LocalRuleReasoner()
        }
```

`reasoner` is a `private val`, fixed once at construction. There is **no
setter, no reconfigure method, no re-read** anywhere in `VnicEngine.kt`.
The only read of which reasoner is active is the label
`fun reasonerName(): String = reasoner.name` (`VnicEngine.kt:371`).

### 1c. The sole production construction site passes no config

`app/src/main/java/com/ghosttap/IntelViewModel.kt:107-109` — the **only**
production construction site in the tree (verified by grep over all of
`app/src`; the only other `VnicEngine(` hit is the class declaration itself):

```kotlin
val vnicEngine: VnicEngine by lazy {
    VnicEngine(appRef, engine).also { e -> vesp32Client?.let { e.attachVesp32(it) } }
}
```

Only `(appRef, engine)` are passed, so both `researchConfig` and
`llmConfig` take their empty defaults. The engine is `lazy`, i.e. built
once on first use and then lives for the ViewModel's lifetime — a key saved
afterwards can never reach it through this path. No test constructs a
`VnicEngine` at all (only `VnicLlmConfig(...)` appears in tests, in
`VnicPackage7Test.kt:86`, to test the remote reasoner in isolation).

### 1d. The saved config never reaches the engine — disconnect confirmed

`IntelSettings` lives in
`app/src/main/java/com/ghosttap/core/Reasoning.kt:940-968`:

```kotlin
object IntelSettings {
    private const val PREFS = "intel_settings"
    private const val KEY_ENDPOINT = "remote_endpoint"
    private const val KEY_API_KEY = "remote_api_key"
    private fun prefs(app: Application) =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun getEndpoint(app: Application): String =
        prefs(app).getString(KEY_ENDPOINT, "").orEmpty()      // :948-949
    fun getApiKey(app: Application): String =
        prefs(app).getString(KEY_API_KEY, "").orEmpty()       // :951-952
    fun setEndpoint(app: Application, v: String) { ... }      // :954-956
    fun setApiKey(app: Application, v: String) { ... }        // :958-960
    fun clear(app: Application) { ... }                       // :962-964
}
```

It needs an `Application` — and `VnicEngine` **already has one**
(`context: Context` / `appRef` is passed at construction), so there is no
Context blocker; the values are simply never read on the VNIC path.

Grep over the whole tree: `IntelSettings` is referenced only in
`Reasoning.kt` itself (`ReasonerPicker`, :926-927), in
`ui/InvestigationsScreen.kt:904-905,922-923,927` (the Thinking Engine box:
load on compose, `setEndpoint`/`setApiKey` on SAVE, `clear` on CLEAR), and
nowhere under `core/vnic/` except a doc comment. The code itself states
the disconnect — `VnicReasoning.kt:97-100`:

> "The user's own key is used when present (the existing RemoteLlmReasoner
> reads it from IntelSettings); this adapter takes the values as constructor
> arguments and stores nothing."

`IntelViewModel.refreshReasonerName()` (`IntelViewModel.kt:1215-1220`)
re-picks the *old* provider and updates only the displayed name/status;
it never touches `vnicEngine`.

### 1e. Correction / nuance: the VNIC remote reasoner is stricter than the old path

The older investigation reasoner
(`core/Reasoning.kt:397`, `class RemoteLlmReasoner(endpoint: String,
apiKey: String, ...)`) treats the endpoint as **optional**:

- `Reasoning.kt:410-411`: `const val DEFAULT_ENDPOINT =
  "https://api.openai.com/v1/chat/completions"`; blank endpoint falls back
  to it (`endpoint.trim().ifBlank { DEFAULT_ENDPOINT }`).
- `configured` is key-only: `trimmedApiKey.isNotBlank()`.
- `ReasonerPicker.pick(app, local)` (`Reasoning.kt:919-931`) re-reads
  `IntelSettings.getEndpoint(app)` / `getApiKey(app)` **on every call**, so
  a newly saved key takes effect immediately, no restart. It is called
  from `IntelViewModel.kt:253, 553, 1175, 1216, 1451, 1574` (LiveTarget/AI
  card path, plan refinement, etc.).

The VNIC remote reasoner (`vnic/VnicReasoning.kt:102`,
`class RemoteLlmReasoner(private val config: VnicLlmConfig)`) has **no**
default endpoint and its `complete()` (`:149-150`) returns null unless
*both* are non-blank — and `VnicEngine` gates construction on both
(`VnicEngine.kt:95`). Consequence for a repair: passing the
`IntelSettings` values straight through is **not sufficient** — a user who
pasted only a key (the normal case; the UI label says endpoint is
"optional — leave blank for OpenAI",
`InvestigationsScreen.kt:911`) would *still* get `LocalRuleReasoner`
under VNIC. The fix must mirror the old path's blank-endpoint → OpenAI
default, or the VNIC gate must change.

Related asymmetry in the same file: VNIC `RemoteLlmReasoner.hypotheses`
(`VnicReasoning.kt:~120-128`) falls back to the local reasoner with a
labelled first line when the call fails, but `planFor` (`:131-136`)
returns `emptyList()` on any failure with **no** local fallback:

```kotlin
override fun planFor(goal: String, capabilityIds: List<String>): List<String> {
    val prompt = "Goal: $goal\nAvailable capability ids: ${capabilityIds.joinToString(", ")}\n" +
        "Reply with only the ids, comma-separated, in the order they should run (max 5). Use only ids from the list."
    val text = complete(prompt) ?: return emptyList()
    ...
}
```

Same empty-default pattern also applies to research, though not asserted:
`VnicResearchConfig` (`VnicResearch.kt:61-70`) defaults `braveApiKey` /
`serperApiKey` to null, so `VnicResearcher` (`VnicResearch.kt:371-381`)
runs only the free DuckDuckGo + Wikipedia providers.

---

## Defect 2 — PARTLY / NOT AS ASSERTED

### 2a. The classification is exactly as asserted

`app/src/main/java/com/ghosttap/core/vnic/VnicCore.kt:415-420`
(DECIDE — "the Python rules, verbatim"):

```kotlin
var confidence = min(0.95, 0.25 + 0.08 * memories.size + 0.035 * graph.size)
var action = when {
    parsed.intent == "statement" -> "store_context"
    known -> "answer_from_context"
    else -> "research_required"
}
```

`known` = prior memories or graph associations exist (`:375-378`).
`VnicCore`'s own default reasoner is also local
(`VnicCore.kt:266-272`: `reasoner: VnicReasoningProvider =
LocalRuleReasoner()`), but `VnicEngine` injects its own.

### 2b. The planner gate is exactly as asserted

`VnicCore.kt:501-505`:

```kotlin
val plan = if (action == "answer_from_context") {
    reasoner.planFor(observation.text, capabilities.map { it.id })
} else {
    emptyList()
}
```

The planner is invoked only when `action == "answer_from_context"`.
`plan` is `List<String>` of capability ids; `selected = plan.firstOrNull()`
(`:506`).

### 2c. But "unfamiliar subjects never receive a plan" is NOT true

Between DECIDE and PLAN, `research_required` is handled, not dead-ended
(`VnicCore.kt:423-481`, RESEARCH — "the Python core stopped at
`research_required` with no adapter. Run the live researcher"):

```kotlin
if (action == "research_required") {
    phases += "RESEARCH: local evidence insufficient — live research"
    val report = try {
        researcher.research(observation.subject + " " + observation.text.take(120))
    } catch (_: Exception) { null }
    ...
    if (report != null && report.findings.isNotEmpty()) {
        findings = report.findings
        for (f in findings) { memory.remember(... SEMANTIC ...) }   // stored + graph-linked
        ...
        confidence = maxOf(confidence, findings.maxOf { it.confidence })
        action = "answer_from_context"                                // :479 — the flip
        phases += "RESEARCH: ${findings.size} finding(s) from ..."
    } else {
        phases += "RESEARCH: no findings — research providers returned nothing usable"
    }
}
```

So an unfamiliar subject **does** get a plan in the same cycle whenever
live research returns ≥1 finding — the flip at `:479` makes the `:501`
gate pass. This is covered by an existing test,
`app/src/test/java/com/ghosttap/core/vnic/VnicCoreTest.kt:284-294`
`researchFindingsTurnResearchRequiredIntoAnswer` (stub researcher returns
one finding → `assertEquals("answer_from_context", result.action)`).

The **actual** gap is narrower — no plan is produced when:

1. `action` stays `research_required` because research returned **zero**
   findings (or threw), or
2. the intent was `statement`, i.e. `action == "store_context"` — also
   never planned (and the ACT branch then only stores to working memory,
   `VnicCore.kt:550-559`).

### 2d. What happens to a research_required subject after research (no findings)

- Hypotheses **are** still generated: `reasoner.hypotheses(observation.text,
  knownFacts)` is called unconditionally (`VnicCore.kt:~490-493`), with
  `knownFacts = memories + graph + findings`. So the reasoner is consulted
  for every subject; only `planFor` is gated.
- Execution state is set from evidence (`:494-498`): `RESEARCHED` if
  findings exist, `UNDERSTOOD` if previously known, else `IDENTIFIED`.
- `selected` is null, so the DEVELOP branch records a missing-capability
  gap instead (`VnicCore.kt:534-543`): phase `"PLAN: no capability matches
  — missing-capability gap recorded"` + a SEMANTIC memory
  `"Missing capability for: <text>"`.
- ACT (`:550+`) is **not** skipped for `research_required`: the `else`
  branch (any non-`store_context` action) still runs. If
  `vnicDetectBringUpKind(...)` finds a kind **and** a bring-up port is
  attached, the bring-up runs as the act *despite the empty plan*
  (`VnicBringUp.kt:66-75` keyword detection: esp32/espressif → ESP32,
  bluetooth/ble/gatt → BLE, nfc/tag → NFC, usb/serial/uart/ch340 → USB,
  wifi/ssid/network/ip/host → NETWORK). If no kind is detectable, or no
  port is attached, nothing executes — the cycle ends at `IDENTIFIED`
  (or `UNDERSTOOD`/`RESEARCHED`) with the gap record only.
- The existing test pins this behaviour:
  `VnicCoreTest.kt:247-257`
  `unknownInstructionWithNoFindingsIsResearchRequired` asserts
  `action == "research_required"`, `executionState == IDENTIFIED`, and a
  second test (`:300-315`) asserts that with no findings "nothing
  executes" until knowledge is added. A repair that plans for
  `research_required` would need these tests updated.

### 2e. Planner inputs — ids only; research and memory do NOT reach planFor

- Call: `reasoner.planFor(observation.text, capabilities.map { it.id })`
  (`VnicCore.kt:502`). Inputs are the raw observation text and **bare
  capability ids only** — no titles/descriptions, no `findings`, no
  `knownFacts`/memory, no hypotheses.
- `knownFacts` (memories + graph + findings) go only to `hypotheses()`
  (`:490-493`).
- `LocalRuleReasoner.planFor` (`VnicReasoning.kt:73-82`): keyword-matches
  goal words (plus `SYNONYMS`: bluetooth↔ble, wifi/wireless→net,
  serial/uart→usb, tag→nfc, printer↔print) against id segments, max 5.
- VNIC `RemoteLlmReasoner.planFor` (`VnicReasoning.kt:131-136`): sends goal
  + id list only, "Use only ids from the list", filters the reply to ids in
  the list, max 5; on any failure returns `emptyList()` (no local
  fallback — see §1e).
- How a plan becomes executed capabilities: `selected = plan.firstOrNull()`;
  DEVELOP (`VnicCore.kt:515-...`): if a `developProcedure` callback is
  attached — `VnicEngine.observe` always attaches one
  (`VnicEngine.kt:241`, `bridge.developProcedure(goal, stepIds, args)`)
  — the plan is developed + tested via `VnicCapabilityBridge`, saved to
  VNIC memory and registered as a runnable capability on success; ACT:
  if a bring-up kind + port exist, the bring-up *replaces* plan execution;
  otherwise plan steps run in sequence via `executor` (max 5,
  `VnicCore.kt:~575-600`), where `VnicExecution.execute`
  (`VnicExecution.kt:22-32`) calls `IntelEngine.run(capabilityId, args)`
  and `VnicVerify.verify` checks the result. A plan step id that is not
  registered is refused by the engine/validation, never improvised
  (`VnicCore.kt:94-102`).

---

## Capability registry — where ids/descriptions live

- **Single source of truth:** `object CapabilityRegistry` in
  `app/src/main/java/com/ghosttap/core/Capability.kt:67-...` —
  `register(c) / all() / get(id) / ids() / byCategory(...)`. `IntelCapability`
  (`Capability.kt:12-...`) carries `id, title, detail, category, inputs,
  outputs, preconditions, readOnly, riskLevel, effects, verified,
  sourceMethod, run`.
- **Populated at runtime** (no static list): `IntelEngine` init registers
  via its private `reg(...)` helper (`IntelEngine.kt:155-167` →
  `CapabilityRegistry.register(IntelCapability(...))`); ~29 `reg(` calls
  plus 1 direct register across `registerVisual / registerBle /
  registerNetwork / registerUsb / registerNfc / registerSystem`
  (`IntelEngine.kt:170, 288, 427, 559, 681, 735`), e.g.
  `visual.capture_analyze`, `ble.list_targets`. Additional registrations:
  `ops/OperationRegistry.kt:52` (operations as capabilities),
  `vnic/VnicEsp32Bridge.kt:236` (`vesp32.*`),
  `DynamicCapabilityRuntime` (`DynamicCapability.kt:310`) and
  `ScriptCapability.kt:344` (dynamic/scripted capabilities).
- **VNIC view:** `VnicCapabilityBridge.mapCapabilities()`
  (`VnicCapabilityBridge.kt:29-31`) maps `CapabilityRegistry.all()` to
  `VnicCapabilityInfo(id, title, description=detail, riskLevel, verified)`.
  `VnicEngine.observe` passes that list into `core.process(...)` — so
  titles/descriptions **are available** at the call site but are discarded
  by `VnicCore.kt:502`, which passes only `.map { it.id }` to `planFor`.
  A fix can reference only ids that exist in this registry (plus
  `vnic.*` procedures registered after a successful develop, and
  `vesp32.*` once attached).

---

## Test layout & build config (unchanged)

- Tests: plain JVM unit tests, JUnit 4 (`junit:junit:4.13.2`,
  `app/build.gradle.kts:87`; no MockK / coroutines-test in the test
  dependencies). Location `app/src/test/java/com/ghosttap/core/`:
  `HandshakeTest`, `NfcPackage4Test`,
  `investigate/{InvestigatorTest, LiveTargetTest}`,
  `ops/{OperationRegistryTest, OperationRunnerTest, Package5OperationsTest,
  Package6OperationsTest, Package7OperationsTest, VerificationTest}`,
  `vnic/{VnicClosedLoopTest, VnicCoreTest, VnicDrivePolicyTest,
  VnicPackage7Test}`. VNIC tests use `runBlocking` with stub researchers,
  fake executors and `InMemoryVnicMemory`; there is **no `VnicEngine`
  test**. Tests that pin current behaviour relevant to a repair:
  `VnicCoreTest.unknownInstructionWithNoFindingsIsResearchRequired`
  (`:247`), `researchFindingsTurnResearchRequiredIntoAnswer` (`:284`),
  `verifiedExecutionReachesVerifiedState` (`:296`, incl. the comment
  "stays research_required, so nothing executes"), and
  `VnicPackage7Test.remoteFallbackIsLabelledAndFallsBackToLocal` (`:85`,
  remote reasoner with an explicit `VnicLlmConfig`).
- Build: `app/build.gradle.kts:9-17` as in the header. No `androidTest`
  VNIC coverage; `androidTestImplementation` is espresso/compose only.

---

## Other repair blockers / notes

1. **Single construction site, but immutable + lazy.** Only
   `IntelViewModel.kt:108` builds a `VnicEngine`, which is good for a
   surgical fix — but it is `by lazy` and its `reasoner` is a `private
   val`. A fix must either build it with the config at that site, or add
   a reconfigure/re-read path; merely changing `IntelSettings` (or the
   SAVE handler) cannot affect an already-built engine. Note the engine
   may already exist before the user ever opens the Thinking Engine box.
2. **No Context blocker.** `VnicEngine` already receives a `Context`
   (`appRef: Application` is held in `IntelViewModel.kt:94`) and
   `IntelSettings` needs exactly an `Application`.
3. **Blank-endpoint trap (see §1e).** The VNIC gate requires a non-blank
   endpoint; the saved endpoint is blank in the normal single-key case.
   A pass-through fix that does not default the endpoint reproduces the
   bug for the most common user.
4. **Plan failure is silent.** VNIC remote `planFor` returns `emptyList()`
   on failure with no local fallback, so a remote outage yields an empty
   plan (indistinguishable from "no capability matches") rather than a
   rule-based plan.
5. **Planner input poverty.** `planFor` receives ids only; the
   descriptions in `VnicCapabilityInfo` are dropped at `VnicCore.kt:502`.
   Any "AI-generated plan" fix that wants the model to choose sensibly
   must widen that call (or the reasoner must keyword-match ids, as the
   local one does).
6. **Bring-up bypasses the plan.** Even with an empty plan, a
   `research_required` cycle can still drive a bring-up when the kind is
   keyword-detectable and a port is attached — so "no plan" ≠ "no action"
   for BLE/NFC/USB/Network/ESP32 subjects, but it does mean no
   capability-step execution and no developed procedure.
7. **Research config has the same empty-default shape** (`VnicResearchConfig`
   keys null → free providers only). Out of the asserted scope, but the
   same disconnect pattern.
