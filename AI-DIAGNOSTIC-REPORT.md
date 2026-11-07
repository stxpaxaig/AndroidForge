# Ghost Tap — AI Connection Diagnostic (Part 2) + Gap Fixes — Verification & Packaging Report

**Date:** 2026-11-07 (America/Los_Angeles)
**Tree:** `~/workspace/ghosttap/zero-config/` (the zero-config tree; implementation done by the implementation agent, verified + packaged here)
**Base:** the previous zero-config build — branch `muse/ghosttap-zero-config` head `5367197caeee0047aa5c352eef300caeaee974bb` ("Ghost Tap zero-config: sync ZERO-CONFIG-REPORT.md with repair commit 7400551b", 2026-11-07T02:51:18Z). The pre-diagnostic tree was recovered from the previous source ZIP (`~/workspace/your_files/ghosttap-zero-config.zip` as it stood before this repackaging: SHA256 `a823ba885a99ccf0a75203577bbd637eb04f189be7e99d43a6aacdca7a650a53`, 605,522 bytes, 149 files).
**Companion report:** `ZERO-CONFIG-REPORT.md` §17 (status note appended, report itself not rewritten).
**Status: VERIFIED STATICALLY ONLY. No compile, no test run, no on-device proof. See §7.**

No API key exists in this environment; none was asked for, invented, hardcoded, logged, or written to any file. `~/workspace/ghosttap/vnic-repair/`, fixed3, `main`, and the `muse/virtualesp32-chatgpt-handoff` branch were NOT touched. No AndroidForge build was attempted — the build is the parent/coordinator's step, from the new ZIP link (§5).

## 1. Verification — what was done

The packaging agent did NOT redo the implementation. It verified it by diffing the working tree against the pre-diagnostic tree (the previous ZIP, unzipped to scratch):

- `diff -r` (excluding `.gradle/`) between the old ZIP contents and `~/workspace/ghosttap/zero-config/`: **exactly 3 new files and 7 changed files. Nothing else differs.** No file was removed.
- Every claimed change was spot-checked in the diff (full diffs read for `VnicReasoning.kt`, `Reasoning.kt`, `LiveTarget.kt`, `VnicCore.kt`, `VnicEngine.kt`, `InvestigationsScreen.kt`, `IntelViewModel.kt`; `VnicDiagnostic.kt` read in full).
- Package identity unchanged: `app/build.gradle.kts` — `namespace = "com.ghosttap"`, `applicationId = "com.ghosttap"`, `versionCode = 28`, `versionName = "1.25"`. `AndroidManifest.xml` is byte-identical to the pre-diagnostic tree — **no new permissions**.
- Secret grep over all 10 new/changed files: **no real key**. The only hits are benign — the pre-existing `KEY_API_KEY` SharedPreferences key *name* (`Reasoning.kt:1000`, present in the old tree too), op ids containing "ask-device" (false positive on the `sk-` pattern), and the dummy string `"stub-test-key"` / `"stub-test-key-ABC123"` in the two new test files (test fixtures, explicitly labelled dummy in the test headers — not a real key).
- The two parallel `RemoteLlmReasoner` classes are still present (`VnicReasoning.kt:203` and `Reasoning.kt:397`) — Gap 2 NOT done, as stated (§4).
- No JDK/JRE exists here (`java`, `javac`, `kotlinc` all absent; no `/usr/lib/jvm`). **Nothing was compiled and no test was run.** A naive brace-balance script was tried and discarded: it flags the same ±1 "imbalances" on the *unchanged* pre-diagnostic files, so it measures the script's string/comment stripping (apostrophes, `${…}` interpolations), not the code. It is not evidence either way.

## 2. Already present in the pre-diagnostic tree (Parts 1/3/4/5/7D)

These were in the old ZIP already and are unchanged by this work — confirmed present in the current tree by grep (line numbers are the current tree):

- **VNIC repair (key wiring + zero-findings planning):** `VnicLlmConfig.DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"` (`VnicReasoning.kt:172`); `fromSaved()` trims saved values, blank endpoint defaults to OpenAI (`:180`); `configured` is key-only (`:188`); `effectiveEndpoint` (`:191`); `SwitchableReasoner` holding one core object while the engine swaps the live delegate (`:552`); `VnicEngine.refreshReasonerFromSettings()` (`VnicEngine.kt:133`) re-reading saved settings on every `observe()` and on SAVE/CLEAR. Full record: `IMPLEMENTATION.md` in the tree.
- **Zero-config transformation:** `runIntent()` (`GhostTapApp.kt:226`); `autoConnectionFor()` (`ZeroConfig.kt:123`); `connectDiscoveredHost()` (`NetworkToolsViewModel.kt:621`); the standalone global picker removed, per-area routes only (`ZeroConfig.kt:220-238`, `GLOBAL_PICKER_ROUTE = "what_to_do"` kept as a constant so tests can assert it is gone); `IntentBox` on every function screen. Full record: `ZERO_CONFIG.md` in the tree.
- **Plain-words UI rule kept:** section/function titles and the six main-screen entry buttons keep their previous names; the diagnostic's stage labels and failure reasons are plain words.

## 3. The diagnostic as implemented (Part 2)

### New file

- `app/src/main/java/com/ghosttap/core/vnic/VnicDiagnostic.kt` — **272 lines, new.**

### The 7-stage machine

`VnicAiDiagnostic.run()` (`VnicDiagnostic.kt:153`) runs stages in order and **stops at the first failure**; later stages are reported as "not run". `VnicDiagReport.passed` is true only when all 7 stages ran and passed (`:95-96`).

| # | Stage | What it proves | Code |
|---|---|---|---|
| 1 | `KEY_PRESENT` ("Key saved") | A key is saved on this phone | `:157-168` |
| 2 | `CONFIG_RECEIVED` ("Brain picked up the settings") | The VNIC picked the saved endpoint + credential up (`refreshReasonerFromSettings()` + `remoteSummary()`, endpoint must equal the saved effective endpoint) | `:170-181` |
| 3 | `REASONER_SELECTED` ("Online helper picked") | The live reasoner delegate is a `RemoteLlmReasoner`, not the built-in helper | `:183-192` |
| 4 | `REQUEST_SENT` ("Check message sent") | The probe request actually went out through the live remote reasoner | `:194-212` |
| 5 | `HTTP_SUCCESS` ("Service answered") | A 2xx answer came back | `:213-219` |
| 6 | `RESPONSE_PARSED` ("Answer read") | The model answer was read and holds the exact check words | `:220-243` |
| 7 | `AI_REASONING_CONFIRMED` ("Brain used the answer") | The returned words flow into a real reasoning call and the reasoning output mentions them | `:245-271` |

### The probe (stages 4–6, one call)

Stages 4–6 come from **one** `RemoteLlmReasoner.probeConnection()` call through the live engine delegate — `VnicEngine` implements `VnicAiDiagnosticPort` (`VnicEngine.kt:68`, `:140-170`); `probeConnection()` delegates to the live remote reasoner (`:157-158`), or returns null when the built-in helper is live. The probe sends exactly `vnicDiagProbePayload(config.model)` (`VnicReasoning.kt:395`), i.e. for the default model:

```json
{"model": "gpt-4o-mini", "messages": [{"role": "user", "content": "Reply with exactly: GHOST_TAP_AI_CONNECTED"}], "temperature": 0}
```

(`VnicDiagnostic.kt:50-55`; a configured model in `VnicLlmConfig.model` is preserved, default `gpt-4o-mini`.) The payload goes through the **shared** `postChat()` transport (`VnicReasoning.kt:441`) — the same transport, endpoint, key, and failure classification as real reasoning. Stage 6 passes only when the trimmed content equals `GHOST_TAP_AI_CONNECTED` exactly (`VNIC_DIAG_EXPECTED_REPLY`, `VnicDiagnostic.kt:40`); empty and malformed answers fail with their own reasons.

### How stage 7 proves real consumption

Stage 7 (`VnicDiagnostic.kt:245-271`) builds the observation *"The online helper just answered the connection check. Its exact reply was: GHOST_TAP_AI_CONNECTED. What does that reply tell us?"* and feeds it into a real reasoning call through the live `SwitchableReasoner` delegate (`VnicEngine.reasonerHypotheses()`, `VnicEngine.kt:166-170` — the same object the VNIC core calls; no cycle, no research, no plan execution, no bring-up). It passes **only** when a returned hypothesis mentions `GHOST_TAP_AI_CONNECTED` **and** no fallback occurred. It fails if the built-in helper answered instead (the local fallback line starts "The online brain didn't answer"), if the fallback flag is set, or if the hypotheses never mention the check words. A local-fallback answer never mentions the check words, so it can never pass as remote success. Key safety: the diagnostic only ever sees `VnicRemoteSummary` (endpoint + model + key-presence), never the key value; no stage detail or failure reason contains a key (asserted by tests, §6).

### UI wiring

- `InvestigationsScreen.kt`: "CHECK THE AI CONNECTION" button + stages panel in the `AiSection`, right after the SAVE/CLEAR FlowRow (`:952-991`); each stage shows ✓/✗ with its plain-words detail, unrun stages show "not run". Thinking Engine copy corrected so the fallback is stated in the plan, never silent (`:906-910`). The key field is now masked: `visualTransformation = PasswordVisualTransformation()` (`:937`; import `:31`).
- `IntelViewModel.kt`: `aiDiagnosticReport` (`:1253`), `aiDiagnosticRunning` (`:1257`), `runAiDiagnostic()` (`:1267`) — runs `VnicAiDiagnostic(vnicEngine).run()` on `Dispatchers.IO` (`:1273`); a failure to even start the engine produces a one-stage KEY_PRESENT failure report (`:1277-1285`), never a crash.

## 4. Gap fixes

### Gap 1 — result → reasoning return path (DONE)

- `VnicReasoning.kt:48-57` — `VnicReasoningProvider.noteResult(capabilityId, outcome)` added, default no-op returning `emptyList()`, so existing providers keep working.
- `VnicReasoning.kt:148-158` — `LocalRuleReasoner.noteResult`: rule-based next-step per execution state (VERIFIED/CONTROLLABLE → build on the result; CONNECTED → try reading; ATTEMPTED → check the device is still there; FAILED → try a different check).
- `VnicReasoning.kt:269-282` — `RemoteLlmReasoner.noteResult`: the execution result goes back through the model via the same `complete()` call; on remote failure the built-in helper's evaluation is used and labelled, like the hypotheses fallback.
- `VnicReasoning.kt:573-574` — `SwitchableReasoner` delegates `noteResult` to the live delegate.
- `VnicCore.kt:672-706` — new `EVALUATE` block in `process()`, after ACT: the executed capability id + outcome (tracked at `:546-548`, `:630-631`, `:638`, `:656`) go back through `reasoner.noteResult()`; the evaluation is recorded in the phases, in `learned`, in the cycle result, and as an episodic memory (`:689-703`). Exposed as `VnicCycleResult.resultEvaluation` (`:85-86`, wired at `:753`). The scalar reward / mesh feedback in LEARN below is untouched.

### Gap 3 — empty vs malformed answers distinguished (DONE)

- `VnicReasoning.kt:507-519` — `parseChatContentDetailed()` returns `VnicChatContent.Text` / `.Empty` / `.Malformed` (bad JSON / missing choices / missing message / non-string content = Malformed; valid-but-blank content = Empty).
- `VnicReasoning.kt:356-390` — `complete()` now distinguishes them: empty → `lastFailureReason = "Online helper answered but said nothing."`; malformed → `"Online helper answered, but the answer was in a shape Ghost Tap couldn't read."` Transport refactored into the shared `postChat()` (`:441`); the old `parseChatContent()` (`:521-525`) is kept as a thin wrapper over the detailed form, so existing callers behave as before.

### Gap 4 — failure reasons classified, never lumped or silent (DONE)

- `Reasoning.kt:541-548` (old investigations path) — shared `httpFailureReason()`: 401/403 → `KEY_REJECTED`, 429 → `TOO_BUSY`, 5xx → `SERVER_ERROR`, else `SERVER_REFUSED`; used by all four POST methods (`postBackground`, `postDraft`, `postScriptDraft`, `postRefine` — non-2xx branches at `:504-508`, `:616-620`, `:711-715`, `:836-840`).
- `Reasoning.kt:557-563` — `classifyFailure` no longer maps unexpected exceptions to `EMPTY_ANSWER` (now `UNREADABLE_ANSWER`); `postDraft`/`postScriptDraft`/`postRefine` now set `lastFailureReason` on every failure path (exception catches at `:580`, `:661`, `:684`, `:772`, `:794`, `:896`; unreadable/empty bodies at `:516-518`, `:654-658`, `:717`, `:756`, `:892-896`).
- `VnicReasoning.kt:478-486` — the VNIC-side `httpFailureReason()` (same mapping, plain-words strings) used by `complete()` and `probeConnection()`; `classifyExchangeFailure()` (`:487-495`) for transport failures.
- `LiveTarget.kt:67-74` — three additive enum entries (`TOO_BUSY`, `SERVER_ERROR`, `UNREADABLE_ANSWER`) with plain-words lines (`:87-94`). Additive only; existing entries untouched.

### Gap 5 — honest status/copy (DONE)

- `Reasoning.kt:419-422` — `statusLine` now `"Remote AI set up (gpt-4o-mini via your endpoint)"` — "set up", not "connected"; configured is not connected until a call succeeds (comment `:418-420`).
- `VnicReasoning.kt:241-245,254-256` — a failed VNIC `hypotheses()` call now sets `lastPlanFellBackToLocal` (and resets it at the start), so a caller reading the flag after the call sees the fallback honestly.
- Thinking Engine copy corrected (`InvestigationsScreen.kt:906-910`): "if the service ever fails, Ghost Tap goes back to the built-in helper and says so in the plan — it never pretends the online helper answered."

### Gap 2 — NOT done (known limitation)

The two parallel `RemoteLlmReasoner` classes were **intentionally left unmerged**: `VnicReasoning.kt:203` (VNIC path) and `Reasoning.kt:397` (older investigations path) still exist side by side. The diagnostic and the gap fixes touch both paths' failure handling but do not unify them. Any future merge must keep both call sites' behaviour (the VNIC `complete()`/`probeConnection()`/`postChat()` shape and the older path's `postBackground`/`postDraft`/`postScriptDraft`/`postRefine` shape).

### Known limitation — key storage (pre-existing, NOT changed by this work)

The saved key lives in **plaintext SharedPreferences**: `IntelSettings` (`Reasoning.kt:997-1009`) — `PREFS = "intel_settings"`, `getSharedPreferences(PREFS, Context.MODE_PRIVATE)`, `getApiKey()` / `putString(KEY_API_KEY, …)` with no encryption. This predates the diagnostic (present in the old tree) and this work does not change it. The diagnostic itself never exposes the key value — but the key at rest is unencrypted on the phone. Flagged, not fixed here.

## 5. Packaging

- **Source ZIP (REPLACED, same filename):** `~/workspace/your_files/ghosttap-zero-config.zip`
- **SHA256:** `982677dcde8e4b44bed244e36259d21c032f4715d6e1993456355f0e8543f0bc`
- **Size:** 605,967 bytes (previous ZIP: 605,522 bytes, SHA256 `a823ba885a99ccf0a75203577bbd637eb04f189be7e99d43a6aacdca7a650a53`)
- **Entries:** 152 files (previous: 149). Excluded: `.gradle/` build scratch only — the same exclusion as the previous packaging.
- **Verified:** unzipped to a scratch dir; `diff -r` against the working tree (excluding `.gradle/`) reports **byte-identical**. The new files (`VnicDiagnostic.kt`, `VnicDiagnosticTest.kt`, `RemoteReasonerFailureTest.kt`) and all 7 changed files are present in the ZIP with the fixed content.
- **Hosted link:** NONE yet — the upload needs the coordinator/parent step (same approval pattern as before: the previous upload attempts expired while unanswered). Do not claim a link exists.

## 6. Tests — WRITTEN, NOT RUN

41 new `@Test` methods across 2 new test files. **None ran.** This environment has no JDK/JRE (`java`/`javac`/`kotlinc` absent, no `/usr/lib/jvm`), so no compile and no test run was possible — the same blocker as the zero-config and VNIC-repair rounds. Do NOT claim any test passed.

- `app/src/test/java/com/ghosttap/core/vnic/VnicDiagnosticTest.kt` — **685 lines, 30 tests.** Fake `VnicAiDiagnosticPort` with a knob per stage; covers: the probe payload is exactly the specified request; end-to-end pass with the fake remote; each stage failing in turn (no key; local reasoner; unconfigured remote; probe never sent / null probe; HTTP 401/429/500; malformed / empty / wrong-content answers; stage 7 failing when the brain doesn't mention the check words, when the local helper answers instead, and when the fallback flag is set even without the status line); `parseChatContentDetailed` distinguishing malformed from empty (and the old `parseChatContent` keeping its behaviour); `hypotheses` failure setting the fallback flag; Gap 1 (verified/failed results reach the reasoner and derive next steps; `store_context` runs no evaluation; local/remote/`SwitchableReasoner` `noteResult` behaviour); probe tests running the REAL `RemoteLlmReasoner.postChat` against a LOCAL loopback stub server (127.0.0.1, ephemeral port), gated on loopback TCP working (the test header states this sandbox black-holes JVM loopback) — that proves the real transport + exact probe bytes, NOT a live call to OpenAI. No key-leak assertions: no stage detail or overall line contains the stub key. The header states explicitly that a live call needs the user's saved key and his phone (live Test A).
- `app/src/test/java/com/ghosttap/core/RemoteReasonerFailureTest.kt` — **256 lines, 11 tests.** Gap 4 + Gap 5a on the old path, against a local loopback stub server: 429 → `TOO_BUSY`, 500 → `SERVER_ERROR`, 401 stays `KEY_REJECTED`, unreachable is not `EMPTY_ANSWER`, garbage body is `UNREADABLE_ANSWER` not empty, empty content stays `EMPTY_ANSWER`; `postDraft`/`postScriptDraft`/`postRefine` record the failure reason on 500/429; status line does not claim "connected". Header honesty: `"stub-test-key"` strings are dummy values; HTTP tests run the real reasoner against a local stub, not a live call; 200-body tests are additionally gated on `org.json` actually working (on plain JVM unit tests `android.jar`'s `org.json` is a stub that throws, so those tests Assume out here and run on device/CI instead); non-2xx tests never touch `org.json` and run anywhere loopback works.
- Existing tests were NOT edited. Compatibility was checked by reading only (same method as the VNIC-repair round): the `noteResult` default is a no-op so existing providers keep working; the `EVALUATE` block only runs when something executed; `parseChatContent` keeps its old return contract (`Text` → text, `Empty` → `""`, `Malformed` → null); the `LiveTarget.kt` enum additions are additive. That is a reading check, not a test run.

## 7. What is verified and what is NOT verified

**Verified (this pass):**
- The diff vs the pre-diagnostic tree is confined to the diagnostic + the proven gaps: 3 new files, 7 changed files, 0 removed; **+1,714 / −67 lines** (changed files +501/−67; new files +1,213/−0). It does not sprawl beyond the diagnostic + Gaps 1/3/4/5.
- The stage machine, probe payload, stage-7 consumption proof, `noteResult` wiring, failure distinctions, and status/copy fixes are present at the cited lines and match the claimed behaviour on reading.
- Package identity (`com.ghosttap` v1.25/code 28) and permissions unchanged; no secrets in the changed files; Gap 2 recorded as not done.

**NOT verified (do not claim):**
- **No compile.** No JDK here. AndroidForge CI is the next proof step; if it goes red, the failure is a compile error in this change set.
- **No test run.** 41 tests written, 0 run (see §6).
- **No live Test A/B.** The diagnostic has never talked to a real AI service: it needs Justin's saved 'Ghost' key + his phone. The field test is the proof — a green diagnostic on his phone (all 7 stages passing, stage 7 mentioning the check words) is the first real evidence the key reaches the engine and the answer flows through real reasoning.
- **No on-device proof** for the UI (button, stages panel, masked key field) or the EVALUATE path in a live cycle.
- **No `VnicEngine` test exists** (it needs Android `Application`/Room); the `VnicAiDiagnosticPort` implementation was verified by code read only.

## 8. Diff summary (vs pre-diagnostic tree, branch head 5367197c)

| File | Change |
|---|---|
| `app/src/main/java/com/ghosttap/core/vnic/VnicDiagnostic.kt` | **NEW, +272** — 7-stage diagnostic, probe payload, port interface |
| `app/src/test/java/com/ghosttap/core/vnic/VnicDiagnosticTest.kt` | **NEW, +685** — 30 tests |
| `app/src/test/java/com/ghosttap/core/RemoteReasonerFailureTest.kt` | **NEW, +256** — 11 tests |
| `app/src/main/java/com/ghosttap/core/vnic/VnicReasoning.kt` | +239 / −39 — `noteResult` (interface + local + remote + switchable), `complete()` rewrite on shared `postChat()`, `probeConnection()`, `parseChatContentDetailed()`, `httpFailureReason()`/`classifyExchangeFailure()`, `remoteSummary`, hypotheses fallback flag |
| `app/src/main/java/com/ghosttap/core/Reasoning.kt` | +79 / −22 — Gap 4 shared `httpFailureReason()`, `classifyFailure` → `UNREADABLE_ANSWER`, `lastFailureReason` on every failure path, Gap 5a status line |
| `app/src/main/java/com/ghosttap/core/vnic/VnicCore.kt` | +45 / −0 — `resultEvaluation` field, EVALUATE block after ACT |
| `app/src/main/java/com/ghosttap/IntelViewModel.kt` | +44 / −0 — `aiDiagnosticReport`, `aiDiagnosticRunning`, `runAiDiagnostic()` |
| `app/src/main/java/com/ghosttap/ui/InvestigationsScreen.kt` | +47 / −2 — CHECK THE AI CONNECTION button + stages panel, masked key field, Thinking Engine copy fix |
| `app/src/main/java/com/ghosttap/core/vnic/VnicEngine.kt` | +34 / −3 — implements `VnicAiDiagnosticPort` (saved config, live delegate, remote summary, probe, hypotheses) |
| `app/src/main/java/com/ghosttap/core/investigate/LiveTarget.kt` | +13 / −1 — `TOO_BUSY`, `SERVER_ERROR`, `UNREADABLE_ANSWER` + plain-words lines |
| **Total** | **10 files, +1,714 / −67** |

## 9. Branch + commit

- Branch: `muse/ghosttap-zero-config` — head verified before pushing: `5367197caeee0047aa5c352eef300caeaee974bb` (via `list_commits`; matches the expected head).
- Push staging: `~/workspace/ghosttap/zero-config-push/` — `ghosttap-zero-config/` (full tree, excluding `.gradle/`) + `AI-DIAGNOSTIC-REPORT.md` and updated `ZERO-CONFIG-REPORT.md` at root. Excludes `gradle/wrapper/gradle-wrapper.jar` (binary — the snapshot-push procedure rejects binary content; the ZIP contains the complete tree including the jar).
- Manifest: `/tmp/zc-diag-push-manifest` (NUL-delimited paths; ephemeral — regenerate from the staging dir if the VM restarts).
- Commit: **(filled in by the packaging agent after the push — see below; if the push approval expired, the exact retry state is recorded instead and no hash is claimed).**
- Main head re-verified after the push: still `a2640c67046191584573d533f88ff08ffdcac216` — **main untouched**. Nothing pushed to `muse/virtualesp32-chatgpt-handoff` or `muse/ghosttap-vnic-repair`.
