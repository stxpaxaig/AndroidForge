# Ghost Tap Zero-Config Build — Packaging Report

**Date:** 2026-11-06 (America/Los_Angeles)
**Working tree:** `~/workspace/ghosttap/zero-config/` (fresh copy of the VNIC-repair tree)
**Base:** `~/workspace/ghosttap/vnic-repair/`
**Report:** `~/workspace/goals/ghost-tap-android-app-build/ZERO-CONFIG-REPORT.md`

## 1. The concept, as implemented

Flow in EVERY function: **DISCOVER → SELECT → AUTO-CONNECT → COMMANDS.**

- Discover in each function, tap a device, Ghost Tap connects it to a controllable state on its own — no config, never type host / port / address.
- A pre-configured plain-words command list lives inside each function (per-area pickers + the per-device command centers that already existed).
- An intent box ("what I'm tryna do" → the app does it) sits on every function screen and on the control page.
- No standalone global "What do you want to do?" list.
- Auto-config comes from discovery in every function.
- Only Ghost Tap's own limitations apply — nothing else was added as a limitation or barrier.

## 2. The corrected directive (the user's words, WITH the NOTs)

> "YOU WILL NOT TAKE AWAY FROM WHAT THERE, YOU WILL NOT ALTER ANY FUNCTIONS AND YOU WILL NOT BUILD WITH ANY LIMITATIONS OR BARRIERS!"

The earlier message had dropped the NOTs (voice-to-text). The rule as corrected: preserve everything, alter no functions, build with no limitations except Ghost Tap's own. The build was restarted clean from a fresh copy of the VNIC-repair tree with the NOTs in.

## 3. Files changed — verified by this packaging pass

Measured by `diff -u` per file against `~/workspace/ghosttap/vnic-repair/` (re-run here, not copied from the implementation doc):

| File | + / − |
|---|---|
| `app/src/main/java/com/ghosttap/IntelViewModel.kt` | +21 / −0 |
| `app/src/main/java/com/ghosttap/NetworkToolsViewModel.kt` | +26 / −1 |
| `app/src/main/java/com/ghosttap/ui/GhostTapApp.kt` | +198 / −32 |
| `app/src/main/java/com/ghosttap/ui/InvestigationsScreen.kt` | +16 / −0 |
| `app/src/main/java/com/ghosttap/ui/OperationEntryScreen.kt` | +13 / −22 |
| `app/src/main/java/com/ghosttap/ui/UsbUartScreen.kt` | +52 / −4 |
| `app/src/main/java/com/ghosttap/ui/VirtualEsp32Screen.kt` | +50 / −2 |
| `app/src/main/java/com/ghosttap/ui/VisualIntelScreen.kt` | +13 / −1 |
| `app/src/main/java/com/ghosttap/core/ZeroConfig.kt` (new) | +357 / −0 |
| `app/src/main/java/com/ghosttap/ui/IntentBox.kt` (new) | +73 / −0 |
| `app/src/test/java/com/ghosttap/core/ZeroConfigTest.kt` (new) | +287 / −0 |
| **Source total: 11 files (8 changed + 3 added)** | **+1106 / −62** |
| `ZERO_CONFIG.md` (working doc, new) | +247 / −0 |
| **Grand total: 12 files** | **+1353 / −62** |

Correction to the task brief: the brief said "7 changed" — it is **8 changed**. The eighth is `IntelViewModel.kt`, touched by the fix pass (+21/−0, manual-disconnect memory + `clearCommandAnswer()`). The earlier 994/58 figure predates the fix pass; the fix pass added +112/−4 (1106/62), and `ZERO_CONFIG.md` adds a further +247 lines of doc.

`diff -rq` against the base confirms exactly these files differ — nothing else in the source tree changed. No files were removed. Package identity untouched (`com.ghosttap`, versionCode 28, versionName 1.25); no new permissions; no build-config change.

## 4. Global-picker removal + override note

**OVERRIDE RECORDED (explicit reversal):** the earlier "home global button untouched" rule is REVERSED by this directive (item 4). The standalone "What do you want to do?" list is REMOVED:

- The `composable("what_to_do")` route and the home-screen button are gone.
- `OperationEntryScreen` now requires a non-null `OperationArea` (the all-areas branch is deleted); its title is now `area.label`.
- Its content lives ONLY inside each function: the per-area routes `what_to_do/{area}` (buttons kept on all six tool screens + Investigations), the per-device command centers on `LiveTargetCard`, and the new intent box.
- An unknown area name now navigates back instead of falling back to the removed global list.

Verified in this pass: no `composable("what_to_do")` remains; the only `what_to_do` references left are a code comment in `GhostTapApp.kt`, a KDoc note in `OperationEntryScreen.kt`, and the `GLOBAL_PICKER_ROUTE = "what_to_do"` constant in `ZeroConfig.kt` (kept so code/tests can assert the route is gone). No dead references to the removed route.

## 5. Where auto-config replaces manual config

| Where | Before | Now (from discovery) |
|---|---|---|
| Network live host tap | Tap host → tap a port → tap Connect and send | Tap host → port scan runs, address + best open port pre-filled, HELLO sent automatically (`connectDiscoveredHost`) |
| Net LiveTargetCard Connect / Control | Port scan only | Same auto-connect as above |
| BLE device page | "Step 1: Tap Connect" | Opening the page auto-runs the existing bring-up (connect → handshake check → control); Connect button remains as manual retry |
| USB board | CHECK AGAIN, then CONNECT | Permitted plugged-in board connects by itself, once per board |
| Virtual ESP32 | CONNECT button | Opening the screen connects to the bridge by itself, once per entry |
| Wi-Fi open network | (already direct) | Unchanged — joins with no typing |
| Wi-Fi from photo / QR | Password typed | Unchanged — photographed password joins without typing (pre-existing) |
| Every function screen + control page | No intent input | Intent box routes plain words to a real capability and runs it |

Supporting code: `NetworkToolsViewModel.connectDiscoveredHost()` scans ports, then pre-fills TCP host + port from the scan (`preferredPort` over ports that actually answered) and sends the same HELLO the port tap already sent. A host with nothing open reports that and sends nothing — no port is ever guessed. `ZeroConfig.preferredPort()` picks from ports a scan actually observed open (80, 443, 8080, 1883, 9000, 22, 23, 21, else first); null when nothing answered.

## 6. Every manual fallback, with its justification

1. **Secured Wi-Fi password** (`NetworkToolsScreen` password field, `VisualIntelScreen` Wi-Fi action): a password cannot be observed by any scan, and Android cannot join a secured network without it. It is the ONLY typed connection detail left. Open networks need nothing; a password the camera already read joins without typing.
2. **USB permission** (ALLOW USB ACCESS button): Android grants USB access only through its own system prompt; the app cannot bypass it. A board without permission keeps the button; it auto-connects the moment permission lands.
3. **Classic Bluetooth text link** (TargetScreen "Connect (text link)" / "Try text link"): GATT vs SPP are two different transports and the choice between them is real, not config. Auto bring-up covers BLE (GATT) only.
4. **Virtual ESP32 guest START** (START button): which software runs (a loaded file vs built-in test software) is a real choice, not a connection detail. The bridge CONNECT is auto; START stays one tap.
5. **Manual tool boxes** (Ping / TCP / UDP / Port scan / HTTP fields, NFC write-text / emulate text, USB/UART message boxes, firmware file pick): these are for a device discovery never saw, or for the message content itself — not connection config for a found device. They stay as fallbacks, clearly labelled as such on the Network screen. No screen demands typed host/port/address before connecting to a DISCOVERED device.
6. **Bluetooth pairing code**: unchanged — when a device demands pairing, the phone's own pairing prompt asks for the code (bring-up fails honestly with "pair it in the phone's Bluetooth settings"). No code path asks the user to type a pairing code into Ghost Tap.

## 7. Intent routing

- `runIntent(text)` in `GhostTapApp.kt` routes typed intent through `IntentRouter` over `OperationRegistry.all()` and runs the match through the existing `OperationRunner`; the outcome is reported in plain words via `intelVm.noteCommandResult`. No match → an honest "couldn't match that" answer, nothing runs.
- `IntentRouter` (in `ZeroConfig.kt`, pure Kotlin, stdlib only): plain-words intent → real registered capability ids only (id words ×3, title ×2, description ×1, synonym + prefix matching, "wi-fi" normalised). It can only return ids from the supplied list; no match → null. No invented ids.
- `IntentBox` (shared composable, "WHAT ARE YOU TRYING TO DO?"): text field + "Do it" button + one plain-words outcome line. It matches nothing and runs nothing itself — it hands words to `onIntent`. Null `onIntent` hides it. Not a chat interface.
- Intent box present on 8 surfaces (verified by grep in this pass): home + TargetScreen (control page) + NetworkToolsScreen + NfcToolsScreen in `GhostTapApp.kt`, plus `UsbUartScreen`, `VirtualEsp32Screen`, `VisualIntelScreen`, `InvestigationsScreen`.

## 8. Test results — 16 written, 0 run

- 16 new unit tests in `app/src/test/java/com/ghosttap/core/ZeroConfigTest.kt` (verified: `grep -c @Test` = 16), covering: discover → select → auto-connect pre-fill (network host, open/secured Wi-Fi, BLE/USB/NFC/virtual); no-guessed-port; intent routing to real capability ids (4 intents) + unknown intent → null + routing never invents ids; global picker route removed + per-area routes present; per-function picker content per area; no-config regression. Capability refs copy REAL registered operations (id/title/description/area verified against `OperationAreaA.kt`).
- **NOT RUN. Do NOT claim any test passed.** `./gradlew :app:testDebugUnitTest --tests "com.ghosttap.core.ZeroConfigTest"` failed before any test executed — this environment has NO Java at all (`java: command not found`, no `/usr/lib/jvm`, no JDK, no JRE, no kotlinc anywhere on disk). Gradle fails on missing JAVA_HOME.
- Correction: an earlier "18 tests" claim was wrong. It is 16.

## 9. Verification summary (independent read-only verification)

Re-checked in this packaging pass, by reading (no source file modified):

- **Diff exact:** `diff -rq` vs the VNIC-repair base shows exactly the 12 files in §3 — nothing else differs; base untouched.
- **Global picker gone** with no dead references (§4).
- **No-config flows pass** by code reading (auto-connect paths in §5; `preferredPort` never guesses).
- **Intent box routes only to real registered ids** (`IntentRouter` can only return ids from the supplied list; no match → null).
- **Regression pass:** no capability, function, screen, or workflow removed or altered except the standalone global picker (directive item 4) and the manual-config steps replaced by auto-config. The VNIC repair is untouched and built on, not redone (saved-key wiring, zero-findings planning, honest remote/local labels reused as-is). Plain-words UI rule kept; section/function titles and the six main-screen entry buttons keep their previous names.
- **Plain-words + identity + permissions unchanged** per the implementation doc (package `com.ghosttap`, versionCode 28, versionName 1.25, no new permissions, no build-config change).
- **No secrets:** grep scan of the changed code found no key material (one false positive: the operation id string `usb.ask-permission-again`). No API key exists in this environment; none was invented, hardcoded, logged, or written to any file.
- **Fix pass re-verified** (see §10).

## 10. The fix pass

Two defects found after the first implementation, both fixed in the tree:

**(a) Auto bring-up re-armed on fresh navigation after manual Disconnect — FIXED.**
The first build's guard (`autoBringUpFor` / per-screen `remember`) reset every time the screen was re-opened, so disconnecting a device and re-opening its page reconnected it. Fixed by persisting the manual-disconnect choice in `IntelViewModel`: `manuallyDisconnectedIds` (a `mutableStateOf(setOf<String>())`) plus `noteManualDisconnect(id)` / `clearManualDisconnect(id)` / `wasManuallyDisconnected(id)` methods. All three `LaunchedEffect` guards check it before auto-connecting — `TargetScreen` (BLE), `VirtualEsp32Screen`, `UsbUartScreen` — and each clears the entry the moment the link is up again by any path, so a later drop that was NOT the user's choice still auto-reconnects. False comments claiming the old behaviour were corrected. Verified in this pass: all three guards contain `intelVm?.wasManuallyDisconnected(...) != true`, and the three methods exist in `IntelViewModel.kt:239-249`.

**Scoping note:** the two SPP "Disconnect text link" buttons were NOT wired to record a manual disconnect. There is no auto SPP connect to block, and `target.connected` is GATT-only — recording SPP under the BLE id would wrongly block GATT auto bring-up. That is a possible follow-up, not part of this defect.

**(b) Stale shared `lastCommandAnswer` — FIXED (cosmetic).**
The `IntentBox` answer line shows `intelVm.lastCommandAnswer`, which is shared across screens/cards — an answer from a command tapped on another screen could show in the box until the next intent ran. Fixed by `clearCommandAnswer()` on entry at all 8 IntentBox surfaces (`LaunchedEffect(Unit)` in `GhostTapApp.kt` ×4, `InvestigationsScreen`, `VirtualEsp32Screen`, `UsbUartScreen`, `VisualIntelScreen` — verified by grep in this pass, 8 call sites). `noteCommandResult()` / `clearCommandAnswer()` live in `IntelViewModel.kt:222-228`.

## 11. What is NOT verified — the field-test gate, stated plainly

- **No compile.** No JDK here. AndroidForge CI is the next proof step; if it goes red, the failure is a compile error in this change set, not a device result.
- **No test execution.** 16 tests written, 0 run.
- **No on-device proof.** BLE auto bring-up on page entry, USB auto-connect, Virtual ESP32 auto-connect, network auto HELLO, intent routing end-to-end, and the manual-disconnect guard all need Justin's phone + field test with his saved Ghost key. Nothing here is proven until that field test passes.
- **Intent router is word-overlap matching, not a model call.** A remote-AI intent parse was NOT built — no key exists here and the router must work offline with the app's own rules; the VNIC / `RemoteLlmReasoner` path still handles live-target reasoning.
- `runIntent` runs operations that may need a connected target — those fail honestly via preparation when none is connected, with the reason shown as the intent answer.

## 12. ZIP facts

- Path: `~/workspace/your_files/ghosttap-zero-config.zip`
- SHA256: `a823ba885a99ccf0a75203577bbd637eb04f189be7e99d43a6aacdca7a650a53` (repackaged after the §16 repair; supersedes `e9536c6991ebd3eaa0b8e840def07162340089285d3f3de6ff30885e6920a751`)
- Size: 605,522 bytes
- Entries: 184 (149 files + directory entries, as reported by `unzip -l`)
- Verified: unzipped cleanly to scratch (`/tmp/zc-verify`); all changed/added source files, `ZERO_CONFIG.md`, and `gradle/wrapper/gradle-wrapper.jar` present; unzipped tree byte-identical to the working tree (see exclusion note below).
- **Exclusion note — `.gradle/`:** the ZIP contains the complete source tree EXCEPT `.gradle/` (271 MB, 269 files — the Gradle 8.7 distribution downloaded as a side effect of the failed `./gradlew` run that proved there is no JDK here). That is build scratch, not source: the VNIC-repair ZIP precedent likewise shipped a clean tree with no `.gradle/`. The tree itself was NOT modified — `.gradle/` is still on disk in the working tree; it was excluded from the ZIP only. (Note: `ZERO_CONFIG.md` states the `.gradle` directory "was removed afterwards" — it was not; it re-appeared at 00:54, presumably from the fix-pass verification attempt. The exclusion stands regardless.)
- Layout matches the VNIC precedent: files at the archive root (no top-level folder).

## 13. Hosted link — BLOCKED (upload failed twice)

- Hosted link: **NONE — upload blocked.** Two `remote-storage upload-file` attempts both failed with `approval_expired` (egress approval card timed out unanswered, ~30 min each; approval IDs `connector-attempt-1f2e18fa8fa34f65b7e951ef608859f21733b1901d587a7658cc1173d0e79981` and `connector-attempt-d250d36ccf9f43d312d659965a98bbf31050940b3b752ccfeb0239a853715a02`). Nothing was uploaded. No retry beyond the single instructed retry — do NOT spam retries.
- Expiry: not yet issued.
- **Retry state (ready, nothing to redo):** ZIP is at `~/workspace/your_files/ghosttap-zero-config.zip`, SHA256 `e9536c6991ebd3eaa0b8e840def07162340089285d3f3de6ff30885e6920a751`, 605,512 bytes. Retry once an approver is present to tap:
  `/opt/hatch/bin/remote-storage upload-file --path ~/workspace/your_files/ghosttap-zero-config.zip`
  Then record the returned URL + `expires_at` here (§13).

## 14. Branch + commit

- Branch: `muse/ghosttap-zero-config` — **CREATED** from `main` head `a2640c67046191584573d533f88ff08ffdcac216`.
- Commit: **`cae72f42aac5b842b0676ab407eb64d12b187e1d`** — message "Ghost Tap zero-config autonomous build (verified, tests not run)", 2026-11-07T02:24:46Z. Read back and verified via `list_commits` on the branch (head SHA + message match). URL: https://github.com/stxpaxaig/AndroidForge/commit/cae72f42aac5b842b0676ab407eb64d12b187e1d
- Main head re-verified after the push: still `a2640c67046191584573d533f88ff08ffdcac216` — **main untouched**. Nothing was pushed to `muse/virtualesp32-chatgpt-handoff` or `muse/ghosttap-vnic-repair`.
- Branch creation: first attempt was interrupted by a service restart (read-back: branch still 404 — it had NOT applied); second attempt succeeded (approval granted). Push: first attempt failed only because the service restart had wiped `/tmp/zc-push-manifest` — manifest regenerated from the staging dir (149 paths, 10,638 bytes) and the push then succeeded on the next attempt.
- Spot-check: `ZERO-CONFIG-REPORT.md` downloads from the branch (SHA 8163f9ac5c2c39d5d79b8a8750772a44c4ba5a5f).
- Sync record: `~/workspace/ghosttap/zero-config-push.muse-github-snapshot-sync.json` (1 entry, 149 paths, commit `cae72f42aac5b842b0676ab407eb64d12b187e1d`).
- **Push staging (kept):** `~/workspace/ghosttap/zero-config-push/` — `ghosttap-zero-config/` (full zero-config tree) + `ZERO-CONFIG-REPORT.md` at root. Excludes `gradle/wrapper/gradle-wrapper.jar` (binary — the snapshot-push procedure rejects binary content; the ZIP still contains the complete tree including the jar).
- **Manifest:** `/tmp/zc-push-manifest` (NUL-delimited paths; ephemeral — regenerate from the staging dir if the VM restarts: `cd ~/workspace/ghosttap/zero-config-push && find . -type f | sed 's|^\./||' | sort` piped to NUL-join with a trailing NUL).

## 15. Status summary

- Code: complete per the corrected directive (DISCOVER → SELECT → AUTO-CONNECT → COMMANDS in every function; global picker removed; intent box on every function screen + control page). Fix pass applied (manual-disconnect memory + stale-answer clear). Repair pass applied after the red build — see §16.
- Tests: 16 written, 0 run. No compile. No on-device proof.
- ZIP: ready and verified (§12, repackaged after the §16 repair). Hosted link: BLOCKED on approval (§13) — the only open delivery item. Branch: CREATED + PUSHED (§14); repair commit pending (§16).
- The AndroidForge build (parent's step) waits on the hosted link. Main untouched.

## 16. Repair — AndroidForge run 42329337124 went RED (repair attempt 1 of 2)

- Run: **42329337124** ("Build Android APK #152", job 128272001942, step "Build Ghost Tap", task `:app:compileDebugKotlin` FAILED). Exactly 3 `e:` lines in the full log, all repaired in one pass. No other errors.
- **Error 1 — `core/vnic/VnicReasoning.kt:160:59`:** "Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type String?" — `effectiveEndpoint` called `.ifBlank` directly on `endpoint?.trim()` (a nullable `String?`). (Note: the log's warning text mentioned a deprecated nullable `ipV4` — no `ipV4` exists anywhere in this tree; the real culprit at 160:59 is the `.ifBlank` call on the nullable trim result.) **Fix:** `endpoint?.trim()?.ifBlank { DEFAULT_ENDPOINT } ?: DEFAULT_ENDPOINT` — one added `?`. Semantics unchanged: null → OpenAI default; blank → OpenAI default; non-blank → trimmed value.
- **Errors 2 + 3 — `ui/UsbUartScreen.kt:199:32` and `:199:46`:** "No value passed for parameter 'onClick'" + "Type mismatch: inferred type is () -> Unit but Boolean was expected" — the DISCONNECT button passed its action as a trailing lambda to `UartButton(text, onClick, highlighted = false)`, so the lambda bound to the last parameter (`highlighted: Boolean`). **Fix:** the lambda is now passed as the named `onClick` argument: `UartButton("DISCONNECT", onClick = { … })`. Behavior unchanged — it still records the manual disconnect, then disconnects.
- **Diff vs pre-repair tree: exactly 2 files, 3 lines** (VnicReasoning.kt:160, UsbUartScreen.kt:199 + closing `})`). Verified by diff against a pre-repair baseline copy. No function changes, no behavior changes beyond making the code compile as intended; nothing taken away, nothing altered.
- **Sweeps (same error classes, whole tree):** (a) nullable-receiver member calls — the only unsafe `?.foo().bar(` chain in the tree was line 160; every other hit uses `.orEmpty()` or safe calls (`?.`). Clean. (b) trailing-lambda / missing-onClick sites — `UartButton` is the only helper with a Boolean last parameter and line 199 was its only trailing-lambda call; all other `UartButton` sites pass `onClick` positionally or by name. `TerminalButton` has two overloads; all trailing-lambda calls resolve to the 2-parameter overload (`onClick` last) and all `enabled = …` calls pass `onClick` by name. Clean. No further fixes needed.
- **NOT verified:** no JDK exists here (no Java at all) — no compile or test run was possible, and none was attempted. The repair is hand-checked only. AndroidForge is the proof: the parent re-triggers the build from the new ZIP link.
- **ZIP:** repackaged — SHA256 `a823ba885a99ccf0a75203577bbd637eb04f189be7e99d43a6aacdca7a650a53`, 605,522 bytes, 184 entries; unzipped tree byte-identical to the working tree (excluding `.gradle/`); fixed lines confirmed inside the ZIP (§12).
- **Branch commit:** repair commit to `muse/ghosttap-zero-config` — pending (recorded here once pushed).
