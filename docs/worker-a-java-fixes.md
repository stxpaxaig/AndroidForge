# Worker A — Java fixes diff report (VirtualESP32, package `dev.virtualesp32`)

- **Work tree:** `~/workspace/tmp/vesp32-fix/virtual-esp32/app/src/main/java/dev/virtualesp32/`
- **Source base:** extraction of `virtualesp32-merged-bridge9-repaired.zip` (SHA256 `024551aa…5ea0`)
- **Scope verified by diff against the pristine ZIP:** exactly **5 Java files changed**, nothing else. No C files, no `.so`, no `jniLibs/`, no manifest, no new permissions, no Ghost Tap changes.
- **Verification done here:** brace/paren balance = 0 on all 5 files; `diff -rq` vs pristine ZIP confirms only these 5 files differ; `cpp/` + `jniLibs/` have zero files modified after the base extraction. No local build or unit-test run was possible in this sandbox (Gradle cannot run here; the AndroidForge workflow is the compile test, and the user's phone is the runtime test).

## 1. `ClassicEsp32QemuBackend.java`

| Location | Change |
|---|---|
| Javadoc, lines 3–7 | **D2-note fix:** stale "No QEMU binary is bundled yet; availability is false unless the native port is supplied" replaced with the truth — the QEMU core ships as the prebuilt `libesp32_qemu_android.so` (arm64-v8a) in `jniLibs/`; availability is false only when that library cannot be loaded. |
| Lines 12–13 | New field `sessionStateMissing` — latched once a `nativeGetSessionState` call proves the loaded `.so` has no such symbol. |
| Line 43 | **Fixed JNI contract:** declared `private static native int nativeGetSessionState();` (Worker B implements the native side with exactly this name). |
| Lines 49–54 | New `static int sessionState()` — returns the native value, or `-1` when the library is not loaded. Also returns `-1` (and latches `sessionStateMissing`) when the loaded `.so` lacks the symbol (`UnsatisfiedLinkError`), and `-1` on any other `Throwable`. Never throws. |
| Line 60 | New `static boolean sessionStateAvailable()` — true only when the library is loaded AND the getter symbol exists. Learned on the first `sessionState()` probe (optimistically true before any probe while loaded — callers must probe via `sessionState()` first; every caller in this change does). |
| Line 65 | New `static boolean qemuAlive()` — true only when `sessionState() == 1`, per the fixed contract. Strict probe: false whenever the state cannot be probed. |

## 2. `FirmwareEmulatorRuntime.java` (full rewrite of the 26-line file; all original behaviour preserved unless noted)

| Location | Change |
|---|---|
| Class Javadoc, lines 5–29 | Documents D3 reconciliation, the `status()` strings, and the three reset semantics (D4). |
| Line 32 | New static field `appContext` (application context, set in `start()`), used to start the GPIO monitor when a `"starting"` session is promoted to `"running"` from `status()`. |
| Line 37, `status()` | Now returns `reconcileLocked()` (reconciled state) instead of the raw flag. |
| Lines 48–53, `running()` | **D3:** true only when the flag is `"running"` AND the native state is RUNNING (1). Falls back to the flag alone when the native state cannot be probed — see "D3 fallback case" below. |
| Lines 57–107, `start()` | Already-up path (lines 62–80): **D6** — re-inspects the flash file even when already running; an invalid file is logged + posted as an error event but the live session is NOT disturbed (it keeps the image it loaded). If the native session is dead under a live flag, stale objects are torn down and it falls through to a fresh start (no second session is ever started while one is STARTING/STOPPING — returns `"starting"`/`"stopping"` instead). Fresh-start path: tears down any stale UART/backend/monitor first (new `teardownStaleLocked()`), then after `backend.start()` succeeds polls the native state up to ~1 s (`pollNativeStateLocked()`, 100 ms steps) — returns `"running"` only once native state is RUNNING (or when unprobable, see fallback); returns `"starting"` (flag `"starting"`, no monitor, no `GUEST_STARTED`) if QEMU is still STARTING; throws `IllegalStateException` if the session already ended right after start. Catch block now also tears down via `teardownStaleLocked()` (previously only nulled uart/backend). |
| Lines 115–130, `reset()` | **D6 + D4:** signature changed `void` → `String`. Soft reset (same session, `qemu_system_reset_request`) — unchanged behaviour when running. When NOT running it is no longer a silent no-op: returns an error string (`"Guest reset failed: guest is not running"` / `"Guest reset failed: no QEMU backend"`), logs it to the HAL log, AND posts it as an error event (all three, so callers that ignore the return value still surface it). Returns `"ok"` on success. |
| Line 131, `stop()` | Same externally-visible behaviour (state `"stopped"`, persist, `postGuestStopped()`), now implemented via `teardownStaleLocked()` so monitor/UART/backend are all cleaned up. Keeps the flag consistent. |
| Lines 135–156, `reconcileLocked()` (new) | Flag-vs-native reconciliation. Flag `"running"`: native 1 → `"running"`; 0 → `"starting"`; 2 → `"stopping"`; 3/4/-1/unknown → latch `"error"` (once) and return `"error"`. Flag `"starting"`: native 1 → promote to `"running"` (monitor started, `GUEST_STARTED` posted); 0 → `"starting"`; 2 → `"stopping"`; dead → latch `"error"`. Unprobable native state → return the flag unchanged (D3 fallback). |
| Lines 159–166, `promoteStartingLocked()` (new) | `"starting"` → `"running"` transition: persists flag, logs, starts `GuestGpioMonitor` if absent, posts `GUEST_STARTED`. |
| Lines 169–176, `latchErrorLocked()` (new) | Latches flag to `"error"` once (single log line + single error event). Stale backend/UART/monitor objects are deliberately left in place so `stop()` can clean them up; `start()` tears them down before any fresh start. |
| Lines 178–184, `teardownStaleLocked()` (new) | Best-effort teardown of monitor/UART/backend, each step guarded by `try/catch (Throwable)`. |
| Lines 188–197, `pollNativeStateLocked()` (new) | Polls native state until it leaves STARTING (0) or the timeout elapses; returns `-1` when unprobable (callers check `sessionStateAvailable()` to tell that apart from "no session"). Blocks the calling thread (main thread in both call sites) for at most the timeout, on top of the native start's own up-to-5 s wait. |

## 3. `BridgeCommandReceiver.java`

| Location | Change |
|---|---|
| Lines 14–19, `ensureBusListener()` | **D5:** changed `private` → `public static` (+ Javadoc) so `MainActivity.onCreate` can call it. Body unchanged; still idempotent (`busListenerRegistered` guard). Existing START/RESET call sites kept (lines 130, 163). |
| Lines 147–163, `RESET_GUEST` | **D4 — confirmed:** it already was stop+start = full restart (flash re-inspected by `start()`, UART re-created, monitor restarted); error is sent and `GUEST_RESET` is NOT sent when the restart fails to reach running (existing check, kept). **Gap fixed:** the firmware-path check now runs BEFORE `stop()`, so a restart that cannot proceed (no imported firmware) no longer tears down a running guest first. Comment block documents the full-restart semantics vs the soft reset. |
| Lines 207–221, `QUERY_STATUS` | **D3:** now reads reconciled `FirmwareEmulatorRuntime.status()` instead of `running()`. JSON mapping: `"running"`→`"RUNNING"`, `"starting"`→`"STARTING"`, `"error"`→`"ERROR"`, anything else→`"STOPPED"`; `"firmware"` is `"running"` / `"starting"` / `"not loaded"` on the same basis. `cpu`/`backend` literals untouched (D9, INFO — not a defect). |

## 4. `MainActivity.java`

| Location | Change |
|---|---|
| Line 20, `onCreate()` | **D5:** `BridgeCommandReceiver.ensureBusListener(getApplicationContext())` called once at app start, so Ghost Tap gets `UART_RX` events for UI-started guests too. No `Application` class was created and no manifest change was needed. |
| Line 26, power button | **D4 (rename):** label `"Power / Reset"` → `"Power"`; `refresh()` line 57 dynamic text `"Power off / reset"` → `"Power off"`. Behaviour unchanged (power toggle + BLE stop only). Comment states it does NOT touch the QEMU guest. |
| Line 37, "Reset guest" button | **D4 + D6:** still calls `FirmwareEmulatorRuntime.reset()` — **soft-reset behaviour preserved**. Now checks the returned string: on failure shows an `AlertDialog` ("Guest reset failed" + message) instead of silently doing nothing; `refresh()` still runs either way. Comment documents it is a soft reset, not a restart. |
| Line 39, clear button | **D4 (rename):** label `"Soft reset / reboot runtime"` → `"Soft reset virtual runtime"` + comment that `hal.softReset()` only bumps boot count / restarts BLE and does NOT reset the QEMU guest. Behaviour unchanged. |

## 5. `VirtualEsp32BridgeService.java`

**Decision (D7): the service was KEPT and fixed, not deleted.** Nothing in the app starts it (only `VirtualDeviceService` is ever started), so it is effectively dead code and the receiver is the live IPC path — but it is still declared exported in the manifest, so deleting it would remove a declared IPC surface. Its error surface now matches the receiver's exactly, so it is safe to revive. This decision is recorded in the class Javadoc (lines 12–17).

| Location | Change |
|---|---|
| Lines 12–17, class Javadoc | Records the dead-code status and the D7 decision above, plus the D4 soft-reset note. |
| Lines 94–100, `buildTargetInfo()` | **D3:** uses reconciled `status()` with the same RUNNING/STARTING/ERROR/STOPPED mapping as receiver `QUERY_STATUS`. `peripherals` literals untouched (D9). |
| Lines 131–144, `ACTION_START_GUEST` | Adjacent consistency fix: now verifies the start actually reached running (previously it never checked the result); posts `"startGuest failed: runtime did not reach running state (<result>)"` on failure, mirroring the receiver. |
| Lines 153–161, `ACTION_RESET_GUEST` | **D4:** documented as soft reset (same session), NOT the receiver's full restart. Now uses `reset()`'s return value: posts `GUEST_RESET` only on `"ok"`; posts the error string on failure (previously it posted `GUEST_RESET` unconditionally, even when the reset was a silent no-op). |
| Lines 176–195, `ACTION_WRITE_GPIO` | **D7:** error strings now identical to the receiver: `"WRITE_GPIO failed: guest is not running"`, `"WRITE_GPIO pin 4 failed: <msg>"`, `"WRITE_GPIO failed: GPIO <n> is not supported by this bridge"` (previously: different wording, and unsupported pins were silently ignored). |
| Lines 204–223, `ACTION_READ_GPIO` | **D7:** no longer sends a `GPIO_VALUE` event with `value=-1` for errors. Now posts explicit errors identical to the receiver (`"READ_GPIO failed: guest is not running"`, `"READ_GPIO failed: GPIO <n> is not supported by this bridge"`, `"READ_GPIO pin 2 failed: <msg>"`) and sends `GPIO_VALUE` only on a real pin-2 read. |

## D3 fallback case — exactly when the flag can still lie

`running()` / `status()` fall back to the Java flag alone (pre-fix behaviour) **only when the native session state cannot be probed at all**:

1. **Library not loaded** (`ClassicEsp32QemuBackend.available()` false). Near-unreachable in practice: the flag can only become `"running"` via `start()`, which requires the library first. Kept as a defensive branch per the task spec.
2. **The loaded `.so` has no `nativeGetSessionState` symbol — THIS IS THE LIVE CASE.** The current prebuilt `.so` (SHA256 `207d50ac…`) does not export it. Until Worker B's updated `.so` ships, `sessionState()` returns `-1`, `sessionStateAvailable()` is false after the first probe, and `running()`/`status()` behave exactly as before this fix (flag only). This is deliberate fail-open: it preserves all current functionality with the old `.so` (a fail-closed design would have broken START/GPIO for every live guest). The latch (`sessionStateMissing`) records that the symbol is absent; no crash, no error spam.
3. **Even with the native getter live, the flag can still over-claim in two ways the native session state cannot see:**
   - **Guest CPU panic/reboot inside QEMU:** native state stays RUNNING through a guest reboot (QEMU keeps running), so `running()` stays true. This fix detects QEMU-process death, NOT guest health. A guest in a crash loop still reports RUNNING.
   - **UART never connected:** `running()` does not check the UART socket. QEMU alive but UART client never connected still reports running (pre-existing, investigation §5 item 7).
4. **Not a lie — the `"starting"` window:** after `start()`, if QEMU is still STARTING, `status()` reports `"starting"` and `running()` is false until the native session reaches RUNNING (promoted by `status()` or a later `START`). `START_GUEST`/`RESET_GUEST` therefore report failure with `(starting)` in that window; a retry once QEMU is up succeeds.

## Deliberately NOT changed, and why

- **No C files, no `.so`, no `jniLibs/`, no manifest, no new permissions, no Ghost Tap changes** — per task scope; Worker B owns the native side.
- **No `Application` class created** — D5 is satisfied via `MainActivity.onCreate`; an `Application` subclass would have required a manifest change for no benefit.
- **`QUERY_STATUS` / `buildTargetInfo()` `cpu`/`backend`/`peripherals` literals left as-is** — D9 is INFO (claims by construction, not measurements); changing them was out of scope.
- **Bridge service NOT deleted** — see D7 decision above.
- **UI "Reset guest" behaviour preserved as a soft reset** — per task constraint; only error visibility was added.
- **`start()` early-return replaced by re-inspection (D6 option A), not a documented early return** — when already running and the flash file is now invalid, the failure is logged + posted as an error event, but the live session is deliberately NOT disturbed (it keeps the image it loaded).
- **Pre-existing `uart.start()` failure hole left alone:** in `start()`, if `EmulatorUartBridge.start()` throws, the exception propagates outside the `try` (as in the original code) and the half-built bridge is left for `stop()` / the next `start()`'s teardown to clean up. Not made worse by this change.
- **SharedPreferences key `"firmwareEmulatorState"` still written but never read** — pre-existing dead telemetry, out of scope.
- **No local build/test run** — Gradle cannot run in this sandbox; compile verification is the AndroidForge workflow build, runtime verification is the field test on Justin's phone.
- **Bug caught during verification:** my first MainActivity edit placed `//` comments mid-line before further statements on the same dense one-liner lines, which would have commented out live code (`ble=…`, the Stop-guest listener, the clear-button listener). All three were fixed to `/* … */` block comments and the diff re-verified.
