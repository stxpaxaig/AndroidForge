# VirtualESP32 — Engineering Handoff Investigation for ChatGPT

**Status:** READ-ONLY investigation. No source was modified, nothing was rebuilt, Ghost Tap was not touched.
**Date:** 2026-11-05 (investigation run 2026-11-06 ~00:20–00:26 UTC)
**Scope:** VirtualESP32 only (package `dev.virtualesp32`, version 0.2.0, code 2). Ghost Tap is a separate app and is OUT OF SCOPE except where its screens are field evidence.
**Intended reader:** ChatGPT, continuing this work from the repository without asking the user anything.

---

## 0. Verdict up front

1. **The IPC repair IS compiled into the installed APK and works in the field.** All five new repair strings are present in `classes2.dex`; the old failure string is absent from both dex files; source↔dex match 1:1. Field proof: Ghost Tap sent `LOAD_FIRMWARE` with a `content://com.ghosttap.fileprovider/…` URI and the firmware imported and ran (see §7).
2. **No corrected APK build is needed for the IPC repair.** Do not rebuild to "fix" the IPC path — it is fixed.
3. **A corrected build IS required before anyone rebuilds from this source tree**, because the tree as-shipped **cannot reproduce the working `.so`**. The checked-in C source is an older revision (3-arg `nativeStart`, no GPIO natives, 2-buffer session struct) while the shipped `.so` is a newer revision (4-arg `nativeStart`, GPIO natives, 3-buffer struct — proven by disassembly + DWARF, §4). Rebuilding from the tree breaks START outright and GPIO silently.
4. **The guest crash at 16:10 on 2026-11-05 is a CURRENT open defect with UNKNOWN cause.** It is not "cache-related" and not 3–4 days old (§7). Do not rebuild to fix it blind — decode the backtrace first (§8, D1).
5. **First real guest boot ever demonstrated:** MicroPython v1.28.0 booted to the `>>>` prompt at 16:05–16:06 on 2026-11-05 (§7). The prior audit's "no guest boot ever demonstrated" is now superseded.

---

## 1. Source revision — what produced the installed APK

### 1.1 Provenance table (all hashes/sizes recomputed during this investigation)

| Item | Value |
|---|---|
| Repaired source ZIP | `virtualesp32-merged-bridge9-repaired.zip`, **25,802,041 bytes**, **78 files**, SHA256 `024551aab2dfe64c6c721c9f7881ae91dc0d894f79b79322d5061314985b5ea0` |
| Unpatched base ZIP | `virtualesp32-fw-manager.zip`, **25,919,642 bytes**, **77 files**, SHA256 `75b97f69e1ed01afd853da300ad697d202b1844ce5968cbc1f2c7662d6a04988` |
| Installed APK | `virtualesp32-repaired.apk`, **82,059,723 bytes**, SHA256 `c3516f8b4fb144c87e726f3e3893307cd5876ff65d45491c880ff4ab8baffe24` |
| Workflow | AndroidForge, repo `github.com/stxpaxaig/AndroidForge`, run **42130846133**, workflow commit **a2640c6** |
| Workflow artifact | id **13464035279**, **26,270,163 bytes**, named `GHOST-TAP-APK` (the workflow names every artifact that, regardless of app), SHA256 `7a49d09653174501896266dcc2bbdf4b22fb48a431721bd2649c653a59715934` |
| APK internals | 8 entries, uncompressed 82,366,716 bytes; `lib/arm64-v8a/libesp32_qemu_android.so` 81,361,024 bytes; `classes.dex` 564 bytes (stub); `classes2.dex` 86,820 bytes (all app code); `assets/esp32-v3-rom.bin` + `assets/esp32-v3-rom-app.bin` 455,722 bytes each; AGP 8.6.1; minSdk 26; targetSdk 35; compileSdk 35; versionCode 2; versionName 0.2.0; arm64-v8a only |
| Native lib (both copies) | `libesp32_qemu_android.so`, 81,361,024 bytes, SHA256 `207d50ac9711d89f9c40350fb5151ad966170efe377f3de2078bbb2e89ea4a75` — byte-identical in the repaired ZIP and in the APK |

File locations: repaired ZIP `~/workspace/your_files/virtualesp32-merged-bridge9-repaired.zip`; base ZIP `~/workspace/your_files/virtualesp32-fw-manager.zip` (symlink to `~/workspace/goals/virtualesp32-native-emulator-backend/files/virtualesp32-fw-manager.zip`); APK `~/workspace/your_files/virtualesp32-repaired.apk`.

### 1.2 What differs between the repaired ZIP and the base (complete list)

Only **3 of 78 entries differ**; the other 75 files are identical in name and size.

| File | Base | Repaired | Change |
|---|---|---|---|
| `virtual-esp32/MERGE_NOTES.md` | absent | 1,928 bytes | **Added** |
| `virtual-esp32/app/src/main/AndroidManifest.xml` | 3,298 bytes | 3,130 bytes | Changed |
| `virtual-esp32/app/src/main/java/dev/virtualesp32/BridgeCommandReceiver.java` | 13,829 bytes | 12,393 bytes | Changed |

### 1.3 `BridgeCommandReceiver.java` — diff summary

Paths: `virtual-esp32/app/src/main/java/dev/virtualesp32/BridgeCommandReceiver.java` in both ZIPs.

- **Imports:** repaired adds `import android.net.Uri;` and `import java.io.File;`.
- **Javadoc:** base's 14-line design doc replaced with one line: "Handles Ghost Tap IPC commands directly from explicit broadcasts."
- **Bus listener:** `ensureBusListener` becomes `synchronized`; the three event-forwarding blocks lose their `try/catch (Exception ignored)` wrappers.
- **Logging:** `log()` switches to try-with-resources.
- **`sendEvent` signature:** base `sendEvent(context, action, Intent base)` → repaired `sendEvent(context, Intent event)`; all call sites updated.
- **LOAD_FIRMWARE — the core IPC repair (base ~L184–258 → repaired L74–116):** base reads only `intent.getStringExtra("path")` and fails with `"LOAD_FIRMWARE failed: no path provided"` if absent, then blindly trusts the path and fabricates validation summary `"Loaded (N KB)"`. Repaired reads, in order: Intent data URI → `"uri"` string extra → legacy `"path"` extra; if none, error `"LOAD_FIRMWARE failed: no content URI or legacy path supplied"`; then validates via `FirmwareImageManager.importAndInspect(context, uri)`, rejects invalid images with `"LOAD_FIRMWARE rejected: <summary>"`, stores the imported file path + inspector summary in VirtualHal, and reports real imported-file fields (path, filename, size, inspector summary) in the `FIRMWARE_LOADED` event.
- **START_GUEST (repaired L117–134):** drops the base's try/catch; checks `start()` returns `"running"` AND `FirmwareEmulatorRuntime.running()` is true, else `"START failed: runtime did not reach running state (…)"`.
- **RESET_GUEST (repaired L142–160):** errors `"RESET failed: no firmware imported in VirtualESP32 app"` when no path; verifies the running state after restart like START. (Base silently did nothing and still sent `GUEST_RESET` when no path existed.)
- **SEND_UART (repaired L162–171):** first checks `running()`; error text `"SEND_UART failed: guest UART is not connected"`.
- **WRITE_GPIO (repaired L173–193):** errors `"WRITE_GPIO failed: guest is not running"` when stopped; pin-4 errors `"WRITE_GPIO pin 4 failed: …"`; any other pin gets explicit `"WRITE_GPIO failed: GPIO N is not supported by this bridge"`. (Base: pin 4 only, no running check, silent no-op for other pins.)
- **READ_GPIO (repaired L207–227):** errors `"READ_GPIO failed: guest is not running"` when stopped; pin-2 throwables produce `"READ_GPIO pin 2 failed: …"`; other pins get the explicit "not supported" error. (Base: pin 2 with value −1 default, silently swallowed throwables, other pins silently ignored.)
- **Removed entirely:** the `EJECT_FIRMWARE` and `USE_BUILTIN_FIRMWARE` command cases (including builtin-backup save/restore logic) — no equivalent in the repaired file. The manifest's intent-filter drops both actions too; the exported receiver now declares only the commands the repaired receiver handles.
- **Added:** a `default:` case — `"Unsupported bridge command: <action>"` error + log line.

### 1.4 `MERGE_NOTES.md` summary (what it claims was repaired)

- **Selected base:** `virtualesp32-bridge9.zip`, chosen for the newer Ghost Tap receiver/service, event bus, guest GPIO monitor, reconnect-capable UART bridge, and the larger ARM64 native library exporting `nativeGetGpio2`/`nativeSetGpio4`. All Bridge9 project files retained; `final.zip` compared file-by-file, no unique project files.
- **Applied repairs (6 bullets):** (1) `LOAD_FIRMWARE` added to the exported receiver's manifest filter; (2) receiver accepts the controller's `uri` extra and Intent data URI, imports via `FirmwareImageManager.importAndInspect`, keeps legacy `path` compatibility; (3) firmware-loaded response fields come from the actual imported file/inspector summary, no fabricated validation claims; (4) guest start/reset responses only sent after runtime returns `running` and running flag is true; (5) reset without an imported image returns an error; (6) unsupported GPIO ops and ops attempted while stopped return explicit errors.
- **Known verification boundary (stated in the notes themselves):** the Bridge9 ARM64 `.so` exports the GPIO JNI entry points, but checked-in `esp32_qemu_jni.c` does not define those two entry points and the checked-in CMake defaults to a stub build — a fully reproducible native rebuild is **not** established by this merge. (The notes do NOT mention the `nativeStart` arity mismatch — see §4.)

### 1.5 APK built from repaired ZIP, not base — evidence

Checked by piping `classes2.dex` out of the APK through `strings` and grepping (no APK extraction to disk needed):

| String | Source | In APK dex? |
|---|---|---|
| `no content URI or legacy path supplied` | repaired only | ✅ 1 hit |
| `importAndInspect` | repaired only | ✅ 1 hit |
| `runtime did not reach running state` | repaired only | ✅ 1 hit |
| `Unsupported bridge command` | repaired only | ✅ 1 hit |
| `is not supported by this bridge` | repaired only | ✅ 1 hit |
| `LOAD_FIRMWARE REJECTED` | repaired only | ✅ 1 hit |
| `firmwareImageSummary` | repaired only | ✅ 1 hit |
| `no path provided` | base only | ❌ 0 hits |
| `Built-in test firmware` / `Saved builtin backup` / `No built-in firmware available` | base only | ❌ 0 hits |
| `EJECT_FIRMWARE` / `USE_BUILTIN_FIRMWARE` | base only | ❌ 0 hits |

The `.so` inside the APK is byte-identical to the `.so` in both ZIPs, so the native library cannot discriminate base vs repaired — the discrimination comes entirely from the dex strings above.

**What this proves:** the APK's Java code is the repaired receiver, not the base receiver. **What it cannot prove by itself:** that this APK file is the artifact of workflow run 42130846133 — that link is established separately: the artifact (26,270,163 bytes, SHA256 `7a49d096…`) was downloaded from that run's Artifacts page via the signed-in browser session, the ZIP's SHA256 matched the run page, and the APK extracted from it has the SHA256 above. A rebuild from the same source would produce the same strings, so strings alone cannot prove byte-for-byte source identity — only that the compiled code is the repaired code.

---

## 2. IPC repair in the APK — verification (detail)

File: `~/workspace/your_files/virtualesp32-repaired.apk` — size 82,059,723 bytes, SHA256 `c3516f8b4fb144c87e726f3e3893307cd5876ff65d45491c880ff4ab8baffe24`, both recomputed and matching.

Two independent methods, both agree (raw dex bytes via `grep -a -o -F`, and `strings -a`):

| # | String | classes2.dex | Quoted match |
|---|---|---|---|
| 1 | `no content URI or legacy path supplied` | PRESENT (×1) | `<LOAD_FIRMWARE failed: no content URI or legacy path supplied` |
| 2 | `LOAD_FIRMWARE rejected: ` | PRESENT (×1) | `LOAD_FIRMWARE rejected: ` |
| 3 | `START failed: runtime did not reach running state` | PRESENT (×1) | `3START failed: runtime did not reach running state (` |
| 4 | `RESET failed: no firmware imported` | PRESENT (×1) | `6RESET failed: no firmware imported in VirtualESP32 app` |
| 5 | `is not supported by this bridge` | PRESENT (×1) | `  is not supported by this bridge` |
| 6 | `LOAD_FIRMWARE failed: no path provided` (OLD) | ABSENT (×0, both methods, both dex files) | — |
| 7 | `importAndInspect` | PRESENT (×1) | `importAndInspect` |
| 8 | `FIRMWARE_LOADED` | PRESENT (×1) | `&dev.virtualesp32.event.FIRMWARE_LOADED` |
| 9 | `GUEST_STARTED` | PRESENT (×2) | `EVENT_GUEST_STARTED` and `$dev.virtualesp32.event.GUEST_STARTED` |

(Leading `<`, `3`, `6`, `&`, `$` characters are dex MUTF-8 length-prefix bytes, not part of the strings.)

Supporting identifiers in `classes2.dex`: `BridgeCommandReceiver` ×5, `FirmwareImageManager` ×5, `getData` ×1, `getStringExtra` ×1, `LOAD_FIRMWARE` ×6, `uri` ×7, `legacyPath` present. Class descriptors confirmed: `Ldev/virtualesp32/BridgeCommandReceiver;` (plus `$1` inner class) and `Ldev/virtualesp32/FirmwareImageManager;` (plus `$Inspection`). Additional LOAD_FIRMWARE literals: `LOAD_FIRMWARE FAILED: missing URI/path`, `LOAD_FIRMWARE OK: `, `LOAD_FIRMWARE REJECTED: `, `dev.virtualesp32.action.LOAD_FIRMWARE`. No `content://` literal in the dex (expected — URIs arrive at runtime, not as literals). `classes.dex` (564-byte stub) contains none of the nine strings.

Source cross-check (repaired ZIP, `virtual-esp32/app/src/main/java/dev/virtualesp32/BridgeCommandReceiver.java`, 12,393 bytes) — every string matches the dex exactly, zero discrepancies:

| String | In source? | Location |
|---|---|---|
| `no content URI or legacy path supplied` | YES | line 90 |
| `LOAD_FIRMWARE rejected: ` | YES | line 97 |
| `START failed: runtime did not reach running state` | YES | line 128 |
| `RESET failed: no firmware imported` | YES | line 147 |
| `is not supported by this bridge` | YES | lines 190 and 223 |
| `LOAD_FIRMWARE failed: no path provided` (OLD) | ABSENT | — |
| `importAndInspect` | YES | line 95: `FirmwareImageManager.importAndInspect(context, selectedUri);` |
| `FIRMWARE_LOADED` | YES | line 106 |
| `GUEST_STARTED` | YES | line 132 |

Repair logic confirmed in source (lines 74–90): `Uri selectedUri = intent.getData();` → if null, `intent.getStringExtra("uri")` → if still null, legacy `intent.getStringExtra("path")` wrapped via `Uri.fromFile` (comment: "Backward compatibility with older controllers that sent a file path.") → if still null, the new error string.

**Verdict: YES — the IPC repair IS compiled into this APK.**
**Limit:** string presence proves the code is present, not that it executes correctly at runtime. Runtime execution is proven separately by the field evidence (§7): Ghost Tap's `LOAD_FIRMWARE` TX line with the `content://com.ghosttap.fileprovider/firmware/virtualesp32-test-micropython-4mb.bin` URI, followed by a successful import and boot.

---

## 3. Firmware execution + UART

Base path in this section: `J = virtual-esp32/app/src/main/java/dev/virtualesp32/`, `C = virtual-esp32/app/src/main/cpp/`.

### 3.1 Validation rules — `J/FirmwareImageManager.java` (66 lines)

`importAndInspect` (lines 17–28): copies the content URI to `<filesDir>/esp32-qemu/flash-image.importing`, runs `inspect()` on it; if invalid, deletes the temp file and returns invalid; if valid, deletes any prior `flash-image.bin` and renames the temp into place. No execution happens here.

`inspect(File)` (lines 29–39) — the exact rules, in order:
1. **Size (line 30):** must be exactly 2, 4, 8, or 16 MiB. Reject: `"QEMU ESP32 expects a 2, 4, 8, or 16 MiB flash image; this file is "+size+" bytes."` — this is exactly why `ESP32_GENERIC-20260406-v1.28.0.bin` (1,760,192 bytes) does not load.
2. **Bootloader header (line 31):** byte at offset `0x1000` must be `0xE9`. Reject: `"No ESP image header at bootloader offset 0x1000. Select an ESP-IDF merged flash image, not a standalone app .bin."`
3. **Partition table (lines 33–35):** walks 32-byte entries from `0x8000`; stops at `0xFF 0xFF` (erased) or `0xEB 0xEB` (MD5 entry) or at the first entry whose magic is not `0xAA 0x50`. Only entries with `type==0` (app) count: reads the 32-bit LE offset at bytes +4..+7, counts it only if `offset < size` and the byte at that offset is `0xE9`. Records `firstApp` and `apps` (count).
4. **No app partition (line 36):** `apps==0` → reject: `"Bootloader header found, but the default partition table at 0x8000 has no app partition with an ESP image header."`
5. **Valid (line 37):** summary `"Merged-image structure found: %d MiB flash, bootloader @0x1000, %d app image(s) in partition table (first @0x%x). Structural check only; full firmware/SoC compatibility is not proven."` — the code itself declares the check structural only. It does NOT validate app-image contents, entry point, flash-mode/size header fields, or SoC compatibility.
6. **Catch-all (line 38):** `"Could not inspect flash image: "+e.getMessage()`.

Supporting: `efuseBackingFile` (lines 40–44) creates a 124-byte zeroed `efuse.bin`, throws unless length is exactly 124. `esp32RomDir` (lines 45–66) copies `esp32-v3-rom.bin` + `esp32-v3-rom-app.bin` from assets if missing or <400,000 bytes; throws if a copy is <400,000 bytes or unreadable.

### 3.2 Start — `J/FirmwareEmulatorRuntime.java` (26 lines, all `synchronized`)

- `state` is a `static String`, initialised `"stopped"` (line 8). `running()` (line 11) is just `"running".equals(state)`. `status()` returns the raw string.
- `start()` (lines 13–22): (1) if `running()`, return `"running"` immediately — no revalidation; (2) if `!ClassicEsp32QemuBackend.available()`, set `state="unavailable"`, persist it, return the availability string (no throw); (3) **re-inspects** the flash file — invalid → throw `IllegalArgumentException(summary)`, state left as-is; (4) `uart=new EmulatorUartBridge(context); int port=uart.start()` (line 18); (5) `romDir=esp32RomDir()` + log `"ESP32 ROM dir ready: …"` (lines 19–20); (6) `backend.start(flash, efuseBackingFile, romDir, port)` — only on success: `state="running"`, persist, log `"Classic ESP32 QEMU session started"`, start `GuestGpioMonitor`, `BridgeEventBus.postGuestStarted()`, return `"running"`; (7) on any exception: `uart.close(); uart=null; backend=null; state="error"`; persist; `postError("Guest start failed: …")`; rethrow.
- `J/ClassicEsp32QemuBackend.java` `start()` (lines 18–30): throws if the native lib isn't loaded; throws `"QEMU session is already running"` if `handle!=0`; `handle=nativeStart(flash, efuse, romDir, uartPort)` (line 22); `handle==0` → `nativeGetLastError()` → throw `IllegalStateException(detail)` or `"QEMU native backend failed to create a machine"`.
- `C/esp32_qemu_jni.c` `nativeStart` (lines 121–180): `validate_start_args` re-checks size (2/4/8/16 MiB), read-write access, eFuse 124 bytes, port 1–65535; if `QEMU_CORE_AVAILABLE` is undefined → `set_last_error("QEMU core not compiled into this APK…")`, return 0; else allocates session and calls `qemu_driver_start`.
- `C/qemu_driver_real.c` `qemu_driver_start`: spawns the QEMU worker thread, then spins up to 100×50 ms = **5 s** waiting for native state RUNNING/ERROR; **if still STARTING after 5 s it returns 0 (success) anyway** — comment: *"Leave it running; the Java layer polls status()"* — but **no Java code ever polls native state** (verified by grep: no JNI getter for it exists).

**What "running" actually proves:** a native session object was allocated, a QEMU worker thread was created, and `qemu_init` did not report a synchronous error inside the 5 s window (or the window expired). **What it does NOT prove:** that `qemu_init` completed, that the machine was created, that the guest CPU executed one instruction, that the bootloader ran, that the app booted, that UART connected, or that the guest is still alive. The proof of real execution in the 16:05 screenshots is the guest's own UART output (MicroPython banner + `>>>`), not the flag.

### 3.3 UART — `J/EmulatorUartBridge.java` (176 lines)

- **The app is the TCP server; QEMU is the client.** `start()` opens `new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))` (line 37) — an ephemeral port on loopback only — starts a daemon accept thread, returns the port. QEMU is wired as client in `C/qemu_driver_real.c` with `-serial tcp:127.0.0.1:<port>` (also stated in the class Javadoc, lines 13–15: *"QEMU is configured as the socket client"*).
- **`"QEMU UART connected on loopback (#1)"`** (`EmulatorUartBridge.java:76`) = the accept loop accepted the first inbound TCP connection from QEMU's serial backend. `#N` is a per-bridge connection counter (`connectionSeq++`). The accept loop stays alive for the bridge lifetime; each new connection replaces the previous one (old socket closed) and gets its own reader thread; only the latest connection's data is shown.
- **Guest → app:** each reader `read()` (≤1024 bytes) appends to `hal` key `"uart"` (capped at 32,768 chars, tail-kept), logs the chunk with tag `UART` (literal `"QEMU UART data"` if the chunk is blank/whitespace-only), calls `BridgeEventBus.postUartRx(chunk)` (line 103), and broadcasts `dev.virtualesp32.STATE`.
- **App → guest:** `sendHostText` writes to the latest live socket on a single-thread executor; returns `false` + logs `"HOST UART TX failed: no guest socket"` if there is no socket; on `IOException` it drops the dead stream so a reconnect can replace it.
- **To Ghost Tap:** `BridgeEventBus.onUartRx` is re-broadcast as `dev.virtualesp32.event.UART_RX` (extra `"data"`, package `com.ghosttap`) by listeners in `J/BridgeCommandReceiver.java:23–28` and `J/VirtualEsp32BridgeService.java:58–61`. **Gap (verified, see §8 D5):** the receiver's bus listener is registered only by `ensureBusListener`, called only from the receiver's `START_GUEST`/`RESET_GUEST` (`BridgeCommandReceiver.java:125,151`). If the guest was started from the app UI's "Run firmware" button, that listener is never registered, so Ghost Tap gets no `UART_RX` events via the receiver path (only via the bridge service if it was created).

**The 16:05 log lines in `231_12_p3b1.png`, in source terms:**
- `16:05:07 EMU "Merged-image structure found: 4 MiB flash, bootloader @0x1000, 1 app image(s) in partition table (first @0x10000). Structural check only; …; imported only, not executed"` — `inspect()` summary (`FirmwareImageManager.java:37`) + `"; imported only, not executed"` appended by the import path.
- `16:05:40 EMU "ESP32 ROM dir ready: /data/user/0/dev.virtualesp32/files/esp32-qemu"` — `FirmwareEmulatorRuntime.java:20`.
- `16:05:40 EMU-UART "QEMU UART connected on loopback (#1)"` — `EmulatorUartBridge.java:76`, first QEMU TCP connect.
- `16:05:40 EMU "Classic ESP32 QEMU session started"` — `FirmwareEmulatorRuntime.java:21`.
- `16:05:41–46 UART <fragment>` lines (`"e"`, `"ts Jul 29 2019 1"`, `"2:21:46"`, `"rst:0x…"`, `"1 (POWERON_RESET),boot:0x12 (SPI_FAST_FLASH_BOOT)"`, etc.) — each TCP `read()` chunk is logged as its own line, so the ROM banner `"ets Jul 29 2019 12:21:46"` arrives split across reads. Fragmentation is normal TCP chunking, not corruption.
- `16:05:44 UART "QEMU UART data"` — a whitespace-only chunk (the reader logs that literal string when `chunk.trim().isEmpty()`).
- `16:05:46 UART "MicroPython v1.28.0 on 2026-04-06; Generic ESP32 module with ESP32"` + `>>>` — genuine guest firmware output: the guest CPU really executed the MicroPython app. This is the execution proof.

---

## 4. Native QEMU loading

### 4.1 Java side — `J/ClassicEsp32QemuBackend.java` (43 lines)

Load + error handling (verbatim):
- `:11-15` static initializer: `try { System.loadLibrary("esp32_qemu_android"); loaded=true; } catch (UnsatisfiedLinkError e) { error="Native classic-ESP32 QEMU library is not packaged"; }` → `LOADED` / `LOAD_ERROR`.
- `:17` `static boolean available(){return LOADED;}`
- `:18` `static String unavailableReason(){return LOADED?"":"Native classic-ESP32 QEMU library is not packaged; firmware import and validation work, execution does not.";}`
- `:20` `if(!LOADED)throw new IllegalStateException(unavailableReason());`
- `:22` `handle=nativeStart(flashImage.getAbsolutePath(),efuseImage.getAbsolutePath(),romDir.getAbsolutePath(),uartPort);`
- `:23-27` handle==0 → `String detail=nativeGetLastError();` → throw `IllegalStateException(detail … ?"QEMU native backend failed to create a machine":detail)`.

Native declarations (exact):
- `:32` `private static native long nativeStart(String flashImage,String efuseImage,String romDir,int uartPort);` — **4 args**
- `:33` `private static native void nativeReset(long handle);`
- `:34` `private static native void nativeStop(long handle);`
- `:36` `private static native String nativeGetLastError();`
- `:39` `static native int nativeGetGpio2();`
- `:42` `static native void nativeSetGpio4(int level);`

Note: class Javadoc `:3-6` says "No QEMU binary is bundled yet; availability is false unless the native port is supplied" — **stale**, contradicted by the 81 MB `.so` in `jniLibs/`.

### 4.2 C side — `C/esp32_qemu_jni.c` (217 lines)

Header comment, `:3-7` (verbatim):
```
 * Java contract (dev.virtualesp32.ClassicEsp32QemuBackend):
 *   nativeStart(flashImage, efuseImage, uartPort) -> long handle (0 = failure)
 *   nativeReset(handle)
 *   nativeStop(handle)
 *   nativeGetLastError() -> String with the precise failure reason
```

JNI functions defined — **exactly 4**, no GPIO natives anywhere under `app/src/main/cpp/` (grep for `JNIEXPORT|Java_dev` returns only these):
- `:121-125` `Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeStart(JNIEnv *env, jclass clazz, jstring flashImage, jstring efuseImage, jint uartPort)` — **3 args, no romDir**. Body: `validate_start_args`, then `#ifndef QEMU_CORE_AVAILABLE` returns 0 with "QEMU core not compiled into this APK…", else allocates `qemu_session_t` and calls `qemu_driver_start`.
- `:181-182` `…_nativeReset(JNIEnv *env, jclass clazz, jlong handle)`
- `:193-194` `…_nativeStop(JNIEnv *env, jclass clazz, jlong handle)`
- `:208-209` `…_nativeGetLastError(JNIEnv *env, jclass clazz)`

Header comment also documents a process-kill hazard (`:22-27` as read): "qemu_init() calls exit() on fatal argument/config errors. A bad machine configuration can therefore kill the app process instead of returning an error… there is no clean recovery hook in QEMU 9.x."

Supporting files: `qemu_session.h` (27 lines) defines the session struct with **only two** path buffers — `flash_path[4096]`, `efuse_path[4096]`, `uart_port`, `error_msg[1024]` — **no `rom_dir` field** (computed size ≈ 0x243C). `CMakeLists.txt`: STUB is the default (`QEMU_CORE_AVAILABLE` OFF); FULL requires `-DQEMU_CORE_AVAILABLE=ON -DQEMU_BUILD_DIR=<dir>`. `app/build.gradle` (24 lines) has **no `externalNativeBuild`/NDK block** — its comment states the workflow "builds the Java/Kotlin app only" and the `.so` is a prebuilt in `jniLibs/`, never rebuilt by CI.

### 4.3 `nm -D` on both `.so` copies — identical

| | ZIP copy | APK copy |
|---|---|---|
| Size | 81,361,024 bytes | 81,361,024 bytes |
| SHA256 | `207d50ac9711d89f9c40350fb5151ad966170efe377f3de2078bbb2e89ea4a75` | `207d50ac9711d89f9c40350fb5151ad966170efe377f3de2078bbb2e89ea4a75` |
| Byte-identical | yes | yes |

`nm -D --defined-only … | grep Java_` — **identical output for both** (the prior audit's "6 symbols" claim verified):
```
0000000000f0c6d0 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeStart
0000000000f0cb58 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeReset
0000000000f0cbb4 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeStop
0000000000f0cc28 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetLastError
0000000000f0ccc8 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetGpio2
0000000000f0ccd0 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeSetGpio4
```
(Total dynamic symbols: 25,308 — the `.so` exports the whole QEMU symbol table, not just the 6 JNI entries. `readelf -h`: ELF 64-bit AArch64, DYN. `NEEDED`: `liblog.so`, `libm.so`, `libz.so`, `libdl.so`, `libc.so`. Only ABI present is `arm64-v8a`.)

`strings` checks on the shipped `.so`: `"UART loopback port out of range"` present (1×), `"flash image must be"` present (1×), `"QEMU core not compiled"` **absent** (0× — FULL build, not the CMake stub), `"romDir"` present (in `.debug_str`).

### 4.4 Decisive evidence — disassembly + DWARF of the shipped `.so`

System `objdump` has no AArch64 support, so `nativeStart` (0xf0c6d0–0xf0cb58) was disassembled with Capstone. JNI args arrive as x0=env, x1=clazz, x2=flashImage, x3=efuseImage, x4=romDir, x5=uartPort:
- `f0c704: mov w25, w5` — **port is read from w5** (the 6th JNI arg = 4th Java arg)
- `f0c708: mov x24, x4` — romDir jstring saved; `f0c70c: mov x20, x3` (efuse); `f0c6f4: mov x22, x2` (flash)
- **Three** `GetStringUTFChars` calls via JNIEnv offset `0x548` (flash, efuse, romDir) and **three** matching `ReleaseStringUTFChars` calls via offset `0x550`
- Port range check at `f0c958` operates on `w25` (the w5 value)
- Session: `calloc(1, 0x3438)`; `strncpy(session+0x34, flash)`, `strncpy(session+0x1034, efuse)`, `strncpy(session+0x2034, romDir)`, `str w25, [session, #0x3034]` (uart_port) — i.e. the binary's session struct has **three** 0x1000-byte path buffers and is 0x3438 bytes, vs the shipped source's two buffers / ≈0x243C.

DWARF (the `.so` ships full debug info) confirms it independently:
- `nativeStart` DIE: `DW_AT_decl_line: 163`, parameters in order `env`, `clazz`, `flashImage` (164), `efuseImage` (165), **`romDir` (166)**, `uartPort` (167) — **six parameters, the 4-arg Java form**
- `nativeGetGpio2` DIE at decl_line 273, `nativeSetGpio4` at decl_line 283, same compilation unit (decl_file 1)
- CU: `DW_AT_name: ../android-jni/esp32_qemu_jni.c`, `DW_AT_comp_dir: /home/hatch/workspace/virtualesp32/native/qemu-src/build`

**The shipped `.so` was compiled from a newer revision of the same file (`…/native/qemu-src/android-jni/esp32_qemu_jni.c`, ≥283 lines) on this VM — not from the 217-line copy in the ZIP.**

### 4.5 Assessment

**(a) 6-vs-4 symbol gap — reproducibility gap only, NOT a runtime risk for the shipped binary.** The shipped `.so` exports all 6 symbols the Java class declares, and JNI resolves natives by name at first call, so every declared native binds. The gap is that the ZIP's C source cannot reproduce the shipped `.so`: it lacks both GPIO natives, has a different `nativeStart`, and a different session struct. **But** the gap becomes a live runtime break if anyone rebuilds from the ZIP's C source and ships that `.so`: (i) first `nativeGetGpio2`/`nativeSetGpio4` call throws `UnsatisfiedLinkError` — caught at every call site (`Throwable`), so it degrades silently (GPIO monitor polls forever; WRITE_GPIO/READ_GPIO return ERROR broadcasts; UI toggle logs "HOST GPIO4 inject failed"); (ii) worse, `nativeStart` would misread the port (see (b)) and START would fail outright; (iii) the CMake default is STUB mode, so a naive rebuild yields a `.so` whose `nativeStart` always returns 0 with "QEMU core not compiled into this APK". The prior audit's D.4 verdict stands, with one correction below.

**(b) nativeStart 4-vs-3 — a REAL mismatch between Java and the shipped C source (not just a stale comment — the C function definition itself at `:121-125` takes 3 args), but NOT a mismatch between Java and the shipped `.so`.** The prior audit left this "UNVERIFIED from symbols alone" (D.4); it is now resolved — the shipped binary's `nativeStart` **does** take 4 args (disassembly + DWARF above), and the binary's exact source is identified: `../android-jni/esp32_qemu_jni.c` (≥283 lines) built under `/home/hatch/workspace/virtualesp32/native/qemu-src/build`. Had the shipped `.so` been built from the ZIP's C file, the JVM would still find the symbol by name but the C code would read the port from w4 — which holds the romDir pointer — truncating it to an int almost certainly outside 1–65535, so `validate_start_args` would fail with "UART loopback port out of range" and every START would fail. The field-test evidence (MicroPython booting 2026-11-05 ~16:05) is consistent with the binary being the 4-arg form.

**(c) Missing/mismatched `.so` at runtime — fully traced, graceful, never a crash via the load path:**
- `.so` absent, wrong ABI (only `arm64-v8a` ships), or missing a `NEEDED` lib → `System.loadLibrary` throws `UnsatisfiedLinkError`, caught in the static init → `LOADED=false`. UI: firmware panel shows "Unavailable: Native classic-ESP32 QEMU library is not packaged; firmware import and validation work, execution does not."; "Run firmware" shows AlertDialog "Firmware was not started" with that text. IPC: START returns ERROR broadcast "START failed: runtime did not reach running state (Unavailable: …)". Import/validation keep working.
- `.so` present but an individual symbol missing (i.e. a rebuild from shipped C) → `UnsatisfiedLinkError` only on first invocation of that native, caught as `Throwable` at every call site (receiver, service, monitor, UI toggle) → ERROR broadcasts or silent no-op, never a crash.
- `.so` present but built from shipped C → `nativeStart` returns 0 with "UART loopback port out of range" → `IllegalStateException` → runtime posts "Guest start failed: UART loopback port out of range" and the UI/IPC surfaces it.
- One residual hazard unrelated to the mismatch: per the C header comment, a fatal `qemu_init()` error calls `exit()` and kills the app process with no recovery hook.

### 4.6 Runtime load-path trace (source, with file/line)

**First touch of the native library.** `MainActivity.java:20` `onCreate` ends with `refresh()`; `refresh()` (`:56`) renders `FirmwareEmulatorRuntime.backendAvailability()` into the firmware panel → `FirmwareEmulatorRuntime.java:12` `ClassicEsp32QemuBackend.available()` → first class load → static init → `System.loadLibrary("esp32_qemu_android")` (`ClassicEsp32QemuBackend.java:12`). If the app is woken by broadcast instead of UI, first touch is `FirmwareEmulatorRuntime.start()` (`:16`, the `available()` check) or the receiver's direct `nativeSetGpio4` call (`BridgeCommandReceiver.java:180`).

**UI start path.** `MainActivity.java:37` "Run firmware" button → `runFirmware()` (`:55`): no image → AlertDialog "Import and validate a merged ESP32 flash image first." Otherwise `FirmwareEmulatorRuntime.start(this, new File(path))`. In `FirmwareEmulatorRuntime.java:13-25`: if already running return `"running"`; if `!available()` → `state="unavailable"`, HAL stores it, return `backendAvailability()` string → `runFirmware()` shows AlertDialog "Firmware was not started" with that string. If available: image inspected, `EmulatorUartBridge` started (ephemeral loopback port), `romDir=FirmwareImageManager.esp32RomDir(context)`, then `backend.start(flash, efuseBackingFile, romDir, port)` (`:18-21`). On any exception: UART closed, backend nulled, `state="error"`, `BridgeEventBus.postError("Guest start failed: "+e.getMessage())`, exception rethrown → `runFirmware()` catch → AlertDialog "Firmware was not started" + `e.getMessage()`.

**IPC start path.** `BridgeCommandReceiver.java:64` `onReceive`, `try {` at `:72`, `START_GUEST` case `:117-135`: no imported firmware → `sendError("START failed: no firmware imported in VirtualESP32 app")`; else `FirmwareEmulatorRuntime.start(...)` (`:126`); result not `"running"` → `sendError("START failed: runtime did not reach running state (" + result + ")")` (`:128`) + log; success → broadcast `GUEST_STARTED`. `RESET_GUEST` (`:142-161`) is stop-then-start with the same checks. The outer `catch (Exception e)` (`:232-235`) logs `HANDLER EXCEPTION: …` and sends `dev.virtualesp32.event.ERROR` ("Command failed: …") to `com.ghosttap` — so a `nativeStart` failure surfacing as `IllegalStateException` is caught here, never crashes the receiver.

**GPIO native calls.** `BridgeCommandReceiver.java:180` `nativeSetGpio4` inside `try/catch (Throwable)` → on failure `sendError("WRITE_GPIO pin 4 failed: " + t.getMessage())` (`:187`); `:213` `nativeGetGpio2` inside `try/catch (Throwable)` → `sendError("READ_GPIO pin 2 failed: …")` (`:215`). `GuestGpioMonitor.java:51` polls `nativeGetGpio2()` every 250 ms inside `try/catch (Throwable) { continue; }` (`:22-25`) — a missing symbol would silently poll forever, doing nothing. `MainActivity.java:32` GPIO4 toggle also wraps it in `try/catch (Throwable)` → logs `"HOST GPIO4 inject failed: " + t`.

**Dead path.** `VirtualEsp32BridgeService.java:122-191` handles the same actions in `onStartCommand` (START at `:122-134` with its own `try/catch → postError`, WRITE_GPIO `:155-172`, READ_GPIO `:178-190`), but **nothing starts it**: the only `startService`/`startForegroundService` calls in the app target `VirtualDeviceService` (`MainActivity.java:53-54`); the manifest declares the bridge service exported, but Ghost Tap uses explicit broadcasts to `BridgeCommandReceiver` (exported, manifest intent-filter lists all 8 actions). Confirms the prior audit's Finding 4 (bridge service is dead code; receiver is the live path).

---

## 5. Guest lifecycle — both paths, and where state can diverge

**App UI (`J/MainActivity.java`):**
- "Run firmware" → `runFirmware()` (line 55): empty `firmwareImagePath` → dialog `"Import and validate a merged ESP32 flash image first."`; else `FirmwareEmulatorRuntime.start(...)`; if `!running()` → dialog `"Firmware was not started"` with the result string.
- "Reset guest" (line 37) → `FirmwareEmulatorRuntime.reset()` + `refresh()` — **soft reset only** (see below).
- "Stop guest" (line 37) → `FirmwareEmulatorRuntime.stop()` + `refresh()` — full teardown.
- "Power / Reset" (line 26, handler line 31): toggles `hal.powered`; powering off calls MainActivity's private `stop()`, which only sends `ACTION_STOP` to `VirtualDeviceService` (**BLE stop only**); powering on just refreshes. **It does not touch the QEMU runtime at all.**
- "Soft reset / reboot runtime" (line 39): `hal.softReset()` (bootCount+1, `wifiApplied=false` — `J/VirtualHal.java`) then BLE stop + restart after 600 ms. **Despite the name, it does not reset the guest either.**
- Import (`onActivityResult`, line 58): `importAndInspect`; valid → sets `firmwareImagePath`/`firmwareImageSummary`, logs `"…; imported only, not executed"`, dialog `"Merged image imported"` + summary + `backendAvailability()`. The `"Runtime: stopped. Firmware execution is reported running only after a native QEMU machine is created."` text in `230_13_ay1k.png` comes from `refresh()` (line 56).

**Runtime primitives (`J/FirmwareEmulatorRuntime.java`):**
- `reset()` (line 23): `if(backend!=null){backend.reset(); log "QEMU system reset requested";}` — **no state change, no event**; if `backend==null` it is a **silent no-op**. Native side: `qemu_system_reset_request(SHUTDOWN_CAUSE_HOST_QMP_SYSTEM_RESET)` (`C/qemu_driver_real.c` `qemu_driver_request_reset`). Same QEMU session, same flash, no revalidation.
- `stop()` (line 24): `backend.stop()`+null, `uart.close()`+null, `gpioMonitor.stop()`+null, `state="stopped"`, persist pref, `postGuestStopped()`.

**Receiver (`J/BridgeCommandReceiver.java`):**
- `START_GUEST` (117–134): empty path → `"START failed: no firmware imported in VirtualESP32 app"`; `ensureBusListener`; `start()` must return `"running"` **and** `running()` must be true, else `"START failed: runtime did not reach running state ("+result+")"`; else sends `GUEST_STARTED`.
- `STOP_GUEST` (136–140): unconditional `stop()`; sends `GUEST_STOPPED`.
- `RESET_GUEST` (142–160): **`stop()` first (full teardown incl. UART close, monitor stop, `state="stopped"`, `postGuestStopped`), then re-reads the path and `start()`s again**; must reach running else `"RESET failed: runtime did not return to running state (…)"`; else sends `GUEST_RESET`. This is a full stop+start — **different semantics from the UI/service reset**.
- `SEND_UART` (162–171): `"SEND_UART failed: guest UART is not connected"` unless `running() && writeUart(...)`.

**Service (`J/VirtualEsp32BridgeService.java`):** `ACTION_START_GUEST` (~123–133) same start, errors via `postError`, but **does not verify the result is running**; `ACTION_STOP_GUEST` (135–137) `stop()`; `ACTION_RESET_GUEST` (138–141) **`reset()` only (soft reset) + `postGuestReset()`** — different from the receiver's stop+start. `VirtualDeviceService` never touches `FirmwareEmulatorRuntime` (it is BLE/provisioning only).

**State stores (verified by grep):**
- (a) `FirmwareEmulatorRuntime` static `state` — the **only** thing `running()`/`status()` read; in-memory, re-initialises to `"stopped"` on process restart.
- (b) SharedPreferences key `"firmwareEmulatorState"` — written on every transition (`running`/`unavailable`/`error`/`stopped`) but **never read by any code** (grep finds writes only). Dead telemetry.
- (c) `ClassicEsp32QemuBackend.handle` — nonzero while a native session exists; cleared only by `stop()`.
- (d) Native `qemu_session_t.state` (`C/qemu_session.h`: STARTING/RUNNING/STOPPING/STOPPED/ERROR) — **never exposed to Java; no JNI getter exists**.

**Out-of-sync states identified:**
1. **Guest panic/reboot is invisible.** The 16:10 crash (`236_9_ewi9.png`: `EXCCAUSE: 0x00000007`, `"Rebooting..."`, then `rst:0x…c (SW_CPU_RESET)`) reboots the guest CPU inside QEMU; Java `state` stays `"running"`; no event, no flag change. "Running" cannot distinguish a healthy guest from one in a crash loop.
2. **QEMU main-loop exit is invisible.** If `qemu_main_loop()` returns, the worker sets native state STOPPED, but Java `state` stays `"running"` forever — nothing polls it. `QUERY_STATUS` would keep saying RUNNING and the `READ_GPIO` gate would keep passing with no guest behind it.
3. **Process restart resets the flag** while the pref still says `"running"` (stale, but unread — so the visible effect is the app reporting STOPPED after a death; the native session dies with the process, so that is actually correct).
4. **Three different things called "reset":** receiver RESET = stop+start (new session, UART re-created, monitor restarted, flash re-inspected); service RESET and UI "Reset guest" = soft reset (same session); UI "Power / Reset" and "Soft reset / reboot runtime" = no guest effect at all. A controller mixing paths gets different behaviour for the same word.
5. **`reset()` is a silent no-op when not running** (no error, no event).
6. **`start()` early-returns `"running"` without revalidating** the flash file if already running.
7. **UART connectivity is not part of "running":** the bridge is created before `backend.start()`; if QEMU never connects its UART client, state is still `"running"`.

---

## 6. GPIO sync — and the 16:08 `READ_GPIO` failure

**Receiver (`J/BridgeCommandReceiver.java`):**
- `WRITE_GPIO` (173–193): gate `running()` else **`"WRITE_GPIO failed: guest is not running"`** (line 177); `pin==4` → `ClassicEsp32QemuBackend.nativeSetGpio4(high ? 1 : 0)` (line 180) + send `GPIO_CHANGED` (pin 4, `isOutput=false`); `Throwable` → `"WRITE_GPIO pin 4 failed: "+t.getMessage()`; any other pin → **`"WRITE_GPIO failed: GPIO "+pin+" is not supported by this bridge"`** (line 190). **Write = pin 4 only.**
- `READ_GPIO` (207–227): gate `running()` else **`"READ_GPIO failed: guest is not running"`** (line 210); `pin==2` → `ClassicEsp32QemuBackend.nativeGetGpio2()` (line 213) → send `GPIO_VALUE` (pin 2, value); `Throwable` → `"READ_GPIO pin 2 failed: "+t.getMessage()`; any other pin → **`"READ_GPIO failed: GPIO "+pin+" is not supported by this bridge"`** (line 223). **Read = pin 2 only.**
- `QUERY_STATUS` (195–205): see §6.1 below.

**Service path differs (`J/VirtualEsp32BridgeService.java`):** `WRITE_GPIO` (157–172) same gate/pin-4-only, but unsupported pins are **silently ignored** (no error, no event). `READ_GPIO` (175–190) sends **no error at all** — if not running or `pin!=2`, `value` stays `-1` and a `GPIO_VALUE` event with `value=-1` is still sent. So the exact string `"READ_GPIO failed: guest is not running"` **can only come from the receiver path**. (Note: the service is dead code — nothing starts it — so this inconsistency is low-impact in practice; see §8 D7.)

**Natives (`J/ClassicEsp32QemuBackend.java`):** `nativeGetGpio2()` (line 39): *"Returns 0 (LOW), 1 (HIGH), or -1 (unavailable / not an output)"*. `nativeSetGpio4(int)` (line 42): *"Injects an input level into emulated GPIO4 via QEMU's qdev input mechanism."*

**`J/GuestGpioMonitor.java` (72 lines):** started only in `start()` after `state="running"`; polls `nativeGetGpio2()` every **250 ms** (line 44); `Throwable` → silently `continue` (lines 52–54); `level<0` → skip (line 55); only on **change** vs `lastLevel` (init `-2`) → writes `hal` `"gpio"` JSON `{"2": high, "guest_driven": true}`, logs `"GUEST GPIO2 -> HIGH/LOW"`, `postGpioChanged(2, high, true)`, broadcasts STATE. It monitors **pin 2 only**, reports only changes, and only when the guest has configured the pin as an output — otherwise it observes `-1` forever and never fires. The stock MicroPython image drives no GPIO2 output, so the monitor never firing is consistent with Pin 2 showing `"(unknown)"` in `233_10_cd74.png`. Pin 4 `OFF` there is Ghost Tap's displayed default, not a measured value (no `WRITE_GPIO` evidence in the screenshots).

**Explanation of the 16:08 failure (`232_11_wksj.png`: red `"Problem: READ_GPIO failed: guest is not running"` beside `TARGET Status: RUNNING`):**
- The string matches receiver `READ_GPIO` line 210 exactly. That check fired means **at the instant that broadcast was handled, `FirmwareEmulatorRuntime.running()` was false** — i.e. static `state != "running"`. The gate sits **before** any native call.
- `QUERY_STATUS` reads the **same** flag in the **same process** (`j.put("status", running() ? "RUNNING" : "STOPPED")`, line 197; the manifest declares no `android:process` attributes, so receiver, service and activity share one process — cross-process static divergence is ruled out). Therefore the two displays **cannot be from the same instant**: the red Problem line is a sticky last-error, and the TARGET card is from a `QUERY_STATUS` at a different moment (before the failure, or after a later successful start). On the VirtualESP32 side there is no code path that returns RUNNING and "guest is not running" simultaneously.
- Plausible causes for the gate being false at that moment (source terms): (a) `READ_GPIO` arrived **before `START_GUEST` finished** — the window between the receiver handling START and `state="running"` covers flash re-inspection, UART bind, ROM copy and native start; (b) a **stop had happened** (UI "Stop guest", receiver/service `STOP_GUEST`) with a re-START later; (c) a `start()` failure left `state="error"` followed by a later successful start; (d) a receiver `RESET_GUEST` in flight — it calls `stop()` first, so state is `"stopped"` for the whole teardown+restart window and a concurrent `READ_GPIO` fails; (e) a VirtualESP32 **process restart** (statics reset to `"stopped"`) followed by a re-START. The screenshots alone cannot distinguish these; Ghost Tap's polling timing is outside this repo.
- **Ruled out — the 16:10 crash:** timestamps are 16:08 (failure) vs 16:10:13 (crash in `236_9_ewi9.png`) — the crash happened ~2 minutes **after** the failure, so it cannot be its cause. And independently, a guest CPU panic/reboot **never touches the Java flag** (QEMU keeps running, state stays `"running"`), so even a crash at the right time could not produce `"guest is not running"`. The failure is a host-side lifecycle/timing state, not a guest-side state.
- **Ruled out — the missing GPIO natives:** those would throw `UnsatisfiedLinkError` inside the `pin==2` branch (lines 213–216) and produce `"READ_GPIO pin 2 failed: …"`, a different string; the line-209 gate fires first.

### 6.1 Hardcoded vs measured status

`QUERY_STATUS` (`J/BridgeCommandReceiver.java:195–205`) and `buildTargetInfo()` (`J/VirtualEsp32BridgeService.java:87–100`) build the same JSON:
- `j.put("cpu", "Xtensa LX6 / Classic ESP32")` (receiver line 198) and `j.put("backend", "Native QEMU")` (line 199) — **string literals, never measured**. Only `"status"` (`"RUNNING"`/`"STOPPED"`, line 197) and `"firmware"` (`"running"`/`"not loaded"`, line 200) derive from `running()`. The service additionally hardcodes `peripherals: {uart: true, gpio2_output: true, gpio4_input: true}` (lines 95–98) — capability claims, not measurements.
- So Ghost Tap's `Processor: Xtensa LX6 / Classic ESP32` and `Running on: Native QEMU` are hardcoded labels (true by construction when the backend exists, but not probed), and `Software: running` is the Java flag only.

---

## 7. Screenshot dating discrepancy — the "3–4 days old / cache-related" claim does NOT hold up

The handoff prompt asserts the crash screenshots are from an older session ~3–4 days ago and the crash was "cache-related". Verified against the evidence — **that claim fails on every check**:

| Evidence | What it shows | Implication |
|---|---|---|
| Status-bar clocks in the screenshots | 4:05 (`230_13_ay1k.png`, `231_12_p3b1.png`), 4:08 (`232_11_wksj.png`), 4:10 (`233_10_cd74.png`, `236_9_ewi9.png`) | Taken 16:05–16:10 on the day they arrived |
| In-log timestamps | `16:05:07`–`16:05:46` in `231_12_p3b1.png`; `16:10:13`–`16:10:18` in `236_9_ewi9.png` | Log times match the status-bar clocks |
| File mtimes | All five PNGs: Nov 6 00:11 UTC = Nov 5 16:11 PST | Arrived together, minutes after the last log line |
| TX line in `233_10_cd74.png` | `TX: LOAD_FIRMWARE content://com.ghosttap.fileprovider/firmware/virtualesp32-test-micropython-4mb.bin` | Names the exact file Muse named to Justin at ~16:02, minutes before the screenshots arrived |
| Ghost Tap screens in `232_11_wksj.png` / `233_10_cd74.png` | LIVE TARGET card + "thinking engine" line | That UI exists only in the Nov 5 master build (Ghost Tap fixed3, run 42075309502, green 09:35 PST Nov 5) — it did not exist 3–4 days ago |
| MicroPython version in `231_12_p3b1.png` | `MicroPython v1.28.0 on 2026-04-06` | Matches the Drive file `virtualesp32-test-micropython-4mb.bin` / `ESP32_GENERIC-20260406-v1.28.0.bin` |
| Import popup in `230_13_ay1k.png` | "Merged image imported" + "Runtime: stopped. Firmware execution is reported running only after a native QEMU machine is created." | Matches the repaired build's `MainActivity.refresh()` text |
| Prior audit (written Nov 5 ~14:16 PST, before these screenshots) | "No guest firmware boot ever demonstrated" (section L) | Consistent with the boot being new at 16:05 today, not 3–4 days old |
| "Guru Meditation" wording | **Nowhere in any screenshot.** The dump is a Core 1 register dump + backtrace + "Rebooting..." | The handoff prompt's label does not match the evidence |
| "Cache-related" | **No mention of cache anywhere in any log line, screenshot, or source path involved.** The dump is a guest CPU exception (EXCCAUSE 0x00000007) followed by a clean `SW_CPU_RESET` reboot | Zero evidence for a cache cause |

**What would be needed to support "3–4 days old / cache-related":** file mtimes or log timestamps from Nov 1–2; any mention of cache in the logs; a different firmware version or an older app build visible in the screens. None of that exists. **Treat the 16:05 MicroPython boot as current evidence (first real guest boot ever demonstrated) and the 16:10 crash (Core 1 register dump, EXCCAUSE 7) as a CURRENT open defect with UNKNOWN cause.** Do not adopt "cache-related" without source evidence.

Timeline reconstructed from the screenshots (all Nov 5, PST):
- **16:05** — `230_13_ay1k.png`: merged image imported in the app's own UI ("Merged image imported" popup). `231_12_p3b1.png`: ROM dir ready, QEMU UART connected on loopback (#1), Classic ESP32 QEMU session started, MicroPython v1.28.0 boots to the `>>>` prompt.
- **16:08** — `232_11_wksj.png`: Ghost Tap connected; TARGET Status RUNNING on Native QEMU; red Problem `READ_GPIO failed: guest is not running`.
- **16:10** — `233_10_cd74.png`: Ghost Tap TX `LOAD_FIRMWARE` via `content://com.ghosttap.fileprovider/firmware/virtualesp32-test-micropython-4mb.bin`; Pin 2 (unknown), Pin 4 OFF; LIVE TARGET running; AI line "Your AI couldn't be reached — checked…". `236_9_ewi9.png`: guest Core 1 register dump (EXCCAUSE 0x00000007, backtrace `0x40091ccf:0x3ffbc420 0x4020881a:0x3ffbc440 0x40208870:0x3ffbc460 0x400968dc:0x3ffbc480`, ELF file SHA256 `cc85d995e`), "Rebooting...", then a clean boot (`ets Jul 29 2019 12:21:46`, `rst:0xc (SW_CPU_RESET)`, `boot:0x12 (SPI_FAST_FLASH_BOOT)`, segment loads, `entry 0x400805a8`).

---

## 8. Defect list (verified only — no speculation)

### D1 — Guest firmware crash, cause UNKNOWN (HIGH)
- **Evidence:** `236_9_ewi9.png` (16:10:13, Nov 5): Core 1 register dump, `EXCCAUSE: 0x00000007`, `EXCVADDR: 0x00000000`, backtrace `0x40091ccf:0x3ffbc420 0x4020881a:0x3ffbc440 0x40208870:0x3ffbc460 0x400968dc:0x3ffbc480`, `ELF file SHA256: cc85d995e`, then `"Rebooting..."` and a clean `SW_CPU_RESET` boot. The guest panicked ~5 minutes after a successful boot; it rebooted itself and booted again.
- **What it is:** a panic inside the guest firmware (MicroPython v1.28.0), not a QEMU or host crash — the dump format is the ESP-IDF panic handler's, and QEMU kept running. EXCCAUSE 7 is the Xtensa exception cause code recorded in the dump; its precise meaning for this firmware is **not** decoded (do not assert it without the firmware symbols).
- **What would identify the cause:** decode the backtrace with `addr2line` (or equivalent) against the ELF for the exact MicroPython build (`ESP32_GENERIC-20260406-v1.28.0`, ELF SHA256 `cc85d995e`) — the `.bin` on Drive is a merged flash image, not an ELF, so the ELF must come from the MicroPython build for that version; and/or reproduce with UART logging of what the guest was doing immediately before the dump. Also record whether it recurs and on what trigger (idle, after a Ghost Tap RESTART, after GPIO traffic, etc.).
- **Fix location:** unknown until diagnosed. Do NOT rebuild to "fix the crash" blind.
- **Note:** the crash is current (Nov 5, 16:10), not historical; see §7.

### D2 — Source tree cannot reproduce the shipped `.so` (HIGH)
- **Evidence:** §4 — checked-in `C/esp32_qemu_jni.c` (217 lines) defines 4 JNI functions, `nativeStart` with 3 args (flash, efuse, uartPort), no GPIO natives, session struct with 2 path buffers (≈0x243C). The shipped `.so` (SHA256 `207d50ac…`) exports 6 JNI symbols including `nativeGetGpio2`/`nativeSetGpio4`, its `nativeStart` takes 4 args (flash, efuse, romDir, uartPort — proven by Capstone disassembly + DWARF: `romDir` decl_line 166, `uartPort` decl_line 167, session struct 0x3438 with 3 path buffers), built from `../android-jni/esp32_qemu_jni.c` (≥283 lines) under `/home/hatch/workspace/virtualesp32/native/qemu-src/build`. CMake defaults to STUB mode; `app/build.gradle` has no NDK/`externalNativeBuild` block — the `.so` is a prebuilt in `jniLibs/`, never rebuilt by CI.
- **Consequence:** rebuilding from this tree breaks START outright (`nativeStart` would read the port from the romDir pointer slot → "UART loopback port out of range") and GPIO silently (`UnsatisfiedLinkError`, caught as `Throwable` at every call site). The shipped binary itself is unaffected — this is a reproducibility/continuity defect, not a runtime defect in the installed APK.
- **Fix:** check in the true native sources that produced the shipped `.so` — the ≥283-line `esp32_qemu_jni.c`, the 3-buffer `qemu_session.h`, and the FULL CMake/build config that produces the non-stub `.so` — and verify a from-tree rebuild produces a byte- or behavior-identical `.so`. Until then, the prebuilt `.so` in `jniLibs/` must be treated as the only valid binary; never rebuild it from the checked-in C source.
- **Also fix:** the stale Javadoc in `J/ClassicEsp32QemuBackend.java:3-6` ("No QEMU binary is bundled yet") and the stale header comment in `C/esp32_qemu_jni.c:3-7` (3-arg contract) — both contradict the shipped binary.

### D3 — Java `"running"` flag never reconciled with QEMU liveness (MEDIUM)
- **Evidence:** `J/FirmwareEmulatorRuntime.java:8,11` — `running()` is just `"running".equals(state)`. Native `qemu_session_t.state` (`C/qemu_session.h`: STARTING/RUNNING/STOPPING/STOPPED/ERROR) has **no JNI getter**; `qemu_driver_start` returns success after a 5 s wait even if still STARTING, with the comment "the Java layer polls status()" — but no Java code ever polls native state (verified by grep). A guest panic/reboot never touches the Java flag (§5.1). If `qemu_main_loop()` returns, native state becomes STOPPED while Java stays `"running"` forever.
- **Consequence:** `QUERY_STATUS` can say RUNNING with no live guest behind it; the `READ_GPIO` gate can pass with no guest; "running" cannot distinguish a healthy guest from one in a crash loop.
- **Fix:** add a JNI getter for native session state (or a liveness heartbeat, e.g. QEMU main-loop alive + UART socket connected) and reconcile it in `running()`/`status()`/`QUERY_STATUS`; surface a distinct state when the guest has panicked or QEMU has exited. Files: `J/ClassicEsp32QemuBackend.java`, `J/FirmwareEmulatorRuntime.java`, `C/esp32_qemu_jni.c`, `J/BridgeCommandReceiver.java` (`QUERY_STATUS`, lines 195–205).

### D4 — Three different things called "reset" (MEDIUM)
- **Evidence:** §5 — receiver `RESET_GUEST` (`J/BridgeCommandReceiver.java:142-160`) = full stop+start (new session, UART re-created, monitor restarted, flash re-inspected); service `ACTION_RESET_GUEST` (`J/VirtualEsp32BridgeService.java:138-141`) and UI "Reset guest" (`J/MainActivity.java:37` → `FirmwareEmulatorRuntime.reset()`, line 23) = soft reset (same session, `qemu_system_reset_request`, no state change, no event); UI "Power / Reset" (`MainActivity.java:26,31`) and "Soft reset / reboot runtime" (`MainActivity.java:39`) = no guest effect at all (BLE only / `hal.softReset()` only).
- **Consequence:** a controller mixing paths gets different behaviour for the same word; Ghost Tap's RESTART button (receiver path) does a full teardown+restart while the app's own Reset button does a soft reset.
- **Fix:** pick one semantic per entry point and name them distinctly in code and UI (e.g. receiver RESET stays stop+start; rename the soft-reset paths; make "Power / Reset" and "Soft reset / reboot runtime" labels honest about not touching the guest). Files: `J/BridgeCommandReceiver.java`, `J/VirtualEsp32BridgeService.java`, `J/MainActivity.java`, `J/FirmwareEmulatorRuntime.java`.

### D5 — `UART_RX` forwarding to Ghost Tap only registered on receiver START/RESET (MEDIUM)
- **Evidence:** `J/BridgeCommandReceiver.java:23-28` (bus listener re-broadcasts `UART_RX` to `com.ghosttap`); `ensureBusListener` called only from `START_GUEST`/`RESET_GUEST` (`BridgeCommandReceiver.java:125,151`). If the guest was started from the app UI's "Run firmware" button, that listener is never registered, so Ghost Tap gets no `UART_RX` events via the receiver path (only via the bridge service if it was created — and the service is dead code, §4.6).
- **Consequence:** Ghost Tap's UART view goes silent for UI-started guests; the 16:05 boot's UART output reached Ghost Tap only because the session was receiver-started (or not at all — the screenshots do not show Ghost Tap's UART content for that boot, so do not claim either way).
- **Fix:** register the bus listener once at app start (e.g. in `MainActivity.onCreate` or `Application.onCreate`), not per START/RESET. Files: `J/BridgeCommandReceiver.java`, `J/MainActivity.java` (or application class).

### D6 — `reset()` silent no-op when not running; `start()` early-returns without revalidation (LOW)
- **Evidence:** `J/FirmwareEmulatorRuntime.java:23` — `reset()` does nothing and emits no error/event when `backend==null`. `J/FirmwareEmulatorRuntime.java:13` — `start()` returns `"running"` immediately if already running, without re-inspecting the flash file.
- **Consequence:** a controller cannot tell a reset did nothing; a replaced/corrupt flash file is not caught while a session is already running.
- **Fix:** `reset()` should return/report an error when not running; `start()` should re-inspect the flash file even when already running (or document the early return). File: `J/FirmwareEmulatorRuntime.java`.

### D7 — Service `READ_GPIO` silently returns −1 vs receiver's explicit errors (LOW)
- **Evidence:** `J/VirtualEsp32BridgeService.java:175-190` — if not running or `pin!=2`, `value` stays `-1` and a `GPIO_VALUE` event with `value=-1` is still sent, with no error. The receiver (`J/BridgeCommandReceiver.java:207-227`) sends explicit errors for the same conditions. The two IPC paths have inconsistent error surfaces.
- **Consequence:** low impact in practice — the service is dead code (nothing starts it, §4.6), so no controller currently hits this path. Fix for consistency if the service is ever revived; otherwise remove the dead service (see note in §9).
- **Fix:** mirror the receiver's error strings in the service, or delete `VirtualEsp32BridgeService` if it stays unused. File: `J/VirtualEsp32BridgeService.java`.

### D8 — `EJECT_FIRMWARE` / `USE_BUILTIN_FIRMWARE` removed (INFO — behavior change, not a defect)
- **Evidence:** §1.3 — both command cases and their manifest actions were removed in the repaired receiver; the receiver now answers `"Unsupported bridge command: <action>"` via its `default:` case.
- **Consequence:** any controller sending those actions now gets an explicit error instead of the old behaviour (eject / builtin test firmware). No controller is known to send them (Ghost Tap does not, per the prior audit's IPC analysis), so this is documented here rather than flagged as a defect. If a future controller needs them, re-add the cases deliberately.

### D9 — `QUERY_STATUS` cpu/backend strings are hardcoded literals (INFO)
- **Evidence:** §6.1 — `J/BridgeCommandReceiver.java:198-199` (`"Xtensa LX6 / Classic ESP32"`, `"Native QEMU"`) and `J/VirtualEsp32BridgeService.java:95-98` (`peripherals: {uart: true, gpio2_output: true, gpio4_input: true}`) are string literals, never measured. Only `"status"` and `"firmware"` derive from `running()`.
- **Consequence:** Ghost Tap displays these as facts, but they are claims by construction, not probed. True when the backend exists, but a build with a stub/missing backend would still report them if the receiver ran. Not a runtime defect in the current build; note it so nobody treats these strings as measurements.

### What is NOT a defect (do not "fix" these)
- **"Your AI couldn't be reached" line in Ghost Tap** (`233_10_cd74.png`): Ghost Tap / OpenAI-side, out of VirtualESP32 scope. The VirtualESP32 app does not call OpenAI. Do not touch it in this repo.
- **Pin 2 showing "(unknown)"** (`233_10_cd74.png`): expected behaviour — `GuestGpioMonitor` only fires on *changes* and only when the guest configures GPIO2 as an output; stock MicroPython drives no GPIO2 output, so the monitor observes −1 forever and never fires. Not a bug.
- **UART log fragmentation** (e.g. `"e"`, `"ts Jul 29 2019 1"`, `"2:21:46"` as separate lines): normal TCP chunking — each `read()` (≤1024 bytes) is logged as its own line. Not corruption.
- **"LIVE TARGET — running / Fairly sure" card**: Ghost Tap-side display, out of scope.
- **The 16:08 `READ_GPIO` failure** (`232_11_wksj.png`): explained in §6 — a host-side timing/lifecycle state (flag false at that instant), not a guest defect and not caused by the 16:10 crash (wrong order; a guest panic cannot clear the Java flag). The underlying fix is D3 (reconcile the flag with real liveness) plus timestamped error reporting on the Ghost Tap side (out of scope here).
- **Firmware files on Drive**: `virtualesp32-test-micropython-4mb.bin`, `flash_image_gpio4.bin`, `flash_image_gpio.bin`, `flash_image_v2.bin`, `flash_image.bin` all pass the app's own validation (verified 2026-11-05 by downloading + running the app's checks). `ESP32_GENERIC-20260406-v1.28.0.bin` (1,760,192 bytes) correctly fails the size rule. No firmware-file defect.

---

## 9. Verdict — is a corrected APK build needed, and what must it change?

**No corrected APK build is needed for the IPC repair.** It is verified in the APK (§2) and proven in the field (§7). Rebuilding the same Java from the same ZIP would produce the same behaviour.

**A corrected build IS required before anyone rebuilds the native side from this tree.** The minimum, in order:
1. **Check in the true native sources** (D2): the ≥283-line `esp32_qemu_jni.c` (4-arg `nativeStart`, `nativeGetGpio2`, `nativeSetGpio4`), the 3-buffer `qemu_session.h` (0x3438), and the FULL CMake/build configuration that produces the non-stub `.so`. Then verify a from-tree rebuild produces a byte- or behavior-identical `.so` (same 6 exported JNI symbols, same `nativeStart` behaviour). Until this is done, **the prebuilt `.so` in `jniLibs/` is the only valid binary** — never rebuild it from the checked-in C source, and never ship a `.so` built from the 217-line file.
2. **Fix the runtime defects in the next build:** D3 (JNI getter for native session state + reconcile `running()`), D4 (one reset semantic per entry point, honestly labelled), D5 (register the bus listener at app start), D6 (`reset()` error when not running; `start()` revalidation), D7 (service error parity, or delete the dead `VirtualEsp32BridgeService`). Also fix the stale Javadocs (D2 note) and consider D9 (measure or stop claiming the cpu/backend/peripheral strings).
3. **Diagnose D1 (guest crash) before changing anything for it:** decode the backtrace against the MicroPython ELF for `ESP32_GENERIC-20260406-v1.28.0` (ELF SHA256 `cc85d995e`); reproduce and note the trigger. Do not rebuild to fix an undiagnosed guest panic.
4. **Field-test gate for any new build (same as before):** install, import `virtualesp32-test-micropython-4mb.bin`, START, confirm MicroPython reaches the `>>>` prompt, type `help()` at the prompt and confirm an answer, leave it running and confirm no repeat panic, and confirm Ghost Tap's IPC load + `QUERY_STATUS` + GPIO read/write behave. Green in CI means "compiles" only — it is not proof the emulator runs firmware.

**What ChatGPT needs to continue (no user input required):**
- This report + the prior audit `~/workspace/your_files/ghost-tap-virtualesp32-technical-handover-audit.md` (557 lines, sections A–L; its Finding 3/D.4 "whether the shipped .so has the 4-arg form is UNVERIFIED" is now resolved — it does, §4.4; its "no guest boot ever demonstrated" is superseded by §7).
- Source: `~/workspace/your_files/virtualesp32-merged-bridge9-repaired.zip` (SHA256 `024551aab2…5ea0`); base: `~/workspace/your_files/virtualesp32-fw-manager.zip` (SHA256 `75b97f69e…4988`); installed APK: `~/workspace/your_files/virtualesp32-repaired.apk` (SHA256 `c3516f8b…ffe24`).
- Build: AndroidForge workflow `github.com/stxpaxaig/AndroidForge`, run 42130846133, commit a2640c6 — trigger via workflow dispatch with `zip_source=download-url` and the ZIP's hosted link in the `zip_url` box (never the release-tag box; that mistake built the wrong source once before). Verify any new build by artifact size (~26 MB for VirtualESP32; ~48 MB means it built the Ghost Tap fallback, ~15 MB the old scanner app) and by the dex-string check in §2.
- Tests: the project has one unit test, `virtual-esp32/tests/ProtoWireTest.java` — it was NOT run (Gradle cannot run in this sandbox; the workflow builds only). Run it as part of any corrected build if the CI can be extended; do not claim test results that were not run.
- The missing native sources (D2) live, per the shipped `.so`'s DWARF, under `/home/hatch/workspace/virtualesp32/native/qemu-src/` (`android-jni/esp32_qemu_jni.c`, build dir `…/qemu-src/build`) — that path is on the machine that produced the `.so`, not in either ZIP. Recovering them from there is the first step of D2 if that tree still exists.

---

## Appendix — exact verification commands (reproducibility)

Commands run by the investigation workers (all read-only; paths are the workers' scratch locations — re-run from your own scratch dir):

```bash
# Hashes / sizes
sha256sum ~/workspace/your_files/virtualesp32-merged-bridge9-repaired.zip \
  ~/workspace/your_files/virtualesp32-fw-manager.zip \
  ~/workspace/your_files/virtualesp32-repaired.apk
ls -l ~/workspace/your_files/virtualesp32-merged-bridge9-repaired.zip \
  ~/workspace/your_files/virtualesp32-fw-manager.zip \
  ~/workspace/your_files/virtualesp32-repaired.apk

# ZIP listings + diff (streamed, no extraction needed)
unzip -l ~/workspace/your_files/virtualesp32-merged-bridge9-repaired.zip
unzip -l ~/workspace/your_files/virtualesp32-fw-manager.zip
diff <(unzip -p ~/workspace/your_files/virtualesp32-fw-manager.zip virtual-esp32/app/src/main/java/dev/virtualesp32/BridgeCommandReceiver.java) \
     <(unzip -p ~/workspace/your_files/virtualesp32-merged-bridge9-repaired.zip virtual-esp32/app/src/main/java/dev/virtualesp32/BridgeCommandReceiver.java)

# IPC strings in the APK dex (streamed; no disk writes)
for s in "no content URI or legacy path supplied" "LOAD_FIRMWARE rejected: " \
  "START failed: runtime did not reach running state" "RESET failed: no firmware imported" \
  "is not supported by this bridge" "LOAD_FIRMWARE failed: no path provided" "importAndInspect"; do
  unzip -p ~/workspace/your_files/virtualesp32-repaired.apk classes2.dex | grep -a -o -F "$s" | wc -l
done
unzip -p ~/workspace/your_files/virtualesp32-repaired.apk classes2.dex | strings -a | grep -F "LOAD_FIRMWARE"
unzip -l ~/workspace/your_files/virtualesp32-repaired.apk

# .so symbol + identity checks (extract the .so first — it is 81 MB; /tmp is a 512 MB tmpfs and was full during this investigation, so stream or use a workspace scratch dir)
unzip -p ~/workspace/your_files/virtualesp32-merged-bridge9-repaired.zip virtual-esp32/app/src/main/jniLibs/arm64-v8a/libesp32_qemu_android.so > /tmp/so-from-zip.so
unzip -p ~/workspace/your_files/virtualesp32-repaired.apk lib/arm64-v8a/libesp32_qemu_android.so > /tmp/so-from-apk.so
sha256sum /tmp/so-from-zip.so /tmp/so-from-apk.so
nm -D --defined-only /tmp/so-from-zip.so | grep Java_
readelf --debug-dump=info /tmp/so-from-zip.so | grep -B2 -A25 "nativeStart" | head -80

# Source greps (extract the ZIP to scratch first, excluding the .so if disk is tight)
unzip -q -o ~/workspace/your_files/virtualesp32-merged-bridge9-repaired.zip -x '*/jniLibs/*' -d /tmp/vesp32-src
grep -n "native\|loadLibrary\|LOADED\|available" /tmp/vesp32-src/virtual-esp32/app/src/main/java/dev/virtualesp32/ClassicEsp32QemuBackend.java
grep -n "^Java_\|^JNIEXPORT" /tmp/vesp32-src/virtual-esp32/app/src/main/cpp/esp32_qemu_jni.c
grep -rn "firmwareEmulatorState" /tmp/vesp32-src/virtual-esp32/app/src/main/java --include=*.java
grep -rn "ensureBusListener" /tmp/vesp32-src/virtual-esp32/app/src/main/java --include=*.java
```

**Environment notes for the next engineer:** `/tmp` on this machine is a 512 MB tmpfs and was 100% full during this investigation (parallel workers' extractions). Prefer streaming (`unzip -p … | …`) or a workspace scratch dir (`~/workspace/tmp/…`) over `/tmp` for the 81 MB `.so`. The sandbox also reset mid-investigation once, wiping `/tmp`, `/dev/shm`, and pip-installed packages — keep findings in the report, not in scratch files. Gradle cannot run in this sandbox (Java TCP is blocked), so local unit-test runs and local builds are not available here; the AndroidForge workflow build is the compile test, and the user's phone is the runtime test.
