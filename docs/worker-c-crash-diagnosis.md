# VirtualESP32 — Guest crash diagnosis (D1)

**Worker C — read-only investigation. No source modified, nothing installed or deployed.**
**Date:** 2026-11-05 ~17:00 PST
**Status: ELF NOT FOUND — but the crash location was still identified from the firmware binary itself (see §5). No decode was guessed.**

## 1. The event (the only crash evidence — this is what was diagnosed)

- **When:** 2026-11-05 ~16:10:13 PST (screenshot `236_9_ewi9.png`; status-bar clock 4:10, in-log timestamps 16:10:13–16:10:18). Current, not historical — see handoff investigation §7.
- **Guest:** MicroPython v1.28.0 on 2026-04-06, file `virtualesp32-test-micropython-4mb.bin` (4,194,304 bytes), running in the VirtualESP32 QEMU emulator. Booted cleanly to the `>>>` prompt at 16:05:41–46, panicked ~4.5 minutes later while idle at the REPL, printed a Core 1 register dump, `Rebooting...`, then a clean `SW_CPU_RESET` boot. QEMU kept running throughout.
- **Dump (as quoted in the handoff investigation §7/§8-D1):** `EXCCAUSE: 0x00000007`, `EXCVADDR: 0x00000000`, backtrace `0x40091ccf:0x3ffbc420 0x4020881a:0x3ffbc440 0x40208870:0x3ffbc460 0x400968dc:0x3ffbc480`, `ELF file SHA256: cc85d995e`.
- **What it is:** a panic inside the guest firmware (ESP-IDF panic handler format), **not** a QEMU or host crash.

## 2. Verdict up front

1. **The exact MicroPython ELF was NOT found** — not in the workspace, not on Google Drive, and not retrievable from micropython.org in this session (the download page does publish an `.elf and .map` link for v1.28.0, but the page fetch failed — see §3.3). **No addr2line decode was performed against any substitute ELF.** The four local `.elf` files are hand-built test firmware and were explicitly ruled out (§3.1).
2. **The crash location was nevertheless identified exactly** by disassembling the firmware `.bin` itself with the Xtensa objdump present on this machine (§5). The fault is in the FreeRTOS **idle task's CPU-wait function** (`esp_cpu_wait_for_intr()` — `entry; waiti 0; retw`), i.e. the guest was asleep waiting for an interrupt when it died.
3. **EXCCAUSE 7 is NOT "Privileged"** (that is cause 8). Cause 7 is **`PCValue` — "Next PC Value Illegal"** (ESP-IDF's own panic table, verified in source on this machine). The CPU tried to take a next-PC of `0x00000000` (`EXCVADDR: 0x00000000`).
4. **Most likely cause (plain terms): the emulated CPU mishandled waking up from the idle wait** — an intermittent emulator (QEMU) defect in the WAIT/interrupt-resume path, not a MicroPython bug. It ran fine for ~4.5 minutes (~hundreds of thousands of wait/wake cycles at the 1 ms tick) before failing once, so it is a rare race, not a deterministic break. Confidence: moderate — the ELF decode + full register dump + a real-hardware control run are still needed to confirm (§6).

## 3. Every place checked for the ELF

### 3.1 Workspace — no MicroPython ELF anywhere

- `find ~/workspace -iname '*.elf'` (excluding node_modules) returned exactly **4 files, all in `~/workspace/your_files/`**:
  - `app.elf` (5,624 bytes), `bootloader.elf` (4,884 bytes), `firmware.elf` (6,588 bytes), `firmware_gpio.elf` (6,688 bytes) — all dated 2026-10-10.
  - `xtensa-esp32-elf-readelf -s` on each: symbols are only `uart_putc`, `uart_puts`, `uart_put_u32`, `poll_rx`, `app_main` (and `_start` in the bootloader). These are the **hand-built test firmwares** from 2026-10-10, not MicroPython. None contains the strings `MicroPython`, `v1.28`, or `ESP32_GENERIC`.
  - `xtensa-esp32-elf-addr2line -e firmware.elf` against all four backtrace addresses returns `?? / ??:0` for every address — the addresses are outside these ELFs entirely.
  - SHA256s (for the record): `app.elf 21f98263…`, `bootloader.elf ddd7ead7…`, `firmware.elf adc670e2…`, `firmware_gpio.elf 3796a851…` — none matches the target (§4).
- `~/workspace/virtualesp32-test-firmware/` contains only the two `.bin` files + `README.md` (no ELF, no map).
- `~/workspace/goals/virtualesp32-native-emulator-backend/files/` (7 ZIPs) and `~/workspace/your_files/virtualesp32-merged-bridge9-repaired.zip`: `unzip -l` grepped for `.elf` / `.map` / `micropython` — **zero hits** (only unrelated QEMU build-tree `.map.in` files).
- `~/workspace/virtualesp32/` tree (QEMU sources, NDK, native code): no MicroPython ELF, no firmware ELF.
- No `.map` file for MicroPython anywhere in the workspace.

### 3.2 Google Drive — no MicroPython ELF

Drive searches run via the `google_drive` connector (`hatch_gws_cli drive files list`):
- `name contains '.elf'` → 2 files only: `app.elf` (id `1-ys-diP53Smi9431cRYVnDrZhm2HnK9o`, 6,588 bytes, modified 2026-10-10) and `bootloader.elf` (id `1PeFqfHb5-gsh0Rvgszwcr1y4NmozA5mD`, 4,884 bytes, modified 2026-10-10) — the same hand-built test ELFs as §3.1 (sizes match byte-for-byte).
- `name contains 'micropython'` → 1 file: `virtualesp32-test-micropython-4mb.bin` (id `1g7TaAfj4Ydti1P47z0JtRze8Z0xTsG6S`).
- `name contains 'ESP32_GENERIC'` → 1 file: `ESP32_GENERIC-20260406-v1.28.0.bin` (id `1hrGXd2T96W7tqbGX4YbPkf696_-b0apI`).
- `name contains '.map'` → **0 files**.
- Conclusion: Drive holds the two firmware `.bin` files only. No ELF, no map.

### 3.3 micropython.org — ELF/MAP is published, but the exact link could not be retrieved in this session

- Web searches confirmed the release is real: **MicroPython v1.28.0 (2026-04-06)**.
- The micropython.org **download pages do publish `.elf and .map`** for releases. Search results for the board download pages show, verbatim: `v1.28.0 (2026-04-06) .uf2 / [.app-bin] / [.bin] / [.elf and .map] /` (and for preview builds `[.elf] / [.map]` separately). So the matching artifacts **exist on micropython.org** — they were not obtainable here, because:
  - `browser.open` of the board download page `https://micropython.org/download/ESP32_GENERIC` failed with a browser-service error (`browser_open failed`, tool failure — not a "page not found"), and the instruction for this task was not to repeat that request.
  - Web searches for the exact filename `"ESP32_GENERIC-20260406-v1.28.0.elf"` returned no direct URL.
- The known-good firmware URL (from `~/workspace/virtualesp32-test-firmware/README.md`, verbatim) is `https://micropython.org/resources/firmware/ESP32_GENERIC-20260406-v1.28.0.bin`. By analogy the ELF is most likely at `https://micropython.org/resources/firmware/ESP32_GENERIC-20260406-v1.28.0.elf` — **this is an UNVERIFIED guess by pattern, not a confirmed link; do not trust it until the SHA256 check in §4 passes.**
- The reliable route: open the board download page `https://micropython.org/download/ESP32_GENERIC` (in a live browser) and click the **`[.elf and .map]`** link on the **v1.28.0 (2026-04-06)** row.

## 4. ELF verification — the target identity (computed from the firmware itself)

The panic prints only the first 9 hex chars of the app ELF SHA (`cc85d995e`). The **full** SHA256 was recovered from the app descriptor embedded in the firmware `.bin` (ESP-IDF `esp_app_desc_t`, magic `0xABCD5432` at the start of the app's first segment; the `app_elf_sha256` field sits 144 bytes into the descriptor):

- **Target app ELF SHA256: `cc85d995e8b415bd66c20a7dea84b5117a08c05a3c0f9ea791bc29a9251487a1`**
- It starts with `cc85d995e` — matches the panic print exactly.
- Verified identical in **both** files: the raw release download `ESP32_GENERIC-20260406-v1.28.0.bin` (SHA256 `cd7820d02c35d34dd403b44263129c6a511b350aea8446c229890753fe240784`, 1,760,192 bytes; app at file offset `0xF000`) and the 4 MB image `virtualesp32-test-micropython-4mb.bin` (SHA256 `7adfc0b15179a8d242ac856bce15516472f046df67c61c836a70bdb3311abaec`, 4,194,304 bytes; app at file offset `0x10000`).
- Descriptor fields (same in both): build date **Apr 6 2026**, time **13:16:34**, ESP-IDF version **v5.5.1**, version/project-name strings empty (daily/release build).
- **Rule for accepting any candidate ELF:** `sha256sum <candidate.elf>` must equal `cc85d995e8b415bd66c20a7dea84b5117a08c05a3c0f9ea791bc29a9251487a1`. Nothing found in §3 comes close (the four local ELFs' SHA256s are listed in §3.1; none matches). **Do not decode against any ELF that fails this check.**

## 5. What was found WITHOUT the ELF — disassembly of the firmware `.bin`

Method (read-only, reproducible): parse the ESP image segments from the local `.bin`, extract the two code segments, disassemble with the Xtensa objdump already on this machine (`~/.espressif/tools/xtensa-esp32-elf/esp-12.2.0_20230208/xtensa-esp32-elf/bin/xtensa-esp32-elf-objdump`). Extracted segment copies are kept at `~/workspace/tmp/vesp32-crash/seg2-irom.bin` and `seg4-iram.bin` for the next worker.

App segments (from `ESP32_GENERIC-20260406-v1.28.0.bin`, app at file offset `0xF000`, 6 segments, entry `0x40081320`):

| Seg | Load address | Length | File offset | Contents |
|---|---|---|---|---|
| 0 | `0x3f400020` | `0x3f4e0` | `0xf020` | .rodata (starts with app descriptor) |
| 1 | `0x3ffbdb60` | `0xb10` | `0x4e508` | .data |
| 2 | `0x400d0020` | `0x13c69c` | `0x4f020` | IROM .text (flash-mapped code) |
| 3 | `0x3ffbe670` | `0x4ea0` | `0x18b6c4` | .bss-ish/data |
| 4 | `0x40080000` | `0x1d598` | `0x19056c` | IRAM .text |
| 5 | `0x50001000` | `0x84` | `0x1adb0c` | RTC |

**All four backtrace PCs land inside the app's own code segments** (none in bootloader/ROM):

| Printed PC | Raw address* | File offset | Segment | Instruction there |
|---|---|---|---|---|
| `0x40091ccf` | `0x40091cd2` | `0x1a223e` | seg 4 (IRAM .text) | `retw.n` — last instruction of the function at `0x40091ccc` |
| `0x4020881a` | `0x4020881d` | `0x18781d` | seg 2 (IROM .text) | just after a `call4` at `0x4020881a` (return address into the caller) |
| `0x40208870` | `0x40208873` | `0x187873` | seg 2 (IROM .text) | just after a `call8` at `0x40208870` (return address into the caller) |
| `0x400968dc` | `0x400968df` | `0x1a6e4b` | seg 4 (IRAM .text) | just after a `callx8` at `0x400968dc` (return address into the caller) |

\* ESP-IDF prints backtrace PCs as `esp_cpu_process_stack_pc(pc)` = `pc − 3` (verified in `components/xtensa/include/esp_cpu_utils.h` on this machine: *"Minus 3 to get PC of previous instruction (i.e. instruction executed before return address)"*). So the raw address is the printed address + 3. The first printed entry is the faulting PC (EPC) from the exception frame (`panic_print_backtrace` seeds the frame with `xt_frame->pc`); the rest are return addresses walked off the stack.

### 5.1 The faulting function — identified exactly

```
0x40091ccc:  entry   a1, 32
0x40091ccf:  waiti   0
0x40091cd2:  retw.n
```

This is **`esp_cpu_wait_for_intr()`** — the whole function is one `waiti 0` (verified against ESP-IDF source on this machine: `components/esp_hw_support/cpu.c:108` calls `xt_utils_wait_for_intr()`; `components/xtensa/include/xt_utils.h:79-82` is exactly `asm volatile ("waiti 0")`). Call chain: FreeRTOS idle task → `esp_vApplicationIdleHook()` (`components/esp_system/freertos_hooks.c:57`) → `esp_cpu_wait_for_intr()` (or `esp_pm_impl_waiti()` → same). **The guest was in the idle task, asleep waiting for an interrupt, when it crashed.** (Note: the local ESP-IDF copy is v5.1.0; the crashed firmware was built with v5.5.1 per §4 — the code path is the same across IDF 5.x, but exact line numbers may differ.)

### 5.2 EXCCAUSE 7 — corrected meaning

- ESP-IDF's panic reason table (`components/esp_system/port/arch/xtensa/panic_arch.c`, index 7) says **`"PCValue"`**. The header `components/xtensa/include/xtensa/corebits.h` defines `EXCCAUSE_PC_ERROR 7 /* Next PC Value Illegal */`.
- **Cause 8 is "Privileged"** — not 7. Any earlier "privileged instruction" reading of this crash is wrong.
- So: the CPU tried to jump/return to an illegal next-PC, and `EXCVADDR: 0x00000000` is that illegal target — **a jump to address 0**.

### 5.3 What the other frames say (with a caution)

- Frame 2's PC is the faulting frame's return address (A0): raw `0x4020881d`, i.e. the idle-wait function was called via `call4` from IROM code at `0x4020881a` and would have returned there — **a valid address**. That argues *against* the simple story "RETW returned to A0 = 0" and *toward* the fault being in the WAIT/wake-up path itself (or in interrupt-return handling around it).
- Frame 3 (`0x40208870` raw `0x40208873`) is a return just after a `call8` at `0x40208870` in the same IROM region — a plausible caller chain (a per-core interrupt/hook dispatcher: the disassembly around `0x402087e0` shows `rsr.prid` + per-core table indexing, i.e. core-ID-dependent dispatch code).
- Frame 4 (`0x400968dc` raw `0x400968df`) is a return just after a `callx8` in IRAM — deeper in the call chain.
- Caution: the IROM/IRAM regions around frames 2–4 were decoded by linear disassembly without symbols, and one stretch (near `0x400968a9`) demonstrably drifts out of sync (a stray `0xff` byte). The frame-2/3/4 **return-address** identifications above are anchored on call/return pairs and are consistent, but function *names* for those frames cannot be stated without the ELF — that is exactly what the addr2line step (§6) is for.

## 6. Most likely cause (plain terms)

The guest was doing nothing — sitting at the MicroPython `>>>` prompt, no user code running — with its idle task asleep in `waiti 0`, waiting for the next timer tick or interrupt. About 4.5 minutes after boot, on waking from that wait, the CPU tried to jump to address 0 and the ESP-IDF panic handler rebooted it.

Most likely: **the emulator (QEMU, espressif fork) fumbled the wake-up from the idle WAIT** — a rare race in the emulated CPU's WAIT/interrupt-resume or windowed-register handling, not a bug in MicroPython and not bad firmware. Why this is the leading theory:

- The crash is in generic idle code (`waiti`), not in any MicroPython feature (no Wi-Fi, GPIO, REPL, or user code on the stack).
- It took ~4.5 minutes of continuous running to happen once — thousands of wait/wake cycles at the 1 ms FreeRTOS tick succeeded first, so it is intermittent, i.e. a race or a rare state, not a deterministic defect.
- The implied return address (frame 2) is valid, so the guest's own return path looks intact — the bad next-PC of 0 points at the wake-up/return mechanism itself, which in this setup is emulated by QEMU.
- The alternative (guest stack/window corruption) has no supporting evidence: nothing was writing to the idle task's stack, and no user code was running.

It is **not** cache-related, and it is **not** 3–4 days old (handoff §7). It is also **not** caused by the 16:08 `READ_GPIO` failure (that was 2 minutes earlier, host-side, and a guest panic cannot clear the Java `running` flag) and **not** caused by Ghost Tap's 16:10 `LOAD_FIRMWARE` (import only; it does not execute guest code).

## 7. What would confirm it (in order)

1. **Get the exact ELF and decode** — open `https://micropython.org/download/ESP32_GENERIC`, click **`[.elf and .map]`** on the **v1.28.0 (2026-04-06)** row; verify `sha256sum` equals `cc85d995e8b415bd66c20a7dea84b5117a08c05a3c0f9ea791bc29a9251487a1` (§4); then run the toolchain already on this machine:
   ```
   A2=~/.espressif/tools/xtensa-esp32-elf/esp-12.2.0_20230208/xtensa-esp32-elf/bin/xtensa-esp32-elf-addr2line
   $A2 -e <micropython-1.28.0.elf> -f -C 0x40091ccf 0x4020881a 0x40208870 0x400968dc
   ```
   (Use the printed addresses as-is — that is what `esp-idf-monitor` does; the `−3` convention is already baked into them. Expect frame 1 → `esp_cpu_wait_for_intr` / `xt_utils_wait_for_intr`.) The `.map` file pins the same function names by address range as a cross-check.
   - If the download-page route fails, rebuild from source instead: MicroPython tag **`v1.28.0`**, ESP-IDF **v5.5.1**, `cd micropython/ports/esp32 && make BOARD=ESP32_GENERIC` (the build produces `build-ESP32_GENERIC/micropython.elf`); then verify the rebuilt ELF's SHA256 against §4 — **only accept it if the hash matches** (a rebuild that doesn't match means different flags/submodules and must not be used for the decode).
2. **Reproduce with the FULL register dump** — boot the same `.bin` in VirtualESP32, leave it idle at the `>>>` prompt, capture the complete Core 1 register dump (PC, A0, A1/SP, PS, SAR, LBEG/LEND) when/if it panics again, and note the time-to-crash and what (if anything) was happening just before. The full dump decides between "RETW returned to A0 = 0" (A0 = 0) and "wake-up delivered a bad PC with A0 intact" (A0 = `0x4020881d`).
3. **Hardware control run** — flash the identical `ESP32_GENERIC-20260406-v1.28.0.bin` to a real ESP32 board, leave it idle at the REPL for well past 5 minutes. If it never panics on hardware but does under QEMU, the defect is in the emulator; if it panics on hardware too, it is a guest/ESP-IDF issue and the ELF decode (§6 step 1) names the exact function to fix.
4. Do **not** rebuild VirtualESP32 or change anything to "fix" this crash until steps 1–3 are done — the cause is undiagnosed at the code level, and the crash is in guest idle code, not in the app.

## 8. Blocker (exact)

- **Missing:** the MicroPython app ELF (and `.map`) for `ESP32_GENERIC-20260406-v1.28.0`, SHA256 `cc85d995e8b415bd66c20a7dea84b5117a08c05a3c0f9ea791bc29a9251487a1`.
- **Where it should come from:** micropython.org download page for ESP32_GENERIC, v1.28.0 (2026-04-06) row, `[.elf and .map]` link — confirmed published by the download pages, exact href not retrieved here because the page fetch failed (browser-service error) and search did not surface the direct URL. Fallback: rebuild from MicroPython tag `v1.28.0` + ESP-IDF v5.5.1 (see step 1 above), gated on the SHA256 match.
- **What is ready:** the Xtensa toolchain is already on this machine (`xtensa-esp32-elf-addr2line`, GNU addr2line crosstool-NG esp-12.2.0, v2.39.0), the exact decode command is in §7 step 1, and the extracted code segments are at `~/workspace/tmp/vesp32-crash/seg2-irom.bin` / `seg4-iram.bin`.
- **What was NOT done (and why):** no addr2line decode against the four local `.elf` files as if they were the answer — they are different firmware and decode to `??:0`; no URL was fabricated for the micropython.org ELF.
