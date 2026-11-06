# CHATGPT_HANDOFF.md — VirtualESP32 engineering handoff (Muse → ChatGPT)

**Date:** 2026-11-05
**Branch:** `muse/virtualesp32-chatgpt-handoff` (this branch — do not touch `main`)
**Reader:** ChatGPT, continuing this work from the repository without asking the user anything.
**Scope:** VirtualESP32 only (package `dev.virtualesp32`, version 0.2.0, code 2). **Do not rebuild Ghost Tap. Do not uninstall, replace, or deploy either application. Do not ask the user to diagnose problems, interpret logs, compare source files, or relay technical explanations.**

Full evidence behind every claim here: `CHATGPT-HANDOFF-INVESTIGATION.md` on this branch (502 lines, read-only investigation run 2026-11-05). The prior audit is `ghost-tap-virtualesp32-technical-handover-audit.md` (the user has it; its "no guest boot ever demonstrated" is superseded by §2 below, and its "whether the shipped .so has the 4-arg form is UNVERIFIED" is now resolved — it does).

---

## 1. Verdicts up front (read this first)

1. **The IPC repair IS compiled into the installed APK and works in the field.** All five new repair strings are present in `classes2.dex`; the old failure string is absent. Field proof: Ghost Tap sent `LOAD_FIRMWARE` with a `content://com.ghosttap.fileprovider/…` URI and the firmware imported and booted. **No corrected APK build is needed for the IPC repair. Do not rebuild to "fix" the IPC path.**
2. **D2 (native sources) is RECOVERED — see §4.** The true native sources were found on the build machine and verified byte-identical to the shipped `.so`. They are on this branch under `virtualesp32-native/android-jni/`. What remains is to verify a from-tree rebuild reproduces the `.so`, and to check the sources into the permanent tree.
3. **The guest crash at 16:10 on 2026-11-05 is a CURRENT open defect with UNKNOWN cause.** It is not "cache-related" and not 3–4 days old — see §3. Do not rebuild to fix it blind; decode the backtrace first (defect D1).
4. **First real guest boot ever demonstrated:** MicroPython v1.28.0 booted to the `>>>` prompt at 16:05–16:06 on 2026-11-05.
5. **Verified defects to fix:** D1 (guest crash, diagnose first), D3 (Java `running` flag never reconciled with QEMU liveness), D4 (three different things called "reset"), D5 (`UART_RX` forwarding only registered on receiver START/RESET), D6 (`reset()` silent no-op; `start()` early-return without revalidation), D7 (service `READ_GPIO` silently returns −1). Details in §5. D3's fix needs a new JNI getter — now possible because the true native sources are recovered.

---

## 2. Source revision — what produced the installed APK

| Item | Value |
|---|---|
| Repaired source ZIP | `virtualesp32-merged-bridge9-repaired.zip`, **25,802,041 bytes**, **78 files**, SHA256 `024551aab2dfe64c6c721c9f7881ae91dc0d894f79b79322d5061314985b5ea0` |
| Unpatched base ZIP | `virtualesp32-fw-manager.zip`, **25,919,642 bytes**, **77 files**, SHA256 `75b97f69e1ed01afd853da300ad697d202b1844ce5968cbc1f2c7662d6a04988` |
| Installed APK | `virtualesp32-repaired.apk`, **82,059,723 bytes**, SHA256 `c3516f8b4fb144c87e726f3e3893307cd5876ff65d45491c880ff4ab8baffe24` |
| Workflow | AndroidForge, repo `github.com/stxpaxaig/AndroidForge`, run **42130846133**, workflow commit **a2640c6** |
| Workflow artifact | id **13464035279**, **26,270,163 bytes**, named `GHOST-TAP-APK` (the workflow names every artifact that, regardless of app), SHA256 `7a49d09653174501896266dcc2bbdf4b22fb48a431721bd2649c653a59715934` |
| APK internals | `lib/arm64-v8a/libesp32_qemu_android.so` 81,361,024 bytes; `classes.dex` 564 bytes (stub); `classes2.dex` 86,820 bytes (all app code); `assets/esp32-v3-rom.bin` + `assets/esp32-v3-rom-app.bin` 455,722 bytes each; AGP 8.6.1; minSdk 26; targetSdk 35; versionCode 2; versionName 0.2.0; arm64-v8a only |
| Native lib (ZIP and APK) | `libesp32_qemu_android.so`, 81,361,024 bytes, SHA256 `207d50ac9711d89f9c40350fb5151ad966170efe377f3de2078bbb2e89ea4a75` — byte-identical in both |

Only **3 of 78 entries differ** between the repaired ZIP and the base: `MERGE_NOTES.md` (added), `AndroidManifest.xml` (receiver intent-filter drops `EJECT_FIRMWARE`/`USE_BUILTIN_FIRMWARE`, keeps the 8 handled actions), and `BridgeCommandReceiver.java` (13,829 → 12,393 bytes — the IPC repair).

### IPC repair (repaired `BridgeCommandReceiver.java`)
- `LOAD_FIRMWARE` reads, in order: Intent data URI → `"uri"` string extra → legacy `"path"` extra; if none, error `"LOAD_FIRMWARE failed: no content URI or legacy path supplied"`; then validates via `FirmwareImageManager.importAndInspect(context, uri)`, rejects invalid images with `"LOAD_FIRMWARE rejected: <summary>"`, stores the imported path + inspector summary in VirtualHal, reports real imported-file fields in the `FIRMWARE_LOADED` event.
- `START_GUEST` sends `GUEST_STARTED` only when `start()` returns `"running"` **and** `FirmwareEmulatorRuntime.running()` is true, else `"START failed: runtime did not reach running state (…)"`.
- `RESET_GUEST` errors `"RESET failed: no firmware imported in VirtualESP32 app"` when no path; verifies the running state after restart. (Base silently did nothing and still sent `GUEST_RESET`.)
- `WRITE_GPIO` / `READ_GPIO` error `"… failed: guest is not running"` when stopped; any pin other than 4 (write) / 2 (read) gets an explicit `"… is not supported by this bridge"` error. Removed entirely: `EJECT_FIRMWARE` and `USE_BUILTIN_FIRMWARE` (receiver answers `"Unsupported bridge command: <action>"` via its `default:` case).

### IPC repair verified in the APK (dex strings)
All checked by streaming `classes2.dex` through `grep -a -o -F` and `strings -a` (two independent methods, agreeing):

| String | In APK? |
|---|---|
| `no content URI or legacy path supplied` | PRESENT ×1 |
| `LOAD_FIRMWARE rejected: ` | PRESENT ×1 |
| `START failed: runtime did not reach running state` | PRESENT ×1 |
| `RESET failed: no firmware imported` | PRESENT ×1 |
| `is not supported by this bridge` | PRESENT ×1 |
| `importAndInspect` | PRESENT ×1 |
| `LOAD_FIRMWARE failed: no path provided` (OLD, base only) | ABSENT ×0 (both dex files, both methods) |
| `EJECT_FIRMWARE` / `USE_BUILTIN_FIRMWARE` / `Built-in test firmware` | ABSENT ×0 |

Source cross-check: every string matches the repaired `BridgeCommandReceiver.java` exactly (lines 90, 97, 128, 147, 190, 223). **Verdict: the IPC repair IS compiled into this APK.** String presence proves the code is present, not that it executes — runtime execution is proven by the field evidence in §3.

---

## 3. Field evidence — what actually happened on the phone (2026-11-05, all times PST)

Screenshots (all five arrived together 16:11, file mtimes Nov 6 00:11 UTC):

- **16:05** — Merged image imported in the app's own UI ("Merged image imported" popup). Log: ROM dir ready, `QEMU UART connected on loopback (#1)`, `Classic ESP32 QEMU session started`, then **MicroPython v1.28.0 on 2026-04-06 booted to the `>>>` prompt**. File loaded: `virtualesp32-test-micropython-4mb.bin` (4,194,304 bytes, Drive). **First real guest boot ever demonstrated.**
- **16:08** — Ghost Tap connected; TARGET Status RUNNING on Native QEMU; red Problem `READ_GPIO failed: guest is not running`. Explained in §5 (D3 family): the red line is a sticky last-error from a different instant than the TARGET card — the Java flag was false at the moment that broadcast was handled (start/reset window, a stop, or a process restart). It is a host-side lifecycle/timing state, **not** caused by the 16:10 crash (wrong order — the crash was ~2 minutes later — and a guest panic cannot clear the Java flag anyway).
- **16:10** — Ghost Tap TX `LOAD_FIRMWARE content://com.ghosttap.fileprovider/firmware/virtualesp32-test-micropython-4mb.bin` (the repaired IPC path working from Ghost Tap). Pin 2 `(unknown)`, Pin 4 `OFF`. LIVE TARGET running. **Guest crashed: Core 1 register dump, `EXCCAUSE: 0x00000007`, `EXCVADDR: 0x00000000`, backtrace `0x40091ccf:0x3ffbc420 0x4020881a:0x3ffbc440 0x40208870:0x3ffbc460 0x400968dc:0x3ffbc480`, `ELF file SHA256: cc85d995e`, then `"Rebooting..."` and a clean boot (`ets Jul 29 2019 12:21:46`, `rst:0xc (SW_CPU_RESET)`, `boot:0x12 (SPI_FAST_FLASH_BOOT)`, `entry 0x400805a8`).** The guest panicked ~5 minutes after a successful boot, rebooted itself, and booted again.

### The "3–4 days old / cache-related" claim does NOT hold up

The handoff prompt asserted the crash screenshots are from an older session ~3–4 days ago and the crash was "cache-related". Verified against the evidence — that claim fails on every check:

| Evidence | Finding |
|---|---|
| Status-bar clocks | 4:05, 4:08, 4:10 — taken 16:05–16:10 on the day they arrived |
| In-log timestamps | `16:05:07`–`16:05:46`; `16:10:13`–`16:10:18` — match the clocks |
| TX line | Names `virtualesp32-test-micropython-4mb.bin` — the exact file Muse named to the user at ~16:02, minutes before the screenshots arrived |
| Ghost Tap screens | LIVE TARGET card + "thinking engine" line exist only in the Nov 5 master build (Ghost Tap fixed3, run 42075309502, green 09:35 PST Nov 5) — did not exist 3–4 days ago |
| MicroPython version | `v1.28.0 on 2026-04-06` — matches the Drive file |
| Prior audit (written Nov 5 ~14:16 PST) | "No guest firmware boot ever demonstrated" — consistent with the boot being new at 16:05, not 3–4 days old |
| "Guru Meditation" wording | **Nowhere in any screenshot.** The dump is a Core 1 register dump + backtrace + "Rebooting..." |
| "Cache-related" | **No mention of cache anywhere in any log line, screenshot, or source path involved.** The dump is a guest CPU exception (EXCCAUSE 7) followed by a clean `SW_CPU_RESET` reboot |

What would be needed to support "3–4 days old / cache-related": file mtimes or log timestamps from Nov 1–2; any mention of cache in the logs; a different firmware version or an older app build visible in the screens. None of that exists. **Treat the 16:05 boot as current evidence and the 16:10 crash as a CURRENT open defect with UNKNOWN cause (D1). Do not adopt "cache-related" without source evidence.**

Firmware files on Drive (verified 2026-11-05 by downloading + running the app's own checks — all pass validation): `virtualesp32-test-micropython-4mb.bin`, `flash_image_gpio4.bin`, `flash_image_gpio.bin`, `flash_image_v2.bin`, `flash_image.bin`. `ESP32_GENERIC-20260406-v1.28.0.bin` (1,760,192 bytes) correctly fails the size rule (must be exactly 2/4/8/16 MiB).

---

## 4. D2 RECOVERED — the true native sources (the big find)

The prior audit's Finding 3: the shipped `.so` exports 6 JNI symbols but the shipped C source defines only 4 (no GPIO natives), Java `nativeStart` takes 4 args vs C's 3, and the `.so` cannot be reproduced from the ZIP. **Resolved — the true sources were found on the build machine and verified:**

| File (on the build machine) | Size / lines | SHA256 |
|---|---|---|
| `~/workspace/virtualesp32/native/qemu-src/android-jni/esp32_qemu_jni.c` | 11,998 bytes / **288 lines** | `c73ba51c8135f7e683d49f0ada035df520cd4f5e59a81fc9a96aee039116c01a` |
| `~/workspace/virtualesp32/native/qemu-src/android-jni/qemu_session.h` | 647 bytes | `9dd8a76362c3dbb675912f96ce165dbfc3c817b2204516951faf7ef9741e3366` |
| `~/workspace/virtualesp32/native/qemu-src/android-jni/qemu_driver_real.c` | 8,617 bytes | `44b6e169501ff617ec9c4cc347cc588c2733278071b36fc7b0581e3dcf61886b` |
| `~/workspace/virtualesp32/native/qemu-src/android-jni/meson.build` | 1,498 bytes | `fee47b48858c2b7dae7647d4508d4ab87b8cb6def41d70610f723944c364449f` |
| `~/workspace/virtualesp32/native/qemu-src/android-jni/esp32_rsa_stub.c` | 2,072 bytes | `6af1ba26325b6678567f51496e0215d6d2e5b5eb089fdd0e1bf6bd87fe1f3345` |

Verification (all three independent checks agree):
- **DWARF line match:** the shipped `.so`'s DWARF says `nativeStart` decl_line **163**, `nativeGetGpio2` decl_line **273**, `nativeSetGpio4` decl_line **283**, CU `../android-jni/esp32_qemu_jni.c`, comp_dir `/home/hatch/workspace/virtualesp32/native/qemu-src/build`. In the recovered file, `nativeStart` is defined at line **163** (4 args: flashImage, efuseImage, **romDir**, uartPort), `nativeGetGpio2` at line **273**, `nativeSetGpio4` at line **283**. Exact match.
- **Struct match:** recovered `qemu_session.h` has **three** path buffers (`flash_path`, `efuse_path`, `rom_path`) — matches the disassembled session struct (0x3438 bytes, three 0x1000-byte buffers), vs the ZIP's two-buffer struct (≈0x243C).
- **Byte-identical binary:** the built library at `~/workspace/virtualesp32/native/qemu-src/build/libesp32_qemu_android.so` has SHA256 `207d50ac9711d89f9c40350fb5151ad966170efe377f3de2078bbb2e89ea4a75` — **identical to the `.so` in the APK and in the ZIP**. `nm -D` shows the same 6 JNI symbols. `meson.build` passes `-DQEMU_CORE_AVAILABLE` (FULL build, not the CMake stub).

**These files are committed on this branch under `virtualesp32-native/android-jni/`** (the five files above). The full QEMU source tree (`qemu-src/`, with `build/` containing `build.ninja`, `config-host.h`, etc.) is far too large to commit — it stays on the build machine at `~/workspace/virtualesp32/native/qemu-src/`. Record its location; do not lose it.

**Consequence:** D2 is solved in substance. The prebuilt `.so` in `jniLibs/` is no longer the only valid binary — the sources that produced it are now in hand. **Remaining D2 work:** verify a from-tree rebuild (meson/ninja from `qemu-src/build`, or a clean reconfigure with the same flags) reproduces a byte- or behavior-identical `.so` (same 6 exported JNI symbols, same `nativeStart` behaviour). Until that verification passes, keep treating the shipped `.so` as the reference binary. Also fix the stale comments: `ClassicEsp32QemuBackend.java:3-6` ("No QEMU binary is bundled yet") and `esp32_qemu_jni.c:3-7` (3-arg contract) both contradict the shipped binary.

**Why this mattered:** had anyone rebuilt from the ZIP's stale C source and shipped that `.so`, START would fail outright (`nativeStart` would read the port from the romDir pointer slot → "UART loopback port out of range") and GPIO would fail silently (`UnsatisfiedLinkError`, caught as `Throwable` at every call site). The shipped binary itself was never affected.

---

## 5. Defect list (verified only — no speculation)

Base path in the source: `virtual-esp32/app/src/main/java/dev/virtualesp32/` = `J/`, `virtual-esp32/app/src/main/cpp/` = `C/`.

### D1 — Guest firmware crash, cause UNKNOWN (HIGH)
- **Evidence:** 16:10 Nov 5, Core 1 register dump, EXCCAUSE 7, backtrace `0x40091ccf:0x3ffbc420 0x4020881a:0x3ffbc440 0x40208870:0x3ffbc460 0x400968dc:0x3ffbc480`, ELF SHA256 `cc85d995e`, then `"Rebooting..."` + clean `SW_CPU_RESET` boot. Guest panicked ~5 min after a successful boot; rebooted itself and booted again. The dump format is the ESP-IDF panic handler's — a panic inside the guest firmware (MicroPython v1.28.0), not a QEMU or host crash; QEMU kept running.
- **What would identify the cause:** decode the backtrace with `addr2line` (or equivalent) against the ELF for the exact MicroPython build (`ESP32_GENERIC-20260406-v1.28.0`, ELF SHA256 `cc85d995e`) — the `.bin` on Drive is a merged flash image, not an ELF, so the ELF must come from the MicroPython build for that version; and/or reproduce with UART logging of what the guest was doing immediately before the dump. Also record whether it recurs and on what trigger (idle, after a Ghost Tap RESTART, after GPIO traffic, etc.).
- **Fix location:** unknown until diagnosed. **Do NOT rebuild to "fix the crash" blind.**

### D3 — Java `running` flag never reconciled with QEMU liveness (MEDIUM)
- **Evidence:** `J/FirmwareEmulatorRuntime.java:8,11` — `running()` is just `"running".equals(state)`. Native `qemu_session_t.state` (`C/qemu_session.h`: STARTING/RUNNING/STOPPING/STOPPED/ERROR) has **no JNI getter**; `qemu_driver_start` returns success after a 5 s wait even if still STARTING, with the comment "the Java layer polls status()" — but no Java code ever polls native state (verified by grep). A guest panic/reboot never touches the Java flag. If `qemu_main_loop()` returns, native state becomes STOPPED while Java stays `"running"` forever.
- **Consequence:** `QUERY_STATUS` can say RUNNING with no live guest behind it; the `READ_GPIO` gate can pass with no guest; "running" cannot distinguish a healthy guest from one in a crash loop.
- **Fix:** add a JNI getter for native session state (or a liveness heartbeat, e.g. QEMU main-loop alive + UART socket connected) and reconcile it in `running()`/`status()`/`QUERY_STATUS`; surface a distinct state when the guest has panicked or QEMU has exited. Files: `J/ClassicEsp32QemuBackend.java`, `J/FirmwareEmulatorRuntime.java`, `C/esp32_qemu_jni.c` (now recoverable — see §4), `J/BridgeCommandReceiver.java` (`QUERY_STATUS`, lines 195–205).

### D4 — Three different things called "reset" (MEDIUM)
- **Evidence:** receiver `RESET_GUEST` (`J/BridgeCommandReceiver.java:142-160`) = full stop+start (new session, UART re-created, monitor restarted, flash re-inspected); service `ACTION_RESET_GUEST` (`J/VirtualEsp32BridgeService.java:138-141`) and UI "Reset guest" (`J/MainActivity.java:37` → `FirmwareEmulatorRuntime.reset()`, line 23) = soft reset (same session, `qemu_system_reset_request`, no state change, no event); UI "Power / Reset" (`MainActivity.java:26,31`) and "Soft reset / reboot runtime" (`MainActivity.java:39`) = no guest effect at all (BLE only / `hal.softReset()` only).
- **Consequence:** a controller mixing paths gets different behaviour for the same word; Ghost Tap's RESTART button (receiver path) does a full teardown+restart while the app's own Reset button does a soft reset.
- **Fix:** pick one semantic per entry point and name them distinctly in code and UI (e.g. receiver RESET stays stop+start; rename the soft-reset paths; make "Power / Reset" and "Soft reset / reboot runtime" labels honest about not touching the guest). Files: `J/BridgeCommandReceiver.java`, `J/VirtualEsp32BridgeService.java`, `J/MainActivity.java`, `J/FirmwareEmulatorRuntime.java`.

### D5 — `UART_RX` forwarding to Ghost Tap only registered on receiver START/RESET (MEDIUM)
- **Evidence:** `J/BridgeCommandReceiver.java:23-28` (bus listener re-broadcasts `UART_RX` to `com.ghosttap`); `ensureBusListener` called only from `START_GUEST`/`RESET_GUEST` (`BridgeCommandReceiver.java:125,151`). If the guest was started from the app UI's "Run firmware" button, that listener is never registered, so Ghost Tap gets no `UART_RX` events via the receiver path (only via the bridge service if it was created — and the service is dead code, see below).
- **Consequence:** Ghost Tap's UART view goes silent for UI-started guests.
- **Fix:** register the bus listener once at app start (e.g. in `MainActivity.onCreate` or `Application.onCreate`), not per START/RESET. Files: `J/BridgeCommandReceiver.java`, `J/MainActivity.java` (or application class).

### D6 — `reset()` silent no-op when not running; `start()` early-returns without revalidation (LOW)
- **Evidence:** `J/FirmwareEmulatorRuntime.java:23` — `reset()` does nothing and emits no error/event when `backend==null`. `J/FirmwareEmulatorRuntime.java:13` — `start()` returns `"running"` immediately if already running, without re-inspecting the flash file.
- **Consequence:** a controller cannot tell a reset did nothing; a replaced/corrupt flash file is not caught while a session is already running.
- **Fix:** `reset()` should return/report an error when not running; `start()` should re-inspect the flash file even when already running (or document the early return). File: `J/FirmwareEmulatorRuntime.java`.

### D7 — Service `READ_GPIO` silently returns −1 vs receiver's explicit errors (LOW)
- **Evidence:** `J/VirtualEsp32BridgeService.java:175-190` — if not running or `pin!=2`, `value` stays `-1` and a `GPIO_VALUE` event with `value=-1` is still sent, with no error. The receiver (`J/BridgeCommandReceiver.java:207-227`) sends explicit errors for the same conditions.
- **Consequence:** low impact in practice — the service is dead code (nothing starts it: the only `startService`/`startForegroundService` calls in the app target `VirtualDeviceService`; Ghost Tap uses explicit broadcasts to the receiver), so no controller currently hits this path. Fix for consistency if the service is ever revived; otherwise remove the dead service.
- **Fix:** mirror the receiver's error strings in the service, or delete `VirtualEsp32BridgeService` if it stays unused. File: `J/VirtualEsp32BridgeService.java`.

### D8 — `EJECT_FIRMWARE` / `USE_BUILTIN_FIRMWARE` removed (INFO — behavior change, not a defect)
Both command cases and their manifest actions were removed in the repaired receiver; the receiver now answers `"Unsupported bridge command: <action>"`. No controller is known to send them (Ghost Tap does not), so this is documented here rather than flagged as a defect. If a future controller needs them, re-add the cases deliberately.

### D9 — `QUERY_STATUS` cpu/backend strings are hardcoded literals (INFO)
`J/BridgeCommandReceiver.java:198-199` (`"Xtensa LX6 / Classic ESP32"`, `"Native QEMU"`) and `J/VirtualEsp32BridgeService.java:95-98` (`peripherals: {uart: true, gpio2_output: true, gpio4_input: true}`) are string literals, never measured. Only `"status"` and `"firmware"` derive from `running()`. Ghost Tap displays these as facts, but they are claims by construction, not probed. True when the backend exists, but a build with a stub/missing backend would still report them if the receiver ran. Not a runtime defect in the current build; note it so nobody treats these strings as measurements.

### What is NOT a defect (do not "fix" these)
- **"Your AI couldn't be reached" line in Ghost Tap:** Ghost Tap / OpenAI-side, out of VirtualESP32 scope. The VirtualESP32 app does not call OpenAI. Do not touch it in this repo.
- **Pin 2 showing "(unknown)":** expected — `GuestGpioMonitor` only fires on *changes* and only when the guest configures GPIO2 as an output; stock MicroPython drives no GPIO2 output, so the monitor observes −1 forever and never fires. Not a bug.
- **UART log fragmentation** (e.g. `"e"`, `"ts Jul 29 2019 1"`, `"2:21:46"` as separate lines): normal TCP chunking — each `read()` (≤1024 bytes) is logged as its own line. Not corruption.
- **The 16:08 `READ_GPIO` failure:** explained in §3 — a host-side timing/lifecycle state, not a guest defect. The underlying fix is D3 plus timestamped error reporting on the Ghost Tap side (out of scope here).
- **Firmware files on Drive:** all pass the app's own validation (verified 2026-11-05). No firmware-file defect.

---

## 6. How the pieces work (short version — detail in the investigation report)

- **Validation** (`J/FirmwareImageManager.java`): size must be exactly 2/4/8/16 MiB; byte `0xE9` at offset `0x1000`; partition table at `0x8000` with ≥1 app partition pointing at an `0xE9` header. The check is structural only — it does not validate app contents, entry point, or SoC compatibility.
- **Start** (`J/FirmwareEmulatorRuntime.java`, all `synchronized`): re-inspects the flash file, starts `EmulatorUartBridge` (app is the TCP server on loopback, ephemeral port; QEMU connects as client — `"QEMU UART connected on loopback (#1)"` = first inbound TCP connect), copies ROM dir, calls `backend.start(flash, efuse, romDir, port)`. On success `state="running"`. **What "running" actually proves:** a native session object was allocated, a QEMU worker thread was created, and `qemu_init` did not report a synchronous error inside the 5 s window (or the window expired). It does NOT prove the guest CPU executed one instruction — the proof of real execution is the guest's own UART output (MicroPython banner + `>>>`).
- **UART** (`J/EmulatorUartBridge.java`): each TCP `read()` (≤1024 bytes) is appended to the `hal` `"uart"` key (capped 32,768 chars, tail-kept), logged, and re-broadcast as `UART_RX` to `com.ghosttap`.
- **Native loading** (`J/ClassicEsp32QemuBackend.java`): static init `System.loadLibrary("esp32_qemu_android")`; missing/wrong-ABI `.so` → `LOADED=false`, and the UI/IPC degrade gracefully (import/validation keep working, execution reports "library is not packaged"). `.so` present but a symbol missing → `UnsatisfiedLinkError` on first call of that native, caught as `Throwable` at every call site — never a crash. One residual hazard: a fatal `qemu_init()` error calls `exit()` and kills the app process with no recovery hook (per the C header comment).
- **GPIO** (`J/BridgeCommandReceiver.java`): write = pin 4 only (`nativeSetGpio4`); read = pin 2 only (`nativeGetGpio2`). `GuestGpioMonitor` polls pin 2 every 250 ms and reports only *changes*. The bridge service (`J/VirtualEsp32BridgeService.java`) is dead code — nothing starts it; the receiver is the live IPC path.
- **State stores:** (a) `FirmwareEmulatorRuntime` static `state` — the only thing `running()` reads; (b) SharedPreferences key `"firmwareEmulatorState"` — written on every transition but **never read** (dead telemetry); (c) `ClassicEsp32QemuBackend.handle`; (d) native `qemu_session_t.state` — **never exposed to Java; no JNI getter exists** (this is D3).

---

## 7. What to do first (ordered)

1. **Verify the D2 recovery reproduces.** From the recovered sources (`virtualesp32-native/android-jni/` on this branch + the QEMU tree at `~/workspace/virtualesp32/native/qemu-src/` on the build machine), rebuild the `.so` and confirm it is byte- or behavior-identical to the shipped one (same 6 exported JNI symbols, same `nativeStart` behaviour). Do not ship a `.so` built from the stale 217-line C file in the ZIP.
2. **Diagnose D1 before changing anything for it.** Decode the 16:10 backtrace against the MicroPython ELF for `ESP32_GENERIC-20260406-v1.28.0` (ELF SHA256 `cc85d995e`); reproduce and note the trigger. Do not rebuild to fix an undiagnosed guest panic.
3. **Implement D3–D7** (specs in §5). D3 needs the new JNI getter — now possible with the recovered sources. Preserve existing functionality; no behaviour changes beyond the defects listed.
4. **Build the corrected APK via AndroidForge** (workflow `github.com/stxpaxaig/AndroidForge`, run a new dispatch with `zip_source=download-url` and the corrected ZIP's hosted link in the `zip_url` box — **never** the release-tag box; that mistake built the wrong source once before). Verify the new build by artifact size (**~26 MB for VirtualESP32**; ~48 MB means it built the Ghost Tap fallback, ~15 MB the old scanner app) and by the dex-string check in §2.
5. **Field-test gate (same as before):** install, import `virtualesp32-test-micropython-4mb.bin`, START, confirm MicroPython reaches the `>>>` prompt, type `help()` at the prompt and confirm an answer, leave it running and confirm no repeat panic, and confirm Ghost Tap's IPC load + `QUERY_STATUS` + GPIO read/write behave. Green in CI means "compiles" only — it is not proof the emulator runs firmware.
6. **Tests:** the project has one unit test, `virtual-esp32/tests/ProtoWireTest.java` — it was NOT run (Gradle cannot run in the sandbox; the workflow builds only). Run it as part of any corrected build if the CI can be extended; do not claim test results that were not run.

## 8. Do not do these

- Do not rebuild Ghost Tap. Do not touch Ghost Tap source.
- Do not rebuild the `.so` from the stale 217-line C file in the repaired ZIP.
- Do not claim the guest crash is fixed until a field test shows no repeat panic.
- Do not claim "cache-related" or any other crash cause without source evidence.
- Do not treat the hardcoded `QUERY_STATUS` strings (`Xtensa LX6 / Classic ESP32`, `Native QEMU`) as measurements.
- Do not deploy anything or overwrite `main`. Work on this branch only.
- Do not ask the user to diagnose, interpret logs, compare source files, or relay technical messages. The user installs and reports what he sees; everything else is your job.

## 9. Reproducibility — exact verification commands

```bash
# Hashes / sizes
sha256sum virtualesp32-merged-bridge9-repaired.zip virtualesp32-fw-manager.zip virtualesp32-repaired.apk

# IPC strings in the APK dex (streamed; no disk writes)
for s in "no content URI or legacy path supplied" "LOAD_FIRMWARE rejected: " \
  "START failed: runtime did not reach running state" "RESET failed: no firmware imported" \
  "is not supported by this bridge" "LOAD_FIRMWARE failed: no path provided" "importAndInspect"; do
  unzip -p virtualesp32-repaired.apk classes2.dex | grep -a -o -F "$s" | wc -l
done

# .so identity (stream or use a workspace scratch dir — /tmp on the build machine is a 512 MB tmpfs)
unzip -p virtualesp32-merged-bridge9-repaired.zip virtual-esp32/app/src/main/jniLibs/arm64-v8a/libesp32_qemu_android.so | sha256sum
unzip -p virtualesp32-repaired.apk lib/arm64-v8a/libesp32_qemu_android.so | sha256sum
sha256sum ~/workspace/virtualesp32/native/qemu-src/build/libesp32_qemu_android.so
# all three must be 207d50ac9711d89f9c40350fb5151ad966170efe377f3de2078bbb2e89ea4a75
nm -D --defined-only <so> | grep Java_

# D2 recovery — DWARF line check (nativeStart 163, nativeGetGpio2 273, nativeSetGpio4 283)
grep -n "Java_dev_virtualesp32_ClassicEsp32QemuBackend_native" virtualesp32-native/android-jni/esp32_qemu_jni.c
```

**Environment notes:** `/tmp` on the build machine is a 512 MB tmpfs — prefer streaming (`unzip -p … | …`) or a workspace scratch dir for the 81 MB `.so`. Gradle cannot run in the sandbox (Java TCP is blocked), so local unit-test runs and local builds are not available there; the AndroidForge workflow build is the compile test, and the user's phone is the runtime test.
