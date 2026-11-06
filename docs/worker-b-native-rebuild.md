# Worker B — Native rebuild for VirtualESP32 (D3 native side)

**Date:** 2026-11-06 ~01:00 PST (work run 2026-11-06 00:57–01:01 PST)
**Work dir:** `~/workspace/tmp/vesp32-fix-b/`
**Verdict: REBUILD SUCCEEDED.** `libesp32_qemu_android.so` rebuilt from the recovered
config with `nativeGetSessionState` included. All 6 original JNI symbols intact +
the new getter = **7 JNI entries, verified by `nm -D` and by disassembly**.
Ghost Tap was not touched. The stale Bridge9 217-line JNI source was not used for
anything. Nothing was installed or deployed.

## Deliverables (in the work dir)

| Item | Path |
|---|---|
| Modified JNI source (332 lines; only file changed) | `~/workspace/tmp/vesp32-fix-b/esp32_qemu_jni.c` |
| Unchanged recovered copies (reference) | `~/workspace/tmp/vesp32-fix-b/qemu_session.h`, `qemu_driver_real.c`, `esp32_rsa_stub.c`, `meson.build` |
| Rebuilt `.so` | `~/workspace/tmp/vesp32-fix-b/libesp32_qemu_android.so` |
| Backups of the originals | `~/workspace/tmp/vesp32-fix-b/backup/libesp32_qemu_android.so.orig`, `backup/esp32_qemu_jni.c.orig` |
| This report | `~/workspace/tmp/vesp32-fix-b-report.md` |
| Build logs / evidence | `control-build.log`, `rebuild.log`, `build-ninja-so-rules.txt`, `qemu_driver_real.c.zip-copy` |
| Toolchain used for the build | `~/workspace/tmp/vesp32-fix-b/venv/` (ninja 1.13.2 + meson 1.12.1, pip-installed; see §5) |

## 1. Hashes and sizes (old vs new `.so`)

| | Original (shipped / build-dir copy) | Rebuilt (with getter) |
|---|---|---|
| SHA256 | `207d50ac9711d89f9c40350fb5151ad966170efe377f3de2078bbb2e89ea4a75` | `6a1fde2ceea5d9c7c9802fea161824b40dbfb392266aae2a72d2708833694894` |
| Size | 81,361,024 bytes | 81,361,464 bytes (+440) |
| ELF | ELF64, DYN, AArch64, `with debug_info, not stripped` | same |
| NEEDED | `liblog.so libm.so libz.so libdl.so libc.so` | same (unchanged) |

The original build-dir `.so` hash matched the shipped `.so` exactly before I
started (`207d50ac…`, byte-identical to the ZIP and APK copies). The rebuilt
`.so` is intentionally NOT byte-identical — source changed, as expected.
The rebuilt `.so` in the work dir and the one now in
`~/workspace/virtualesp32/native/qemu-src/build/libesp32_qemu_android.so` have
the same hash (`6a1fde2c…`).

## 2. `nm -D --defined-only` — JNI symbols

Original `.so` (6 JNI entries):

```
0000000000f0c6d0 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeStart
0000000000f0cb58 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeReset
0000000000f0cbb4 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeStop
0000000000f0cc28 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetLastError
0000000000f0ccc8 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetGpio2
0000000000f0ccd0 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeSetGpio4
```

Rebuilt `.so` (7 JNI entries — all 6 originals present, addresses shifted slightly
because of the added code, plus the getter):

```
0000000000f0c6d0 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeStart
0000000000f0cb78 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeReset
0000000000f0cbd4 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeStop
0000000000f0cc84 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetLastError
0000000000f0cd24 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetGpio2
0000000000f0cd2c T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeSetGpio4
0000000000f0cd40 T Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetSessionState
```

The 4-arg `nativeStart` signature is unchanged (the rebuilt `nativeStart` is still
at `0xf0c6d0`, same entry as the original). Total dynamic symbols in the rebuilt
`.so`: 14,321 defined (`T`) entries counted via `llvm-nm -D --defined-only`
(the original exports the whole QEMU symbol table the same way).

Disassembly sanity check on the getter (`llvm-objdump -d
--disassemble-symbols=…nativeGetSessionState`): real code, not a stub — it locks
`g_session_lock`, loads `g_live_session`, returns `-1` (`mov w19, #-0x1`) when
NULL, otherwise locks the session mutex, loads `s->state` at struct offset
`0x30` (correct: `thread` 0x0–0x8, `lock` 0x8–0x30, `state` 0x30), unlocks both,
returns the value.

## 3. What was changed in the source (work-dir copy only)

`esp32_qemu_jni.c`: 288 lines → 332 lines. `diff` against the recovered original
shows only additive changes plus the header-comment correction:

1. **Header comment fixed (lines ~3–7):** the stale 3-arg contract
   `nativeStart(flashImage, efuseImage, uartPort)` corrected to the real 4-arg
   contract `nativeStart(flashImage, efuseImage, romDir, uartPort)`; the comment
   now also lists `nativeGetGpio2`, `nativeSetGpio4`, and
   `nativeGetSessionState` with its return-value contract.
2. **Prototype added** (required by QEMU's `-Wmissing-prototypes -Werror`):
   `Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetSessionState(JNIEnv *env, jclass clazz)`.
3. **Live-session tracking:** `static qemu_session_t *g_live_session = NULL;` plus
   `static pthread_mutex_t g_session_lock`. Set in `nativeStart` on success only
   (after `qemu_driver_start` returns 0); cleared in `nativeStop` (only if it
   still points at the session being stopped) before the session mutex is
   destroyed and the session freed. The getter takes no handle by design — the
   fixed contract declares no handle argument, mirroring `nativeGetGpio2` /
   `nativeSetGpio4`, which also take none and read global QEMU state. Only one
   session may exist per process, and the Java layer already enforces that.
4. **Getter implementation** (appended at end of file): returns `(jint)s->state`
   (`0 STARTING, 1 RUNNING, 2 STOPPING, 3 STOPPED, 4 ERROR` per `qemu_state_t`
   in `qemu_session.h`), or `-1` when `g_live_session` is NULL (no live
   session). Thread-safety: the getter reads `s->state` while holding
   `g_session_lock`, and `nativeStop` clears the pointer under the same lock
   before freeing, so the session can never be freed underneath a read. Lock
   order is always `g_session_lock` → `s->lock`; no other path holds `s->lock`
   while acquiring `g_session_lock`, so no deadlock. The stub case needs no
   special handling: without `QEMU_CORE_AVAILABLE`, `nativeStart` returns 0
   and `g_live_session` is never set, so the getter returns `-1`.

No other native file was changed. `qemu_session.h` (3 path buffers), the
recovered `qemu_driver_real.c` (220 lines), and `esp32_rsa_stub.c` are used
exactly as recovered.

## 4. Rebuild path — how the `.so` is actually built

The correct rebuild path is **ninja on the existing `build.ninja` in the
original build dir** — NOT a meson reconfigure, NOT the standalone
`android-jni/meson.build`, NOT CMake. Findings:

- The `.so` target is defined **inline in the top-level**
  `~/workspace/virtualesp32/native/qemu-src/meson.build` (~line 4300), inside the
  `xtensa-softmmu` emulator block: sources `android-jni/esp32_qemu_jni.c` +
  `android-jni/qemu_driver_real.c` (+ `android-jni/esp32_rsa_stub.c` when
  libgcrypt is unavailable — it is, on Android), `c_args` + `-DQEMU_CORE_AVAILABLE`,
  linked against the same QEMU objects as the emulator (`objects:
  lib.extract_all_objects(recursive: true)`), `-llog`. That is exactly what
  `build.ninja` does (3 `.o` files in `libesp32_qemu_android.so.p/`, link via
  `c_LINKER_RSP` with `-shared -fPIC -Wl,-soname,libesp32_qemu_android.so
  -Wl,--no-undefined` etc.).
- The standalone `android-jni/meson.build` does **NOT** describe the shipped
  build: it lists only 2 sources (no RSA stub) and links
  `libqemu_xtensa_softmmu.a`, which **does not exist** anywhere in the build
  tree. Nothing references it (`subdir('android-jni')` appears nowhere; the
  top-level meson.build inlines the target instead). Do not use it to rebuild.
- The original configure command (from `build/config.status` and
  `build/config.log`, Oct 9 2026) was:
  `configure --cross-prefix=…/ndk-wrappers/aarch64-linux-android-
  --cc=…/android-ndk-r27c/…/bin/aarch64-linux-android26-clang --host-cc=gcc
  --target-list=xtensa-softmmu --enable-tcg-interpreter --disable-tools
  --disable-docs --disable-guest-agent --disable-gtk --disable-sdl --disable-vnc
  --disable-spice --disable-slirp --disable-capstone --disable-gcrypt
  --disable-nettle --disable-virtfs --extra-cflags/ldflags (NDK sysroot +
  ~/workspace/virtualesp32/deps-arm64-v8a) -Db_staticpic=true`.
  No reconfigure was needed or performed — the existing `build.ninja` is valid.
- NDK r27c confirmed at `~/workspace/virtualesp32/native/android-ndk-r27c`
  (compiler `aarch64-linux-android26-clang` present; sysroot used by the build).
- `ninja -n libesp32_qemu_android.so` (dry run, before any change) showed the
  tree was up to date except the always-dirty `qemu-version.h` generator step.

### Blockers hit during the rebuild (both resolved — rebuild is NOT blocked)

1. **`ninja` and `meson` were not installed** (not on PATH; no `ninja`/`mesonbuild`
   Python modules). Fixed by creating a venv in the work dir and
   `pip install ninja meson` (ninja 1.13.2, meson 1.12.1; network available).
2. **First control build failed:** `FAILED: [code=127] qemu-version.h —
   /usr/local/bin/meson: not found`. The `qemu-version.h` generator step in
   `build.ninja` hardcodes `/usr/local/bin/meson`. Fixed by symlinking
   `/usr/local/bin/meson -> <work-dir>/venv/bin/meson` (I am root; `/tmp` is
   100% full on this machine, so all scratch stayed in the workspace).
   Re-running the control build then succeeded (exit 0). Note: `qemu-version.h`
   is regenerated every run but its content is unchanged (`QEMU_FULL_VERSION
   "9.2.2"`), so ninja's restat clears the dependent steps — the control build
   did no recompiles and left the original `.so` byte-identical (hash still
   `207d50ac…`, mtime Oct 10 12:59). The `fatal: No names found, cannot describe
   anything.` line in the log is `git describe` failing inside
   `qemu-version.sh` (no tags in the QEMU checkout); it is harmless noise, the
   step still exits 0.

### Commands run (in order)

```bash
# 1. Copies (recovered android-jni -> work dir); hash check: esp32_qemu_jni.c = c73ba51c… (matches stated recovered hash)
cp ~/workspace/virtualesp32/native/qemu-src/android-jni/{esp32_qemu_jni.c,qemu_session.h,qemu_driver_real.c,meson.build,esp32_rsa_stub.c} ~/workspace/tmp/vesp32-fix-b/
sha256sum ~/workspace/tmp/vesp32-fix-b/*
# 2. Original .so symbols (NDK llvm-nm): 6 Java_ entries; readelf: ELF64 DYN AArch64
~/workspace/virtualesp32/native/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm -D --defined-only ~/workspace/virtualesp32/native/qemu-src/build/libesp32_qemu_android.so | grep Java_
# 3. Ninja dry run (no changes yet)
cd ~/workspace/virtualesp32/native/qemu-src/build && ~/workspace/tmp/vesp32-fix-b/venv/bin/ninja -n libesp32_qemu_android.so
# 4. Toolchain: python3 -m venv ~/workspace/tmp/vesp32-fix-b/venv && venv/bin/pip install ninja meson
#    ln -sf ~/workspace/tmp/vesp32-fix-b/venv/bin/meson /usr/local/bin/meson
# 5. Backups (hashes verified: .so 207d50ac…, JNI c73ba51c…)
cp ~/workspace/virtualesp32/native/qemu-src/build/libesp32_qemu_android.so ~/workspace/tmp/vesp32-fix-b/backup/libesp32_qemu_android.so.orig
cp ~/workspace/virtualesp32/native/qemu-src/android-jni/esp32_qemu_jni.c ~/workspace/tmp/vesp32-fix-b/backup/esp32_qemu_jni.c.orig
# 6. CONTROL rebuild, original sources (first attempt FAILED on missing /usr/local/bin/meson; after the symlink, exit 0, .so unchanged)
cd ~/workspace/virtualesp32/native/qemu-src/build && ~/workspace/tmp/vesp32-fix-b/venv/bin/ninja libesp32_qemu_android.so
# 7. Swap modified JNI in (hash 2eacfef1…), rebuild: exit 0 in ~7 s — [2/3] compile JNI object, [3/3] link
cp ~/workspace/tmp/vesp32-fix-b/esp32_qemu_jni.c ~/workspace/virtualesp32/native/qemu-src/android-jni/esp32_qemu_jni.c
cd ~/workspace/virtualesp32/native/qemu-src/build && ~/workspace/tmp/vesp32-fix-b/venv/bin/ninja libesp32_qemu_android.so
#    -> new .so 81,361,464 bytes, SHA256 6a1fde2c…
# 8. Verify new .so: llvm-nm -D (7 Java_ entries), llvm-readelf -h/-d (ELF64 DYN AArch64, same NEEDED libs),
#    llvm-objdump -d --disassemble-symbols=…nativeGetSessionState (real code, -1 path present)
# 9. Copy rebuilt .so to work dir; RESTORE original JNI; verify restore hash c73ba51c… and 0 mentions of nativeGetSessionState in the tree file
cp ~/workspace/virtualesp32/native/qemu-src/build/libesp32_qemu_android.so ~/workspace/tmp/vesp32-fix-b/libesp32_qemu_android.so
cp ~/workspace/tmp/vesp32-fix-b/backup/esp32_qemu_jni.c.orig ~/workspace/virtualesp32/native/qemu-src/android-jni/esp32_qemu_jni.c
sha256sum ~/workspace/virtualesp32/native/qemu-src/android-jni/esp32_qemu_jni.c
# 10. ZIP/cpp reconciliation: unzip -l …/virtualesp32-merged-bridge9-repaired.zip; unzip -p …/app/build.gradle; diffs of ZIP cpp files vs recovered
```

### Restore verification

- `~/workspace/virtualesp32/native/qemu-src/android-jni/esp32_qemu_jni.c` is
  byte-identical to the recovered original again: SHA256
  `c73ba51c8135f7e683d49f0ada035df520cd4f5e59a81fc9a96aee039116c01a`, 288 lines,
  0 occurrences of `nativeGetSessionState`.
- The only changes left in the original tree are regenerated build outputs:
  the rebuilt `.so` (now `6a1fde2c…`) and its `.o` in
  `build/libesp32_qemu_android.so.p/`, plus `.ninja_log`/`.ninja_deps`. I
  deliberately left the rebuilt `.so` in `build/` (coherent with the rebuilt
  `.o`); the original `.so` is preserved in `backup/` and in the ZIP/APK.
  `git status` in `qemu-src` shows only the pre-existing port modifications
  (e.g. `M hw/gpio/esp32_gpio.c`, `M meson.build`, untracked `android-jni/`),
  none of which I touched.

## 5. Host load test — what was done, and its limit

- **Host `dlopen` attempted** (`python3 ctypes.CDLL` on the rebuilt `.so`):
  failed with `OSError: … cannot open shared object file: No such file or
  directory`. This is the expected outcome on this host, not a defect in the
  `.so`: it is an AArch64 Android library (needs `liblog.so` etc., which do not
  exist on this x86_64 Linux host) and there is no `qemu-aarch64` installed
  to run it under. No Android device/emulator is available in this sandbox.
- **Checks actually performed instead (recorded):** `file` (ELF 64-bit LSB
  shared object, ARM aarch64, dynamically linked, with debug_info, not
  stripped — same as shipped); `llvm-readelf -h` (ELF64/DYN/AArch64);
  `llvm-readelf -d` (identical NEEDED list to the shipped `.so`);
  `llvm-nm -D` (7 JNI entries, all `T` defined); `llvm-objdump` disassembly
  of the getter (real code, correct `-1` path and state load at offset `0x30`).
- **Not proven here:** runtime behaviour on a device (START → getter returns 1
  RUNNING; STOP → getter returns -1). That needs the field-test gate on
  Justin's phone after Worker A wires the Java side. Green/rebuilt here means
  "compiles and links with the right symbols", same honesty bound as before.

## 6. Final `cpp/` file list for the corrected tree

Reconciled from the repaired ZIP's `virtual-esp32/app/src/main/cpp/` (12 entries)
against the recovered sources and the real build (`build.ninja`):

| File | Decision |
|---|---|
| `esp32_qemu_jni.c` | **Replace** stale 217-line ZIP copy (3-arg `nativeStart`, no GPIO) with the recovered 288-line file **plus the new getter (332 lines)** — this work dir's `esp32_qemu_jni.c`. |
| `qemu_session.h` | **Replace** stale ZIP copy (612 bytes, 2 path buffers) with the recovered 3-buffer version (3 × 4096 path buffers, `qemu_state_t` 0–4). |
| `qemu_driver_real.c` | **Replace** ZIP copy (153 lines) with the recovered 220-line version. Verified different (SHA ZIP `b2a875df…` vs recovered `44b6e169…`): the ZIP copy lacks the exit-interceptor/`setjmp` handling, stderr capture, `-L rom_path`, and `-net none` — the recovered one is the code that built the shipped `.so`. |
| `esp32_rsa_stub.c` | **Add** — absent from the ZIP's `cpp/`, but compiled and linked into the shipped `.so` (present in `build.ninja` / top-level `meson.build` when libgcrypt is unavailable, which it is on Android). |
| `CMakeLists.txt` | **Keep** as-is (documents the STUB/FULL CMake path). **Not invoked by the Android build** — see below. |
| `qemu/android/BUILD.md`, `ATTEMPT_LOG.md`, `build-qemu-android.sh`, `patches/0001–0007` | **Keep** (port docs + patches). Note: `BUILD.md`'s `install-arm64-v8a/` + CMake flow is **not** how the shipped `.so` was built; the shipped `.so` came from the in-tree meson build in `qemu-src/build/` (§4). `BUILD.md` should gain a line saying so when the corrected tree is written up — I did not edit the ZIP. |
| `android-jni/meson.build` (recovered) | **Do NOT carry into `cpp/` as-is.** It describes a link against `libqemu_xtensa_softmmu.a` that does not exist and omits the RSA stub; the real JNI target lives inline in the top-level `qemu-src/meson.build` (~line 4300). If it is kept anywhere, it must be fixed or labelled stale, or the corrected tree will mislead the next rebuild. |

### `app/build.gradle` — `externalNativeBuild` check (verified)

Read `virtual-esp32/app/build.gradle` from the repaired ZIP (24 lines). **There
is NO `externalNativeBuild` / NDK block.** The file states it explicitly in a
comment: *"The NDK/CMake native build (libesp32_qemu_android) is NOT configured
here. The GitHub Actions workflow builds the Java/Kotlin app only."* and *"To
build WITH the native QEMU backend locally, see app/src/main/cpp/qemu/android/BUILD.md
and add the externalNativeBuild cmake block manually."* So `CMakeLists.txt` is
**not invoked by the Android build**; the `.so` is a prebuilt in `jniLibs/`.
The corrected `.so` must therefore be dropped into `jniLibs/arm64-v8a/` by
whoever assembles the corrected APK — no Gradle change is needed for the
native side (Worker A owns the Java side).

## 7. Fallback — not needed

The rebuild is **not blocked**, so no fallback is invoked. Had it been blocked
(e.g. no working ninja/meson, or the link failing on a missing QEMU object),
the recommendation would have been: **ship the verified original `.so`
(SHA256 `207d50ac…`) and have the Java side fall back** — i.e. Worker A's
`nativeGetSessionState` declaration must catch `UnsatisfiedLinkError` and treat
the native state as unknown, never claiming RUNNING/STOPPED from a missing
symbol. Do not silently ship a `.so` built from the stale 217-line source: it
would break START outright (3-arg `nativeStart` misreading the port) and drop
both GPIO natives.

## 8. Notes for Worker A / integration

- Exact JNI name: `Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetSessionState`,
  `(JNIEnv *env, jclass clazz) -> jint`. No handle argument — the getter finds
  the live session via the internal `g_live_session` pointer.
- Return values: `0 STARTING, 1 RUNNING, 2 STOPPING, 3 STOPPED, 4 ERROR`
  (matches `qemu_state_t` in `qemu_session.h`); `-1` = no live session
  (never started, or already stopped/freed).
- Timing note: between `nativeStart` returning a handle and the worker thread
  flipping state to RUNNING, the getter returns `0` (STARTING) — that is the
  honest value during the up-to-5 s init window; the Java side should not treat
  STARTING as failure.
- After `nativeStop`, the getter returns `-1`, not `3` (STOPPED): the session
  object is freed on stop, so there is no live session to report STOPPED for.
  If Worker A wants a persistent STOPPED reading, that has to live in Java,
  not in this getter.
- The getter is safe to call from any thread (including the 250 ms GPIO
  monitor thread) and safe to call when no session exists.
- Rebuild reproducibility for the next engineer: the venv at
  `~/workspace/tmp/vesp32-fix-b/venv/` plus the `/usr/local/bin/meson` symlink
  are the only new tools this rebuild needed. `/usr/local/bin` is ephemeral
  outside `~`; if the VM resets, re-run `python3 -m venv <dir>/venv &&
  <dir>/venv/bin/pip install ninja meson && ln -sf <dir>/venv/bin/meson
  /usr/local/bin/meson`, then `cd qemu-src/build && <venv>/bin/ninja
  libesp32_qemu_android.so` after swapping in the modified JNI source and
  restoring it afterwards (hash check `c73ba51c…`).
