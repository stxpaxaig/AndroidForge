# VirtualESP32 — Corrected Build Report (engineering draft)

**Date:** 2026-11-06 ~01:15 PST
**Branch:** `muse/virtualesp32-chatgpt-handoff` (repo `github.com/stxpaxaig/AndroidForge`) — this report is the draft for the parent to commit there. Main untouched.
**Base:** `virtualesp32-merged-bridge9-repaired.zip` (25,802,041 bytes, SHA256 `024551aab2dfe64c6c721c9f7881ae91dc0d894f79b79322d5061314985b5ea0`) — the repaired source that produced installed APK SHA256 `c3516f8b4fb144c87e726f3e3893307cd5876ff65d45491c880ff4ab8baffe24` (AndroidForge run 42130846133).
**Scope:** VirtualESP32 only. Ghost Tap NOT modified. Nothing installed or deployed to the user's phone.

## 0. Build result — CORRECTED APK BUILT AND VERIFIED (2026-11-05 ~17:28 PST)

- **Workflow:** AndroidForge run **42143704645** ("Build Android APK #151"), triggered 17:25 PST, **completed success** 17:26 PST.
- **Artifact:** GHOST-TAP-APK id **13469625826**, **26,272,635 bytes** (expires 2027-02-04) — ~26 MB, the VirtualESP32 size (not the ~48 MB Ghost Tap fallback, not the ~15 MB wrong source). Artifact ZIP SHA256 `8a6e0e7dcb3892b60166d3e1de2e5e5e632f77690beeafa7d67fb666048d0fc4`.
- **APK:** `GHOST-TAP-debug.apk`, **82,060,163 bytes**, SHA256 `a7c918a8aec332526fb9c9982ebe20b60e91f3ae2a3f697b96385a8c72c6a3f9`. Package `dev.virtualesp32`, versionCode 2, versionName 0.2.0, minSdk 26, targetSdk 35, arm64-v8a only. Delivered as `~/workspace/your_files/virtualesp32-corrected.apk`.
- **Verified inside the APK (independent check by Muse, not the builders):** `.so` is SHA256 `6a1fde2c…` — the REBUILT one with the new getter; dex contains `nativeGetSessionState` ×1 and the IPC repair string ×1; the old failure string ×0. **This is the corrected build.**
- **Green means compiles only.** The on-device field test (install, import, START, `>>>`, `help()`, leave running, Ghost Tap IPC + QUERY_STATUS + GPIO) is still the only proof the fixes behave on the phone — see §6 item 2.
- **Note (2026-11-05 ~17:31 PST):** ChatGPT has picked up the shared branch in parallel — 7 commits since the handoff (review of the handoff, an actionable work request, and an isolated VirtualESP32 build workflow `.github/workflows/build-virtualesp32.yml` plus `diagnostics.yml`). This commit adds on top of that work; no files collided.

## 1. Corrected source ZIP

- **File:** `~/workspace/your_files/virtualesp32-corrected.zip` — **25,928,545 bytes, 80 entries**, SHA256 `4d3414c5b15953aa167cb3c1a497bfa78930681c2eb7eb3dd8e019417fbce49e`
- **Hosted link:** `https://muse.ai/files/1343851108811859/1121146587190298/tvbt2p3b7itnjbv6eg8g8fv9/virtualesp32-corrected.zip` — **expires 2026-11-08T01:23:01Z (Nov 7 ~17:23 PST)**
- **Verified inside the ZIP:** `esp32_qemu_jni.c` has the 4-arg `nativeStart` (flashImage, efuseImage, **romDir**, uartPort) and `nativeGetSessionState` (4 mentions); the `.so` in `jniLibs/arm64-v8a/` is SHA256 `6a1fde2ceea5d9c7c9802fea161824b40dbfb392266aae2a72d2708833694894`; manifest and `app/build.gradle` byte-identical to the repaired ZIP.
- **Diff vs the repaired ZIP (exact):** 5 Java files changed, 3 C files replaced, 2 files added (`esp32_rsa_stub.c`, `app/src/main/cpp/REBUILD.md`), 1 binary replaced (the `.so`). Nothing else differs. No new permissions. No manifest changes.

## 2. What changed

### Java (Worker A — 5 files, diff vs pristine ZIP verified)

| File | Change |
|---|---|
| `ClassicEsp32QemuBackend.java` | Declares `private static native int nativeGetSessionState()` + `sessionState()` (-1 if lib not loaded or symbol missing) + `qemuAlive()` (true only when state == 1) + `sessionStateAvailable()`. Stale Javadoc fixed ("No QEMU binary is bundled yet" → prebuilt `.so` in `jniLibs/`). |
| `FirmwareEmulatorRuntime.java` | **D3:** `running()` true only when flag is `"running"` AND native state is RUNNING (1); `status()` reconciles to `"running"` / `"starting"` / `"stopping"` / `"error"` (latched once) / `"stopped"` / `"unavailable"`; `start()` polls native state up to ~1 s, returns `"starting"` instead of falsely claiming running, self-heals a dead session under a live flag, re-inspects flash even when already running (**D6**). **`reset()` now returns `"ok"` or an error string** and posts the error — never a silent no-op (**D6**). `stop()` teardown unified. |
| `BridgeCommandReceiver.java` | **D5:** `ensureBusListener()` made `public static` (idempotent). **D4:** `RESET_GUEST` confirmed as stop+start full restart; gap fixed — the firmware-path check now runs BEFORE `stop()`, so a doomed restart never tears down a running guest; `GUEST_RESET` not sent on failure. **D3:** `QUERY_STATUS` reads reconciled `status()` (RUNNING/STARTING/ERROR/STOPPED). |
| `MainActivity.java` | **D5:** bus listener registered once in `onCreate` — Ghost Tap now gets `UART_RX` for UI-started guests too. **D4:** "Power / Reset" → "Power", "Soft reset / reboot runtime" → "Soft reset virtual runtime" (labels honest; behaviour unchanged). "Reset guest" still calls soft `reset()` (**behaviour preserved**); failure now shows an AlertDialog. |
| `VirtualEsp32BridgeService.java` | **D7:** service KEPT (still manifest-exported, currently dead code) and fixed — READ_GPIO/WRITE_GPIO errors now identical to the receiver's (no more silent `-1` values or ignored pins); `ACTION_START_GUEST` now verifies the start reached running; `ACTION_RESET_GUEST` posts `GUEST_RESET` only on `"ok"`. `buildTargetInfo()` uses reconciled `status()`. |

**D3 fallback (honest bound):** `running()`/`status()` fall back to the Java flag alone only when the native state cannot be probed — library not loaded, or the loaded `.so` lacks the symbol. The old shipped `.so` lacks it, so with the old `.so` the fix is inert by design (fail-open, no breakage). With the rebuilt `.so` in this ZIP, reconciliation is live. **Even with the getter live, the flag can still over-claim in two cases the native session state cannot see:** (a) a guest CPU panic/reboot inside QEMU — native stays RUNNING through a guest reboot, so a guest in a crash loop still reports RUNNING; (b) UART never connected — `running()` does not check the UART socket. This fix detects QEMU-process death, NOT guest health.

### Native (Worker B — rebuild SUCCEEDED)

- **Added `nativeGetSessionState`** to the recovered 288-line `esp32_qemu_jni.c` (now 332 lines): returns `qemu_state_t` 0–4, `-1` when no live session; thread-safe (global session lock; `nativeStop` clears the pointer under the same lock before freeing). Header comment corrected to the real 4-arg contract. Existing 6 JNI symbols intact, 4-arg `nativeStart` unchanged.
- **Rebuilt `libesp32_qemu_android.so` from the recovered config** (ninja on the existing `build.ninja` in `qemu-src/build/`, NDK r27c `aarch64-linux-android26-clang`; ~7 s, only the JNI object recompiled + relink). The real target lives inline in top-level `qemu-src/meson.build` (~line 4300), NOT in `android-jni/meson.build` (which links a nonexistent `libqemu_xtensa_softmmu.a`) and NOT in CMake. Two blockers hit and fixed: ninja/meson not installed (pip venv), and `build.ninja` hardcoding `/usr/local/bin/meson` (symlinked). A no-change control build proved the toolchain first.
- **Rebuilt `.so` verified:** SHA256 `6a1fde2ceea5d9c7c9802fea161824b40dbfb392266aae2a72d2708833694894`, 81,361,464 bytes (+440 vs shipped). `nm -D` shows **7 JNI entries** (all 6 originals + `nativeGetSessionState` at `0xf0cd40`); disassembly confirms real code (locks, loads live-session state at struct offset `0x30`, `-1` when NULL). Same ELF (AArch64 DYN, debug_info, not stripped), same NEEDED libs. Intentionally NOT byte-identical to the shipped `.so` — source changed, as expected.
- **Original tree restored:** `esp32_qemu_jni.c` in `qemu-src/android-jni/` is byte-identical to the recovered original again (SHA256 `c73ba51c…`, 0 mentions of the getter); only regenerated build outputs changed. The original `.so` is preserved in the work-dir backup and in the ZIP/APK. Stale 217-line Bridge9 source never used.
- **Host load test:** host `dlopen` attempted and failed as expected (AArch64 Android `.so` on x86_64 host, no `liblog.so`, no `qemu-aarch64`) — recorded as a limit, not a defect. Verification was via `readelf`/`nm`/`objdump`. On-device runtime proof needs the field-test gate.
- **`cpp/` final list:** recovered `esp32_qemu_jni.c` (+getter), recovered 3-buffer `qemu_session.h`, recovered 220-line `qemu_driver_real.c` (the ZIP's 153-line copy lacks the exit-interceptor, stderr capture, `-L rom_path`, `-net none`), `esp32_rsa_stub.c` ADDED (absent from the ZIP but linked into the shipped `.so`), `CMakeLists.txt` + `qemu/android/` docs/patches kept, `android-jni/meson.build` NOT carried as-is. New `app/src/main/cpp/REBUILD.md` documents the real rebuild path. `app/build.gradle` has NO `externalNativeBuild` block (verified) — CMakeLists is never invoked; the `.so` is a prebuilt in `jniLibs/`.

## 3. Crash diagnosis — D1 (Worker C)

**Event:** 2026-11-05 ~16:10:13 PST. MicroPython v1.28.0 (2026-04-06) panicked ~4.5 minutes after a clean boot to `>>>`, while idle at the REPL. Core 1 register dump, EXCCAUSE 7, EXCVADDR 0x00000000, backtrace `0x40091ccf:0x3ffbc420 0x4020881a:0x3ffbc440 0x40208870:0x3ffbc460 0x400968dc:0x3ffbc480`, ELF SHA256 `cc85d995e`, then `Rebooting...` + clean `SW_CPU_RESET` boot. QEMU kept running.

- **Exact ELF: NOT FOUND.** Workspace holds only 4 hand-built test ELFs (Oct 10, symbols just `uart_putc/poll_rx/app_main`; addr2line returns `??:0` for all four backtrace addresses — ruled out). Drive holds only the two firmware `.bin` files (no ELF, no `.map`). micropython.org **does** publish `.elf and .map` for v1.28.0, but the download page fetch failed (browser-service error) and search surfaced no direct URL. **No decode was performed against any substitute ELF.**
- **Target identity recovered from the firmware itself:** full app ELF SHA256 `cc85d995e8b415bd66c20a7dea84b5117a08c05a3c0f9ea791bc29a9251487a1` (from the `esp_app_desc_t` in the `.bin`; IDF v5.5.1, built Apr 6 2026 13:16:34). Any candidate ELF must match this hash.
- **Crash location identified WITHOUT the ELF** (disassembly of the `.bin` with the on-machine Xtensa objdump; segments at `~/workspace/tmp/vesp32-crash/seg2-irom.bin`, `seg4-iram.bin`): all four backtrace PCs land inside the app's own code. The faulting function is **`esp_cpu_wait_for_intr()`** — the 3-instruction function at `0x40091ccc` (`entry; waiti 0; retw`). The guest was in the FreeRTOS idle task, asleep waiting for an interrupt, when it died.
- **EXCCAUSE 7 corrected: `PCValue` — "Next PC Value Illegal", NOT "Privileged"** (that's cause 8; verified in ESP-IDF's panic table and headers on this machine). The CPU tried to jump to address 0.
- **Most likely cause (moderate confidence): the emulator fumbled waking the CPU from its idle wait** — an intermittent QEMU WAIT/wake-up race, not a MicroPython bug. It ran ~4.5 minutes (thousands of wait/wake cycles) before failing once, in generic idle code with no user code running, and the implied return address (frame 2) looks valid — pointing at the wake-up mechanism itself, which QEMU emulates. Not cache-related, not the 16:08 READ_GPIO failure, not Ghost Tap's 16:10 LOAD_FIRMWARE.
- **What would confirm it:** (1) get the exact ELF — open `https://micropython.org/download/ESP32_GENERIC`, click `[.elf and .map]` on the v1.28.0 (2026-04-06) row, verify SHA256 equals `cc85d995e8b415bd66c20a7dea84b5117a08c05a3c0f9ea791bc29a9251487a1`, then run `xtensa-esp32-elf-addr2line -e <elf> -f -C 0x40091ccf 0x4020881a 0x40208870 0x400968dc` (toolchain already on the machine at `~/.espressif/tools/xtensa-esp32-elf/esp-12.2.0_20230208/`); fallback rebuild from MicroPython tag `v1.28.0` + ESP-IDF v5.5.1, gated on the SHA256 match. (2) Reproduce with the FULL register dump and time-to-crash. (3) Hardware control run — same `.bin` on a real ESP32, idle past 5 minutes. **Do not rebuild or change anything to "fix" this crash until steps 1–3 are done.**
- Full detail: `~/workspace/tmp/vesp32-crash-diagnosis.md`.

## 4. Test results (Worker D — host-side only)

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | ProtoWireTest.java (project's only unit test) | **PASSED** | ECJ 3.37.0 compile + JRE 17 run (no JDK on machine; Gradle can't run here): `PASS: Security 0 session envelope, Wi-Fi config command fields, zero-valued scan command, config response envelope`, exit 0. Proves the protobuf wire vectors on a host JVM; proves nothing about Android/QEMU/phone. |
| 2 | Host-QEMU boot of `virtualesp32-test-micropython-4mb.bin` to `>>>` | **PASSED** | Espressif prebuilt host QEMU 9.2.2 (esp_develop, same fork/version the app embeds; bundled ROMs byte-identical to the APK's): MicroPython v1.28.0 booted to `>>>` at t=2.8 s. Full log `~/workspace/tmp/vesp32-test-scratch/work/host-boot.log`. |
| 3 | REPL `help()` via serial | **PASSED** | Answered, 1,198 chars captured (standard MicroPython help text). |
| 4 | Second REPL command + 6-min idle survival | **PASSED** | `print('host-test-ok-123')` echoed; guest then ran silently alive 360 s idle, no panic, no reboot, QEMU rc=0. **The field crash did NOT reproduce on the host.** (Driver exited 1 solely on an over-broad `rst:0x` panic marker matching the normal `POWERON_RESET` boot line — a false positive; every substantive check passed.) |
| 5 | Firmware passes the app's own validation rules | **PASSED** | Python re-implementation of `FirmwareImageManager.inspect()` against the real file: 4 MiB, `0xE9` at `0x1000`, 1 app partition, first app @ `0x10000`. |
| 6 | Python helper tests (`firmware_image_layout.py`, `proto_vectors.py`) | **PASSED** | Both exit 0. |
| 7 | Python source-contract test (`check_project.py`) | **FAILED** | Fails at line 46: `assert 'externalNativeBuild' in gradle` — `app/build.gradle` has no `externalNativeBuild`/NDK block. All assertions before line 46 passed. This is the already-known D2: the native side cannot be rebuilt from this tree *via Gradle*. It is a source-tree defect relative to that test's expectation, NOT a test-environment failure — and it is now partly answered: the native side CAN be rebuilt, via meson/ninja in `qemu-src/build` (Worker B proved it), not via Gradle/CMake. The test was left as-is and NOT edited to pass; see §6. |

**NOT PERFORMED (stated plainly):**
- AndroidForge workflow compile of the corrected ZIP — the parent triggers this via the browser; green there means "compiles" only.
- On-device runtime test of the corrected build (install, import, START, `>>>`, `help()`, leave running, confirm no repeat panic, Ghost Tap IPC load + QUERY_STATUS + GPIO) — the field-test gate; only the user's phone can prove it.
- Host `dlopen` of the rebuilt `.so` — attempted, failed as expected (AArch64 Android library on x86_64 host); verified via `readelf`/`nm`/`objdump` instead.
- ELF addr2line decode of the 16:10 crash — blocked: exact ELF not found (see §3).
- From-source host build of the Espressif QEMU tree — not attempted (machine has ~490 MB free RAM; likely to OOM).

**Host-test limits (what the host tests do NOT prove):** the host QEMU is Espressif's prebuilt x86_64 binary, not the phone's ARM64 `.so`; serial was stdio, not the app's TCP loopback bridge; no Android, no JNI wiring, no Java `running` flag, no Ghost Tap IPC, no GPIO traffic, no receiver RESET. The host idle test rules out "MicroPython panics on its own after ~5 idle minutes under this QEMU" but does NOT rule out a crash triggered by something the phone setup does (UART/TCP traffic, GPIO polling, a reset, phone-side timing). One flag from the host run: QEMU printed `warning: global nvram.esp32.efuse.drive has invalid class name` — the eFuse `-global` from the app's argv warns on prebuilt QEMU 9.2.2 but does not stop the boot; worth verifying the eFuse wiring actually attaches in the phone's linked-in QEMU core.

## 5. Workflow build — DONE (see §0)

- Workflow: `github.com/stxpaxaig/AndroidForge` → "Build Android APK", run **42143704645**, completed success.
- Inputs used: `zip_source=download-url`; `zip_url` = the corrected-ZIP link in §1 (expires Nov 7 ~17:23 PST); `release_tag` left EMPTY; `build_variant` auto; `enable_ndk` false.
- Verification: artifact 26,272,635 bytes (~26 MB = VirtualESP32); APK contains the rebuilt `.so` (SHA256 `6a1fde2c…`) and the `nativeGetSessionState` string in the dex (§0).

## 6. What remains

1. **Parent:** trigger the workflow build with §5 inputs; verify artifact size + `.so` hash in the APK; commit the corrected ZIP link, this report, build logs, and the APK SHA256 to branch `muse/virtualesp32-chatgpt-handoff`. Do not touch `main`. Do not deploy to the phone.
2. **Field-test gate (user's phone, install only):** install the new APK, import `virtualesp32-test-micropython-4mb.bin`, START, confirm MicroPython reaches `>>>`, type `help()` and confirm an answer, leave it running past 5 minutes and confirm no repeat panic, confirm Ghost Tap's IPC load + `QUERY_STATUS` + GPIO read/write behave. Also confirm the new status surface: after a STOP, QUERY_STATUS must say STOPPED (not RUNNING); the D3 fix is proven only if a QEMU exit is reported honestly.
3. **D1 (crash):** get the exact ELF via §3 step 1 and decode; reproduce with the full register dump; hardware control run. No code change for the crash until diagnosed.
4. **Open item:** `check_project.py` line 46 still fails — the test expects a Gradle native build the project deliberately does not use (prebuilt `.so` model). Decide later whether to update the test to assert the real rebuild path (meson/ninja in `qemu-src/build`, proven by Worker B) or to leave it as a known-failing contract test. Do not silently edit it to pass.
5. **Open item:** the eFuse `-global` warning from the host run — verify eFuse wiring attaches in the phone's linked-in QEMU core.

## 7. Source documents (workspace)

- Investigation: `~/workspace/goals/virtualesp32-native-emulator-backend/CHATGPT-HANDOFF-INVESTIGATION.md`
- Handoff: `~/workspace/goals/virtualesp32-native-emulator-backend/CHATGPT_HANDOFF.md`
- Worker A diff report: `~/workspace/tmp/vesp32-fix-a-report.md`
- Worker B rebuild report: `~/workspace/tmp/vesp32-fix-b-report.md` (logs: `~/workspace/tmp/vesp32-fix-b/control-build.log`, `rebuild.log`)
- Worker C crash diagnosis: `~/workspace/tmp/vesp32-crash-diagnosis.md`
- Worker D test report: `~/workspace/tmp/vesp32-test-results.md` (scratch: `~/workspace/tmp/vesp32-test-scratch/`, host-boot log `work/host-boot.log`)
- Corrected tree: `~/workspace/tmp/vesp32-fix/virtual-esp32/` — local ZIP: `~/workspace/your_files/virtualesp32-corrected.zip`
