# VirtualESP32 — Host-Side Test Report (Worker D)

**Date:** 2026-11-05 (Thu), ~17:00–18:00 PST
**Tester:** Worker D (subagent, read-only, host-side only)
**Scope:** Host-side tests only. No source was modified. Nothing was installed on or deployed to the user's phone. Nothing here proves anything about the phone build except where explicitly stated.
**Inputs:** repaired source tree `~/workspace/tmp/vesp32-fix/virtual-esp32/`; firmware `~/workspace/virtualesp32-test-firmware/virtualesp32-test-micropython-4mb.bin` (4,194,304 bytes, SHA256 `7adfc0b15179a8d242ac856bce15516472f046df67c61c836a70bdb3311abaec`); host `x86_64`, Linux.
**Read first:** `CHATGPT-HANDOFF-INVESTIGATION.md` §§3, 4, 9 (validation rules, UART, what "running" proves, test notes) — done.

**Path correction:** the task brief cited the unit test at `virtual-esp32/tests/ProtoWireTest.java`. The file actually lives at `~/workspace/tmp/vesp32-fix/virtual-esp32/tests/ProtoWireTest.java` (no doubled `virtual-esp32/` segment). Same for its companion `ProtoWire.java` at `~/workspace/tmp/vesp32-fix/virtual-esp32/app/src/main/java/dev/virtualesp32/ProtoWire.java`.

## Results table

| # | Test | Result | Exact command / evidence |
|---|------|--------|--------------------------|
| 1 | ProtoWireTest.java (project's only unit test) | PASSED | `java -jar ecj.jar` compile + `java dev.virtualesp32.ProtoWireTest` → `PASS: Security 0 session envelope, Wi-Fi config command fields, zero-valued scan command, config response envelope`, exit 0. No JDK on machine; ECJ 3.37.0 (3.3 MB, Maven Central) used as compiler + existing JRE 17 to run. See §1. |
| 2 | Host-QEMU boot of `virtualesp32-test-micropython-4mb.bin` to `>>>` | PASSED | MicroPython v1.28.0 booted to `>>>` at **t=2.8 s** after start. Full log: `work/host-boot.log`. See §2. |
| 3 | REPL `help()` via serial | PASSED | `help()` answered (1,198 chars captured: pin/I2C/WiFi examples, CTRL-A–E list). See §2. |
| 4 | Second REPL command (`print(...)`) + multi-minute idle survival | PASSED | `print('host-test-ok-123')` echoed back. Guest then ran **silently alive for 360 s idle** (total runtime 363.2 s) with **no panic, no reboot**. See §2. |
| 5 | Firmware passes the app's own validation rules (§3.1) | PASSED | Python re-implementation of `FirmwareImageManager.inspect()` rules against the real file. See §3. |
| 6 | Python helper tests (`firmware_image_layout.py`, `proto_vectors.py`) | PASSED | `python3 firmware_image_layout.py`, `python3 proto_vectors.py` in `tests/`. See §4. |
| 7 | Python source-contract test (`check_project.py`) | FAILED | Fails at line 46: `assert 'externalNativeBuild' in gradle` — `app/build.gradle` has no `externalNativeBuild`/NDK block. All assertions before line 46 passed. See §4. This matches handoff defect D2. |

## 1. ProtoWireTest — PASSED

**What the test needs (read first):** `ProtoWireTest.java` is a plain `public static void main()` — **no JUnit needed** (only import is `java.util.Arrays`). It depends only on `ProtoWire.java` in the same package (`dev.virtualesp32`). It asserts four protobuf wire vectors and prints `PASS: Security 0 session envelope, Wi-Fi config command fields, zero-valued scan command, config response envelope`. Compiling it needs only `javac ProtoWire.java ProtoWireTest.java` + `java dev.virtualesp32.ProtoWireTest`. No JUnit jar is required at all (JUnit jars do exist locally in Gradle caches, e.g. `~/workspace/ghosttap/v14/work/.gradle/wrapper/fallback/gradle-8.7/lib/junit-4.13.2.jar`, but the test does not use them).

**Environment blocker found first (exact evidence):**
- `java -version` / `javac -version` → `java: command not found`, `javac: command not found`.
- `ls /usr/lib/jvm/` → no such directory. `find / -maxdepth 6 -name javac` → no results.
- The only Java on the machine is a **JRE, not a JDK**: `~/jre/jdk-17.0.9+9-jre/bin/java` (bin/ contains only `java, jfr, jrunscript, keytool, rmiregistry` — no `javac`). The tarball `~/jre/jre.tar.gz` (46,280,224 bytes) is the same JRE, confirmed by listing (top dir `jdk-17.0.9+9-jre/`).
- No `gradle` binary on PATH either (`which gradle` → not found); the project's `gradlew` cannot run without a JDK and the handoff notes Gradle cannot run in this sandbox (Java TCP blocked).
- `sudo -n apt-get update` (passwordless sudo works) to try installing `openjdk-17-jdk-headless` **failed**: every repo reports `E: The repository '…' is not signed` (GPG errors splitting InRelease files, azure.archive.ubuntu.com + mirror.cogentco.com + deb.nodesource.com, Ubuntu noble). `apt-cache policy qemu-system-misc openjdk-17-jdk-headless` returns nothing. **apt is unusable here; no packages were installed from it.** (Installing with signature checks bypassed was not attempted.)

**Workaround that ran the test (small download, noted):** Eclipse ECJ 3.37.0 (`ecj-3.37.0.jar`, 3,257,207 bytes, from Maven Central `repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.37.0/ecj-3.37.0.jar`) as a standalone compiler, with the existing JRE 17 as the runtime. This is a compiler substitution, not a source change — the two source files were compiled unmodified. (The JRE also printed a benign warning: `OpenJDK 64-Bit Server VM warning: Insufficient space for shared memory file` — /dev/shm pressure, not a test issue.)

**Exact commands (from `~/workspace/tmp/vesp32-test-scratch/work/`):**
```
~/jre/jdk-17.0.9+9-jre/bin/java -jar ecj.jar -17 -d classes \
  ~/workspace/tmp/vesp32-fix/virtual-esp32/app/src/main/java/dev/virtualesp32/ProtoWire.java \
  ~/workspace/tmp/vesp32-fix/virtual-esp32/tests/ProtoWireTest.java
# COMPILE_EXIT: 0  (classes: ProtoWire.class, ProtoWire$Field.class, ProtoWireTest.class)
~/jre/jdk-17.0.9+9-jre/bin/java -cp classes dev.virtualesp32.ProtoWireTest
# PASS: Security 0 session envelope, Wi-Fi config command fields, zero-valued scan command, config response envelope
# RUN_EXIT: 0
```

**What this proves:** the four protobuf wire vectors in `ProtoWire` are correct on a host JVM — Security 0 session envelope bytes, Wi-Fi config command fields (ssid/pw round-trip), zero-valued scan-start command, config response envelope. **What it does not prove:** anything about Android, the provisioning service at runtime, QEMU, or the phone build. It is the project's only unit test, and it passes as written.

## 2. Host-QEMU firmware boot — PASSED (boot, REPL, 6-min idle, no crash)

**What the app does (from `qemu_driver_real.c`, the exact argv):**
```
qemu-system-xtensa -machine esp32 \
  -drive file=<flash>,if=mtd,format=raw \
  -drive file=<efuse124>,if=none,id=efusedrv,format=raw \
  -global nvram.esp32.efuse.drive=efusedrv \
  -serial tcp:127.0.0.1:<ephemeral-port> \
  -monitor none -net none -L <rom-dir> -nographic
```
with `<rom-dir>` holding `esp32-v3-rom.bin` + `esp32-v3-rom-app.bin` (455,722 bytes each, extracted from the repaired APK: SHA256 `9115bbcd…` and `6d3240e7…`) and the guest's UART0 as a TCP *client* to the app's loopback server.

**No runnable host QEMU existed here at the start — exact evidence (all three named binaries checked):**
- Host is `x86_64` (`uname -m`).
- `~/workspace/virtualesp32/native/qemu-src/build/qemu-system-xtensa` → **does not exist** (`ls: cannot access … No such file or directory`). The task brief's first path (`…/build/qemu-bundle/usr/local/bin/qemu-system-xtensa`) is a **dangling symlink** to that missing file.
- `~/workspace/virtualesp32-complete/qemu-android/qemu-system-xtensa` (79,303,640 bytes) → `file` reports **ELF 64-bit LSB, ARM aarch64, dynamically linked, interpreter /system/bin/linker64** — an Android binary. Executing it on this host fails exactly: `cannot execute binary file: Exec format error`.
- The `build/` directory is an **Android cross-build** (meson cross file targets `aarch64`, NDK clang `aarch64-linux-android26-clang`, sysroot under `android-ndk-r27c`). It contains `libesp32_qemu_android.so` (ARM aarch64) and object dir `qemu-system-xtensa.p/` but **no host `qemu-system-xtensa` binary was ever built**. `which qemu-system-xtensa`, `/usr/bin/qemu*` → none installed.
- A from-source host build of the Espressif QEMU tree present at `~/workspace/virtualesp32/native/qemu-src/` (453 MB) was **not attempted**: the machine has only ~490 MB free RAM (8 GB total, 7.4 GB used) and 4 cores, so a full QEMU host build here is likely to OOM or take far longer than this task allows; that is a judgment call, recorded here, not a claim it is impossible.

**Host QEMU obtained instead (download, noted):** Espressif's official prebuilt host binary for the same fork/version the app embeds — `qemu-xtensa-softmmu-esp_develop_9.2.2_20260417-x86_64-linux-gnu.tar.xz`, **15,619,396 bytes** (size matches `tools.json`), SHA256 `0eecb2a34a5586c0e59110f77b9343b7b336e82fdb0e1a30e1dc1bab8a547e35`, from `https://github.com/espressif/qemu/releases/download/esp-develop-9.2.2-20260417/…` (URL taken from ESP-IDF `tools/tools.json`, `qemu-xtensa` / `linux-amd64`). Verified: `QEMU emulator version 9.2.2 (esp_develop_9.2.2_20260417)`, `-machine help` lists `esp32 — Espressif ESP32 machine`. Its bundled ROM files are **byte-identical to the APK's** (same SHA256s as above), so the ROM dir is exactly the app's.
- One fix needed to run it: `libslirp.so.0` was the only missing shared library (`ldd` — all others resolve). apt is broken (see §1), so the runtime package was fetched directly: `libslirp0_4.1.0-2ubuntu2.2_amd64.deb` (54,444 bytes, Ubuntu noble, from `azure.archive.ubuntu.com/ubuntu/pool/main/libs/libslirp/`), extracted with `dpkg-deb -x` into scratch only (not installed system-wide), and put on `LD_LIBRARY_PATH`. No system libraries were modified.

**Boot driver (ran to completion):** `~/workspace/tmp/vesp32-test-scratch/work/run_host_boot.py` — exact QEMU command:
```
qemu/bin/qemu-system-xtensa -machine esp32 \
  -drive file=<scratch-copy-of-firmware>,if=mtd,format=raw \
  -drive file=<124-byte-zeroed-efuse>,if=none,id=efusedrv,format=raw \
  -global nvram.esp32.efuse.drive=efusedrv \
  -serial stdio -monitor none -net none -L <apk-rom-dir> -nographic
```
(Flash is a scratch copy — QEMU opens it read-write and guest writes persist; the original firmware file is untouched — SHA256 still `7adfc0b1…`. The scratch copy's hash changed after the run (`d3758be6…`), i.e. the guest did write to flash during first boot, as the app's read-write flash setup also permits.) Full timestamped transcript: `work/host-boot.log` (157 lines), stderr: `work/host-boot.stderr.log`. Log lines:
```
[01:02:46] CMD: <command above>
[01:02:49] GUEST: ets Jul 29 2019 12:21:46
[01:02:49] GUEST: rst:0x1 (POWERON_RESET),boot:0x12 (SPI_FAST_FLASH_BOOT)
[01:02:49] GUEST: configsip: 0, SPIWP:0xee
[01:02:49] GUEST: mode:DIO, clock div:2
[01:02:49] GUEST: load:0x3fff0030,len:4200
[01:02:49] GUEST: load:0x40078000,len:15236
[01:02:49] GUEST: load:0x40080400,len:3332
[01:02:49] GUEST: entry 0x400805a8
[01:02:49] GUEST: Performing initial setup
[01:02:49] GUEST: MicroPython v1.28.0 on 2026-04-06; Generic ESP32 module with ESP32
[01:02:49] GUEST: Type "help()" for more information.
[01:02:49] GUEST: >>>
[01:02:49] RESULT: first >>> prompt at t=2.8s after start
[01:02:49] SENT: help()
[01:02:49] RESULT: help() answered=True (captured 1198 chars)
[01:02:49] SENT: print('host-test-ok-123')
[01:02:49] RESULT: print() echoed=True
[01:02:49] IDLE WATCH: until t=362.8s after start (360s from first prompt)
[01:02:49] GUEST: host-test-ok-123
[01:02:49] GUEST: >>>
[01:08:49] RESULT: idle watch done. QEMU still alive=True, panic markers seen=['rst:0x'], total runtime=363.2s
[01:08:49] RESULT: QEMU final rc=0
```

**Results, one by one:**
- **Boot: PASSED.** `MicroPython v1.28.0 on 2026-04-06; Generic ESP32 module with ESP32` then `>>>` at **t=2.8 s** — the same banner and prompt as the 16:05 Nov 5 field boot.
- **`help()`: PASSED, exact answer captured** (1,198 chars in the log): the standard MicroPython help text — pin examples (`pin12.value(1)`, `pin13 = machine.Pin(13, …)`), `i2c = machine.I2C(…)`, `i2c.scan()`, Basic WiFi configuration (`import network`, `sta_if.scan()`, `sta_if.connect(…)`), control commands (CTRL-A raw REPL, CTRL-B normal, CTRL-C interrupt, CTRL-D soft reset, CTRL-E paste mode), ending "For further help on a specific object, type help(obj)" / "For a list of available modules, type help('modules')".
- **Second command: PASSED.** `print('host-test-ok-123')` → guest printed `host-test-ok-123` and returned to `>>>`.
- **Idle survival: PASSED — no crash reproduced.** After the REPL exchanges the guest produced **zero output for the full 360 s idle watch** and QEMU was still alive at the end (total runtime 363.2 s, terminated cleanly by the driver, QEMU rc=0). **No `EXCCAUSE`, no `Guru Meditation`, no `Backtrace:`, no `Rebooting...`, no `SW_CPU_RESET` reboot anywhere in the log.** The field crash (Core 1 register dump, EXCCAUSE 7, ~5 min after boot on the phone) did **not** reproduce on the host in 6 minutes of idle running.
- **Panic-marker note (honesty):** the log line `panic markers seen=['rst:0x']` is a **false positive from my own marker list** — the only `rst:0x` match is the normal boot line `rst:0x1 (POWERON_RESET)` at startup, which is expected power-on output, not a panic. There was no second reset line at any point. The driver's marker list was too broad; the true verdict is "no panic markers" (no EXCCAUSE/Backtrace/Rebooting). Consequence: the driver script itself exited **1** (`DRIVER_EXIT:1`) solely because of that false-positive marker, even though every substantive check (boot, `help()`, `print()`, 360 s idle alive) passed — do not read the exit code as a test failure; the log RESULT lines above are the evidence. (QEMU's own final rc was 0.)
- **Logger artifact (honesty):** the boot banner lines appear 3× in `host-boot.log` — a driver logging artifact (partial-line re-emission when a read chunk does not end with `\n`). The boot happened once; the first `>>>` at t=2.8 s is the real event. Do not read the duplicates as three boots.
- **QEMU stderr observation (not a failure):** `Adding SPI flash device` and one warning: `qemu-system-xtensa: warning: global nvram.esp32.efuse.drive has invalid class name`. The eFuse `-global` from the app's argv warns on this prebuilt QEMU 9.2.2 but does not stop the boot. Worth flagging to the native-side work (D2 follow-up): verify the eFuse wiring actually attaches in the phone's linked-in QEMU core, since the same argv is used there.

**What differs from the phone — the host test can never be identical:**
- Machine model: same `-machine esp32` from the **same Espressif QEMU 9.2 fork/version** (esp_develop 9.2.2, 20260417) the app embeds — the closest possible match, but it is Espressif's prebuilt host binary, not the phone's ARM64 `.so` with the QEMU core linked in.
- Serial wiring: the app has QEMU as TCP *client* to its loopback server (`-serial tcp:127.0.0.1:<port>`); the host run used `-serial stdio` — same guest UART0, different plumbing. The app's `EmulatorUartBridge`, JNI layer, and Java `running` flag are entirely absent here.
- Host CPU/arch: x86_64 host running the Xtensa guest via TCG vs the phone's ARM64 host JIT — same guest code, different host JIT and timing.
- No Android, no Ghost Tap IPC, no GPIO traffic, no `READ_GPIO`/`WRITE_GPIO`, no receiver `RESET_GUEST` — the host guest sat idle; the field crash happened while Ghost Tap was connected and polling. **The host idle test therefore rules out "MicroPython panics on its own after ~5 idle minutes under this QEMU" but does NOT rule out a crash triggered by something the phone setup does (UART/TCP traffic, GPIO polling, a reset, or phone-side timing).**
- The eFuse warning above may also differ between the prebuilt host QEMU and the phone's linked core.

**What this proves / does not prove:** it proves the firmware + Espressif QEMU 9.2.2 `esp32` machine boot to a working REPL and survive 6 idle minutes on the host. It does **not** prove the phone build works (that needs the phone), does not explain the 16:10 field crash (D1 stays open, cause unknown), and does not test any app/Java/IPC/GPIO path.

## 3. Firmware validation (app's own rules, §3.1) — PASSED

Ran the exact `FirmwareImageManager.inspect()` rules as a Python check against the real file `virtualesp32-test-micropython-4mb.bin` (SHA256 `7adfc0b15179a8d242ac856bce15516472f046df67c61c836a70bdb3311abaec`):
- Size 4,194,304 bytes — valid (2/4/8/16 MiB rule).
- Byte at offset `0x1000` = `0xE9` — bootloader header present.
- Partition table at `0x8000`: 1 app partition (`type==0`) with `0xE9` at its offset, first app @ `0x10000`.
- Verdict: **passes** — the same summary the app prints: "Merged-image structure found: 4 MiB flash, bootloader @0x1000, 1 app image(s) in partition table (first @0x10000). Structural check only; full firmware/SoC compatibility is not proven." (The check itself declares it is structural only.)

## 4. Python helper tests in the repo — 2 PASSED, 1 FAILED

Commands (from `~/workspace/tmp/vesp32-fix/virtual-esp32/tests/`):
- `python3 firmware_image_layout.py` → exit 0: `PASS: synthetic 2 MiB merged-image vector has bootloader, AA 50 factory partition, and app headers`
- `python3 proto_vectors.py` → exit 0: `PASS: source schema field numbers and Security 0/config protobuf wire vectors`
- `python3 check_project.py` → **exit 1, AssertionError at line 46**: `assert 'externalNativeBuild' in gradle and 'src/main/cpp/CMakeLists.txt' in gradle`. Every assertion before line 46 passed (manifest permissions, provisioning endpoints, HAL import/export, all 8 UI panels, image-manager strings, `nativeStart`/`nativeGetLastError` in the backend, all 4 JNI symbols + `QEMU_CORE_AVAILABLE` in the C source, `qemu_init`/`qemu_main_loop`/`qemu_cleanup`/reset/shutdown in the driver, `"esp32"` + `tcp:127.0.0.1` + `if=mtd,format=raw` argv strings, `esp32_qemu_android` + `QEMU_CORE_AVAILABLE` in CMake). The failure is exactly handoff defect **D2**: `app/build.gradle` has no `externalNativeBuild`/NDK block — the native side cannot be rebuilt from this tree. This is a **source-tree defect, not a test-environment failure**.

Note: these Python checks are static/source checks only. They do not execute Java, QEMU, or firmware.

## 5. Scratch locations (read-only w.r.t. source; no source files modified)

- Report: `~/workspace/tmp/vesp32-test-results.md` (this file).
- Scratch: `~/workspace/tmp/vesp32-test-scratch/` — ROM files extracted from the repaired APK (`rom/esp32-v3-rom.bin`, `rom/esp32-v3-rom-app.bin`); `work/` holds: `ecj.jar` (3,257,207 bytes, Eclipse ECJ 3.37.0 — compiler substitute), `classes/` (compiled `ProtoWire`/`ProtoWireTest`), `qemu-xtensa.tar.xz` + extracted `qemu/` (Espressif prebuilt host QEMU 9.2.2), `libslirp0.deb` + `slirp-extract/` (libslirp.so.0 only), `flash-test.bin` (scratch flash copy, guest-written), `efuse.bin` (124 zeroed bytes), `run_host_boot.py` (driver), `host-boot.log` (157-line timestamped transcript), `host-boot.stderr.log`. `/tmp` was not used (512 MB tmpfs). No source files were modified anywhere.

## Verdict

The host tests prove the firmware and the emulator core are sound together: the project's only unit test, ProtoWireTest, passes on a host JVM (compiled with ECJ, since this machine has a JRE but no JDK), and the exact MicroPython 4 MiB image from the November 5 field test boots to the `>>>` prompt in 2.8 seconds under the same Espressif QEMU 9.2.2 `esp32` machine the phone build embeds, answers `help()` with the full MicroPython help text, executes a second REPL command, and then runs silently alive for a full 6 minutes idle with no panic and no reboot — so the field crash (Core 1 register dump, EXCCAUSE 7, ~5 minutes after boot) does not reproduce as an idle guest fault on the host, and defect D1 remains open with unknown cause, most plausibly tied to something the phone setup does that the host run did not (Ghost Tap IPC/UART traffic, GPIO polling, a reset, or phone-side timing). The one genuine source defect these tests surfaced is the already-known D2: the repo's Python source-contract test `check_project.py` fails because `app/build.gradle` has no `externalNativeBuild`/NDK block, so the native side still cannot be rebuilt from this tree. What the host tests do NOT prove: anything about the phone build itself — the Android app, its JNI wiring, the Java `running` flag, the UART bridge, Ghost Tap IPC, or GPIO — none of that ran here, and the phone remains the only place those can be proven, per the handoff's field-test gate (install, import this same firmware, START, `>>>`, `help()`, leave running, confirm no repeat panic).
