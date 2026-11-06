# Muse → ChatGPT execution request: VirtualESP32 only

**Target branch:** `muse/virtualesp32-chatgpt-handoff`
**Do not touch:** `main`, Ghost Tap source/build, either installed app.
**Do not install or deploy anything to the user's phone.**

ChatGPT inspected this branch directly. It currently contains AndroidForge's build harness, handoff documents, and five recovered native C/header files. It does **not** contain the full `virtual-esp-32/` Android project or the authoritative source ZIP. Therefore ChatGPT cannot yet safely patch the Java lifecycle code or build VirtualESP32 from this branch.

## 1. Put the authoritative project on this branch
Add the exact source used for the currently installed VirtualESP32 APK:
- Preferred archive: `virtualesp32-merged-bridge9-repaired.zip`
- Expected size: 25,802,041 bytes
- Expected SHA-256: `024551aab2dfe64c6c721c9f7881ae91dc0d894f79b79322d5061314985b5ea0`

Either commit the archive under `input/` or commit its complete extracted project tree under a clearly named directory. Preserve the existing packaged native library and ROM assets. Do not substitute `virtualesp32-final.zip`, `virtualesp32-complete.zip`, or any Ghost Tap ZIP. Record the exact source hash in a manifest.

The recovered native source files already in `virtualesp32-native/android-jni/` must be compared byte-for-byte against the corresponding files in the project tree. Keep the project-tree copy and recovered copy synchronized, or document any intentional difference.

## 2. Diagnose D1 before changing guest execution
The Nov 5 field evidence is current: MicroPython v1.28.0 reached `>>>` at 16:05, then at 16:10 the guest raised `EXCCAUSE 0x00000007`, `EXCVADDR 0x00000000`, and backtrace:
- `0x40091ccf`
- `0x4020881a`
- `0x40208870`
- `0x400968dc`

The crash log identifies ELF SHA-256 prefix `cc85d995e`. Obtain the exact matching ELF/map pair and symbolicate those addresses with the Xtensa `addr2line` toolchain. Commit the command, exact ELF/map hashes, and decoded function/file/line results. Do not label this a cache issue or change firmware/QEMU behavior without evidence.

## 3. Patch the verified host-side defects in the Java project
After source is present, inspect and make narrowly scoped fixes for:
- **D3:** reconcile Java/UI runtime state with native QEMU liveness. Add a native state getter if required, with safe synchronization and explicit STARTING/RUNNING/STOPPING/STOPPED/ERROR semantics. Never report RUNNING after the worker exits.
- **D4:** distinguish receiver RESET (full stop/restart) from UI soft reset (QEMU reset request); return explicit errors if no active backend exists.
- **D5:** register UART_RX forwarding once at application/process startup; prevent duplicate listeners and ensure it is active before receiver START/RESET commands.
- **D6:** make reset fail loudly when no runtime is active; revalidate firmware immediately before start; define duplicate-start behavior.
- **D7:** make service READ_GPIO return a truthful unavailable/error result rather than silently returning `-1` as a successful read.

Keep the already field-verified content-URI LOAD_FIRMWARE IPC repair intact. Do not regress the working Ghost Tap → VirtualESP32 URI handoff.

Add/update unit tests for state transitions, duplicate start, reset-without-runtime, and GPIO-unavailable behavior where test seams permit. Record tests actually run; do not report unrun tests as passing.

## 4. Create a dedicated VirtualESP32 CI build path
The existing `.github/workflows/build-android.yml` is titled **Build Ghost Tap**, hardcodes Ghost Tap artifact naming, and its automatic push trigger only watches `main`. Do not use it to build this branch as-is.

Add a separate workflow, e.g. `.github/workflows/build-virtualesp32.yml`, that:
- supports `workflow_dispatch` and push to this handoff branch only;
- builds the VirtualESP32 Android project (not the Ghost Tap project);
- runs the available unit tests before assembling debug APK;
- uploads artifact named `VIRTUALESP32-APK`;
- emits APK SHA-256, file size, package name, version name/code, and native library hash;
- checks the APK contains the expected `dev.virtualesp32` classes, ARM64 QEMU library, ROM assets, and repaired IPC strings.

Do not make any workflow push to or build from `main`. Do not run the existing Ghost Tap workflow.

## 5. Deliverable
Commit the full source, targeted fixes, tests, dedicated workflow, and a concise `VIRTUALESP32_BUILD_REPORT.md` to this branch. Report exact commit SHA and CI run URL/status. A green compile is not proof of guest stability; no phone install or deployment is authorized by this request.

**Important:** ChatGPT has write access to this branch and will continue review/build orchestration once the complete source tree and report are committed.