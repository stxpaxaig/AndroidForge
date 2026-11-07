# VNIC Surgical Repair — Implementation Notes

Working tree only: `~/workspace/ghosttap/vnic-repair/` (copy of fixed3).
Original `~/workspace/ghosttap/ai-command-center-fixed3/`, the main branch,
and VirtualESP32 were not touched. Nothing was built; no git branch was
touched. Ground truth followed: `CONFIRMATION.md` (left in place for the
packaging agent). No API key exists in this environment; none was invented,
hardcoded, logged, or written to any file.

## Files changed (4)

### 1. `app/src/main/java/com/ghosttap/core/vnic/VnicReasoning.kt`

- `:29-32` — `VnicReasoningProvider` gains an enriched `planFor(goal, capabilities, knownFacts, findings)` with a default that delegates to the ids-only call, so existing providers keep working.
- `:41-44` — interface gains `lastFailureReason` (null default) and `lastPlanFellBackToLocal` (false default): the honest-labelling channel for remote failures.
- `:113-127` — `LocalRuleReasoner` overrides enriched `planFor`: goal words are now matched against id + title + description, not the id alone.
- `:141` — `VnicLlmConfig.DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"`, the same default the older investigation path uses (`Reasoning.kt:410`).
- `:149-155` — `VnicLlmConfig.fromSaved(endpoint, apiKey)`: trims both saved values; blank endpoint defaults to OpenAI; blank key stays unset (null).
- `:157` — `VnicLlmConfig.configured` is key-only (blank endpoint no longer blocks remote use).
- `:160` — `VnicLlmConfig.effectiveEndpoint`: configured endpoint, or OpenAI when blank/unset.
- `:176-181` — `RemoteLlmReasoner` exposes `lastFailureReason` / `lastPlanFellBackToLocal` (private setters).
- `:209-210` — ids-only `planFor` now delegates to the enriched one (empty facts/findings), so both signatures share the fallback behaviour.
- `:225-263` — enriched remote `planFor`: prompt carries capability id + title + description, known facts (max 5), and fresh findings (max 5, each snippet 200 chars; zero findings are stated explicitly, not hidden); the current observation is stated to take precedence over older facts on disagreement; on remote failure it falls back to the built-in helper's plan and sets `lastPlanFellBackToLocal` instead of returning a silent empty list.
- `:274-333` — `complete()`: accepts any HTTP 2xx (`code !in 200..299` fails; was `!= 200`); classifies every failure into a plain-words `lastFailureReason` (401/403 = key refused, 429 = busy, other 4xx/5xx, timeout, unreachable, empty answer, not configured) — never containing the key, the endpoint, or a response body; the key is sent only in the `Authorization` header.
- `:335-342` — `parseChatContent()`: extracts `choices[0].message.content` anchored at `"choices"` then `"message"`; the old code took the first `"content"` anywhere in the body (only coincidentally correct for a standard OpenAI reply), and a null/non-string content now returns null instead of a wrong string.
- `:355-374` — new `SwitchableReasoner`: the core holds this one object for life while the engine swaps the live delegate underneath it, so reconfiguration never rebuilds the core (mesh, memory, learning, telemetry survive).

### 2. `app/src/main/java/com/ghosttap/core/vnic/VnicEngine.kt`

- `:107` — engine keeps the `Application` (`context.applicationContext as? Application`) so `IntelSettings` can be read; no Context blocker.
- `:108-109` — the fixed `private val reasoner` becomes a `SwitchableReasoner` (`reasonerSwitch`); the core is handed the switch (`:144`).
- `:117-122` — `resolveReasoner()`: an explicitly passed key wins at construction; otherwise the saved Thinking Engine settings via `VnicLlmConfig.fromSaved()`; remote iff a key is present, else the built-in helper.
- `:133-138` — new `refreshReasonerFromSettings()`: re-reads `IntelSettings` and swaps the live delegate (remote when a key is saved, built-in when cleared); never throws; returns the active reasoner name.
- `:278` — `observe()` calls `refreshReasonerFromSettings()` before the cycle reasons, so a key saved after the engine was built takes effect with no app restart.
- `:414` — `reasonerName()` now reads the live delegate.
- `:417` — new `reasonerFailureReason()`: surfaces the last online-helper failure in plain words for diagnostics/UI.

### 3. `app/src/main/java/com/ghosttap/core/vnic/VnicCore.kt`

- `:517-531` — PLAN gate removed: the planner now runs for **every** cycle that has capabilities, not only `answer_from_context` — including `research_required` with zero findings and `store_context` observations. The `action` itself is unchanged (`research_required` stays `research_required` when research finds nothing); only the plan is now attempted. The enriched call passes the current observation, full capability entries, known facts, and fresh findings; history is supporting context only — planning never requires the subject to already exist in memory or the concept graph, and older facts never override the current observation.
- `:522-528` — the fallback-labelling phase (`"PLAN: online helper did not answer (<reason>) — built-in helper plan used"`) is read in the same cycle that planned, so a no-capability cycle can never report the previous cycle's fallback.
- `:534-539` — registry validation: plan ids are filtered against the runtime capability list actually passed in; invented ids are dropped, never executed, and named in the trace (`"PLAN: dropped N id(s) not in the capability list"`). The develop/execute bridges validate again.
- `:551` — DEVELOP is skipped for `store_context`: a plain statement gets a candidate plan only, never a developed procedure or a device step. ACT for `store_context` is unchanged (working-memory store only).
- Header comment updated to describe the new planning rule.

### 4. `app/src/main/java/com/ghosttap/IntelViewModel.kt`

- `:1215-1225` — `refreshReasonerName()` (already called by the Thinking Engine SAVE/CLEAR handlers) now also calls `vnicEngine.refreshReasonerFromSettings()`, so a just-saved key reaches the VNIC at save time as well as before the next cycle. It forces the lazy engine to build on SAVE/CLEAR, the same as `attachVesp32()` already does. No change to the construction site (`:107-109`): the engine resolves settings itself at construction.

## How each fix works, in one line

- Defect 1 (wiring): the engine builds its reasoner from the saved `IntelSettings` at construction and re-reads them on every `observe()` and on every Thinking Engine SAVE/CLEAR, swapping the live delegate behind a `SwitchableReasoner` without rebuilding the core.
- Defect 1 (blank endpoint): `configured` is key-only and a blank endpoint defaults to the OpenAI chat-completions endpoint, so a key-only user gets remote reasoning.
- Defect 1 (key safety): the key is used only as the `Authorization` header value; no log, diagnostic, phase line, failure reason, or exception anywhere contains it (verified by grep: no logging of the key exists in the changed code).
- Defect 1 (2xx): `complete()` accepts `200..299`, not exactly `200`.
- Defect 1 (parsing): the reply is parsed as `choices[0].message.content`, anchored at `"choices"` then `"message"`.
- Defect 1 (honest labelling): every remote failure records a classified plain-words reason; `planFor` falls back to the built-in helper explicitly and the core adds a phase line naming the fallback and the reason, so a failed remote call is never reported as online.
- Defect 2 (zero-findings gap): the `action == "answer_from_context"` plan gate is gone — any cycle with capabilities plans, so zero-finding research and statements get a plan attempt; `research_required` keeps its action, and the existing "Missing capability" gap record is still written when the plan comes back empty.
- Defect 2 (enriched inputs): the planner now receives capability ids + titles + descriptions, known facts, and fresh findings (including an explicit "none" when research returned nothing).
- Defect 2 (registry-only plans): plan steps are validated against the runtime capability list in the core (and again by the develop/execute bridges); no fitting capability still yields the existing gap record, never an invented action.

## Existing tests — what breaks

**No existing test assertion breaks.** Checked by tracing each test
against the new code (tests were NOT edited, per the task):

- `app/src/test/java/com/ghosttap/core/vnic/VnicCoreTest.kt:248` `unknownInstructionWithNoFindingsIsResearchRequired` — passes unchanged: no capabilities are passed, so no plan is attempted; action stays `research_required`, state `IDENTIFIED`.
- `VnicCoreTest.kt:261` `statementIsStoreContext` — passes unchanged: no capabilities passed; action `store_context`, state `IDENTIFIED`.
- `VnicCoreTest.kt:270` `priorMemoryMakesAnswerFromContext` — passes unchanged (no capabilities passed).
- `VnicCoreTest.kt:283` `researchFindingsTurnResearchRequiredIntoAnswer` — passes unchanged (no capabilities passed; findings flip still works).
- `VnicCoreTest.kt:320` `failedExecutionIsRecordedNotHidden` — passes unchanged (pre-seeded memory → `answer_from_context`, plan as before).
- `VnicCoreTest.kt:109` (`VnicPackage7Test.kt:109`) `cycleGrowsConceptGraphFromRealSubject` — passes unchanged (empty capabilities).
- `app/src/test/java/com/ghosttap/core/vnic/VnicPackage7Test.kt:82` `remoteFallbackIsLabelledAndFallsBackToLocal` — passes unchanged: the unreachable test endpoint still fails, the labelled first line and the built-in hypotheses still follow.
- `app/src/test/java/com/ghosttap/core/vnic/VnicClosedLoopTest.kt:427,470,503` (`coreBringUpClosesLoopWithFakePort`, `failedBringUpEndsFailedWithExactReason`, `kindWithoutPortIsStatedExplicitly`) — pass unchanged: all pre-seed memory (`answer_from_context`), and enriched local planning matches at least the ids the old ids-only planner matched (`esp32.tcp_connect`).
- `VnicClosedLoopTest.kt:537` `closedLoopDemo` — environment-gated (`Assume` on loopback TCP); its assertion `plan.any { startsWith("esp32.") }` still holds under enriched planning.

**One test needs its premise/comment updated (assertions still pass):**

- `VnicCoreTest.kt:297` `verifiedExecutionReachesVerifiedState`, comment at `:303` ("stays research_required, so nothing executes. Give it knowledge:") — **now false on the first cycle**: the first cycle now plans `ble.scan` (local rule matches "bluetooth") and executes it through the attached `FakeExecutor`, reaching `VERIFIED` before any knowledge is added. The test's assertions still pass (the executor is called with `ble.scan` on both cycles; the second cycle is still `answer_from_context` / `VERIFIED` / `reward=0.9`), but the comment describes the old behaviour. The verification agent should update the comment and, ideally, assert the new first-cycle behaviour (plan non-empty, executor called on the first cycle, state `VERIFIED` on the first cycle) instead of leaving a stale premise.

**New behaviour the verification agent should add tests for** (no tests were added here, per the task — tests are the verification agent's job):

- Zero findings + capabilities present → `plan` non-empty (local rule) and `action` still `research_required`, state `IDENTIFIED` when no bring-up/executor runs.
- `store_context` with capabilities → plan is a candidate only: `developProcedure` is NOT called and nothing executes.
- Remote `planFor` failure → falls back to the local plan with `lastPlanFellBackToLocal == true` and a non-null, key-free `lastFailureReason`; the core phase trace contains the fallback line.
- `VnicLlmConfig.fromSaved("", "k")` → `configured == true`, `effectiveEndpoint` is the OpenAI default; `fromSaved("", "")` → not configured.
- Remote `complete()` accepts a 201 response (currently only exercised by a stubbed transport — there is no transport override on `RemoteLlmReasoner`; a fake-HTTP test would need one, or a live stub server).
- `VnicEngine.refreshReasonerFromSettings()` swaps remote ↔ local as settings change (needs an Android/Robolectric harness — there is no `VnicEngine` test today).

## Deliberately NOT changed

- No test files edited; no `build.gradle.kts` change (namespace `com.ghosttap`, versionCode 28, versionName 1.25 untouched).
- No construction-site change in `IntelViewModel.kt:107-109` — the engine resolves settings itself; the lazy `by lazy` construction is preserved.
- No change to `VnicResearchConfig` / research providers (same empty-default shape exists there, but it is out of the asserted scope).
- No change to the `action` decision rules (`VnicCore.kt` DECIDE), the bring-up detection/execution path, `VnicExecution` / `VnicVerify`, risk controls, or the plan → DEVELOP → ACT chain (selected = `plan.firstOrNull()` → `developProcedure` → plan steps max 5 via `VnicExecution` → `IntelEngine.run` + `VnicVerify.verify`).
- No change to any UI, permissions, networking beyond the VNIC remote call, NFC, or VirtualESP32 code.
- No hardcoded device recognition, no placeholder functions, no mock AI responses, no fake successful execution, no new permissions, no chat interface.
- No key in code, files, logs, or diagnostics; no key was available in this environment to use.
- `CONFIRMATION.md` left in place (packaging agent handles scratch).

## Known limitations / notes for the verifier

- A delegate swap can land mid-cycle if two `observe()` calls run concurrently (drive-loop + on-demand): the swap is `@Volatile` and each reasoner call reads the current delegate, so the worst case is a cycle whose hypotheses came from one delegate and whose plan came from the other; the phase trace labels whichever delegate actually planned.
- `refreshReasonerName()` now forces the lazy `vnicEngine` to build when the user SAVEs/CLEARs the Thinking Engine settings (same as `attachVesp32()` already does); if engine construction ever throws, it is swallowed by `runCatching` and the old-path refresh still completes.
- `RemoteLlmReasoner` has no transport override (unlike `VnicResearcher`'s `providersOverride`), so its 2xx/parse/fallback paths can only be unit-tested against a real stub HTTP server or by refactoring the transport out — flagged, not changed, to keep the diff surgical.
