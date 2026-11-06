# Rebuilding libesp32_qemu_android.so — the real path (read this first)

The `.so` in `app/src/main/jniLibs/arm64-v8a/` is a PREBUILT. The Android build
(`app/build.gradle`) has NO `externalNativeBuild` / NDK block, so `CMakeLists.txt`
in this directory is NEVER invoked by the workflow build. Do not add a CMake
block expecting it to reproduce this `.so` — the CMake file defaults to a STUB
build and the shipped library was not built by it.

## Sources of record (this directory)

- `esp32_qemu_jni.c` (332 lines) — the recovered TRUE source (288-line original
  plus the `nativeGetSessionState` getter). The 217-line copy that used to be
  here was a stale revision (3-arg `nativeStart`, no GPIO natives) and must
  never be used to rebuild the `.so`.
- `qemu_session.h` — 3 path buffers (`flash_path`, `efuse_path`, `rom_path`),
  `qemu_state_t` 0–4.
- `qemu_driver_real.c` (220 lines) — the recovered driver (exit-interceptor,
  stderr capture, `-L rom_path`, `-net none`).
- `esp32_rsa_stub.c` — linked into the shipped `.so` when libgcrypt is
  unavailable (it is, on Android).
- `CMakeLists.txt`, `qemu/android/` (BUILD.md, ATTEMPT_LOG.md,
  build-qemu-android.sh, patches 0001–0007) — port docs and patches, kept for
  reference. BUILD.md's CMake flow is NOT how the shipped `.so` was built.

## How the shipped .so is actually built

The JNI target lives INLINE in the top-level QEMU `meson.build` (~line 4300),
inside the `xtensa-softmmu` emulator block: sources
`android-jni/esp32_qemu_jni.c` + `android-jni/qemu_driver_real.c` (+
`android-jni/esp32_rsa_stub.c` when libgcrypt is unavailable),
`c_args` + `-DQEMU_CORE_AVAILABLE`, linked against the same QEMU objects as
the emulator. The standalone `android-jni/meson.build` does NOT describe this
build (it links a `libqemu_xtensa_softmmu.a` that does not exist) — do not use
it.

Rebuild (from the QEMU tree on the build machine):

```bash
# prerequisites: ninja + meson (pip install ninja meson), NDK r27c
# the build.ninja qemu-version.h step hardcodes /usr/local/bin/meson —
# symlink it to the meson in your venv if it is missing there.
cd ~/workspace/virtualesp32/native/qemu-src/build
ninja libesp32_qemu_android.so
```

If you changed `android-jni/esp32_qemu_jni.c`, swap your copy in, rebuild,
verify, then restore the original file (hash `c73ba51c…`) — the tree's source
files must stay pristine; only regenerated build outputs change.

## Verify a rebuilt .so

```bash
llvm-nm -D --defined-only libesp32_qemu_android.so | grep Java_
# must show 7 entries: nativeStart, nativeReset, nativeStop,
# nativeGetLastError, nativeGetGpio2, nativeSetGpio4, nativeGetSessionState
```

The rebuilt `.so` (with the getter) is 81,361,464 bytes, SHA256
`6a1fde2ceea5d9c7c9802fea161824b40dbfb392266aae2a72d2708833694894`. It is
intentionally NOT byte-identical to the previous shipped `.so`
(`207d50ac…`) — the source changed. A `.so` built from the stale 217-line
JNI source would break START outright (3-arg `nativeStart` misreads the port)
and drop both GPIO natives — never ship one.
